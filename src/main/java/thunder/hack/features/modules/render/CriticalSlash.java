package thunder.hack.features.modules.render;

import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.Vec3d;
import thunder.hack.events.impl.EventAttack;
import thunder.hack.features.modules.Module;
import thunder.hack.features.modules.client.HudEditor;
import thunder.hack.setting.Setting;
import thunder.hack.setting.impl.ColorSetting;
import thunder.hack.utility.render.Render2DEngine;
import thunder.hack.utility.render.Render3DEngine;
import thunder.hack.utility.render.TextureStorage;
import thunder.hack.utility.render.animation.Easing;
import thunder.hack.utility.render.core.CloudanCullingSystem;
import thunder.hack.utility.render.core.CloudanRenderCore;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * A slash drawn across whatever you just crit.
 *
 * <p>Two crossed streaks rather than one, at an angle to each other, because a single stroke across a
 * body reads as a scratch and a cross reads as a hit. They are drawn at a fixed angle in the target's
 * space and turned to face the camera, so the mark stays on the target rather than swinging round it.
 *
 * <p>Crits only by default: firing on every swing makes the effect meaningless within a minute, and the
 * whole value of it is knowing which hits were the big ones.
 *
 * <p>Cosmetic only: nothing here touches combat, damage or the world.
 */
public class CriticalSlash extends Module {
    public CriticalSlash() {
        super("CriticalSlash", Category.RENDER);
    }

    /** Only on crits. Off fires on every hit. */
    private final Setting<Boolean> onlyCrits = new Setting<>("OnlyCrits", true);
    private final Setting<Float> size = new Setting<>("Size", 1.4f, 0.3f, 4f);
    private final Setting<Integer> duration = new Setting<>("Duration", 380, 100, 2000);
    /** Angle between the two strokes, in degrees. */
    private final Setting<Float> cross = new Setting<>("Cross", 55f, 0f, 90f);
    private final Setting<Mode> colorMode = new Setting<>("ColorMode", Mode.Sync);
    private final Setting<ColorSetting> color = new Setting<>("Color", new ColorSetting(0xFFFFFFFF), v -> colorMode.getValue() == Mode.Custom);
    private final Setting<Integer> opacity = new Setting<>("Opacity", 235, 20, 255);
    private final Setting<Boolean> throughWalls = new Setting<>("ThroughWalls", true);
    private final Setting<Integer> max = new Setting<>("Max", 6, 1, 20);

    private enum Mode {
        Custom, Sync
    }

    /** One mark: where it is, which way the first stroke runs, and when it was made. */
    private record Slash(Vec3d pos, float lean, long born) {
    }

    private final List<Slash> slashes = new ArrayList<>();

    @Override
    public void onEnable() {
        slashes.clear();
    }

    @Override
    public void onDisable() {
        slashes.clear();
    }

    @EventHandler
    public void onAttack(EventAttack event) {
        // POST carries the target; PRE is posted with a null entity and only says the button went down.
        if (event.isPre() || fullNullCheck()) return;

        Entity target = event.getEntity();
        if (target == null) return;

        // The vanilla crit conditions: falling, not on the ground, not climbing, not in water, no blind.
        if (onlyCrits.getValue() && !isCrit()) return;

        // Leaning a random way each time stops a run of hits stamping the identical mark over itself.
        float lean = -40f + mc.world.random.nextFloat() * 80f;

        slashes.add(new Slash(target.getBoundingBox().getCenter(), lean, System.currentTimeMillis()));
        while (slashes.size() > max.getValue()) slashes.removeFirst();
    }

    private boolean isCrit() {
        return mc.player.fallDistance > 0f
                && !mc.player.isOnGround()
                && !mc.player.isClimbing()
                && !mc.player.isTouchingWater()
                && !mc.player.hasStatusEffect(net.minecraft.entity.effect.StatusEffects.BLINDNESS)
                && !mc.player.hasVehicle();
    }

    @Override
    public void onRender3D(MatrixStack matrices) {
        if (fullNullCheck() || slashes.isEmpty()) return;

        long now = System.currentTimeMillis();
        float cameraYaw = CloudanRenderCore.cameraYaw();

        Color base = colorMode.getValue() == Mode.Sync
                ? HudEditor.getColor(0)
                : color.getValue().getColorObject();

        for (Iterator<Slash> it = slashes.iterator(); it.hasNext(); ) {
            Slash slash = it.next();

            float t = (now - slash.born()) / (float) duration.getValue();
            if (t >= 1f) {
                it.remove();
                continue;
            }

            if (!CloudanCullingSystem.isVisible(slash.pos(), size.getValue())) continue;

            // Snaps to full length immediately and then fades - a slash that grows in looks like a
            // wipe, and a hit does not wipe.
            float fade = 1f - Easing.CubicIn.applyClamped(t);
            double length = size.getValue() * (0.9 + 0.25 * Easing.CubicOut.applyClamped(t));

            Color tint = Render2DEngine.injectAlpha(base, Math.round(opacity.getValue() * fade));

            stroke(slash.pos(), cameraYaw, slash.lean(), length, tint);
            if (cross.getValue() > 0.1f)
                stroke(slash.pos(), cameraYaw, slash.lean() + cross.getValue(), length * 0.85, tint);
        }
    }

    private void stroke(Vec3d at, float cameraYaw, float roll, double length, Color tint) {
        Render3DEngine.drawOrientedSprite3D(TextureStorage.fxStreak, at,
                -cameraYaw, 0f, roll,
                -length / 2, length, -length * 0.16,
                tint, false, throughWalls.getValue());
    }

    @Override
    public String getDisplayInfo() {
        return slashes.isEmpty() ? null : slashes.size() + "";
    }
}
