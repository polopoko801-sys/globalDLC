package thunder.hack.features.modules.render;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import thunder.hack.core.Managers;
import thunder.hack.features.modules.Module;
import thunder.hack.setting.Setting;
import thunder.hack.setting.impl.ColorSetting;
import thunder.hack.utility.render.Render2DEngine;
import thunder.hack.utility.render.Render3DEngine;

import java.awt.Color;

/**
 * Glowing tracers to every visible player.
 * <p>
 * 1.21.11: drawn in SCREEN SPACE — the target point is projected with
 * {@link Render3DEngine#worldSpaceToScreenSpace} and the beam is a 2D gradient glow. The old
 * {@code drawLineDebug} path went through the 3D queue, which double-rotates on this version, so the lines
 * drifted away from their targets as the camera turned. Screen space also lets the beam start at a fixed
 * screen anchor and fade in along its length, which the 3D path could not do.
 */
public class Tracers extends Module {
    public Tracers() {
        super("Tracers", Category.RENDER);
    }

    private final Setting<Anchor> anchor = new Setting<>("Anchor", Anchor.Bottom);
    private final Setting<Aim> aim = new Setting<>("Aim", Aim.Body);
    private final Setting<Float> width = new Setting<>("Width", 4f, 1f, 14f);
    private final Setting<Float> height = new Setting<>("Height", 0f, 0f, 2f);
    private final Setting<Float> range = new Setting<>("Range", 128f, 8f, 512f);
    private final Setting<Boolean> healthColor = new Setting<>("HealthColor", false);
    private final Setting<Boolean> fadeTail = new Setting<>("FadeTail", true);
    private final Setting<Boolean> dot = new Setting<>("TargetDot", true);

    private final Setting<ColorSetting> color = new Setting<>("Color", new ColorSetting(new Color(0x93FF0000, true)), v -> !healthColor.getValue());
    private final Setting<ColorSetting> friendColor = new Setting<>("Friends", new ColorSetting(new Color(0x9317DE5D, true)));

    public void onRender2D(DrawContext context) {
        if (mc.world == null || mc.player == null || mc.options.hudHidden) return;

        float sw = mc.getWindow().getScaledWidth();
        float sh = mc.getWindow().getScaledHeight();
        float ax = sw / 2f;
        float ay = switch (anchor.getValue()) {
            case Top -> 0f;
            case Center -> sh / 2f;
            case Bottom -> sh;
        };

        float td = Render3DEngine.getTickDelta();

        // mc.world.getPlayers() (like NameTags), NOT Managers.ASYNC.getAsyncPlayers() - that async list is not
        // populated on 1.21.11, so Tracers drew nothing ("traces nic").
        for (PlayerEntity player : mc.world.getPlayers()) {
            if (player == mc.player || !player.isAlive()) continue;
            if (mc.player.distanceTo(player) > range.getValue()) continue;

            double x = MathHelper.lerp(td, player.lastX, player.getX());
            double y = MathHelper.lerp(td, player.lastY, player.getY());
            double z = MathHelper.lerp(td, player.lastZ, player.getZ());

            double aimY = switch (aim.getValue()) {
                case Feet -> 0.0;
                case Body -> player.getHeight() / 2.0;
                case Head -> player.getHeight();
            };

            Vec3d screen = Render3DEngine.worldSpaceToScreenSpace(new Vec3d(x, y + aimY + height.getValue(), z));
            // Behind the near plane the projection blows up to garbage coordinates — skip rather than draw.
            if (!(screen.z > 0 && screen.z < 1)) continue;

            Color col = colorFor(player);
            Color tail = fadeTail.getValue() ? Render2DEngine.injectAlpha(col, (int) (col.getAlpha() * 0.12f)) : col;

            Render2DEngine.drawGlowLine(context, ax, ay, (float) screen.x, (float) screen.y, tail, col, width.getValue());

            if (dot.getValue()) {
                float r = Math.max(1.5f, width.getValue() * 0.45f);
                Render2DEngine.drawRound(context, (float) screen.x - r, (float) screen.y - r, r * 2f, r * 2f, r, col);
            }
        }
    }

    private Color colorFor(PlayerEntity player) {
        if (Managers.FRIEND.isFriend(player))
            return friendColor.getValue().getColorObject();

        if (healthColor.getValue()) {
            float frac = MathHelper.clamp((player.getHealth() + player.getAbsorptionAmount()) / Math.max(1f, player.getMaxHealth()), 0f, 1f);
            Color low = new Color(0xFF2A2A), mid = new Color(0xFFC400), high = new Color(0x2FFF00);
            Color c = frac < 0.5f
                    ? Render2DEngine.interpolateColorC(low, mid, frac * 2f)
                    : Render2DEngine.interpolateColorC(mid, high, (frac - 0.5f) * 2f);
            return Render2DEngine.injectAlpha(c, 200);
        }

        return color.getValue().getColorObject();
    }

    public enum Anchor {
        Bottom, Center, Top
    }

    public enum Aim {
        Feet, Body, Head
    }
}
