package thunder.hack.utility.render;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.DepthTestFunction;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.AddressMode;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.Framebuffer;
import net.minecraft.client.gl.GpuSampler;
import net.minecraft.client.gl.UniformType;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.util.Identifier;
import org.joml.Matrix4f;
import org.lwjgl.system.MemoryStack;

import java.awt.Color;
import java.util.OptionalDouble;
import java.util.OptionalInt;

/**
 * GPU pass of the Custom Sky: one full-screen triangle ({@code core/screenquad}, {@code draw(0, 3)} like the
 * vanilla post passes) running {@code core/custom_sky.fsh} over the main framebuffer.
 *
 * <p>It runs from the 3D hook - world finished, depth still intact - and the shader discards every pixel the
 * world wrote depth to, so it only ever replaces the sky. Blocks, entities, clouds, the hand and the HUD stay
 * as they are, and with the module off nothing is drawn at all: the vanilla sky is simply what is left.
 *
 * <p>The ray direction comes from the inverse of the world projection times the camera rotation, which is why
 * the pattern holds still in the world while you turn - it is the sky, not a picture glued to the screen.
 *
 * <p>The uniform block is made with its data each frame (176 bytes) and freed right after the pass: writing
 * into a long-lived buffer on this backend left the shader reading zeros (see ItemOutlineRenderer).
 */
public final class CustomSkyRenderer {
    private CustomSkyRenderer() {
    }

    private static final RenderPipeline PIPELINE = RenderPipeline.builder()
            .withLocation(Identifier.of("gombal", "pipeline/custom_sky"))
            .withVertexShader("core/screenquad")
            .withFragmentShader(Identifier.of("gombal", "core/custom_sky"))
            .withSampler("DepthSampler")
            .withUniform("SkyConfig", UniformType.UNIFORM_BUFFER)
            .withBlend(BlendFunction.TRANSLUCENT)
            .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
            .withDepthWrite(false)
            .withVertexFormat(VertexFormats.EMPTY, VertexFormat.DrawMode.TRIANGLES)
            .build();

    private static final int UBO_SIZE = 64 + 16 * 7;

    private static GpuSampler sampler;
    private static boolean checked, broken;
    private static String error;

    /** Everything the shader needs for one frame. Colours are 0..1 RGB. */
    public record Params(int style, int colorMode, boolean highQuality, boolean swirl,
                         float phase, float scale, float complexity, float intensity,
                         float distortion, float glow, float detail, float opacity,
                         float flowX, float flowY, float flowZ, float stars,
                         Color primary, Color secondary, Color accent) {
    }

    public static boolean isBroken() {
        return broken;
    }

    public static String error() {
        return error;
    }

    /** Lets the module retry after a resource reload (a fixed shader pack / file). */
    public static void resetFailure() {
        broken = false;
        checked = false;
        error = null;
    }

    /**
     * @param proj the world projection used for the terrain this frame
     * @param view the camera rotation (no translation - the sky is infinitely far)
     */
    public static void render(Params p, Matrix4f proj, Matrix4f view) {
        if (broken) return;
        try {
            var device = RenderSystem.getDevice();
            if (!checked) {
                checked = true;
                if (!device.precompilePipeline(PIPELINE).isValid())
                    throw new IllegalStateException("custom_sky.fsh nie skompilowal sie (szczegoly w latest.log)");
            }
            if (sampler == null)
                sampler = device.createSampler(AddressMode.CLAMP_TO_EDGE, AddressMode.CLAMP_TO_EDGE,
                        FilterMode.NEAREST, FilterMode.NEAREST, 1, OptionalDouble.empty());

            Framebuffer fb = MinecraftClient.getInstance().getFramebuffer();
            if (fb.getDepthAttachmentView() == null) return;

            Matrix4f inv = new Matrix4f(proj).mul(view).invert();

            GpuBuffer ubo;
            try (MemoryStack stack = MemoryStack.stackPush()) {
                var buf = Std140Builder.onStack(stack, UBO_SIZE)
                        .putMat4f(inv)
                        .putVec4(p.primary.getRed() / 255f, p.primary.getGreen() / 255f, p.primary.getBlue() / 255f, 1f)
                        .putVec4(p.secondary.getRed() / 255f, p.secondary.getGreen() / 255f, p.secondary.getBlue() / 255f, 1f)
                        .putVec4(p.accent.getRed() / 255f, p.accent.getGreen() / 255f, p.accent.getBlue() / 255f, 1f)
                        .putVec4(p.phase, p.scale, p.complexity, p.intensity)
                        .putVec4(p.distortion, p.glow, p.detail, p.opacity)
                        .putVec4(p.style, p.colorMode, p.highQuality ? 1f : 0f, p.swirl ? 1f : 0f)
                        .putVec4(p.flowX, p.flowY, p.flowZ, p.stars)
                        .get();
                ubo = device.createBuffer(() -> "gombal custom sky config", GpuBuffer.USAGE_UNIFORM, buf);
            }

            try (RenderPass pass = device.createCommandEncoder()
                    .createRenderPass(() -> "gombal custom sky", fb.getColorAttachmentView(), OptionalInt.empty())) {
                pass.setPipeline(PIPELINE);
                pass.bindTexture("DepthSampler", fb.getDepthAttachmentView(), sampler);
                pass.setUniform("SkyConfig", ubo);
                pass.draw(0, 3);
            } finally {
                ubo.close();
            }
        } catch (Throwable t) {
            broken = true;
            error = t.getMessage() != null ? t.getMessage() : t.toString();
            thunder.hack.ThunderHack.LOGGER.error("[GlobalDLC] Custom Sky shader disabled: {}", t.toString());
        }
    }
}
