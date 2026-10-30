package thunder.hack.utility.spotify;

import org.jetbrains.annotations.Nullable;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Locale;

/**
 * The exact playback position without a Spotify login, from the Windows media session - the same source the
 * volume overlay reads. Spotify publishes its track, position, length and play/pause state there, and so do
 * browsers playing YouTube and the like.
 *
 * <p>Read through one hidden PowerShell process (WinRT is not reachable from plain Java) that prints a line
 * every 300 ms; a daemon thread parses it into {@link #state()}. Started lazily on first use, restarted if it
 * dies, killed with the game. On anything but Windows, or if PowerShell is unavailable, it simply reports
 * nothing and callers fall back to the window title.
 */
public final class MediaSession {
    private MediaSession() {
    }

    /** One reading. {@code position} is where playback was at {@code updatedAt}. */
    public record State(String app, String title, String artist, long position, long duration, boolean playing,
                        long updatedAt) {
        /** Where playback is now: the reported position plus the time since, while playing. */
        public long positionNow() {
            long p = position + (playing ? Math.max(0L, System.currentTimeMillis() - updatedAt) : 0L);
            return duration > 0 ? Math.min(p, duration) : p;
        }

        public boolean spotify() {
            return app.toLowerCase(Locale.ROOT).contains("spotify");
        }
    }

    private static final boolean WINDOWS =
            System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");

    private static volatile State state;
    private static volatile long lastLine;
    private static Process process;
    private static long startedAt;
    private static int failures;

    /** The current media session, or null when nothing is playing (or the bridge is not running). */
    public static @Nullable State state() {
        ensureRunning();
        // a reader that has gone quiet is not trusted
        if (System.currentTimeMillis() - lastLine > 3000L) return null;
        return state;
    }

    private static synchronized void ensureRunning() {
        if (!WINDOWS || failures > 5) return;
        if (process != null && process.isAlive()) return;
        long now = System.currentTimeMillis();
        if (now - startedAt < 5000L) return; // do not respawn in a tight loop
        startedAt = now;
        try {
            File script = extractScript();
            if (script == null) {
                failures++;
                return;
            }
            // the full system path: a game started from a launcher may not have PowerShell on its PATH, and a
            // bridge that silently fails to start leaves the lyrics guessing from the window title again
            String root = System.getenv().getOrDefault("SystemRoot", "C:\\Windows");
            File exe = new File(root, "System32\\WindowsPowerShell\\v1.0\\powershell.exe");
            ProcessBuilder pb = new ProcessBuilder(exe.isFile() ? exe.getAbsolutePath() : "powershell", "-NoProfile", "-NonInteractive",
                    "-ExecutionPolicy", "Bypass", "-WindowStyle", "Hidden", "-File", script.getAbsolutePath());
            pb.redirectErrorStream(false);
            process = pb.start();
            Process p = process;
            Thread reader = new Thread(() -> read(p), "Gombal-MediaSession");
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
                lastLine = System.currentTimeMillis();
                state = parse(line.strip());
            }
        } catch (Throwable ignored) {
        }
        state = null;
    }

    private static @Nullable State parse(String line) {
        if (line.isEmpty() || line.equals("none")) return null;
        String[] f = line.split("\t", -1);
        if (f.length < 7) return null;
        try {
            String title = f[1].strip();
            if (title.isEmpty()) return null;
            return new State(f[0], title, f[2].strip(), Long.parseLong(f[3]), Long.parseLong(f[4]),
                    f[5].equalsIgnoreCase("Playing"), Long.parseLong(f[6]));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static @Nullable File extractScript() {
        try (InputStream in = MediaSession.class.getResourceAsStream("/assets/gombal/scripts/media_session.ps1")) {
            if (in == null) return null;
            File out = new File(System.getProperty("java.io.tmpdir"), "gombal_media_session.ps1");
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
        }, "Gombal-MediaSession-Stop"));
    }
}
