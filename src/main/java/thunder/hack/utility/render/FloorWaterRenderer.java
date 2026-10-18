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
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;
import org.lwjgl.system.MemoryStack;

import java.awt.Color;
import java.util.OptionalDouble;
import java.util.OptionalInt;

/**
 * FloorWeather water: one full-screen pass of {@code core/floor_water.fsh}. It needs the world depth (to find the
 * floor and to march reflections against) and a copy of the frame's colour (sampling the framebuffer it draws
 * into is undefined), so it runs from the 3D hook, after the terrain and before the hand.
 */
public final class FloorWaterRenderer {
    private FloorWaterRenderer() {
    }

    private static final RenderPipeline PIPELINE = RenderPipeline.builder()
            .withLocation(Identifier.of("gombal", "pipeline/floor_water"))
            .withVertexShader("core/screenquad")
            .withFragmentShader(Identifier.of("gombal", "core/floor_water"))
            .withSampler("DepthSampler")
            .withSampler("SceneSampler")
            .withUniform("FloorWater", UniformType.UNIFORM_BUFFER)
            .withBlend(BlendFunction.TRANSLUCENT)
            .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
            .withDepthWrite(false)
            .withVertexFormat(VertexFormats.EMPTY, VertexFormat.DrawMode.TRIANGLES)
            .build();

    private static final int UBO_SIZE = 64 * 2 + 16 * 5;

    private static GpuSampler depthSampler, sceneSampler;
    private static GpuTexture scene;
    private static GpuTextureView sceneView;
    private static int sceneW, sceneH;
    private static boolean checked, broken;

    public static boolean isBroken() {
        return broken;
    }

    /**
     * @param feet         the player's feet in world coordinates
     * @param radius       patch radius in blocks
     * @param time         wave phase (seconds * speed)
     * @param water        water colour (what deep water absorbs towards)
     * @param depth        0..1 how much of the water's own colour shows
     * @param waveStrength 0..2 wave slope
     * @param waveScale    wave frequency multiplier
     * @param reflectivity 0..2 Fresnel multiplier
     * @param opacity      0..1 overall strength
     * @param weather      0 water, 1 rain, 2 snow, 3 ice
     * @param rainDensity  0..1 share of ripple cells that get a drop each cycle (Rain only)
     */
    public static void render(Vec3d feet, float radius, float time, Color water, float depth, float waveStrength,
                              float waveScale, float reflectivity, float opacity, int weather, float rainDensity) {
        if (broken) return;
        try {
            var device = RenderSystem.getDevice();
            if (!checked) {
                checked = true;
                if (!device.precompilePipeline(PIPELINE).isValid())
                    throw new IllegalStateException("floor_water.fsh nie skompilowal sie");
            }
            if (depthSampler == null) {
                depthSampler = device.createSampler(AddressMode.CLAMP_TO_EDGE, AddressMode.CLAMP_TO_EDGE,
                        FilterMode.NEAREST, FilterMode.NEAREST, 1, OptionalDouble.empty());
                sceneSampler = device.createSampler(AddressMode.CLAMP_TO_EDGE, AddressMode.CLAMP_TO_EDGE,
                        FilterMode.LINEAR, FilterMode.LINEAR, 1, OptionalDouble.empty());
            }

            MinecraftClient mc = MinecraftClient.getInstance();
            Framebuffer fb = mc.getFramebuffer();
            if (fb.getDepthAttachmentView() == null || fb.getColorAttachment() == null) return;

            // the frame as it is now (terrain, entities, sky), for the refraction and the reflections
            GpuTexture src = fb.getColorAttachment();
            int w = src.getWidth(0), h = src.getHeight(0);
            if (scene == null || sceneW != w || sceneH != h) {
                if (scene != null) scene.close();
                scene = device.createTexture("gombal_floor_water_scene",
                        GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING, TextureFormat.RGBA8, w, h, 1, 1);
                sceneView = device.createTextureView(scene);
                sceneW = w;
                sceneH = h;
            }
            device.createCommandEncoder().copyTextureToTexture(src, scene, 0, 0, 0, 0, 0, w, h);

            Vec3d cam = mc.gameRenderer.getCamera().getCameraPos();
            Matrix4f viewProj = new Matrix4f(Render3DEngine.lastProjMat).mul(Render3DEngine.lastWorldSpaceMatrix);
            Matrix4f inv = new Matrix4f(viewProj).invert();
            // x/z wrapped so the float keeps its precision far from spawn; the pattern jumps once per 4096 blocks
            float camX = (float) (cam.x - Math.floor(cam.x / 4096.0) * 4096.0);
            float camZ = (float) (cam.z - Math.floor(cam.z / 4096.0) * 4096.0);

            GpuBuffer ubo;
            try (MemoryStack stack = MemoryStack.stackPush()) {
                var buf = Std140Builder.onStack(stack, UBO_SIZE)
                        .putMat4f(inv)
                        .putMat4f(viewProj)
                        .putVec4((float) (feet.x - cam.x), (float) (feet.y - cam.y), (float) (feet.z - cam.z), radius)
                        .putVec4(camX, (float) cam.y, camZ, time)
                        .putVec4(water.getRed() / 255f, water.getGreen() / 255f, water.getBlue() / 255f, depth)
                        .putVec4(waveStrength, waveScale, reflectivity, opacity)
                        .putVec4(weather, rainDensity, 0f, 0f)
                        .get();
                ubo = device.createBuffer(() -> "gombal floor water config", GpuBuffer.USAGE_UNIFORM, buf);
            }

            try (RenderPass pass = device.createCommandEncoder()
                    .createRenderPass(() -> "gombal floor water", fb.getColorAttachmentView(), OptionalInt.empty())) {
                pass.setPipeline(PIPELINE);
                pass.bindTexture("DepthSampler", fb.getDepthAttachmentView(), depthSampler);
                pass.bindTexture("SceneSampler", sceneView, sceneSampler);
                pass.setUniform("FloorWater", ubo);
                pass.draw(0, 3);
            } finally {
                ubo.close();
            }
        } catch (Throwable t) {
            broken = true;
            thunder.hack.ThunderHack.LOGGER.error("[GlobalDLC] FloorWeather water shader disabled: {}", t.toString());
        }
    }
}
