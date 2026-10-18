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
import thunder.hack.utility.render.animation.Easing;
import thunder.hack.utility.render.core.CloudanCullingSystem;
import thunder.hack.utility.render.core.CloudanGlowRenderer;
import thunder.hack.utility.render.core.CloudanRenderCore;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * A ring that blows outwards from a hit.
 *
 * <p>Fired on the POST attack event, which is the one that carries the target - the pre event is posted
 * from the click handler with a null entity and says only that the button went down.
 *
 * <p>It expands on an ease-out: fast at the start, settling at the end. Constant expansion reads as a
 * growing circle; the deceleration is what reads as an impact.
 *
 * <p>Cosmetic only: nothing here touches combat, damage or the world.
 */
public class DamageShockwave extends Module {
    public DamageShockwave() {
        super("DamageShockwave", Category.RENDER);
    }

    private final Setting<Float> radius = new Setting<>("Radius", 2.2f, 0.4f, 8f);
    private final Setting<Integer> duration = new Setting<>("Duration", 520, 120, 2500);
    private final Setting<Mode> colorMode = new Setting<>("ColorMode", Mode.Sync);
    private final Setting<ColorSetting> color = new Setting<>("Color", new ColorSetting(0xFFFF5C5C), v -> colorMode.getValue() == Mode.Custom);
    private final Setting<Integer> opacity = new Setting<>("Opacity", 210, 20, 255);
    /** Rings from hits you take as well as hits you land. */
    private final Setting<Boolean> onSelfHurt = new Setting<>("OnHurt", true);
    private final Setting<Boolean> atFeet = new Setting<>("AtFeet", false);
    private final Setting<Boolean> throughWalls = new Setting<>("ThroughWalls", false);
    private final Setting<Integer> max = new Setting<>("Max", 6, 1, 20);

    private enum Mode {
        Custom, Sync
    }

    private record Wave(Vec3d pos, long born) {
    }

    private final List<Wave> waves = new ArrayList<>();
    private float lastHealth = Float.NaN;

    @Override
    public void onEnable() {
        waves.clear();
        lastHealth = Float.NaN;
    }

    @Override
    public void onDisable() {
        waves.clear();
    }

    @EventHandler
    public void onAttack(EventAttack event) {
        // POST carries the target; PRE is posted with a null entity and only says the button went down.
        if (event.isPre() || fullNullCheck()) return;

        Entity target = event.getEntity();
        if (target == null) return;

        add(atFeet.getValue()
                ? target.getEntityPos().add(0, 0.05, 0)
                : target.getBoundingBox().getCenter());
    }

    @Override
    public void onUpdate() {
        if (fullNullCheck() || !onSelfHurt.getValue()) return;

        float health = mc.player.getHealth();
        if (!Float.isNaN(lastHealth) && health < lastHealth - 0.01f)
            add(mc.player.getEntityPos().add(0, 0.05, 0));

        lastHealth = health;
    }

    private void add(Vec3d at) {
        waves.add(new Wave(at, System.currentTimeMillis()));
        while (waves.size() > max.getValue()) waves.removeFirst();
    }

    @Override
    public void onRender3D(MatrixStack matrices) {
        if (fullNullCheck() || waves.isEmpty()) return;

        long now = System.currentTimeMillis();
        Color base = colorMode.getValue() == Mode.Sync
                ? HudEditor.getColor(0)
                : color.getValue().getColorObject();

        for (Iterator<Wave> it = waves.iterator(); it.hasNext(); ) {
            Wave wave = it.next();

            float t = (now - wave.born()) / (float) duration.getValue();
            if (t >= 1f) {
                it.remove();
                continue;
            }

            if (!CloudanCullingSystem.isVisible(wave.pos(), radius.getValue() * 2)) continue;

            // Out fast, settling slow. Constant expansion is a growing circle; the deceleration is what
            // reads as something having hit.
            float eased = Easing.CubicOut.applyClamped(t);
            double r = radius.getValue() * (0.2 + eased * 1.3);

            CloudanGlowRenderer.shockwave(wave.pos(), r, 0,
                    Render2DEngine.injectAlpha(base, opacity.getValue()),
                    (1f - t) * 0.95f, throughWalls.getValue());
        }
    }

    @Override
    public String getDisplayInfo() {
        return waves.isEmpty() ? null : waves.size() + "";
    }
}
