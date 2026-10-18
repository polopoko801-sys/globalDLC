package thunder.hack.core.target;

import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.util.math.Vec3d;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import thunder.hack.utility.render.core.CloudanAnimationEngine;
import thunder.hack.utility.render.core.CloudanRenderCore;

/**
 * What is being targeted, and nothing about where the camera is pointing.
 *
 * <p><b>This class is the whole point of the split.</b> In an ordinary killaura, "the target" and "where
 * you are looking" are the same variable: picking a target moves the view, and moving the view picks a
 * target. That is why such a module fights the mouse. Here the two are different objects that do not know
 * about each other - {@link thunder.hack.core.rotation.RotationState} owns the camera and the server-facing
 * angles, this owns which entity is marked and how its marker is animated, and nothing in this file writes
 * to that one. Grep it: there is no reference to RotationState, to CloudanCameraController, or to any
 * setYaw/setPitch, and there is not meant to be.
 *
 * <p>So the camera keeps absolute priority by construction rather than by a flag that could be left in the
 * wrong position. You can be looking away from your target the whole time; the marker simply sits out there
 * on the entity, where it belongs.
 *
 * <p><b>Everything moves by easing, never by snapping.</b> The marker position, the lock-on progress and
 * the fade of a target you just lost are all driven through
 * {@link CloudanAnimationEngine#approach(float, float, float, float)}, which is half-life based - so the
 * motion looks identical at 60fps and at 300, and a target that jumps across the screen is followed rather
 * than teleported to.
 */
public final class TargetState {
    private TargetState() {
    }

    /** What the HUD is allowed to say about a target, beyond its numbers. */
    public enum Status {
        /** Nothing special - a plain, hittable target. */
        Normal,
        /** Took damage very recently. */
        Hurt,
        /** Holding a raised shield, so a swing would be blocked. */
        Shielding,
        /** There is a block between you and it. */
        Blocked,
        /** Flying on an elytra. */
        Gliding,
        /** Below a quarter of its health. */
        Low
    }

    /* ------------------------------------------------------------------ */
    /* State                                                               */
    /* ------------------------------------------------------------------ */

    private static LivingEntity target;
    /** The one before it, kept only so its marker can fade out instead of vanishing. */
    private static LivingEntity fading;

    private static long acquiredAt;
    private static long lostAt;

    /** The marker's own position, which chases the target rather than being pinned to it. */
    private static Vec3d marker = Vec3d.ZERO;
    private static Vec3d fadingMarker = Vec3d.ZERO;

    /** 0 on acquire, easing to 1 - what the lock-on animation reads. */
    private static float lock;
    /** 1 when a target is lost, easing to 0. */
    private static float fade;

    private static long lastUpdate;

    /* ------------------------------------------------------------------ */
    /* Reading                                                             */
    /* ------------------------------------------------------------------ */

    public static @Nullable LivingEntity target() {
        return target;
    }

    public static boolean has() {
        return target != null && target.isAlive();
    }

    public static @Nullable LivingEntity fadingTarget() {
        return fading;
    }

    /** The eased marker position - not the entity's position. This is what visuals draw at. */
    public static @NotNull Vec3d marker() {
        return marker;
    }

    public static @NotNull Vec3d fadingMarker() {
        return fadingMarker;
    }

    /** How far the lock-on animation has got, 0 to 1. */
    public static float lock() {
        return lock;
    }

    /** How much of the lost target's marker is left, 1 to 0. */
    public static float fade() {
        return fade;
    }

    public static long heldMs() {
        return target == null ? 0L : System.currentTimeMillis() - acquiredAt;
    }

    /* ------------------------------------------------------------------ */
    /* Updating                                                            */
    /* ------------------------------------------------------------------ */

    /**
     * Files a new selection and advances every animation.
     *
     * <p>Called once per frame from the visual that owns it. Passing null is how a target is dropped, and
     * it starts the fade rather than clearing immediately - a marker that blinks out the instant someone
     * steps behind a wall is what makes this kind of visual feel twitchy.
     *
     * @param followHalfLifeMs how long the marker takes to cover half the distance to the entity. Smaller
     *                         is tighter; around 60-120ms reads as "attached but not rigid".
     */
    public static void update(@Nullable LivingEntity selected, float followHalfLifeMs, float animationSpeed) {
        long now = System.currentTimeMillis();
        float delta = lastUpdate == 0L ? 16f : Math.min(250f, now - lastUpdate);
        lastUpdate = now;

        // A target that died or unloaded is gone, not lost - there is nothing to fade towards.
        if (target != null && (!target.isAlive() || target.isRemoved())) {
            target = null;
            fading = null;
            fade = 0f;
            lock = 0f;
        }

        if (selected != target) {
            if (target != null) {
                // Hand the old one over to the fade, from exactly where its marker was. Starting the fade
                // at the entity instead would make it jump on the frame the target changed.
                fading = target;
                fadingMarker = marker;
                fade = 1f;
                lostAt = now;
            }

            target = selected;

            if (selected != null) {
                acquiredAt = now;
                lock = 0f;
                // Start the marker on the entity. Easing it in from wherever the last one was would drag a
                // visible streak across the world between two unrelated targets.
                marker = centreOf(selected);
            } else {
                lock = 0f;
            }
        }

        float speed = Math.max(0.1f, animationSpeed);
        float halfLife = Math.max(8f, followHalfLifeMs / speed);

        if (target != null) {
            marker = approach(marker, centreOf(target), halfLife, delta);
            // 180ms to settle: fast enough to feel immediate, slow enough to be seen happening.
            lock = CloudanAnimationEngine.approach(lock, 1f, 180f / speed, delta);
        }

        if (fade > 0f) {
            if (fading != null && fading.isAlive() && !fading.isRemoved())
                fadingMarker = approach(fadingMarker, centreOf(fading), halfLife, delta);

            fade = CloudanAnimationEngine.approach(fade, 0f, 160f / speed, delta);
            if (fade < 0.01f) {
                fade = 0f;
                fading = null;
            }
        }
    }

    /** The point a marker belongs on: the middle of the body, interpolated between ticks. */
    public static @NotNull Vec3d centreOf(@NotNull Entity entity) {
        Vec3d position = CloudanRenderCore.interpolated(entity);
        return position.add(0, entity.getHeight() / 2.0, 0);
    }

    private static Vec3d approach(Vec3d current, Vec3d goal, float halfLifeMs, float deltaMs) {
        return new Vec3d(
                CloudanAnimationEngine.approach((float) current.x, (float) goal.x, halfLifeMs, deltaMs),
                CloudanAnimationEngine.approach((float) current.y, (float) goal.y, halfLifeMs, deltaMs),
                CloudanAnimationEngine.approach((float) current.z, (float) goal.z, halfLifeMs, deltaMs));
    }

    /** What to say about the target besides its health bar. */
    public static Status statusOf(@Nullable LivingEntity entity) {
        if (entity == null) return Status.Normal;

        if (entity.isGliding()) return Status.Gliding;
        if (entity.isBlocking()) return Status.Shielding;
        if (entity.hurtTime > 0) return Status.Hurt;
        if (entity.getHealth() <= entity.getMaxHealth() * 0.25f) return Status.Low;

        // Checked last: it is the only one of these that costs a raycast.
        var player = net.minecraft.client.MinecraftClient.getInstance().player;
        if (player != null && !player.canSee(entity)) return Status.Blocked;

        return Status.Normal;
    }

    /**
     * Drops everything.
     *
     * <p>Called on disable, on world change and on logout. Not optional: a {@code LivingEntity} from an
     * unloaded world is a reference to an object whose world is gone, and the animation timers would carry
     * a stale {@code lastUpdate} across the loading screen and produce one enormous delta on the first
     * frame of the new world.
     */
    public static void reset() {
        target = null;
        fading = null;
        marker = Vec3d.ZERO;
        fadingMarker = Vec3d.ZERO;
        lock = 0f;
        fade = 0f;
        acquiredAt = 0L;
        lostAt = 0L;
        lastUpdate = 0L;
    }
}
