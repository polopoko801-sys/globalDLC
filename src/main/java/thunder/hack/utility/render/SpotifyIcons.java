package thunder.hack.utility.render;

import net.minecraft.client.gui.DrawContext;

import java.awt.Color;

/**
 * The transport glyphs, drawn as geometry rather than typed as characters.
 *
 * <p>The client's fonts are built from TTF faces that carry Latin text and little else - there is no
 * guarantee of a play triangle, a shuffle arrow or a repeat loop in any of them, and a missing glyph is
 * drawn as nothing at all, which would leave the panel with invisible buttons on some installs. Shapes
 * built from triangles and rounded quads always exist, scale to any button size without a second font,
 * and stay sharp because the radius is computed from the size rather than baked into a bitmap.
 *
 * <p>Every method takes the button's top-left corner and its size, and draws inside that square. Nothing
 * here reads global state, so the same call works in the HUD island and in the full screen.
 */
public final class SpotifyIcons {
    private SpotifyIcons() {
    }

    /* ------------------------------------------------------------------ */
    /* Transport                                                           */
    /* ------------------------------------------------------------------ */

    /** A right-pointing triangle, inset so it sits optically centred rather than measured centred. */
    public static void play(DrawContext context, float x, float y, float size, Color color) {
        triangle(context, x + size * 0.22f, y + size * 0.12f, size * 0.62f, size * 0.76f, color);
    }

    public static void pause(DrawContext context, float x, float y, float size, Color color) {
        float bar = Math.max(1.5f, size * 0.2f);
        float gap = size * 0.18f;
        float left = x + (size - bar * 2 - gap) / 2f;

        Render2DEngine.drawRound(context, left, y + size * 0.14f, bar, size * 0.72f, bar * 0.35f, color);
        Render2DEngine.drawRound(context, left + bar + gap, y + size * 0.14f, bar, size * 0.72f, bar * 0.35f, color);
    }

    public static void next(DrawContext context, float x, float y, float size, Color color) {
        skip(context, x, y, size, color, false);
    }

    public static void previous(DrawContext context, float x, float y, float size, Color color) {
        skip(context, x, y, size, color, true);
    }

    /** A triangle with the bar it runs into, which is what distinguishes a skip from a fast-forward. */
    private static void skip(DrawContext context, float x, float y, float size, Color color, boolean back) {
        float bar = Math.max(1.2f, size * 0.14f);
        float top = y + size * 0.16f;
        float height = size * 0.68f;
        float width = size * 0.56f;

        if (back) {
            Render2DEngine.drawRound(context, x + size * 0.1f, top, bar, height, bar * 0.4f, color);
            backTriangle(context, x + size * 0.1f + bar + size * 0.04f, top, width, height, color);
        } else {
            triangle(context, x + size * 0.14f, top, width, height, color);
            Render2DEngine.drawRound(context, x + size * 0.9f - bar, top, bar, height, bar * 0.4f, color);
        }
    }

    /** A filled triangle pointing right, inside the given box. */
    public static void triangle(DrawContext context, float x, float y, float width, float height, Color color) {
        // Drawn as a stack of one-pixel rows: the engine has no triangle primitive, and a stack of rows
        // antialiases no worse than a raw triangle would at these sizes while needing no new shader.
        int rows = Math.max(3, (int) Math.ceil(height));
        float step = height / rows;

        for (int i = 0; i < rows; i++) {
            float t = (i + 0.5f) / rows;
            // Widest at the vertical middle, tapering to a point at the tip.
            float span = width * (1f - Math.abs(t - 0.5f) * 2f);
            Render2DEngine.drawRect(context, x, y + i * step, Math.max(0.6f, span), step + 0.4f, color);
        }
    }

    private static void backTriangle(DrawContext context, float x, float y, float width, float height, Color color) {
        int rows = Math.max(3, (int) Math.ceil(height));
        float step = height / rows;

        for (int i = 0; i < rows; i++) {
            float t = (i + 0.5f) / rows;
            float span = width * (1f - Math.abs(t - 0.5f) * 2f);
            span = Math.max(0.6f, span);
            Render2DEngine.drawRect(context, x + width - span, y + i * step, span, step + 0.4f, color);
        }
    }

    /* ------------------------------------------------------------------ */
    /* Modes                                                               */
    /* ------------------------------------------------------------------ */

    /** Two crossing paths with arrowheads - shuffle. */
    public static void shuffle(DrawContext context, float x, float y, float size, Color color) {
        float t = Math.max(1f, size * 0.11f);
        float l = x + size * 0.14f, r = x + size * 0.86f;
        float top = y + size * 0.3f, bottom = y + size * 0.7f;

        Render2DEngine.thickLine(context, l, top, r - size * 0.14f, bottom, t, color);
        Render2DEngine.thickLine(context, l, bottom, r - size * 0.14f, top, t, color);

        arrowHead(context, r - size * 0.16f, top, size * 0.2f, color, true);
        arrowHead(context, r - size * 0.16f, bottom, size * 0.2f, color, true);
    }

    /**
     * A loop. With {@code one} it carries a dot, which is how "repeat this track" differs from
     * "repeat the album" at eight pixels across - a digit would be unreadable at that size.
     */
    public static void repeat(DrawContext context, float x, float y, float size, Color color, boolean one) {
        // Four bars, not a filled rounded rect with a smaller one punched out of it: there is no way to
        // punch a hole in this engine - a transparent fill draws nothing rather than cutting - so an
        // outline has to actually be drawn as an outline, which also keeps it crisp at eight pixels.
        outline(context, x + size * 0.12f, y + size * 0.22f, size * 0.76f, size * 0.56f,
                Math.max(1f, size * 0.11f), color);

        arrowHead(context, x + size * 0.66f, y + size * 0.22f, size * 0.24f, color, true);

        if (one)
            Render2DEngine.drawRound(context, x + size * 0.44f, y + size * 0.44f, size * 0.13f, size * 0.13f,
                    size * 0.07f, color);
    }

    /** A rectangular frame of the given thickness, drawn as four bars. */
    private static void outline(DrawContext context, float x, float y, float width, float height,
                               float t, Color color) {
        Render2DEngine.drawRound(context, x, y, width, t, t * 0.4f, color);
        Render2DEngine.drawRound(context, x, y + height - t, width, t, t * 0.4f, color);
        Render2DEngine.drawRound(context, x, y, t, height, t * 0.4f, color);
        Render2DEngine.drawRound(context, x + width - t, y, t, height, t * 0.4f, color);
    }

    /** A small filled arrowhead, pointing right or left. */
    private static void arrowHead(DrawContext context, float cx, float cy, float size, Color color, boolean right) {
        float half = size / 2f;
        if (right) triangle(context, cx - half * 0.4f, cy - half, size * 0.8f, size, color);
        else backTriangle(context, cx - half * 0.4f, cy - half, size * 0.8f, size, color);
    }

    /* ------------------------------------------------------------------ */
    /* Library and lists                                                   */
    /* ------------------------------------------------------------------ */

    /**
     * The Save control.
     *
     * <p>Filled when saved, a ring when not. The shape is the client's own heart sprite drawn through a
     * matrix, so it matches the hearts elsewhere in the HUD instead of introducing a second heart.
     */
    public static void heart(DrawContext context, float x, float y, float size, Color color, boolean filled) {
        var matrices = context.getMatrices();
        matrices.pushMatrix();
        matrices.translate(x, y);

        // Saved is the accent at full strength, unsaved the same silhouette at low alpha. Not an outline:
        // the sprite is additive, so the only way to hollow it would be to paint the plate colour back
        // over the middle - and a helper that needs to be told the colour behind it is a helper that gets
        // called wrong. Alpha says "not saved" clearly and survives any plate.
        Render2DEngine.drawHeart(context, filled ? color : Render2DEngine.injectAlpha(color, 70), size);

        matrices.popMatrix();
    }

    /** A plus. */
    public static void plus(DrawContext context, float x, float y, float size, Color color) {
        float t = Math.max(1.2f, size * 0.16f);
        Render2DEngine.drawRound(context, x + (size - t) / 2f, y + size * 0.15f, t, size * 0.7f, t * 0.4f, color);
        Render2DEngine.drawRound(context, x + size * 0.15f, y + (size - t) / 2f, size * 0.7f, t, t * 0.4f, color);
    }

    /** Three stacked bars - a queue or a list. */
    public static void list(DrawContext context, float x, float y, float size, Color color) {
        float t = Math.max(1f, size * 0.13f);
        float gap = size * 0.26f;

        for (int i = 0; i < 3; i++) {
            float ly = y + size * 0.22f + i * gap;
            // The top bar is short so the shape is a list rather than a hamburger menu.
            float width = i == 0 ? size * 0.5f : size * 0.72f;
            Render2DEngine.drawRound(context, x + size * 0.14f, ly, width, t, t * 0.4f, color);
        }
    }

    /** A speaker with waves, dimmed as the volume drops so the icon itself reads as a level. */
    public static void volume(DrawContext context, float x, float y, float size, Color color, int percent) {
        float bodyX = x + size * 0.16f;

        Render2DEngine.drawRound(context, bodyX, y + size * 0.38f, size * 0.14f, size * 0.24f, size * 0.04f, color);
        triangle(context, bodyX + size * 0.1f, y + size * 0.2f, size * 0.22f, size * 0.6f, color);

        if (percent < 0) return;

        for (int i = 0; i < 2; i++) {
            float wx = x + size * 0.56f + i * size * 0.16f;
            // Each wave lights only once the volume passes it: the whole icon is the level readout.
            boolean lit = percent > (i == 0 ? 1 : 55);
            Render2DEngine.drawRound(context, wx, y + size * (0.36f - i * 0.08f),
                    Math.max(1f, size * 0.09f), size * (0.28f + i * 0.16f), size * 0.05f,
                    lit ? color : Render2DEngine.injectAlpha(color, 55));
        }
    }

    /** A magnifier. */
    public static void search(DrawContext context, float x, float y, float size, Color color) {
        float t = Math.max(1f, size * 0.12f);
        float d = size * 0.6f;

        // A real ring: drawArc sweeps a band between an inner and an outer radius, which is the one way
        // to get a circular outline here without a background colour to cut the middle out with.
        Render2DEngine.drawArc(context, x + size * 0.08f, y + size * 0.08f, d, d, d / 2f, t, 0f, 360f, color, color);
        Render2DEngine.thickLine(context, x + size * 0.62f, y + size * 0.62f, x + size * 0.9f, y + size * 0.9f, t, color);
    }

    /** A cross. */
    public static void close(DrawContext context, float x, float y, float size, Color color) {
        float t = Math.max(1f, size * 0.13f);
        Render2DEngine.thickLine(context, x + size * 0.22f, y + size * 0.22f, x + size * 0.78f, y + size * 0.78f, t, color);
        Render2DEngine.thickLine(context, x + size * 0.78f, y + size * 0.22f, x + size * 0.22f, y + size * 0.78f, t, color);
    }

    /** A chevron, rotated by quarter turns: 0 right, 1 down, 2 left, 3 up. */
    public static void chevron(DrawContext context, float x, float y, float size, Color color, int quarter) {
        float t = Math.max(1f, size * 0.12f);
        float cx = x + size / 2f, cy = y + size / 2f, arm = size * 0.26f;

        float[][] points = switch (((quarter % 4) + 4) % 4) {
            case 1 -> new float[][]{{cx - arm, cy - arm * 0.5f}, {cx, cy + arm * 0.5f}, {cx + arm, cy - arm * 0.5f}};
            case 2 -> new float[][]{{cx + arm * 0.5f, cy - arm}, {cx - arm * 0.5f, cy}, {cx + arm * 0.5f, cy + arm}};
            case 3 -> new float[][]{{cx - arm, cy + arm * 0.5f}, {cx, cy - arm * 0.5f}, {cx + arm, cy + arm * 0.5f}};
            default -> new float[][]{{cx - arm * 0.5f, cy - arm}, {cx + arm * 0.5f, cy}, {cx - arm * 0.5f, cy + arm}};
        };

        Render2DEngine.thickLine(context, points[0][0], points[0][1], points[1][0], points[1][1], t, color);
        Render2DEngine.thickLine(context, points[1][0], points[1][1], points[2][0], points[2][1], t, color);
    }

    /** A screen on a stand - the device picker. */
    public static void device(DrawContext context, float x, float y, float size, Color color) {
        float t = Math.max(1f, size * 0.1f);

        outline(context, x + size * 0.12f, y + size * 0.2f, size * 0.76f, size * 0.46f, t, color);
        Render2DEngine.drawRound(context, x + size * 0.34f, y + size * 0.72f, size * 0.32f, t, t * 0.4f, color);
    }

    /** Three dots - the context menu handle. */
    public static void more(DrawContext context, float x, float y, float size, Color color) {
        float d = Math.max(1.2f, size * 0.16f);
        for (int i = 0; i < 3; i++)
            Render2DEngine.drawRound(context, x + size * 0.2f + i * size * 0.26f, y + (size - d) / 2f,
                    d, d, d / 2f, color);
    }

    /** An arrow leaving a box - "open in Spotify". */
    public static void external(DrawContext context, float x, float y, float size, Color color) {
        float t = Math.max(1f, size * 0.11f);

        outline(context, x + size * 0.12f, y + size * 0.3f, size * 0.52f, size * 0.58f, t, color);
        Render2DEngine.thickLine(context, x + size * 0.46f, y + size * 0.54f, x + size * 0.86f, y + size * 0.14f, t, color);
        arrowHead(context, x + size * 0.82f, y + size * 0.18f, size * 0.26f, color, true);
    }
}
