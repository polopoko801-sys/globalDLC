package thunder.hack.utility.spotify;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Every call to Spotify goes through here, and none of them touch the render thread.
 *
 * <p>The widget is drawn sixty times a second and the API answers in tens or hundreds of milliseconds,
 * so the two can never meet. Drawing code only ever reads a value that is already in memory; this class
 * is what puts it there, on its own small pool of daemon threads.
 *
 * <p>Four things it does besides sending bytes:
 *
 * <ul>
 *   <li><b>Deduplication.</b> Identical in-flight requests share one answer. Without it, a widget that
 *       polls and a panel that lists the same thing would each ask separately, and every mutation would
 *       be sent twice because two components both reacted to the same click.
 *   <li><b>Caching.</b> Each GET names its own freshness. Playlists do not change between two frames,
 *       the player does; one TTL for everything would either show stale playback or re-fetch a hundred
 *       playlist covers a second.
 *   <li><b>Retry with backoff.</b> A 429 is obeyed to the second Spotify asks for, a 5xx is retried
 *       three times with growing gaps, and a 401 renews the token once and retries immediately. A 4xx
 *       that is none of those is final - retrying a bad request only spends your rate limit.
 *   <li><b>Cancellation.</b> {@link Gate} is how a search that has been superseded is dropped.
 * </ul>
 */
public final class SpotifyApi {
    private SpotifyApi() {
    }

    public static final String BASE = "https://api.spotify.com/v1";

    private static final AtomicInteger THREADS = new AtomicInteger();

    /**
     * Three threads, and no more.
     *
     * <p>This is a background chore inside a game. A larger pool would not make Spotify answer faster -
     * it rate-limits per account, not per connection - it would only let a burst of stale work compete
     * with the game for CPU. The queue is bounded so a disconnected network cannot grow it without end;
     * a rejected task is reported as a failure, which the UI already knows how to show.
     */
    private static final ExecutorService POOL = new ThreadPoolExecutor(
            1, 3, 30L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(96),
            runnable -> {
                Thread thread = new Thread(runnable, "Gombal-Spotify-" + THREADS.incrementAndGet());
                thread.setDaemon(true);
                // Below normal: a cover download must never be the reason a frame is late.
                thread.setPriority(Thread.NORM_PRIORITY - 2);
                return thread;
            },
            // Abort, not discard. A discarded task leaves its CompletableFuture uncompleted forever, and
            // anything waiting on that future waits for the rest of the session; an abort throws, which
            // dispatch below turns into an ordinary failed Result.
            new ThreadPoolExecutor.AbortPolicy());

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(6))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    /** One answer. A failed call still carries its status, which is what decides whether to retry. */
    public record Result(int status, JsonElement body, String error) {
        public boolean ok() {
            return status >= 200 && status < 300;
        }

        public JsonObject obj() {
            return body != null && body.isJsonObject() ? body.getAsJsonObject() : new JsonObject();
        }

        static Result failed(String error) {
            return new Result(0, JsonNull.INSTANCE, error);
        }
    }

    /* ------------------------------------------------------------------ */
    /* Cancellation                                                        */
    /* ------------------------------------------------------------------ */

    /**
     * A generation counter, so an answer that is no longer wanted is thrown away.
     *
     * <p>Interrupting the socket would be the other way to do this, and it is worse: the request has
     * usually already been sent by the time the user types the next letter, so aborting it saves nothing
     * and costs a connection teardown. What matters is that the stale answer never reaches the UI, which
     * is exactly what this checks. The bounded pool above is what stops the abandoned work piling up.
     */
    public static final class Gate {
        private final AtomicInteger generation = new AtomicInteger();

        /** Opens a new generation, retiring every one before it. */
        public int bump() {
            return generation.incrementAndGet();
        }

        public boolean current(int ticket) {
            return generation.get() == ticket;
        }
    }

    /* ------------------------------------------------------------------ */
    /* Cache and dedup                                                     */
    /* ------------------------------------------------------------------ */

    private record Cached(Result value, long stamp) {
    }

    private static final Map<String, Cached> CACHE = new ConcurrentHashMap<>();
    private static final Map<String, CompletableFuture<Result>> INFLIGHT = new ConcurrentHashMap<>();

    /** The last failure worth showing, so a panel can say why it is empty. */
    private static volatile String lastError = "";
    /** When the whole API is rate limited until, so nothing even tries in the meantime. */
    private static final AtomicLong limitedUntil = new AtomicLong();

    public static String lastError() {
        return lastError;
    }

    public static boolean rateLimited() {
        return limitedUntil.get() > System.currentTimeMillis();
    }

    /** Drops cached GETs whose path starts with this, after a mutation makes them wrong. */
    public static void invalidate(String prefix) {
        CACHE.keySet().removeIf(key -> key.startsWith("GET " + prefix));
    }

    public static void clearCache() {
        CACHE.clear();
        INFLIGHT.clear();
        lastError = "";
    }

    /* ------------------------------------------------------------------ */
    /* Calls                                                               */
    /* ------------------------------------------------------------------ */

    /**
     * A GET, answered from cache when the cached copy is younger than {@code ttlMs}.
     *
     * <p>A zero TTL still goes through deduplication, so "always fresh" does not mean "once per caller".
     */
    public static CompletableFuture<Result> get(String path, long ttlMs) {
        String key = "GET " + path;

        Cached cached = CACHE.get(key);
        if (cached != null && System.currentTimeMillis() - cached.stamp() < ttlMs)
            return CompletableFuture.completedFuture(cached.value());

        return dispatch("GET", path, null, ttlMs);
    }

    /** A call that changes something. Never cached, and it never serves a deduplicated older answer. */
    public static CompletableFuture<Result> send(String method, String path, String body) {
        return dispatch(method, path, body == null ? "" : body, 0L);
    }

    /**
     * A command whose answer nobody reads - play, pause, seek, volume.
     *
     * <p>The UI has already moved: pressing pause flips the local snapshot at once and the poll that
     * follows confirms it. So the only thing left to do with the answer is remember a failure, which is
     * what {@link #lastError()} is for.
     */
    public static void command(String method, String path, String body) {
        send(method, path, body).thenAccept(result -> {
            if (!result.ok()) lastError = describe(result);
        });
    }

    /**
     * One request, or the one already in flight for the same thing.
     *
     * <p>Written with an explicit future rather than {@code supplyAsync} + {@code computeIfAbsent} for two
     * reasons that both bite in practice. The future is published <i>before</i> the task is submitted, so
     * a rejection can complete it rather than leaving a caller waiting on something that will never run.
     * And the in-flight entry is removed by the task itself, so a failure cannot leave a permanent entry
     * that makes every later call to the same path return the old failure.
     */
    private static CompletableFuture<Result> dispatch(String method, String path, String body, long ttlMs) {
        String key = method + " " + path + (body == null || body.isEmpty() ? "" : " " + body.hashCode());

        CompletableFuture<Result> future = new CompletableFuture<>();
        CompletableFuture<Result> existing = INFLIGHT.putIfAbsent(key, future);
        // Someone else is already asking: a double click cannot become two POSTs.
        if (existing != null) return existing;

        try {
            POOL.execute(() -> {
                Result result;
                try {
                    result = perform(method, path, body);
                } catch (Throwable t) {
                    result = Result.failed(t.getClass().getSimpleName());
                }

                if (result.ok() && "GET".equals(method) && ttlMs > 0)
                    CACHE.put(key, new Cached(result, System.currentTimeMillis()));

                INFLIGHT.remove(key);
                future.complete(result);
            });
        } catch (RuntimeException rejected) {
            // The queue is full of work nobody is waiting for any more. Failing fast is right: the caller
            // draws its error state and the next frame asks again, by which time the pool has drained.
            INFLIGHT.remove(key);
            future.complete(Result.failed("kolejka zapytan pelna"));
        }

        return future;
    }

    /* ------------------------------------------------------------------ */
    /* The request itself                                                  */
    /* ------------------------------------------------------------------ */

    private static final int ATTEMPTS = 3;

    private static Result perform(String method, String path, String body) {
        long wait = limitedUntil.get() - System.currentTimeMillis();
        if (wait > 0) {
            // Already inside a 429 window. Sleeping here rather than failing keeps a button press from
            // being silently lost, and the window is seconds, not minutes.
            if (wait > 10_000L) return Result.failed("rate limited");
            sleep(wait);
        }

        String token = SpotifyAuth.token();
        if (token == null) return Result.failed("not logged in");

        boolean renewed = false;

        for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
            Result result = once(method, path, body, token);

            if (result.ok()) {
                lastError = "";
                return result;
            }

            // 401 on a token we believed was valid: it was revoked or rotated under us. Worth exactly
            // one renewal - a second failure means the login itself is gone.
            if (result.status() == 401 && !renewed) {
                renewed = true;
                String fresh = SpotifyAuth.renew();
                if (fresh == null) return result;
                token = fresh;
                continue;
            }

            if (result.status() == 429) {
                long retryAfter = parseRetryAfter(result.error());
                limitedUntil.set(System.currentTimeMillis() + retryAfter);
                if (attempt == ATTEMPTS) break;
                sleep(retryAfter);
                continue;
            }

            // 5xx and a dead socket (status 0) are the only other things worth repeating. Everything
            // else - a bad id, a missing scope, a device that will not take commands - fails the same
            // way every time, and retrying it only spends the rate limit.
            if (result.status() != 0 && result.status() < 500) return result;

            if (attempt == ATTEMPTS) break;
            sleep(400L * (1L << (attempt - 1)));
        }

        lastError = "spotify nie odpowiada";
        return Result.failed(lastError);
    }

    private static Result once(String method, String path, String body, String token) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(path.startsWith("http") ? path : BASE + path))
                    .header("Authorization", "Bearer " + token)
                    .timeout(Duration.ofSeconds(12));

            if (body == null) {
                builder.method(method, HttpRequest.BodyPublishers.noBody());
            } else {
                builder.header("Content-Type", "application/json");
                builder.method(method, HttpRequest.BodyPublishers.ofString(body));
            }

            HttpResponse<String> response = HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString());

            // 204 is the normal answer to a player command, and to "nothing is playing". An empty body
            // is not an error and must not be parsed as one.
            String text = response.body();
            JsonElement parsed = text == null || text.isBlank()
                    ? JsonNull.INSTANCE
                    : JsonParser.parseString(text);

            if (response.statusCode() == 429)
                return new Result(429, parsed,
                        response.headers().firstValue("Retry-After").orElse("1"));

            return new Result(response.statusCode(), parsed,
                    response.statusCode() >= 400 ? errorText(parsed) : "");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Result.failed("interrupted");
        } catch (Exception e) {
            // Status 0 means "never got an answer", which is retryable; a 4xx is not.
            return Result.failed(e.getClass().getSimpleName());
        }
    }

    /** The human half of an error body: Spotify nests it under {@code error.message}. */
    private static String errorText(JsonElement parsed) {
        if (parsed == null || !parsed.isJsonObject()) return "";

        JsonObject object = parsed.getAsJsonObject();
        if (!object.has("error")) return "";
        if (object.get("error").isJsonPrimitive()) return object.get("error").getAsString();
        if (!object.get("error").isJsonObject()) return "";

        return SpotifyModels.str(object.getAsJsonObject("error"), "message");
    }

    /** A failure worded for a status line - short, and never containing a token. */
    public static String describe(Result result) {
        if (result == null) return "";

        return switch (result.status()) {
            case 0 -> result.error().isEmpty() ? "brak polaczenia" : "brak polaczenia (" + result.error() + ")";
            case 401 -> "sesja wygasla - zaloguj sie ponownie";
            case 403 -> result.error().toLowerCase().contains("premium")
                    // Worth naming: the whole transport half of this panel is a Premium-only API, and
                    // "403" on its own sends people looking for a bug that is not there.
                    ? "sterowanie wymaga Spotify Premium"
                    : (result.error().isEmpty() ? "brak uprawnien" : result.error());
            case 404 -> result.error().isEmpty() ? "nie znaleziono (brak aktywnego urzadzenia?)" : result.error();
            case 429 -> "limit zapytan - chwila przerwy";
            default -> result.error().isEmpty() ? "blad " + result.status() : result.error();
        };
    }

    private static long parseRetryAfter(String header) {
        try {
            // Spotify answers in whole seconds; a second on top absorbs the clock skew that otherwise
            // produces a second 429 straight after the wait.
            return Math.min(30_000L, Math.max(1_000L, (Long.parseLong(header.trim()) + 1L) * 1000L));
        } catch (Exception e) {
            return 2_000L;
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(Math.max(0L, ms));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Runs something on the API pool, for callers that need a thread rather than a request. */
    static void async(Runnable task) {
        try {
            POOL.execute(task);
        } catch (RuntimeException ignored) {
            // Rejected means the queue is already full of work nobody is waiting for any more.
        }
    }
}
