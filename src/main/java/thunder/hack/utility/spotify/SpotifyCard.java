package thunder.hack.utility.spotify;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Locale;

/**
 * The line being sung, read live from the lyrics view of the Spotify web player - nothing is downloaded, the
 * text is exactly what Spotify itself shows, and the line is the one Spotify highlights at that moment.
 *
 * <p>The view is read through UI Automation (the accessibility tree screen readers use) by one hidden PowerShell
 * process that prints the current line every ~50 ms; a daemon thread parses it into {@link #state()}. Same
 * lifecycle as {@link MediaSession}: started lazily, restarted if it dies, killed with the game. The script never
 * changes what Spotify shows: it reads whichever Spotify window already has the lyrics view open.
 *
 * <p>Reads the web player in the user's own, already signed-in Chrome. Chrome may stop repainting a window
 * that is fully covered; if the lyrics freeze behind the game, switching off chrome://flags
 * "Calculate window occlusion on Windows" keeps them moving.
 */
public final class SpotifyCard {
    private SpotifyCard() {
    }

    public enum Status {
        /** The bridge is not running yet (or this is not Windows). */
        Unknown,
        /** No Spotify window open. */
        NoWindow,
        /** A Spotify window, but its lyrics view is still being opened. */
        NoLyrics,
        /** Spotify has no lyrics for this song. */
        NoText,
        /** Before the first line of the song. */
        Intro,
        /** A line is being sung (it may be blank between lines). */
        Line
    }

    /**
     * @param index   the highlighted line's position in the song, -1 when none - two identical lines in a row
     *                differ only in this
     * @param line    the line Spotify highlights (being sung), {@code next} the one after it
     * @param since   when Spotify started highlighting it (wall clock, ms), estimated to the middle of the poll
     * @param playing false while Spotify is paused
     */
    public record State(Status status, int index, String line, String next, long since, boolean playing) {
    }

    /** The reader polls every ~50 ms; a change happened on average half a poll before it was seen. */
    private static final long HALF_POLL_MS = 75L;

    private static final State UNKNOWN = new State(Status.Unknown, -1, "", "", 0L, false);
    private static final boolean WINDOWS =
            System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");

    private static volatile State state = UNKNOWN;
    /** When the reader last reported a sung line. */
    private static volatile long lastLineSeen;
    private static volatile long lastLine;
    private static Process process;
    private static long startedAt;
    private static int failures;

    /* ------------------------------------------------------------------ */
    /* Now playing and its cover                                           */
    /* ------------------------------------------------------------------ */

    /** The player bar's "Now playing" link: the album page of the track, and its label (title and artists). */
    private static volatile String albumUrl = "", nowLabel = "";
    private static volatile long nowAt;

    /** Album page URL to the cover image URL Spotify's oEmbed gives for it ("" = none). */
    private static final java.util.Map<String, String> COVERS = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.Set<String> COVER_PENDING = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static final java.net.http.HttpClient HTTP = java.net.http.HttpClient.newBuilder()
            .connectTimeout(java.time.Duration.ofSeconds(6)).build();

    /**
     * The exact cover of the track Spotify is playing - the image Spotify itself shows - or null while it is
     * not known (reader not running, Spotify closed, still fetching). {@code title} must appear in the "Now
     * playing" label, so a cover is never shown against a different track than the caller displays.
     */
    public static String coverUrl(String title) {
        ensureRunning();
        if (System.currentTimeMillis() - nowAt > 5000L || albumUrl.isEmpty()) return null;
        if (title == null || title.isBlank()
                || !nowLabel.toLowerCase(Locale.ROOT).contains(title.toLowerCase(Locale.ROOT).strip())) return null;
        String url = albumUrl;
        String cover = COVERS.get(url);
        if (cover != null) return cover.isEmpty() ? null : cover;
        if (COVER_PENDING.add(url)) {
            // Spotify's public oEmbed endpoint (the one sites use to embed Spotify) names the cover image
            java.net.http.HttpRequest req = java.net.http.HttpRequest.newBuilder(java.net.URI.create(
                            "https://open.spotify.com/oembed?url=" + java.net.URLEncoder.encode(url, StandardCharsets.UTF_8)))
                    .timeout(java.time.Duration.ofSeconds(8)).GET().build();
            HTTP.sendAsync(req, java.net.http.HttpResponse.BodyHandlers.ofString()).whenComplete((res, err) -> {
                try {
                    if (err == null && res.statusCode() == 200) {
                        var json = com.google.gson.JsonParser.parseString(res.body()).getAsJsonObject();
                        COVERS.put(url, json.has("thumbnail_url") ? json.get("thumbnail_url").getAsString() : "");
                    }
                } catch (Throwable ignored) {
                } finally {
                    COVER_PENDING.remove(url);
                }
            });
        }
        return null;
    }

    /* ------------------------------------------------------------------ */
    /* Playback clock                                                      */
    /* ------------------------------------------------------------------ */

    /** Where the song is, how long it is and whether it plays, straight from Spotify's own player bar. */
    public record Playback(long positionMs, long durationMs, boolean playing) {
    }

    /** The clock: {@code anchorPos} was the position at {@code anchorAt}; it runs only while playing. */
    private static volatile long anchorPos, anchorAt, lengthMs;
    private static volatile boolean clockPlaying;
    private static volatile boolean clockKnown;

    /**
     * The player bar shows whole seconds, read about four times a second. Re-anchoring on every reading would
     * make the clock stutter back and forth inside each second, so the running clock is only corrected when it
     * leaves the second the bar shows - and it stops dead the moment Spotify says paused.
     */
    private static void readClock(long shownMs, long length, boolean playing, long now) {
        if (shownMs < 0) return;
        lengthMs = Math.max(0L, length);
        long running = clockPlaying ? anchorPos + (now - anchorAt) : anchorPos;
        boolean outside = running < shownMs || running > shownMs + 1000L;
        if (!clockKnown || playing != clockPlaying || outside) {
            // paused: exactly what the bar shows; playing: the middle of the shown second unless just resumed
            anchorPos = !playing || !clockPlaying ? shownMs : shownMs + 500L;
            anchorAt = now;
        } else if (!playing) {
            anchorPos = shownMs;
            anchorAt = now;
        }
        clockPlaying = playing;
        clockKnown = true;
    }

    /** "1:06" or "1:02:03" to milliseconds, -1 when it is not a time. */
    private static long clockMs(String text) {
        if (text.isEmpty()) return -1L;
        long total = 0L;
        for (String part : text.split(":")) {
            try {
                total = total * 60 + Long.parseLong(part.strip());
            } catch (NumberFormatException e) {
                return -1L;
            }
        }
        return total * 1000L;
    }

    /**
     * Spotify's own playback clock for the track called {@code title}, or null when it cannot be read or is
     * playing something else - the caller then falls back to its other sources.
     */
    public static Playback playback(String title) {
        ensureRunning();
        long now = System.currentTimeMillis();
        if (!clockKnown || now - nowAt > 3000L || title == null || title.isBlank()
                || !nowLabel.toLowerCase(Locale.ROOT).contains(title.toLowerCase(Locale.ROOT).strip())) return null;
        long pos = clockPlaying ? anchorPos + (now - anchorAt) : anchorPos;
        if (lengthMs > 0) pos = Math.min(pos, lengthMs);
        return new Playback(Math.max(0L, pos), lengthMs, clockPlaying);
    }

    public static State state() {
        ensureRunning();
        if (System.currentTimeMillis() - lastLine > 3000L) return UNKNOWN;
        return state;
    }

    private static synchronized void ensureRunning() {
        if (!WINDOWS || failures > 5) return;
        if (process != null && process.isAlive()) return;
        long now = System.currentTimeMillis();
        if (now - startedAt < 5000L) return;
        startedAt = now;
        try {
            File script = extract("spotify_card.ps1");
            if (script == null) {
                failures++;
                return;
            }
            String root = System.getenv().getOrDefault("SystemRoot", "C:\\Windows");
            File exe = new File(root, "System32\\WindowsPowerShell\\v1.0\\powershell.exe");
            ProcessBuilder pb = new ProcessBuilder(exe.isFile() ? exe.getAbsolutePath() : "powershell", "-NoProfile", "-NonInteractive",
                    "-ExecutionPolicy", "Bypass", "-WindowStyle", "Hidden", "-File", script.getAbsolutePath());
            pb.redirectError(ProcessBuilder.Redirect.DISCARD);
            process = pb.start();
            Process p = process;
            Thread reader = new Thread(() -> read(p), "Gombal-SpotifyCard");
            reader.setDaemon(true);
            reader.start();
        } catch (Throwable t) {
            failures++;
        }
    }

    private static void read(Process p) {
        try (BufferedReader in = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = in.readLine()) != null) {
                long now = System.currentTimeMillis();
                if (line.startsWith("now\t")) {
                    String[] f = line.split("\t", -1);
                    albumUrl = field(f, 1);
                    nowLabel = field(f, 2);
                    nowAt = now;
                    readClock(clockMs(field(f, 3)), clockMs(field(f, 4)), !field(f, 5).equals("0"), now);
                    continue;
                }
                lastLine = now;
                State next = parse(line, now);
                State prev = state;
                // Spotify re-renders its lyrics list now and then and the reader loses it for a pass; a line on
                // screen must not be dropped for that blink, so a "no lyrics" right after a line is held off
                if (next.status() == Status.NoLyrics && prev.status() == Status.Line && now - lastLineSeen < 800L) continue;
                if (next.status() == Status.Line) lastLineSeen = now;
                // keep the moment the line began; only a different line (or status) starts a new one
                if (prev.status() == next.status() && prev.index() == next.index() && prev.line().equals(next.line()))
                    next = new State(next.status(), next.index(), next.line(), next.next(), prev.since(), next.playing());
                state = next;
            }
        } catch (Throwable ignored) {
        }
        state = UNKNOWN;
    }

    private static State parse(String line, long now) {
        String[] f = line.split("\t", -1);
        long since = now - HALF_POLL_MS;
        boolean playing = f.length < 5 || !f[4].strip().equals("0");
        return switch (f[0].strip()) {
            case "line" -> new State(Status.Line, index(f), field(f, 2), field(f, 3), since, playing);
            case "intro" -> new State(Status.Intro, -1, "", field(f, 3), since, playing);
            case "nolyrics" -> new State(Status.NoLyrics, -1, "", "", since, false);
            case "notext" -> new State(Status.NoText, -1, "", "", since, false);
            case "nowindow" -> new State(Status.NoWindow, -1, "", "", since, false);
            default -> UNKNOWN;
        };
    }

    private static String field(String[] f, int i) {
        return f.length > i ? f[i].strip() : "";
    }

    private static int index(String[] f) {
        try {
            return Integer.parseInt(field(f, 1));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static File extract(String name) {
        try (InputStream in = SpotifyCard.class.getResourceAsStream("/assets/gombal/scripts/" + name)) {
            if (in == null) return null;
            File out = new File(System.getProperty("java.io.tmpdir"), "gombal_" + name);
            Files.copy(in, out.toPath(), StandardCopyOption.REPLACE_EXISTING);
            return out;
        } catch (Throwable t) {
            return null;
        }
    }

    static {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            Process p = process;
            if (p != null) p.destroyForcibly();
        }, "Gombal-SpotifyCard-Stop"));
    }
}
