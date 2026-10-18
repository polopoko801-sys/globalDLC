package thunder.hack.utility.render.core;

import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import thunder.hack.ThunderHack;
import thunder.hack.utility.render.ScreenCapture;

import java.util.Locale;

import static thunder.hack.features.modules.Module.mc;

/**
 * What the machine we are running on can do - read once, logged once.
 *
 * <p>The point is diagnosis as much as fallback: "it looks fine here and bad on the other PC" cannot be
 * answered without knowing what the other PC is. The first frame writes one line with vendor, renderer,
 * driver, backend, texture limit, framebuffer size and GUI scale to latest.log, and the RenderDebug HUD shows
 * the same live, so a log from any machine says exactly which path it took.
 */
public final class GpuCapabilities {
    private GpuCapabilities() {
    }

    public enum Vendor {NVIDIA, AMD, INTEL, APPLE, OTHER}

    private static boolean probed;
    private static String vendor = "?", renderer = "?", version = "?", backend = "?";
    private static int maxTextureSize, maxAnisotropy;
    private static Vendor vendorKind = Vendor.OTHER;

    /** Called from the render thread at the start of a 3D frame; does its work exactly once. */
    static void probe() {
        if (probed) return;
        probed = true;

        try {
            GpuDevice device = RenderSystem.getDevice();
            vendor = safe(device.getVendor());
            renderer = safe(device.getRenderer());
            version = safe(device.getVersion());
            backend = safe(device.getBackendName());
            maxTextureSize = device.getMaxTextureSize();
            maxAnisotropy = device.getMaxSupportedAnisotropy();
        } catch (Throwable t) {
            ThunderHack.LOGGER.warn("[Cloudan] GPU probe failed", t);
        }

        String id = (vendor + " " + renderer).toLowerCase(Locale.ROOT);
        if (id.contains("nvidia") || id.contains("geforce")) vendorKind = Vendor.NVIDIA;
        else if (id.contains("amd") || id.contains("radeon") || id.contains("ati ")) vendorKind = Vendor.AMD;
        else if (id.contains("intel")) vendorKind = Vendor.INTEL;
        else if (id.contains("apple")) vendorKind = Vendor.APPLE;

        ThunderHack.LOGGER.info("[Cloudan] GPU: {} | {} | driver {} | backend {} | maxTex {} | aniso {} | fb {}x{} | window {}x{} | gui scale {}",
                vendor, renderer, version, backend, maxTextureSize, maxAnisotropy,
                mc.getWindow().getFramebufferWidth(), mc.getWindow().getFramebufferHeight(),
                mc.getWindow().getWidth(), mc.getWindow().getHeight(), mc.getWindow().getScaleFactor());
    }

    private static String safe(String s) {
        return s == null || s.isBlank() ? "?" : s.trim();
    }

    public static boolean probed() {
        return probed;
    }

    public static Vendor vendorKind() {
        return vendorKind;
    }

    public static String renderer() {
        return renderer;
    }

    public static String driver() {
        return version;
    }

    public static String backend() {
        return backend;
    }

    public static int maxTextureSize() {
        return maxTextureSize;
    }

    /** Backdrop glass needs the framebuffer snapshot; without it panels fall back to the painted glass. */
    public static boolean backdropAvailable() {
        return ScreenCapture.available();
    }

    /** Window and framebuffer disagree under OS display scaling - worth showing, it changes projections. */
    public static boolean displayScaled() {
        return mc.getWindow().getFramebufferWidth() != mc.getWindow().getWidth()
                || mc.getWindow().getFramebufferHeight() != mc.getWindow().getHeight();
    }
}
