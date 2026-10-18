package thunder.hack.utility.render;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.DepthTestFunction;
import com.mojang.blaze3d.systems.CommandEncoder;
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

import java.awt.Color;
import java.util.OptionalDouble;
import java.util.OptionalInt;

/**
 * Real shader outline around the first-person hand + held item (Rockstar's outline.frag, ported to 1.21.11).
 *
 * <p>Rockstar rendered the hand into its own framebuffer and ran outline.frag over it. 1.21.11 draws the hand
 * deferred through the command queue, so redirecting it is not practical - instead the mask is taken by
 * difference: a copy of the frame right before renderHand ({@link #captureBefore}) and one right after, when the
 * HUD starts ({@link #render}). Pixels that changed are the hand. Two full-screen passes ({@code core/screenquad}
 * vertex shader, {@code draw(0, 3)} like vanilla post effects) turn that into an exact-distance outline:
 * horizontal pass into an offscreen texture, vertical pass blended onto the main framebuffer.
 *
 * <p>Custom fragment shaders DO work on 1.21.11 as long as they go through a RenderPipeline (shader files in
 * assets/gombal/shaders/core, uniforms as a std140 block) - only the old JSON core-shader system is gone.
 */
public final class ItemOutlineRenderer {
    private ItemOutlineRenderer() {
    }

    private static final RenderPipeline H_PASS = RenderPipeline.builder()
            .withLocation(Identifier.of("gombal", "pipeline/item_outline_h"))
            .withVertexShader("core/screenquad")
            .withFragmentShader(Identifier.of("gombal", "core/item_outline_h"))
            .withSampler("BeforeSampler")
            .withSampler("AfterSampler")
            .withUniform("OutlineConfig", UniformType.UNIFORM_BUFFER)
            .withoutBlend()
            .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
            .withDepthWrite(false)
            .withVertexFormat(VertexFormats.EMPTY, VertexFormat.DrawMode.TRIANGLES)
            .build();

    private static final RenderPipeline V_PASS = RenderPipeline.builder()
            .withLocation(Identifier.of("gombal", "pipeline/item_outline_v"))
            .withVertexShader("core/screenquad")
            .withFragmentShader(Identifier.of("gombal", "core/item_outline_v"))
            .withSampler("HSampler")
            .withUniform("OutlineConfig", UniformType.UNIFORM_BUFFER)
            .withBlend(BlendFunction.TRANSLUCENT)
            .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
            .withDepthWrite(false)
            .withVertexFormat(VertexFormats.EMPTY, VertexFormat.DrawMode.TRIANGLES)
            .build();

    /** The V pass again, but writing raw (unblended) into the mist texture the trail accumulates from. */
    private static final RenderPipeline V_RAW = RenderPipeline.builder()
            .withLocation(Identifier.of("gombal", "pipeline/item_outline_v_raw"))
            .withVertexShader("core/screenquad")
            .withFragmentShader(Identifier.of("gombal", "core/item_outline_v"))
            .withSampler("HSampler")
            .withUniform("OutlineConfig", UniformType.UNIFORM_BUFFER)
            .withoutBlend()
            .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
            .withDepthWrite(false)
            .withVertexFormat(VertexFormats.EMPTY, VertexFormat.DrawMode.TRIANGLES)
            .build();

    private static final RenderPipeline TRAIL_PASS = RenderPipeline.builder()
            .withLocation(Identifier.of("gombal", "pipeline/item_mist_trail"))
            .withVertexShader("core/screenquad")
            .withFragmentShader(Identifier.of("gombal", "core/item_mist_trail"))
            .withSampler("CurSampler")
            .withSampler("PrevSampler")
            .withSampler("CenCur")
            .withSampler("CenPrev")
            .withUniform("TrailConfig", UniformType.UNIFORM_BUFFER)
            .withoutBlend()
            .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
            .withDepthWrite(false)
            .withVertexFormat(VertexFormats.EMPTY, VertexFormat.DrawMode.TRIANGLES)
            .build();

    /** Where the fire is this frame, into a 1x1 texture - the trail sweeps along the move between frames. */
    private static final RenderPipeline CENTROID_PASS = RenderPipeline.builder()
            .withLocation(Identifier.of("gombal", "pipeline/item_mist_centroid"))
            .withVertexShader("core/screenquad")
            .withFragmentShader(Identifier.of("gombal", "core/item_mist_centroid"))
            .withSampler("MistSampler")
            .withoutBlend()
            .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
            .withDepthWrite(false)
            .withVertexFormat(VertexFormats.EMPTY, VertexFormat.DrawMode.TRIANGLES)
            .build();

    private static final RenderPipeline COMPOSITE_PASS = RenderPipeline.builder()
            .withLocation(Identifier.of("gombal", "pipeline/item_mist_composite"))
            .withVertexShader("core/screenquad")
            .withFragmentShader(Identifier.of("gombal", "core/item_mist_composite"))
            .withSampler("HistorySampler")
            .withSampler("CurSampler")
            .withBlend(new BlendFunction(com.mojang.blaze3d.platform.SourceFactor.ONE, com.mojang.blaze3d.platform.DestFactor.ONE_MINUS_SRC_ALPHA))
            .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
            .withDepthWrite(false)
            .withVertexFormat(VertexFormats.EMPTY, VertexFormat.DrawMode.TRIANGLES)
            .build();

    private static final int UBO_SIZE = 112; // 7 x vec4 (outline + plasma fill + mist)

    private static GpuTexture before, after, hTex;
    private static GpuTextureView beforeView, afterView, hView;
    /** Mist trail: this frame's raw mist, and two history buffers used ping-pong. */
    private static GpuTexture mistTex, histA, histB;
    private static GpuTextureView mistView, histAView, histBView;
    /** Fire centre of this frame and the last one (ping-pong, 1x1). */
    private static GpuTexture cenA, cenB;
    private static GpuTextureView cenAView, cenBView;
    private static boolean histFlip;
    private static long lastTrailNanos;
    private static GpuSampler sampler;
    private static int width, height;
    private static boolean haveBefore;
    private static boolean broken;

    /** Called from the 3D hook, right before renderHand: snapshot of the frame without the hand. */
    public static void captureBefore() {
        haveBefore = false;
        if (broken) return;
        try {
            GpuTexture src = fb().getColorAttachment();
            ensure(src.getWidth(0), src.getHeight(0));
            RenderSystem.getDevice().createCommandEncoder().copyTextureToTexture(src, before, 0, 0, 0, 0, 0, width, height);
            haveBefore = true;
        } catch (Throwable t) {
            fail(t);
        }
    }

    /**
     * Called while the HUD is being built (the frame now holds the hand, but no GUI yet): diff, outline, blend.
     *
     * @param radiusPx outline width in real pixels
     * @param fillAlpha 0..1 tint over the hand/item itself (0 = outline only)
     * @param rainbowPhase moving offset for the rainbow (seconds * speed)
     */
    public static void render(Color color, int radiusPx, boolean rainbow, float rainbowPhase, float saturation,
                              float rainbowStrength, float fillAlpha) {
        render(color, radiusPx, rainbow, rainbowPhase, saturation, rainbowStrength, fillAlpha,
                0, 0f, 1f, 1f, color, color);
    }

    /**
     * @param plasma      0 = flat colour, otherwise 1 + the plasma pattern index the hand/item is filled with;
     *                    {@code fillAlpha} is then the plasma opacity
     * @param plasmaTime  animation phase in seconds (already multiplied by the speed)
     * @param plasmaScale pattern size, plasmaIntensity brightness; color / col2 / col3 are the three gradient stops
     */
    public static void render(Color color, int radiusPx, boolean rainbow, float rainbowPhase, float saturation,
                              float rainbowStrength, float fillAlpha, int plasma, float plasmaTime,
                              float plasmaScale, float plasmaIntensity, Color col2, Color col3) {
        render(color, radiusPx, rainbow, rainbowPhase, saturation, rainbowStrength, fillAlpha, plasma, plasmaTime,
                plasmaScale, plasmaIntensity, col2, col3, false, 0f);
    }

    /**
     * @param mist         draw the soft outward glow (Mist style) instead of the hard outline; radiusPx is then
     *                     how far it reaches
     * @param mistStrength 0..1.5 brightness of the glow at the edge
     */
    public static void render(Color color, int radiusPx, boolean rainbow, float rainbowPhase, float saturation,
                              float rainbowStrength, float fillAlpha, int plasma, float plasmaTime,
                              float plasmaScale, float plasmaIntensity, Color col2, Color col3,
                              boolean mist, float mistStrength) {
        render(color, radiusPx, rainbow, rainbowPhase, saturation, rainbowStrength, fillAlpha, plasma, plasmaTime,
                plasmaScale, plasmaIntensity, col2, col3, mist, mistStrength, 0f);
    }

    /**
     * @param trailSeconds Mist only: how long the glow lingers where the item has been (0 = no trail). The mist
     *                     is drawn into its own texture, folded into a history that fades by this time constant,
     *                     and the history is what lands on the frame.
     */
    public static void render(Color color, int radiusPx, boolean rainbow, float rainbowPhase, float saturation,
                              float rainbowStrength, float fillAlpha, int plasma, float plasmaTime,
                              float plasmaScale, float plasmaIntensity, Color col2, Color col3,
                              boolean mist, float mistStrength, float trailSeconds) {
        if (broken || !haveBefore) return;
        haveBefore = false;
        try {
            Framebuffer fb = fb();
            GpuTexture src = fb.getColorAttachment();
            if (src.getWidth(0) != width || src.getHeight(0) != height) return; // resized mid-frame

            var device = RenderSystem.getDevice();
            CommandEncoder enc = device.createCommandEncoder();
            enc.copyTextureToTexture(src, after, 0, 0, 0, 0, 0, width, height);

            int r = Math.max(1, Math.min(mist ? 96 : 16, radiusPx));
            // past 16 px the passes sample every few pixels, so a wide mist costs no more than a 16 px outline
            int step = Math.max(1, r / 16);
            // Rockstar: strength = 1 / -(1000 * rainbowStrength)
            float strength = 1f / -(1000f * Math.max(0.01f, rainbowStrength));
            // a fresh uniform buffer created WITH its data (what vanilla post passes do) - writing into a
            // long-lived buffer with writeToBuffer left the shader reading zeros
            GpuBuffer ubo;
            try (MemoryStack stack = MemoryStack.stackPush()) {
                var buf = Std140Builder.onStack(stack, UBO_SIZE)
                        .putVec4(color.getRed() / 255f, color.getGreen() / 255f, color.getBlue() / 255f, color.getAlpha() / 255f)
                        .putVec4(r, rainbow ? 1f : 0f, rainbowPhase, saturation)
                        .putVec4(1f / width, 1f / height, fillAlpha, strength)
                        .putVec4(plasma, plasmaTime, plasmaScale, plasmaIntensity)
                        .putVec4(col2.getRed() / 255f, col2.getGreen() / 255f, col2.getBlue() / 255f, 1f)
                        .putVec4(col3.getRed() / 255f, col3.getGreen() / 255f, col3.getBlue() / 255f, 1f)
                        .putVec4(mist ? 1f : 0f, mistStrength, 1.6f, step)
                        .get();
                ubo = device.createBuffer(() -> "gombal item outline config", GpuBuffer.USAGE_UNIFORM, buf);
            }

            try (RenderPass pass = enc.createRenderPass(() -> "gombal item outline H", hView, OptionalInt.empty())) {
                pass.setPipeline(H_PASS);
                pass.bindTexture("BeforeSampler", beforeView, sampler);
                pass.bindTexture("AfterSampler", afterView, sampler);
                pass.setUniform("OutlineConfig", ubo);
                pass.draw(0, 3);
            }
            long now = System.nanoTime();
            float dt = lastTrailNanos == 0 ? 0.016f : Math.min(0.5f, (now - lastTrailNanos) / 1.0E9f);
            lastTrailNanos = now;
            if (mist && trailSeconds > 0.01f) {
                // mist -> raw texture; history = max(mist, history * decay); history -> frame
                try (RenderPass pass = enc.createRenderPass(() -> "gombal item mist raw", mistView, OptionalInt.of(0))) {
                    pass.setPipeline(V_RAW);
                    pass.bindTexture("HSampler", hView, sampler);
                    pass.setUniform("OutlineConfig", ubo);
                    pass.draw(0, 3);
                }
                // fades to 2% after trailSeconds, whatever the frame rate
                float decay = (float) Math.pow(0.02, dt / trailSeconds);
                GpuBuffer trailUbo;
                try (MemoryStack stack = MemoryStack.stackPush()) {
                    var buf = Std140Builder.onStack(stack, 16).putVec4(decay, 1f / width, 1f / height, (System.nanoTime() % 1_000_000_000_000L) / 1.0E9f).get();
                    trailUbo = device.createBuffer(() -> "gombal mist trail config", GpuBuffer.USAGE_UNIFORM, buf);
                }
                GpuTextureView read = histFlip ? histBView : histAView;
                GpuTextureView write = histFlip ? histAView : histBView;
                GpuTextureView cenNow = histFlip ? cenAView : cenBView;
                GpuTextureView cenLast = histFlip ? cenBView : cenAView;
                try (RenderPass pass = enc.createRenderPass(() -> "gombal item mist centroid", cenNow, OptionalInt.empty())) {
                    pass.setPipeline(CENTROID_PASS);
                    pass.bindTexture("MistSampler", mistView, sampler);
                    pass.draw(0, 3);
                }
                histFlip = !histFlip;
                try (RenderPass pass = enc.createRenderPass(() -> "gombal item mist trail", write, OptionalInt.empty())) {
                    pass.setPipeline(TRAIL_PASS);
                    pass.bindTexture("CurSampler", mistView, sampler);
                    pass.bindTexture("PrevSampler", read, sampler);
                    pass.bindTexture("CenCur", cenNow, sampler);
                    pass.bindTexture("CenPrev", cenLast, sampler);
                    pass.setUniform("TrailConfig", trailUbo);
                    pass.draw(0, 3);
                }
                try (RenderPass pass = enc.createRenderPass(() -> "gombal item mist composite", fb.getColorAttachmentView(), OptionalInt.empty())) {
                    pass.setPipeline(COMPOSITE_PASS);
                    pass.bindTexture("HistorySampler", write, sampler);
                    pass.bindTexture("CurSampler", mistView, sampler);
                    pass.draw(0, 3);
                }
                trailUbo.close();
            } else {
                try (RenderPass pass = enc.createRenderPass(() -> "gombal item outline V", fb.getColorAttachmentView(), OptionalInt.empty())) {
                    pass.setPipeline(V_PASS);
                    pass.bindTexture("HSampler", hView, sampler);
                    pass.setUniform("OutlineConfig", ubo);
                    pass.draw(0, 3);
                }
            }
            ubo.close();
        } catch (Throwable t) {
            fail(t);
        }
    }

    public static boolean isBroken() {
        return broken;
    }

    private static Framebuffer fb() {
        return MinecraftClient.getInstance().getFramebuffer();
    }

    private static void ensure(int w, int h) {
        if (before != null && w == width && h == height) return;
        close();
        var device = RenderSystem.getDevice();
        int copyTex = GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING;
        before = device.createTexture("gombal_outline_before", copyTex, TextureFormat.RGBA8, w, h, 1, 1);
        after = device.createTexture("gombal_outline_after", copyTex, TextureFormat.RGBA8, w, h, 1, 1);
        hTex = device.createTexture("gombal_outline_h", GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_TEXTURE_BINDING,
                TextureFormat.RGBA8, w, h, 1, 1);
        beforeView = device.createTextureView(before);
        afterView = device.createTextureView(after);
        hView = device.createTextureView(hTex);
        int target = GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_DST;
        mistTex = device.createTexture("gombal_mist", target, TextureFormat.RGBA8, w, h, 1, 1);
        histA = device.createTexture("gombal_mist_hist_a", target, TextureFormat.RGBA8, w, h, 1, 1);
        histB = device.createTexture("gombal_mist_hist_b", target, TextureFormat.RGBA8, w, h, 1, 1);
        mistView = device.createTextureView(mistTex);
        histAView = device.createTextureView(histA);
        histBView = device.createTextureView(histB);
        cenA = device.createTexture("gombal_mist_cen_a", target, TextureFormat.RGBA8, 1, 1, 1, 1);
        cenB = device.createTexture("gombal_mist_cen_b", target, TextureFormat.RGBA8, 1, 1, 1, 1);
        cenAView = device.createTextureView(cenA);
        cenBView = device.createTextureView(cenB);
        // new textures hold garbage until written; the history is read before it is written, so clear it
        var enc = device.createCommandEncoder();
        enc.clearColorTexture(histA, 0);
        enc.clearColorTexture(histB, 0);
        enc.clearColorTexture(cenA, 0);
        enc.clearColorTexture(cenB, 0);
        if (sampler == null)
            sampler = device.createSampler(AddressMode.CLAMP_TO_EDGE, AddressMode.CLAMP_TO_EDGE,
                    FilterMode.NEAREST, FilterMode.NEAREST, 1, OptionalDouble.empty());
        width = w;
        height = h;
    }

    private static void fail(Throwable t) {
        broken = true;
        haveBefore = false;
        thunder.hack.ThunderHack.LOGGER.error("[Gombal] ItemShader outline disabled: {}", t.toString());
    }

    private static void close() {
        for (GpuTextureView v : new GpuTextureView[]{beforeView, afterView, hView, mistView, histAView, histBView, cenAView, cenBView})
            try {
                if (v != null) v.close();
            } catch (Throwable ignored) {
            }
        for (GpuTexture t : new GpuTexture[]{before, after, hTex, mistTex, histA, histB, cenA, cenB})
            try {
                if (t != null) t.close();
            } catch (Throwable ignored) {
            }
        before = after = hTex = mistTex = histA = histB = cenA = cenB = null;
        beforeView = afterView = hView = mistView = histAView = histBView = cenAView = cenBView = null;
        width = height = 0;
    }
}
