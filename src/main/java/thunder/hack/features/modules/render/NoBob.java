package thunder.hack.features.modules.render;

import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.MathHelper;
import thunder.hack.features.modules.Module;
import thunder.hack.setting.Setting;

public class NoBob extends Module {
    public NoBob() {
        super("NoBob", Category.RENDER);
    }

    public static Setting<Mode> mode = new Setting<>("Mode", Mode.Sexy);

    private float strideDistance, lastStrideDistance, bobDist, bobPrevDist;
    private int bobLastAge = Integer.MIN_VALUE;

    public void bobView(MatrixStack matrices, float tickDelta) {
        if (!(mc.getCameraEntity() instanceof PlayerEntity))
            return;

        // 1.21.11 fix: advance per-tick state only on tick change (see Core.bobView) — updating every
        // render frame made deltaSpeed jitter, shaking the view/hand while walking.
        if (mc.player.age != bobLastAge) {
            bobLastAge = mc.player.age;
            bobPrevDist = bobDist;
            bobDist = mc.player.distanceTraveled;
            lastStrideDistance = strideDistance;
            strideDistance += (Math.min(bobDist - bobPrevDist, 1f) - strideDistance) * 0.4f;
        }

        float deltaSpeed = bobDist - bobPrevDist;
        float g = -(bobDist + deltaSpeed * tickDelta);
        float h = MathHelper.lerp(tickDelta, lastStrideDistance, strideDistance);
        matrices.translate(0, -Math.abs(g * h * (mode.is(Mode.Sexy) ? 0.00035 : 0.)), 0);
    }

    public enum Mode {
        Sexy,
        Off
    }
}