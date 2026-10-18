package thunder.hack.utility.render;

/**
 * 1.21.6+ stub. The old MSAA helper extended {@code net.minecraft.client.gl.Framebuffer} and drove
 * raw GL (rboColor/rboDepth, GlStateManager._glBlitFrameBuffer, beginWrite/endWrite) — all removed
 * by the RenderPipeline overhaul. Until a multisample framebuffer is reimplemented on the new
 * pipeline, {@link #use} simply runs the draw block directly (no anti-aliasing). Behaviour-neutral
 * except for edge smoothing; verify at runtime.
 */
public class MSAAFramebuffer {
    public static final int MAX_SAMPLES = 4;

    public static void use(boolean ignored, Runnable drawer) {
        if (drawer != null) drawer.run();
    }

    public static void use(int ignoredSamples, Runnable drawer) {
        if (drawer != null) drawer.run();
    }
}