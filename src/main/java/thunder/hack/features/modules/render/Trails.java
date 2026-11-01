package thunder.hack.features.modules.render;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix3x2f;
import thunder.hack.features.modules.Module;
import thunder.hack.features.modules.client.HudEditor;
import thunder.hack.setting.Setting;
import thunder.hack.setting.impl.ColorSetting;
import thunder.hack.utility.render.GuiRender;
import thunder.hack.utility.render.Render2DEngine;
import thunder.hack.utility.render.Render3DEngine;
import thunder.hack.utility.render.ScreenCapture;
import thunder.hack.utility.render.FxSurface;
import thunder.hack.utility.render.TrailTextures;
import net.minecraft.util.Identifier;

import java.awt.Color;
import java.util.ArrayList;

/**
 * 1:1 port of Celestial's {@code Trails}: a vertical ribbon from your feet to head height along the path you
 * walked, bottom colour from the mode, top colour from Top Color, fading out with age.
 *
 * <p>Celestial drew it as one GL_QUAD_STRIP in EventRender3D with depth off. The 3D queues double-rotate on
 * 1.21.11, so the same strip is projected and drawn from onRender2D - with depth off it was an overlay anyway,
 * so the result is the same picture. Everything else (when points are added, the ageing quirk in
 * {@link Trail#remove}, the alpha formula, the heights) is kept as it was, per frame like the original.
 */
public class Trails extends Module {
    public Trails() {
        super("Trails", Category.RENDER);
    }

    public final Setting<Float> sizeToRemove = new Setting<>("Ticks", 300.0f, 10.0f, 500.0f);
    public final Setting<TrailsMode> trailsMode = new Setting<>("Trails Mode", TrailsMode.Custom);
    public final Setting<ColorSetting> topColor = new Setting<>("Top Color", new ColorSetting(HudEditor.getColor(0).getRGB()), v -> usesOwnColors());
    public final Setting<ColorSetting> bottomColor = new Setting<>("Bottom Color", new ColorSetting(HudEditor.getColor(0).getRGB()), v -> usesOwnColors());
    public final Setting<Float> trailsAlpha = new Setting<>("Trails Alpha", 0.9f, 0.1f, 1.0f);
    private final Setting<Boolean> hideInFirstPerson = new Setting<>("Hide In First Person", false);
    private final Setting<Float> waveStrength = new Setting<>("Waves", 1.0f, 0.0f, 3.0f, v -> trailsMode.is(TrailsMode.Water));
    private final Setting<Float> blobSize = new Setting<>("Blob Size", 0.3f, 0.1f, 0.8f, v -> trailsMode.is(TrailsMode.Blobs));
    private final Setting<Float> blobSpacing = new Setting<>("Blob Spacing", 0.55f, 0.2f, 2.0f, v -> trailsMode.is(TrailsMode.Blobs));
    private final Setting<Float> tint = new Setting<>("Tint", 0.45f, 0.0f, 1.0f, v -> trailsMode.is(TrailsMode.Water) || trailsMode.is(TrailsMode.Glass) || trailsMode.is(TrailsMode.Blobs));

    public enum TrailsMode {Astolfo, Rainbow, Client, Custom, Water, Glass, Blobs, Images}

    /** Images mode: built-in sprites (drawn in code) or your own picture from misc/images/trail.png. */
    public enum TrailImage {Stars, Hearts, Sparkles, Flames, Snowflakes, Bubbles, Petals, Notes, Custom}

    private final Setting<TrailImage> image = new Setting<>("Image", TrailImage.Stars, v -> trailsMode.is(TrailsMode.Images));
    private final Setting<Float> imageSize = new Setting<>("Image Size", 0.35f, 0.1f, 1.5f, v -> trailsMode.is(TrailsMode.Images));
    private final Setting<Float> imageSpacing = new Setting<>("Image Spacing", 0.5f, 0.15f, 2.0f, v -> trailsMode.is(TrailsMode.Images));
    private final Setting<Boolean> imageSpin = new Setting<>("Image Spin", true, v -> trailsMode.is(TrailsMode.Images));
    /** Colour the sprite with Bottom Color. Off: drawn as it is - what you want for a colourful custom photo. */
    private final Setting<Boolean> imageTint = new Setting<>("Image Tint", true, v -> trailsMode.is(TrailsMode.Images));

    private record Sprite(float x, float y, float half, float angle, int argb) {
    }

    private Identifier customImage;
    private long customTriedAt;

    private boolean usesOwnColors() {
        return trailsMode.is(TrailsMode.Custom) || trailsMode.is(TrailsMode.Water) || trailsMode.is(TrailsMode.Glass)
                || trailsMode.is(TrailsMode.Blobs) || trailsMode.is(TrailsMode.Images);
    }

    /** How the ribbon is drawn: Water / Glass trails modes get the see-through styles, every other mode the coloured ribbon. */
    public enum Style {Default, Water, Glass}

    final ArrayList<Trail> trails = new ArrayList<>();

    void createTrail() {
        Vec3d color = null;
        int oneColor = bottomColor.getValue().getColor();
        float red = (float) (oneColor >> 16 & 0xFF) / 255.0f;
        float green = (float) (oneColor >> 8 & 0xFF) / 255.0f;
        float blue = (float) (oneColor & 0xFF) / 255.0f;
        switch (trailsMode.getValue()) {
            case Client -> {
                Color c = HudEditor.getColor(0);
                color = new Vec3d((float) c.getRed() / 255.0f, (float) c.getGreen() / 255.0f, (float) c.getBlue() / 255.0f);
            }
            case Custom, Water, Glass, Blobs, Images -> color = new Vec3d(red, green, blue);
            case Astolfo -> color = new Vec3d((float) Render2DEngine.astolfo(false, 1).getRed() / 255.0f,
                    (float) Render2DEngine.astolfo(false, 1).getGreen() / 255.0f, (float) Render2DEngine.astolfo(false, 1).getBlue() / 255.0f);
            case Rainbow -> color = new Vec3d((float) Render2DEngine.rainbow(300, 1.0f, 1.0f).getRed() / 255.0f,
                    (float) Render2DEngine.rainbow(300, 1.0f, 1.0f).getGreen() / 255.0f, (float) Render2DEngine.rainbow(300, 1.0f, 1.0f).getBlue() / 255.0f);
        }
        float td = Render3DEngine.getTickDelta();
        double x = mc.player.lastRenderX + (mc.player.getX() - mc.player.lastRenderX) * (double) td;
        double y = mc.player.lastRenderY + (mc.player.getY() - mc.player.lastRenderY) * (double) td;
        double z = mc.player.lastRenderZ + (mc.player.getZ() - mc.player.lastRenderZ) * (double) td;
        Vec3d vector = new Vec3d(x, y, z);
        trails.add(new Trail(vector, color));
    }

    @Override
    public void onDisable() {
        trails.clear();
        super.onDisable();
    }

    @Override
    public void onRender2D(DrawContext context) {
        if (fullNullCheck()) return;
        if (mc.options.getPerspective().isFirstPerson() && hideInFirstPerson.getValue()) {
            return;
        }
        if (mc.player.getVelocity().x != 0.0 || mc.player.getVelocity().z != 0.0) createTrail();
        // every piece fades where it lies, one after another in the order you left them: the oldest end goes
        // first, the part by your feet last
        trails.removeIf(this::remove);

        int topColor1 = topColor.getValue().getColor();
        float red = (float) (topColor1 >> 16 & 0xFF) / 255.0f;
        float green = (float) (topColor1 >> 8 & 0xFF) / 255.0f;
        float blue = (float) (topColor1 & 0xFF) / 255.0f;
        float height = (float) (mc.player.isSneaking() ? 1.5 : 1.8);

        // GL_QUAD_STRIP: every trail point gives a bottom and a top vertex, and each pair of neighbouring
        // points makes one quad. Projected here; a point behind the camera drops the quads that touch it.
        int n = trails.size();
        if (n < 2) return;
        float[] bx = new float[n], by = new float[n], tx = new float[n], ty = new float[n];
        int[] bottom = new int[n], top = new int[n];
        boolean[] ok = new boolean[n];
        for (int i = 0; i < n; i++) {
            Trail trail = trails.get(i);
            float alpha = trailsAlpha.getValue() * (1.0f - (float) trail.existed / sizeToRemove.getValue());
            Vec3d b = Render3DEngine.worldSpaceToScreenSpace(trail.position);
            Vec3d t = Render3DEngine.worldSpaceToScreenSpace(trail.position.add(0, height, 0));
            ok[i] = b.z > 0 && b.z < 1 && t.z > 0 && t.z < 1;
            bx[i] = (float) b.x;
            by[i] = (float) b.y;
            tx[i] = (float) t.x;
            ty[i] = (float) t.y;
            bottom[i] = argb((float) trail.color.x, (float) trail.color.y, (float) trail.color.z, alpha);
            // Celestial took the top from Top Color in every mode, so Rainbow/Astolfo/Client had a grey top;
            // those modes now colour the whole ribbon, Top Color stays for Custom / Water / Glass.
            top[i] = usesOwnColors() ? argb(red, green, blue, alpha) : bottom[i];
        }

        Matrix3x2f pose = GuiRender.pose(context);
        if (trailsMode.is(TrailsMode.Images)) {
            drawImages(context, pose);
            return;
        }
        if (trailsMode.is(TrailsMode.Blobs)) {
            drawBlobs(context);
            return;
        }
        Style s = trailsMode.is(TrailsMode.Water) ? Style.Water : trailsMode.is(TrailsMode.Glass) ? Style.Glass : Style.Default;
        if (s != Style.Default) {
            ScreenCapture.capture();
            if (!ScreenCapture.available()) s = Style.Default;
        }

        if (s == Style.Default) {
            GuiRender.quads(context, Render2DEngine.currentClip(), vc -> {
                for (int i = 0; i + 1 < n; i++) {
                    if (!ok[i] || !ok[i + 1]) continue;
                    frontQuad(vc, pose,
                            bx[i], by[i], tx[i], ty[i], tx[i + 1], ty[i + 1], bx[i + 1], by[i + 1],
                            bottom[i], top[i], top[i + 1], bottom[i + 1]);
                }
            });
            return;
        }

        // Water / Glass: per-pixel shaders (fx_water / fx_glass) looking through the ribbon at the frame -
        // u runs along the trail in world units (scaled by Waves for water), v from the feet (0) to the top (1).
        float[] along = new float[n];
        for (int i = 1; i < n; i++)
            along[i] = along[i - 1] + (float) trails.get(i).position.distanceTo(trails.get(i - 1).position);
        float freq = s == Style.Water ? Math.max(0.05f, waveStrength.getValue()) : 1f;
        java.util.List<FxSurface.V> verts = new java.util.ArrayList<>();
        java.util.List<Float> alphas = new java.util.ArrayList<>();
        for (int i = 0; i + 1 < n; i++) {
            if (!ok[i] || !ok[i + 1]) continue;
            float f0 = (bottom[i] >>> 24) / 255f, f1 = (bottom[i + 1] >>> 24) / 255f;
            verts.add(new FxSurface.V(bx[i], by[i], along[i] * freq, 0f));
            verts.add(new FxSurface.V(tx[i], ty[i], along[i] * freq, 1f));
            verts.add(new FxSurface.V(tx[i + 1], ty[i + 1], along[i + 1] * freq, 1f));
            verts.add(new FxSurface.V(bx[i + 1], by[i + 1], along[i + 1] * freq, 0f));
            alphas.add(f0);
            alphas.add(f0);
            alphas.add(f1);
            alphas.add(f1);
        }
        float[] op = new float[alphas.size()];
        for (int i = 0; i < op.length; i++) op[i] = alphas.get(i);
        int rgb = trails.isEmpty() ? 0xFFFFFF : bottom[n - 1] & 0xFFFFFF;
        FxSurface.draw(context, s == Style.Water ? FxSurface.Kind.Water : FxSurface.Kind.Glass, verts, op, rgb, tint.getValue(), false);
    }

    /**
     * Blobs: round drops of water hanging in the air behind you along the trail, every "Blob Spacing" blocks -
     * each one a camera-facing disc drawn with the per-pixel water shader, so the world wobbles through it. They
     * shrink and fade as they age, and bob gently.
     */
    private void drawBlobs(DrawContext context) {
        var cam = mc.gameRenderer.getCamera();
        Vec3d forward = Vec3d.fromPolar(cam.getPitch(), cam.getYaw());
        Vec3d up = forward.crossProduct(new Vec3d(0, 1, 0)).normalize().crossProduct(forward).normalize();
        float time = (System.currentTimeMillis() % 100000L) / 1000f;
        final int seg = 18;

        java.util.List<FxSurface.V> verts = new java.util.ArrayList<>();
        java.util.List<Float> alphas = new java.util.ArrayList<>();
        Vec3d last = null;
        for (int idx = trails.size() - 1; idx >= 0; idx--) {           // newest first, so spacing starts at you
            Trail trail = trails.get(idx);
            if (last != null && trail.position.distanceTo(last) < blobSpacing.getValue()) continue;
            last = trail.position;
            float life = 1.0f - (float) trail.existed / sizeToRemove.getValue();
            if (life <= 0f) continue;
            float alpha = trailsAlpha.getValue() * life;
            double bob = Math.sin(time * 2.0 + idx * 0.37) * 0.08;
            Vec3d centre = trail.position.add(0, 1.0 + bob, 0);
            Vec3d sc = Render3DEngine.worldSpaceToScreenSpace(centre);
            Vec3d se = Render3DEngine.worldSpaceToScreenSpace(centre.add(up.multiply(blobSize.getValue() * (0.45 + 0.55 * life))));
            if (!(sc.z > 0 && sc.z < 1) || !(se.z > 0 && se.z < 1)) continue;
            float r = (float) Math.hypot(se.x - sc.x, se.y - sc.y);
            if (r < 0.5f) continue;
            r = Math.min(r, 90f);
            float cx = (float) sc.x, cy = (float) sc.y;
            // a slightly wobbly outline so the drops are not perfect circles
            for (int i = 0; i < seg; i++) {
                double a0 = Math.PI * 2 * i / seg, a1 = Math.PI * 2 * (i + 1) / seg;
                float w0 = 1f + 0.07f * (float) Math.sin(a0 * 3 + time * 2 + idx), w1 = 1f + 0.07f * (float) Math.sin(a1 * 3 + time * 2 + idx);
                float x0 = cx + (float) Math.cos(a0) * r * w0, y0 = cy + (float) Math.sin(a0) * r * w0;
                float x1 = cx + (float) Math.cos(a1) * r * w1, y1 = cy + (float) Math.sin(a1) * r * w1;
                float u0 = (float) (a0 / (Math.PI * 2)) * 2f + idx, u1 = (float) (a1 / (Math.PI * 2)) * 2f + idx;
                // v: 0 at the rim (soft edge) to 0.5 in the middle
                verts.add(new FxSurface.V(x0, y0, u0, 0f));
                verts.add(new FxSurface.V(cx, cy, u0, 0.5f));
                verts.add(new FxSurface.V(cx, cy, u1, 0.5f));
                verts.add(new FxSurface.V(x1, y1, u1, 0f));
                for (int k = 0; k < 4; k++) alphas.add(alpha);
            }
        }
        if (verts.isEmpty()) return;
        float[] op = new float[alphas.size()];
        for (int i = 0; i < op.length; i++) op[i] = alphas.get(i);
        FxSurface.draw(context, FxSurface.Kind.Water, verts, op, bottomColor.getValue().getColor(), tint.getValue(), false);
    }

    /**
     * Images: a sprite every "Image Spacing" blocks along the path, at chest height, facing the camera. Each one
     * pops in, gently bobs and turns, then shrinks and fades as it ages - one batched draw for the whole trail.
     */
    private void drawImages(DrawContext context, Matrix3x2f pose) {
        Identifier tex;
        if (image.is(TrailImage.Custom)) {
            if (customImage == null && System.currentTimeMillis() - customTriedAt > 3000) {
                customTriedAt = System.currentTimeMillis();
                try {
                    customImage = thunder.hack.utility.ThunderUtility.getCustomImg("trail");
                } catch (Exception e) {
                    sendMessage(".minecraft -> " + thunder.hack.core.manager.client.ConfigManager.CONFIG_FOLDER_NAME + " -> misc -> images -> trail.png");
                }
            }
            tex = customImage;
        } else {
            tex = TrailTextures.get(TrailTextures.Kind.valueOf(image.getValue().name()));
        }
        if (tex == null) return;

        var cam = mc.gameRenderer.getCamera();
        Vec3d forward = Vec3d.fromPolar(cam.getPitch(), cam.getYaw());
        Vec3d up = forward.crossProduct(new Vec3d(0, 1, 0)).normalize().crossProduct(forward).normalize();
        float time = (System.currentTimeMillis() % 100000L) / 1000f;
        int tintRgb = bottomColor.getValue().getColor() & 0xFFFFFF;

        java.util.List<Sprite> sprites = new java.util.ArrayList<>();
        Vec3d last = null;
        for (int idx = trails.size() - 1; idx >= 0; idx--) {               // newest first, spacing starts at you
            Trail trail = trails.get(idx);
            if (last != null && trail.position.distanceTo(last) < imageSpacing.getValue()) continue;
            last = trail.position;
            float life = 1.0f - (float) trail.existed / sizeToRemove.getValue();
            if (life <= 0f) continue;
            float age = 1f - life;
            float popIn = Math.min(1f, age * 8f);                            // quick pop when it is dropped
            float pop = (float) thunder.hack.utility.render.animation.EaseOutBack.dropAnimation(popIn);
            float scale = imageSize.getValue() * pop * (0.55f + 0.45f * life);
            double bob = Math.sin(time * 2.2 + idx * 0.41) * 0.06;
            Vec3d centre = trail.position.add(0, 1.0 + bob + age * 0.25, 0);   // drifts up a little as it fades
            Vec3d sc = Render3DEngine.worldSpaceToScreenSpace(centre);
            Vec3d se = Render3DEngine.worldSpaceToScreenSpace(centre.add(up.multiply(scale)));
            if (!(sc.z > 0 && sc.z < 1) || !(se.z > 0 && se.z < 1)) continue;
            float half = Math.min(90f, (float) Math.hypot(se.x - sc.x, se.y - sc.y));
            if (half < 0.6f) continue;
            float angle = imageSpin.getValue() ? (float) Math.sin(time * 1.6 + idx * 0.7) * 0.5f : 0f;
            float alpha = trailsAlpha.getValue() * life;
            int rgb = imageTint.getValue() ? tintRgb : 0xFFFFFF;
            int argb = (Math.round(MathHelper.clamp(alpha, 0f, 1f) * 255) << 24) | rgb;
            sprites.add(new Sprite((float) sc.x, (float) sc.y, half, angle, argb));
        }
        if (sprites.isEmpty()) return;

        // oldest first, so the newest sprite (by your feet) lands on top
        java.util.Collections.reverse(sprites);
        GuiRender.textured(context, tex, Render2DEngine.currentClip(), vc -> {
            for (Sprite s : sprites) {
                float cx = s.x(), cy = s.y(), h = s.half();
                float cos = (float) Math.cos(s.angle()) * h, sin = (float) Math.sin(s.angle()) * h;
                int c = s.argb();
                // corners of a square of half-size h turned by angle: (-1,-1) (1,-1) (1,1) (-1,1)
                GuiRender.vertexTex(vc, pose, cx + (-cos + sin), cy + (-sin - cos), 0f, 0f, c);
                GuiRender.vertexTex(vc, pose, cx + (-cos - sin), cy + (-sin + cos), 0f, 1f, c);
                GuiRender.vertexTex(vc, pose, cx + (cos - sin), cy + (sin + cos), 1f, 1f, c);
                GuiRender.vertexTex(vc, pose, cx + (cos + sin), cy + (sin - cos), 1f, 0f, c);
            }
        });
    }

    /**
     * The GUI pipelines cull back faces on 1.21.11, and a projected ribbon flips its winding whenever you see
     * it from the other side - that is why the trail vanished at some angles. Each quad is emitted in the
     * front-facing order (negative signed area in y-down screen space) whichever way it was projected.
     */
    private static boolean backFacing(float ax, float ay, float bx, float by, float cx, float cy, float dx, float dy) {
        float area = (ax * by - bx * ay) + (bx * cy - cx * by) + (cx * dy - dx * cy) + (dx * ay - ax * dy);
        return area > 0;
    }

    private static void frontQuad(VertexConsumer vc, Matrix3x2f pose,
                                  float ax, float ay, float bx, float by, float cx, float cy, float dx, float dy,
                                  int ca, int cb, int cc, int cd) {
        if (backFacing(ax, ay, bx, by, cx, cy, dx, dy))
            GuiRender.quad(vc, pose, dx, dy, cx, cy, bx, by, ax, ay, cd, cc, cb, ca);
        else
            GuiRender.quad(vc, pose, ax, ay, bx, by, cx, cy, dx, dy, ca, cb, cc, cd);
    }

    private static int argb(float r, float g, float b, float a) {
        return (Math.round(MathHelper.clamp(a, 0f, 1f) * 255) << 24)
                | (Math.round(MathHelper.clamp(r, 0f, 1f) * 255) << 16)
                | (Math.round(MathHelper.clamp(g, 0f, 1f) * 255) << 8)
                | Math.round(MathHelper.clamp(b, 0f, 1f) * 255);
    }

    /**
     * Ages a piece by time, not by frames: "Ticks" 300 = 2.4 s of life whatever the FPS (Celestial counted
     * frames, so a fast PC wiped the trail in a blink). {@code existed} stays the age in those units, so the
     * fade formula is unchanged.
     */
    boolean remove(Trail trail) {
        trail.existed = (int) ((System.currentTimeMillis() - trail.born) / 8L);
        return (float) trail.existed > sizeToRemove.getValue();
    }

    static class Trail {
        int existed;
        final long born = System.currentTimeMillis();
        Vec3d position;
        Vec3d color;

        public Trail(Vec3d position, Vec3d color) {
            this.position = position;
            this.color = color;
            this.existed = 0;
        }
    }
}
