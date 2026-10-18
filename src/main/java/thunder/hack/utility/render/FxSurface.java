package thunder.hack.utility.render;

import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gl.UniformType;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;
import org.joml.Matrix3x2f;

import java.util.List;
import java.util.function.Consumer;

/**
 * Per-pixel water and frosted glass for projected world shapes (Trails, JumpCircle, ChinaHat).
 *
 * <p>Two GUI pipelines with our own fragment shaders ({@code fx_water.fsh}, {@code fx_glass.fsh}) sampling the
 * frame snapshot ({@link ScreenCapture}) at the pixel's own screen position - the vertex shader is vanilla's
 * position_tex_color. The GUI renderer binds the default uniforms, so the shaders get {@code ScreenSize} and
 * {@code GameTime} from {@code Globals}; everything else is packed into the vertex (see the shader headers):
 * u = along the shape (negative = ripple ring), v = 0..1 across + the tint in its integer part, colour = tint
 * with the opacity in alpha. Culling is off, so the shape shows from both sides.
 */
public final class FxSurface {
    private FxSurface() {
    }

    public enum Kind {Water, Glass}

    private static final RenderPipeline WATER = build("fx_water");
    private static final RenderPipeline GLASS = build("fx_glass");

    private static RenderPipeline build(String shader) {
        RenderPipeline base = RenderPipelines.GUI_TEXTURED;
        RenderPipeline.Builder b = RenderPipeline.builder()
                .withLocation(Identifier.of("gombal", "pipeline/" + shader))
                .withVertexShader(base.getVertexShader())
                .withFragmentShader(Identifier.of("gombal", "core/" + shader))
                .withVertexFormat(base.getVertexFormat(), base.getVertexFormatMode())
                .withDepthTestFunction(base.getDepthTestFunction())
                .withCull(false)
                .withDepthWrite(false)
                .withBlend(BlendFunction.TRANSLUCENT);
        base.getSamplers().forEach(b::withSampler);
        boolean globals = false;
        for (RenderPipeline.UniformDescription u : base.getUniforms()) {
            if (u.name().equals("Globals")) globals = true;
            if (u.textureFormat() != null) b.withUniform(u.name(), u.type(), u.textureFormat());
            else b.withUniform(u.name(), u.type());
        }
        if (!globals) b.withUniform("Globals", UniformType.UNIFORM_BUFFER);
        return b.build();
    }

    /** One vertex of a shape: screen position, u along the shape, v (0..1) across it. */
    public record V(float x, float y, float u, float v) {
    }

    /**
     * Draws quads (4 vertices each) as water or glass.
     *
     * @param tint    0..1 how strongly the colour tints the surface
     * @param rgb     tint colour
     * @param opacity per quad corner 0..1 (same length as the vertex list), or null for 1
     * @param ring    water only: ripple-ring shading (one crest across v) instead of open-water waves
     * @return false when there is no frame snapshot to look through
     */
    public static boolean draw(DrawContext ctx, Kind kind, List<V> quads, float[] opacity, int rgb, float tint, boolean ring) {
        ScreenCapture.capture();
        if (!ScreenCapture.available() || quads.isEmpty()) return false;
        Matrix3x2f pose = GuiRender.pose(ctx);
        float packed = 2f * Math.round(MathHelper.clamp(tint, 0f, 1f) * 50f);
        Consumer<VertexConsumer> builder = vc -> {
            for (int i = 0; i < quads.size(); i++) {
                V q = quads.get(i);
                float a = opacity == null ? 1f : MathHelper.clamp(opacity[i], 0f, 1f);
                float u = ring ? -(Math.abs(q.u()) + 0.001f) : Math.abs(q.u());
                GuiRender.vertexTex(vc, pose, q.x(), q.y(), u, MathHelper.clamp(q.v(), 0f, 1f) + packed,
                        (Math.round(a * 255) << 24) | (rgb & 0xFFFFFF));
            }
        };
        GuiRender.texturedView(ctx, kind == Kind.Water ? WATER : GLASS, ScreenCapture.view(), ScreenCapture.sampler(),
                Render2DEngine.currentClip(), builder);
        return true;
    }
}
