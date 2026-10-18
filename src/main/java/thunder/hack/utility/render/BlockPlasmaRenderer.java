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
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;
import org.lwjgl.system.MemoryStack;

import java.awt.Color;
import java.util.OptionalDouble;
import java.util.OptionalInt;

/**
 * BlockOverlay plasma: one full-screen pass of {@code core/block_plasma.fsh} that ray-casts the highlighted block's
 * box per pixel, so the plasma lies exactly on its faces (between the outline lines) with no mesh at all. Same
 * pipeline setup as {@link CustomSkyRenderer}; it must run from the 3D hook while the world depth is still there.
 */
public final class BlockPlasmaRenderer {
    private BlockPlasmaRenderer() {
    }

    private static final RenderPipeline PIPELINE = RenderPipeline.builder()
            .withLocation(Identifier.of("gombal", "pipeline/block_plasma"))
            .withVertexShader("core/screenquad")
            .withFragmentShader(Identifier.of("gombal", "core/block_plasma"))
            .withSampler("DepthSampler")
            .withUniform("BlockPlasma", UniformType.UNIFORM_BUFFER)
            .withBlend(BlendFunction.TRANSLUCENT)
            .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
            .withDepthWrite(false)
            .withVertexFormat(VertexFormats.EMPTY, VertexFormat.DrawMode.TRIANGLES)
            .build();

    private static final int UBO_SIZE = 64 + 16 * 6;

    /** Which pattern block_plasma.fsh paints: 0 = Veins (the original), 1..11 see patternColor there. */
    public static int pattern;

    private static GpuSampler sampler;
    private static boolean checked, broken;

    public static boolean isBroken() {
        return broken;
    }

    /**
     * @param box       the block box in world coordinates
     * @param time      animation phase (seconds * speed)
     * @param alpha     0..1 opacity
     * @param edgeGlow  extra light towards the face edges
     */
    public static void render(Box box, Color c1, Color c2, float alpha, float scale, float time, float intensity,
                              float edgeGlow, boolean throughWalls) {
        render(box, c1, c2, alpha, scale, time, intensity, edgeGlow, 0f, 0f, throughWalls);
    }

    /** Same, plus the outline drawn in the same pass: {@code lineWidth} in pixels, {@code lineAlpha} 0..1. */
    public static void render(Box box, Color c1, Color c2, float alpha, float scale, float time, float intensity,
                              float edgeGlow, float lineWidth, float lineAlpha, boolean throughWalls) {
        if (broken) return;
        try {
            var device = RenderSystem.getDevice();
            if (!checked) {
                checked = true;
                if (!device.precompilePipeline(PIPELINE).isValid())
                    throw new IllegalStateException("block_plasma.fsh nie skompilowal sie");
            }
            if (sampler == null)
                sampler = device.createSampler(AddressMode.CLAMP_TO_EDGE, AddressMode.CLAMP_TO_EDGE,
                        FilterMode.NEAREST, FilterMode.NEAREST, 1, OptionalDouble.empty());

            Framebuffer fb = MinecraftClient.getInstance().getFramebuffer();
            if (fb.getDepthAttachmentView() == null) return;

            Vec3d cam = MinecraftClient.getInstance().gameRenderer.getCamera().getCameraPos();
            Matrix4f inv = new Matrix4f(Render3DEngine.lastProjMat).mul(Render3DEngine.lastWorldSpaceMatrix).invert();

            GpuBuffer ubo;
            try (MemoryStack stack = MemoryStack.stackPush()) {
                var buf = Std140Builder.onStack(stack, UBO_SIZE)
                        .putMat4f(inv)
                        .putVec4((float) (box.minX - cam.x), (float) (box.minY - cam.y), (float) (box.minZ - cam.z), throughWalls ? 1f : 0f)
                        .putVec4((float) (box.maxX - cam.x), (float) (box.maxY - cam.y), (float) (box.maxZ - cam.z), time)
                        .putVec4(c1.getRed() / 255f, c1.getGreen() / 255f, c1.getBlue() / 255f, alpha)
                        .putVec4(c2.getRed() / 255f, c2.getGreen() / 255f, c2.getBlue() / 255f, scale)
                        .putVec4(edgeGlow, lineWidth, lineAlpha, intensity)
                        .putVec4(pattern, 0f, 0f, 0f)
                        .get();
                ubo = device.createBuffer(() -> "gombal block plasma config", GpuBuffer.USAGE_UNIFORM, buf);
            }

            try (RenderPass pass = device.createCommandEncoder()
                    .createRenderPass(() -> "gombal block plasma", fb.getColorAttachmentView(), OptionalInt.empty())) {
                pass.setPipeline(PIPELINE);
                pass.bindTexture("DepthSampler", fb.getDepthAttachmentView(), sampler);
                pass.setUniform("BlockPlasma", ubo);
                pass.draw(0, 3);
            } finally {
                ubo.close();
            }
        } catch (Throwable t) {
            broken = true;
            thunder.hack.ThunderHack.LOGGER.error("[GlobalDLC] BlockOverlay plasma disabled: {}", t.toString());
        }
    }
}
