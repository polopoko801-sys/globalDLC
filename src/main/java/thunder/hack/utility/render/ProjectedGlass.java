package thunder.hack.utility.render;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.util.math.MathHelper;
import org.joml.Matrix3x2f;
import org.joml.Vector2f;

/**
 * Helpers for world shapes that are projected to the screen and drawn in onRender2D as quads.
 *
 * <p>Two things every such shape needs on 1.21.11:
 * <ul>
 *     <li>the GUI pipelines CULL back faces, and a projected quad flips its winding whenever it is seen
 *     from the other side - so every quad is re-ordered to the front-facing winding (negative signed area
 *     in y-down screen space) before it is emitted;</li>
 *     <li>glass: the frame snapshot ({@link ScreenCapture}) sampled inside the shape at shifted offsets and
 *     stacked with progressive alpha (layer k = 1/(k+1)), which averages to a blur.</li>
 * </ul>
 * A shape is handed over as a list of quads: {@code float[8]} screen corners a,b,c,d plus per-corner
 * sample offsets ({@code float[8]}, may be null) for refraction.
 */
public final class ProjectedGlass {
    private ProjectedGlass() {
    }

    public static boolean backFacing(float ax, float ay, float bx, float by, float cx, float cy, float dx, float dy) {
        float area = (ax * by - bx * ay) + (bx * cy - cx * by) + (cx * dy - dx * cy) + (dx * ay - ax * dy);
        return area > 0;
    }

    /** A coloured quad in front-facing order. */
    public static void quad(VertexConsumer vc, Matrix3x2f pose, float[] p, int ca, int cb, int cc, int cd) {
        if (backFacing(p[0], p[1], p[2], p[3], p[4], p[5], p[6], p[7]))
            GuiRender.quad(vc, pose, p[6], p[7], p[4], p[5], p[2], p[3], p[0], p[1], cd, cc, cb, ca);
        else
            GuiRender.quad(vc, pose, p[0], p[1], p[2], p[3], p[4], p[5], p[6], p[7], ca, cb, cc, cd);
    }

    /** A quad textured with the frame snapshot; corner i samples its own screen spot + off[2i], off[2i+1] + (ox, oy). */
    public static void texQuad(VertexConsumer vc, Matrix3x2f pose, float sw, float sh, float[] p, float[] off,
                               float ox, float oy, int ca, int cb, int cc, int cd) {
        int[] order = backFacing(p[0], p[1], p[2], p[3], p[4], p[5], p[6], p[7]) ? new int[]{3, 2, 1, 0} : new int[]{0, 1, 2, 3};
        int[] cols = {ca, cb, cc, cd};
        for (int i : order) {
            float x = p[i * 2], y = p[i * 2 + 1];
            float sx = ox + (off == null ? 0f : off[i * 2]);
            float sy = oy + (off == null ? 0f : off[i * 2 + 1]);
            Vector2f scr = pose.transformPosition(x, y, new Vector2f());
            float u = MathHelper.clamp((scr.x + sx) / sw, 0f, 1f);
            float v = MathHelper.clamp(1f - (scr.y + sy) / sh, 0f, 1f);
            GuiRender.vertexTex(vc, pose, x, y, u, v, cols[i]);
        }
    }

    /**
     * Frosted glass inside the given quads: {@code taps} shifted copies of the frame on a golden-angle
     * spiral of radius {@code blur} px, each corner's alpha scaled by {@code alpha[i*4 + corner]} (0..1).
     *
     * @return false when there is no frame snapshot (nothing drawn)
     */
    public static boolean blur(DrawContext context, java.util.List<float[]> quads, java.util.List<float[]> offsets,
                               float[] alpha, float blur, int taps) {
        ScreenCapture.capture();
        if (!ScreenCapture.available() || quads.isEmpty()) return false;
        Matrix3x2f pose = GuiRender.pose(context);
        float sw = context.getScaledWindowWidth(), sh = context.getScaledWindowHeight();
        double golden = Math.PI * (3 - Math.sqrt(5));
        for (int k = 0; k < taps; k++) {
            double d = blur * Math.pow((k + 0.5) / taps, 0.7), a = k * golden;
            float ox = (float) (Math.cos(a) * d), oy = (float) (Math.sin(a) * d);
            float layer = 1f / (k + 1);
            GuiRender.texturedView(context, GuiRender.GUI_TEXTURED_TRANSLUCENT, ScreenCapture.view(), ScreenCapture.sampler(), Render2DEngine.currentClip(), vc -> {
                for (int q = 0; q < quads.size(); q++) {
                    texQuad(vc, pose, sw, sh, quads.get(q), offsets == null ? null : offsets.get(q), ox, oy,
                            white(alpha[q * 4] * layer), white(alpha[q * 4 + 1] * layer), white(alpha[q * 4 + 2] * layer), white(alpha[q * 4 + 3] * layer));
                }
            });
        }
        return true;
    }

    public static int white(float a) {
        return (Math.round(MathHelper.clamp(a, 0f, 1f) * 255) << 24) | 0xFFFFFF;
    }

    public static int withAlpha(int rgb, float a) {
        return (Math.round(MathHelper.clamp(a, 0f, 1f) * 255) << 24) | (rgb & 0xFFFFFF);
    }
}
