package thunder.hack.gui.clickui;

import net.minecraft.client.gui.DrawContext;
import thunder.hack.features.modules.Module;
import thunder.hack.utility.render.Render2DEngine;

import java.awt.Color;
import java.util.Locale;

/**
 * Sidebar glyphs for the ClickGUI: one icon per main category (Combat, Render, Movement, Player, Client,
 * Misc, HUD) and per OTHER row (Configs, Friends, Edit HUD).
 *
 * <p>Drawn in code on purpose. The client used to draw the SAME two little squares on every row, which
 * told you nothing, and the PNG set in {@code textures/gui/headers} is no help either - client.png,
 * misc.png, hud.png and stock.png are one and the same file, so half the categories would still have
 * ended up with a copy of the same shape. Everything here is authored on a 10x10 grid and scaled, so the
 * whole sidebar reads as one icon set.
 */
public final class CategoryIcons {
    private CategoryIcons() {
    }

    /**
     * @param category the main category this row shows, or null for an OTHER row
     * @param action   the OTHER row's action id ("configs" / "friends" / "hud"), null for a category
     * @param size     box size in pixels; the glyph is drawn inside a size x size square
     */
    public static void draw(DrawContext context, Module.Category category, String action,
                            float x, float y, float size, Color color) {
        float u = size / 10f; // one authored unit, so every glyph below is on a 0..10 grid
        String key = category != null
                ? "cat:" + category.getName().toLowerCase(Locale.ROOT)
                : "act:" + (action == null ? "" : action.toLowerCase(Locale.ROOT));

        switch (key) {
            case "cat:combat" -> sword(context, x, y, u, color);
            case "cat:render" -> eye(context, x, y, u, color);
            case "cat:movement" -> chevrons(context, x, y, u, color);
            case "cat:player" -> person(context, x, y, u, color);
            case "cat:client" -> sliders(context, x, y, u, color);
            case "cat:misc" -> blocks(context, x, y, u, color);
            case "cat:hud" -> layout(context, x, y, u, color);
            case "act:configs" -> folder(context, x, y, u, color);
            case "act:friends" -> people(context, x, y, u, color);
            case "act:hud" -> pencil(context, x, y, u, color);
            default -> dots(context, x, y, u, color);
        }
    }

    /** Diagonal blade, crossguard and grip. */
    private static void sword(DrawContext c, float x, float y, float u, Color col) {
        Render2DEngine.thickLine(c, x + 2.0f * u, y + 8.0f * u, x + 7.6f * u, y + 2.4f * u, 1.8f * u, col);
        Render2DEngine.thickLine(c, x + 2.1f * u, y + 5.3f * u, x + 4.7f * u, y + 7.9f * u, 1.6f * u, col);
        Render2DEngine.drawRound(c, x + 0.6f * u, y + 7.8f * u, 1.8f * u, 1.8f * u, 0.9f * u, col);
    }

    /**
     * The "Render" category: an eye. Two half-circle lids squashed vertically (a lens, not a ring) with
     * the pupil inside - the matrix squash is what makes it read as an eye instead of a circle.
     */
    private static void eye(DrawContext c, float x, float y, float u, Color col) {
        float cx = x + 5f * u, cy = y + 5f * u;
        float radius = 4.6f * u;

        c.getMatrices().pushMatrix();
        c.getMatrices().translate(cx, cy);
        c.getMatrices().scale(1f, 0.6f);
        c.getMatrices().translate(-cx, -cy);
        {
            float left = cx - radius, top = cy - radius;
            // 0 degrees is twelve o'clock, so the sweep 270 -> 450 is the top lid and 90 -> 270 the bottom.
            Render2DEngine.drawArc(c, left, top, radius * 2, radius * 2, radius, 1.2f * u, 270f, 450f, col, col);
            Render2DEngine.drawArc(c, left, top, radius * 2, radius * 2, radius, 1.2f * u, 90f, 270f, col, col);
        }
        c.getMatrices().popMatrix();

        Render2DEngine.drawRound(c, cx - 1.5f * u, cy - 1.5f * u, 3f * u, 3f * u, 1.5f * u, col);
    }

    /** Movement: double chevron pointing right. */
    private static void chevrons(DrawContext c, float x, float y, float u, Color col) {
        Render2DEngine.thickLine(c, x + 1.2f * u, y + 2.2f * u, x + 3.6f * u, y + 5f * u, 1.7f * u, col);
        Render2DEngine.thickLine(c, x + 3.6f * u, y + 5f * u, x + 1.2f * u, y + 7.8f * u, 1.7f * u, col);
        Render2DEngine.thickLine(c, x + 5.4f * u, y + 2.2f * u, x + 7.8f * u, y + 5f * u, 1.7f * u, col);
        Render2DEngine.thickLine(c, x + 7.8f * u, y + 5f * u, x + 5.4f * u, y + 7.8f * u, 1.7f * u, col);
    }

    /** Player: head over shoulders. */
    private static void person(DrawContext c, float x, float y, float u, Color col) {
        Render2DEngine.drawRound(c, x + 3.0f * u, y + 1.2f * u, 4f * u, 4f * u, 2f * u, col);
        Render2DEngine.drawRound(c, x + 1.5f * u, y + 6.2f * u, 7f * u, 3.1f * u, 1.55f * u, col);
    }

    /** Two people, one behind the other. */
    private static void people(DrawContext c, float x, float y, float u, Color col) {
        Render2DEngine.drawRound(c, x + 0.5f * u, y + 2.6f * u, 3.4f * u, 3.4f * u, 1.7f * u, col);
        Render2DEngine.drawRound(c, x + 0.2f * u, y + 6.6f * u, 4.4f * u, 2.6f * u, 1.3f * u, col);
        Render2DEngine.drawRound(c, x + 4.8f * u, y + 1.5f * u, 3.8f * u, 3.8f * u, 1.9f * u, col);
        Render2DEngine.drawRound(c, x + 4.1f * u, y + 6.0f * u, 5.7f * u, 2.8f * u, 1.4f * u, col);
    }

    /** Client: three sliders. */
    private static void sliders(DrawContext c, float x, float y, float u, Color col) {
        float[] rows = {2.2f, 5f, 7.8f};
        float[] knobs = {3.4f, 6.8f, 4.8f};
        for (int i = 0; i < rows.length; i++) {
            Render2DEngine.thickLine(c, x + 0.8f * u, y + rows[i] * u, x + 9.2f * u, y + rows[i] * u, 1.3f * u, col);
            Render2DEngine.drawRound(c, x + (knobs[i] - 1.3f) * u, y + (rows[i] - 1.3f) * u, 2.6f * u, 2.6f * u, 1.3f * u, col);
        }
    }

    /** Misc: a 2x2 block grid. */
    private static void blocks(DrawContext c, float x, float y, float u, Color col) {
        Render2DEngine.drawRound(c, x + 1.2f * u, y + 1.2f * u, 3.4f * u, 3.4f * u, 1f, col);
        Render2DEngine.drawRound(c, x + 5.4f * u, y + 1.2f * u, 3.4f * u, 3.4f * u, 1f, col);
        Render2DEngine.drawRound(c, x + 1.2f * u, y + 5.4f * u, 3.4f * u, 3.4f * u, 1f, col);
        Render2DEngine.drawRound(c, x + 5.4f * u, y + 5.4f * u, 3.4f * u, 3.4f * u, 1f, col);
    }

    /** HUD: a layout - one wide bar over a two column grid. */
    private static void layout(DrawContext c, float x, float y, float u, Color col) {
        Render2DEngine.thickLine(c, x + 0.8f * u, y + 2.2f * u, x + 9.2f * u, y + 2.2f * u, 1.5f * u, col);
        Render2DEngine.thickLine(c, x + 0.8f * u, y + 5.2f * u, x + 5f * u, y + 5.2f * u, 1.5f * u, col);
        Render2DEngine.thickLine(c, x + 0.8f * u, y + 7.8f * u, x + 5f * u, y + 7.8f * u, 1.5f * u, col);
        Render2DEngine.thickLine(c, x + 6.2f * u, y + 5.2f * u, x + 9.2f * u, y + 5.2f * u, 1.5f * u, col);
        Render2DEngine.thickLine(c, x + 6.2f * u, y + 7.8f * u, x + 9.2f * u, y + 7.8f * u, 1.5f * u, col);
    }

    /** Configs: a folder with a tab. */
    private static void folder(DrawContext c, float x, float y, float u, Color col) {
        Render2DEngine.drawRound(c, x + 0.8f * u, y + 2.4f * u, 4f * u, 2f * u, 0.7f * u, col);
        Render2DEngine.drawRound(c, x + 0.8f * u, y + 3.4f * u, 8.4f * u, 5f * u, 1.3f * u, col);
    }

    /** Edit HUD: a pencil. */
    private static void pencil(DrawContext c, float x, float y, float u, Color col) {
        Render2DEngine.thickLine(c, x + 2.8f * u, y + 7.2f * u, x + 7.2f * u, y + 2.8f * u, 1.9f * u, col);
        Render2DEngine.thickLine(c, x + 6.4f * u, y + 1.6f * u, x + 8.4f * u, y + 3.6f * u, 1.6f * u, col);
        Render2DEngine.drawRound(c, x + 0.9f * u, y + 7.1f * u, 2.2f * u, 2.2f * u, 1.1f * u, col);
    }

    /** Anything unrecognised (addon categories): a plain dot grid. */
    private static void dots(DrawContext c, float x, float y, float u, Color col) {
        Render2DEngine.drawRound(c, x + 1.2f * u, y + 1.2f * u, 2.2f * u, 2.2f * u, 1.1f * u, col);
        Render2DEngine.drawRound(c, x + 6.6f * u, y + 1.2f * u, 2.2f * u, 2.2f * u, 1.1f * u, col);
        Render2DEngine.drawRound(c, x + 1.2f * u, y + 6.6f * u, 2.2f * u, 2.2f * u, 1.1f * u, col);
        Render2DEngine.drawRound(c, x + 6.6f * u, y + 6.6f * u, 2.2f * u, 2.2f * u, 1.1f * u, col);
    }
}