package thunder.hack.utility.spotify;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import net.minecraft.util.Util;
import thunder.hack.core.manager.client.ConfigManager;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Spotify login: Authorization Code with PKCE, through a loopback redirect.
 *
 * <p><b>Why PKCE and not the simpler flows.</b> The implicit flow gives no refresh token, so you would
 * be sent back to a browser every hour. The client-credentials flow has no user attached to it at all,
 * so it cannot see your playback, your library or your playlists. The classic authorization-code flow
 * needs a client <i>secret</i>, and a secret shipped inside a mod is not a secret. PKCE is the one flow
 * that gives a refreshable user token with nothing confidential on this side.
 *
 * <p><b>What is stored, and what is never stored.</b> Your Spotify password never reaches this process -
 * you type it on accounts.spotify.com, in your own browser, and what comes back here is a one-shot code.
 * Only the tokens are kept, in {@code GlobalDLC/misc/spotify-token.dat}, outside the client config so a
 * shared or uploaded config never carries a login. The file is obfuscated and, where the filesystem
 * allows it, readable only by your user. Be clear-eyed about what that buys: it stops a config dump or a
 * shoulder-surf from handing over a working token, it does not stop a program already running as you.
 * Revoking is instant from Spotify under Apps, and {@code .spotify logout} deletes the file.
 *
 * <p><b>The client id.</b> There is no shared one: an app registration is per-developer, and baking one
 * in would mean every user of this mod competing for one rate limit. You register a free app at
 * developer.spotify.com, add {@link #REDIRECT} as a redirect URI, and paste the id with
 * {@code .spotify clientid <id>}. The id is public by design - it is in the authorisation URL - so it
 * lives in a plain file.
 */
public final class SpotifyAuth {
    private SpotifyAuth() {
    }

    /**
     * The loopback address the browser is sent back to.
     *
     * <p>Fixed, not an ephemeral port: Spotify matches the redirect URI exactly against what the app
     * registration lists, so a port that changed per launch could never be registered. Loopback HTTP is
     * the one case Spotify still allows without TLS, which is what makes this work at all.
     */
    public static final String REDIRECT = "http://127.0.0.1:57129/callback";
    private static final int PORT = 57129;

    /**
     * The scopes, and only these.
     *
     * <p>Each one is here because a feature of the panel stops working without it; nothing is requested
     * speculatively. Notably absent: {@code user-top-read}, {@code user-read-email},
     * {@code user-follow-*} and the image-upload scopes - the panel never asks for any of that.
     */
    private static final String SCOPES = String.join(" ",
            "user-read-playback-state",      // what is playing, on which device, volume/shuffle/repeat
            "user-modify-playback-state",    // play, pause, next, previous, seek, volume, queue, transfer
            "user-read-currently-playing",   // the track when playback is scoped to a single item
            "user-read-private",             // the account id, needed to know which playlists are yours
            "user-library-read",             // whether the current track is saved
            "user-library-modify",           // the Save button
            "playlist-read-private",         // your own playlists in the picker
            "playlist-read-collaborative",   // playlists you may add to but do not own
            "playlist-modify-private",       // add to / remove from / create a private playlist
            "playlist-modify-public");       // the same for a public one

    private static final String AUTH_URL = "https://accounts.spotify.com/authorize";
    private static final String TOKEN_URL = "https://accounts.spotify.com/api/token";

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8)).build();

    /** What the UI is allowed to say about the login. */
    public enum Stage {
        /** No client id pasted yet - nothing can be attempted. */
        NoClientId,
        /** Client id present, no tokens. */
        LoggedOut,
        /** A browser is open and we are holding the loopback port. */
        Waiting,
        LoggedIn,
        Failed
    }

    private record Tokens(String access, String refresh, long expiresAt) {
        boolean stale() {
            // Thirty seconds of headroom: a token that expires mid-flight costs a retry, and a retry on
            // a command the user just pressed reads as the button not working.
            return access == null || access.isEmpty() || System.currentTimeMillis() > expiresAt - 30_000L;
        }
    }

    private static final AtomicReference<Tokens> TOKENS = new AtomicReference<>(null);
    private static volatile Stage stage = Stage.LoggedOut;
    private static volatile String message = "";
    private static volatile boolean loaded;
    private static volatile HttpServer server;

    /**
     * Two locks, not one, and never the class monitor.
     *
     * <p>{@link #stage()} is called from render, every frame. A refresh is a blocking HTTP exchange that
     * can take seconds. If both took the same lock, one expiring token would freeze the game for the
     * length of a network round trip - so the refresh path takes {@code TOKEN_LOCK} and the read path
     * takes nothing at all, reading volatiles and an {@link AtomicReference} instead.
     */
    private static final Object TOKEN_LOCK = new Object();
    private static final Object LOAD_LOCK = new Object();

    /* ------------------------------------------------------------------ */
    /* State the UI reads                                                  */
    /* ------------------------------------------------------------------ */

    public static Stage stage() {
        load();
        if (clientId().isEmpty()) return Stage.NoClientId;
        if (stage == Stage.Waiting || stage == Stage.Failed) return stage;
        return TOKENS.get() != null ? Stage.LoggedIn : Stage.LoggedOut;
    }

    public static boolean authorised() {
        return stage() == Stage.LoggedIn;
    }

    /** The last thing that happened, for the line under the login button. Never a token. */
    public static String message() {
        return message;
    }

    /* ------------------------------------------------------------------ */
    /* Client id                                                           */
    /* ------------------------------------------------------------------ */

    private static Path clientIdFile() {
        return ConfigManager.MISC_FOLDER.toPath().resolve("spotify-client.txt");
    }

    private static volatile String clientId;

    public static String clientId() {
        if (clientId != null) return clientId;

        try {
            Path file = clientIdFile();
            clientId = Files.exists(file) ? Files.readString(file, StandardCharsets.UTF_8).trim() : "";
        } catch (Exception e) {
            clientId = "";
        }
        return clientId;
    }

    public static void setClientId(String id) {
        clientId = id == null ? "" : id.trim();
        try {
            Files.createDirectories(clientIdFile().getParent());
            Files.writeString(clientIdFile(), clientId, StandardCharsets.UTF_8);
        } catch (Exception e) {
            message = "could not save the client id";
        }
    }

    /* ------------------------------------------------------------------ */
    /* The token, as every request sees it                                 */
    /* ------------------------------------------------------------------ */

    /**
     * A usable access token, refreshing first when the one we hold has expired.
     *
     * <p>Blocking, and deliberately so: every caller is already on a worker thread, and the alternative -
     * handing back a token that is about to expire - turns one await into a failed request plus a retry.
     * Returns null when there is no login, which is the signal to stop rather than to retry.
     */
    public static String token() {
        load();

        Tokens tokens = TOKENS.get();
        if (tokens == null) return null;
        // The common path takes no lock at all: a token that is still good is one volatile read away.
        if (!tokens.stale()) return tokens.access();

        synchronized (TOKEN_LOCK) {
            // Re-read inside the lock: while this thread waited, another may already have refreshed, and
            // refreshing twice would burn a rotated refresh token for nothing.
            Tokens current = TOKENS.get();
            if (current == null) return null;
            if (!current.stale()) return current.access();

            return refresh() ? TOKENS.get().access() : null;
        }
    }

    /** Forces a refresh, for the one case a 401 arrives on a token we believed was still good. */
    public static String renew() {
        load();

        synchronized (TOKEN_LOCK) {
            return TOKENS.get() != null && refresh() ? TOKENS.get().access() : null;
        }
    }

    /** Must be called holding {@link #TOKEN_LOCK}. */
    private static boolean refresh() {
        Tokens tokens = TOKENS.get();
        if (tokens == null || tokens.refresh() == null || tokens.refresh().isEmpty()) return false;

        JsonObject answer = form("grant_type=refresh_token"
                + "&refresh_token=" + enc(tokens.refresh())
                + "&client_id=" + enc(clientId()));

        if (answer == null || !answer.has("access_token")) {
            // A refresh token is only invalidated by the user revoking the app or changing the password.
            // Either way there is nothing to retry, so the login is dropped and the UI says to log in.
            message = answer != null && answer.has("error_description")
                    ? SpotifyModels.str(answer, "error_description")
                    : "the session expired - log in again";
            forget();
            return false;
        }

        store(new Tokens(
                SpotifyModels.str(answer, "access_token"),
                // Spotify rotates refresh tokens; keeping the old one when it does would log you out at
                // the next expiry for no visible reason.
                answer.has("refresh_token") ? SpotifyModels.str(answer, "refresh_token") : tokens.refresh(),
                System.currentTimeMillis() + SpotifyModels.num(answer, "expires_in", 3600) * 1000L));
        return true;
    }

    /* ------------------------------------------------------------------ */
    /* Login                                                               */
    /* ------------------------------------------------------------------ */

    public interface Listener {
        void onStatus(String status);
    }

    /**
     * Opens the browser and waits for the redirect.
     *
     * <p>The whole flow is on one daemon thread, including the wait, so a login left hanging while you
     * play costs nothing and dies with the game. The loopback server is held for five minutes and then
     * closed - a port that stayed bound for the rest of the session would make the next attempt fail
     * with an error that says nothing about the real cause.
     */
    public static void login(Listener listener) {
        if (clientId().isEmpty()) {
            report(listener, "paste the client id first: .spotify clientid <id>");
            return;
        }
        if (stage == Stage.Waiting) {
            report(listener, "a login is already waiting in your browser");
            return;
        }

        Thread thread = new Thread(() -> flow(listener), "Gombal-SpotifyAuth");
        thread.setDaemon(true);
        thread.start();
    }

    private static void flow(Listener listener) {
        String verifier = randomVerifier();

        try {
            stage = Stage.Waiting;

            // The state value is the one defence against a different page on this machine firing the
            // callback at us: a code that arrives without the state we generated is simply dropped.
            String state = randomVerifier().substring(0, 16);
            AtomicReference<String> code = new AtomicReference<>(null);
            Object lock = new Object();

            server = HttpServer.create(new InetSocketAddress("127.0.0.1", PORT), 0);
            server.createContext("/callback", exchange -> {
                Map<String, String> query = parseQuery(exchange.getRequestURI().getRawQuery());
                String got = query.get("code");
                boolean ok = got != null && state.equals(query.get("state"));

                byte[] page = page(ok
                        ? "Zalogowano. Wroc do Minecrafta."
                        : "Nie udalo sie: " + query.getOrDefault("error", "brak kodu")).getBytes(StandardCharsets.UTF_8);

                exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
                exchange.sendResponseHeaders(200, page.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(page);
                }

                if (ok) code.set(got);
                else message = "browser returned: " + query.getOrDefault("error", "no code");

                synchronized (lock) {
                    lock.notifyAll();
                }
            });
            server.setExecutor(null);
            server.start();

            String url = AUTH_URL
                    + "?client_id=" + enc(clientId())
                    + "&response_type=code"
                    + "&redirect_uri=" + enc(REDIRECT)
                    + "&code_challenge_method=S256"
                    + "&code_challenge=" + challenge(verifier)
                    + "&state=" + state
                    + "&scope=" + enc(SCOPES);

            report(listener, "otwieram przegladarke...");
            Util.getOperatingSystem().open(URI.create(url));

            synchronized (lock) {
                if (code.get() == null) lock.wait(300_000L);
            }

            if (code.get() == null) {
                stage = Stage.Failed;
                if (message.isEmpty()) message = "login timed out";
                report(listener, message);
                return;
            }

            report(listener, "wymieniam kod na token...");
            JsonObject answer = form("grant_type=authorization_code"
                    + "&code=" + enc(code.get())
                    + "&redirect_uri=" + enc(REDIRECT)
                    + "&client_id=" + enc(clientId())
                    + "&code_verifier=" + enc(verifier));

            if (answer == null || !answer.has("access_token")) {
                stage = Stage.Failed;
                // The overwhelmingly common cause is a redirect URI that is not on the app registration,
                // and Spotify words that as "INVALID_CLIENT", which sends people looking at the id.
                message = answer != null && answer.has("error_description")
                        ? SpotifyModels.str(answer, "error_description")
                        : "token exchange failed - is " + REDIRECT + " added to your app?";
                report(listener, message);
                return;
            }

            store(new Tokens(
                    SpotifyModels.str(answer, "access_token"),
                    SpotifyModels.str(answer, "refresh_token"),
                    System.currentTimeMillis() + SpotifyModels.num(answer, "expires_in", 3600) * 1000L));

            stage = Stage.LoggedIn;
            message = "";
            report(listener, "zalogowano");
        } catch (java.net.BindException e) {
            stage = Stage.Failed;
            message = "port " + PORT + " is taken - close the other login and try again";
            report(listener, message);
        } catch (Exception e) {
            stage = Stage.Failed;
            message = String.valueOf(e.getMessage());
            report(listener, message);
        } finally {
            HttpServer open = server;
            server = null;
            if (open != null) open.stop(0);
        }
    }

    private static void report(Listener listener, String status) {
        message = status;
        if (listener != null) listener.onStatus(status);
    }

    private static String page(String text) {
        return "<!doctype html><meta charset=utf-8><title>GOMBAL</title>"
                + "<body style=\"background:#0d0d12;color:#eee;font:16px system-ui;"
                + "display:flex;align-items:center;justify-content:center;height:100vh;margin:0\">"
                + "<div style=\"text-align:center\"><div style=\"font-weight:700;letter-spacing:.2em;"
                + "color:#1DB954\">GOMBAL</div><p>" + text + "</p></div>";
    }

    public static void logout() {
        synchronized (TOKEN_LOCK) {
            forget();
        }
        stage = Stage.LoggedOut;
        message = "";
        SpotifyLibrary.clear();
        SpotifyPlayer.clear();
    }

    /* ------------------------------------------------------------------ */
    /* PKCE                                                                */
    /* ------------------------------------------------------------------ */

    private static final SecureRandom RANDOM = new SecureRandom();

    private static String randomVerifier() {
        byte[] bytes = new byte[64];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String challenge(String verifier) throws Exception {
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
    }

    /* ------------------------------------------------------------------ */
    /* Token store                                                         */
    /* ------------------------------------------------------------------ */

    private static Path tokenFile() {
        return ConfigManager.MISC_FOLDER.toPath().resolve("spotify-token.dat");
    }

    /**
     * Reads the token file, once.
     *
     * <p>Double checked on a volatile, and under its own lock rather than the token lock: this is called
     * from {@link #stage()} on the render thread, and it must never be able to queue behind a refresh.
     */
    private static void load() {
        if (loaded) return;

        synchronized (LOAD_LOCK) {
            if (loaded) return;
            loaded = true;
            read();
        }
    }

    private static void read() {
        try {
            Path file = tokenFile();
            if (!Files.exists(file)) return;

            JsonObject json = JsonParser.parseString(deobfuscate(Files.readAllBytes(file))).getAsJsonObject();
            String access = SpotifyModels.str(json, "a");
            String refresh = SpotifyModels.str(json, "r");
            if (refresh.isEmpty()) return;

            TOKENS.set(new Tokens(access, refresh, SpotifyModels.longOf(json, "e", 0L)));
            stage = Stage.LoggedIn;
        } catch (Exception e) {
            // A corrupt store is the same situation as no store: there is nothing to recover and nothing
            // worth telling the user, because the next login overwrites it.
            TOKENS.set(null);
        }
    }

    private static void store(Tokens tokens) {
        TOKENS.set(tokens);

        try {
            JsonObject json = new JsonObject();
            json.addProperty("a", tokens.access());
            json.addProperty("r", tokens.refresh());
            json.addProperty("e", tokens.expiresAt());

            Path file = tokenFile();
            Files.createDirectories(file.getParent());
            Files.write(file, obfuscate(json.toString()));
            restrict(file);
        } catch (Exception e) {
            // Not being able to persist costs one browser login on the next launch; the session itself
            // is unaffected, so this is not worth interrupting anybody over.
            message = "logged in, but the token could not be saved";
        }
    }

    private static void forget() {
        TOKENS.set(null);
        try {
            Files.deleteIfExists(tokenFile());
        } catch (Exception ignored) {
            // The file staying behind is harmless - a revoked refresh token is not a credential.
        }
    }

    /**
     * Narrows the file to this user where the filesystem supports it.
     *
     * <p>POSIX gets 0600. Windows gets the ACL rewritten to the owner alone, which is what actually
     * matters here because that is where this mod runs. Both are best effort: a filesystem that cannot
     * express it (a FAT stick, a mapped share) simply keeps the default, and the obfuscation below is
     * what remains.
     */
    private static void restrict(Path file) {
        try {
            var posix = Files.getFileAttributeView(file, java.nio.file.attribute.PosixFileAttributeView.class);
            if (posix != null) {
                posix.setPermissions(java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
                return;
            }

            var acl = Files.getFileAttributeView(file, java.nio.file.attribute.AclFileAttributeView.class);
            if (acl == null) return;

            var owner = acl.getOwner();
            var entry = java.nio.file.attribute.AclEntry.newBuilder()
                    .setType(java.nio.file.attribute.AclEntryType.ALLOW)
                    .setPrincipal(owner)
                    .setPermissions(java.util.EnumSet.allOf(java.nio.file.attribute.AclEntryPermission.class))
                    .build();
            acl.setAcl(java.util.List.of(entry));
        } catch (Exception ignored) {
            // Best effort by design - see the note above.
        }
    }

    /**
     * Light obfuscation of the token file.
     *
     * <p>This is not encryption and is not presented as any: the key is derived from the machine, so
     * anything running as you can derive it too. What it does buy is that the file is not a plain token
     * - it survives a pasted config folder, a screenshot of a text editor or a support log without
     * handing anybody a working session, and it will not be picked up by a scanner looking for bearer
     * tokens in plain files. Real protection is Spotify-side revocation, which is one click.
     */
    private static byte[] key() {
        String seed = System.getProperty("user.name", "u") + "|"
                + System.getProperty("os.name", "o") + "|gombal-spotify";
        try {
            return MessageDigest.getInstance("SHA-256").digest(seed.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            return seed.getBytes(StandardCharsets.UTF_8);
        }
    }

    private static byte[] obfuscate(String text) {
        byte[] data = text.getBytes(StandardCharsets.UTF_8);
        byte[] key = key();
        for (int i = 0; i < data.length; i++) data[i] ^= key[i % key.length];
        return Base64.getEncoder().encode(data);
    }

    private static String deobfuscate(byte[] stored) {
        byte[] data = Base64.getDecoder().decode(stored);
        byte[] key = key();
        for (int i = 0; i < data.length; i++) data[i] ^= key[i % key.length];
        return new String(data, StandardCharsets.UTF_8);
    }

    /* ------------------------------------------------------------------ */
    /* HTTP                                                                */
    /* ------------------------------------------------------------------ */

    private static JsonObject form(String body) {
        try {
            HttpResponse<String> response = HTTP.send(
                    HttpRequest.newBuilder(URI.create(TOKEN_URL))
                            .header("Content-Type", "application/x-www-form-urlencoded")
                            .timeout(Duration.ofSeconds(15))
                            .POST(HttpRequest.BodyPublishers.ofString(body))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());

            // The error body is the useful half of a failed exchange, so it is parsed too rather than
            // being thrown away on a non-200.
            return JsonParser.parseString(response.body()).getAsJsonObject();
        } catch (Exception e) {
            return null;
        }
    }

    private static Map<String, String> parseQuery(String raw) {
        Map<String, String> out = new java.util.HashMap<>();
        if (raw == null) return out;

        for (String pair : raw.split("&")) {
            int eq = pair.indexOf('=');
            if (eq <= 0) continue;
            out.put(java.net.URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
                    java.net.URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
        }
        return out;
    }

    static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
