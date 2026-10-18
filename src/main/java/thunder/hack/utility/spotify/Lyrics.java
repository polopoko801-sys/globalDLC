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

    public record Track(List<Line> lines, boolean synced, boolean found) {
        public String lineAt(long ms) {
            if (lines.isEmpty()) return "";
            String cur = "";
            for (Line l : lines) {
                if (l.atMs() <= ms) cur = l.text();
                else break;
            }
            return cur;
        }
    }

    private static final Track EMPTY = new Track(List.of(), false, false);
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

    private static void queue(String key, String title, String artist, long durationMs) {
        if (!PENDING.add(key)) return;
        POOL.execute(() -> {
            try {
                CACHE.put(key, fetch(title, artist, durationMs));
            } catch (Throwable t) {
                CACHE.put(key, EMPTY);
            } finally {
                PENDING.remove(key);
            }
        });
    }

    private static Track fetch(String title, String artist, long durationMs) throws Exception {
        String url = "https://lrclib.net/api/get?track_name=" + enc(title) + "&artist_name=" + enc(artist)
                + (durationMs > 0 ? "&duration=" + (durationMs / 1000) : "");
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .header("User-Agent", "Gombal (https://github.com/)")
                .timeout(Duration.ofSeconds(8)).GET().build();
        HttpResponse<String> res = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() != 200) return EMPTY;

        JsonObject o = JsonParser.parseString(res.body()).getAsJsonObject();
        String synced = o.has("syncedLyrics") && !o.get("syncedLyrics").isJsonNull() ? o.get("syncedLyrics").getAsString() : null;
        if (synced != null && !synced.isBlank()) return new Track(parseLrc(synced), true, true);

        String plain = o.has("plainLyrics") && !o.get("plainLyrics").isJsonNull() ? o.get("plainLyrics").getAsString() : null;
        if (plain != null && !plain.isBlank()) {
            List<Line> lines = new ArrayList<>();
            for (String l : plain.split("\n")) lines.add(new Line(0, l.strip()));
            return new Track(lines, false, true);
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
