package thunder.hack.utility;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.util.Identifier;
import thunder.hack.core.manager.client.ConfigManager;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static thunder.hack.features.modules.Module.mc;

/**
 * The artwork for whatever is playing, found from the track name alone.
 *
 * <p>A window title is all {@link NowPlaying} can see - there is no cover in it, and no player exposes
 * one to us. So the title is used as a search: the public iTunes catalogue first, Deezer second, both of
 * which answer an unauthenticated GET and hand back an album image. That is the whole trick, and it is
 * why the island can show real artwork without anybody logging into anything.
 *
 * <p>Every lookup happens once. The answer, hit or miss, is remembered for the session, and the image is
 * written to {@code <gameDir>/GlobalDLC/misc/covers} so the next launch draws it without asking anybody.
 * A track nothing knows about is remembered as a miss, so a local recording is not searched for on every
 * single poll for as long as it plays.
 *
 * <p>Off the render thread, silent on every failure. No network, no match, an unreadable image: each one
 * simply means the island keeps the note glyph it had before.
 */
public final class CoverArt {
    private CoverArt() {
    }

    /** Track key to texture. A key present with a null value is a search that came back empty. */
    private static final Map<String, Identifier> CACHE = new HashMap<>();
    /** Keys currently being looked up, so a poll every second does not start the same search again. */
    private static final Map<String, Boolean> PENDING = new HashMap<>();

    /**
     * The cover for this track, or null when there is not one yet.
     *
     * <p>Null is the answer for "still looking" and for "nothing found" alike, because the caller draws
     * the same placeholder either way.
     */
    public static synchronized Identifier of(String artist, String title) {
        if (title == null || title.isBlank()) return null;

        String key = key(artist, title);
        if (CACHE.containsKey(key)) return CACHE.get(key);
        if (PENDING.containsKey(key)) return null;

        PENDING.put(key, true);
        Thread thread = new Thread(() -> find(key, artist, title), "Cloudan-CoverArt");
        thread.setDaemon(true);
        thread.start();
        return null;
    }

    private static String key(String artist, String title) {
        return ((artist == null ? "" : artist) + " " + title).trim().toLowerCase();
    }

    /* ------------------------------------------------------------------ */
    /* Lookup                                                              */
    /* ------------------------------------------------------------------ */

    private static void find(String key, String artist, String title) {
        try {
            byte[] png = fromDisk(key);
            if (png == null) {
                String url = search(artist, title);
                if (url != null) png = toPng(download(url));
                if (png != null) toDisk(key, png);
            }

            if (png == null) {
                remember(key, null);
                return;
            }
            register(key, png);
        } catch (Throwable ignored) {
            remember(key, null);
        }
    }

    /**
     * The artwork URL from whichever catalogue knows the track, or null.
     *
     * <p><b>Why this is fussier than it looks.</b> A music-video title is not a track name: it arrives as
     * {@code "KIZO - PARIS [Official Video] prod. XYZ 4K"} and a catalogue search for that whole string
     * matches almost anything. The old version asked iTunes for one result and used it unconditionally, which
     * is precisely why a cover for a completely different song would appear. So now the query is cleaned of
     * video furniture first, several candidates are asked for, and each one has to actually RESEMBLE what is
     * playing before it is accepted.
     *
     * <p>Returning null - no cover, just the note placeholder - is a better answer than a confident wrong
     * cover. That is the whole change in behaviour.
     */
    private static String search(String artist, String title) {
        String cleanTitle = clean(title);
        String cleanArtist = clean(artist);
        String query = (cleanArtist.isEmpty() ? "" : cleanArtist + " ") + cleanTitle;

        String found = fromItunes(query.trim(), cleanArtist, cleanTitle);
        if (found != null) return found;

        // Worth a second try with the title alone: a browser tab often carries a channel name rather than an
        // artist, and that extra word is enough to miss an otherwise obvious track.
        if (!cleanArtist.isEmpty()) {
            found = fromItunes(cleanTitle, "", cleanTitle);
            if (found != null) return found;
        }

        return fromDeezer(query.trim(), cleanArtist, cleanTitle);
    }

    /**
     * Strips the things a video title carries and a track name does not.
     *
     * <p>Brackets first - that is where "[Official Music Video]", "(Lyrics)" and "(prod. Somebody)" live - then
     * the loose tags that are not bracketed. What is left is usually the actual name of the song, which is the
     * only part worth searching for.
     */
    static String clean(String raw) {
        if (raw == null) return "";

        String text = raw;

        // Bracketed asides. Applied repeatedly because titles routinely carry two or three of them.
        text = text.replaceAll("(?i)[\\[(][^\\[\\]()]*\\b(official|video|audio|lyrics?|visualizer|prod|remaster(ed)?|hd|4k|8k|mv|teledysk|clip|m/?v)\\b[^\\[\\]()]*[\\])]", " ");

        // The same words when nobody bothered with brackets.
        text = text.replaceAll("(?i)\\b(official\\s+(music\\s+)?video|official\\s+audio|music\\s+video|lyric\\s+video|lyrics?|visualizer|audio\\s+only|teledysk)\\b", " ");
        text = text.replaceAll("(?i)\\bprod\\.?\\s*(by)?\\s*[^-\\[(]*$", " ");
        text = text.replaceAll("(?i)\\b(hd|full\\s*hd|4k|8k|60fps|remaster(ed)?)\\b", " ");

        // Leftover punctuation from whatever was removed, and the separators that are now dangling.
        text = text.replaceAll("[\\[\\]()|]", " ");
        text = text.replaceAll("\\s{2,}", " ").trim();
        text = text.replaceAll("^[-–—•\\s]+", "").replaceAll("[-–—•\\s]+$", "");

        // If the cleaning ate everything - a title that was nothing but tags - fall back to the original
        // rather than searching for an empty string.
        return text.isBlank() ? raw.trim() : text;
    }

    /* ------------------------------------------------------------------ */
    /* Matching                                                            */
    /* ------------------------------------------------------------------ */

    /** Letters and digits only, lowercased - so punctuation and spacing cannot decide a match. */
    private static String normalise(String text) {
        return text == null ? "" : text.toLowerCase().replaceAll("[^a-z0-9]+", "");
    }

    /**
     * Whether a catalogue hit is plausibly the thing that is playing.
     *
     * <p>Two ways to pass, because the two sources disagree about what a "title" is. Either the hit's track
     * name appears inside what we searched for (or the other way round) once both are reduced to letters and
     * digits - which catches "PARIS" against "KIZO PARIS" - or enough of the words overlap. A hit that does
     * neither is a different song, and a different song's cover is worse than no cover.
     */
    static boolean matches(String hitTitle, String hitArtist, String wantArtist, String wantTitle) {
        String hitT = normalise(hitTitle);
        String wantT = normalise(wantTitle);
        if (hitT.isEmpty() || wantT.isEmpty()) return false;

        // THE TITLE has to match. The artist can only corroborate, never substitute - that was the hole:
        // merging artist and title into one token set meant a hit by the same artist passed on the artist
        // alone, so "KIZO - Bueno" was accepted as the cover for "KIZO - PARIS". Same artist, wrong song, which
        // is the complaint this whole gate exists to answer.
        boolean artistOk = artistMatches(hitArtist, wantArtist);

        // Containment handles the normal cases: a catalogue suffix ("Jungle (Radio Edit)" for "Jungle"), or a
        // searched string that still carries the artist ("KIZO PARIS" for "PARIS"). It needs a minimum length,
        // because a three-letter title is a substring of half the catalogue - "Hot" inside "Hot Girl Summer"
        // is a coincidence, not a match.
        int shorter = Math.min(hitT.length(), wantT.length());
        if (shorter >= 5 && (hitT.contains(wantT) || wantT.contains(hitT))) return true;

        // Otherwise, overlapping words of the TITLES only.
        Set<String> hitWords = words(hitTitle);
        Set<String> wantWords = words(wantTitle);
        if (hitWords.isEmpty() || wantWords.isEmpty()) return false;

        int shared = 0;
        String only = null;
        for (String word : wantWords)
            if (hitWords.contains(word)) {
                shared++;
                only = word;
            }

        // Two words in common is evidence on its own. One word is only evidence when it is a substantial word
        // AND the artist agrees - which is what stops a single common word from carrying a wrong cover.
        if (shared >= 2) return true;
        return shared == 1 && only != null && only.length() >= 5 && artistOk;
    }

    /** Whether two artist strings plausibly name the same act. Empty on either side means "unknown". */
    private static boolean artistMatches(String hitArtist, String wantArtist) {
        String a = normalise(hitArtist);
        String b = normalise(wantArtist);
        if (a.isEmpty() || b.isEmpty()) return false;

        if (a.contains(b) || b.contains(a)) return true;

        Set<String> left = words(hitArtist);
        for (String word : words(wantArtist)) if (left.contains(word)) return true;
        return false;
    }

    private static Set<String> words(String text) {
        Set<String> out = new java.util.HashSet<>();
        for (String word : text.toLowerCase().split("[^a-z0-9]+"))
            // One and two letter fragments match everything, so they are not evidence of anything.
            if (word.length() > 2) out.add(word);
        return out;
    }

    /**
     * Asks iTunes for several candidates and returns the first that actually resembles the track.
     *
     * <p>Six, not one. With {@code limit=1} the catalogue's single best guess was accepted no matter how
     * unrelated it was, which is the bug: a video title it did not recognise still produced a confident cover
     * for some other song. Asking for a handful and checking each one means a near-miss further down the list
     * can win, and a page of nothing relevant correctly produces no cover at all.
     */
    private static String fromItunes(String query, String wantArtist, String wantTitle) {
        if (query.isBlank()) return null;

        String body = get("https://itunes.apple.com/search?media=music&limit=6&term="
                + URLEncoder.encode(query, StandardCharsets.UTF_8));
        if (body == null) return null;

        try {
            JsonObject json = JsonParser.parseString(body).getAsJsonObject();
            JsonArray results = json.getAsJsonArray("results");
            if (results == null || results.isEmpty()) return null;

            for (var element : results) {
                if (!element.isJsonObject()) continue;
                JsonObject hit = element.getAsJsonObject();
                if (!hit.has("artworkUrl100")) continue;

                String hitTitle = hit.has("trackName") ? hit.get("trackName").getAsString() : "";
                String hitArtist = hit.has("artistName") ? hit.get("artistName").getAsString() : "";
                if (!matches(hitTitle, hitArtist, wantArtist, wantTitle)) continue;

                // The catalogue hands out a 100px thumbnail; the same path serves any size, and the island
                // draws it at 30 logical pixels on a display that may be scaled several times over.
                return hit.get("artworkUrl100").getAsString().replace("100x100", "512x512");
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    /** The same candidate-and-check approach against Deezer, which indexes a lot that iTunes does not. */
    private static String fromDeezer(String query, String wantArtist, String wantTitle) {
        if (query.isBlank()) return null;

        String body = get("https://api.deezer.com/search?limit=6&q="
                + URLEncoder.encode(query, StandardCharsets.UTF_8));
        if (body == null) return null;

        try {
            JsonObject json = JsonParser.parseString(body).getAsJsonObject();
            JsonArray data = json.getAsJsonArray("data");
            if (data == null || data.isEmpty()) return null;

            for (var element : data) {
                if (!element.isJsonObject()) continue;
                JsonObject hit = element.getAsJsonObject();

                String hitTitle = hit.has("title") ? hit.get("title").getAsString() : "";
                String hitArtist = hit.has("artist") && hit.get("artist").isJsonObject()
                        && hit.getAsJsonObject("artist").has("name")
                        ? hit.getAsJsonObject("artist").get("name").getAsString() : "";
                if (!matches(hitTitle, hitArtist, wantArtist, wantTitle)) continue;

                JsonObject album = hit.has("album") && hit.get("album").isJsonObject()
                        ? hit.getAsJsonObject("album") : null;
                if (album == null) continue;

                if (album.has("cover_big")) return album.get("cover_big").getAsString();
                if (album.has("cover_medium")) return album.get("cover_medium").getAsString();
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    private static String get(String url) {
        try (HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
            HttpResponse<String> response = http.send(
                    HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(8)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200 ? response.body() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static byte[] download(String url) {
        try (HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
            HttpResponse<byte[]> response = http.send(
                    HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10)).GET().build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            return response.statusCode() == 200 ? response.body() : null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Re-encodes the artwork as PNG.
     *
     * <p>Both catalogues serve JPEG and the game's image reader only speaks PNG, so this is not optional
     * - without it every cover fails to decode and the island silently keeps its placeholder.
     */
    private static byte[] toPng(byte[] bytes) {
        if (bytes == null) return null;

        try {
            var image = javax.imageio.ImageIO.read(new ByteArrayInputStream(bytes));
            if (image == null) return null;

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            javax.imageio.ImageIO.write(image, "png", out);
            return out.toByteArray();
        } catch (Exception e) {
            return null;
        }
    }

    /* ------------------------------------------------------------------ */
    /* Cache                                                               */
    /* ------------------------------------------------------------------ */

    private static Path fileFor(String key) {
        return ConfigManager.MISC_FOLDER.toPath().resolve("covers")
                .resolve(Integer.toHexString(key.hashCode()) + ".png");
    }

    private static byte[] fromDisk(String key) {
        try {
            Path file = fileFor(key);
            return Files.exists(file) ? Files.readAllBytes(file) : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static void toDisk(String key, byte[] png) {
        try {
            Path file = fileFor(key);
            Files.createDirectories(file.getParent());
            Files.write(file, png);
        } catch (Exception ignored) {
            // Not being able to cache only costs one lookup on the next launch.
        }
    }

    private static void register(String key, byte[] png) {
        mc.execute(() -> {
            try (var in = new ByteArrayInputStream(png)) {
                var image = net.minecraft.client.texture.NativeImage.read(in);
                var id = Identifier.of("gombal", "cover_" + Integer.toHexString(key.hashCode()));
                mc.getTextureManager().registerTexture(id,
                        new net.minecraft.client.texture.NativeImageBackedTexture(id::toString, image));
                remember(key, id);
            } catch (Throwable ignored) {
                remember(key, null);
            }
        });
    }

    private static synchronized void remember(String key, Identifier id) {
        CACHE.put(key, id);
        PENDING.remove(key);
    }

    /** Drops everything, so a cover replaced on disk is picked up again. */
    public static synchronized void forget() {
        CACHE.clear();
        PENDING.clear();
    }
}
