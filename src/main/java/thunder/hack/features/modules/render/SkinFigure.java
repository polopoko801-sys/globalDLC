package thunder.hack.features.modules.render;

import net.minecraft.client.render.Camera;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.RotationAxis;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;
import thunder.hack.utility.render.Render3DUtil;

import java.awt.Color;

import static thunder.hack.features.modules.Module.mc;

/**
 * A player body drawn from a skin in world space - for KillEffect Ninja: you (in your own skin) and the player you
 * beat (in theirs), and the pieces they are cut into.
 *
 * <p>Uses the standard 64x64 skin layout: every part is a cuboid whose six faces take their own region of the
 * texture, exactly as the game's player model maps them. A part can be drawn partially (a range of its pixel rows) -
 * that is how a limb is cut in two; the exposed cut faces are drawn as raw red flesh instead of skin.
 */
final class SkinFigure {
    private SkinFigure() {
    }

    /** One pixel of the skin in blocks: a player is 32 skin pixels and 1.8 blocks tall. */
    static final float PX = 1.8f / 32f;

    /** Cuboid in the skin: texture origin (u, v) and size (w across, h tall, d deep), in pixels. */
    enum Part {
        HEAD(0, 0, 8, 8, 8), BODY(16, 16, 8, 12, 4),
        RIGHT_ARM(40, 16, 4, 12, 4), LEFT_ARM(32, 48, 4, 12, 4),
        RIGHT_LEG(0, 16, 4, 12, 4), LEFT_LEG(16, 48, 4, 12, 4);

        final int u, v, w, h, d;

        Part(int u, int v, int w, int h, int d) {
            this.u = u;
            this.v = v;
            this.w = w;
            this.h = h;
            this.d = d;
        }
    }

    private static final Color FLESH = new Color(150, 18, 24);

    /**
     * Draws rows {@code y0..y1} (pixels from the top of the part) of one part.
     *
     * @param origin world point the figure stands on (or a piece's centre)
     * @param yaw    which way the figure faces; local +z is its front
     * @param pitch  rotation about local x through {@code pivot}
     * @param pivot  local point the pitch turns about (blocks)
     * @param min    local position of the part's back-bottom-right corner (blocks) - for the WHOLE part
     * @param scale  size of the figure (1 = a player)
     */
    static void part(Identifier skin, Vec3d origin, float yaw, float pitch, Vec3d pivot, Vec3d min,
                     Part p, int y0, int y1, float scale, int alpha) {
        float px = PX * scale;
        float x0 = (float) min.x, x1 = x0 + p.w * px;
        float z0 = (float) min.z, z1 = z0 + p.d * px;
        float top = (float) min.y + p.h * px;
        float ya = top - y1 * px, yb = top - y0 * px; // ya = lower edge of the drawn rows, yb = upper

        Matrix4f m = matrix(origin, yaw, pitch, pivot);
        int argb = (Math.max(0, Math.min(255, alpha)) << 24) | 0xFFFFFF;

        RenderLayer layer = Render3DUtil.skinLayer(skin);
        VertexConsumer vc = Render3DUtil.buffer(layer);
        float u = p.u, v = p.v, w = p.w, d = p.d;
        float sv0 = v + d + y0, sv1 = v + d + y1; // the rows of the side faces

        // front (+z): u -> +x
        face(vc, m, x0, yb, z1, x1, yb, z1, x1, ya, z1, x0, ya, z1, u + d, sv0, u + d + w, sv1, argb);
        // back (-z): u -> -x
        face(vc, m, x1, yb, z0, x0, yb, z0, x0, ya, z0, x1, ya, z0, u + 2 * d + w, sv0, u + 2 * d + 2 * w, sv1, argb);
        // right side (-x): back on the left, front on the right
        face(vc, m, x0, yb, z0, x0, yb, z1, x0, ya, z1, x0, ya, z0, u, sv0, u + d, sv1, argb);
        // left side (+x): front on the left, back on the right
        face(vc, m, x1, yb, z1, x1, yb, z0, x1, ya, z0, x1, ya, z1, u + d + w, sv0, u + 2 * d + w, sv1, argb);
        // top / bottom only where the part is whole - a cut side gets flesh below instead
        if (y0 == 0) face(vc, m, x0, yb, z0, x1, yb, z0, x1, yb, z1, x0, yb, z1, u + d, v, u + d + w, v + d, argb);
        if (y1 == p.h) face(vc, m, x0, ya, z1, x1, ya, z1, x1, ya, z0, x0, ya, z0, u + d + w, v, u + d + 2 * w, v + d, argb);
        Render3DUtil.flush(layer);

        if (y0 != 0 || y1 != p.h) {
            RenderLayer q = Render3DUtil.QUADS;
            VertexConsumer qc = Render3DUtil.buffer(q);
            int flesh = (Math.max(0, Math.min(255, alpha)) << 24) | (FLESH.getRGB() & 0xFFFFFF);
            if (y0 != 0) Render3DUtil.quad(qc, m, x0, yb, z0, x1, yb, z0, x1, yb, z1, x0, yb, z1, flesh);
            if (y1 != p.h) Render3DUtil.quad(qc, m, x0, ya, z1, x1, ya, z1, x1, ya, z0, x0, ya, z0, flesh);
            Render3DUtil.flush(q);
        }
    }

    private static void face(VertexConsumer vc, Matrix4f m,
                             float ax, float ay, float az, float bx, float by, float bz,
                             float cx, float cy, float cz, float dx, float dy, float dz,
                             float u0, float v0, float u1, float v1, int argb) {
        float s = 64f;
        Render3DUtil.vertexTex(vc, m, ax, ay, az, u0 / s, v0 / s, argb);
        Render3DUtil.vertexTex(vc, m, bx, by, bz, u1 / s, v0 / s, argb);
        Render3DUtil.vertexTex(vc, m, cx, cy, cz, u1 / s, v1 / s, argb);
        Render3DUtil.vertexTex(vc, m, dx, dy, dz, u0 / s, v1 / s, argb);
    }

    /** Camera-relative world matrix, then the figure's own turn: yaw about y, pitch about x through the pivot. */
    private static Matrix4f matrix(Vec3d origin, float yaw, float pitch, Vec3d pivot) {
        Camera camera = mc.gameRenderer.getCamera();
        Vec3d cam = camera.getCameraPos();
        MatrixStack ms = new MatrixStack();
        ms.multiply(RotationAxis.POSITIVE_X.rotationDegrees(camera.getPitch()));
        ms.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(camera.getYaw() + 180f));
        ms.translate(origin.x - cam.x, origin.y - cam.y, origin.z - cam.z);
        ms.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(-yaw));
        if (pitch != 0f) {
            ms.translate(pivot.x, pivot.y, pivot.z);
            ms.multiply(RotationAxis.POSITIVE_X.rotationDegrees(pitch));
            ms.translate(-pivot.x, -pivot.y, -pivot.z);
        }
        return ms.peek().getPositionMatrix();
    }

    /** The skin of a player, or null for anything that is not one. */
    static Identifier skinOf(net.minecraft.entity.Entity e) {
        try {
            return e instanceof net.minecraft.client.network.AbstractClientPlayerEntity p ? p.getSkin().body().texturePath() : null;
        } catch (Throwable t) {
            return null;
        }
    }
}
