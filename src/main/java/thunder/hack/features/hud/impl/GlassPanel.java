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

    record Row(Identifier icon, String left, String right) {
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

    static void draw(DrawContext context, float x, float y, float w, String title, Identifier titleIcon, List<Row> rows, Color tint) {
        float h = height(rows.size());
        // the pane: the client's liquid glass (Render2DEngine.drawRoundedBlur - blur, GUI-colour wash, milky veil)
        Render2DEngine.drawRoundedBlur(context, x, y, w, h, RADIUS, tint);

        FontRenderer f = font();
        f.drawString(context, title, x + PAD, y + 8, new Color(255, 255, 255, 235).getRGB());
        if (titleIcon != null)
            icon(context, titleIcon, x + w - PAD - ICON, y + 5.5f, ICON);

        float ry = y + HEADER_H;
        for (Row r : rows) {
            float tx = x + PAD;
            if (r.icon() != null) {
                icon(context, r.icon(), tx, ry + (ROW_H - ICON) / 2f - 1, ICON);
                tx += ICON + 5;
            }
            f.drawString(context, r.left(), tx, ry + 6, -1);
            f.drawString(context, r.right(), x + w - PAD - f.getStringWidth(r.right()), ry + 6, -1);
            ry += ROW_H;
        }
    }

    private static void icon(DrawContext context, Identifier id, float x, float y, float size) {
        int s = Math.round(size);
        context.drawTexture(RenderPipelines.GUI_TEXTURED, id, Math.round(x), Math.round(y), 0, 0, s, s, s, s);
    }
}
