package thunder.hack.utility.render;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.entity.LivingEntity;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix3x2f;
import thunder.hack.features.modules.client.HudEditor;

import java.util.ArrayList;
import java.util.List;

/**
 * Kilaura's target ESP shapes, re-drawn for 1.21.11 by projecting world points and emitting front-facing screen
 * quads ({@link ProjectedGlass}):
 * <ul>
 *     <li>NurikZapen - the capture marker spinning on the target;</li>
 *     <li>Jello / Sims - 1:1 Rockstar Free 2.0 {@code visuals.TargetESP}.</li>
 * </ul>
 * Celestial's target ESPs (Jello / Astolfo circle, Sims Mark) live in Kilaura itself, like in Celestial's KillAura.
 */
public final class TargetEspStyles {
    private TargetEspStyles() {
    }

    private static long lastNanos = System.nanoTime();
    // Rockstar TargetESP's height / animat
    private static double simsHeight;
    private static boolean simsAnimat;

    /** Call once per frame before drawing; returns the frame time in seconds (Feature.deltaTime()). */
    private static float frame() {
        long now = System.nanoTime();
        float dt = Math.min(0.1f, (now - lastNanos) / 1.0E9f);
        lastNanos = now;
        return dt;
    }

    // ------------------------------------------------------------------ Rockstar TargetESP "Jello"

    public static void rockstarJello(DrawContext ctx, LivingEntity t, Vec3d p, double circleSize) {
        frame();
        double everyTime = 1500;
        double drawTime = (System.currentTimeMillis() % everyTime);
        boolean drawMode = drawTime > (everyTime / 2);
        double drawPercent = drawTime / (everyTime / 2);
        // true when goes up
        if (!drawMode) {
            drawPercent = 1 - drawPercent;
        } else {
            drawPercent -= 1;
        }
        drawPercent = easeInOutQuad(drawPercent);

        double radius = circleSize;
        double height = t.getHeight() + 0.1;
        double x = p.x;
        double y = p.y + height * drawPercent;
        double z = p.z;
        double eased = (height / 3) * ((drawPercent > 0.5) ? 1 - drawPercent : drawPercent) * ((drawMode) ? -1 : 1);

        List<float[]> quads = new ArrayList<>();
        List<Integer> cols = new ArrayList<>();
        for (int lox = 0; lox < 360; lox += 5) {
            // ClientHelper.getClientColor(5, lox, 5)
            int color = HudEditor.getColor(lox).getRGB();
            double x1 = x - Math.sin(lox * Math.PI / 180F) * radius;
            double z1 = z + Math.cos(lox * Math.PI / 180F) * radius;
            double x2 = x - Math.sin((lox - 5) * Math.PI / 180F) * radius;
            double z2 = z + Math.cos((lox - 5) * Math.PI / 180F) * radius;
            float[] a = proj(x1, y + eased, z1), b = proj(x2, y + eased, z2), c = proj(x2, y, z2), d = proj(x1, y, z1);
            if (a == null || b == null || c == null || d == null) continue;
            quads.add(new float[]{a[0], a[1], b[0], b[1], c[0], c[1], d[0], d[1]});
            cols.add(color);
        }
        Matrix3x2f pose = GuiRender.pose(ctx);
        GuiRender.quads(ctx, Render2DEngine.currentClip(), vc -> {
            for (int i = 0; i < quads.size(); i++) {
                int c = cols.get(i);
                // glColor(color, 0) on the eased edge, glColor(color, 255) on the ring
                ProjectedGlass.quad(vc, pose, quads.get(i), ProjectedGlass.withAlpha(c, 0f), ProjectedGlass.withAlpha(c, 0f), c | 0xFF000000, c | 0xFF000000);
            }
        });
        // GL_LINE_LOOP of each segment's ring edge
        for (int i = 0; i < quads.size(); i++) {
            float[] q = quads.get(i);
            Render2DEngine.drawThickLine(ctx, q[4], q[5], q[6], q[7], cols.get(i) | 0xFF000000, 1f);
        }
    }

    // ------------------------------------------------------------------ Rockstar TargetESP "Sims"

    public static void rockstarSims(DrawContext ctx, LivingEntity t, Vec3d p) {
        double dt = frame();
        float radius = 0.2f;
        int side = 4;

        if (simsAnimat) {
            simsHeight = MathHelper.lerp(Math.min(1.0, 2 * dt), simsHeight, 0.4);
            if (simsHeight > 0.39) simsAnimat = false;
        } else {
            simsHeight = MathHelper.lerp(Math.min(1.0, 4 * dt), simsHeight, 0.1);
            if (simsHeight < 0.11) simsAnimat = true;
        }

        var mc = net.minecraft.client.MinecraftClient.getInstance();
        double spin = Math.toRadians((mc.player.age + Render3DEngine.getTickDelta()) * 10);
        double base = p.y + t.getHeight() + simsHeight;
        // GLU Cylinder, GLU_LINE: draw(0 -> radius, 0.3) then, 0.3 up, draw(radius -> 0, 0.3); 100 stacks
        wireCylinder(ctx, p.x, base, p.z, 0f, radius, 0.3, side, spin);
        wireCylinder(ctx, p.x, base + 0.3, p.z, radius, 0f, 0.3, side, spin);
    }

    /**
     * A GLU_LINE cylinder: the slice edges plus a ring at each of its 100 stacks - so dense the diamond reads as
     * solid. enableSmoothLine(0.5F): glLineWidth 0.5 with smoothing, i.e. about one framebuffer pixel, depth off.
     */
    private static void wireCylinder(DrawContext ctx, double x, double y, double z, float baseR, float topR, double h, int slices, double spin) {
        int stacks = 100;
        // a 0.5-wide smoothed line covers about half a pixel: white at half strength
        int color = 0x80FFFFFF;
        float px = (float) (1.0 / net.minecraft.client.MinecraftClient.getInstance().getWindow().getScaleFactor());
        for (int s = 0; s <= stacks; s++) {
            double f = (double) s / stacks;
            double r = baseR + (topR - baseR) * f;
            if (r <= 0) continue;
            float[] prev = null;
            for (int i = 0; i <= slices; i++) {
                double a = spin + Math.PI * 2 * i / slices;
                float[] pt = proj(x + Math.cos(a) * r, y + h * f, z + Math.sin(a) * r);
                if (pt != null && prev != null)
                    Render2DEngine.drawThickLine(ctx, prev[0], prev[1], pt[0], pt[1], color, px);
                prev = pt;
            }
        }
        for (int i = 0; i < slices; i++) {
            double a = spin + Math.PI * 2 * i / slices;
            float[] b = proj(x + Math.cos(a) * baseR, y, z + Math.sin(a) * baseR);
            float[] tp = proj(x + Math.cos(a) * topR, y + h, z + Math.sin(a) * topR);
            if (b != null && tp != null)
                Render2DEngine.drawThickLine(ctx, b[0], b[1], tp[0], tp[1], color, px);
        }
    }

    // ------------------------------------------------------------------ Ghosts ("Dusze")

    /**
     * Three glowing souls circling the target on tilted orbits at different heights, each dragging a fading tail:
     * additive {@link TextureStorage#ghostGlow} sprites, a bright core per soul.
     */
    public static void ghosts(DrawContext ctx, LivingEntity t, Vec3d p, int color) {
        frame();
        double time = System.currentTimeMillis() / 1000.0;
        Vec3d up = cameraUp();
        double radius = t.getWidth() * 0.85 + 0.25;
        int souls = 3, tail = 16;
        List<float[]> sprites = new ArrayList<>();
        List<Integer> colors = new ArrayList<>();
        for (int s = 0; s < souls; s++) {
            double phase = s * Math.PI * 2 / souls;
            for (int k = tail; k >= 0; k--) {
                double tt = time - k * 0.035;
                double a = tt * 2.4 + phase;
                double y = p.y + t.getHeight() * (0.5 + 0.38 * Math.sin(tt * 1.7 + phase));
                Vec3d w = new Vec3d(p.x + Math.cos(a) * radius, y, p.z + Math.sin(a) * radius);
                float f = 1f - (float) k / (tail + 1);
                float[] sp = sprite(w, up, (float) (0.22 * (0.35 + 0.65 * f)), 60f);
                if (sp == null) continue;
                sprites.add(sp);
                colors.add(withAlphaInt(color, (int) (255 * f * f * (k == 0 ? 1f : 0.55f))));
            }
        }
        Matrix3x2f pose = GuiRender.pose(ctx);
        GuiRender.texturedAdditive(ctx, TextureStorage.ghostGlow, Render2DEngine.currentClip(), vc -> {
            for (int i = 0; i < sprites.size(); i++) {
                float[] s = sprites.get(i);
                int c = colors.get(i);
                quadSprite(vc, pose, s[0], s[1], s[2], 0f, c, c, c, c);
            }
        });
        // white-hot cores on the heads
        GuiRender.texturedAdditive(ctx, TextureStorage.ghostGlow, Render2DEngine.currentClip(), vc -> {
            for (int s = 0; s < souls; s++) {
                double phase = s * Math.PI * 2 / souls;
                double a = time * 2.4 + phase;
                double y = p.y + t.getHeight() * (0.5 + 0.38 * Math.sin(time * 1.7 + phase));
                float[] sp = sprite(new Vec3d(p.x + Math.cos(a) * radius, y, p.z + Math.sin(a) * radius), up, 0.09f, 26f);
                if (sp == null) continue;
                int c = withAlphaInt(0xFFFFFF, 200);
                quadSprite(vc, pose, sp[0], sp[1], sp[2], 0f, c, c, c, c);
            }
        });
    }

    // ------------------------------------------------------------------ Crystals ("Kryształy")

    /**
     * A ring of shaded crystals (elongated four-sided bipyramids) turning round the target at chest height, each
     * spinning on its own axis and bobbing, with a soft glow behind it.
     */
    public static void crystals(DrawContext ctx, LivingEntity t, Vec3d p, int color) {
        frame();
        double time = System.currentTimeMillis() / 1000.0;
        Vec3d up = cameraUp();
        int count = 8;
        double radius = t.getWidth() * 0.8 + 0.3;
        double baseY = p.y + t.getHeight() * 0.55;
        record Tri(float[] a, float[] b, float[] c, int col, double depth) {
        }
        List<Tri> tris = new ArrayList<>();
        List<float[]> glows = new ArrayList<>();
        Vec3d cam = net.minecraft.client.MinecraftClient.getInstance().gameRenderer.getCamera().getCameraPos();
        for (int i = 0; i < count; i++) {
            double a = time * 0.9 + i * Math.PI * 2 / count;
            Vec3d c = new Vec3d(p.x + Math.cos(a) * radius, baseY + Math.sin(time * 2.0 + i) * 0.12, p.z + Math.sin(a) * radius);
            double r = 0.065, hh = 0.17, spin = time * 2.2 + i;
            Vec3d top = c.add(0, hh, 0), bottom = c.add(0, -hh, 0);
            Vec3d[] ring = new Vec3d[4];
            for (int k = 0; k < 4; k++) {
                double ang = spin + Math.PI / 2 * k;
                ring[k] = c.add(Math.cos(ang) * r, 0, Math.sin(ang) * r);
            }
            float[] sp = sprite(c, up, 0.32f, 70f);
            if (sp != null) glows.add(sp);
            for (int k = 0; k < 4; k++) {
                Vec3d r0 = ring[k], r1 = ring[(k + 1) % 4];
                float shade = 0.62f + 0.38f * (float) ((Math.cos(spin + Math.PI / 2 * k + 0.6) + 1) / 2);
                for (Vec3d apex : new Vec3d[]{top, bottom}) {
                    float[] pa = proj(apex.x, apex.y, apex.z), p0 = proj(r0.x, r0.y, r0.z), p1 = proj(r1.x, r1.y, r1.z);
                    if (pa == null || p0 == null || p1 == null) continue;
                    double depth = apex.add(r0).add(r1).multiply(1 / 3.0).squaredDistanceTo(cam);
                    float sh = apex == top ? shade : shade * 0.78f;
                    tris.add(new Tri(pa, p0, p1, (0xE6 << 24) | (scale(color, sh) & 0xFFFFFF), depth));
                }
            }
        }
        Matrix3x2f pose = GuiRender.pose(ctx);
        GuiRender.texturedAdditive(ctx, TextureStorage.ghostGlow, Render2DEngine.currentClip(), vc -> {
            int g = withAlphaInt(color, 110);
            for (float[] s : glows) quadSprite(vc, pose, s[0], s[1], s[2], 0f, g, g, g, g);
        });
        // far faces first; both windings, the GUI pipeline culls back faces
        tris.sort((x, y) -> Double.compare(y.depth(), x.depth()));
        GuiRender.quads(ctx, Render2DEngine.currentClip(), vc -> {
            for (Tri tr : tris) {
                GuiRender.tri(vc, pose, tr.a()[0], tr.a()[1], tr.b()[0], tr.b()[1], tr.c()[0], tr.c()[1], tr.col());
                GuiRender.tri(vc, pose, tr.a()[0], tr.a()[1], tr.c()[0], tr.c()[1], tr.b()[0], tr.b()[1], tr.col());
            }
        });
    }

    private static int withAlphaInt(int rgb, int alpha) {
        return (Math.max(0, Math.min(255, alpha)) << 24) | (rgb & 0xFFFFFF);
    }

    private static int scale(int rgb, float k) {
        int r = Math.min(255, Math.round(((rgb >> 16) & 0xFF) * k));
        int g = Math.min(255, Math.round(((rgb >> 8) & 0xFF) * k));
        int b = Math.min(255, Math.round((rgb & 0xFF) * k));
        return (r << 16) | (g << 8) | b;
    }

    // ------------------------------------------------------------------ NurikZapen

    /** NurikZapen ({@code CaptureMark}): the capture marker spinning (with its speed swinging back and forth) on the target. */
    public static void nurikZapen(DrawContext ctx, LivingEntity t, Vec3d p, float scale) {
        frame();
        Vec3d centre = p.add(0, t.getEyeHeight(t.getPose()) / 2f, 0);
        // ~1.25x the original 0.75 half-size, with a higher on-screen cap so it is not clipped back up close
        float[] s = sprite(centre, cameraUp(), 0.95f * scale, 110f);
        if (s == null) return;
        float angle = (float) Math.toRadians(thunder.hack.utility.render.animation.CaptureMark.angle());
        int c0 = HudEditor.getColor(270).getRGB(), c1 = HudEditor.getColor(90).getRGB();
        int c2 = HudEditor.getColor(0).getRGB(), c3 = HudEditor.getColor(180).getRGB();
        Matrix3x2f pose = GuiRender.pose(ctx);
        GuiRender.textured(ctx, TextureStorage.captureAlpha, Render2DEngine.currentClip(), vc -> quadSprite(vc, pose, s[0], s[1], s[2], angle, c0, c1, c2, c3));
    }

    /** Camera's up vector in world space. */
    private static Vec3d cameraUp() {
        var cam = net.minecraft.client.MinecraftClient.getInstance().gameRenderer.getCamera();
        Vec3d forward = Vec3d.fromPolar(cam.getPitch(), cam.getYaw());
        Vec3d right = forward.crossProduct(new Vec3d(0, 1, 0)).normalize();
        return right.crossProduct(forward).normalize();
    }

    /** A camera-facing sprite of world half-size {@code half}: {screen x, screen y, pixel half-size}, or null. */
    private static float[] sprite(Vec3d w, Vec3d up, float half, float maxPx) {
        Vec3d s = Render3DEngine.worldSpaceToScreenSpace(w);
        if (!(s.z > 0 && s.z < 1)) return null;
        Vec3d e = Render3DEngine.worldSpaceToScreenSpace(w.add(up.multiply(half)));
        if (!(e.z > 0 && e.z < 1)) return null;
        float px = (float) Math.hypot(e.x - s.x, e.y - s.y);
        // capped, so a target right in your face does not fill the screen
        return px < 0.3f ? null : new float[]{(float) s.x, (float) s.y, Math.min(px, maxPx)};
    }

    /** A textured square of half-size h at (x, y), turned by {@code angle}; corner colours top-left, bottom-left, bottom-right, top-right. */
    private static void quadSprite(net.minecraft.client.render.VertexConsumer vc, Matrix3x2f pose, float x, float y, float h, float angle,
                                   int c0, int c1, int c2, int c3) {
        float cos = (float) Math.cos(angle), sin = (float) Math.sin(angle);
        float[][] corners = {{-h, -h}, {-h, h}, {h, h}, {h, -h}};
        float[][] uv = {{0, 0}, {0, 1}, {1, 1}, {1, 0}};
        int[] cols = {c0, c1, c2, c3};
        for (int i = 0; i < 4; i++) {
            float cx = corners[i][0] * cos - corners[i][1] * sin, cy = corners[i][0] * sin + corners[i][1] * cos;
            GuiRender.vertexTex(vc, pose, x + cx, y + cy, uv[i][0], uv[i][1], cols[i]);
        }
    }

    // ------------------------------------------------------------------ helpers

    private static float[] proj(double x, double y, double z) {
        Vec3d s = Render3DEngine.worldSpaceToScreenSpace(new Vec3d(x, y, z));
        return s.z > 0 && s.z < 1 ? new float[]{(float) s.x, (float) s.y} : null;
    }

    private static double easeInOutQuad(double t) {
        return t < 0.5 ? 2 * t * t : 1 - Math.pow(-2 * t + 2, 2) / 2;
    }
}
