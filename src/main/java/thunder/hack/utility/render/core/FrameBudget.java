package thunder.hack.utility.render.core;

import thunder.hack.features.modules.client.ClientSettings;

/**
 * Scales the render budgets down when the frame rate falls below the target, and back up when it recovers.
 *
 * <p>It does not switch anything off. It multiplies into {@link ClientSettings#qualityScale()}, which every
 * visual already uses for its particle count, glow layers and preview limits - so a slow machine gets fewer
 * particles and less glow first, and never a missing RenderCore. Falls fast, recovers slowly, so it does not
 * oscillate around the target.
 */
public final class FrameBudget {
    private FrameBudget() {
    }

    private static final float MIN = 0.35f;

    private static long lastNanos;
    private static float frameMs = 16.6f;
    private static float scale = 1f;

    static void frame() {
        long now = System.nanoTime();
        if (lastNanos != 0L) {
            float ms = (now - lastNanos) / 1_000_000f;
            // ignore hitches longer than a quarter second (loading, alt-tab) - they are not a trend
            if (ms < 250f) frameMs += (ms - frameMs) * 0.05f;
        }
        lastNanos = now;

        if (!ClientSettings.adaptiveQuality.getValue()) {
            scale = 1f;
            return;
        }

        float targetMs = 1000f / Math.max(20, ClientSettings.targetFps.getValue());
        if (frameMs > targetMs * 1.08f) scale = Math.max(MIN, scale - 0.02f);
        else if (frameMs < targetMs * 0.85f) scale = Math.min(1f, scale + 0.004f);
    }

    /** 1 when the target is met, down to {@value #MIN} on a machine that cannot hold it. */
    public static float scale() {
        return scale;
    }

    public static float frameMillis() {
        return frameMs;
    }

    public static boolean throttling() {
        return scale < 0.98f;
    }
}
