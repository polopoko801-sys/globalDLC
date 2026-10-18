package thunder.hack.features.modules.render;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.entity.Entity;
import net.minecraft.entity.projectile.thrown.EnderPearlEntity;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import org.joml.Matrix3x2f;
import thunder.hack.features.modules.Module;
import thunder.hack.features.modules.client.HudEditor;
import thunder.hack.setting.Setting;
import thunder.hack.utility.render.GuiRender;
import thunder.hack.utility.render.Render2DEngine;
import thunder.hack.utility.render.Render3DEngine;

import java.awt.Color;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 1:1 port of Celestial's {@code PearlESP}:
 * <ul>
 *     <li>Triangle ESP - an arrow on a 25 px circle around the crosshair pointing at every ender pearl, with
 *     its distance;</li>
 *     <li>a client-coloured box on every pearl, plus a tracer to it when prediction is off;</li>
 *     <li>Pearl Prediction - the pearl's whole flight simulated tick by tick (drag 0.99, gravity, thrower's
 *     motion added, stops at the first block) and drawn as a line that shortens by one tick as the pearl
 *     flies.</li>
 * </ul>
 * Celestial ran the simulation from a pearl-spawn hook; here it runs the first tick a pearl is seen. The 3D
 * parts are projected and drawn in onRender2D, since the 3D queue double-rotates on 1.21.11.
 */
public class PearlESP extends Module {
    public PearlESP() {
        super("PearlESP", Category.RENDER);
    }

    private final Setting<Boolean> triangleESP = new Setting<>("Triangle ESP", true);
    private final Setting<Boolean> pearlPrediction = new Setting<>("Pearl Prediction", false);

    private final List<PredictionLine> lines = new ArrayList<>();
    private final Set<Integer> seen = new HashSet<>();

    @Override
    public void onDisable() {
        lines.clear();
        seen.clear();
    }

    @Override
    public void onUpdate() {
        if (fullNullCheck()) return;
        Set<Integer> alive = new HashSet<>();
        for (Entity entity : mc.world.getEntities()) {
            if (!(entity instanceof EnderPearlEntity pearl)) continue;
            alive.add(pearl.getId());
            if (seen.add(pearl.getId()) && pearlPrediction.getValue()) handleEntityPrediction(pearl);
        }
        seen.retainAll(alive);

        if (!pearlPrediction.getValue()) return;
        lines.removeIf(PredictionLine::remove);
    }

    private void handleEntityPrediction(EnderPearlEntity ent) {
        double sx = ent.getX();
        double sy = ent.getY();
        double sz = ent.getZ();
        double mx = ent.getVelocity().x;
        double my = ent.getVelocity().y;
        double mz = ent.getVelocity().z;
        Entity thrower = ent.getOwner();
        if (thrower != null) {
            mx += thrower.getVelocity().x;
            mz += thrower.getVelocity().z;
            if (!thrower.isOnGround()) my += thrower.getVelocity().y;
        }
        int updateTicks = 250;
        ArrayList<Vec3d> positions = new ArrayList<>();
        while (updateTicks > 0) {
            Vec3d vec3d = new Vec3d(sx, sy, sz);
            if (--updateTicks != 250) positions.add(vec3d);
            Vec3d vec3d1 = new Vec3d(sx + mx, sy + my, sz + mz);
            HitResult raytraceresult = ent.getEntityWorld().raycast(new RaycastContext(vec3d, vec3d1, RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, ent));
            sx += mx;
            sy += my;
            sz += mz;
            float f1 = 0.99f;
            double f2 = ent.getFinalGravity();
            mx *= f1;
            my *= f1;
            mz *= f1;
            if (!ent.hasNoGravity()) my -= f2;
            if (raytraceresult.getType() == HitResult.Type.MISS) continue;
            positions.add(new Vec3d(sx + mx, sy + my, sz + mz));
            break;
        }
        lines.add(new PredictionLine(positions, ent));
    }

    @Override
    public void onRender2D(DrawContext context) {
        if (fullNullCheck()) return;
        float partialTicks = Render3DEngine.getTickDelta();
        Color client = HudEditor.getColor(0);

        // Boxes, tracers and the prediction line (Celestial's 3D passes).
        for (Entity entity : mc.world.getEntities()) {
            if (!(entity instanceof EnderPearlEntity)) continue;
            double x = MathHelper.lerp(partialTicks, entity.lastRenderX, entity.getX());
            double y = MathHelper.lerp(partialTicks, entity.lastRenderY, entity.getY());
            double z = MathHelper.lerp(partialTicks, entity.lastRenderZ, entity.getZ());
            Box bb = entity.getBoundingBox();
            Box box = new Box(bb.minX - entity.getX() + x - 0.05, bb.minY - entity.getY() + y, bb.minZ - entity.getZ() + z - 0.05,
                    bb.maxX - entity.getX() + x + 0.05, bb.maxY - entity.getY() + y + 0.15, bb.maxZ - entity.getZ() + z + 0.05);
            Render3DEngine.drawProjectedBox(context, box, client, client, new Color(0, 0, 0, 128), 2f, true, true);

            if (!pearlPrediction.getValue()) {
                Vec3d s = Render3DEngine.worldSpaceToScreenSpace(new Vec3d(x, y, z));
                if (s.z > 0 && s.z < 1)
                    Render2DEngine.drawThickLine(context, context.getScaledWindowWidth() / 2f, context.getScaledWindowHeight() / 2f, (float) s.x, (float) s.y, client.getRGB(), 1.5f);
            }
        }

        if (pearlPrediction.getValue()) {
            for (PredictionLine line : lines) {
                List<Vec3d> positions = line.positions;
                for (int i = 0; i + 1 < positions.size(); ++i) {
                    Vec3d c = Render3DEngine.worldSpaceToScreenSpace(positions.get(i));
                    Vec3d n = Render3DEngine.worldSpaceToScreenSpace(positions.get(i + 1));
                    if (!(c.z > 0 && c.z < 1) || !(n.z > 0 && n.z < 1)) continue;
                    Render2DEngine.drawThickLine(context, (float) c.x, (float) c.y, (float) n.x, (float) n.y, client.getRGB(), 1f);
                }
            }
        }

        if (!triangleESP.getValue()) return;
        float size = 50.0f;
        float xOffset = context.getScaledWindowWidth() / 2.0f - 24.5f;
        float yOffset = context.getScaledWindowHeight() / 2.0f - 25.2f;
        for (Entity entity : mc.world.getEntities()) {
            if (!(entity instanceof EnderPearlEntity)) continue;
            double x = MathHelper.lerp(partialTicks, entity.lastRenderX, entity.getX()) - mc.gameRenderer.getCamera().getCameraPos().x;
            double z = MathHelper.lerp(partialTicks, entity.lastRenderZ, entity.getZ()) - mc.gameRenderer.getCamera().getCameraPos().z;
            double cos = Math.cos(mc.player.getYaw() * (Math.PI / 180));
            double sin = Math.sin(mc.player.getYaw() * (Math.PI / 180));
            double rotY = -(z * cos - x * sin);
            double rotX = -(x * cos + z * sin);
            float angle = (float) (Math.atan2(rotY, rotX) * 180.0 / Math.PI);
            double xPos = (size / 2.0f) * Math.cos(Math.toRadians(angle)) + xOffset + (size / 2.0f);
            double yPos = (size / 2.0f) * Math.sin(Math.toRadians(angle)) + yOffset + (size / 2.0f);

            context.getMatrices().pushMatrix();
            context.getMatrices().translate((float) xPos, (float) yPos);
            context.getMatrices().rotate((float) Math.toRadians(angle));
            drawTriangle(context, 5.0f, 1.0f, 7.0f, 90.0f, new Color(5, 5, 5, 150).getRGB());
            drawTriangle(context, 5.0f, 1.0f, 6.0f, 90.0f, client.getRGB());
            String distance = Math.round(mc.player.distanceTo(entity) * 100.0) / 100.0 + "m";
            context.drawTextWithShadow(mc.textRenderer, distance, -2, 9, -1);
            context.getMatrices().popMatrix();
        }
    }

    /** Celestial's {@code RenderHelper.drawTriangle}: translate, rotate by 180 + vector, fan (0,s) (s,-s) (-s,-s). */
    private static void drawTriangle(DrawContext context, float x, float y, float size, float vector, int color) {
        Matrix3x2f pose = new Matrix3x2f(context.getMatrices()).translate(x, y).rotate((float) Math.toRadians(180.0f + vector));
        GuiRender.quads(context, vc -> GuiRender.tri(vc, pose, 0, size, size, -size, -size, -size, color));
    }

    private static class PredictionLine {
        final List<Vec3d> positions;
        final int ownerID;

        PredictionLine(List<Vec3d> positions, Entity predictable) {
            this.positions = positions;
            this.ownerID = predictable.getId();
        }

        boolean remove() {
            Entity target = mc.world.getEntityById(ownerID);
            if (!positions.isEmpty()) positions.remove(0);
            return positions.isEmpty() || target == null || target.isRemoved();
        }
    }
}
