package thunder.hack.utility.render.animation;

import thunder.hack.utility.math.FrameRateCounter;

public class AnimationUtility {
    /**
     * Duration of the current frame in seconds.
     *
     * <p>Used to be 1 / (frames counted over the last second). That average lags a second behind: after a
     * stutter every animation in the client kept stepping at the old rate and visibly sped up or crawled.
     * The real per-frame time follows the frame rate as it actually is.
     */
    public static float deltaTime() {
        return FrameRateCounter.INSTANCE.getFrameDelta();
    }

    /**
     * Eases {@code end} towards {@code start} (the names are historical: current value first, target second).
     *
     * <p>Exponential smoothing, {@code 1 - e^(-dt * speed)}: the same visual speed at 30 FPS and at 300 FPS.
     * The old linear step {@code dt * speed} overshot at low frame rates (clamped to a hard snap at 1) and
     * dragged at high ones, so the whole GUI felt different on every machine.
     */
    public static float fast(float end, float start, float multiple) {
        float k = 1f - (float) Math.exp(-deltaTime() * multiple);
        float v = end + (start - end) * k;
        // land exactly instead of creeping towards the target forever (no 0.9999 shimmer on alpha / position)
        return Math.abs(start - v) < 0.0005f ? start : v;
    }
}
