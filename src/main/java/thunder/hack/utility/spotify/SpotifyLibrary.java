package thunder.hack.utility.spotify;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import thunder.hack.utility.spotify.SpotifyModels.Entry;
import thunder.hack.utility.spotify.SpotifyModels.Hit;
import thunder.hack.utility.spotify.SpotifyModels.Kind;
import thunder.hack.utility.spotify.SpotifyModels.Playlist;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Your library: playlists, their contents, saving tracks, and search.
 *
 * <p>Everything here is a <b>pull</b> interface. The UI calls {@code playlists()} every frame and gets
 * whatever is known right now, together with whether that is still loading and why it might be empty; it
 * never waits and never registers a callback that could fire into a closed screen. The request behind it
 * is started by the matching {@code load} call, which is cheap to repeat because the cache and the
 * deduplication in {@link SpotifyApi} absorb it.
 *
 * <p><b>On two endpoints that moved.</b> The spec this was built to names {@code /me/library} for saving
 * and {@code /playlists/{id}/items} for playlist contents. The documented endpoints are
 * {@code /me/tracks} and {@code /playlists/{id}/tracks}. Rather than bet on either, both calls try the
 * documented path and, only on a 404 - which is what a path that does not exist returns - repeat the call
 * against the other spelling and remember which one worked. So it is correct under either API, and it
 * costs one extra request once per session at most.
 */
public final class SpotifyLibrary {
    private SpotifyLibrary() {
    }

    /** What a pulled collection is doing, so a panel can tell "empty" from "not yet". */
    public enum Status {
        Idle, Loading, Ready, Error
    }

    /** A collection plus why it looks the way it does. */
    public static final class Async<T> {
        private volatile T value;
        private volatile Status status = Status.Idle;
        private volatile String error = "";
        private volatile long stamp;

        Async(T empty) {
            this.value = empty;
        }

        public T get() {
            return value;
        }

        public Status status() {
            return status;
        }

        public String error() {
            return error;
        }

        boolean fresh(long ttlMs) {
            return status == Status.Ready && System.currentTimeMillis() - stamp < ttlMs;
        }

        /**
         * True for a few seconds after a failure.
         *
         * <p>Without this, a panel that loads on every frame and failed once would retry sixty times a
         * second for as long as you looked at the error - which on a 429 is the one thing guaranteed to
         * keep it a 429. The load calls treat this exactly like freshness: nothing to do yet.
         */
        boolean cooling() {
            return status == Status.Error && System.currentTimeMillis() - stamp < 5_000L;
        }

        /** True while a request for this is already out, so a per-frame caller does not start another. */
        boolean busy() {
            return status == Status.Loading && System.currentTimeMillis() - stamp < 15_000L;
        }

        void loading() {
            // The stamp moves on every transition, because cooling() and busy() both measure from it.
            this.stamp = System.currentTimeMillis();
            if (status != Status.Ready) status = Status.Loading;
        }

        void ready(T value) {
            this.value = value;
            this.status = Status.Ready;
            this.error = "";
            this.stamp = System.currentTimeMillis();
        }

        void failed(String error) {
            this.status = Status.Error;
            this.error = error;
            this.stamp = System.currentTimeMillis();
        }
    }

    /* ------------------------------------------------------------------ */
    /* Who you are                                                         */
    /* ------------------------------------------------------------------ */

    private static volatile String meId = "";
    private static volatile String meName = "";

    /**
     * Your account id.
     *
     * <p>Needed for one thing only: deciding which playlists you may change. "Remove item" is drawn on a
     * row only when the owner id matches this or the playlist is collaborative, so until this is known
     * the UI shows no destructive action rather than showing one that would be refused.
     */
    public static String meId() {
        if (meId.isEmpty()) loadMe();
        return meId;
    }

    public static String meName() {
        return meName;
    }

    /** When /me was last asked for, so a per-frame caller does not re-parse a cached answer every frame. */
    private static volatile long meAt;

    private static void loadMe() {
        if (!meId.isEmpty()) return;

        long now = System.currentTimeMillis();
        if (now - meAt < 5_000L) return;
        meAt = now;

        SpotifyApi.get("/me", 600_000L).thenAccept(result -> {
            if (!result.ok()) return;
            meId = SpotifyModels.str(result.obj(), "id");
            meName = SpotifyModels.str(result.obj(), "display_name");
        });
    }

    /* ------------------------------------------------------------------ */
    /* Saved tracks                                                        */
    /* ------------------------------------------------------------------ */

    /** Which spelling of the save endpoint this account's API actually has. Null until one answers. */
    private static volatile Boolean savesViaLibrary;

    /**
     * Toggles the current track in your library.
     *
     * <p>The heart flips at once and the request goes behind it; if the request fails, the check that
     * follows puts the real state back. A heart that waits for a round trip before moving is the single
     * most noticeable lag in a panel like this.
     */
    public static void toggleSaved() {
        SpotifyPlayer.Snapshot snapshot = SpotifyPlayer.state();
        if (snapshot.trackId().isEmpty()) return;

        setSaved(snapshot.trackId(), snapshot.trackUri(), !snapshot.saved());
    }

    public static void setSaved(String trackId, String trackUri, boolean saved) {
        if (trackId == null || trackId.isEmpty()) return;

        SpotifyPlayer.assumeSaved(trackId, saved);
        String method = saved ? "PUT" : "DELETE";

        if (Boolean.TRUE.equals(savesViaLibrary)) {
            library(method, trackUri, trackId);
            return;
        }

        SpotifyApi.send(method, "/me/tracks?ids=" + trackId, "{}").thenAccept(result -> {
            if (result.ok()) {
                savesViaLibrary = Boolean.FALSE;
                SpotifyApi.invalidate("/me/tracks/contains");
                return;
            }

            // 404 on this path means the endpoint is not there, which is the one failure worth answering
            // by trying the other spelling. Anything else - no scope, rate limit - would fail there too.
            if (result.status() == 404) library(method, trackUri, trackId);
            else revertSaved(trackId, result);
        });
    }

    private static void library(String method, String trackUri, String trackId) {
        String uri = trackUri == null || trackUri.isEmpty() ? "spotify:track:" + trackId : trackUri;

        SpotifyApi.send(method, "/me/library?uris=" + SpotifyAuth.enc(uri), "{}").thenAccept(result -> {
            if (result.ok()) {
                savesViaLibrary = Boolean.TRUE;
                SpotifyApi.invalidate("/me/tracks/contains");
            } else {
                revertSaved(trackId, result);
            }
        });
    }

    private static void revertSaved(String trackId, SpotifyApi.Result result) {
        lastError = SpotifyApi.describe(result);
        // Ask rather than assume the inverse: the request may have succeeded server-side and failed on
        // the way back, and guessing would then leave the heart permanently wrong.
        SpotifyApi.invalidate("/me/tracks/contains");
        SpotifyPlayer.refreshSaved(trackId);
    }

    private static volatile String lastError = "";

    /** The last thing that went wrong in this class, for a status line. */
    public static String lastError() {
        return lastError;
    }

    /* ------------------------------------------------------------------ */
    /* Playlists                                                           */
    /* ------------------------------------------------------------------ */

    private static final Async<List<Playlist>> PLAYLISTS = new Async<>(List.of());

    public static Async<List<Playlist>> playlists() {
        return PLAYLISTS;
    }

    /**
     * Loads your playlists, at most one page beyond the first.
     *
     * <p>Two pages is a hundred playlists, which covers nearly everyone, and the picker has a search box
     * for the rest. Walking every page would mean a dozen requests before the first row could be drawn,
     * on a screen whose whole job is to be instant.
     */
    public static void loadPlaylists(boolean force) {
        if (!force && (PLAYLISTS.fresh(60_000L) || PLAYLISTS.cooling() || PLAYLISTS.busy())) return;
        if (!SpotifyAuth.authorised()) return;

        PLAYLISTS.loading();
        loadMe();

        SpotifyApi.get("/me/playlists?limit=50", force ? 0L : 30_000L).thenAccept(first -> {
            if (!first.ok()) {
                PLAYLISTS.failed(SpotifyApi.describe(first));
                return;
            }

            List<Playlist> out = new ArrayList<>(page(first.obj()));

            int total = SpotifyModels.num(first.obj(), "total", out.size());
            if (total <= out.size()) {
                PLAYLISTS.ready(Collections.unmodifiableList(out));
                return;
            }

            SpotifyApi.get("/me/playlists?limit=50&offset=50", force ? 0L : 30_000L).thenAccept(second -> {
                if (second.ok()) out.addAll(page(second.obj()));
                PLAYLISTS.ready(Collections.unmodifiableList(out));
            });
        });
    }

    private static List<Playlist> page(JsonObject body) {
        List<Playlist> out = new ArrayList<>();

        JsonElement items = body.get("items");
        if (items == null || !items.isJsonArray()) return out;

        for (JsonElement e : items.getAsJsonArray()) {
            if (!e.isJsonObject()) continue;
            Playlist playlist = Playlist.of(e.getAsJsonObject());
            if (playlist != null) out.add(playlist);
        }
        return out;
    }

    /** Playlists whose name contains this text, case insensitively. The picker's search box. */
    public static List<Playlist> filterPlaylists(String query) {
        List<Playlist> all = PLAYLISTS.get();
        if (query == null || query.isBlank()) return all;

        String needle = query.toLowerCase().trim();
        List<Playlist> out = new ArrayList<>();
        for (Playlist playlist : all)
            if (playlist.name().toLowerCase().contains(needle) || playlist.owner().toLowerCase().contains(needle))
                out.add(playlist);
        return out;
    }

    /* ------------------------------------------------------------------ */
    /* Playlist contents                                                   */
    /* ------------------------------------------------------------------ */

    private static final Map<String, Async<List<Entry>>> TRACKS = new ConcurrentHashMap<>();
    /** Which spelling of the playlist-items endpoint answered. */
    private static volatile Boolean itemsPath;

    private static String tracksPath(String playlistId) {
        return "/playlists/" + playlistId + (Boolean.TRUE.equals(itemsPath) ? "/items" : "/tracks");
    }

    public static Async<List<Entry>> tracks(String playlistId) {
        return TRACKS.computeIfAbsent(playlistId, id -> new Async<>(List.of()));
    }

    /** Loads the first hundred tracks of a playlist - one page, which is what the panel shows. */
    public static void loadTracks(String playlistId, boolean force) {
        if (playlistId == null || playlistId.isEmpty()) return;

        Async<List<Entry>> holder = tracks(playlistId);
        if (!force && (holder.fresh(30_000L) || holder.cooling() || holder.busy())) return;

        holder.loading();
        requestTracks(playlistId, holder, Boolean.TRUE.equals(itemsPath));
    }

    private static void requestTracks(String playlistId, Async<List<Entry>> holder, boolean useItems) {
        String path = "/playlists/" + playlistId + (useItems ? "/items" : "/tracks") + "?limit=100";

        SpotifyApi.get(path, 15_000L).thenAccept(result -> {
            if (!result.ok()) {
                // The same 404 fallback as saving, and only on a 404.
                if (result.status() == 404 && !useItems) {
                    requestTracks(playlistId, holder, true);
                    return;
                }
                holder.failed(SpotifyApi.describe(result));
                return;
            }

            itemsPath = useItems;

            List<Entry> out = new ArrayList<>();
            JsonElement items = result.obj().get("items");
            if (items != null && items.isJsonArray())
                for (JsonElement e : items.getAsJsonArray()) {
                    if (!e.isJsonObject()) continue;

                    JsonObject item = e.getAsJsonObject();
                    String addedBy = item.has("added_by") && item.get("added_by").isJsonObject()
                            ? SpotifyModels.str(item.getAsJsonObject("added_by"), "id") : "";

                    Entry entry = Entry.of(item, addedBy);
                    if (entry != null) out.add(entry);
                }

            holder.ready(Collections.unmodifiableList(out));
        });
    }

    /* ------------------------------------------------------------------ */
    /* Changing playlists                                                  */
    /* ------------------------------------------------------------------ */

    /** Adds a track to a playlist, trying the documented path first. */
    public static void addToPlaylist(String playlistId, String trackUri) {
        if (playlistId == null || playlistId.isEmpty() || trackUri == null || trackUri.isEmpty()) return;

        JsonObject body = new JsonObject();
        JsonArray uris = new JsonArray();
        uris.add(trackUri);
        body.add("uris", uris);

        addToPlaylist(playlistId, body.toString(), Boolean.TRUE.equals(itemsPath));
    }

    private static void addToPlaylist(String playlistId, String body, boolean useItems) {
        String path = "/playlists/" + playlistId + (useItems ? "/items" : "/tracks");

        SpotifyApi.send("POST", path, body).thenAccept(result -> {
            if (result.ok()) {
                itemsPath = useItems;
                lastError = "";
                // The playlist is now one longer and its listing is stale in two places.
                SpotifyApi.invalidate("/playlists/" + playlistId);
                SpotifyApi.invalidate("/me/playlists");
                loadTracks(playlistId, true);
                loadPlaylists(true);
                return;
            }

            if (result.status() == 404 && !useItems) {
                addToPlaylist(playlistId, body, true);
                return;
            }
            lastError = SpotifyApi.describe(result);
        });
    }

    /**
     * Removes every occurrence of a track from a playlist.
     *
     * <p>Only called from a row that passed {@link Playlist#editableBy}, so a refusal here means the
     * permission changed under us rather than the UI having offered something impossible.
     */
    public static void removeFromPlaylist(String playlistId, String trackUri) {
        if (playlistId == null || playlistId.isEmpty() || trackUri == null || trackUri.isEmpty()) return;

        JsonObject track = new JsonObject();
        track.addProperty("uri", trackUri);

        JsonArray tracks = new JsonArray();
        tracks.add(track);

        JsonObject body = new JsonObject();
        body.add("tracks", tracks);

        removeFromPlaylist(playlistId, body.toString(), Boolean.TRUE.equals(itemsPath));
    }

    private static void removeFromPlaylist(String playlistId, String body, boolean useItems) {
        String path = "/playlists/" + playlistId + (useItems ? "/items" : "/tracks");

        SpotifyApi.send("DELETE", path, body).thenAccept(result -> {
            if (result.ok()) {
                itemsPath = useItems;
                lastError = "";
                SpotifyApi.invalidate("/playlists/" + playlistId);
                loadTracks(playlistId, true);
                loadPlaylists(true);
                return;
            }

            if (result.status() == 404 && !useItems) {
                removeFromPlaylist(playlistId, body, true);
                return;
            }
            lastError = SpotifyApi.describe(result);
        });
    }

    /**
     * Creates a playlist on your account, and optionally drops a track straight into it.
     *
     * <p>Creating one and then having to find it in the list to add the track would make the common case
     * - "new playlist for this song" - two steps, so the add is chained onto the create.
     */
    public static void createPlaylist(String name, String description, boolean publicList, String addUri) {
        if (name == null || name.isBlank()) return;
        String owner = meId();

        JsonObject body = new JsonObject();
        body.addProperty("name", name.trim());
        if (description != null && !description.isBlank()) body.addProperty("description", description.trim());
        body.addProperty("public", publicList);

        // The documented route is POST /users/{id}/playlists, and /me/playlists is the shorthand for it.
        // The shorthand needs no account id, so it works even before /me has answered.
        String path = owner.isEmpty() ? "/me/playlists" : "/users/" + owner + "/playlists";

        SpotifyApi.send("POST", path, body.toString()).thenAccept(result -> {
            if (!result.ok() && result.status() == 404 && !path.equals("/me/playlists")) {
                SpotifyApi.send("POST", "/me/playlists", body.toString()).thenAccept(retry -> created(retry, addUri));
                return;
            }
            created(result, addUri);
        });
    }

    private static void created(SpotifyApi.Result result, String addUri) {
        if (!result.ok()) {
            lastError = SpotifyApi.describe(result);
            return;
        }

        lastError = "";
        String id = SpotifyModels.str(result.obj(), "id");

        SpotifyApi.invalidate("/me/playlists");
        loadPlaylists(true);

        if (!id.isEmpty() && addUri != null && !addUri.isEmpty()) addToPlaylist(id, addUri);
    }

    /* ------------------------------------------------------------------ */
    /* Search                                                              */
    /* ------------------------------------------------------------------ */

    /** One finished search: the text it was for, and the hits per kind. */
    public record Results(String query, Map<Kind, List<Hit>> hits) {
        public static final Results EMPTY = new Results("", Map.of());

        public List<Hit> of(Kind kind) {
            return hits.getOrDefault(kind, List.of());
        }

        public boolean empty() {
            for (List<Hit> list : hits.values()) if (!list.isEmpty()) return false;
            return true;
        }
    }

    private static final AtomicReference<Results> RESULTS = new AtomicReference<>(Results.EMPTY);
    private static final Async<Results> SEARCH = new Async<>(Results.EMPTY);
    private static final SpotifyApi.Gate GATE = new SpotifyApi.Gate();
    private static final AtomicLong typedAt = new AtomicLong();

    public static Results results() {
        return RESULTS.get();
    }

    public static Status searchStatus() {
        return SEARCH.status();
    }

    public static String searchError() {
        return SEARCH.error();
    }

    /**
     * The limit per kind.
     *
     * <p>Ten, deliberately. The spec this was built to states the maximum for {@code /search} was reduced
     * to ten; the limit this API otherwise documents is fifty. Ten is valid under both, and for a panel
     * this size it is also simply the right number - six kinds at fifty hits each would be three hundred
     * rows and three hundred cover downloads for a query you are about to refine anyway.
     */
    public static final int LIMIT = 10;

    /**
     * Searches, with the keystrokes debounced and every superseded answer discarded.
     *
     * <p>Call it on every keypress. Nothing is sent until typing has paused, and the {@link SpotifyApi.Gate}
     * ticket means an answer that arrives after the query has moved on is dropped rather than flashing
     * old results over new ones. Both halves are needed: the debounce saves the requests, the gate saves
     * the correctness, since the network can still reorder two searches that were both actually sent.
     */
    public static void search(String query, List<Kind> kinds) {
        if (query == null || query.isBlank()) {
            GATE.bump();
            RESULTS.set(Results.EMPTY);
            SEARCH.ready(Results.EMPTY);
            return;
        }

        String trimmed = query.trim();
        int ticket = GATE.bump();
        long typed = System.currentTimeMillis();
        typedAt.set(typed);
        SEARCH.loading();

        SpotifyApi.async(() -> {
            try {
                // 280ms: shorter and every letter of a word becomes a request; longer and the results
                // feel like they are lagging the cursor.
                Thread.sleep(280L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }

            if (typedAt.get() != typed || !GATE.current(ticket)) return;
            fire(trimmed, kinds, ticket);
        });
    }

    private static void fire(String query, List<Kind> kinds, int ticket) {
        StringBuilder types = new StringBuilder();
        for (Kind kind : kinds) {
            if (types.length() > 0) types.append(',');
            types.append(kind.type);
        }

        String path = "/search?q=" + SpotifyAuth.enc(query)
                + "&type=" + types
                + "&limit=" + LIMIT;

        SpotifyApi.get(path, 20_000L).thenAccept(result -> {
            if (!GATE.current(ticket)) return;

            if (!result.ok()) {
                SEARCH.failed(SpotifyApi.describe(result));
                return;
            }

            Map<Kind, List<Hit>> hits = new EnumMap<>(Kind.class);
            for (Kind kind : kinds) {
                JsonElement page = result.obj().get(kind.key);
                if (page == null || !page.isJsonObject()) continue;

                JsonElement items = page.getAsJsonObject().get("items");
                if (items == null || !items.isJsonArray()) continue;

                List<Hit> list = new ArrayList<>();
                for (JsonElement e : items.getAsJsonArray()) {
                    // A search page can contain nulls - a track that was removed from the catalogue is
                    // still indexed for a while and comes back as a hole in the array.
                    if (e == null || !e.isJsonObject()) continue;
                    Hit hit = Hit.of(kind, e.getAsJsonObject());
                    if (hit != null) list.add(hit);
                }
                hits.put(kind, Collections.unmodifiableList(list));
            }

            Results results = new Results(query, Collections.unmodifiableMap(hits));
            RESULTS.set(results);
            SEARCH.ready(results);
        });
    }

    /* ------------------------------------------------------------------ */
    /* Teardown                                                            */
    /* ------------------------------------------------------------------ */

    /** Drops everything a logout must not leave behind. */
    public static void clear() {
        meId = "";
        meName = "";
        lastError = "";
        savesViaLibrary = null;
        itemsPath = null;
        TRACKS.clear();
        GATE.bump();
        RESULTS.set(Results.EMPTY);
        SEARCH.ready(Results.EMPTY);
        PLAYLISTS.ready(List.of());
        SpotifyApi.clearCache();
    }
}
