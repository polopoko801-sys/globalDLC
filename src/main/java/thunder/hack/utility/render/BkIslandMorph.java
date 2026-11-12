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
 * BKClient's {@code DrawUtility.drawIslandMorph} (MIT, bkubiakdev) on 1.21.11: the Dynamic Island capsule with
 * the logo bubble rolling out of its right end, both one liquid-glass surface ({@code bk_islandmorph.fsh}).
 */
public final class BkIslandMorph {
    private BkIslandMorph() {
    }

    private static final RenderPipeline PIPELINE = build();

    private static RenderPipeline build() {
        RenderPipeline base = RenderPipelines.GUI_TEXTURED;
        RenderPipeline.Builder b = RenderPipeline.builder()
                .withLocation(Identifier.of("gombal", "pipeline/bk_islandmorph"))
                .withVertexShader(base.getVertexShader())
                .withFragmentShader(Identifier.of("gombal", "core/bk_islandmorph"))
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
     * @param x, y           the capsule's top-left
     * @param pillWidth      capsule width
     * @param pillHeight     capsule height
     * @param bubbleProgress 0 = bubble tucked inside the capsule, 1 = fully rolled out (BK's bubbleAnimation)
     */
    public static void draw(DrawContext ctx, float x, float y, float pillWidth, float pillHeight, float bubbleProgress, float alpha) {
        float p = MathHelper.clamp(bubbleProgress, 0f, 1f);
        float width = pillWidth + 19f * p + 10f;
        float height = pillHeight + 10f;
        float qx = x - 5f, qy = y - 5f;

        ScreenCapture.capture();
        if (!ScreenCapture.available() || alpha <= 0.003f) {
            Render2DEngine.drawRound(ctx, x, y, pillWidth, pillHeight, Math.min(7f, pillHeight / 2f),
                    new Color(12, 12, 12, Math.round(220 * alpha)));
            return;
        }

        Matrix3x2f pose = GuiRender.pose(ctx);
        Vector2f tl = pose.transformPosition(new Vector2f(qx, qy));
        Vector2f tr = pose.transformPosition(new Vector2f(qx + width, qy));
        Vector2f bl = pose.transformPosition(new Vector2f(qx, qy + height));
        float rw = Math.max(0.001f, tl.distance(tr)), rh = Math.max(0.001f, tl.distance(bl));
        float scale = Math.min(rw / width, rh / height);

        float guiScale = (float) MinecraftClient.getInstance().getWindow().getScaleFactor();
        int r = MathHelper.clamp(Math.round(p * 255f), 0, 255);
        int g = MathHelper.clamp(Math.round(scale / 4f * 255f), 1, 255);
        int b = MathHelper.clamp(Math.round(guiScale / 8f * 255f), 0, 255);
        int a = MathHelper.clamp(Math.round(alpha * 255f), 0, 255);
        int argb = a << 24 | r << 16 | g << 8 | b;

        float pw = 4f * Math.round(rw * 4f), ph = 4f * Math.round(rh * 4f);
        final float one = 0.9999f;
        GuiRender.texturedView(ctx, PIPELINE, ScreenCapture.view(), ScreenCapture.sampler(), Render2DEngine.currentClip(), vc -> {
            GuiRender.vertexTex(vc, pose, qx, qy, pw, ph, argb);
            GuiRender.vertexTex(vc, pose, qx, qy + height, pw, ph + one, argb);
            GuiRender.vertexTex(vc, pose, qx + width, qy + height, pw + one, ph + one, argb);
            GuiRender.vertexTex(vc, pose, qx + width, qy, pw + one, ph, argb);
        });
    }
}
