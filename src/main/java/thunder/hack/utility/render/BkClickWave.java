package thunder.hack.utility.render;

import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gl.UniformType;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;
import org.joml.Matrix3x2f;

/**
 * A ring of liquid glass ({@code bk_click_wave.fsh}): the frame snapshot refracted through a smooth band of
 * glass, with BK's chromatic split and glare. Built like {@link BkLiquidGlass}, one quad per ring.
 */
public final class BkClickWave {
    private BkClickWave() {
    }

    private static final RenderPipeline PIPELINE = build();

    private static RenderPipeline build() {
        RenderPipeline base = RenderPipelines.GUI_TEXTURED;
        RenderPipeline.Builder b = RenderPipeline.builder()
                .withLocation(Identifier.of("gombal", "pipeline/bk_click_wave"))
                .withVertexShader(base.getVertexShader())
                .withFragmentShader(Identifier.of("gombal", "core/bk_click_wave"))
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

    /**
     * One ring centred on (cx, cy).
     *
     * @param radius   radius of the crest, GUI pixels
     * @param band     half width of the glass band, GUI pixels (at most 64)
     * @param strength how hard the band bends the picture behind it, 0..1
     * @param alpha    opacity of the whole ring
     * @return false when there is no frame snapshot to look through
     */
    public static boolean ring(DrawContext ctx, float cx, float cy, float radius, float band, float strength, float alpha) {
        ScreenCapture.capture();
        if (!ScreenCapture.available() || alpha <= 0.003f || radius + band <= 1f) return false;
        band = MathHelper.clamp(band, 1f, 64f);
        float half = radius + band;
        float x = cx - half, y = cy - half, s = half * 2f;

        Matrix3x2f pose = GuiRender.pose(ctx);
        float guiScale = (float) MinecraftClient.getInstance().getWindow().getScaleFactor();
        int r = MathHelper.clamp(Math.round(band / 64f * 255f), 0, 255);
        int g = MathHelper.clamp(Math.round(strength * 255f), 0, 255);
        int b = MathHelper.clamp(Math.round(guiScale / 8f * 255f), 0, 255);
        int a = MathHelper.clamp(Math.round(alpha * 255f), 0, 255);
        int argb = a << 24 | r << 16 | g << 8 | b;

        float p = 4f * Math.round(s * 4f);
        final float one = 0.9999f;
        GuiRender.texturedView(ctx, PIPELINE, ScreenCapture.view(), ScreenCapture.sampler(), Render2DEngine.currentClip(), vc -> {
            GuiRender.vertexTex(vc, pose, x, y, p, p, argb);
            GuiRender.vertexTex(vc, pose, x, y + s, p, p + one, argb);
            GuiRender.vertexTex(vc, pose, x + s, y + s, p + one, p + one, argb);
            GuiRender.vertexTex(vc, pose, x + s, y, p + one, p, argb);
        });
        return true;
    }
}
