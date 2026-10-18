package thunder.hack.features.modules.render;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import thunder.hack.features.modules.Module;
import thunder.hack.setting.Setting;
import thunder.hack.setting.impl.ColorSetting;
import thunder.hack.utility.render.Render3DEngine;

import java.awt.Color;

/**
 * Animated stick-figure skeleton over living entities. 1.21.11: drawn via SCREEN PROJECTION
 * ({@link Render3DEngine#drawLine2D}) exactly like the working entity ESP — the 3D line queue
 * double-rotates on this version, which made the skeleton slide off the entity as the camera turned.
 */
public final class Skeleton extends Module {
    private final Setting<Boolean> players = new Setting<>("Players", true);
    private final Setting<Boolean> mobs = new Setting<>("Mobs", false);
    private final Setting<Boolean> self = new Setting<>("Self", false);
    private final Setting<Float> rangeSetting = new Setting<>("Range", 48f, 4f, 128f);
    private final Setting<Boolean> rainbow = new Setting<>("Rainbow", false);
    private final Setting<ColorSetting> color = new Setting<>("Color", new ColorSetting(new Color(233, 234, 240)), v -> !rainbow.getValue());
    private final Setting<Boolean> friendColor = new Setting<>("FriendColor", true);
    private final Setting<ColorSetting> friendColorValue = new Setting<>("FriendColorValue", new ColorSetting(new Color(0x30FF00)), v -> friendColor.getValue());

    public Skeleton() {
        super("Skeleton", Category.RENDER);
    }

    public void onRender2D(DrawContext context) {
        if (mc.world == null || mc.player == null) return;

        for (Entity e : mc.world.getEntities()) {
            if (!(e instanceof LivingEntity le)) continue;
            if (le == mc.player && !self.getValue()) continue;
            if (le instanceof PlayerEntity) {
                if (!players.getValue()) continue;
            } else if (!mobs.getValue()) continue;
            if (!le.isAlive() || mc.player.distanceTo(le) > rangeSetting.getValue()) continue;

            drawSkeleton(context, le);
        }
    }

    private void drawSkeleton(DrawContext context, LivingEntity e) {
        float td = Render3DEngine.getTickDelta();
        double x = MathHelper.lerp(td, e.lastX, e.getX());
        double y = MathHelper.lerp(td, e.lastY, e.getY());
        double z = MathHelper.lerp(td, e.lastZ, e.getZ());

        double yr = Math.toRadians(e.getYaw());
        double rx = -Math.cos(yr), rz = -Math.sin(yr);   // right (perpendicular to facing)

        double h = e.getHeight();
        double w = Math.max(0.3, e.getWidth());
        double sh = w * 0.55;   // shoulder half-width
        double hw = w * 0.28;   // hip half-width

        double moveAmt = Math.min(1.0, Math.hypot(e.getVelocity().x, e.getVelocity().z) * 4.5);
        double swing = Math.sin(System.currentTimeMillis() / 120.0) * moveAmt * (h * 0.30);
        double fx = -Math.sin(yr), fz = Math.cos(yr);    // facing (horizontal)

        Vec3d feet = new Vec3d(x, y, z);
        Vec3d hip = new Vec3d(x, y + h * 0.5, z);
        Vec3d neck = new Vec3d(x, y + h * 0.78, z);
        Vec3d head = new Vec3d(x, y + h, z);

        Vec3d lSh = neck.add(rx * sh, 0, rz * sh);
        Vec3d rSh = neck.add(-rx * sh, 0, -rz * sh);
        Vec3d lHip = hip.add(rx * hw, 0, rz * hw);
        Vec3d rHip = hip.add(-rx * hw, 0, -rz * hw);

        Vec3d lHand = lSh.add(fx * swing, -h * 0.42, fz * swing);
        Vec3d rHand = rSh.add(-fx * swing, -h * 0.42, -fz * swing);
        Vec3d lFoot = feet.add(rx * hw - fx * swing, 0, rz * hw - fz * swing);
        Vec3d rFoot = feet.add(-rx * hw + fx * swing, 0, -rz * hw + fz * swing);

        Color c;
        if (friendColor.getValue() && e instanceof PlayerEntity && thunder.hack.core.Managers.FRIEND.isFriend((PlayerEntity) e))
            c = friendColorValue.getValue().getColorObject();   // friend zawsze na zielono
        else if (rainbow.getValue())
            c = Color.getHSBColor((System.currentTimeMillis() % 3600L) / 3600f, 0.7f, 1f);
        else
            c = color.getValue().getColorObject();

        Render3DEngine.drawLine2D(context, neck, head, c);   // neck -> head
        Render3DEngine.drawLine2D(context, neck, hip, c);    // spine
        Render3DEngine.drawLine2D(context, lSh, rSh, c);     // shoulders
        Render3DEngine.drawLine2D(context, lHip, rHip, c);   // pelvis
        Render3DEngine.drawLine2D(context, lSh, lHand, c);   // left arm
        Render3DEngine.drawLine2D(context, rSh, rHand, c);   // right arm
        Render3DEngine.drawLine2D(context, lHip, lFoot, c);  // left leg
        Render3DEngine.drawLine2D(context, rHip, rFoot, c);  // right leg
    }
}
