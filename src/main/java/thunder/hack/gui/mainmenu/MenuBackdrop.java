package thunder.hack.gui.mainmenu;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import thunder.hack.utility.render.Render2DEngine;

import java.awt.Color;

/**
 * The cinematic layer behind the menu: wallpaper, slow zoom, parallax, vignette and a lighting gradient.
 *
 * <p>Shared by the menu and the wallpaper gallery so both sit on an identical background - a gallery that
 * looked subtly different from the menu behind it would make every "apply" look like it had changed something
 * else as well.
 *
 * <p>Everything here is deliberately slight. The zoom covers four percent over a minute and a half, the
 * parallax moves by a fraction of the crop, and the vignette only darkens the outer edges. The brief was
 * cinematic, not busy: these are the things you notice only when you turn them off.
 *
 * <p><b>No film grain.</b> It would need a noise texture generated and uploaded, redrawn tiled every frame,
 * for an effect that is close to invisible at this scale - so it is left out rather than shipped as a cost
 * with no benefit. The vignette and the gradient are what actually carry the mood.
 */
public final class MenuBackdrop {
    private MenuBackdrop() {
    }

    /** Seconds for one full in-and-out of the slow zoom. */
    private static final float ZOOM_PERIOD = 90f;
    /** How far in the zoom pushes, as a fraction. Four percent is felt rather than seen. */
    private static final float ZOOM_AMOUNT = 0.04f;
    /**
     * Base crop for photos. The cursor can only pan inside the part of the image that is cropped off, and
     * with the old 4% breathing zoom that was a few pixels - "practically not noticeable". 12% gives the
     * parallax real room; drawn wallpapers ignore it and move their layers by depth instead.
     */
    private static final float BASE_ZOOM = 1.12f;

    /** Eased cursor offset, so the scene glides after the mouse instead of snapping to it. */
    private static float smoothX, smoothY;
    private static long lastFrame;

    /**
     * Draws the wallpaper with its motion, then the veil, gradient and vignette over it.
     *
     * @param dim extra darkening on top, 0 to 1 - the gallery uses more than the menu so its cards read
     * @return false when no wallpaper is selected, so the caller can fall back to the vanilla panorama
     */
    public static boolean render(DrawContext context, Screen screen, float width, float height,
                                 int mouseX, int mouseY, float dim, float delta) {
        MenuBackground.refresh();

        // Breathing zoom: a sine so it eases at both ends instead of snapping back when it wraps.
        float phase = (System.currentTimeMillis() % (long) (ZOOM_PERIOD * 1000L)) / (ZOOM_PERIOD * 1000f);
        float zoom = BASE_ZOOM + ZOOM_AMOUNT * (0.5f - 0.5f * (float) Math.cos(phase * Math.PI * 2));

        // Parallax from the cursor, as -1..1 either side of centre. Clamped inside MenuBackground so the crop
        // can never slide past the edge of the image.
        float targetX = width <= 0 ? 0f : Math.clamp((mouseX / width - 0.5f) * 2f, -1f, 1f);
        float targetY = height <= 0 ? 0f : Math.clamp((mouseY / height - 0.5f) * 2f, -1f, 1f);
        long now = System.currentTimeMillis();
        float dt = lastFrame == 0L ? 16f : Math.min(100f, now - lastFrame);
        lastFrame = now;
        float follow = 1f - (float) Math.exp(-dt / 220.0); // ~220 ms half-life, frame-rate independent
        smoothX += (targetX - smoothX) * follow;
        smoothY += (targetY - smoothY) * follow;
        float panX = smoothX, panY = smoothY;

        boolean drawn = MenuBackground.render(context, width, height, zoom, panX, panY);

        if (!drawn) {
            // Nothing to show yet (still decoding) or nothing chosen: a plain dark gradient.
            //
            // NOT screen.renderBackground: that applies the vanilla blur, and vanilla has usually already
            // called renderBackground for this frame before render() - a second blur in one frame is a hard
            // crash ("Can only blur once per frame"), which is what opening Customize did while a wallpaper
            // was still loading.
            Render2DEngine.verticalGradient(context, 0, 0, width, height, new Color(14, 14, 22), new Color(4, 4, 8));
        }

        veil(context, width, height, dim);
        return drawn;
    }

    /** The veil, the lighting gradient and the vignette, in that order. */
    private static void veil(DrawContext context, float width, float height, float dim) {
        int base = Math.round(Math.clamp(dim, 0f, 1f) * 255f);
        if (base > 0)
            Render2DEngine.drawRound(context, 0, 0, width, height, 0f, new Color(0, 0, 0, base));

        // A soft diagonal lift from the upper left, which gives the flat image a direction of light.
        Render2DEngine.draw2DGradientRect(context, 0, 0, width, height,
                new Color(255, 255, 255, 10), new Color(255, 255, 255, 16),
                new Color(0, 0, 0, 0), new Color(0, 0, 0, 0));

        vignette(context, width, height);
    }

    /**
     * The vignette, as four edge gradients rather than a radial texture.
     *
     * <p>A true radial falloff would need a texture or a shader; four bands fading inwards give the same
     * read - bright centre, dark corners, because the corners get darkened twice - for four quads and no
     * assets at all.
     */
    private static void vignette(DrawContext context, float width, float height) {
        float band = Math.min(width, height) * 0.42f;

        Color dark = new Color(0, 0, 0, 132);
        Color clear = new Color(0, 0, 0, 0);

        Render2DEngine.verticalGradient(context, 0, 0, width, band, dark, clear);
        Render2DEngine.verticalGradient(context, 0, height - band, width, height, clear, dark);
        Render2DEngine.horizontalGradient(context, 0, 0, band, height, dark, clear);
        Render2DEngine.horizontalGradient(context, width - band, 0, width, height, clear, dark);
    }
}
