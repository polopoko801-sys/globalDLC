package thunder.hack.utility.render;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.DepthTestFunction;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.AddressMode;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.textures.TextureFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.Framebuffer;
import net.minecraft.client.gl.GpuSampler;
import net.minecraft.client.gl.UniformType;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.util.Identifier;
import org.lwjgl.system.MemoryStack;

import java.util.OptionalDouble;
import java.util.OptionalInt;

/**
 * BKClient's world post-processing passes (JumpDistortPost, GlassVaporPost; MIT, bkubiakdev) on 1.21.11.
 *
 * <p>BK copied the frame, drew its shader into a second target and copied that back. Here one full-screen pass
 * reads a copy of the scene ({@code Scene}) and the live world depth ({@code DepthSampler}) and writes straight
 * into the main target. BK's float array goes up unchanged as the shader's std140 block, so the shaders are BK's.
 */
public final class BkWorldPost {
    private final RenderPipeline pipeline;
    private final String name, block;
    public final int floats;

    private GpuTexture scene;
    private GpuTextureView sceneView;
    private int sceneW, sceneH;
    private boolean checked, broken;

    private static GpuSampler depthSampler, sceneSampler;

    /**
     * @param name   shader {@code gombal:core/<name>.fsh} and pipeline id
     * @param block  the std140 uniform block's name in that shader
     * @param floats how many floats the block holds (a multiple of 4)
     */
    public BkWorldPost(String name, String block, int floats) {
        this.name = name;
        this.block = block;
        this.floats = floats;
        this.pipeline = RenderPipeline.builder()
                .withLocation(Identifier.of("gombal", "pipeline/" + name))
                .withVertexShader("core/screenquad")
                .withFragmentShader(Identifier.of("gombal", "core/" + name))
                .withSampler("Scene")
                .withSampler("DepthSampler")
                .withUniform(block, UniformType.UNIFORM_BUFFER)
                .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
                .withDepthWrite(false)
                .withVertexFormat(VertexFormats.EMPTY, VertexFormat.DrawMode.TRIANGLES)
                .build();
    }

    public boolean isBroken() {
        return broken;
    }

    public void apply(float[] data) {
        if (broken || data == null || data.length < floats) return;
        try {
            var device = RenderSystem.getDevice();
            if (!checked) {
                checked = true;
                if (!device.precompilePipeline(pipeline).isValid())
                    throw new IllegalStateException(name + ".fsh nie skompilowal sie");
            }
            if (depthSampler == null) {
                depthSampler = device.createSampler(AddressMode.CLAMP_TO_EDGE, AddressMode.CLAMP_TO_EDGE,
                        FilterMode.NEAREST, FilterMode.NEAREST, 1, OptionalDouble.empty());
                sceneSampler = device.createSampler(AddressMode.CLAMP_TO_EDGE, AddressMode.CLAMP_TO_EDGE,
                        FilterMode.LINEAR, FilterMode.LINEAR, 1, OptionalDouble.empty());
            }

            Framebuffer fb = MinecraftClient.getInstance().getFramebuffer();
            if (fb.getDepthAttachmentView() == null || fb.getColorAttachment() == null) return;

            GpuTexture src = fb.getColorAttachment();
            int w = src.getWidth(0), h = src.getHeight(0);
            if (scene == null || sceneW != w || sceneH != h) {
                if (scene != null) scene.close();
                scene = device.createTexture("gombal_" + name + "_scene",
                        GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING, TextureFormat.RGBA8, w, h, 1, 1);
                sceneView = device.createTextureView(scene);
                sceneW = w;
                sceneH = h;
            }
            device.createCommandEncoder().copyTextureToTexture(src, scene, 0, 0, 0, 0, 0, w, h);

            GpuBuffer ubo;
            try (MemoryStack stack = MemoryStack.stackPush()) {
                Std140Builder b = Std140Builder.onStack(stack, floats * 4);
                for (int i = 0; i < floats; i += 4) b.putVec4(data[i], data[i + 1], data[i + 2], data[i + 3]);
                ubo = device.createBuffer(() -> "gombal " + name, GpuBuffer.USAGE_UNIFORM, b.get());
            }

            try (RenderPass pass = device.createCommandEncoder()
                    .createRenderPass(() -> "gombal " + name, fb.getColorAttachmentView(), OptionalInt.empty())) {
                pass.setPipeline(pipeline);
                pass.bindTexture("Scene", sceneView, sceneSampler);
                pass.bindTexture("DepthSampler", fb.getDepthAttachmentView(), depthSampler);
                pass.setUniform(block, ubo);
                pass.draw(0, 3);
            } finally {
                ubo.close();
            }
        } catch (Throwable t) {
            broken = true;
            thunder.hack.ThunderHack.LOGGER.error("[GlobalDLC] {} shader disabled: {}", name, t.toString());
        }
    }
}
