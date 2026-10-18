package thunder.hack.utility.render.animation;

import com.mojang.blaze3d.vertex.VertexFormat;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.render.*;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.RotationAxis;
import org.joml.Matrix4f;
import thunder.hack.features.modules.client.HudEditor;
import thunder.hack.utility.render.Render2DEngine;
import thunder.hack.utility.render.Render3DEngine;
import thunder.hack.utility.render.TextureStorage;

import static thunder.hack.features.modules.Module.mc;

public class CaptureMark {
    private static float espValue = 1f, prevEspValue;
    private static float espSpeed = 1f;
    private static boolean flipSpeed;

    public static void render(Entity target) {
        Camera camera = mc.gameRenderer.getCamera();

        double tPosX = Render2DEngine.interpolate(target.lastX, target.getX(), Render3DEngine.getTickDelta()) - camera.getCameraPos().x;
        double tPosY = Render2DEngine.interpolate(target.lastY, target.getY(), Render3DEngine.getTickDelta()) - camera.getCameraPos().y;
        double tPosZ = Render2DEngine.interpolate(target.lastZ, target.getZ(), Render3DEngine.getTickDelta()) - camera.getCameraPos().z;

        MatrixStack matrices = new MatrixStack();
        thunder.hack.utility.render.RSCompat.disableDepthTest();
        thunder.hack.utility.render.RSCompat.disableCull();
        matrices.multiply(RotationAxis.POSITIVE_X.rotationDegrees(camera.getPitch()));
        matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(camera.getYaw() + 180.0F));
        matrices.translate(tPosX, (tPosY + target.getEyeHeight(target.getPose()) / 2f), tPosZ);
        matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(-camera.getYaw()));
        matrices.multiply(RotationAxis.POSITIVE_X.rotationDegrees(camera.getPitch()));
        matrices.multiply(RotationAxis.POSITIVE_Z.rotationDegrees(Render2DEngine.interpolateFloat(prevEspValue, espValue, Render3DEngine.getTickDelta())));
        thunder.hack.utility.render.RSCompat.enableBlend();
        thunder.hack.utility.render.RSCompat.noop();
        thunder.hack.utility.render.RSCompat.noop();
        matrices.translate(-0.75, -0.75, -0.01);
        Matrix4f matrix = matrices.peek().getPositionMatrix();
        thunder.hack.utility.render.RSCompat.noop();
        BufferBuilder buffer = Tessellator.getInstance().begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION_TEXTURE_COLOR);
        buffer.vertex(matrix, 0, 1.5f, 0).texture(0f, 1f).color(HudEditor.getColor(90).getRGB());
        buffer.vertex(matrix, 1.5f, 1.5f, 0).texture(1f, 1f).color(HudEditor.getColor(0).getRGB());
        buffer.vertex(matrix, 1.5f, 0, 0).texture(1f, 0).color(HudEditor.getColor(180).getRGB());
        buffer.vertex(matrix, 0, 0, 0).texture(0, 0).color(HudEditor.getColor(270).getRGB());
        thunder.hack.utility.render.RSCompat.noop(buffer.endNullable());
        thunder.hack.utility.render.RSCompat.enableCull();
        thunder.hack.utility.render.RSCompat.enableDepthTest();
        thunder.hack.utility.render.RSCompat.noop();
        thunder.hack.utility.render.RSCompat.disableBlend();
        thunder.hack.utility.render.RSCompat.setShaderColor(1f, 1f, 1f, 1f);
    }

    /** Current spin in degrees, interpolated between ticks (for the projected NurikZapen ESP). */
    public static float angle() {
        return Render2DEngine.interpolateFloat(prevEspValue, espValue, Render3DEngine.getTickDelta());
    }

    public static void tick() {
        prevEspValue = espValue;
        espValue += espSpeed;
        if (espSpeed > 25) flipSpeed = true;
        if (espSpeed < -25) flipSpeed = false;
        espSpeed = flipSpeed ? espSpeed - 0.5f : espSpeed + 0.5f;
    }
}
