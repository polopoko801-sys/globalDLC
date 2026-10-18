package thunder.hack.features.modules.render;

import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import thunder.hack.features.modules.Module;
import thunder.hack.setting.Setting;
import thunder.hack.setting.impl.ColorSetting;
import thunder.hack.utility.render.Render2DEngine;
import thunder.hack.utility.render.core.CloudanAnimationEngine;
import thunder.hack.utility.render.core.CloudanCullingSystem;
import thunder.hack.utility.render.core.CloudanGlowRenderer;
import thunder.hack.utility.render.core.CloudanRenderCore;

import java.awt.Color;

/**
 * Rings under you when there is nothing below you.
 *
 * <p>A warning you read without looking down. The rings appear once the drop under your feet passes a
 * threshold and tighten as it gets worse - more layers, faster pulse, stronger colour - so the state is
 * legible from the corner of your eye rather than needing a number.
 *
 * <p>The drop is measured by walking down from your feet to the bottom of the world, which is a column
 * of block lookups. That is done a few times a second and cached, not per frame: the floor under you
 * does not change sixty times a second, and the same answer costs the same either way.
 */
public class VoidRings extends Module {
    public VoidRings() {
        super("VoidRings", Category.RENDER);
    }

    /** Below this much clear air the rings start showing at all. */
    private final Setting<Integer> triggerDrop = new Setting<>("TriggerDrop", 6, 2, 64);
    /** The drop at which the effect is at full strength. */
    private final Setting<Integer> criticalDrop = new Setting<>("CriticalDrop", 25, 4, 128);
    private final Setting<Float> radius = new Setting<>("Radius", 1.6f, 0.4f, 6f);
    private final Setting<Integer> layers = new Setting<>("Layers", 3, 1, 5);
    private final Setting<ColorSetting> safeColor = new Setting<>("NearColor", new ColorSetting(0xFFFFC65A));
    private final Setting<ColorSetting> dangerColor = new Setting<>("VoidColor", new ColorSetting(0xFFFF566E));
    private final Setting<Integer> opacity = new Setting<>("Opacity", 170, 20, 255);
    private final Setting<Boolean> glow = new Setting<>("Glow", true);
    private final Setting<Float> animationSpeed = new Setting<>("AnimationSpeed", 1f, 0.1f, 4f);
    private final Setting<Boolean> throughWalls = new Setting<>("ThroughWalls", true);

    /** Cached drop, and when it was measured. */
    private int drop;
    private long measuredAt;

    private static final long REMEASURE_MS = 250;

    @Override
    public void onRender3D(MatrixStack matrices) {
        if (fullNullCheck()) return;

        measure();
        if (drop < triggerDrop.getValue()) return;

        // 0 at the trigger, 1 at the critical depth. Everything below scales off this one number.
        float severity = Math.min(1f, (drop - triggerDrop.getValue())
                / (float) Math.max(1, criticalDrop.getValue() - triggerDrop.getValue()));

        Vec3d feet = CloudanRenderCore.interpolated(mc.player).add(0, 0.02, 0);
        if (!CloudanCullingSystem.isVisible(feet, radius.getValue() * 2)) return;

        Color tint = blend(safeColor.getValue().getColorObject(), dangerColor.getValue().getColorObject(), severity);
        Color shown = Render2DEngine.injectAlpha(tint, opacity.getValue());

        // The pulse quickens as it gets worse - urgency without shouting.
        float period = 2.6f / (animationSpeed.getValue() * (1f + severity));
        float pulse = CloudanAnimationEngine.pulse(period, 0.10f + severity * 0.06f);

        // More layers the closer you are to falling, up to the configured cap.
        int shownLayers = Math.max(1, Math.round(layers.getValue() * (0.4f + severity * 0.6f)));

        for (int i = 0; i < shownLayers; i++) {
            float t = shownLayers == 1 ? 0f : i / (float) (shownLayers - 1);
            double r = radius.getValue() * pulse * (1.0 + t * 0.55);
            // Outer layers are fainter, which is what gives the stack depth instead of a flat disc.
            float intensity = (1f - t * 0.55f) * (0.45f + severity * 0.55f);
            double spin = CloudanAnimationEngine.spin((9f + i * 4f) / animationSpeed.getValue()) * (i % 2 == 0 ? 1 : -1);

            CloudanGlowRenderer.ring(feet, r, spin, shown, intensity, throughWalls.getValue());
        }

        if (glow.getValue())
            CloudanGlowRenderer.halo(feet, radius.getValue() * 1.4 * pulse, shown,
                    0.22f + severity * 0.25f, throughWalls.getValue());
    }

    /** Walks the column under the player looking for the first solid block. */
    private void measure() {
        long now = System.currentTimeMillis();
        if (now - measuredAt < REMEASURE_MS) return;
        measuredAt = now;

        BlockPos.Mutable cursor = mc.player.getBlockPos().mutableCopy();
        int bottom = mc.world.getBottomY();
        int start = cursor.getY();

        while (cursor.getY() > bottom) {
            cursor.setY(cursor.getY() - 1);
            if (!mc.world.getBlockState(cursor).isAir()) {
                drop = start - cursor.getY();
                return;
            }
        }

        // Nothing at all underneath: the drop is the whole way down.
        drop = start - bottom;
    }

    private static Color blend(Color from, Color to, float t) {
        float c = Math.max(0f, Math.min(1f, t));
        return new Color(
                Math.round(from.getRed() + (to.getRed() - from.getRed()) * c),
                Math.round(from.getGreen() + (to.getGreen() - from.getGreen()) * c),
                Math.round(from.getBlue() + (to.getBlue() - from.getBlue()) * c),
                255);
    }

    @Override
    public String getDisplayInfo() {
        return drop >= triggerDrop.getValue() ? drop + "" : null;
    }
}
