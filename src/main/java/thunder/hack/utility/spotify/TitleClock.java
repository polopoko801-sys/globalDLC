package thunder.hack.utility.spotify;

import org.jetbrains.annotations.Nullable;
import thunder.hack.utility.NowPlaying;

import java.util.Locale;

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
     *
     * The session is only trusted when it IS Spotify. Windows publishes one session per player, and a browser
     * that happens to be playing TikTok, YouTube or a podcast shows up there too - with a position that has
     * nothing to do with the song. That is exactly how the lyrics ended up timed against a random video. For a
     * browser session the Spotify window/tab has to be showing the same track before the clock is used.
     */

    /** Titles of feeds and clips - a session playing one of these is not a song, whatever it looks like. */
    private static final String[] NOISE = {
            "tiktok", "instagram", "reel", "shorts", "twitch", "facebook", "twitter", " x.com",
            "soundcloud", "podcast", "radio", "live stream"
    };

    /** The media session, if it is Spotify's (the app, or the web player in a browser). */
    private static MediaSession.State spotifySession() {
        MediaSession.State state = MediaSession.state();
        if (state == null) return null;
        if (state.spotify()) return state;

        // A browser session says nothing about which tab is playing, so it first has to agree with the
        // Spotify window/tab. poll() keeps that reading a quarter-second fresh.
        poll();
        if (polled != null && sameTrack(polled, state)) return state;

        // The web player reports an EMPTY artist and puts "Track • Artist" in the title - so the artist field
        // alone cannot be the test, or the browser case (open.spotify.com in a background tab) was thrown out
        // and nothing at all was shown. Split the title first, then judge the song.
        NowPlaying.Track track = trackOf(state);
        if (track == null || track.artist().isBlank()) return null;
        if (!state.playing() || state.duration() < 45_000L) return null;

        String text = (track.title() + " " + track.artist()).toLowerCase(Locale.ROOT);
        for (String noise : NOISE)
            if (text.contains(noise)) return null;
        return state;
    }

    /**
     * What the media session can see right now, one short line - so a player with no lyrics on screen can be
     * told whether Spotify is invisible to us or merely not what Windows reports.
     */
    public static String sourceSummary() {
        MediaSession.State state = MediaSession.state();
        if (state == null) return "nic";
        String track = state.artist() == null || state.artist().isBlank()
                ? state.title()
                : state.artist() + " - " + state.title();
        return state.app() + ": " + track;
    }

    /** The track playing, or null when nothing is. */
    public static @Nullable NowPlaying.Track current() {
        MediaSession.State state = spotifySession();
        if (state != null) return trackOf(state);
        poll();
        return polled;
    }

    /**
     * Spotify's own player bar (read from its web player by {@link SpotifyCard}) for the track being shown -
     * the one source that always knows about a pause. Without it a paused song fell through to the window-title
     * clock below, which cannot see a pause in the web player and kept counting.
     */
    private static SpotifyCard.@Nullable Playback exact() {
        NowPlaying.Track track = current();
        return track == null ? null : SpotifyCard.playback(track.title());
    }

    /** Milliseconds into the current track. */
    public static long position() {
        SpotifyCard.Playback exact = exact();
        if (exact != null) return exact.positionMs();
        MediaSession.State state = spotifySession();
        if (state != null) return state.positionNow();
        poll();
        if (key.isEmpty()) return 0L;
        long end = pausedAt != 0L ? pausedAt : System.currentTimeMillis();
        return Math.max(0L, end - since);
    }

    /** The track's length in milliseconds, or 0 when the source does not say. */
    public static long duration() {
        SpotifyCard.Playback exact = exact();
        if (exact != null && exact.durationMs() > 0) return exact.durationMs();
        MediaSession.State state = spotifySession();
        return state != null ? state.duration() : 0L;
    }

    /** False while paused (only known from the media session; the window title cannot tell). */
    public static boolean playing() {
        SpotifyCard.Playback exact = exact();
        if (exact != null) return exact.playing();
        MediaSession.State state = spotifySession();
        if (state != null) return state.playing();
        poll();
        return !key.isEmpty() && pausedAt == 0L;
    }

    /** A stable id for the current track, for callers that need to notice a change. */
    public static String key() {
        MediaSession.State state = spotifySession();
        if (state != null) {
            NowPlaying.Track track = trackOf(state);
            return track == null ? "" : track.artist() + " - " + track.title();
        }
        poll();
        return key;
    }

    /**
     * The track a media session describes.
     *
     * <p>The app sends the artist as its own field, but the web player (and plenty of other sources) put it in
     * the title as {@code "Track • Artist"} with an empty artist. Split that here once, so every caller gets a
     * title and an artist instead of a search for a string with a bullet in it.
     */
    private static @Nullable NowPlaying.Track trackOf(MediaSession.State state) {
        String title = state.title() == null ? "" : state.title().strip();
        String artist = state.artist() == null ? "" : state.artist().strip();

        for (String suffix : new String[]{" | Spotify", " - Spotify"})
            if (title.endsWith(suffix)) title = title.substring(0, title.length() - suffix.length()).strip();

        if (artist.isEmpty()) {
            int bullet = title.indexOf(" • ");
            if (bullet < 0) bullet = title.indexOf(" · ");
            if (bullet > 0) {
                String separator = title.startsWith(" • ", bullet) ? " • " : " · ";
                artist = title.substring(bullet + separator.length()).strip();
                title = title.substring(0, bullet).strip();
            }
        }

        if (title.isEmpty()) return null;
        return new NowPlaying.Track(artist, title);
    }

    /** Whether the window/tab title and the media session are talking about the same song. */
    private static boolean sameTrack(NowPlaying.Track window, MediaSession.State session) {
        String a = norm(window.title()), b = norm(session.title());
        if (a.isEmpty() || b.isEmpty()) return false;
        boolean title = a.equals(b) || a.contains(b) || b.contains(a);

        String x = norm(window.artist()), y = norm(session.artist());
        boolean artist = x.isEmpty() || y.isEmpty() || x.equals(y) || x.contains(y) || y.contains(x);

        return title && artist;
    }

    private static String norm(String text) {
        if (text == null) return "";
        return text.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", " ").strip();
    }
}
