package thunder.hack.core.rotation;

import net.minecraft.entity.Entity;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.jetbrains.annotations.Nullable;

import static thunder.hack.features.modules.Module.mc;

/**
 * Where an attack points - which is not, and must not be, where the camera points.
 *
 * <p>Four modes, and the difference between them is precisely how much they are allowed to touch:
 *
 * <ul>
 *   <li>{@link AimMode#Normal} — the aim goes at the target, the camera is never touched. The attack
 *       direction is computed from where the target actually IS, not from the camera's animation, so a
 *       target to your east is hit to the east whatever the screen is showing.</li>
 *   <li>{@link AimMode#AimAssist} — the camera stays yours; assist only bends it, by Strength, inside
 *       MaxAngle. This is the only mode that goes near the camera, and it nudges rather than takes.</li>
 *   <li>{@link AimMode#TargetLock} — locks the logical target and keeps aiming at it. Lock target, NOT
 *       force camera: a new target changes what is hit, never where you are looking.</li>
 *   <li>{@link AimMode#Independent} — aim and camera fully separate. Camera east, target west, attack
 *       west, screen unmoved.</li>
 * </ul>
 *
 * <p>Nothing here writes {@code mc.player}'s rotation. It produces angles in {@link RotationState}; the
 * packet layer decides what to send and the camera controller decides what to show. Keeping those apart
 * is the entire point of the split.
 */
public final class AimController {
    private AimController() {
    }

    public enum AimMode {
        Normal,
        AimAssist,
        TargetLock,
        Independent
    }

    /** Which target the aim prefers when several are in range. */
    public enum TargetPriority {
        Distance,
        Angle,
        Health
    }

    public static AimMode mode = AimMode.Normal;
    public static TargetPriority priority = TargetPriority.Distance;

    /* ---- Aim Assist ---- */
    /** 0..1. How much of the remaining angle assist closes per step. */
    public static float strength = 0.35f;
    /** Milliseconds to close half the gap. Higher = lazier. */
    public static float smoothing = 140f;
    /** Assist gives up past this many degrees - you are clearly aiming at something else. */
    public static float maxAngle = 35f;
    /** Ticks of target movement to lead by. 0 = aim where it is now. */
    public static int prediction = 0;

    /** The target the lock is holding, independent of what is nearest right now. */
    private static Entity locked;
    private static long lastUpdate;

    /**
     * Works out this tick's aim.
     *
     * @param candidate what the aura picked as nearest/best this tick
     * @return the entity actually being aimed at, which under a lock may not be the candidate
     */
    public static @Nullable Entity update(@Nullable Entity candidate) {
        long now = System.currentTimeMillis();
        float deltaMs = lastUpdate == 0 ? 50f : Math.min(200f, now - lastUpdate);
        lastUpdate = now;

        Entity target = resolve(candidate);
        RotationState.setTarget(target);

        if (target == null || mc.player == null) {
            RotationState.clearAim();
            return null;
        }

        Vec3d aimPoint = predictedPoint(target);
        float[] angles = RotationState.anglesTo(aimPoint);

        switch (mode) {
            case Normal, TargetLock, Independent ->
                // The aim goes straight at the target. The camera is not consulted and not written.
                    RotationState.setAim(angles[0], angles[1]);

            case AimAssist -> {
                // Assist does not aim for you - it helps you aim. The attack direction follows the CAMERA
                // (nudged), so what you hit is still what you are pointing at.
                CloudanCameraController.nudge(angles[0], angles[1], strength, smoothing, maxAngle, deltaMs);
                RotationState.setAim(mc.player.getYaw(), mc.player.getPitch());
            }
        }

        return target;
    }

    /**
     * Target Lock keeps the entity it had for as long as it stays valid; every other mode simply takes
     * whatever the aura offered this tick.
     */
    private static @Nullable Entity resolve(@Nullable Entity candidate) {
        if (mode != AimMode.TargetLock) {
            locked = null;
            return candidate;
        }

        if (locked != null && locked.isAlive() && !locked.isRemoved()) return locked;

        locked = candidate;
        return locked;
    }

    /** Leads a moving target by {@link #prediction} ticks of its current velocity. */
    private static Vec3d predictedPoint(Entity target) {
        Vec3d eyes = target.getEyePos();
        if (prediction <= 0) return eyes;

        Vec3d velocity = new Vec3d(
                target.getX() - target.lastX,
                target.getY() - target.lastY,
                target.getZ() - target.lastZ);

        return eyes.add(velocity.multiply(prediction));
    }

    /**
     * True when this mode is allowed to move the camera.
     *
     * <p>Only Aim Assist is, and only as a nudge. Everything that decides whether the client may write
     * the camera should ask this instead of guessing - it is why a target change can never spin you.
     */
    public static boolean mayMoveCamera() {
        return mode == AimMode.AimAssist || CloudanCameraController.followEnabled;
    }

    /** The angles an attack packet should carry. */
    public static float[] attackAngles() {
        return RotationState.aiming
                ? new float[]{RotationState.aimYaw, RotationState.aimPitch}
                : RotationState.interactionAngles();
    }

    public static @Nullable Entity locked() {
        return locked;
    }

    public static void reset() {
        locked = null;
        lastUpdate = 0;
        RotationState.clearAim();
    }

    /** Sorts candidates by the configured priority. Lower is better. */
    public static double score(Entity entity) {
        if (mc.player == null || entity == null) return Double.MAX_VALUE;

        return switch (priority) {
            case Distance -> mc.player.squaredDistanceTo(entity);
            case Angle -> {
                float[] angles = RotationState.anglesTo(entity.getEyePos());
                float dYaw = RotationState.yawDelta(RotationState.cameraYaw, angles[0]);
                float dPitch = angles[1] - RotationState.cameraPitch;
                yield Math.sqrt(dYaw * dYaw + dPitch * dPitch);
            }
            case Health -> entity instanceof net.minecraft.entity.LivingEntity living
                    ? living.getHealth() + living.getAbsorptionAmount()
                    : Double.MAX_VALUE;
        };
    }

    /** Clamps a pitch the way the server expects. */
    public static float clampPitch(float pitch) {
        return MathHelper.clamp(pitch, -90f, 90f);
    }
}
