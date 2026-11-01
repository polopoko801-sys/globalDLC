package thunder.hack.utility.render.animation;

/**
 * One animated 0..1 value that moves in real time, not in frames.
 *
 * <p>Everything visual that fades, scales or slides holds one of these instead of hand-rolling a
 * {@code value += 0.05f} that runs three times faster on a 300 fps machine than on a 100 fps one. The
 * progress is driven off the wall clock and shaped by an {@link Easing}, so the same animation takes the
 * same number of milliseconds everywhere.
 *
 * <p>Not thread safe on purpose: these live on the render thread and a lock per fade would cost more
 * than the fade.
 */
public final class Animation {
    private final long durationMs;
    private final Easing easing;

    /** Raw, un-eased progress, 0..1. */
    private float raw;
    private boolean forward;
    private long lastUpdate = System.nanoTime();

    public Animation(long durationMs, Easing easing) {
        this.durationMs = Math.max(1, durationMs);
        this.easing = easing;
    }

    public Animation(long durationMs) {
        this(durationMs, Easing.EaseOut);
    }

    /** Where the animation should be heading. Safe to call every frame with the same value. */
    public Animation setForward(boolean forward) {
        this.forward = forward;
        return this;
    }

    /** Advances by the real time since the last call and returns the eased value, 0..1. */
    public float get() {
        long now = System.nanoTime();
        float elapsed = (now - lastUpdate) / 1_000_000f;
        lastUpdate = now;

        // A frame that took a second (world load, alt-tab) would otherwise teleport every animation to
        // its end; clamping the step keeps a hitch looking like a hitch instead of a jump cut.
        float step = Math.min(elapsed, 250f) / (float) durationMs;
        raw = clamp(forward ? raw + step : raw - step);

        return easing.applyClamped(raw);
    }

    /** The eased value without advancing time - for a second read inside the same frame. */
    public float peek() {
        return easing.applyClamped(raw);
    }

    /** Jumps straight to one end. Used on enable/disable so nothing fades in from a stale state. */
    public Animation reset(boolean toEnd) {
        raw = toEnd ? 1f : 0f;
        forward = toEnd;
        lastUpdate = System.nanoTime();
        return this;
    }

    public boolean isFinished() {
        return forward ? raw >= 1f : raw <= 0f;
    }

    public boolean isIdle() {
        return !forward && raw <= 0f;
    }

    private static float clamp(float v) {
        return v < 0f ? 0f : Math.min(v, 1f);
    }
}
