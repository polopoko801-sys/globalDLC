package thunder.hack.features.modules.render;

import com.google.common.collect.Lists;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix3x2fStack;
import thunder.hack.core.Managers;
import thunder.hack.core.manager.client.ModuleManager;
import thunder.hack.events.impl.EventAttack;
import thunder.hack.injection.accesors.IClientPlayerEntity;
import thunder.hack.features.modules.Module;
import thunder.hack.setting.Setting;
import thunder.hack.utility.Timer;
import thunder.hack.utility.render.Render2DEngine;
import thunder.hack.utility.render.Render3DEngine;

import java.util.ArrayList;

public class HitBubbles extends Module {
    public HitBubbles() {
        super("HitBubbles", Category.RENDER);
    }

    public final Setting<Integer> lifeTime = new Setting<>("LifeTime", 30, 1, 150);

    private final ArrayList<HitBubble> bubbles = new ArrayList<>();

    @EventHandler
    public void onHit(EventAttack e) {
        Vec3d point = Managers.PLAYER.getRtxPoint(((IClientPlayerEntity) mc.player).getLastYaw(), ((IClientPlayerEntity) mc.player).getLastPitch(), ModuleManager.aura.attackRange.getValue());
        if (point != null && !e.isPre())
            bubbles.add(new HitBubble((float) point.x, (float) point.y, (float) point.z, -((IClientPlayerEntity) mc.player).getLastYaw(), ((IClientPlayerEntity) mc.player).getLastPitch(), new Timer()));
    }

    public void onRender2D(DrawContext context) {
        // 1.21.11: drawBubble now draws through the 2D DrawContext (Matrix3x2fStack) rather than a
        // 3D MatrixStack. Project each bubble's world position to screen space and translate the 2D
        // matrix there before drawing (same worldSpaceToScreenSpace path ItemESP/ESP use).
        ArrayList<HitBubble> bubblesCopy = Lists.newArrayList(bubbles);
        for (HitBubble b : bubblesCopy) {
            Vec3d screen = Render3DEngine.worldSpaceToScreenSpace(new Vec3d(b.x, b.y, b.z));
            if (screen.z > 0 && screen.z < 1) {
                float factor = Math.min(1f, b.life.getPassedTimeMs() / (float) (lifeTime.getValue() * 50));
                Matrix3x2fStack ms = context.getMatrices();
                ms.pushMatrix();
                ms.translate((float) screen.x, (float) screen.y);
                Render2DEngine.drawBubble(context, b.yaw, factor);
                ms.popMatrix();
            }
        }
        bubbles.removeIf(b -> b.life.passedMs(lifeTime.getValue() * 50));
    }

    public record HitBubble(float x, float y, float z, float yaw, float pitch, Timer life) {
    }
}
