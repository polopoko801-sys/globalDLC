package thunder.hack.utility.render;

import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.util.Identifier;
import org.jetbrains.annotations.NotNull;

import java.awt.Color;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;

import static thunder.hack.features.modules.Module.mc;

/**
 * Coloured copies of white sprites, made on the CPU.
 *
 * <p><b>Why this exists.</b> Every world sprite in the client is painted white so it can be tinted at
 * draw time, and the tint is carried on the vertex colour. On this version that colour does not reach
 * the shader for the textured world layers - the pipeline declares POSITION_TEXTURE_COLOR, the shader
 * multiplies by {@code vertexColor}, the buffer writes the element, and the sprite still comes out
 * white. Wings, lanterns, the jump ring and the target marker all ignored their colour for the same
 * reason.
 *
 * <p>So the colour goes into the PICTURE instead. The source PNG is read once, multiplied by the tint,
 * and uploaded as its own texture; the sprite then draws with a white vertex colour that carries only
 * the alpha. Nothing depends on the vertex colour channel any more.
 *
 * <p><b>Why it does not thrash.</b> Tints are quantised to 32 levels per channel before they become a
 * key, so a rainbow accent sweeping through the spectrum reuses a few dozen copies rather than making a
 * new texture every frame, and the cache is capped and evicts the least recently used.
 */
public final class TintedTextures {
    private TintedTextures() {
    }

    /** How many distinct tinted copies may exist at once, across every sprite. */
    private static final int MAX_CACHED = 96;

    /** Cache in access order, so removing the eldest entry removes the least recently used. */
    private static final Map<Long, Identifier> CACHE = new LinkedHashMap<>(32, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, Identifier> eldest) {
            if (size() <= MAX_CACHED) return false;
            // The texture manager holds the GPU handle, so dropping the map entry is not enough.
            mc.getTextureManager().destroyTexture(eldest.getValue());
            return true;
        }
    };

    /** Sources that could not be read. Retrying a missing file every frame is not worth it. */
    private static final Map<Identifier, Boolean> FAILED = new LinkedHashMap<>();

    private static int nextId;

    /**
     * A copy of {@code source} multiplied by {@code tint}, or {@code source} itself if it cannot be
     * made. The tint's alpha is ignored - alpha stays the sprite's own and is scaled at draw time.
     */
    public static @NotNull Identifier tinted(@NotNull Identifier source, @NotNull Color tint) {
        if (FAILED.containsKey(source)) return source;

        // White changes nothing, so it is not worth a second copy of the texture.
        int rgb = quantise(tint);
        if (rgb == 0xFFFFFF) return source;

        long key = ((long) source.hashCode() << 24) | rgb;
        Identifier cached = CACHE.get(key);
        if (cached != null) return cached;

        Identifier made = build(source, rgb);
        if (made == null) {
            FAILED.put(source, true);
            return source;
        }

        CACHE.put(key, made);
        return made;
    }

    /** Drops to 5 bits per channel: close enough to look identical, coarse enough to reuse. */
    private static int quantise(Color tint) {
        int r = tint.getRed() & 0xF8;
        int g = tint.getGreen() & 0xF8;
        int b = tint.getBlue() & 0xF8;
        // Bottom bits filled back in so a full-brightness channel stays full brightness.
        return ((r | r >> 5) << 16) | ((g | g >> 5) << 8) | (b | b >> 5);
    }

    /** Decoded source pixels, so a new tint is a recolour of memory, not a fresh PNG read and decode. */
    private record Source(int width, int height, int[] argb) {
    }

    private static final Map<Identifier, Source> SOURCES = new java.util.HashMap<>();

    private static Source source(Identifier id) throws java.io.IOException {
        Source cached = SOURCES.get(id);
        if (cached != null) return cached;
        try (InputStream in = mc.getResourceManager().getResourceOrThrow(id).getInputStream();
             NativeImage image = NativeImage.read(in)) {
            int w = image.getWidth(), h = image.getHeight();
            int[] px = new int[w * h];
            for (int y = 0; y < h; y++)
                for (int x = 0; x < w; x++) px[y * w + x] = image.getColorArgb(x, y);
            Source made = new Source(w, h, px);
            SOURCES.put(id, made);
            return made;
        }
    }

    private static Identifier build(Identifier source, int rgb) {
        // Animated colours (Sync, rainbow trails) ask for a new tint almost every frame. Re-reading and
        // decoding the PNG from the jar each time was the expensive part, and on a weaker machine it was a
        // visible stutter; the decoded pixels are now kept and only recoloured.
        try {
            Source src = source(source);
            NativeImage image = new NativeImage(src.width(), src.height(), false);
            for (int y = 0; y < src.height(); y++)
                for (int x = 0; x < src.width(); x++) image.setColorArgb(x, y, src.argb()[y * src.width() + x]);

            float fr = ((rgb >> 16) & 0xFF) / 255f;
            float fg = ((rgb >> 8) & 0xFF) / 255f;
            float fb = (rgb & 0xFF) / 255f;

            for (int y = 0; y < image.getHeight(); y++)
                for (int x = 0; x < image.getWidth(); x++) {
                    int argb = image.getColorArgb(x, y);
                    int a = argb >>> 24;
                    if (a == 0) continue;

                    int r = Math.round(((argb >> 16) & 0xFF) * fr);
                    int g = Math.round(((argb >> 8) & 0xFF) * fg);
                    int b = Math.round((argb & 0xFF) * fb);

                    image.setColorArgb(x, y, (a << 24) | (r << 16) | (g << 8) | b);
                }

            Identifier id = Identifier.of("gombal", "tinted/" + (nextId++));
            mc.getTextureManager().registerTexture(id, new NativeImageBackedTexture(id::toString, image));
            return id;
        } catch (Throwable t) {
            return null;
        }
    }
}
