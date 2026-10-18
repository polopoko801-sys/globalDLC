package thunder.hack.utility.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.AddressMode;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.textures.TextureFormat;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.Framebuffer;
import net.minecraft.client.gl.GpuSampler;

import java.util.OptionalDouble;

/**
 * Snapshot of the frame as it looks right before the HUD is drawn. WildClient gets its glass by binding a
 * copy of the main framebuffer and running a radial-blur shader over it; custom core shaders are gone in
 * 1.21.6+ (everything is RenderPipeline + UBOs), so we keep the first half — the copy — and do the blur by
 * accumulating offset samples of this texture (see {@link Render2DEngine#drawBackdropBlur}).
 *
 * <p>The copy is what makes it safe: sampling the framebuffer you are drawing into is undefined, and the
 * snapshot is taken before any HUD geometry lands, so panels never blur other panels.
 */
public final class ScreenCapture {
    private static GpuTexture texture;
    private static GpuTextureView view;
    private static GpuSampler sampler;
    private static int width, height;
    private static boolean captured;
    private static boolean unsupported;
    private static int failures;
    private static boolean warned;

    /**
     * How many frames a failing copy is retried before glass gives up for good. One failed frame can mean
     * anything - a resolution change, a device that is not ready on the first frames, a driver hiccup - and
     * it used to switch the entire client's glass off for the rest of the session.
     */
    private static final int MAX_FAILURES = 60;

    private ScreenCapture() {
    }

    /** True once this frame's snapshot exists and can be sampled. */
    public static boolean available() {
        return captured && view != null && sampler != null;
    }

    public static GpuTextureView view() {
        return view;
    }

    public static GpuSampler sampler() {
        return sampler;
    }

    /** Invalidate the snapshot; called at the start of every HUD frame. */
    public static void invalidate() {
        captured = false;
    }

    /** Take a fresh snapshot for this frame — call before any glass panel is built. */
    public static void captureFrame() {
        invalidate();
        capture();
    }

    /**
     * Copies the current main framebuffer into our own texture. Cheap enough to run per frame; if the
     * driver/backend refuses the copy it retries for a short while ({@link #MAX_FAILURES} frames) and only
     * then switches itself off for good, logging why so a flat-looking GUI is diagnosable.
     */
    public static void capture() {
        if (unsupported || captured) return;
        try {
            MinecraftClient mc = MinecraftClient.getInstance();
            Framebuffer fb = mc.getFramebuffer();
            if (fb == null) return;
            GpuTexture src = fb.getColorAttachment();
            if (src == null) return;

            int w = src.getWidth(0);
            int h = src.getHeight(0);
            if (w <= 0 || h <= 0) return;

            if (texture == null || width != w || height != h)
                recreate(w, h);

            RenderSystem.getDevice().createCommandEncoder()
                    .copyTextureToTexture(src, texture, 0, 0, 0, 0, 0, w, h);
            captured = true;
            failures = 0;
            warned = false;
        } catch (Throwable t) {
            captured = false;
            if (++failures > MAX_FAILURES) {
                unsupported = true;
                close();
                thunder.hack.ThunderHack.LOGGER.error("[Glass] backdrop snapshot failed {} frames in a row - glass falls back to the translucent veil: {}",
                        failures, t.toString());
            } else if (!warned) {
                warned = true;
                thunder.hack.ThunderHack.LOGGER.warn("[Glass] backdrop snapshot failed, retrying next frame: {}", t.toString());
            }
        }
    }

    private static void recreate(int w, int h) {
        close();
        var device = RenderSystem.getDevice();
        texture = device.createTexture("thunderhack_backdrop", GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING,
                TextureFormat.RGBA8, w, h, 1, 1);
        view = device.createTextureView(texture);
        width = w;
        height = h;

        if (sampler == null) {
            try {
                sampler = device.createSampler(AddressMode.CLAMP_TO_EDGE, AddressMode.CLAMP_TO_EDGE,
                        FilterMode.LINEAR, FilterMode.LINEAR, 0, OptionalDouble.empty());
            } catch (Throwable t) {
                // fall back to whatever sampler a normal GUI texture uses
                sampler = MinecraftClient.getInstance().getTextureManager()
                        .getTexture(TextureStorage.miniLogo).getSampler();
            }
        }
    }

    private static void close() {
        try {
            if (texture != null) texture.close();
        } catch (Throwable ignored) {
        }
        texture = null;
        view = null;
        width = height = 0;
    }
}
