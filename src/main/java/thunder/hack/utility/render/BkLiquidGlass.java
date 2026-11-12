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
import org.joml.Vector2f;

import java.awt.Color;

/**
 * BKClient's liquid glass on 1.21.11 (shader logic: BKClient, MIT, bkubiakdev).
 *
 * <p>One quad per panel through our own GUI pipeline ({@code bk_liquidglass.fsh}), sampling this frame's
 * {@link ScreenCapture}: blurred backdrop, refracted through the panel's rounded edge, a fresnel rim and the
 * diagonal water glare - the panel BK draws behind every HUD element, music card and menu button.
 *
 * <p>{@link #panel} is BK's {@code drawClientRect}: the glass plus its 50% background veil. {@link #glass} is the
 * bare glass ({@code drawLiquidGlass}) for things that add their own tint, like the menu music card.
 */
public final class BkLiquidGlass {
    private BkLiquidGlass() {
    }

    /** BK's {@code Interface.glassDistortion()} at render quality Best. */
    public static final float DISTORTION = 0.3f;
    /** BK's dark theme background colour, laid over the glass at 50% (0.8 - 0.3 * glass). */
    public static final Color BACKGROUND = new Color(12, 12, 12);

    private static final RenderPipeline PIPELINE = build();

    private static RenderPipeline build() {
        RenderPipeline base = RenderPipelines.GUI_TEXTURED;
        RenderPipeline.Builder b = RenderPipeline.builder()
                .withLocation(Identifier.of("gombal", "pipeline/bk_liquidglass"))
                .withVertexShader(base.getVertexShader())
                .withFragmentShader(Identifier.of("gombal", "core/bk_liquidglass"))
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

    /** BK's HUD panel: liquid glass, then the theme background over it at half strength. */
    public static void panel(DrawContext ctx, float x, float y, float w, float h, float radius, float alpha) {
        if (!glass(ctx, x, y, w, h, radius, DISTORTION, alpha))
            Render2DEngine.drawRound(ctx, x, y, w, h, radius, new Color(18, 18, 22, Math.round(200 * alpha)));
        Render2DEngine.drawRound(ctx, x, y, w, h, radius,
                new Color(BACKGROUND.getRed(), BACKGROUND.getGreen(), BACKGROUND.getBlue(), Math.round(255 * 0.5f * alpha)));
    }

    /**
     * Bare liquid glass.
     *
     * @return false when there is no frame snapshot to look through (caller should draw a plain fallback)
     */
    public static boolean glass(DrawContext ctx, float x, float y, float w, float h, float radius, float distortion, float alpha) {
        ScreenCapture.capture();
        if (!ScreenCapture.available() || w <= 0.5f || h <= 0.5f || alpha <= 0.003f) return false;

        Matrix3x2f pose = GuiRender.pose(ctx);
        // the shader works on the panel as it lands on screen, so a scaled HUD keeps its edge and glare attached
        Vector2f tl = pose.transformPosition(new Vector2f(x, y));
        Vector2f tr = pose.transformPosition(new Vector2f(x + w, y));
        Vector2f bl = pose.transformPosition(new Vector2f(x, y + h));
        float rw = Math.max(0.001f, tl.distance(tr)), rh = Math.max(0.001f, tl.distance(bl));
        float scale = Math.min(rw / w, rh / h);

        float guiScale = (float) MinecraftClient.getInstance().getWindow().getScaleFactor();
        int r = MathHelper.clamp(Math.round(radius * scale / 32f * 255f), 0, 255);
        int g = MathHelper.clamp(Math.round(distortion / 0.5f * 255f), 0, 255);
        int b = MathHelper.clamp(Math.round(guiScale / 8f * 255f), 0, 255);
        int a = MathHelper.clamp(Math.round(alpha * 255f), 0, 255);
        int argb = a << 24 | r << 16 | g << 8 | b;

        float pw = 4f * Math.round(rw * 4f), ph = 4f * Math.round(rh * 4f);
        // the local corner (0..1) rides in the fraction; 1.0 itself would carry over, so the far edge stops just short
        final float one = 0.9999f;
        GuiRender.texturedView(ctx, PIPELINE, ScreenCapture.view(), ScreenCapture.sampler(), Render2DEngine.currentClip(), vc -> {
            GuiRender.vertexTex(vc, pose, x, y, pw, ph, argb);
            GuiRender.vertexTex(vc, pose, x, y + h, pw, ph + one, argb);
            GuiRender.vertexTex(vc, pose, x + w, y + h, pw + one, ph + one, argb);
            GuiRender.vertexTex(vc, pose, x + w, y, pw + one, ph, argb);
        });
        return true;
    }
}
