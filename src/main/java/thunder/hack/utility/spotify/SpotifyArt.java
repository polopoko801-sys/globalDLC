package thunder.hack.utility.spotify;

import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.util.Identifier;
import thunder.hack.core.manager.client.ConfigManager;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static thunder.hack.features.modules.Module.mc;

/**
 * Album art straight from Spotify, cached on disk and on the GPU.
 *
 * <p>Unlike {@link thunder.hack.utility.CoverArt}, which guesses an album from a window title, there is
 * nothing to guess here: the image URL arrives inside the same object as the track, so the cover always
 * belongs to the track being shown. That is the whole reason the Spotify path exists.
 *
 * <p><b>Attribution.</b> The image is drawn exactly as Spotify serves it - no crop, no tint, no overlay,
 * no rounding of the artwork itself, nothing composited on top. The only transformation is a JPEG to PNG
 * re-encode, which is a container change forced by the game's image reader and not a change to the
 * picture. Everywhere a cover is drawn, the thing it depicts can be opened in Spotify.
 *
 * <p><b>Texture budget.</b> A long playlist would otherwise register a texture per row and never let one
 * go. Registrations are capped and evicted least-recently-used, so scrolling a thousand tracks costs a
 * bounded amount of VRAM; an evicted cover is still on disk and comes back without the network.
 */
public final class SpotifyArt {
    private SpotifyArt() {
    }

    /** How many covers may be resident on the GPU at once. */
    private static final int BUDGET = 128;

    /** URL to texture. A present-but-null value is a URL that could not be turned into an image. */
    private static final LinkedHashMap<String, Identifier> LIVE = new LinkedHashMap<>(32, 0.75f, true);
    private static final Set<String> PENDING = ConcurrentHashMap.newKeySet();

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(6)).build();

    /**
     * The texture for this image URL, or null while there is not one.
     *
     * <p>Safe to call every frame: the first call starts one download and every call after it is a map
     * lookup. Null means "draw the placeholder", for both "still fetching" and "no image", because the
     * caller does the same thing either way.
     */
    public static synchronized Identifier of(String url) {
        if (url == null || url.isBlank()) return null;

        if (LIVE.containsKey(url)) return LIVE.get(url);
        if (!PENDING.add(url)) return null;

        SpotifyApi.async(() -> fetch(url));
        return null;
    }

    private static void fetch(String url) {
        try {
            byte[] png = fromDisk(url);

            if (png == null) {
                byte[] raw = download(url);
                png = toPng(raw);
                if (png != null) toDisk(url, png);
            }

            if (png == null) {
                remember(url, null);
                return;
            }

            register(url, png);
        } catch (Throwable t) {
            remember(url, null);
        }
    }

    /* ------------------------------------------------------------------ */
    /* Network                                                             */
    /* ------------------------------------------------------------------ */

    private static byte[] download(String url) {
        try {
            // No Authorization header: the image CDN is public and sending a bearer token to it would
            // leak the token to a host that has no business holding one.
            HttpResponse<byte[]> response = HTTP.send(
                    HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(12)).GET().build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            return response.statusCode() == 200 ? response.body() : null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Re-encodes as PNG when it is not one already.
     *
     * <p>{@link NativeImage} reads PNG and nothing else, and Spotify serves JPEG. A PNG that arrives as
     * a PNG is passed through byte for byte rather than being decoded and written again - there is no
     * reason to re-compress an image that is already in the right container.
     */
    private static byte[] toPng(byte[] bytes) {
        if (bytes == null || bytes.length < 8) return null;

        boolean alreadyPng = (bytes[0] & 0xFF) == 0x89 && bytes[1] == 'P' && bytes[2] == 'N' && bytes[3] == 'G';
        if (alreadyPng) return bytes;

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
    /* Disk cache                                                          */
    /* ------------------------------------------------------------------ */

    public static Path folder() {
        return ConfigManager.MISC_FOLDER.toPath().resolve("spotify-art");
    }

    /** A stable file name for a URL. SHA-1 because a hashCode collides often enough to matter here. */
    private static String hash(String url) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-1").digest(url.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(40);
            for (byte b : digest) sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(url.hashCode());
        }
    }

    private static byte[] fromDisk(String url) {
        try {
            Path file = folder().resolve(hash(url) + ".png");
            return Files.exists(file) ? Files.readAllBytes(file) : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static void toDisk(String url, byte[] png) {
        try {
            Path file = folder().resolve(hash(url) + ".png");
            Files.createDirectories(file.getParent());
            Files.write(file, png);
        } catch (Exception ignored) {
            // Failing to cache costs one download next launch, nothing more.
        }
    }

    /* ------------------------------------------------------------------ */
    /* GPU                                                                 */
    /* ------------------------------------------------------------------ */

    private static void register(String url, byte[] png) {
        // Texture registration is render-thread work; doing it here would be a race against the frame
        // being drawn, which shows up as a texture that is bound but empty.
        mc.execute(() -> {
            try (var in = new ByteArrayInputStream(png)) {
                NativeImage image = NativeImage.read(in);
                Identifier id = Identifier.of("gombal", "spotify_art_" + hash(url));

                mc.getTextureManager().registerTexture(id, new NativeImageBackedTexture(id::toString, image));
                remember(url, id);
                evict();
            } catch (Throwable t) {
                remember(url, null);
            }
        });
    }

    /** Frees the oldest covers once the budget is exceeded. Called on the render thread only. */
    private static synchronized void evict() {
        if (LIVE.size() <= BUDGET) return;

        var iterator = LIVE.entrySet().iterator();
        while (LIVE.size() > BUDGET && iterator.hasNext()) {
            Map.Entry<String, Identifier> oldest = iterator.next();
            // Access order puts the least recently drawn first, but a miss (null) is worth keeping: it
            // costs no VRAM and dropping it would restart a download that is known to fail.
            if (oldest.getValue() == null) continue;

            try {
                mc.getTextureManager().destroyTexture(oldest.getValue());
            } catch (Throwable ignored) {
                // A texture the manager has already dropped is not a problem.
            }
            iterator.remove();
        }
    }

    private static synchronized void remember(String url, Identifier id) {
        LIVE.put(url, id);
        PENDING.remove(url);
    }

    /** Drops every registration, so a cleared disk cache or a new login starts fresh. */
    public static synchronized void forget() {
        mc.execute(() -> {
            synchronized (SpotifyArt.class) {
                for (Identifier id : LIVE.values()) {
                    if (id == null) continue;
                    try {
                        mc.getTextureManager().destroyTexture(id);
                    } catch (Throwable ignored) {
                    }
                }
                LIVE.clear();
                PENDING.clear();
            }
        });
    }
}
