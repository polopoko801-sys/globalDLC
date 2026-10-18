package thunder.hack.gui.font;

import it.unimi.dsi.fastutil.chars.Char2IntArrayMap;
import it.unimi.dsi.fastutil.chars.Char2ObjectArrayMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectList;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.util.Identifier;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix3x2f;
import thunder.hack.features.modules.client.HudEditor;
import thunder.hack.utility.render.GuiRender;

import java.awt.*;
import java.io.Closeable;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static thunder.hack.core.manager.IManager.mc;

public class FontRenderer implements Closeable {
    private static final Char2IntArrayMap colorCodes = new Char2IntArrayMap() {{
        put('0', 0x000000);
        put('1', 0x0000AA);
        put('2', 0x00AA00);
        put('3', 0x00AAAA);
        put('4', 0xAA0000);
        put('5', 0xAA00AA);
        put('6', 0xFFAA00);
        put('7', 0xAAAAAA);
        put('8', 0x555555);
        put('9', 0x5555FF);
        put('A', 0x55FF55);
        put('B', 0x55FFFF);
        put('C', 0xFF5555);
        put('D', 0xFF55FF);
        put('E', 0xFFFF55);
        put('F', 0xFFFFFF);
    }};

    private static final ExecutorService ASYNC_WORKER = Executors.newCachedThreadPool();
    private final Object2ObjectMap<Identifier, ObjectList<DrawEntry>> GLYPH_PAGE_CACHE = new Object2ObjectOpenHashMap<>();
    private final float originalSize;
    private final int charsPerPage;
    private final int padding;
    private final String prebakeGlyphs;
    /**
     * The baked faces, keyed by supersample factor.
     *
     * <p>A face laid out at size X but stretched on the matrix is X times blurrier than one baked at
     * the size it actually appears at: the glyphs are bitmaps, so the matrix only magnifies texels.
     * Drawing with a supersample factor bakes the same layout at b times the pixel density and shrinks
     * it back down, which is sharp at any scale. Quantised (see {@link #quantize}) so dragging a size
     * slider does not create a new atlas for every frame.
     */
    private final java.util.HashMap<Float, Face> faces = new java.util.HashMap<>();
    private int scaleMul = 0;
    /** The TTF at {@link #originalSize}. Every face derives from this, so re-baking never compounds sizes. */
    private Font baseFont;
    private int previousGameScale = -1;
    private Future<Void> prebakeGlyphsFuture;
    private boolean initialized;

    public FontRenderer(Font font, float sizePx, int charactersPerPage, int paddingBetweenCharacters, @Nullable String prebakeCharacters) {
        this.originalSize = sizePx;
        this.charsPerPage = charactersPerPage;
        this.padding = paddingBetweenCharacters;
        this.prebakeGlyphs = prebakeCharacters;
        this.baseFont = font;
        init();
    }

    public FontRenderer(Font font, float sizePx) {
        this(font, sizePx, 256, 5, null);
    }

    private static int floorNearestMulN(int x, int n) {
        return n * (int) Math.floor((double) x / (double) n);
    }

    public static String stripControlCodes(String text) {
        char[] chars = text.toCharArray();
        StringBuilder f = new StringBuilder();
        for (int i = 0; i < chars.length; i++) {
            char c = chars[i];
            if (c == '§') {
                i++;
                continue;
            }
            f.append(c);
        }
        return f.toString();
    }

    private void sizeCheck() {
        int gs = (int) mc.getWindow().getScaleFactor();
        if (gs != this.previousGameScale) {
            close();
            init();
        }
    }

    private void init() {
        if (initialized) throw new IllegalStateException("Double call to init()");
        initialized = true;
        this.previousGameScale = (int) mc.getWindow().getScaleFactor();
        this.scaleMul = this.previousGameScale;
        faces.clear();
        if (prebakeGlyphs != null && !prebakeGlyphs.isEmpty()) {
            prebakeGlyphsFuture = this.prebake();
        }
    }

    private Future<Void> prebake() {
        return ASYNC_WORKER.submit(() -> {
            Face face = face(1f);
            for (char c : prebakeGlyphs.toCharArray()) {
                if (Thread.interrupted()) break;
                face.glyph(c);
            }
            return null;
        });
    }

    /**
     * One point size of the TTF, rasterised at its own density.
     *
     * <p>Every glyph atlas belongs to a face: that is what lets the same renderer draw a HUD line at
     * 1x and the same line at 3x with each one rendered natively instead of one being a magnification
     * of the other.
     */
    private final class Face {
        /** Pixels baked per GUI unit: the window's GUI scale times the supersample factor. */
        final float units;
        final Font font;
        final ObjectList<GlyphMap> maps = new ObjectArrayList<>();
        final Char2ObjectArrayMap<Glyph> allGlyphs = new Char2ObjectArrayMap<>();

        Face(float boost) {
            this.units = scaleMul * boost;
            this.font = baseFont.deriveFont(originalSize * this.units);
        }

        GlyphMap generateMap(char from, char to) {
            GlyphMap gm = new GlyphMap(from, to, this.font, randomIdentifier(), padding);
            maps.add(gm);
            return gm;
        }

        private Glyph locate(char glyph) {
            for (GlyphMap map : maps) {
                if (map.contains(glyph)) {
                    return map.getGlyph(glyph);
                }
            }
            int base = floorNearestMulN(glyph, charsPerPage);
            GlyphMap glyphMap = generateMap((char) base, (char) (base + charsPerPage));
            return glyphMap.getGlyph(glyph);
        }

        @Nullable
        Glyph glyph(char glyph) {
            return allGlyphs.computeIfAbsent(glyph, this::locate);
        }

        /** Width in GUI units, so a supersampled draw measures exactly like the plain one. */
        float width(String text) {
            char[] c = stripControlCodes(text).toCharArray();
            float currentLine = 0;
            float maxPreviousLines = 0;
            for (char c1 : c) {
                if (c1 == '\n') {
                    maxPreviousLines = Math.max(currentLine, maxPreviousLines);
                    currentLine = 0;
                    continue;
                }
                Glyph glyph = glyph(c1);
                currentLine += glyph == null ? 0 : (glyph.width() / units);
            }
            return Math.max(currentLine, maxPreviousLines);
        }

        /** Line height in GUI units. */
        float height(String text) {
            char[] c = stripControlCodes(text).toCharArray();
            if (c.length == 0) c = new char[]{' '};
            float currentLine = 0;
            float previous = 0;
            for (char c1 : c) {
                if (c1 == '\n') {
                    if (currentLine == 0) {
                        Glyph space = glyph(' ');
                        currentLine = space == null ? 0 : (space.height() / units);
                    }
                    previous += currentLine;
                    currentLine = 0;
                    continue;
                }
                Glyph glyph = glyph(c1);
                currentLine = Math.max(glyph == null ? 0 : (glyph.height() / units), currentLine);
            }
            return currentLine + previous;
        }
    }

    /**
     * The ladder of supersample factors glyphs are baked at.
     *
     * <p>Each step is at most 1.25x away from the one below it, so a caller's own scale is always met
     * by baking a little denser and shrinking a little - never by magnifying. Above 4x the steps become
     * whole numbers; past that the atlas cost outweighs anything the eye can see.
     */
    private static final float[] SUPERSAMPLE_STEPS = {1f, 1.25f, 1.5f, 1.75f, 2f, 2.5f, 3f, 4f};

    private static float quantize(float supersample) {
        if (!(supersample > 1.02f)) return 1f;
        for (float step : SUPERSAMPLE_STEPS) {
            if (supersample <= step + 0.001f) return step;
        }
        return Math.min(6f, (float) Math.ceil(supersample));
    }

    /**
     * Largest font size a face may be rasterised at, in pixels.
     *
     * <p>A glyph page is a texture the size of every character in its range, so its memory grows with
     * the square of the font size. Without this cap a huge face (the menu wordmark on a 4K window at a
     * high GUI scale) would try to allocate a texture no GPU would accept. Past 256 px the native
     * glyphs are already larger than any text the client draws, so nothing visible is lost.
     */
    private static final float MAX_BAKED_PX = 256f;

    private Face face(float supersample) {
        float boost = quantize(supersample);
        float cap = MAX_BAKED_PX / Math.max(1f, originalSize * scaleMul);
        if (boost > cap) boost = Math.max(1f, cap);
        return faces.computeIfAbsent(boost, Face::new);
    }

    public void drawString(DrawContext context, String s, double x, double y, int color) {
        float r = ((color >> 16) & 0xff) / 255f;
        float g = ((color >> 8) & 0xff) / 255f;
        float b = ((color) & 0xff) / 255f;
        float a = ((color >> 24) & 0xff) / 255f;
        drawString(context, s, (float) x, (float) y, r, g, b, a);
    }

    public void drawString(DrawContext context, String s, double x, double y, Color color) {
        drawString(context, s, (float) x, (float) y, color.getRed() / 255f, color.getGreen() / 255f, color.getBlue() / 255f, color.getAlpha() / 255f);
    }

    /**
     * Text drawn inside a matrix that has been scaled by {@code supersample}.
     *
     * <p>Pass the scale the caller already put on the matrix: the glyphs are then baked at that density
     * instead of being magnified by it. The layout does not move - widths stay in the same units - so
     * this is a drop-in replacement for the plain overload wherever a widget is scaled up.
     */
    public void drawString(DrawContext context, String s, float x, float y, int color, float supersample) {
        float r = ((color >> 16) & 0xff) / 255f;
        float g = ((color >> 8) & 0xff) / 255f;
        float b = ((color) & 0xff) / 255f;
        float a = ((color >> 24) & 0xff) / 255f;
        drawString(context, s, x, y, r, g, b, a, supersample);
    }

    // 1.21.6+ stub overloads. A handful of legacy call sites pass a raw transform (a 3D-world
    // MatrixStack for billboarded text, or a Matrix3x2fStack) instead of a DrawContext. Glyph emission
    // now requires a DrawContext (GuiRender), so these compat overloads are no-ops; the affected text
    // is not drawn until those sites are migrated to pass a DrawContext.
    public void drawString(net.minecraft.client.util.math.MatrixStack matrices, String s, double x, double y, int color) {
    }

    public void drawString(org.joml.Matrix3x2fStack matrices, String s, double x, double y, int color) {
    }

    public void drawCenteredString(net.minecraft.client.util.math.MatrixStack matrices, String s, double x, double y, int color) {
    }

    public void drawCenteredString(org.joml.Matrix3x2fStack matrices, String s, double x, double y, int color) {
    }

    public void drawString(DrawContext context, String s, float x, float y, float r, float g, float b, float a) {
        drawString(context, s, x, y, r, g, b, a, 1f);
    }

    /** See {@link #drawString(DrawContext, String, float, float, int, float)}. */
    public void drawString(DrawContext context, String s, float x, float y, float r, float g, float b, float a, float supersample) {
        drawString(context, s, x, y, r, g, b, a, false, 0, supersample);
    }

    private static int packColor(float r, float g, float b, float a) {
        int ri = Math.round(Math.min(Math.max(r, 0f), 1f) * 255f);
        int gi = Math.round(Math.min(Math.max(g, 0f), 1f) * 255f);
        int bi = Math.round(Math.min(Math.max(b, 0f), 1f) * 255f);
        int ai = Math.round(Math.min(Math.max(a, 0f), 1f) * 255f);
        return (ai << 24) | (ri << 16) | (gi << 8) | bi;
    }

    public void drawString(DrawContext context, String s, float x, float y, float r, float g, float b, float a, boolean gradient, int offset) {
        drawString(context, s, x, y, r, g, b, a, gradient, offset, 1f);
    }

    private void drawString(DrawContext context, String s, float x, float y, float r, float g, float b, float a,
                            boolean gradient, int offset, float supersample) {
        if (prebakeGlyphsFuture != null && !prebakeGlyphsFuture.isDone()) {
            try {
                prebakeGlyphsFuture.get();
            } catch (InterruptedException | ExecutionException ignored) {
            }
        }

        sizeCheck();
        // No explicit factor: read it off the matrix the caller is drawing in, so a widget scaled up with
        // getMatrices().scale(...) (MusicWidget, HUD cards' Scale) gets glyphs baked at the size they appear.
        if (!(supersample > 1f)) {
            Matrix3x2f m = context.getMatrices();
            supersample = (float) Math.sqrt(Math.abs(m.m00() * m.m11() - m.m01() * m.m10()));
        }
        Face face = face(supersample);
        float r2 = r, g2 = g, b2 = b;
        y -= 3f;
        // Local 2D pose: base context pose * translate(x,y) * scale(1/units). The glyph coordinates are
        // baked pixels, so dividing by the face's density brings them back to the caller's units.
        Matrix3x2f pose = new Matrix3x2f(context.getMatrices())
                .translate(x, y)
                .scale(1f / face.units, 1f / face.units);
        // Glyphs are baked at one texel per screen pixel (times the face density), so the text is only
        // sharp when its origin sits ON a screen pixel. Rounding x/y to 0.1 GUI units left it between
        // pixels - soft or jagged letters. Snap the final translation to the pixel grid instead (after
        // any HUD offset/scale in the pose).
        float px = 1f / (float) mc.getWindow().getScaleFactor();
        pose.set(pose.m00(), pose.m01(), pose.m10(), pose.m11(),
                Math.round(pose.m20() / px) * px, Math.round(pose.m21() / px) * px);

        char[] chars = s.toCharArray();
        float xOffset = 0;
        float yOffset = 0;
        boolean inSel = false;
        int lineStart = 0;
        synchronized (GLYPH_PAGE_CACHE) {
            for (int i = 0; i < chars.length; i++) {
                char c = chars[i];
                if (inSel) {
                    inSel = false;
                    char c1 = Character.toUpperCase(c);
                    if (colorCodes.containsKey(c1)) {
                        int ii = colorCodes.get(c1);
                        int[] col = RGBIntToRGB(ii);
                        r2 = col[0] / 255f;
                        g2 = col[1] / 255f;
                        b2 = col[2] / 255f;
                    } else if (c1 == 'R') {
                        r2 = r;
                        g2 = g;
                        b2 = b;
                    }
                    continue;
                }

                if (gradient) {
                    Color color = HudEditor.getColor(i * offset);
                    r2 = color.getRed() / 255f;
                    g2 = color.getGreen() / 255f;
                    b2 = color.getBlue() / 255f;
                    a = color.getAlpha() / 255f;
                }

                if (c == '§') {
                    inSel = true;
                    continue;
                } else if (c == '\n') {
                    yOffset += face.height(s.substring(lineStart, i)) * face.units;
                    xOffset = 0;
                    lineStart = i + 1;
                    continue;
                }
                Glyph glyph = face.glyph(c);
                if (glyph != null) {
                    if (glyph.value() != ' ') {
                        Identifier i1 = glyph.owner().bindToTexture;
                        DrawEntry entry = new DrawEntry(xOffset, yOffset, r2, g2, b2, a, glyph);
                        GLYPH_PAGE_CACHE.computeIfAbsent(i1, integer -> new ObjectArrayList<>()).add(entry);
                    }
                    xOffset += glyph.width();
                }
            }
            for (Identifier identifier : GLYPH_PAGE_CACHE.keySet()) {
                List<DrawEntry> objects = GLYPH_PAGE_CACHE.get(identifier);
                GuiRender.textured(context, identifier, thunder.hack.utility.render.Render2DEngine.currentClip(), vc -> {
                    for (DrawEntry object : objects) {
                        float xo = object.atX;
                        float yo = object.atY;
                        int argb = packColor(object.r, object.g, object.b, object.a);
                        Glyph glyph = object.toDraw;
                        GlyphMap owner = glyph.owner();
                        float w = glyph.width();
                        float h = glyph.height();
                        float u1 = (float) glyph.u() / owner.width;
                        float v1 = (float) glyph.v() / owner.height;
                        float u2 = (float) (glyph.u() + glyph.width()) / owner.width;
                        float v2 = (float) (glyph.v() + glyph.height()) / owner.height;

                        GuiRender.vertexTex(vc, pose, xo + 0, yo + h, u1, v2, argb);
                        GuiRender.vertexTex(vc, pose, xo + w, yo + h, u2, v2, argb);
                        GuiRender.vertexTex(vc, pose, xo + w, yo + 0, u2, v1, argb);
                        GuiRender.vertexTex(vc, pose, xo + 0, yo + 0, u1, v1, argb);
                    }
                });
            }

            GLYPH_PAGE_CACHE.clear();
        }
    }

    public void drawCenteredString(DrawContext context, String s, double x, double y, int color) {
        float r = ((color >> 16) & 0xff) / 255f;
        float g = ((color >> 8) & 0xff) / 255f;
        float b = ((color) & 0xff) / 255f;
        float a = ((color >> 24) & 0xff) / 255f;
        drawString(context, s, (float) (x - getStringWidth(s) / 2f), (float) y, r, g, b, a);
    }

    public void drawCenteredString(DrawContext context, String s, double x, double y, Color color) {
        drawString(context, s, (float) (x - getStringWidth(s) / 2f), (float) y, color.getRed() / 255f, color.getGreen() / 255f, color.getBlue() / 255f, color.getAlpha() / 255f);
    }

    public void drawCenteredString(DrawContext context, String s, float x, float y, float r, float g, float b, float a) {
        drawString(context, s, x - getStringWidth(s) / 2f, y, r, g, b, a);
    }

    public float getStringWidth(String text) {
        return face(1f).width(text);
    }

    /** Width in the same units as {@link #getStringWidth(String)}, measured off the supersampled face. */
    public float getStringWidth(String text, float supersample) {
        return face(supersample).width(text);
    }

    public float getStringHeight(String text) {
        return face(1f).height(text);
    }

    public float getStringHeight(String text, float supersample) {
        return face(supersample).height(text);
    }


    @Override
    public void close() {
        try {
            if (prebakeGlyphsFuture != null && !prebakeGlyphsFuture.isDone() && !prebakeGlyphsFuture.isCancelled()) {
                prebakeGlyphsFuture.cancel(true);
                prebakeGlyphsFuture.get();
                prebakeGlyphsFuture = null;
            }
            for (Face face : faces.values()) {
                for (GlyphMap map : face.maps) map.destroy();
                face.maps.clear();
                face.allGlyphs.clear();
            }
            faces.clear();
            initialized = false;
        } catch (Exception ignored) {
        }
    }

    @Contract(value = "-> new", pure = true)
    public static @NotNull Identifier randomIdentifier() {
        return Identifier.of("gombal", "temp/" + randomString());
    }

    private static String randomString() {
        return IntStream.range(0, 32).mapToObj(operand -> String.valueOf((char) new Random().nextInt('a', 'z' + 1))).collect(Collectors.joining());
    }

    @Contract(value = "_ -> new", pure = true)
    public static int @NotNull [] RGBIntToRGB(int in) {
        int red = in >> 8 * 2 & 0xFF;
        int green = in >> 8 & 0xFF;
        int blue = in & 0xFF;
        return new int[]{red, green, blue};
    }

    public float getFontHeight(String str) {
        return getStringHeight(str);
    }

    public void drawGradientString(DrawContext context, String s, float x, float y, int offset) {
        drawString(context, s, x, y, 1f, 1f, 1f, 1f, true, offset);
    }

    public void drawGradientCenteredString(DrawContext context, String s, float x, float y, int i) {
        drawGradientString(context, s, x - getStringWidth(s) / 2f, y, i);
    }

    record DrawEntry(float atX, float atY, float r, float g, float b, float a, Glyph toDraw) {
    }
}
