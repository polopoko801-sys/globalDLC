package thunder.hack.utility.spotify;

import org.jetbrains.annotations.Nullable;
import thunder.hack.utility.NowPlaying;

/**
 * Playback position without a Spotify login, worked out from the Spotify window title.
 *
 * <p>The title carries the track but no position, so the clock starts the moment the title changes - i.e. when
 * the song started - and pauses while the title reads plain "Spotify" (which is what it shows when paused).
 * It is exact as long as the song was not skipped into the middle; the callers' offsets cover the rest.
 *
 * <p>One clock for everyone (the menu's music card, TextInMinecraft): the window scan walks every top-level
 * window, so it is polled here a few times a second and shared, never per caller per frame.
 */
public final class TitleClock {
    private TitleClock() {
    }

    private static final long POLL_MS = 250L;

    private static NowPlaying.Track polled;
    private static long polledAt;
    private static String key = "";
    private static long since;
    /** When the title went idle (paused), 0 while playing. */
    private static long pausedAt;

    private static void poll() {
        long now = System.currentTimeMillis();
        if (now - polledAt <= POLL_MS) return;
        polledAt = now;
        polled = NowPlaying.current();

        if (polled == null) {
            // paused (or closed): hold the clock so resuming the same song carries on from where it was
            if (!key.isEmpty() && pausedAt == 0L) pausedAt = now;
            return;
        }
        String k = polled.artist() + " - " + polled.title();
        if (!k.equals(key)) {
            key = k;
            // the change happened somewhere in the last poll interval; take the middle of it
            since = now - POLL_MS / 2;
            pausedAt = 0L;
        } else if (pausedAt != 0L) {
            since += now - pausedAt;
            pausedAt = 0L;
        }
    }

    /*
     * The Windows media session comes first: it reports the exact position, the length and play/pause, so it is
     * right even when the song was started before the game, skipped into, or the window title lagged. The
     * window-title clock below is only the fallback for when that bridge is not available.
     */

    /** The track playing, or null when nothing is. */
    public static @Nullable NowPlaying.Track current() {
        MediaSession.State s = MediaSession.state();
        if (s != null) return new NowPlaying.Track(s.artist(), s.title());
        poll();
        return polled;
    }

    /** Milliseconds into the current track. */
    public static long position() {
        MediaSession.State s = MediaSession.state();
        if (s != null) return s.positionNow();
        poll();
        if (key.isEmpty()) return 0L;
        long end = pausedAt != 0L ? pausedAt : System.currentTimeMillis();
        return Math.max(0L, end - since);
    }

    /** The track's length in milliseconds, or 0 when the source does not say. */
    public static long duration() {
        MediaSession.State s = MediaSession.state();
        return s != null ? s.duration() : 0L;
    }

    /** False while paused (only known from the media session; the window title cannot tell). */
    public static boolean playing() {
        MediaSession.State s = MediaSession.state();
        return s == null || s.playing();
    }

    /** A stable id for the current track, for callers that need to notice a change. */
    public static String key() {
        MediaSession.State s = MediaSession.state();
        if (s != null) return s.artist() + " - " + s.title();
        poll();
        return key;
    }
}
