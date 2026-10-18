package thunder.hack.utility.render.core;

import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import org.jetbrains.annotations.NotNull;
import org.joml.FrustumIntersection;
import org.joml.Matrix4f;

/**
 * Decides what is worth drawing at all, and how well.
 *
 * <p>Two questions, asked before any geometry is built: is it on screen, and how far away is it. The
 * first is a frustum test against the same view-projection matrix the frame is being drawn with - built
 * here rather than borrowed from vanilla's {@code Frustum}, which the client has no clean handle on. The
 * second picks a {@link Lod} tier, which is what lets a visual draw three glow layers on the thing in
 * your face and a single flat one on the thing sixty blocks away without every module inventing its own
 * distance rules.
 *
 * <p>Culling is the cheapest optimisation there is: work not submitted costs nothing downstream - no
 * vertices, no draw call, no overdraw.
 */
public final class CloudanCullingSystem {
    private CloudanCullingSystem() {
    }

    /** How far out each tier reaches, in blocks. Squared once at build time. */
    public static final double ULTRA_DISTANCE = 16;
    public static final double HIGH_DISTANCE = 32;
    public static final double MEDIUM_DISTANCE = 64;
    public static final double LOW_DISTANCE = 128;

    private static final FrustumIntersection FRUSTUM = new FrustumIntersection();
    private static Vec3d camera = Vec3d.ZERO;
    private static boolean ready;

    /** Detail tier for one object this frame. */
    public enum Lod {
        Ultra,
        High,
        Medium,
        Low,
        /** Past the last tier: do not draw it. */
        Skipped;

        /** Multiplier a visual can apply to its own segment counts, layer counts, particle counts. */
        public float scale() {
            return switch (this) {
                case Ultra -> 1f;
                case High -> 0.75f;
                case Medium -> 0.5f;
                case Low -> 0.25f;
                case Skipped -> 0f;
            };
        }

        public boolean drawable() {
            return this != Skipped;
        }
    }

    /** Called once per frame by the core, before anything draws. */
    static void update(@NotNull Matrix4f viewProjection, @NotNull Vec3d cameraPos) {
        camera = cameraPos;
        // The matrix is camera-relative, so the frustum it describes is centred on the origin and every
        // test below subtracts the camera position first.
        FRUSTUM.set(viewProjection);
        ready = true;
    }

    /* ------------------------------------------------------------------ */
    /* Frustum                                                             */
    /* ------------------------------------------------------------------ */

    /** True when any part of the box could be on screen. */
    public static boolean isVisible(@NotNull Box box) {
        if (!ready) return true;

        return FRUSTUM.testAab(
                (float) (box.minX - camera.x), (float) (box.minY - camera.y), (float) (box.minZ - camera.z),
                (float) (box.maxX - camera.x), (float) (box.maxY - camera.y), (float) (box.maxZ - camera.z));
    }

    /** True when a sphere around {@code centre} could be on screen. */
    public static boolean isVisible(@NotNull Vec3d centre, double radius) {
        if (!ready) return true;

        return FRUSTUM.testSphere(
                (float) (centre.x - camera.x), (float) (centre.y - camera.y), (float) (centre.z - camera.z),
                (float) radius);
    }

    /* ------------------------------------------------------------------ */
    /* Distance                                                            */
    /* ------------------------------------------------------------------ */

    public static double distanceTo(@NotNull Vec3d point) {
        return Math.sqrt(camera.squaredDistanceTo(point));
    }

    public static boolean inRange(@NotNull Vec3d point, double range) {
        return camera.squaredDistanceTo(point) <= range * range;
    }

    /* ------------------------------------------------------------------ */
    /* LOD                                                                 */
    /* ------------------------------------------------------------------ */

    /**
     * Tier for a point, from its distance AND the quality dial: turning quality down pulls every tier
     * boundary in, so Low genuinely draws less rather than drawing the same thing slightly dimmer.
     */
    public static @NotNull Lod lod(@NotNull Vec3d point) {
        return lod(distanceTo(point));
    }

    public static @NotNull Lod lod(double distance) {
        float scale = CloudanRenderCore.qualityScale();

        if (distance <= ULTRA_DISTANCE * scale) return Lod.Ultra;
        if (distance <= HIGH_DISTANCE * scale) return Lod.High;
        if (distance <= MEDIUM_DISTANCE * scale) return Lod.Medium;
        if (distance <= LOW_DISTANCE * scale) return Lod.Low;
        return Lod.Skipped;
    }

    /**
     * The whole question in one call: visible, in range, and worth drawing. Counts itself either way, so
     * the debug overlay can show how much work is being skipped.
     */
    public static @NotNull Lod evaluate(@NotNull Vec3d centre, double radius, double maxDistance) {
        double distance = distanceTo(centre);

        if (distance > maxDistance || !isVisible(centre, radius)) {
            CloudanRenderCore.countCulled(1);
            return Lod.Skipped;
        }

        Lod lod = lod(distance);
        if (lod.drawable()) CloudanRenderCore.countVisible(1);
        else CloudanRenderCore.countCulled(1);
        return lod;
    }
}
