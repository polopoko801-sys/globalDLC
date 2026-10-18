package thunder.hack.utility.render.core;

import net.minecraft.util.math.Vec3d;
import org.jetbrains.annotations.NotNull;
import thunder.hack.utility.render.Render2DEngine;
import thunder.hack.utility.render.TextureStorage;

import java.awt.Color;

/**
 * Glow, from a painted sprite rather than from maths.
 *
 * <p>A soft falloff is something you draw once into a texture and reuse forever; approximating it with
 * stacked translucent geometry costs more, bands at the edges and never quite looks like light. So the
 * halo and the ring are PNGs with the gradient already in them, drawn additively so the black backdrop
 * disappears and overlapping layers brighten each other the way light does.
 *
 * <p>Intensity comes from stacking layers, not from turning the alpha up: one pass looks flat however
 * bright it is, and the quality dial decides how many passes you get.
 */
public final class CloudanGlowRenderer {
    private CloudanGlowRenderer() {
    }

    /**
     * A soft round glow centred on a point.
     *
     * @param radius    in blocks
     * @param intensity 0-1; scales the alpha of every layer
     */
    public static void halo(@NotNull Vec3d centre, double radius, @NotNull Color color,
                            float intensity, boolean throughWalls) {
        if (CloudanRenderCore.reduceEffects()) {
            // One flat pass. Still a glow, just not a stack of them.
            submit(TextureStorage.fxHalo, centre, radius, 0, color, intensity * 0.7f, throughWalls);
            return;
        }

        int layers = CloudanRenderCore.glowLayers();
        for (int i = 0; i < layers; i++) {
            // Each layer is wider and fainter than the last, which is what gives the falloff depth
            // instead of a single hard-edged disc.
            float t = i / (float) layers;
            double r = radius * (1f + t * 0.55f);
            float alpha = intensity * (1f - t * 0.55f) / layers;
            submit(TextureStorage.fxHalo, centre, r, 0, color, alpha, throughWalls);
        }
    }

    /**
     * A thin bright ring lying flat on the ground.
     *
     * @param angle rotation in radians - drive it off time for a ring that turns
     */
    public static void ring(@NotNull Vec3d centre, double radius, double angle, @NotNull Color color,
                            float intensity, boolean throughWalls) {
        submit(TextureStorage.fxRing, centre, radius, angle, color, intensity, throughWalls);
    }

    /** An expanding shockwave - sharp on the outside, trailing inwards. */
    public static void shockwave(@NotNull Vec3d centre, double radius, double angle, @NotNull Color color,
                                 float intensity, boolean throughWalls) {
        submit(TextureStorage.fxShockwave, centre, radius, angle, color, intensity, throughWalls);
    }

    /** A hot core with soft spikes - the first frames of an impact. */
    public static void flash(@NotNull Vec3d centre, double radius, double angle, @NotNull Color color,
                             float intensity, boolean throughWalls) {
        submit(TextureStorage.fxFlash, centre, radius, angle, color, intensity, throughWalls);
    }

    private static void submit(net.minecraft.util.Identifier texture, Vec3d centre, double radius,
                               double angle, Color color, float intensity, boolean throughWalls) {
        if (intensity <= 0.002f || radius <= 0) return;

        int alpha = Math.round(Math.min(1f, Math.max(0f, intensity)) * color.getAlpha());
        if (alpha <= 1) return;

        CloudanBatchRenderer.groundSprite(texture, centre, radius, angle,
                Render2DEngine.injectAlpha(color, alpha), throughWalls);
    }
}
