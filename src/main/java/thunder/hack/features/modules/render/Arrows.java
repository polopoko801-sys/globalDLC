package thunder.hack.features.modules.render;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.MathHelper;
import thunder.hack.core.Managers;
import thunder.hack.features.modules.Module;
import thunder.hack.setting.Setting;
import thunder.hack.setting.impl.ColorSetting;
import thunder.hack.utility.render.Render2DEngine;

import java.awt.Color;

/**
 * Draws a small arrow around the crosshair for every nearby player, pointing in the direction they
 * are relative to your view — so you can tell where enemies are even off-screen / behind you.
 */
public final class Arrows extends Module {
    private final Setting<Float> range = new Setting<>("Range", 60f, 5f, 200f);
    private final Setting<Float> radius = new Setting<>("Radius", 26f, 8f, 120f);
    private final Setting<Float> size = new Setting<>("Size", 9f, 3f, 24f);
    private final Setting<Boolean> ignoreFov = new Setting<>("AlwaysShow", true);
    private final Setting<Boolean> distanceColor = new Setting<>("DistanceColor", true);
    private final Setting<ColorSetting> color = new Setting<>("Color", new ColorSetting(new Color(139, 100, 246)), v -> !distanceColor.getValue());
    private final Setting<Boolean> friendColor = new Setting<>("FriendColor", true);
    private final Setting<ColorSetting> friendColorValue = new Setting<>("FriendColorValue", new ColorSetting(new Color(0x30FF00)), v -> friendColor.getValue());

    public Arrows() {
        super("Arrows", Category.RENDER);
    }

    public void onRender2D(DrawContext context) {
        if (mc.world == null || mc.player == null) return;
        float cx = mc.getWindow().getScaledWidth() / 2f;
        float cy = mc.getWindow().getScaledHeight() / 2f;

        for (PlayerEntity target : mc.world.getPlayers()) {
            if (target == mc.player || !target.isAlive()) continue;
            double dist = mc.player.distanceTo(target);
            if (dist > range.getValue()) continue;

            double dx = target.getX() - mc.player.getX();
            double dz = target.getZ() - mc.player.getZ();
            float yawTo = (float) (-(Math.atan2(dx, dz) * 180d / Math.PI));
            float rel = MathHelper.wrapDegrees(yawTo - mc.player.getYaw());
            if (!ignoreFov.getValue() && Math.abs(rel) < 30f) continue; // hide when roughly in view

            // friend zawsze na zielono (jeśli włączone) — nadpisuje kolor odległości / stały
            Color col;
            if (friendColor.getValue() && Managers.FRIEND.isFriend(target))
                col = friendColorValue.getValue().getColorObject();
            else
                col = distanceColor.getValue() ? distColor(dist) : color.getValue().getColorObject();

            var m = context.getMatrices();
            m.pushMatrix();
            m.translate(cx, cy);
            m.rotate((float) Math.toRadians(rel));
            drawArrow(context, radius.getValue(), size.getValue(), col);
            m.popMatrix();
        }
    }

    /**
     * Arrow pointing outward (−y). Goes through the shared tracer-pointer painter, so it follows
     * HudEditor's ArrowsStyle / ArrowsFilled / ArrowsOutline like every other arrow in the client —
     * the old version stacked 1px slices, which is why it looked like a staircase.
     */
    private void drawArrow(DrawContext context, float radius, float size, Color col) {
        Render2DEngine.drawTracerPointer(context, 0f, -(radius + size), size, 0.5f, size * 0.3f, true, true, col.getRGB());
    }

    private Color distColor(double dist) {
        // near = red, far = green
        float t = (float) Math.max(0, Math.min(1, dist / range.getValue()));
        return Color.getHSBColor(t * 0.33f, 0.85f, 1f);
    }
}
