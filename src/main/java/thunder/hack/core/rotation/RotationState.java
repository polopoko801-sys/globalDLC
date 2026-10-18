package thunder.hack.core.rotation;

import net.minecraft.entity.Entity;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import static thunder.hack.features.modules.Module.mc;

/**
 * The four directions a player has, kept apart.
 *
 * <p>A client that stores one "rotation" and lets everything write to it produces exactly the bug this
 * package exists to remove: your target changes and the camera swings to face it, or your body turns to
 * throw a hit and the block you were about to click is suddenly behind you. They are different questions
 * with different answers:
 *
 * <ul>
 *   <li>{@link #cameraYaw}/{@link #cameraPitch} — where YOU are looking. The only direction that may
 *       decide what a normal click interacts with.</li>
 *   <li>{@link #aimYaw}/{@link #aimPitch} — where an attack is pointed. This is what goes to the server
 *       in the movement packet; it does not have to be where the camera points.</li>
 *   <li>{@link #bodyYaw}/{@link #bodyPitch} — which way the model faces. Cosmetic, and free to spin.</li>
 *   <li>{@link #targetYaw}/{@link #targetPitch} — the raw angle to the current target. An input to the
 *       three above, never a rotation in its own right.</li>
 * </ul>
 *
 * <p>Nothing here decides anything. It is the shared vocabulary the controllers write into and everyone
 * else reads from, so "which yaw did you mean" stops being a question you have to ask.
 */
public final class RotationState {
    private RotationState() {
    }

    /* ---- camera: the user's own look ---- */
    public static float cameraYaw;
    public static float cameraPitch;

    /* ---- aim: what the server is told an attack points at ---- */
    public static float aimYaw;
    public static float aimPitch;
    /** False when nothing is aiming; the aim angles are then just a copy of the camera. */
    public static boolean aiming;

    /* ---- body: the model's facing, purely visual ---- */
    public static float bodyYaw;
    public static float bodyPitch;

    /* ---- target: the raw angle to whatever is being fought ---- */
    public static float targetYaw;
    public static float targetPitch;
    public static boolean hasTarget;

    /**
     * Takes the user's real look direction at the top of the tick, before any module has had a chance to
     * write to the player.
     *
     * <p>This is the anchor for everything else: once a module sets {@code mc.player.setYaw(...)} to send
     * an aimed packet, the player object no longer remembers where you were actually looking, and without
     * a snapshot taken first there is nothing to restore or to interact with.
     */
    public static void captureCamera() {
        if (mc.player == null) return;

        cameraYaw = mc.player.getYaw();
        cameraPitch = mc.player.getPitch();

        if (!aiming) {
            aimYaw = cameraYaw;
            aimPitch = cameraPitch;
        }
    }

    /** Called by whatever is aiming, with the angle it wants the server to see. */
    public static void setAim(float yaw, float pitch) {
        aimYaw = MathHelper.wrapDegrees(yaw);
        aimPitch = MathHelper.clamp(pitch, -90f, 90f);
        aiming = true;
    }

    /** Aim goes back to following the camera. */
    public static void clearAim() {
        aiming = false;
        aimYaw = cameraYaw;
        aimPitch = cameraPitch;
    }

    /** Records the angle to the current target, or clears it when there is none. */
    public static void setTarget(@Nullable Entity target) {
        if (target == null || mc.player == null) {
            hasTarget = false;
            return;
        }

        float[] angles = anglesTo(target.getEyePos());
        targetYaw = angles[0];
        targetPitch = angles[1];
        hasTarget = true;
    }

    /** Yaw and pitch from the player's eyes to a world point. */
    public static float @NotNull [] anglesTo(@NotNull Vec3d point) {
        if (mc.player == null) return new float[]{cameraYaw, cameraPitch};

        Vec3d eyes = mc.player.getEyePos();
        double dx = point.x - eyes.x;
        double dy = point.y - eyes.y;
        double dz = point.z - eyes.z;
        double horizontal = Math.sqrt(dx * dx + dz * dz);

        return new float[]{
                (float) MathHelper.wrapDegrees(Math.toDegrees(Math.atan2(dz, dx)) - 90.0),
                (float) MathHelper.wrapDegrees(-Math.toDegrees(Math.atan2(dy, horizontal)))
        };
    }

    /** Shortest signed difference between two yaws, in degrees. */
    public static float yawDelta(float from, float to) {
        return MathHelper.wrapDegrees(to - from);
    }

    /** Angle between the camera and the target, in degrees - how far off you are pointing. */
    public static float angleToTarget() {
        if (!hasTarget) return 0f;

        float dYaw = yawDelta(cameraYaw, targetYaw);
        float dPitch = targetPitch - cameraPitch;
        return (float) Math.sqrt(dYaw * dYaw + dPitch * dPitch);
    }

    /** The direction a plain interaction should use. Always the camera - that is the whole point. */
    public static float @NotNull [] interactionAngles() {
        return new float[]{cameraYaw, cameraPitch};
    }

    public static void reset() {
        aiming = false;
        hasTarget = false;
        if (mc.player == null) return;

        cameraYaw = aimYaw = bodyYaw = mc.player.getYaw();
        cameraPitch = aimPitch = bodyPitch = mc.player.getPitch();
    }
}
