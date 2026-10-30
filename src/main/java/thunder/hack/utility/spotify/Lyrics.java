package thunder.hack.utility.spotify;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Synced lyrics for the now-playing track, from lrclib.net - a free, keyless, public lyrics API.
 *
 * <p>One fetch per track, cached. The result is a list of timestamped lines; {@link #lineAt(long)} returns the
 * line for a playback position so the widget can show the current lyric. Everything runs off the render thread;
 * the widget only ever reads the cached result.
 */
public final class Lyrics {
    private Lyrics() {
    }

    public record Line(long atMs, String text) {
    }

    /** @param durationMs the track length lrclib reports, 0 when unknown */
    public record Track(List<Line> lines, boolean synced, boolean found, long durationMs) {
        public String lineAt(long ms) {
            if (lines.isEmpty()) return "";
            String cur = "";
            for (Line l : lines) {
                if (l.atMs() <= ms) cur = l.text();
                else break;
            }
            return cur;
        }

        /** Index of the line playing at {@code ms}, or -1 before the first one. */
        public int indexAt(long ms) {
            int idx = -1;
            for (int i = 0; i < lines.size(); i++) {
                if (lines.get(i).atMs() <= ms) idx = i;
                else break;
            }
            return idx;
        }
    }

    private static final Track EMPTY = new Track(List.of(), false, false, 0L);
    private static final Map<String, Track> CACHE = new ConcurrentHashMap<>();
    private static final java.util.Set<String> PENDING = ConcurrentHashMap.newKeySet();

    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(6)).build();
    private static final ExecutorService POOL = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Gombal-Lyrics");
        t.setDaemon(true);
        return t;
    });

    /** The lyrics for this track, or null while they are being fetched (or there are none). */
    public static Track get(String title, String artist, long durationMs) {
        if (title == null || title.isBlank()) return null;
        String key = (artist + " - " + title).toLowerCase();
        Track cached = CACHE.get(key);
        if (cached != null) return cached.found() ? cached : null;
        queue(key, title, artist, durationMs);
        return null;
    }

    /** True while this track's lyrics are still being looked up - as opposed to looked up and not found. */
    public static boolean pending(String title, String artist) {
        if (title == null || title.isBlank()) return false;
        String key = (artist + " - " + title).toLowerCase();
        return !CACHE.containsKey(key);
    }

    /** True when the last lookup for this track failed because lrclib (or the network) was down - it is being retried. */
    public static boolean unavailable(String title, String artist) {
        if (title == null || title.isBlank()) return false;
        return FAILED_AT.containsKey((artist + " - " + title).toLowerCase());
    }

    /** When a lookup last failed on the network, so it is retried later instead of every frame or never. */
    private static final Map<String, Long> FAILED_AT = new ConcurrentHashMap<>();
    private static final long RETRY_MS = 10_000L;

    private static void queue(String key, String title, String artist, long durationMs) {
        Long failed = FAILED_AT.get(key);
        if (failed != null && System.currentTimeMillis() - failed < RETRY_MS) return;
        if (!PENDING.add(key)) return;
        POOL.execute(() -> {
            try {
                CACHE.put(key, fetch(title, artist, durationMs));
                FAILED_AT.remove(key);
            } catch (Throwable t) {
                // a network error is not "no lyrics": remember when, and try again in a while
                FAILED_AT.put(key, System.currentTimeMillis());
            } finally {
                PENDING.remove(key);
            }
        });
    }

    private static Track fetch(String title, String artist, long durationMs) throws Exception {
        String url = "https://lrclib.net/api/get?track_name=" + enc(title) + "&artist_name=" + enc(artist)
                + (durationMs > 0 ? "&duration=" + (durationMs / 1000) : "");
        HttpResponse<String> res = request(url);
        Track exact = res.statusCode() == 200 ? parse(JsonParser.parseString(res.body()).getAsJsonObject()) : EMPTY;
        if (exact.synced()) return exact;

        // /api/get wants an exact match, and a window title rarely is one ("Song - Remastered 2011",
        // "Song (feat. X)", several artists). Search is forgiving: try it with the title cleaned up and take
        // the first hit that has timestamps.
        Track found = search(clean(title), firstArtist(artist));
        if (found.synced()) return found;

        // last resort: free-text search over "artist title", the most forgiving lrclib offers
        Track loose = searchQuery(firstArtist(artist) + " " + clean(title));
        if (loose.synced()) return loose;

        if (exact.found()) return exact;
        return found.found() ? found : loose;
    }

    private static Track searchQuery(String query) throws Exception {
        HttpResponse<String> res = request("https://lrclib.net/api/search?q=" + enc(query.strip()));
        return pickFrom(res);
    }

    private static HttpResponse<String> request(String url) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .header("User-Agent", "Gombal (https://github.com/)")
                .timeout(Duration.ofSeconds(8)).GET().build();
        HttpResponse<String> res = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        // lrclib being down (5xx) or rate limiting (429) is NOT "this song has no lyrics". Treating it as such
        // cached an empty result for the rest of the session, so one outage meant that song never got lyrics.
        // Throwing sends it down the failure path instead, which retries shortly.
        if (res.statusCode() >= 500 || res.statusCode() == 429)
            throw new java.io.IOException("lrclib unavailable: HTTP " + res.statusCode());
        return res;
    }

    private static Track search(String title, String artist) throws Exception {
        HttpResponse<String> res = request("https://lrclib.net/api/search?track_name=" + enc(title)
                + (artist.isBlank() ? "" : "&artist_name=" + enc(artist)));
        return pickFrom(res);
    }

    /** The first result with timestamps, else the first with plain text, else nothing. */
    private static Track pickFrom(HttpResponse<String> res) {
        if (res.statusCode() != 200) return EMPTY;
        var results = JsonParser.parseString(res.body());
        if (!results.isJsonArray()) return EMPTY;
        Track plain = EMPTY;
        for (var el : results.getAsJsonArray()) {
            if (!el.isJsonObject()) continue;
            Track t = parse(el.getAsJsonObject());
            if (t.synced()) return t;
            if (t.found() && !plain.found()) plain = t;
        }
        return plain;
    }

    /** Drops the decorations a player adds to a title: "(feat. X)", "[Live]", " - Remastered 2011". */
    private static String clean(String title) {
        String t = title.replaceAll("\\s*[(\\[][^)\\]]*[)\\]]", "");
        int dash = t.indexOf(" - ");
        if (dash > 0) t = t.substring(0, dash);
        return t.strip().isEmpty() ? title : t.strip();
    }

    /** "A, B & C" -> "A": the search matches on the main artist far more reliably than on the full credit. */
    private static String firstArtist(String artist) {
        if (artist == null) return "";
        return artist.split(",|&| feat\\.| ft\\.| x ")[0].strip();
    }

    private static Track parse(JsonObject o) {
        String synced = o.has("syncedLyrics") && !o.get("syncedLyrics").isJsonNull() ? o.get("syncedLyrics").getAsString() : null;
        long length = o.has("duration") && !o.get("duration").isJsonNull() ? (long) (o.get("duration").getAsDouble() * 1000) : 0L;
        if (synced != null && !synced.isBlank()) return new Track(parseLrc(synced), true, true, length);

        String plain = o.has("plainLyrics") && !o.get("plainLyrics").isJsonNull() ? o.get("plainLyrics").getAsString() : null;
        if (plain != null && !plain.isBlank()) {
            List<Line> lines = new ArrayList<>();
            for (String l : plain.split("\n")) lines.add(new Line(0, l.strip()));
            return new Track(lines, false, true, length);
        }
        return EMPTY;
    }

    /** Parses LRC "[mm:ss.xx] text" lines into timestamped entries. */
    private static List<Line> parseLrc(String lrc) {
        List<Line> out = new ArrayList<>();
        for (String raw : lrc.split("\n")) {
            int close = raw.indexOf(']');
            if (!raw.startsWith("[") || close < 0) continue;
            String tag = raw.substring(1, close);
            String text = raw.substring(close + 1).strip();
            String[] mm = tag.split(":");
            if (mm.length < 2) continue;
            try {
                long ms = (long) ((Integer.parseInt(mm[0].trim()) * 60 + Double.parseDouble(mm[1].trim())) * 1000);
                out.add(new Line(ms, text));
            } catch (NumberFormatException ignored) {
            }
        }
        return out;
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
