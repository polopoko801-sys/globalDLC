package thunder.hack.gui.mainmenu;

import net.minecraft.client.gui.DrawContext;
import org.jetbrains.annotations.NotNull;
import thunder.hack.gui.font.FontRenderers;
import thunder.hack.utility.render.Render2DEngine;
import thunder.hack.utility.render.core.CloudanAnimationEngine;

import java.awt.Color;

/**
 * One circular glass button in the menu's navigation row.
 *
 * <p>Each button owns its own hover value and nothing else - position and size are passed in every frame, so
 * the row can re-centre and re-scale for the window without any button holding a stale layout. That is what
 * makes the row work unchanged from 1280x720 up to 4K and across GUI scales.
 *
 * <p><b>The glass.</b> A translucent dark disc, a hairline rim that is brighter along the top than the bottom,
 * and a soft shadow underneath. The rim is the part that sells it: a flat disc with a uniform border reads as
 * a sticker, while a rim that catches light on one edge reads as a curved surface.
 *
 * <p>Hover grows the button, lifts its fill, brightens the icon and fades a label in underneath. The growth is
 * eased through {@link CloudanAnimationEngine#approach} on real elapsed time, so it feels the same at 60fps
 * and at 240 - a per-frame lerp would make the animation speed depend on the framerate.
 */
public final class CircleButton {

    /** What the button does, which also picks its glyph. */
    public enum Kind {
        SINGLEPLAYER, MULTIPLAYER, SETTINGS, EXIT, PROFILE
    }

    private final Kind kind;
    private final String label;
    private final Runnable action;

    /** 0 idle, 1 fully hovered. Eased, not snapped. */
    private float hover;
    private long lastFrame;

    /** Where it was drawn last, for hit testing. */
    private float x, y, radius;

    public CircleButton(Kind kind, @NotNull String label, @NotNull Runnable action) {
        this.kind = kind;
        this.label = label;
        this.action = action;
    }

    public String label() {
        return label;
    }

    /**
     * Draws the button centred on ({@code cx}, {@code cy}).
     *
     * @param progress this button's own staggered 0..1 - it enters from the left as this runs up
     * @param alpha    the row's overall opacity
     */
    public void render(DrawContext context, float cx, float cy, float size, int mouseX, int mouseY,
                       float progress, float alpha) {
        long now = System.currentTimeMillis();
        float delta = lastFrame == 0L ? 16f : Math.min(250f, now - lastFrame);
        lastFrame = now;

        // The entry: slides in from the left and fades up. The offset is proportional to the button size so
        // the motion looks the same however large the row has been scaled.
        float entry = 1f - progress;
        float slide = -entry * size * 2.2f;
        float shown = Math.clamp(alpha * progress, 0f, 1f);
        if (shown <= 0.01f) {
            radius = 0f;
            return;
        }

        float drawX = cx + slide;

        boolean over = progress > 0.75f && inside(mouseX, mouseY, drawX, cy, size / 2f + 3f);
        hover = CloudanAnimationEngine.approach(hover, over ? 1f : 0f, 90f, delta);

        // Grows by a tenth at most. Anything more and the row visibly reflows under the cursor.
        float radiusNow = (size / 2f) * (1f + hover * 0.1f);

        this.x = drawX;
        this.y = cy;
        this.radius = radiusNow;

        float diameter = radiusNow * 2f;
        float left = drawX - radiusNow, top = cy - radiusNow;

        /* Shadow, then the disc, then the rim, then the glyph. */
        // A ROUND shadow. drawBlurredShadow blurs a filled rectangle, so under a circle it left a dark square
        // haze around every button - the "black square" behind them. Stacked translucent discs fade out as a
        // circle and need no texture at all.
        int shadowAlpha = Math.round((70 + 40 * hover) * shown);
        for (int ring = 5; ring >= 1; ring--) {
            float grow = ring * 2.2f;
            float r = radiusNow + grow;
            int a = Math.round(shadowAlpha * 0.22f * (1f - ring / 6f));
            if (a <= 0) continue;
            Render2DEngine.drawRound(context, drawX - r, cy - r + 2f, r * 2f, r * 2f, r, new Color(0, 0, 0, a));
        }

        // Accent glow on hover: soft coloured rings blooming out behind the disc, in the client accent colour.
        // This is what makes the button feel alive and premium rather than a flat grey puck.
        if (hover > 0.01f) {
            Color accent = thunder.hack.features.modules.client.HudEditor.getColor(0);
            for (int ring = 6; ring >= 1; ring--) {
                float r = radiusNow + ring * 3.0f * hover;
                int a = Math.round(70 * hover * (1f - ring / 7f) * shown);
                if (a <= 0) continue;
                Render2DEngine.drawRound(context, drawX - r, cy - r, r * 2f, r * 2f, r,
                        Render2DEngine.injectAlpha(accent, a));
            }
        }

        // The glass disc - a vertical two-tone (lighter top, darker bottom) reads as a lit curved surface.
        int fill = Math.round((206 + 34 * hover) * shown);
        Render2DEngine.drawRound(context, left, top, diameter, diameter, radiusNow,
                new Color(40, 40, 50, fill));
        // a subtle accent wash at the bottom of the disc on hover, so the colour bleeds into the glass
        if (hover > 0.01f) {
            Color accent = thunder.hack.features.modules.client.HudEditor.getColor(90);
            Render2DEngine.drawRound(context, left, cy, diameter, radiusNow, radiusNow,
                    Render2DEngine.injectAlpha(accent, Math.round(40 * hover * shown)));
        }

        // A lighter disc inset inside the dark one. Two flat tones with a gap between them read as a lit
        // surface far more cheaply than a real radial gradient, and it is what stops the button looking like
        // a hole punched in the wallpaper - the inside now has its own light rather than only an edge.
        float innerInset = radiusNow * 0.26f;
        float innerRadius = radiusNow - innerInset;
        Render2DEngine.drawRound(context, drawX - innerRadius, cy - innerRadius,
                innerRadius * 2f, innerRadius * 2f, innerRadius,
                new Color(255, 255, 255, Math.round((12 + 16 * hover) * shown)));

        drawRim(context, drawX, cy, radiusNow, shown, hover);

        float glyph = diameter * 0.56f;
        Color icon = new Color(255, 255, 255, Math.round((226 + 29 * hover) * shown));
        // The disc colour is handed to the glyph so a ring can be made by drawing a smaller disc back in it.
        // There is no way to cut a hole in this engine, and at this size a drawn ring is invisible - see the
        // note on drawGlyph.
        Color hole = new Color(34, 34, 42, fill);
        drawGlyph(context, drawX, cy, glyph, icon, hole);

        /* The label fades in under the button only while hovered - a row of five permanent captions is
         * clutter, and the icons are recognisable enough to not need them at rest. */
        if (hover > 0.02f) {
            float labelAlpha = hover * shown;
            float width = FontRenderers.sf_medium_mini.getStringWidth(label);
            FontRenderers.sf_medium_mini.drawString(context, label,
                    drawX - width / 2f, cy + radiusNow + 7f,
                    new Color(255, 255, 255, Math.round(230 * labelAlpha)).getRGB());
        }
    }

    /**
     * The hairline edge.
     *
     * <p>Drawn as a real ring through {@code drawArc}: the engine cannot punch a hole in a filled circle - a
     * transparent fill draws nothing rather than cutting - so an outline has to be an outline. The top half is
     * brighter than the bottom, which is what reads as a lit curved edge.
     */
    private void drawRim(DrawContext context, float cx, float cy, float radius, float shown, float hover) {
        float thickness = 1.1f;
        float d = radius * 2f;
        float left = cx - radius, top = cy - radius;

        // rim brightens AND tints towards the accent on hover, so the edge catches coloured light
        Color accent = thunder.hack.features.modules.client.HudEditor.getColor(0);
        int br = Math.round(255 - (255 - accent.getRed()) * hover * 0.5f);
        int bg = Math.round(255 - (255 - accent.getGreen()) * hover * 0.5f);
        int bb = Math.round(255 - (255 - accent.getBlue()) * hover * 0.5f);
        Color bright = new Color(br, bg, bb, Math.round((150 + 85 * hover) * shown));
        Color dim = new Color(br, bg, bb, Math.round((55 + 45 * hover) * shown));

        // 0 is straight up in this engine's arc convention, and angles run clockwise.
        Render2DEngine.drawArc(context, left, top, d, d, radius, thickness, -110f, 70f, bright, dim);
        Render2DEngine.drawArc(context, left, top, d, d, radius, thickness, 70f, 250f, dim, bright);
    }

    /* ------------------------------------------------------------------ */
    /* Glyphs                                                              */
    /* ------------------------------------------------------------------ */

    /* ------------------------------------------------------------------ */
    /* Icon grid                                                           */
    /* ------------------------------------------------------------------ */

    /**
     * Every glyph is drawn on the same 24x24 grid and with the same stroke weight.
     *
     * <p>That is the whole reason they look like a set rather than five drawings. The previous versions each
     * picked their own proportions - a filled torso here, an eighth of the size for a tooth there - so the
     * person was heavy, the gear was spiky and nothing shared a line weight. Now a coordinate means the same
     * thing in all five, the stroke is one number, and anything built from {@link #stroke} and {@link #at}
     * automatically matches everything else.
     *
     * <p>Outlines rather than solid shapes: a thin even line is what reads as a modern icon at this size, and
     * it keeps the middle of the button open so the glass behind shows through.
     */
    private static final float GRID = 24f;

    /** Grid coordinate to screen coordinate. */
    private static float at(float centre, float size, float grid) {
        return centre + (grid - GRID / 2f) / GRID * size;
    }

    /** One line weight for every glyph, scaled with the button and never thinner than a pixel. */
    private static float stroke(float size) {
        return Math.max(1f, size * (1.7f / GRID));
    }

    /** A straight stroke between two grid points. */
    private static void line(DrawContext context, float cx, float cy, float size,
                             float x1, float y1, float x2, float y2, Color color) {
        Render2DEngine.thickLine(context,
                at(cx, size, x1), at(cy, size, y1),
                at(cx, size, x2), at(cy, size, y2),
                stroke(size), color);
    }

    /**
     * A circular stroke, or part of one, centred on a grid point.
     *
     * <p>Drawn with {@code drawArc}, which sweeps a band between an inner and an outer radius - a real ring.
     * The engine cannot cut a hole in a filled circle (a transparent fill draws nothing rather than cutting),
     * so an outline has to be drawn as an outline.
     *
     * @param from degrees, 0 straight up, running clockwise
     */
    /** A filled disc centred on a grid point. */
    private static void disc(DrawContext context, float cx, float cy, float size,
                             float gx, float gy, float radius, Color color) {
        float r = radius / GRID * size;
        float x = at(cx, size, gx), y = at(cy, size, gy);
        Render2DEngine.drawRound(context, x - r, y - r, r * 2f, r * 2f, r, color);
    }

    /**
     * A ring, made by drawing a disc and then the background colour back over its middle.
     *
     * <p>Not {@code drawArc}. That produces a band between an inner and an outer radius, which is correct in
     * principle and invisible in practice here: a button is about forty pixels across, the glyph half of that,
     * and a ring inside the glyph ends up with a radius of two or three pixels and a band of one. Rendered as
     * ninety thin triangles it comes out as nothing at all - which is exactly what happened, and why the first
     * version showed only the gear's teeth and the power symbol's stem, the two parts not made of arcs.
     *
     * <p>Two filled discs always resolve, however small. The cost is needing to know what is behind, which is
     * why the caller passes its own fill colour in.
     */
    private static void ring(DrawContext context, float cx, float cy, float size,
                             float gx, float gy, float radius, float thickness, Color color, Color hole) {
        disc(context, cx, cy, size, gx, gy, radius, color);
        disc(context, cx, cy, size, gx, gy, Math.max(0.5f, radius - thickness), hole);
    }

    /** A filled rounded bar between two grid points, used for limbs and stems. */
    private static void bar(DrawContext context, float cx, float cy, float size,
                            float gx, float gy, float w, float h, float round, Color color) {
        float x = at(cx, size, gx), y = at(cy, size, gy);
        float ww = w / GRID * size, hh = h / GRID * size;
        Render2DEngine.drawRound(context, x - ww / 2f, y - hh / 2f, ww, hh, round / GRID * size, color);
    }

    /* ------------------------------------------------------------------ */
    /* Glyphs                                                              */
    /* ------------------------------------------------------------------ */

    /**
     * The glyphs, as SOLID shapes.
     *
     * <p>Outlines were the first attempt and they simply do not survive at this scale - see {@link #ring}. The
     * reference these are modelled on uses filled white symbols for the same reason: at forty pixels a glyph
     * is read by its silhouette, not by its line work.
     */
    private void drawGlyph(DrawContext context, float cx, float cy, float size, Color color, Color hole) {
        switch (kind) {
            case SINGLEPLAYER -> person(context, cx, cy, size, color);
            case MULTIPLAYER -> {
                // The back figure is offset, smaller and dimmer. Two identical copies side by side read as a
                // pair of unrelated icons; one tucked behind the other reads as a group.
                person(context, cx - size * 0.17f, cy + size * 0.02f, size * 0.74f,
                        Render2DEngine.injectAlpha(color, Math.round(color.getAlpha() * 0.6f)));
                person(context, cx + size * 0.15f, cy, size * 0.9f, color);
            }
            case SETTINGS -> gear(context, cx, cy, size, color, hole);
            case EXIT -> power(context, cx, cy, size, color, hole);
            case PROFILE -> badge(context, cx, cy, size, color, hole);
        }
    }

    /** A filled head over filled shoulders. */
    private static void person(DrawContext context, float cx, float cy, float size, Color color) {
        disc(context, cx, cy, size, 12f, 8f, 4.1f, color);
        // The shoulders are wide, low and rounded at the top - a tall pill would read as a bottle.
        bar(context, cx, cy, size, 12f, 17.5f, 13f, 8f, 3.6f, color);
    }

    /** A cog: a solid disc with the middle taken back out, plus eight teeth. */
    private static void gear(DrawContext context, float cx, float cy, float size, Color color, Color hole) {
        for (int i = 0; i < 8; i++) {
            double angle = Math.toRadians(i * 45.0);
            float dx = (float) Math.sin(angle), dy = -(float) Math.cos(angle);
            bar(context, cx, cy, size, 12f + dx * 8.6f, 12f + dy * 8.6f, 4.2f, 4.2f, 1.2f, color);
        }

        ring(context, cx, cy, size, 12f, 12f, 7.4f, 3.1f, color, hole);
    }

    /** The power symbol: a thick broken ring with a stem standing in the gap. */
    private static void power(DrawContext context, float cx, float cy, float size, Color color, Color hole) {
        ring(context, cx, cy, size, 12f, 13f, 7.6f, 2.9f, color, hole);
        // The gap: the background painted back over the top of the ring, then the stem through it.
        bar(context, cx, cy, size, 12f, 5.8f, 6.4f, 5.2f, 0f, hole);
        bar(context, cx, cy, size, 12f, 7.4f, 2.9f, 9f, 1.4f, color);
    }

    /** A person inside a ring - a profile picture, which is what distinguishes it from Singleplayer. */
    private static void badge(DrawContext context, float cx, float cy, float size, Color color, Color hole) {
        ring(context, cx, cy, size, 12f, 12f, 11f, 2.4f, color, hole);
        disc(context, cx, cy, size, 12f, 9.4f, 3f, color);
        bar(context, cx, cy, size, 12f, 16.6f, 9.4f, 5.8f, 2.6f, color);
    }

    /* ------------------------------------------------------------------ */
    /* Input                                                               */
    /* ------------------------------------------------------------------ */

    private static boolean inside(double mx, double my, float cx, float cy, float radius) {
        double dx = mx - cx, dy = my - cy;
        return dx * dx + dy * dy <= radius * radius;
    }

    public boolean covers(double mx, double my) {
        return radius > 0f && inside(mx, my, x, y, radius + 2f);
    }

    public boolean click(double mx, double my) {
        if (!covers(mx, my)) return false;
        action.run();
        return true;
    }

    /** Clears the hover so the button does not animate from a stale state when the menu reopens. */
    public void reset() {
        hover = 0f;
        lastFrame = 0L;
        radius = 0f;
    }
}
