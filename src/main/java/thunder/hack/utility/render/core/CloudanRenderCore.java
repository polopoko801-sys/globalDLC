package thunder.hack.utility.render.core;

import net.minecraft.client.render.Camera;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.Vec3d;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import thunder.hack.features.modules.client.ClientSettings;
import thunder.hack.utility.render.Render3DEngine;

import static thunder.hack.features.modules.Module.mc;

/**
 * The one place a Cloudan visual starts and ends.
 *
 * <p>Every visual used to work out for itself where the camera was, how far away things were, whether to
 * bother drawing them and when to flush. That is how a client ends up with ten renderers that disagree
 * about the same frame. This owns that: it opens the frame, hands out the camera and the view-projection
 * matrix everything else needs, keeps the counters the debug overlay reads, and closes the frame by
 * flushing every batch so nothing is left half-submitted for the next one.
 *
 * <p>It deliberately does NOT own the low-level drawing. {@code BlockModelRender}, {@code Render3DUtil}
 * and {@code Render3DEngine} already do that correctly on 1.21.11 and are used by a lot of working code;
 * the core sits on top of them rather than replacing them with a third way to draw a box.
 *
 * <h2>Render state</h2>
 * Nothing here touches the global GL state - no depth mask, no blend func, no shader binding. On 1.21.6+
 * that state lives inside the {@link net.minecraft.client.render.RenderLayer} pipelines, so the only way
 * to leak it is to leave a buffer unflushed, which {@link #endFrame3D()} exists to prevent.
 */
public final class CloudanRenderCore {
    private CloudanRenderCore() {
    }

    /* ------------------------------------------------------------------ */
    /* Frame state                                                         */
    /* ------------------------------------------------------------------ */

    private static final Matrix4f VIEW_PROJECTION = new Matrix4f();
    private static Vec3d cameraPos = Vec3d.ZERO;
    private static float cameraYaw, cameraPitch;
    private static boolean inFrame;

    private static long frameStartNanos;
    private static long lastRenderNanos;

    /** Counters for the current frame; swapped into {@link #stats} when the frame closes. */
    private static final Stats building = new Stats();
    private static Stats stats = new Stats();

    /**
     * Opens a 3D frame. Called once per frame from the world render hook, BEFORE any module draws.
     *
     * @param matrices the stack the hook built - it already carries the camera rotation exactly once
     */
    public static void beginFrame3D(@NotNull MatrixStack matrices) {
        GpuCapabilities.probe();
        FrameBudget.frame();
        frameStartNanos = System.nanoTime();
        inFrame = true;

        // Clear any ColorModulator left over from the previous frame's hand render (Chams HandItems / ItemShader
        // set it and - deliberately - do not reset it before the deferred flush). Without this the leftover colour
        // tints world sprites drawn this frame (Wings, ESP, trails), which is the "Wings nie działa" regression.
        thunder.hack.utility.render.RSCompat.setShaderColor(1f, 1f, 1f, 1f);

        Camera camera = mc.gameRenderer.getCamera();
        cameraPos = camera.getCameraPos();
        cameraYaw = camera.getYaw();
        cameraPitch = camera.getPitch();

        // The FULL transform a point goes through, multiplied once here so culling, text and sprites all
        // measure against identical numbers instead of each rebuilding it.
        //
        // The world-space matrix is the part that must not be left out. On 1.21.11 the live modelview is
        // identity and the camera rotation lives in `lastWorldSpaceMatrix` instead, which is exactly what
        // worldSpaceToScreenSpace multiplies a camera-relative point by before projecting it. Building
        // the frustum from projection alone describes a box pointing down the world's +Z rather than
        // down the camera, so almost everything in front of you tests as off-screen and gets culled.
        VIEW_PROJECTION.set(Render3DEngine.lastProjMat)
                .mul(Render3DEngine.lastModMat)
                .mul(Render3DEngine.lastWorldSpaceMatrix);

        building.reset();
        CloudanCullingSystem.update(VIEW_PROJECTION, cameraPos);
        CloudanBatchRenderer.begin();
    }

    /**
     * Closes the frame: everything still buffered is pushed and the counters are published.
     *
     * <p>Flushing here rather than in each visual is the whole point - a visual that returns early (out of
     * range, culled, an exception) can no longer strand a half-filled buffer that bleeds into the next
     * frame as geometry in the wrong place.
     */
    public static void endFrame3D() {
        if (!inFrame) return;

        // Particles queue into the batch, so they must be submitted BEFORE it is flushed. In the other order
        // they were added after the flush and wiped by the next frame's begin() - never drawn at all.
        CloudanParticleRenderer.render();
        CloudanBatchRenderer.flushWorld();
        CloudanModelRenderer.flush();

        lastRenderNanos = System.nanoTime() - frameStartNanos;
        building.renderNanos = lastRenderNanos;
        building.particles = CloudanParticleRenderer.alive();
        stats = building.copy();
        inFrame = false;
    }

    /** Called from the 2D pass once every screen-space batch has been submitted. */
    public static void endFrame2D(net.minecraft.client.gui.DrawContext context) {
        CloudanBatchRenderer.flushScreen(context);
    }

    /* ------------------------------------------------------------------ */
    /* What visuals ask for                                                */
    /* ------------------------------------------------------------------ */

    public static @NotNull Vec3d cameraPos() {
        return cameraPos;
    }

    public static float cameraYaw() {
        return cameraYaw;
    }

    public static float cameraPitch() {
        return cameraPitch;
    }

    public static @NotNull Matrix4f viewProjection() {
        return VIEW_PROJECTION;
    }

    /** Partial tick, for interpolating anything that moves between ticks. */
    public static float tickDelta() {
        return Render3DEngine.getTickDelta();
    }

    /** Interpolated position of an entity - never its raw one, which steps 20 times a second. */
    public static @NotNull Vec3d interpolated(@Nullable net.minecraft.entity.Entity entity) {
        if (entity == null) return Vec3d.ZERO;
        float d = tickDelta();
        return new Vec3d(
                entity.lastX + (entity.getX() - entity.lastX) * d,
                entity.lastY + (entity.getY() - entity.lastY) * d,
                entity.lastZ + (entity.getZ() - entity.lastZ) * d);
    }

    /* ------------------------------------------------------------------ */
    /* Quality                                                             */
    /* ------------------------------------------------------------------ */

    public static ClientSettings.Quality quality() {
        return ClientSettings.quality.getValue();
    }

    /** Multiplier a visual applies to its own budget. 1.0 is the tuned default (High). */
    public static float qualityScale() {
        return ClientSettings.qualityScale();
    }

    /** Scales a per-frame budget by the quality dial, never below 1. */
    public static int budget(int base) {
        return ClientSettings.budget(base);
    }

    /** True when effects should be kept still and cheap - ReducedMotion, or the Low preset. */
    public static boolean reduceEffects() {
        return ClientSettings.reduceEffects();
    }

    /** How many layers a glow may stack at the current quality. */
    public static int glowLayers() {
        return switch (quality()) {
            case Low -> 1;
            case Medium -> 1;
            case High -> 2;
            case Ultra -> 3;
        };
    }

    /* ------------------------------------------------------------------ */
    /* Counters                                                            */
    /* ------------------------------------------------------------------ */

    public static void countDrawCall() {
        building.drawCalls++;
    }

    public static void countVisible(int n) {
        building.visibleObjects += n;
    }

    public static void countCulled(int n) {
        building.culled += n;
    }

    static void countCacheHit() {
        building.cacheHits++;
    }

    static void countCacheMiss() {
        building.cacheMisses++;
    }

    public static @NotNull Stats stats() {
        return stats;
    }

    public static float renderMillis() {
        return lastRenderNanos / 1_000_000f;
    }

    /** One frame's worth of numbers. Plain mutable fields on purpose - this is touched per draw call. */
    public static final class Stats {
        public int drawCalls;
        public int visibleObjects;
        public int culled;
        public int particles;
        public int cacheHits;
        public int cacheMisses;
        public long renderNanos;

        void reset() {
            drawCalls = 0;
            visibleObjects = 0;
            culled = 0;
            particles = 0;
            cacheHits = 0;
            cacheMisses = 0;
            renderNanos = 0;
        }

        Stats copy() {
            Stats s = new Stats();
            s.drawCalls = drawCalls;
            s.visibleObjects = visibleObjects;
            s.culled = culled;
            s.particles = particles;
            s.cacheHits = cacheHits;
            s.cacheMisses = cacheMisses;
            s.renderNanos = renderNanos;
            return s;
        }
    }

    /* ------------------------------------------------------------------ */
    /* Lifecycle                                                           */
    /* ------------------------------------------------------------------ */

    /**
     * Drops everything tied to the world that just went away: caches full of block states, particles
     * sitting at coordinates that no longer mean anything, half-built batches.
     */
    public static void onWorldChange() {
        CloudanRenderCache.clearAll();
        CloudanParticleRenderer.clear();
        CloudanBatchRenderer.clear();
        CloudanModelRenderer.flush();
        inFrame = false;
    }
}
