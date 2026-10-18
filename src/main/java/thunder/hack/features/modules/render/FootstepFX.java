package thunder.hack.features.modules.render;

import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.Vec3d;
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
 * A glowing print left where each foot lands.
 *
 * <p>Left and right alternate and sit to either side of your path, rather than one mark in the middle.
 * That offset is the whole thing: a single line of marks down the centre reads as a trail, and a pair of
 * staggered ones reads as footsteps, which is a different effect entirely.
 *
 * <p>A print is only left when a foot actually lands - measured by distance walked, not by time - so
 * standing still leaves nothing and sprinting spaces them further apart, the way a stride does.
 *
 * <p>Cosmetic only: nothing here touches movement, collision or the world.
 */
public class FootstepFX extends Module {
    public FootstepFX() {
        super("FootstepFX", Category.RENDER);
    }

    /** Distance between one step and the next, in blocks. */
    private final Setting<Float> stride = new Setting<>("Stride", 0.9f, 0.3f, 3f);
    /** How far to either side of your path a print sits. */
    private final Setting<Float> spread = new Setting<>("Spread", 0.18f, 0f, 0.8f);
    private final Setting<Float> size = new Setting<>("Size", 0.32f, 0.1f, 1.2f);
    private final Setting<Integer> duration = new Setting<>("Duration", 2200, 300, 10000);
    private final Setting<Integer> max = new Setting<>("Max", 24, 2, 80);
    private final Setting<Mode> colorMode = new Setting<>("ColorMode", Mode.Sync);
    private final Setting<ColorSetting> color = new Setting<>("Color", new ColorSetting(0xFF9B5CFF), v -> colorMode.getValue() == Mode.Custom);
    private final Setting<Integer> opacity = new Setting<>("Opacity", 190, 20, 255);
    /** Only while you are actually on the ground - a print in mid air is not a footstep. */
    private final Setting<Boolean> groundOnly = new Setting<>("GroundOnly", true);
    private final Setting<Boolean> throughWalls = new Setting<>("ThroughWalls", false);
    private final Setting<Integer> renderDistance = new Setting<>("RenderDistance", 48, 8, 128);

    private enum Mode {
        Custom, Sync
    }

    /** One print: where it is, which way it points, and when it was left. */
    private record Print(Vec3d pos, float yaw, long born) {
    }

    private final List<Print> prints = new ArrayList<>();
    private Vec3d lastStep;
    /** Which foot is next. Alternating is what makes it a walk rather than a line of dots. */
    private boolean rightFoot;

    @Override
    public void onEnable() {
        prints.clear();
        lastStep = null;
    }

    @Override
    public void onDisable() {
        prints.clear();
    }

    @Override
    public void onUpdate() {
        if (fullNullCheck()) return;
        if (groundOnly.getValue() && !mc.player.isOnGround()) return;

        Vec3d feet = new Vec3d(mc.player.getX(), mc.player.getBoundingBox().minY + 0.02, mc.player.getZ());

        if (lastStep != null && lastStep.squaredDistanceTo(feet) < stride.getValue() * stride.getValue())
            return;

        lastStep = feet;
        rightFoot = !rightFoot;

        // Offset sideways from the direction of travel, so the pair straddles the path.
        float yaw = mc.player.bodyYaw;
        double side = Math.toRadians(yaw + 90);
        double offset = spread.getValue() * (rightFoot ? 1 : -1);

        prints.add(new Print(feet.add(Math.cos(side) * offset, 0, Math.sin(side) * offset),
                yaw, System.currentTimeMillis()));

        while (prints.size() > max.getValue()) prints.removeFirst();
    }

    @Override
    public void onRender3D(MatrixStack matrices) {
        if (fullNullCheck() || prints.isEmpty()) return;

        long now = System.currentTimeMillis();
        Color base = colorMode.getValue() == Mode.Sync
                ? HudEditor.getColor(0)
                : color.getValue().getColorObject();

        for (Iterator<Print> it = prints.iterator(); it.hasNext(); ) {
            Print print = it.next();

            float t = (now - print.born()) / (float) duration.getValue();
            if (t >= 1f) {
                it.remove();
                continue;
            }

            if (!CloudanCullingSystem.inRange(print.pos(), renderDistance.getValue())) continue;
            if (!CloudanCullingSystem.isVisible(print.pos(), size.getValue() * 2)) continue;

            // Holds its brightness for most of its life and then goes quickly - a print that starts
            // fading immediately never looks like it was there.
            float fade = 1f - Easing.CubicIn.applyClamped(t);

            Render3DEngine.drawGroundSprite3D(TextureStorage.fxSpark, print.pos(),
                    size.getValue(), Math.toRadians(print.yaw()),
                    Render2DEngine.injectAlpha(base, Math.round(opacity.getValue() * fade)),
                    throughWalls.getValue());
        }
    }

    @Override
    public String getDisplayInfo() {
        return prints.isEmpty() ? null : prints.size() + "";
    }
}
