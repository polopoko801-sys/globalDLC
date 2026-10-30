package thunder.hack.gui.mainmenu;

import net.minecraft.client.gui.DrawContext;
import thunder.hack.utility.render.Render2DEngine;
import thunder.hack.utility.render.ScreenCapture;

import java.awt.Color;

/**
 * The frosted glass of the main menu: the wallpaper behind, blurred, under a neutral smoky veil and a soft
 * sheen from the top. The same backdrop blur as the HUD's liquid glass (Potions and friends), but with no GUI
 * colour washed into it and no rim - in the menu the wallpaper is the only colour, and an accent glow or an
 * outline on every panel would fight it.
 *
 * <p>The menu screen calls {@link #capture()} once per frame right after the wallpaper is drawn, so the glass
 * blurs the wallpaper and nothing else - never the clock, never another panel.
 */
public final class MenuGlass {
    private MenuGlass() {
    }

    private static final Color VEIL = new Color(16, 16, 22);

    /** Snapshot of the frame so far; call after the backdrop and before any glass. */
    public static void capture() {
        ScreenCapture.captureFrame();
    }

    /**
     * One pane of glass.
     *
     * @param alpha the pane's opacity, 0 to 1 - the blur fades with it
     * @param lift  0 at rest, 1 hovered: the veil thins and the sheen brightens
     */
    public static void draw(DrawContext context, float x, float y, float w, float h, float radius,
                            float alpha, float lift) {
        draw(context, x, y, w, h, radius, alpha, lift, true);
    }

    /**
     * @param sheen false for a cut-out redrawn inside another pane (the hole of a ring glyph): the same glass,
     *              without a second highlight of its own
     */
    public static void draw(DrawContext context, float x, float y, float w, float h, float radius,
                            float alpha, float lift, boolean sheen) {
        if (alpha <= 0.01f || w <= 0f || h <= 0f) return;

        boolean blurred = Render2DEngine.drawBackdropBlur(context, x, y, w, h, radius, 9f, 1f, alpha);

        // neutral smoke: darker without the blur, since then it is all that separates the pane from the image
        int veil = Math.round((blurred ? 92 - 24 * lift : 170 - 30 * lift) * alpha);
        Render2DEngine.drawRound(context, x, y, w, h, radius, Render2DEngine.injectAlpha(VEIL, veil));

        // a milky lift so the glass reads bright rather than as a dark hole
        Render2DEngine.drawRound(context, x, y, w, h, radius,
                new Color(255, 255, 255, Math.round((10 + 12 * lift) * alpha)));

        // No sheen: every shape of highlight tried here showed as a box, a band or a wedge on these panes.
        // The blur, the smoky veil and the milky lift carry the glass on their own.
    }
}
