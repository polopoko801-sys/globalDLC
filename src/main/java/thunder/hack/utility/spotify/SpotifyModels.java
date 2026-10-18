package thunder.hack.utility.spotify;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * The shapes Spotify answers in, as immutable records.
 *
 * <p>Every one of these is built from a single JSON object by its own {@code of} factory, so a half
 * parsed response can never become a half filled record - the factory either returns a complete value
 * or null, and the caller keeps what it had.
 */
public final class SpotifyModels {
    private SpotifyModels() {
    }

    /** Repeat mode, spelled the way the API spells it so it can go straight into a query string. */
    public enum Repeat {
        off, track, context;

        /** The cycle the button walks: off to the whole context, then the one track, then off. */
        public Repeat next() {
            return this == off ? context : this == context ? track : off;
        }

        public static Repeat parse(String s) {
            if (s == null) return off;
            try {
                return valueOf(s);
            } catch (IllegalArgumentException e) {
                return off;
            }
        }
    }

    /** What a search result is. The order is the order the tabs are drawn in. */
    public enum Kind {
        Track("track", "tracks"),
        Album("album", "albums"),
        Artist("artist", "artists"),
        Playlist("playlist", "playlists"),
        Show("show", "shows"),
        Episode("episode", "episodes");

        /** The value {@code /search?type=} wants. */
        public final String type;
        /** The key the answer puts this page of results under. */
        public final String key;

        Kind(String type, String key) {
            this.type = type;
            this.key = key;
        }
    }

    /**
     * A playback device.
     *
     * @param restricted true when Spotify says it will not accept commands for this device - a web
     *                   player in some browsers, a speaker mid handover. Those are greyed rather than
     *                   being sent a command that is going to be refused.
     */
    public record Device(String id, String name, String type, int volume, boolean active, boolean restricted) {
        public static Device of(JsonObject o) {
            if (o == null || !o.has("id") || o.get("id").isJsonNull()) return null;
            return new Device(
                    str(o, "id"), str(o, "name"), str(o, "type"),
                    num(o, "volume_percent", -1),
                    bool(o, "is_active"), bool(o, "is_restricted"));
        }
    }

    /**
     * A playlist, with everything one row needs to draw itself.
     *
     * @param ownerId kept beside the display name because "may I change this one" is answered by
     *                comparing ids, never by comparing names.
     */
    public record Playlist(String id, String uri, String name, String owner, String ownerId,
                           int total, String imageUrl, boolean collaborative) {
        public static Playlist of(JsonObject o) {
            if (o == null || !o.has("id") || o.get("id").isJsonNull()) return null;

            JsonObject owner = o.has("owner") && o.get("owner").isJsonObject() ? o.getAsJsonObject("owner") : null;
            JsonObject tracks = o.has("tracks") && o.get("tracks").isJsonObject() ? o.getAsJsonObject("tracks") : null;

            return new Playlist(
                    str(o, "id"), str(o, "uri"), str(o, "name"),
                    owner == null ? "" : str(owner, "display_name"),
                    owner == null ? "" : str(owner, "id"),
                    tracks == null ? 0 : num(tracks, "total", 0),
                    image(o), bool(o, "collaborative"));
        }

        /** Whether this account may change the contents. */
        public boolean editableBy(String meId) {
            return collaborative || (meId != null && !meId.isEmpty() && meId.equals(ownerId));
        }
    }

    /**
     * One row of the queue, or one track inside a playlist.
     *
     * @param addedBy who put it in a playlist, empty for a queue entry. A collaborative playlist lets
     *                you remove your own additions, so this is what that check reads.
     */
    public record Entry(String id, String uri, String title, String artist, String album,
                        String imageUrl, long durationMs, String addedBy) {
        public static Entry of(JsonObject o) {
            return of(o, "");
        }

        public static Entry of(JsonObject o, String addedBy) {
            if (o == null) return null;

            // A playlist page wraps the track in an item; the queue hands it over bare.
            JsonObject track = o.has("track") && o.get("track").isJsonObject() ? o.getAsJsonObject("track") : o;
            if (!track.has("uri")) return null;

            JsonObject album = track.has("album") && track.get("album").isJsonObject()
                    ? track.getAsJsonObject("album") : null;

            return new Entry(
                    str(track, "id"), str(track, "uri"), str(track, "name"),
                    artists(track),
                    album == null ? str(track, "name") : str(album, "name"),
                    album == null ? image(track) : image(album),
                    longOf(track, "duration_ms", 0L),
                    addedBy);
        }
    }

    /** A search hit of any kind, flattened to what a row draws. */
    public record Hit(Kind kind, String id, String uri, String name, String subtitle,
                      String imageUrl, String link) {
        public static Hit of(Kind kind, JsonObject o) {
            if (o == null || !o.has("uri")) return null;

            String subtitle = switch (kind) {
                case Track, Episode -> artists(o).isEmpty() ? albumName(o) : artists(o);
                case Album -> artists(o);
                case Artist -> followers(o);
                case Playlist -> {
                    JsonObject owner = o.has("owner") && o.get("owner").isJsonObject() ? o.getAsJsonObject("owner") : null;
                    yield owner == null ? "" : str(owner, "display_name");
                }
                case Show -> str(o, "publisher");
            };

            JsonObject album = o.has("album") && o.get("album").isJsonObject() ? o.getAsJsonObject("album") : null;

            return new Hit(kind, str(o, "id"), str(o, "uri"), str(o, "name"), subtitle,
                    album != null ? image(album) : image(o), SpotifyModels.link(o));
        }

        private static String albumName(JsonObject o) {
            JsonObject album = o.has("album") && o.get("album").isJsonObject() ? o.getAsJsonObject("album") : null;
            return album == null ? "" : str(album, "name");
        }

        private static String followers(JsonObject o) {
            if (!o.has("followers") || !o.get("followers").isJsonObject()) return "ARTIST";
            int total = num(o.getAsJsonObject("followers"), "total", -1);
            if (total < 0) return "ARTIST";
            return total >= 1_000_000 ? (total / 1_000_000) + "M followers"
                    : total >= 1_000 ? (total / 1_000) + "K followers"
                    : total + " followers";
        }
    }

    /** The id part of a {@code spotify:kind:id} uri, or the whole string when it is already an id. */
    public static String idOf(String uri) {
        if (uri == null) return "";
        int last = uri.lastIndexOf(':');
        return last < 0 ? uri : uri.substring(last + 1);
    }

    /* ------------------------------------------------------------------ */
    /* JSON helpers - null safe, because every field here is optional in   */
    /* at least one of the shapes these records are built from.            */
    /* ------------------------------------------------------------------ */

    public static String str(JsonObject o, String key) {
        if (o == null || !o.has(key) || o.get(key).isJsonNull()) return "";
        JsonElement e = o.get(key);
        return e.isJsonPrimitive() ? e.getAsString() : "";
    }

    public static int num(JsonObject o, String key, int fallback) {
        if (o == null || !o.has(key) || o.get(key).isJsonNull()) return fallback;
        try {
            return o.get(key).getAsInt();
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    public static long longOf(JsonObject o, String key, long fallback) {
        if (o == null || !o.has(key) || o.get(key).isJsonNull()) return fallback;
        try {
            return o.get(key).getAsLong();
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    public static boolean bool(JsonObject o, String key) {
        if (o == null || !o.has(key) || o.get(key).isJsonNull()) return false;
        try {
            return o.get(key).getAsBoolean();
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Every artist on the object, comma joined. */
    public static String artists(JsonObject o) {
        if (o == null) return "";

        if (!o.has("artists") || !o.get("artists").isJsonArray()) {
            // A podcast episode carries a show rather than artists.
            if (o.has("show") && o.get("show").isJsonObject())
                return str(o.getAsJsonObject("show"), "name");
            return "";
        }

        StringBuilder sb = new StringBuilder();
        for (JsonElement e : o.getAsJsonArray("artists")) {
            if (!e.isJsonObject()) continue;
            if (sb.length() > 0) sb.append(", ");
            sb.append(str(e.getAsJsonObject(), "name"));
        }
        return sb.toString();
    }

    /**
     * The best image on the object.
     *
     * <p>Spotify lists images largest first, so index 0 is the full-resolution cover (usually 640px). The
     * widget's expanded cover is drawn big on a hi-res screen, where the 300px second image looked soft -
     * "low resolution". One cover is fetched at a time and cached, so the larger download is not a problem.
     */
    public static String image(JsonObject o) {
        if (o == null || !o.has("images") || !o.get("images").isJsonArray()) return "";

        JsonArray images = o.getAsJsonArray("images");
        if (images.isEmpty()) return "";

        JsonElement pick = images.get(0);
        return pick.isJsonObject() ? str(pick.getAsJsonObject(), "url") : "";
    }

    /** The open.spotify.com link, for "Open in Spotify" and "Copy Spotify Link". */
    public static String link(JsonObject o) {
        if (o == null || !o.has("external_urls") || !o.get("external_urls").isJsonObject()) return "";
        return str(o.getAsJsonObject("external_urls"), "spotify");
    }
}
