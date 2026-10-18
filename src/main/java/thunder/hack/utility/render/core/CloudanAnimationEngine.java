package thunder.hack.utility.render.core;

import org.jetbrains.annotations.NotNull;
import thunder.hack.utility.render.animation.Animation;
import thunder.hack.utility.render.animation.Easing;

/**
 * The client's clock for anything that moves.
 *
 * <p>Two rules, and they are the whole reason this is central. Animations run on wall time, never on
 * frames - a fade that steps by a fixed amount per frame is three times faster on a 300 fps machine than
 * on a 100 fps one, and that is how "smooth" ends up meaning different things on different computers.
 * And nothing snaps: a value that jumps is a value that was not animated, which is exactly what makes an
 * effect look cheap.
 *
 * <p>{@link Animation} and {@link Easing} do the work; this hands them out with the global speed applied
 * and provides the stateless helpers - {@link #smoothStep}, {@link #approach} - for the many cases that
 * do not need a whole object to track.
 */
public final class CloudanAnimationEngine {
    private CloudanAnimationEngine() {
    }

    /** Global multiplier on every duration. Below 1 is faster, above 1 is slower. */
    private static float speed = 1f;

    public static void setSpeed(float value) {
        speed = Math.max(0.1f, Math.min(4f, value));
    }

    public static float speed() {
        return speed;
    }

    /**
     * A new animated 0..1 value.
     *
     * <p>ReducedMotion collapses the duration instead of disabling the animation: the value still moves
     * through its whole range, it just gets there almost immediately. Skipping it entirely would leave
     * anything that reads mid-animation state stuck at zero.
     */
    public static @NotNull Animation create(long durationMs, @NotNull Easing easing) {
        long scaled = CloudanRenderCore.reduceEffects()
                ? Math.max(1, (long) (durationMs * 0.25f))
                : (long) (durationMs * speed);
        return new Animation(Math.max(1, scaled), easing);
    }

    public static @NotNull Animation create(long durationMs) {
        return create(durationMs, Easing.EaseOut);
    }

    /* ------------------------------------------------------------------ */
    /* Stateless helpers                                                   */
    /* ------------------------------------------------------------------ */

    /** The classic smoothstep: 0 at the edges, eased in the middle. */
    public static float smoothStep(float t) {
        float x = clamp01(t);
        return x * x * (3f - 2f * x);
    }

    /** smoothstep between two bounds. */
    public static float smoothStep(float edge0, float edge1, float value) {
        if (edge1 - edge0 == 0f) return value < edge0 ? 0f : 1f;
        return smoothStep((value - edge0) / (edge1 - edge0));
    }

    /**
     * Moves {@code current} towards {@code target} at a rate that is the same at any frame rate.
     *
     * <p>For the very common {@code value += (target - value) * 0.1f} that every client has scattered
     * around, and which is frame-rate dependent in exactly the way this class exists to prevent.
     *
     * @param halfLifeMs how long it takes to close half the remaining gap
     * @param deltaMs    real milliseconds since the last call
     */
    public static float approach(float current, float target, float halfLifeMs, float deltaMs) {
        if (halfLifeMs <= 0f) return target;

        float factor = (float) (1.0 - Math.pow(0.5, deltaMs / halfLifeMs));
        return current + (target - current) * factor;
    }

    /** {@link #approach} for an angle, taking the short way round instead of the long one. */
    public static float approachAngle(float current, float target, float halfLifeMs, float deltaMs) {
        float delta = net.minecraft.util.math.MathHelper.wrapDegrees(target - current);
        return current + (approach(0f, delta, halfLifeMs, deltaMs));
    }

    public static float lerp(float from, float to, float t) {
        return from + (to - from) * clamp01(t);
    }

    /** A slow pulse on the wall clock, for anything that should breathe rather than blink. */
    public static float pulse(float periodSeconds, float amount) {
        if (CloudanRenderCore.reduceEffects()) return 1f;

        double seconds = (System.currentTimeMillis() % 1_000_000L) / 1000.0;
        return 1f + (float) Math.sin(seconds * (Math.PI * 2 / Math.max(0.05f, periodSeconds))) * amount;
    }

    /** Radians that advance with the clock, for anything that turns. */
    public static double spin(float secondsPerTurn) {
        if (CloudanRenderCore.reduceEffects()) return 0;

        double seconds = (System.currentTimeMillis() % 1_000_000L) / 1000.0;
        return seconds * (Math.PI * 2 / Math.max(0.05f, secondsPerTurn)) / speed;
    }

    private static float clamp01(float v) {
        return v < 0f ? 0f : Math.min(v, 1f);
    }
}
