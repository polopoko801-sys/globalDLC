package thunder.hack.utility.spotify;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import thunder.hack.utility.spotify.SpotifyModels.Device;
import thunder.hack.utility.spotify.SpotifyModels.Entry;
import thunder.hack.utility.spotify.SpotifyModels.Repeat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * What is playing, as one immutable snapshot, and every command that changes it.
 *
 * <p><b>The snapshot is the point of this class.</b> A HUD that reads the title from one response, the
 * cover from another and the position from a third will, sooner or later, draw the previous track's cover
 * over this track's title - the three answers do not arrive at the same instant, and a skip lands between
 * them. So nothing here is stored field by field: one poll builds one {@link Snapshot} from one response,
 * and that whole object is swapped into place at once. Everything the UI draws in a frame comes from the
 * same object, which means the same track id: the right cover, the right title, the right artist, the
 * right position, always together.
 *
 * <p><b>Position is interpolated, not polled.</b> Asking Spotify where it is sixty times a second would be
 * rate limited within seconds and would still be no more accurate than a clock. The snapshot carries the
 * position it was taken at plus the moment it was taken, and {@link Snapshot#progressMs()} adds the time
 * since. A poll every second or two then corrects the drift.
 *
 * <p><b>Commands are optimistic.</b> Pressing pause flips the local snapshot immediately and sends the
 * request behind it, because a button that visibly waits 200ms for a server reads as a broken button. The
 * poll that follows is the authority: if the command failed, the next poll quietly puts the truth back.
 */
public final class SpotifyPlayer {
    private SpotifyPlayer() {
    }

    /**
     * One coherent view of playback.
     *
     * @param trackId    the id everything else in this record belongs to. Empty means nothing is playing.
     * @param progressAt the moment {@code basePosition} was true, which is what makes the bar smooth
     *                   without polling.
     * @param saved      whether the track is in your library. Tri-state in practice: see {@link #savedKnown}.
     */
    public record Snapshot(String trackId, String trackUri, String title, String artist, String album,
                           String albumUri, String artistUri, String imageUrl, String link,
                           long durationMs, long basePosition, long progressAt, boolean playing,
                           boolean shuffle, Repeat repeat, int volume,
                           String deviceId, String deviceName, String deviceType, boolean deviceRestricted,
                           String contextUri, boolean saved, boolean savedKnown, long stamp) {

        public static final Snapshot EMPTY = new Snapshot("", "", "", "", "", "", "", "", "",
                0L, 0L, 0L, false, false, Repeat.off, -1, "", "", "", false, "", false, false, 0L);

        public boolean present() {
            return !trackId.isEmpty() || !title.isEmpty();
        }

        /** Where playback is now: the polled position plus the time since, clamped to the track. */
        public long progressMs() {
            if (!present()) return 0L;

            long elapsed = playing ? Math.max(0L, System.currentTimeMillis() - progressAt) : 0L;
            long position = basePosition + elapsed;
            return durationMs > 0 ? Math.min(position, durationMs) : position;
        }

        public float fraction() {
            return durationMs <= 0 ? 0f : Math.clamp(progressMs() / (float) durationMs, 0f, 1f);
        }

        /** True when the device cannot take commands, so the transport is drawn disabled. */
        public boolean controllable() {
            return !deviceRestricted;
        }

        private Snapshot withSaved(boolean value) {
            return new Snapshot(trackId, trackUri, title, artist, album, albumUri, artistUri, imageUrl, link,
                    durationMs, basePosition, progressAt, playing, shuffle, repeat, volume,
                    deviceId, deviceName, deviceType, deviceRestricted, contextUri, value, true, stamp);
        }

        private Snapshot withPlaying(boolean value) {
            // Rebasing the position is what stops the bar jumping when you pause: the time already
            // elapsed is folded into the base, so unpausing carries on from where the eye left it.
            return new Snapshot(trackId, trackUri, title, artist, album, albumUri, artistUri, imageUrl, link,
                    durationMs, progressMs(), System.currentTimeMillis(), value, shuffle, repeat, volume,
                    deviceId, deviceName, deviceType, deviceRestricted, contextUri, saved, savedKnown, stamp);
        }

        private Snapshot withPosition(long position) {
            return new Snapshot(trackId, trackUri, title, artist, album, albumUri, artistUri, imageUrl, link,
                    durationMs, Math.max(0L, position), System.currentTimeMillis(), playing, shuffle, repeat, volume,
                    deviceId, deviceName, deviceType, deviceRestricted, contextUri, saved, savedKnown, stamp);
        }

        private Snapshot withShuffle(boolean value) {
            return new Snapshot(trackId, trackUri, title, artist, album, albumUri, artistUri, imageUrl, link,
                    durationMs, basePosition, progressAt, playing, value, repeat, volume,
                    deviceId, deviceName, deviceType, deviceRestricted, contextUri, saved, savedKnown, stamp);
        }

        private Snapshot withRepeat(Repeat value) {
            return new Snapshot(trackId, trackUri, title, artist, album, albumUri, artistUri, imageUrl, link,
                    durationMs, basePosition, progressAt, playing, shuffle, value, volume,
                    deviceId, deviceName, deviceType, deviceRestricted, contextUri, saved, savedKnown, stamp);
        }

        private Snapshot withVolume(int value) {
            return new Snapshot(trackId, trackUri, title, artist, album, albumUri, artistUri, imageUrl, link,
                    durationMs, basePosition, progressAt, playing, shuffle, repeat, Math.clamp(value, 0, 100),
                    deviceId, deviceName, deviceType, deviceRestricted, contextUri, saved, savedKnown, stamp);
        }
    }

    private static final AtomicReference<Snapshot> STATE = new AtomicReference<>(Snapshot.EMPTY);
    private static final AtomicReference<List<Device>> DEVICES = new AtomicReference<>(List.of());
    private static final AtomicReference<List<Entry>> QUEUE = new AtomicReference<>(List.of());

    /** The last moment something drew this, so a widget nobody can see costs no requests. */
    private static volatile long touched;
    /** How often to poll, set by whoever is drawing: a panel wants a second, a compact island does not. */
    private static volatile long interval = 2_000L;
    private static volatile boolean started;
    private static volatile String error = "";

    public static Snapshot state() {
        return STATE.get();
    }

    public static List<Device> devices() {
        return DEVICES.get();
    }

    public static List<Entry> queue() {
        return QUEUE.get();
    }

    public static String error() {
        return error;
    }

    /* ------------------------------------------------------------------ */
    /* Polling                                                             */
    /* ------------------------------------------------------------------ */

    /**
     * Tells the poller that something is on screen and how fresh it needs to be.
     *
     * <p>Called from render, every frame, by whatever is drawing - this is the only coupling between the
     * draw and the network, and it is one field write. Stop drawing and the polling stops on its own
     * within a few seconds, which is why having the widget enabled but hidden behind a screen costs
     * nothing.
     */
    public static void touch(long wantedIntervalMs) {
        touched = System.currentTimeMillis();
        interval = Math.clamp(wantedIntervalMs, 800L, 10_000L);
        start();
    }

    private static synchronized void start() {
        if (started) return;
        started = true;

        Thread thread = new Thread(SpotifyPlayer::loop, "Gombal-SpotifyPoll");
        thread.setDaemon(true);
        thread.setPriority(Thread.NORM_PRIORITY - 2);
        thread.start();
    }

    private static void loop() {
        while (true) {
            try {
                long idle = System.currentTimeMillis() - touched;

                // Nothing has drawn it for five seconds: sleep in long steps until something does. This
                // is what keeps a HUD element that is enabled but off-screen from burning the rate limit.
                if (idle > 5_000L || !SpotifyAuth.authorised()) {
                    Thread.sleep(1_000L);
                    continue;
                }

                poll();
                Thread.sleep(interval);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Throwable t) {
                // The poller must outlive every possible failure: if this thread dies the widget freezes
                // on its last snapshot with no way back short of a restart.
                try {
                    Thread.sleep(2_000L);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    /**
     * One poll.
     *
     * <p>Fire and forget, never {@code join()}. The API pool has three threads; if the poller blocked on
     * one of its own requests, three blocked pollers would be holding every thread the request needs to
     * run on, and the whole thing would deadlock until the game closed. So this hands the answer to
     * {@link #accept} whenever it turns up, and the loop below just keeps time.
     */
    private static void poll() {
        // additional_types is what makes a podcast episode come back as an episode instead of as null,
        // which otherwise reads as "nothing is playing" in the middle of a show.
        SpotifyApi.get("/me/player?additional_types=track,episode", 0L)
                .thenAccept(SpotifyPlayer::accept);
    }

    private static void accept(SpotifyApi.Result result) {
        if (!result.ok()) {
            // 204 is "no active device", which is a real state and not an error: Spotify is installed,
            // nothing is playing anywhere. Anything else is worth reporting.
            if (result.status() == 204) {
                STATE.set(Snapshot.EMPTY);
                error = "";
            } else {
                error = SpotifyApi.describe(result);
            }
            return;
        }

        error = "";
        if (result.status() == 204 || result.body() == null || result.body().isJsonNull()) {
            STATE.set(Snapshot.EMPTY);
            return;
        }

        Snapshot snapshot = parse(result.obj());
        Snapshot previous = STATE.get();

        // Carry the saved flag across only while the track is the same one; a new track's library status
        // is unknown until it has been asked for, and showing the previous answer would light the heart
        // on a track you have never saved.
        if (snapshot.trackId().equals(previous.trackId()) && previous.savedKnown())
            snapshot = snapshot.withSaved(previous.saved());

        STATE.set(snapshot);

        if (!snapshot.trackId().isEmpty() && !snapshot.savedKnown()) refreshSaved(snapshot.trackId());
    }

    private static Snapshot parse(JsonObject player) {
        JsonObject item = player.has("item") && player.get("item").isJsonObject()
                ? player.getAsJsonObject("item") : null;
        if (item == null) return Snapshot.EMPTY;

        JsonObject device = player.has("device") && player.get("device").isJsonObject()
                ? player.getAsJsonObject("device") : null;
        JsonObject album = item.has("album") && item.get("album").isJsonObject()
                ? item.getAsJsonObject("album") : null;
        JsonObject context = player.has("context") && player.get("context").isJsonObject()
                ? player.getAsJsonObject("context") : null;

        String artistUri = "";
        if (item.has("artists") && item.get("artists").isJsonArray()) {
            JsonArray artists = item.getAsJsonArray("artists");
            if (!artists.isEmpty() && artists.get(0).isJsonObject())
                artistUri = SpotifyModels.str(artists.get(0).getAsJsonObject(), "uri");
        }

        return new Snapshot(
                SpotifyModels.str(item, "id"),
                SpotifyModels.str(item, "uri"),
                SpotifyModels.str(item, "name"),
                SpotifyModels.artists(item),
                album == null ? SpotifyModels.str(item, "name") : SpotifyModels.str(album, "name"),
                album == null ? "" : SpotifyModels.str(album, "uri"),
                artistUri,
                album == null ? SpotifyModels.image(item) : SpotifyModels.image(album),
                SpotifyModels.link(item),
                SpotifyModels.longOf(item, "duration_ms", 0L),
                SpotifyModels.longOf(player, "progress_ms", 0L),
                System.currentTimeMillis(),
                SpotifyModels.bool(player, "is_playing"),
                SpotifyModels.bool(player, "shuffle_state"),
                Repeat.parse(SpotifyModels.str(player, "repeat_state")),
                device == null ? -1 : SpotifyModels.num(device, "volume_percent", -1),
                device == null ? "" : SpotifyModels.str(device, "id"),
                device == null ? "" : SpotifyModels.str(device, "name"),
                device == null ? "" : SpotifyModels.str(device, "type"),
                device != null && SpotifyModels.bool(device, "is_restricted"),
                context == null ? "" : SpotifyModels.str(context, "uri"),
                false, false,
                System.currentTimeMillis());
    }

    /** Asks whether this exact track id is in the library, and files the answer against that id. */
    static void refreshSaved(String trackId) {
        if (trackId == null || trackId.isEmpty()) return;

        SpotifyApi.get("/me/tracks/contains?ids=" + trackId, 30_000L).thenAccept(result -> {
            if (!result.ok() || result.body() == null || !result.body().isJsonArray()) return;

            JsonArray array = result.body().getAsJsonArray();
            if (array.isEmpty()) return;

            boolean saved;
            try {
                saved = array.get(0).getAsBoolean();
            } catch (RuntimeException e) {
                return;
            }

            // The id is checked again on the way in: by the time this answer arrives the track may have
            // changed, and filing it against the new one is exactly the bug this class exists to avoid.
            STATE.updateAndGet(current -> current.trackId().equals(trackId) ? current.withSaved(saved) : current);
        });
    }

    /** Local-only flip, for the moment a Save press is sent. */
    static void assumeSaved(String trackId, boolean saved) {
        STATE.updateAndGet(current -> current.trackId().equals(trackId) ? current.withSaved(saved) : current);
    }

    public static void clear() {
        STATE.set(Snapshot.EMPTY);
        DEVICES.set(List.of());
        QUEUE.set(List.of());
        error = "";
    }

    /* ------------------------------------------------------------------ */
    /* Devices and queue                                                   */
    /* ------------------------------------------------------------------ */

    /**
     * When each list was last asked for.
     *
     * <p>These two are called from render, every frame. The cache inside {@link SpotifyApi} stops the
     * network traffic, but a cache hit completes its future on the calling thread - so without this guard
     * the render thread would re-parse the same device and queue arrays sixty times a second. The guard,
     * not the cache, is what keeps that off the frame.
     */
    private static volatile long devicesAt;
    private static volatile long queueAt;

    /** Refreshes the device list. Cached for ten seconds - devices appear and vanish slowly. */
    public static void refreshDevices() {
        long now = System.currentTimeMillis();
        if (now - devicesAt < 3_000L) return;
        devicesAt = now;

        SpotifyApi.get("/me/player/devices", 10_000L).thenAccept(result -> {
            if (!result.ok()) return;

            List<Device> devices = new ArrayList<>();
            JsonElement array = result.obj().get("devices");
            if (array != null && array.isJsonArray())
                for (JsonElement e : array.getAsJsonArray()) {
                    if (!e.isJsonObject()) continue;
                    Device device = Device.of(e.getAsJsonObject());
                    if (device != null) devices.add(device);
                }

            DEVICES.set(Collections.unmodifiableList(devices));
        });
    }

    /**
     * Refreshes the queue.
     *
     * <p>Five seconds of cache: the queue only changes when you or Spotify change it, and both of those
     * invalidate it explicitly, so a shorter TTL would buy nothing but requests.
     */
    public static void refreshQueue() {
        long now = System.currentTimeMillis();
        if (now - queueAt < 2_000L) return;
        queueAt = now;

        SpotifyApi.get("/me/player/queue", 5_000L).thenAccept(result -> {
            if (!result.ok()) return;

            List<Entry> entries = new ArrayList<>();
            JsonElement array = result.obj().get("queue");
            if (array != null && array.isJsonArray())
                for (JsonElement e : array.getAsJsonArray()) {
                    if (!e.isJsonObject()) continue;
                    Entry entry = Entry.of(e.getAsJsonObject());
                    if (entry != null) entries.add(entry);
                }

            QUEUE.set(Collections.unmodifiableList(entries));
        });
    }

    /* ------------------------------------------------------------------ */
    /* Commands                                                            */
    /* ------------------------------------------------------------------ */

    /**
     * The device every command is aimed at.
     *
     * <p>Appended explicitly rather than relying on Spotify's notion of "the active device": with no
     * device_id a command goes wherever Spotify last saw activity, which during a handover is not where
     * the user is looking.
     */
    private static String target(String query) {
        String id = STATE.get().deviceId();
        if (id.isEmpty()) return query;
        return query.isEmpty() ? "?device_id=" + id : query + "&device_id=" + id;
    }

    public static void toggle() {
        Snapshot snapshot = STATE.get();
        if (snapshot.playing()) pause();
        else resume();
    }

    public static void resume() {
        STATE.updateAndGet(s -> s.withPlaying(true));
        SpotifyApi.command("PUT", "/me/player/play" + target(""), "{}");
        recheck();
    }

    public static void pause() {
        STATE.updateAndGet(s -> s.withPlaying(false));
        SpotifyApi.command("PUT", "/me/player/pause" + target(""), null);
        recheck();
    }

    public static void next() {
        SpotifyApi.command("POST", "/me/player/next" + target(""), null);
        // A skip changes the track, so the queue that was listed is now wrong.
        SpotifyApi.invalidate("/me/player/queue");
        recheck();
    }

    public static void previous() {
        SpotifyApi.command("POST", "/me/player/previous" + target(""), null);
        SpotifyApi.invalidate("/me/player/queue");
        recheck();
    }

    /** Seeks, and moves the bar at once so dragging it feels attached to the cursor. */
    public static void seek(long positionMs) {
        Snapshot snapshot = STATE.get();
        long clamped = snapshot.durationMs() > 0
                ? Math.clamp(positionMs, 0L, snapshot.durationMs())
                : Math.max(0L, positionMs);

        STATE.updateAndGet(s -> s.withPosition(clamped));
        SpotifyApi.command("PUT", "/me/player/seek?position_ms=" + clamped + target("").replace("?", "&"), null);
        recheck();
    }

    public static void volume(int percent) {
        int clamped = Math.clamp(percent, 0, 100);
        STATE.updateAndGet(s -> s.withVolume(clamped));
        SpotifyApi.command("PUT", "/me/player/volume?volume_percent=" + clamped + target("").replace("?", "&"), null);
    }

    public static void shuffle(boolean on) {
        STATE.updateAndGet(s -> s.withShuffle(on));
        SpotifyApi.command("PUT", "/me/player/shuffle?state=" + on + target("").replace("?", "&"), null);
        recheck();
    }

    public static void repeat(Repeat mode) {
        STATE.updateAndGet(s -> s.withRepeat(mode));
        SpotifyApi.command("PUT", "/me/player/repeat?state=" + mode.name() + target("").replace("?", "&"), null);
        recheck();
    }

    /** Moves playback to another device, keeping it playing if it was. */
    public static void transfer(String deviceId) {
        if (deviceId == null || deviceId.isEmpty()) return;

        JsonObject body = new JsonObject();
        JsonArray ids = new JsonArray();
        ids.add(deviceId);
        body.add("device_ids", ids);
        body.addProperty("play", STATE.get().playing());

        SpotifyApi.command("PUT", "/me/player", body.toString());
        SpotifyApi.invalidate("/me/player");
        recheck();
    }

    /** Plays a context (album, playlist, artist) or, for a bare track, that one track. */
    public static void play(String uri) {
        if (uri == null || uri.isEmpty()) return;

        JsonObject body = new JsonObject();
        if (uri.contains(":track:") || uri.contains(":episode:")) {
            JsonArray uris = new JsonArray();
            uris.add(uri);
            body.add("uris", uris);
        } else {
            body.addProperty("context_uri", uri);
        }

        SpotifyApi.command("PUT", "/me/player/play" + target(""), body.toString());
        SpotifyApi.invalidate("/me/player/queue");
        recheck();
    }

    /** Plays one track from inside a context, so the rest of the album or playlist follows it. */
    public static void playWithin(String contextUri, String trackUri) {
        if (contextUri == null || contextUri.isEmpty()) {
            play(trackUri);
            return;
        }

        JsonObject body = new JsonObject();
        body.addProperty("context_uri", contextUri);

        JsonObject offset = new JsonObject();
        offset.addProperty("uri", trackUri);
        body.add("offset", offset);

        SpotifyApi.command("PUT", "/me/player/play" + target(""), body.toString());
        SpotifyApi.invalidate("/me/player/queue");
        recheck();
    }

    public static void enqueue(String uri) {
        if (uri == null || uri.isEmpty()) return;

        SpotifyApi.command("POST", "/me/player/queue?uri=" + SpotifyAuth.enc(uri)
                + target("").replace("?", "&"), null);
        SpotifyApi.invalidate("/me/player/queue");

        // Re-list shortly after, so the new entry appears in an open Queue panel without a manual refresh.
        // The guard above has to be cleared for that, or an add made within two seconds of the last listing
        // would be the one add that does not show up.
        after(600L, () -> {
            queueAt = 0L;
            refreshQueue();
        });
    }

    /**
     * Why there is no "remove from queue".
     *
     * <p>Spotify's API exposes adding to the queue and nothing else - there is no endpoint to remove one
     * entry or to empty it, in any scope. The honest way to drop the rest of a queue is to start playing
     * something else, which replaces it, and that is what the UI offers instead of a button that could
     * only ever fail.
     */
    public static boolean canRemoveFromQueue() {
        return false;
    }

    /**
     * Re-polls just after a command, so the UI is corrected if the command did not take.
     *
     * <p>350ms is the interesting delay: long enough that Spotify has applied the change (polling sooner
     * reliably returns the previous state and makes the UI flicker back), short enough that a failed
     * command does not sit on screen looking like it worked.
     */
    private static void recheck() {
        SpotifyApi.invalidate("/me/player");
        after(350L, SpotifyPlayer::poll);
    }

    private static void after(long delayMs, Runnable task) {
        SpotifyApi.async(() -> {
            try {
                Thread.sleep(delayMs);
                task.run();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Throwable ignored) {
            }
        });
    }

    /** mm:ss, for both the elapsed and the total stamps. */
    public static String stamp(long ms) {
        if (ms <= 0) return "0:00";

        long total = ms / 1000L;
        long hours = total / 3600L;
        long minutes = (total % 3600L) / 60L;
        long seconds = total % 60L;

        return hours > 0
                ? String.format("%d:%02d:%02d", hours, minutes, seconds)
                : String.format("%d:%02d", minutes, seconds);
    }
}
