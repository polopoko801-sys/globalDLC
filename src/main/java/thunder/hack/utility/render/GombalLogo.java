package thunder.hack.utility.render;

import net.minecraft.client.gui.DrawContext;

/**
 * The glass "G" brand logo - one place so the watermark, the ClickGUI and anything else that shows the brand
 * all draw the same thing.
 *
 * <p>Static on purpose: no glow ring behind it and no breathing / coin-flip motion. The PNG has a real alpha
 * channel, so it is drawn with normal blending in its own colours.
 */
public final class GombalLogo {
    private GombalLogo() {
    }

    /**
     * @param glow kept for call-site compatibility; the logo no longer draws a glow
     */
    public static void draw(DrawContext context, float x, float y, float size, float glow) {
        Render2DEngine.renderTexture(context, TextureStorage.miniLogo, x, y, size, size, 0, 0, 128, 128, 128, 128);
    }
}
