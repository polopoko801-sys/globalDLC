package thunder.hack.utility;

import com.sun.jna.Callback;
import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.IntByReference;

import java.util.Locale;

/**
 * What is playing right now, read off a window title.
 *
 * <p>Two sources, in order: the Spotify desktop client, which puts {@code "Artist - Title"} straight in
 * its window title; and a browser tab on a site that does the same. Both are public, stable and need no
 * permission, which is what makes them the right ones here - the Web API would mean an application
 * registration and an OAuth flow, and shipping a client secret inside a mod jar is not a thing worth
 * doing for a HUD widget.
 *
 * <p><b>Identification is deliberately strict.</b> The desktop client is recognised by its PROCESS, not
 * by its window class: Spotify is a Chromium shell, and so are Discord, Slack, VS Code and half the
 * desktop - matching on the class picked Discord's window and put a chat channel in the music widget.
 * Browsers are recognised by the suffix every browser puts on its window title, and then only a tab on
 * a site this knows plays music is accepted; an unknown page is ignored rather than guessed at.
 *
 * <p>Nothing is sent anywhere. A title is read from a window on this machine and shown on this machine.
 *
 * <p>Windows only, because the mechanism is. Everywhere else this reports nothing and the widget falls
 * back to its text file, which works on any platform.
 */
public final class NowPlaying {
    private NowPlaying() {
    }

    /** Desktop players, by executable name. */
    private static final String[] PLAYERS = {"spotify.exe"};

    /** Titles Spotify shows when it is open but not playing. */
    private static final String[] IDLE = {
            "spotify", "spotify premium", "spotify free", "advertisement",
            "spotify - web player: music for everyone", "web player: music for everyone"
    };

    /**
     * What browsers append to their window title, longest first.
     *
     * <p>Order matters: "YouTube Music" has to be tested before "YouTube" or the shorter one wins and
     * takes half the name with it.
     */
    private static final String[] BROWSERS = {
            " - Google Chrome", " — Mozilla Firefox", " - Mozilla Firefox", " and 1 more page - Personal - Microsoft​ Edge",
            " - Microsoft​ Edge", " - Microsoft Edge", " - Brave", " - Opera", " - Vivaldi", " - Chromium", " - Zen Browser",
            // The Firefox forks. Missing these was a real bug, not a gap in coverage: an unrecognised suffix is
            // not stripped, so " — LibreWolf" stayed glued to the track name, got searched for as part of the
            // title, and the lookup came back with whatever that nonsense matched - a cover for a different
            // song. Both dash characters are listed because the forks disagree about which one they use.
            " — LibreWolf", " - LibreWolf", " — Waterfox", " - Waterfox", " — Floorp", " - Floorp",
            " — Tor Browser", " - Tor Browser", " — Mullvad Browser", " - Mullvad Browser",
            " - Yandex", " - Yandex Browser", " - Thorium", " - Ungoogled Chromium"
    };

    /** Site markers that mean the tab is playing music, longest first. */
    private static final String[] SITES = {
            " | Free Listening on SoundCloud", " - YouTube Music", " | Spotify", " - YouTube", " | SoundCloud"
    };

    private static final boolean WINDOWS =
            System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");

    /** Set once a JNA call throws, so a broken environment is not retried every second forever. */
    private static boolean unavailable;

    /** Artist and title, or null when nothing is playing. */
    public record Track(String artist, String title) {
    }

    public static Track current() {
        if (!WINDOWS || unavailable) return null;

        try {
            Found found = scan();
            if (found == null) return null;

            String title = found.title().trim();
            for (String idle : IDLE)
                if (title.toLowerCase(Locale.ROOT).equals(idle)) return null;

            return found.browser() ? parseTab(title) : split(title);
        } catch (Throwable t) {
            unavailable = true;
            return null;
        }
    }

    /** A window title worth reading, and where it came from. */
    private record Found(String title, boolean browser) {
    }

    /**
     * The handle of the window we have decided IS the player, once something proved it.
     *
     * <p>Learned rather than assumed, because the proof is not always available: asking a window which
     * process owns it needs a handle to that process, and {@code OpenProcess} can be refused - on a
     * locked-down machine, or against a Store-packaged Spotify. When that happens the process check
     * finds nothing and the desktop client is invisible to us.
     *
     * <p>So there is a second way to be sure. Spotify's window title is EXACTLY {@code "Spotify"} or
     * {@code "Spotify Premium"} whenever it is open and not playing - no other program does that - and
     * seeing it once is enough to identify the window for good. After that the same handle is trusted
     * whatever it says, which is the point: what it says next is the track.
     */
    private static long learnedWindow;

    /** Whether this title is one only the Spotify client shows. */
    private static boolean isIdleSpotifyTitle(String title) {
        String lower = title.trim().toLowerCase(Locale.ROOT);
        for (String idle : IDLE)
            if (lower.equals(idle)) return true;
        return false;
    }

    /**
     * What the scan can currently see, for working out why nothing is being found.
     *
     * <p>One line per visible titled window: its class, the process that owns it (or {@code ?} when
     * that could not be read, which is itself the answer), and its title.
     */
    public static java.util.List<String> describe() {
        java.util.List<String> lines = new java.util.ArrayList<>();
        if (!WINDOWS) {
            lines.add("not Windows - the window-title source does not apply here");
            return lines;
        }

        try {
            User32.INSTANCE.EnumWindows((hWnd, data) -> {
                if (lines.size() >= 40) return false;
                if (!User32.INSTANCE.IsWindowVisible(hWnd)) return true;

                char[] buffer = new char[512];
                int length = User32.INSTANCE.GetWindowTextW(hWnd, buffer, buffer.length);
                if (length <= 0) return true;

                String process = processNameOf(hWnd);
                lines.add((classNameOf(hWnd) == null ? "?" : classNameOf(hWnd))
                        + " | " + (process == null ? "? (OpenProcess refused)" : process)
                        + " | " + new String(buffer, 0, length));
                return true;
            }, Pointer.NULL);
        } catch (Throwable t) {
            lines.add("failed: " + t);
        }

        return lines;
    }

    private static String classNameOf(Pointer hWnd) {
        char[] buffer = new char[256];
        int length = User32.INSTANCE.GetClassNameW(hWnd, buffer, buffer.length);
        return length <= 0 ? null : new String(buffer, 0, length);
    }

    /**
     * Walks the top-level windows for the first thing that might be playing music.
     *
     * <p>The desktop player wins outright and stops the walk; a browser tab is only kept as a
     * candidate, so having Spotify open behind a browser still reports Spotify.
     */
    private static Found scan() {
        final Found[] desktop = new Found[1];
        final Found[] browser = new Found[1];

        User32.INSTANCE.EnumWindows((hWnd, data) -> {
            if (desktop[0] != null) return false;
            if (!User32.INSTANCE.IsWindowVisible(hWnd)) return true;

            char[] buffer = new char[512];
            int length = User32.INSTANCE.GetWindowTextW(hWnd, buffer, buffer.length);
            if (length <= 0) return true;

            String title = new String(buffer, 0, length);

            long handle = Pointer.nativeValue(hWnd);

            // Already proved, on this run, to be the player's window.
            if (learnedWindow != 0 && handle == learnedWindow) {
                desktop[0] = new Found(title, false);
                return false;
            }

            String process = processNameOf(hWnd);
            if (process != null)
                for (String player : PLAYERS)
                    if (player.equals(process)) {
                        learnedWindow = handle;
                        desktop[0] = new Found(title, false);
                        return false;
                    }

            // No process access, but the title gives it away on its own.
            if (isIdleSpotifyTitle(title)) {
                learnedWindow = handle;
                desktop[0] = new Found(title, false);
                return false;
            }

            if (browser[0] == null) {
                String tab = stripBrowser(title);
                if (tab != null && isMusicTab(tab)) browser[0] = new Found(tab, true);
            }

            return true;
        }, Pointer.NULL);

        return desktop[0] != null ? desktop[0] : browser[0];
    }

    /** The tab's own title, or null if this window is not a browser. */
    private static String stripBrowser(String title) {
        for (String suffix : BROWSERS)
            if (title.endsWith(suffix)) return title.substring(0, title.length() - suffix.length());
        return null;
    }

    /** The music site marker this tab title carries, or null if it is not one. */
    private static String siteOf(String tab) {
        for (String site : SITES)
            if (tab.endsWith(site)) return site;
        return null;
    }

    /**
     * Whether a browser tab is a music player at all.
     *
     * <p>A site suffix is only one of the three ways to tell, and it is the one that fails most often.
     * Spotify's web player REPLACES its tab title with the track while something is playing - it becomes
     * {@code "Track • Artist"} with no mention of Spotify anywhere - so waiting for a {@code "| Spotify"}
     * suffix means never recognising it precisely while it is playing, which is the only time it matters.
     *
     * <p>So the bullet counts on its own: no ordinary page title contains {@code " • "}. And a title
     * that merely STARTS with "Spotify" is the player sitting idle on one of its own pages, which is
     * worth recognising so it can be reported as nothing playing rather than fall through to some other
     * window.
     */
    private static boolean isMusicTab(String tab) {
        return siteOf(tab) != null || bulletIn(tab) >= 0 || spotifyPrefix(tab) > 0;
    }

    /**
     * Spotify web's separators between a track and its artist.
     *
     * <p>Both forms are looked for because the player has shipped both, and neither appears in an
     * ordinary page title - which is what makes either one on its own enough to identify the tab.
     */
    private static final String[] BULLETS = {" • ", " · "};

    /** Where a bullet separator starts in this text, or -1. */
    private static int bulletIn(String text) {
        for (String bullet : BULLETS) {
            int at = text.indexOf(bullet);
            if (at >= 0) return at;
        }
        return -1;
    }

    /** The bullet that {@link #bulletIn} found. */
    private static String bulletAt(String text, int index) {
        for (String bullet : BULLETS)
            if (text.startsWith(bullet, index)) return bullet;
        return BULLETS[0];
    }

    /** Dash forms Spotify uses after its own name in a tab title. */
    private static final String[] SPOTIFY_PREFIXES = {"spotify — ", "spotify – ", "spotify - ", "spotify: "};

    /**
     * How many characters to drop to get past a leading "Spotify —", or 0 if there is none.
     *
     * <p>The prefix is only a hint that this is the web player, not that anything is playing: the same
     * shape covers {@code "Spotify — Search"} and {@code "Spotify — Track · Artist"}. What is left after
     * it decides which, in {@link #parseTab}.
     */
    private static int spotifyPrefix(String tab) {
        String lower = tab.toLowerCase(Locale.ROOT);
        for (String prefix : SPOTIFY_PREFIXES)
            if (lower.startsWith(prefix)) return prefix.length();
        return 0;
    }

    /**
     * Turns a music site's tab title into an artist and a track.
     *
     * <p>Spotify's web player writes {@code "Track • Artist"} while playing; YouTube and SoundCloud
     * write the video or track name, which is usually already {@code "Artist - Title"}. So the site
     * suffix comes off first and then whichever separator is actually present decides the split.
     */
    private static Track parseTab(String tab) {
        String site = siteOf(tab);
        String text = site == null ? tab : tab.substring(0, tab.length() - site.length()).trim();
        if (text.isEmpty()) return null;

        // "Spotify — ..." is the web player; what follows is either a track or the name of one of its
        // own pages. Strip the prefix and judge what is left.
        int prefix = spotifyPrefix(text);
        if (prefix > 0) {
            String rest = text.substring(prefix).trim();

            // A page name is a plain phrase - "Search", "Home", "Your Library". A track always comes
            // with its artist attached by a separator, so requiring one is what tells them apart, and
            // reporting a page name as a song would be worse than reporting nothing at all.
            if (bulletIn(rest) < 0 && rest.indexOf(" - ") < 0) return null;

            text = rest;
        }

        // The bullet form puts the track FIRST, which is the opposite way round from the dash form.
        int bullet = bulletIn(text);
        if (bullet >= 0) {
            String separator = bulletAt(text, bullet);
            return new Track(text.substring(bullet + separator.length()).trim(), text.substring(0, bullet).trim());
        }

        return split(text);
    }

    /** {@code "Artist - Title"}. Split on the FIRST dash: track names contain them far more often. */
    private static Track split(String text) {
        int dash = text.indexOf(" - ");
        if (dash < 0) return new Track("", text);
        return new Track(text.substring(0, dash).trim(), text.substring(dash + 3).trim());
    }

    private static String processNameOf(Pointer hWnd) {
        IntByReference pid = new IntByReference();
        User32.INSTANCE.GetWindowThreadProcessId(hWnd, pid);
        if (pid.getValue() == 0) return null;

        // QUERY_LIMITED_INFORMATION is the least this can ask for and still get an image name, and it
        // is the one that works without elevation.
        Pointer process = Kernel32.INSTANCE.OpenProcess(0x1000, false, pid.getValue());
        if (process == null) return null;

        try {
            char[] buffer = new char[1024];
            IntByReference size = new IntByReference(buffer.length);
            if (!Kernel32.INSTANCE.QueryFullProcessImageNameW(process, 0, buffer, size)) return null;

            String path = new String(buffer, 0, size.getValue());
            int slash = Math.max(path.lastIndexOf('\\'), path.lastIndexOf('/'));
            return (slash < 0 ? path : path.substring(slash + 1)).toLowerCase(Locale.ROOT);
        } finally {
            Kernel32.INSTANCE.CloseHandle(process);
        }
    }

    private interface User32 extends Library {
        User32 INSTANCE = Native.load("user32", User32.class);

        boolean EnumWindows(EnumWindowsProc callback, Pointer data);

        boolean IsWindowVisible(Pointer hWnd);

        int GetWindowTextW(Pointer hWnd, char[] buffer, int max);

        int GetWindowThreadProcessId(Pointer hWnd, IntByReference pid);

        int GetClassNameW(Pointer hWnd, char[] buffer, int max);

        interface EnumWindowsProc extends Callback {
            /** Returning false stops the enumeration. */
            boolean callback(Pointer hWnd, Pointer data);
        }
    }

    private interface Kernel32 extends Library {
        Kernel32 INSTANCE = Native.load("kernel32", Kernel32.class);

        Pointer OpenProcess(int desiredAccess, boolean inheritHandle, int pid);

        boolean QueryFullProcessImageNameW(Pointer process, int flags, char[] buffer, IntByReference size);

        boolean CloseHandle(Pointer handle);
    }
}
