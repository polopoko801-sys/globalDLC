package thunder.hack.features.hud.impl;

import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.util.Identifier;
import thunder.hack.gui.font.FontRenderer;
import thunder.hack.gui.font.FontRenderers;
import thunder.hack.utility.render.Render2DEngine;

import java.awt.Color;
import java.util.List;

/**
 * The liquid-glass list panel shared by Potions and KeyBinds: a rounded pane of real backdrop blur, a title on the
 * left with an outline icon on the right, then one "left ... right" row per entry (optionally with a small icon).
 * Light, mostly see-through glass with white text; the GUI colour only tints it a little.
 */
final class GlassPanel {
    private GlassPanel() {
    }

    static final float PAD = 7f, HEADER_H = 20f, ROW_H = 18f, RADIUS = 6f, ICON = 9f;

    /** @param anim 0..1 how far the row has slid in; 1 = settled */
    record Row(Identifier icon, String left, String right, float anim) {
        Row(Identifier icon, String left, String right) {
            this(icon, left, right, 1f);
        }
    }

    static FontRenderer font() {
        return FontRenderers.sf_medium;
    }

    /** Width that fits the title and every row. */
    static float width(String title, List<Row> rows, float minWidth) {
        FontRenderer f = font();
        float w = PAD + f.getStringWidth(title) + 8 + ICON + PAD;
        for (Row r : rows) {
            float iconW = r.icon() != null ? ICON + 5 : 0;
            w = Math.max(w, PAD + iconW + f.getStringWidth(r.left()) + 18 + f.getStringWidth(r.right()) + PAD);
        }
        return Math.max(minWidth, w);
    }

    static float height(int rows) {
        return HEADER_H + rows * ROW_H + 2;
    }

    /** Height with each row counted by how far it has slid in, so the pane grows and shrinks smoothly. */
    static float height(List<Row> rows) {
        float h = HEADER_H + 2;
        for (Row r : rows) h += ROW_H * thunder.hack.features.hud.RowAnimator.ease(r.anim());
        return h;
    }

    static void draw(DrawContext context, float x, float y, float w, String title, Identifier titleIcon, List<Row> rows, Color tint) {
        // The pane rides with its most visible row: when the last entry leaves, the whole panel slides out to
        // the left with it, and it slides back in with the first one.
        float panel = 0f;
        for (Row r : rows) panel = Math.max(panel, r.anim());
        float pe = thunder.hack.features.hud.RowAnimator.ease(panel);
        if (pe <= 0.01f) return;
        x -= (1f - pe) * (w + 12f);

        float h = height(rows);
        // the pane: the client's liquid glass (Render2DEngine.drawRoundedBlur - blur, GUI-colour wash, milky veil)
        Render2DEngine.drawRoundedBlurFaded(context, x, y, w, h, RADIUS, tint, pe);

        FontRenderer f = font();
        f.drawString(context, title, x + PAD, y + 8, new Color(255, 255, 255, Math.round(235 * pe)).getRGB());
        if (titleIcon != null && pe > 0.9f)
            icon(context, titleIcon, x + w - PAD - ICON, y + 5.5f, ICON);

        float ry = y + HEADER_H;
        for (Row r : rows) {
            float e = thunder.hack.features.hud.RowAnimator.ease(r.anim());
            // each entry comes in from the left and leaves to the left, fading as it travels
            float slide = -(1f - e) * w * 0.6f;
            int a = Math.round(255 * e * pe);
            if (a > 8) {
                int color = (a << 24) | 0xFFFFFF;
                float tx = x + PAD + slide;
                if (r.icon() != null) {
                    if (e > 0.9f) icon(context, r.icon(), tx, ry + (ROW_H - ICON) / 2f - 1, ICON);
                    tx += ICON + 5;
                }
                f.drawString(context, r.left(), tx, ry + 6, color);
                f.drawString(context, r.right(), x + w - PAD - f.getStringWidth(r.right()) + slide, ry + 6, color);
            }
            ry += ROW_H * e;
        }
    }

    private static void icon(DrawContext context, Identifier id, float x, float y, float size) {
        int s = Math.round(size);
        context.drawTexture(RenderPipelines.GUI_TEXTURED, id, Math.round(x), Math.round(y), 0, 0, s, s, s, s);
    }
}
