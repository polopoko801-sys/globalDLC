package thunder.hack.core.rotation;

import net.minecraft.util.math.MathHelper;

import static thunder.hack.features.modules.Module.mc;

/**
 * The body's facing - the direction the model points, and nothing else.
 *
 * <p>Purely cosmetic by construction. The body is not what an attack is aimed along, not what a click
 * interacts with, and not what the camera shows; it is a mesh orientation. That is what makes a 360°
 * spin safe: it cannot pull the camera round with it, because the camera is not reading from here.
 *
 * <p>The spin is an OFFSET applied on top of the natural body yaw, not a replacement for it. Overwriting
 * the yaw outright would freeze the normal lean the body does when you strafe, and the result reads as a
 * mannequin being rotated rather than a player spinning.
 */
public final class PlayerRotationController {
    private PlayerRotationController() {
    }

    /** How the body behaves on top of its natural facing. */
    public enum SpinMode {
        /** Nothing added; the body faces where it normally would. */
        Off,
        /** Turns continuously around its own axis. */
        Spin,
        /** Faces the target while the camera stays wherever the user put it. */
        FaceTarget
    }

    public static SpinMode mode = SpinMode.Off;
    /** Degrees per second for {@link SpinMode#Spin}. */
    public static float spinSpeed = 180f;

    /** Accumulated spin, degrees. Kept separately so toggling the mode off leaves nothing behind. */
    private static float spinOffset;
    private static long lastUpdate;

    /**
     * Advances the body one step. Called per frame.
     *
     * @param naturalYaw what vanilla would have used - normally the player's own yaw
     * @return the yaw the model should be drawn at
     */
    public static float update(float naturalYaw) {
        long now = System.currentTimeMillis();
        float deltaMs = lastUpdate == 0 ? 16f : Math.min(100f, now - lastUpdate);
        lastUpdate = now;

        switch (mode) {
            case Off -> spinOffset = 0f;
            case Spin -> spinOffset = MathHelper.wrapDegrees(spinOffset + spinSpeed * deltaMs / 1000f);
            case FaceTarget -> {
                if (RotationState.hasTarget)
                    // An offset rather than an absolute yaw, so the body still leans as you strafe.
                    spinOffset = RotationState.yawDelta(naturalYaw, RotationState.targetYaw);
                else spinOffset = 0f;
            }
        }

        RotationState.bodyYaw = MathHelper.wrapDegrees(naturalYaw + spinOffset);
        RotationState.bodyPitch = mc.player == null ? 0f : mc.player.getPitch();
        return RotationState.bodyYaw;
    }

    /**
     * Applies the current offset WITHOUT advancing time.
     *
     * <p>Two callers need the body yaw each frame and only one of them may drive the clock: the render
     * hook wants a value for the model, and the per-frame driver wants the offset advanced. If both
     * called {@link #update} the spin would step twice per frame, from two different base yaws. So the
     * driver advances and the renderer applies.
     */
    public static float apply(float naturalYaw) {
        if (mode == SpinMode.Off) return naturalYaw;
        return MathHelper.wrapDegrees(naturalYaw + spinOffset);
    }

    /** True when the body is doing something of its own and the render hook should override the state. */
    public static boolean isActive() {
        return mode != SpinMode.Off;
    }

    public static float offset() {
        return spinOffset;
    }

    public static void reset() {
        spinOffset = 0f;
        lastUpdate = 0;
    }
}
