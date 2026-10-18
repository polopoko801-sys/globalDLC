package thunder.hack.core.rotation;

import net.minecraft.entity.Entity;
import net.minecraft.util.math.MathHelper;
import org.jetbrains.annotations.Nullable;
import thunder.hack.utility.render.core.CloudanAnimationEngine;

import static thunder.hack.features.modules.Module.mc;

/**
 * The only thing in the client allowed to move the camera.
 *
 * <p>Aura does not write here. Target Lock does not write here. Changing target does not write here. The
 * camera is the user's, and it moves for exactly two reasons: the user moved the mouse, or the user
 * explicitly turned {@link #followEnabled} on and asked it to track.
 *
 * <p>That rule is the whole design. "My target changed and my screen span round" is what happens when
 * the aim direction and the camera direction are the same variable, and no amount of smoothing fixes it
 * - you have to stop the aim from being able to write the camera at all.
 *
 * <p>When following IS on it is a follow, not a snap: a deadzone so small corrections are ignored, a
 * half-life so the approach is frame-rate independent, and separate horizontal and vertical speeds
 * because vertical tracking is far more noticeable and wants to be slower.
 */
public final class CloudanCameraController {
    private CloudanCameraController() {
    }

    /* ------------------------------------------------------------------ */
    /* Settings, pushed in by the module that owns the UI                  */
    /* ------------------------------------------------------------------ */

    /** OFF by default. The camera is the user's until they say otherwise. */
    public static boolean followEnabled = false;
    /** Degrees per second, horizontally. */
    public static float horizontalSpeed = 140f;
    /** Degrees per second, vertically. Deliberately lower - vertical movement reads as much stronger. */
    public static float verticalSpeed = 90f;
    /** Higher = lazier. Milliseconds to close half the remaining gap. */
    public static float smoothing = 120f;
    /** Inside this many degrees the camera does not bother correcting at all. */
    public static float deadzone = 2.5f;
    /** How fast the camera drifts back to level when there is nothing to follow. 0 = it just stops. */
    public static float returnSpeed = 0f;

    private static long lastUpdate;

    /**
     * Moves the camera one step towards the target, if following is on.
     *
     * <p>Called once per frame. Does nothing at all - not a clamp, not a nudge - when following is off,
     * which is the default and the case that matters.
     */
    public static void update(@Nullable Entity target) {
        long now = System.currentTimeMillis();
        float deltaMs = lastUpdate == 0 ? 16f : Math.min(100f, now - lastUpdate);
        lastUpdate = now;

        if (mc.player == null) return;
        if (!followEnabled) {
            // Not following: the camera is whatever the user last set it to, full stop.
            RotationState.cameraYaw = mc.player.getYaw();
            RotationState.cameraPitch = mc.player.getPitch();
            return;
        }

        if (target == null) {
            if (returnSpeed > 0f) returnToLevel(deltaMs);
            return;
        }

        float[] angles = RotationState.anglesTo(target.getEyePos());
        float dYaw = RotationState.yawDelta(mc.player.getYaw(), angles[0]);
        float dPitch = angles[1] - mc.player.getPitch();

        // Inside the deadzone the target is close enough to centre that correcting it would read as the
        // camera twitching rather than tracking.
        if (Math.abs(dYaw) < deadzone && Math.abs(dPitch) < deadzone) return;

        float maxYaw = horizontalSpeed * deltaMs / 1000f;
        float maxPitch = verticalSpeed * deltaMs / 1000f;

        float stepYaw = CloudanAnimationEngine.approach(0f, dYaw, smoothing, deltaMs);
        float stepPitch = CloudanAnimationEngine.approach(0f, dPitch, smoothing, deltaMs);

        stepYaw = MathHelper.clamp(stepYaw, -maxYaw, maxYaw);
        stepPitch = MathHelper.clamp(stepPitch, -maxPitch, maxPitch);

        apply(mc.player.getYaw() + stepYaw, mc.player.getPitch() + stepPitch);
    }

    /** Eases the pitch back to the horizon when nothing is being followed. */
    private static void returnToLevel(float deltaMs) {
        float pitch = mc.player.getPitch();
        if (Math.abs(pitch) < 0.5f) return;

        float step = MathHelper.clamp(
                CloudanAnimationEngine.approach(0f, -pitch, smoothing, deltaMs),
                -returnSpeed * deltaMs / 1000f, returnSpeed * deltaMs / 1000f);
        apply(mc.player.getYaw(), pitch + step);
    }

    /**
     * The one place the camera is written. Everything else asks this, so there is a single line to put a
     * breakpoint on when the camera moves and nobody knows why.
     */
    private static void apply(float yaw, float pitch) {
        float clampedPitch = MathHelper.clamp(pitch, -90f, 90f);
        mc.player.setYaw(yaw);
        mc.player.setPitch(clampedPitch);
        RotationState.cameraYaw = yaw;
        RotationState.cameraPitch = clampedPitch;
    }

    /**
     * A one-off nudge, used by Aim Assist.
     *
     * <p>Separate from {@link #update} because it is a different promise: assist never takes the camera
     * over, it only bends what the user is already doing, by an amount they set. It is also the only way
     * anything aim-related is allowed near the camera.
     */
    public static void nudge(float towardsYaw, float towardsPitch, float strength, float smoothingMs, float maxDegrees, float deltaMs) {
        if (mc.player == null || strength <= 0f) return;

        float dYaw = RotationState.yawDelta(mc.player.getYaw(), towardsYaw);
        float dPitch = towardsPitch - mc.player.getPitch();

        // Past MaxAngle the user is clearly not trying to point at this target, so assist stays out of it.
        float angle = (float) Math.sqrt(dYaw * dYaw + dPitch * dPitch);
        if (angle > maxDegrees) return;

        float stepYaw = CloudanAnimationEngine.approach(0f, dYaw, smoothingMs, deltaMs) * strength;
        float stepPitch = CloudanAnimationEngine.approach(0f, dPitch, smoothingMs, deltaMs) * strength;

        apply(mc.player.getYaw() + stepYaw, mc.player.getPitch() + stepPitch);
    }

    public static void reset() {
        lastUpdate = 0;
    }
}
