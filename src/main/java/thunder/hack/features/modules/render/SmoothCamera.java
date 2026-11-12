package thunder.hack.features.modules.render;

import net.minecraft.client.world.ClientWorld;
import thunder.hack.features.modules.Module;
import thunder.hack.setting.Setting;

/**
 * BKClient's Smooth Camera ({@code systems/modules/modules/visuals/SmoothCamera}, MIT, bkubiakdev), 1:1: smooths the
 * camera position (not the view direction), so walking, sprinting and stepping do not jerk the view. Time based (ms),
 * so it feels the same at any frame rate, and never more than Max Offset blocks behind the real position. A
 * teleport, respawn, dimension change or a long pause snaps it back at once.
 */
public final class SmoothCamera extends Module {
    public SmoothCamera() {
        super("SmoothCamera", Category.RENDER);
        instance = this;
    }

    /** A real position change between two camera updates above this many blocks is a teleport, not walking. */
    private static final double TELEPORT_BLOCKS = 3.0;
    private static final double PAUSE_SECONDS = 0.5;

    private static SmoothCamera instance;

    private final Setting<Float> smoothness = new Setting<>("Smoothness", 80f, 0f, 300f);
    private final Setting<Float> verticalSmoothness = new Setting<>("VerticalSmoothness", 120f, 0f, 300f);
    private final Setting<Float> maxOffset = new Setting<>("MaxOffset", 1.0f, 0.2f, 3.0f);
    private final Setting<Boolean> firstPerson = new Setting<>("EnableFirstPOV", false);
    private final Setting<Boolean> resetOnPerspective = new Setting<>("ResetOnPerspective", true);

    private double x, y, z;
    private double realX, realY, realZ;
    private boolean ready;
    private boolean lastThirdPerson;
    private boolean seenPerspective;
    private long lastNanos;
    private ClientWorld lastWorld;

    public static SmoothCamera getInstance() {
        return instance;
    }

    @Override
    public void onDisable() {
        ready = false;
        seenPerspective = false;
        lastNanos = 0L;
        lastWorld = null;
    }

    /** The smoothed camera position for the real one; called for every camera update while enabled. */
    public double[] apply(double realX, double realY, double realZ, boolean thirdPerson, ClientWorld world) {
        long now = System.nanoTime();
        double dt = lastNanos == 0L ? 0.0 : (now - lastNanos) / 1.0E9;
        lastNanos = now;

        boolean perspectiveChanged = seenPerspective && lastThirdPerson != thirdPerson;
        lastThirdPerson = thirdPerson;
        seenPerspective = true;

        double jump = Math.sqrt(square(realX - this.realX) + square(realY - this.realY) + square(realZ - this.realZ));
        this.realX = realX;
        this.realY = realY;
        this.realZ = realZ;
        boolean newWorld = world != lastWorld;
        lastWorld = world;

        if (!ready || newWorld || jump > TELEPORT_BLOCKS || dt > PAUSE_SECONDS
                || (perspectiveChanged && resetOnPerspective.getValue())
                || (!thirdPerson && !firstPerson.getValue())) {
            snap(realX, realY, realZ);
            return new double[]{x, y, z};
        }

        x += (realX - x) * blend(dt, smoothness.getValue());
        z += (realZ - z) * blend(dt, smoothness.getValue());
        y += (realY - y) * blend(dt, verticalSmoothness.getValue());
        limitOffset(realX, realY, realZ);
        return new double[]{x, y, z};
    }

    /** Share of the remaining distance covered in this frame for a smoothing time constant in milliseconds. */
    private double blend(double dt, double millis) {
        if (millis <= 0.0) return 1.0;
        return 1.0 - Math.exp(-Math.max(0.0, dt) / (millis / 1000.0));
    }

    /** The smoothed position may lag behind, but never further than the chosen distance from the real one. */
    private void limitOffset(double realX, double realY, double realZ) {
        double dx = x - realX, dy = y - realY, dz = z - realZ;
        double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
        double limit = maxOffset.getValue();
        if (distance > limit && distance > 1.0E-6) {
            double scale = limit / distance;
            x = realX + dx * scale;
            y = realY + dy * scale;
            z = realZ + dz * scale;
        }
    }

    private void snap(double realX, double realY, double realZ) {
        x = realX;
        y = realY;
        z = realZ;
        ready = true;
    }

    private static double square(double value) {
        return value * value;
    }
}
