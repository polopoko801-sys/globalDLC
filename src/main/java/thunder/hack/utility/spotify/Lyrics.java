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

    /**
     * lrclib first, NetEase as the fallback: lrclib goes down (503) for hours at a time, and with a single
     * source that meant no lyrics at all. Only when every source failed on the network is it a failure
     * (retried later) rather than "this song has no lyrics".
     */
    private static Track fetch(String title, String artist, long durationMs) throws Exception {
        Track lrclib = EMPTY;
        Exception lrclibError = null;
        try {
            lrclib = sameLanguage(fetchLrclib(title, artist, durationMs), title, artist);
            if (lrclib.synced()) return lrclib;
        } catch (Exception e) {
            lrclibError = e;
        }

        Track netease;
        try {
            netease = sameLanguage(fetchNetease(title, artist, durationMs), title, artist);
        } catch (Exception e) {
            if (lrclibError != null) throw lrclibError;
            return lrclib;
        }
        if (netease.synced()) return netease;
        // lrclib down and NetEase without the song: maybe lrclib has it, so ask again later
        if (lrclibError != null && !netease.found()) throw lrclibError;
        return lrclib.found() ? lrclib : netease;
    }

    /**
     * Throws away lyrics written in a script the song plainly is not in: mostly Chinese/Japanese characters for a
     * title and artist that have none. That is what a wrong match looks like - a cover or a same-named song from
     * NetEase's or lrclib's catalogue - and showing it is worse than nothing (the hidden Spotify reader then takes
     * over, and Spotify's own lyrics are always the right language).
     */
    private static Track sameLanguage(Track t, String title, String artist) {
        if (t == null || !t.found() || t.lines().isEmpty()) return t;
        if (cjkShare(title + " " + artist) > 0f) return t;          // the song itself is CJK - anything goes
        StringBuilder all = new StringBuilder();
        for (Line l : t.lines()) all.append(l.text()).append(' ');
        return cjkShare(all.toString()) > 0.3f ? EMPTY : t;
    }

    /** Share of the letters in {@code s} that are Chinese / Japanese characters. */
    private static float cjkShare(String s) {
        int letters = 0, cjk = 0;
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            i += Character.charCount(cp);
            if (!Character.isLetter(cp)) continue;
            letters++;
            Character.UnicodeScript sc = Character.UnicodeScript.of(cp);
            if (sc == Character.UnicodeScript.HAN || sc == Character.UnicodeScript.HIRAGANA || sc == Character.UnicodeScript.KATAKANA)
                cjk++;
        }
        return letters == 0 ? 0f : cjk / (float) letters;
    }

    /** NetEase Cloud Music: keyless, and its catalogue carries LRC for most western tracks too. */
    private static Track fetchNetease(String title, String artist, long durationMs) throws Exception {
        String main = firstArtist(artist);
        HttpResponse<String> res = request("https://music.163.com/api/search/get?type=1&limit=10&s="
                + enc((main + " " + clean(title)).strip()));
        if (res.statusCode() != 200) return EMPTY;
        var root = JsonParser.parseString(res.body());
        if (!root.isJsonObject() || !root.getAsJsonObject().has("result")) return EMPTY;
        var result = root.getAsJsonObject().get("result");
        if (!result.isJsonObject() || !result.getAsJsonObject().has("songs")) return EMPTY;

        // the first hit by the right artist (and about the right length, when known); else the first hit
        long pick = -1, fallback = -1;
        for (var el : result.getAsJsonObject().getAsJsonArray("songs")) {
            JsonObject song = el.getAsJsonObject();
            long id = song.get("id").getAsLong();
            if (fallback < 0) fallback = id;
            boolean artistOk = main.isBlank();
            if (song.has("artists"))
                for (var a : song.getAsJsonArray("artists"))
                    if (a.getAsJsonObject().get("name").getAsString().equalsIgnoreCase(main)) artistOk = true;
            long len = song.has("duration") ? song.get("duration").getAsLong() : 0L;
            boolean lengthOk = durationMs <= 0 || len <= 0 || Math.abs(len - durationMs) < 5000L;
            if (artistOk && lengthOk) {
                pick = id;
                break;
            }
        }
        // a hit by someone else is a different song (often a cover in another language) - only take a stranger's
        // hit when there was no artist to compare with
        if (pick < 0 && main.isBlank()) pick = fallback;
        if (pick < 0) return EMPTY;

        HttpResponse<String> lyr = request("https://music.163.com/api/song/lyric?lv=1&id=" + pick);
        if (lyr.statusCode() != 200) return EMPTY;
        JsonObject o = JsonParser.parseString(lyr.body()).getAsJsonObject();
        if (!o.has("lrc") || !o.get("lrc").isJsonObject()) return EMPTY;
        JsonObject lrc = o.getAsJsonObject("lrc");
        if (!lrc.has("lyric") || lrc.get("lyric").isJsonNull()) return EMPTY;
        List<Line> lines = new ArrayList<>();
        for (Line l : parseLrc(lrc.get("lyric").getAsString()))
            // credit lines ("作词 : ...", "Composer : ...") are not lyrics
            if (!l.text().matches("^[^\\s:：]{1,12}\\s*[:：].*") || l.atMs() > 15_000L) lines.add(l);
        if (lines.isEmpty()) return EMPTY;
        return new Track(lines, true, true, durationMs);
    }

    /**
     * lrclib, in three passes, each more forgiving than the last - and every candidate scored against the track
     * that is actually playing.
     *
     * <p>Picking the first hit with timestamps is why the words of one song used to crawl over another: a search
     * for a popular title also returns the live take, the remix, the sped-up version and a cover, and their
     * timestamps belong to a different recording. So the length is compared first (a live cut is rarely the
     * same length), then the title and artist, and only then does a hit with timestamps win.
     */
    private static Track fetchLrclib(String title, String artist, long durationMs) throws Exception {
        // 1. the exact endpoint: with the length it narrows to the album version on its own
        String url = "https://lrclib.net/api/get?track_name=" + enc(clean(title)) + "&artist_name=" + enc(firstArtist(artist))
                + (durationMs > 0 ? "&duration=" + (durationMs / 1000) : "");
        HttpResponse<String> res = request(url);
        Track exact = res.statusCode() == 200 ? parse(JsonParser.parseString(res.body()).getAsJsonObject()) : EMPTY;
        if (exact.synced() && lengthFits(exact, durationMs)) return exact;

        Track best = EMPTY;

        // 2. title + main artist
        best = better(best, search(clean(title), firstArtist(artist), title, artist, durationMs));

        // 3. free text, the most forgiving form lrclib offers
        best = better(best, searchQuery(firstArtist(artist) + " " + clean(title), title, artist, durationMs));

        // 4. title alone: compilations and featured credits often differ in the artist field
        if (!best.synced())
            best = better(best, search(clean(title), "", title, artist, durationMs));

        if (best.synced()) return best;
        if (exact.found()) return exact;
        return best.found() ? best : EMPTY;
    }

    private static Track searchQuery(String query, String title, String artist, long durationMs) throws Exception {
        HttpResponse<String> res = request("https://lrclib.net/api/search?q=" + enc(query.strip()));
        return pickFrom(res, title, artist, durationMs);
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

    private static Track search(String title, String artist, String wantTitle, String wantArtist, long durationMs) throws Exception {
        HttpResponse<String> res = request("https://lrclib.net/api/search?track_name=" + enc(title)
                + (artist.isBlank() ? "" : "&artist_name=" + enc(artist)));
        return pickFrom(res, wantTitle, wantArtist, durationMs);
    }

    /** The best-scoring result that carries lyrics, or nothing. */
    private static Track pickFrom(HttpResponse<String> res, String title, String artist, long durationMs) {
        if (res.statusCode() != 200) return EMPTY;
        var results = JsonParser.parseString(res.body());
        if (!results.isJsonArray()) return EMPTY;

        Track best = EMPTY;
        double bestScore = 0;
        for (var el : results.getAsJsonArray()) {
            if (!el.isJsonObject()) continue;
            JsonObject o = el.getAsJsonObject();
            Track t = parse(o);
            if (!t.found()) continue;

            double score = score(o, title, artist, durationMs, t.synced());
            if (score <= bestScore) continue;
            bestScore = score;
            best = t;
        }
        return best;
    }

    /** The more useful of two answers: a timed one beats a plain one, a found one beats nothing. */
    private static Track better(Track a, Track b) {
        if (b.synced()) return b;
        return !a.found() && b.found() ? b : a;
    }

    /**
     * How well one lrclib hit matches the track that is playing. Zero means "a different song".
     *
     * <p>The length gate is the important part: a live version, a remix or an extended edit of the same title
     * would otherwise supply its own timestamps to the studio cut, and the words would drift out of time
     * exactly as if nothing had been fixed at all.
     */
    private static double score(JsonObject o, String title, String artist, long durationMs, boolean synced) {
        String name = normalize(SpotifyModels.str(o, "trackName"));
        double titleScore = Math.max(titleMatch(name, normalize(title)), titleMatch(name, normalize(clean(title))));
        if (titleScore <= 0) return 0;

        double score = titleScore;

        String by = normalize(SpotifyModels.str(o, "artistName"));
        String wantArtist = normalize(firstArtist(artist));
        if (!by.isEmpty() && !wantArtist.isEmpty()) {
            if (by.equals(wantArtist)) score += 1.6;
            else if (by.contains(wantArtist) || wantArtist.contains(by)) score += 1.0;
            else if (overlap(by, wantArtist) >= 0.6) score += 0.5;
        }

        long length = lengthOf(o);
        if (durationMs > 0 && length > 0) {
            long diff = Math.abs(length - durationMs);
            if (diff > 12_000L) return 0;   // a different edit: its timing would not line up
            score += 1.5 * (1.0 - diff / 12_000.0);
        }

        return synced ? score + 1.0 : score;
    }

    private static double titleMatch(String name, String wanted) {
        if (name.isEmpty() || wanted.isEmpty()) return 0;
        if (name.equals(wanted)) return 3.0;
        if (name.contains(wanted) || wanted.contains(name)) return 1.8;
        return overlap(name, wanted) >= 0.6 ? 0.9 : 0;
    }

    /** Word overlap of two normalised strings, 0..1. */
    private static double overlap(String a, String b) {
        if (a.isEmpty() || b.isEmpty()) return 0;
        var first = new java.util.HashSet<>(java.util.List.of(a.split(" ")));
        var second = new java.util.HashSet<>(java.util.List.of(b.split(" ")));
        var common = new java.util.HashSet<>(first);
        common.retainAll(second);
        return common.size() / (double) Math.max(first.size(), second.size());
    }

    private static String normalize(String text) {
        if (text == null) return "";
        return normalized(text);
    }

    /**
     * Lower case, letters and digits only, single spaces between words - so a title written with a dash,
     * brackets or a stray full stop normalises to the same key as the plain one. Written out rather than as
     * a regex so that a curly quote or a Polish letter can never fall outside the class.
     */
    private static String normalized(String text) {
        StringBuilder out = new StringBuilder(text.length());
        boolean space = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                out.append(Character.toLowerCase(c));
                space = false;
            } else if (!space && out.length() > 0) {
                out.append(' ');
                space = true;
            }
        }
        return out.toString().strip();
    }

    private static long lengthOf(JsonObject o) {
        if (!o.has("duration") || o.get("duration").isJsonNull()) return 0L;
        try {
            return (long) (o.get("duration").getAsDouble() * 1000.0);
        } catch (RuntimeException e) {
            return 0L;
        }
    }

    /** Whether a hit's own length is close enough to the playing track's to use its timestamps. */
    private static boolean lengthFits(Track track, long durationMs) {
        return durationMs <= 0 || track.durationMs() <= 0 || Math.abs(track.durationMs() - durationMs) <= 12_000L;
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
        // not every file is written in order, and everything downstream walks the list front to back
        out.sort(java.util.Comparator.comparingLong(Line::atMs));
        return out;
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
