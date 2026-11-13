package thunder.hack.features.modules.render;

import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;
import thunder.hack.features.modules.Module;
import thunder.hack.features.modules.client.HudEditor;
import thunder.hack.setting.Setting;
import thunder.hack.setting.impl.ColorSetting;
import thunder.hack.utility.render.JumpDistortRenderer;
import thunder.hack.utility.render.Render3DEngine;
import thunder.hack.utility.render.core.CloudanBatchRenderer;

import java.awt.Color;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * BKClient's Jump Circles ({@code systems/modules/modules/visuals/JumpCircles}, MIT, bkubiakdev), 1:1: a ring
 * on the ground where you take off. "Liquid" is BK's world-bending ripple (see {@link JumpDistortRenderer}); the
 * other styles are BK's textures, growing with the chosen easing and spinning down as they fade.
 */
public class JumpCircles extends Module {
    public JumpCircles() {
        super("JumpCircle", Category.RENDER);
    }

    /** BK's styles, then the former JumpCircle2 styles (Classic1-6, Custom, Water). */
    public enum Style {Liquid, Circle, Bold, Hexagon, Portal, SciFi, Soup, Classic1, Classic2, Classic3, Classic4, Classic5, Classic6, Custom, Water}

    public enum EasingMode {Elastic, BackOut, Linear}

    public enum ColorMode {Custom, Client, Rainbow}

    private final Setting<Style> style = new Setting<>("Style", Style.Liquid);
    private final Setting<EasingMode> easing = new Setting<>("Easing", EasingMode.Elastic,
            v -> style.is(Style.Liquid) && !advancedLiquidOn());
    private final Setting<ColorMode> colorMode = new Setting<>("ColorMode", ColorMode.Custom, v -> !hideColors() && !isClassic());

    private final Setting<Float> liveTime = new Setting<>("LiveTime", 2.5f, 0.5f, 5.0f, v -> !isClassic());
    private final Setting<Float> scale = new Setting<>("Scale", 75f, 10f, 300f, v -> !isClassic());
    private final Setting<Float> spinSpeed = new Setting<>("SpinSpeed", 180f, 0f, 360f, v -> !style.is(Style.Liquid) && !isClassic());
    private final Setting<Float> alpha = new Setting<>("Alpha", 128f, 0f, 255f, v -> !style.is(Style.Liquid) && !isClassic());
    private final Setting<Boolean> fadeOut = new Setting<>("FadeOut", true, v -> !style.is(Style.Liquid) && !isClassic());

    private final Setting<ColorSetting> color = new Setting<>("Color", new ColorSetting(new Color(100, 180, 255).getRGB()),
            v -> !isClassic() && !hideColors() && colorMode.is(ColorMode.Custom));
    private final Setting<Boolean> useSecondColor = new Setting<>("SecondColor", false,
            v -> !isClassic() && !hideColors() && colorMode.is(ColorMode.Custom) && !(style.is(Style.Liquid) && !advancedLiquidOn()));
    private final Setting<ColorSetting> secondColor = new Setting<>("SecondColorPicker", new ColorSetting(new Color(220, 100, 255).getRGB()),
            v -> !isClassic() && !hideColors() && colorMode.is(ColorMode.Custom) && useSecondColor.getValue() && !(style.is(Style.Liquid) && !advancedLiquidOn()));

    // Liquid / distortion
    private final Setting<Float> distortStrength = new Setting<>("DistortStrength", 1.5f, 0.1f, 3.0f, v -> style.is(Style.Liquid));
    private final Setting<Float> distortThickness = new Setting<>("DistortWidth", 1.0f, 0.1f, 2.0f, v -> !hideLiquidDetails());
    private final Setting<Boolean> distortWarp = new Setting<>("Turbulence", true, v -> !hideLiquidDetails());
    private final Setting<Float> distortWarpStrength = new Setting<>("TurbulenceStrength", 1.0f, 0.1f, 2.0f,
            v -> !hideLiquidDetails() && distortWarp.getValue());

    // Liquid smoke
    private final Setting<Boolean> distortGlow = new Setting<>("LiquidSmoke", false, v -> style.is(Style.Liquid));
    private final Setting<Float> distortGlowHeight = new Setting<>("SmokeHeight", 30f, 10f, 50f, v -> !hideLiquidDetails() && distortGlow.getValue());
    private final Setting<Float> distortGlowWidth = new Setting<>("SmokeWidth", 18f, 5f, 30f, v -> !hideLiquidDetails() && distortGlow.getValue());
    private final Setting<Float> distortGlowTint = new Setting<>("SmokeTint", 50f, 0f, 100f, v -> !hideLiquidDetails() && distortGlow.getValue());
    private final Setting<Float> distortGlowAlpha = new Setting<>("SmokeAlpha", 50f, 0f, 100f, v -> !hideLiquidDetails() && distortGlow.getValue());

    private final Setting<Boolean> distortTint = new Setting<>("WaveTint", true, v -> style.is(Style.Liquid));
    private final Setting<Float> distortTintStrength = new Setting<>("TintStrength", 55f, 0f, 100f, v -> !hideLiquidDetails() && distortTint.getValue());
    private final Setting<Float> distortSaturation = new Setting<>("Saturation", 0.0f, -1.0f, 1.0f, v -> !hideLiquidDetails());

    private final Setting<Boolean> advancedLiquid = new Setting<>("AdvancedLiquid", false, v -> style.is(Style.Liquid));

    // Former JumpCircle2 (Classic, Custom, Water)
    private final Setting<Boolean> easeOut = new Setting<>("EaseOut", true, v -> isClassic());
    private final Setting<Float> rotateSpeed = new Setting<>("RotateSpeed", 2f, 0.5f, 5f, v -> isClassic() && !style.is(Style.Water));
    private final Setting<Float> circleScale = new Setting<>("CircleScale", 1f, 0.5f, 5f, v -> isClassic());
    private final Setting<Boolean> onlySelf = new Setting<>("OnlySelf", false, v -> isClassic());
    private final Setting<Boolean> throughWalls = new Setting<>("ThroughWalls", false, v -> isClassic() && !style.is(Style.Water));
    private final Setting<Float> waveStrength = new Setting<>("WaveStrength", 4f, 0f, 10f, v -> style.is(Style.Water));
    private final Setting<Float> waterLift = new Setting<>("WaterHeight", 0.12f, 0f, 1f, v -> style.is(Style.Water));
    private final Setting<Float> waterTint = new Setting<>("WaterTint", 0.18f, 0f, 1f, v -> style.is(Style.Water));
    private final Setting<ColorSetting> waterColor = new Setting<>("WaterColor", new ColorSetting(0xFFFFFFFF), v -> style.is(Style.Water));

    private boolean isClassic() {
        return style != null && style.getValue().ordinal() >= Style.Classic1.ordinal();
    }

    private boolean advancedLiquidOn() {
        return advancedLiquid != null && advancedLiquid.getValue();
    }

    private boolean hideLiquidDetails() {
        return !style.is(Style.Liquid) || !advancedLiquidOn();
    }

    private boolean hideColors() {
        return style.is(Style.Liquid) && distortTint != null && distortGlow != null
                && !distortTint.getValue() && !distortGlow.getValue();
    }

    private final List<Ring> circles = new CopyOnWriteArrayList<>();
    private final float[] uniformScratch = new float[JumpDistortRenderer.FLOATS];
    private final Matrix4f invViewProj = new Matrix4f();
    private boolean wasJumping;

    @Override
    public void onDisable() {
        circles.clear();
        classicCircles.clear();
        classicCache.clear();
        wasJumping = false;
    }

    @Override
    public void onUpdate() {
        if (mc.player == null) return;
        if (isClassic()) {
            updateClassic();
            return;
        }
        classicCircles.clear();
        boolean jumping = !mc.player.isOnGround() && !mc.player.isClimbing() && !mc.player.isSubmergedInWater();
        if (jumping && !wasJumping) circles.add(new Ring(mc.player.getEntityPos().add(0, 0.01, 0)));
        wasJumping = jumping;
        circles.removeIf(Ring::isExpired);
    }

    @Override
    public void onRender3D(MatrixStack matrices) {
        if (isClassic()) {
            renderClassic3D();
            return;
        }
        if (circles.isEmpty() || mc.player == null) return;
        if (style.is(Style.Liquid)) {
            renderLiquid();
            return;
        }
        for (Ring c : circles) c.renderTextured();
    }

    /** BK's onRenderWorldPost, filling the Ripples block the same way. */
    private void renderLiquid() {
        if (JumpDistortRenderer.isBroken()) return;
        var fb = mc.getFramebuffer();
        if (fb == null || fb.getColorAttachment() == null) return;
        int fw = fb.getColorAttachment().getWidth(0), fh = fb.getColorAttachment().getHeight(0);
        if (fw <= 0 || fh <= 0) return;

        long l = System.currentTimeMillis();
        float f = Math.max(0.1f, liveTime.getValue());
        float f2 = (scale.getValue() / 100f) * 2.0f;
        float f3 = Math.max(0.15f, f2 * distortThickness.getValue() / 2.0f);
        float f4 = 0.018f * distortStrength.getValue();
        float f5 = (float) fw / (float) Math.max(1, fh);
        boolean bl = distortTint.getValue();
        boolean bl2 = distortGlow.getValue();
        boolean bl3 = bl || bl2;
        Vec3d cam = mc.gameRenderer.getCamera().getCameraPos();

        invViewProj.set(Render3DEngine.lastProjMat).mul(Render3DEngine.lastWorldSpaceMatrix).invert();
        float[] a = uniformScratch;
        java.util.Arrays.fill(a, 0f);
        int n = 0;
        float f6 = 0.0f;

        for (Ring ring : circles) {
            if (n >= 16) break;
            float f7 = ring.getProgress(f);
            if (f7 < 0.0f || f7 >= 1.0f) continue;

            float f8 = getEasingProgress(f7);
            float f9 = MathHelper.clamp(f7 / 0.1f, 0.0f, 1.0f);
            float f10 = MathHelper.clamp((1.0f - f7) / 0.3f, 0.0f, 1.0f);
            float f11 = f9 * f10;
            float f12 = f4 * f11;

            int n3 = 28 + n * 8;
            a[n3] = (float) (ring.position.x - cam.x);
            a[n3 + 1] = (float) (ring.position.y - cam.y);
            a[n3 + 2] = (float) (ring.position.z - cam.z);
            a[n3 + 3] = f8 * f2;
            a[n3 + 4] = f3;
            a[n3 + 5] = f12;
            a[n3 + 6] = f2;
            a[n3 + 7] = f11;

            if (bl3) {
                int n4 = 156 + n * 4 * 4;
                int n5 = (int) f6;
                putColor(a, n4, getColorAt(n5, 1.0f));
                putColor(a, n4 + 4, getColorAt(90 + n5, 1.0f));
                putColor(a, n4 + 8, getColorAt(180 + n5, 1.0f));
                putColor(a, n4 + 12, getColorAt(270 + n5, 1.0f));
            }

            f6 += 45.0f * (1.0f - f7);
            ++n;
        }
        if (n == 0) return;

        a[0] = n;
        a[1] = f5;
        a[2] = (float) (l % 100000L) / 1000.0f;
        a[3] = distortWarp.getValue() ? 0.01f * distortWarpStrength.getValue() : 0.0f;
        a[4] = bl ? MathHelper.clamp(distortTintStrength.getValue() / 100.0f, 0.0f, 1.0f) : 0.0f;
        a[5] = MathHelper.clamp(1.0f + distortSaturation.getValue(), 0.0f, 2.0f);
        a[6] = bl2 ? 1.0f : 0.0f;
        a[7] = 0.6f;
        a[8] = MathHelper.clamp(distortGlowHeight.getValue() / 100.0f, 0.05f, 5.0f);
        a[9] = MathHelper.clamp(distortGlowWidth.getValue() / 100.0f, 0.05f, 5.0f);
        a[10] = MathHelper.clamp(distortGlowTint.getValue() / 100.0f, 0.0f, 1.0f);
        a[11] = MathHelper.clamp(distortGlowAlpha.getValue() / 100.0f, 0.0f, 1.0f);
        invViewProj.get(a, 12);

        JumpDistortRenderer.apply(a);
    }

    public float getEasingProgress(float t) {
        float clamped = MathHelper.clamp(t, 0.0f, 1.0f);
        if (easing.is(EasingMode.Elastic)) return elasticOut(clamped);
        if (easing.is(EasingMode.BackOut)) return backOut(clamped);
        return clamped;
    }

    private static float backOut(float f) {
        return (float) (1.0 + 2.70158 * Math.pow((double) f - 1.0, 3.0) + 1.70158 * Math.pow((double) f - 1.0, 2.0));
    }

    private static float elasticOut(float f) {
        if (f <= 0.0f) return 0.0f;
        if (f >= 1.0f) return 1.0f;
        return (float) (Math.pow(2.0, -10.0 * (double) f) * Math.sin(((double) f * 10.0 - 0.75) * 2.0943951023931953) + 1.0);
    }

    private static Color mix(Color a, Color b, float t) {
        t = MathHelper.clamp(t, 0f, 1f);
        return new Color(
                Math.round(a.getRed() + (b.getRed() - a.getRed()) * t),
                Math.round(a.getGreen() + (b.getGreen() - a.getGreen()) * t),
                Math.round(a.getBlue() + (b.getBlue() - a.getBlue()) * t),
                Math.round(a.getAlpha() + (b.getAlpha() - a.getAlpha()) * t));
    }

    public int getColorAt(int offsetDeg, float alphaMultiplier) {
        if (colorMode.is(ColorMode.Rainbow)) {
            int hue = (int) ((System.currentTimeMillis() / 8L + offsetDeg) % 360L);
            int rgb = Color.HSBtoRGB(hue / 360f, 1.0f, 1.0f);
            int al = Math.round(MathHelper.clamp(alphaMultiplier, 0.0f, 1.0f) * 255.0f);
            return (al << 24) | (rgb & 0x00FFFFFF);
        }
        if (colorMode.is(ColorMode.Client)) {
            Color c1 = HudEditor.getColor(0);
            Color c2 = mix(c1, new Color(255, 120, 220), 0.45f);
            float blend = (float) (Math.sin((System.currentTimeMillis() + offsetDeg * 10L) / 400.0) + 1.0) / 2.0f;
            Color c = mix(c1, c2, blend);
            int al = Math.round(MathHelper.clamp(alphaMultiplier, 0.0f, 1.0f) * 255.0f);
            return (al << 24) | (c.getRGB() & 0x00FFFFFF);
        }
        Color c1 = color.getValue().getColorObject();
        if (useSecondColor.getValue()) {
            Color c2 = secondColor.getValue().getColorObject();
            float blend = (float) (Math.sin((System.currentTimeMillis() + offsetDeg * 10L) / 400.0) + 1.0) / 2.0f;
            Color c = mix(c1, c2, blend);
            int al = Math.round(MathHelper.clamp(alphaMultiplier, 0.0f, 1.0f) * c1.getAlpha());
            return (al << 24) | (c.getRGB() & 0x00FFFFFF);
        }
        int al = Math.round(MathHelper.clamp(alphaMultiplier, 0.0f, 1.0f) * c1.getAlpha());
        return (al << 24) | (c1.getRGB() & 0x00FFFFFF);
    }

    private static void putColor(float[] array, int offset, int rgba) {
        array[offset] = ((rgba >> 16) & 0xFF) / 255.0f;
        array[offset + 1] = ((rgba >> 8) & 0xFF) / 255.0f;
        array[offset + 2] = (rgba & 0xFF) / 255.0f;
        array[offset + 3] = ((rgba >>> 24) & 0xFF) / 255.0f;
    }

    private Identifier texture() {
        String name = switch (style.getValue()) {
            case Bold -> "circle_bold_unblack.png";
            case Hexagon -> "hexagon_unblack.png";
            case Portal -> "portal_unblack.png";
            case SciFi -> "scifi.png";
            case Soup -> "circle_unblack.png";
            default -> "circle.png";
        };
        return Identifier.of("bkclient", "textures/jump_circles/" + name);
    }

    private class Ring {
        private final Vec3d position;
        private final long startTime;
        private float rotationAngle;
        private long lastUpdateTime;

        Ring(Vec3d position) {
            this.position = position;
            this.startTime = System.currentTimeMillis();
            this.lastUpdateTime = startTime;
        }

        float getProgress(float totalLifetimeSec) {
            return (System.currentTimeMillis() - startTime) / 1000f / totalLifetimeSec;
        }

        boolean isExpired() {
            return (System.currentTimeMillis() - startTime) / 1000f > liveTime.getValue();
        }

        void renderTextured() {
            updateRotation();
            float rawProgress = (System.currentTimeMillis() - startTime) / (liveTime.getValue() * 1000f);
            float eased = getEasingProgress(rawProgress);
            float radius = eased * (scale.getValue() / 100f) * 2.0f;
            if (radius <= 0.001f) return;
            float finalAlpha = alpha.getValue();
            if (fadeOut.getValue()) finalAlpha *= (1.0f - MathHelper.clamp(rawProgress, 0f, 1f));
            Color tint = new Color(getColorAt(0, finalAlpha / 255.0f), true);
            CloudanBatchRenderer.groundSprite(texture(), position, radius, rotationAngle, tint, false);
        }

        private void updateRotation() {
            long now = System.currentTimeMillis();
            float dt = Math.min((now - lastUpdateTime) / 1000f, 0.1f);
            lastUpdateTime = now;
            float angularVelocity = (float) Math.toRadians(spinSpeed.getValue());
            float total = liveTime.getValue();
            float elapsed = (now - startTime) / 1000f;
            float remaining = MathHelper.clamp((total - elapsed) / total, 0, 1);
            rotationAngle += angularVelocity * dt * remaining;
        }
    }

    /* ------------------------------------------------------------------ */
    /* Former JumpCircle2: Classic1-6, Custom, Water                       */
    /* ------------------------------------------------------------------ */

    /** How long one classic ring lives, in ms. */
    private static final long CLASSIC_LIFETIME_MS = 1100L;

    private record ClassicCircle(Vec3d pos, thunder.hack.utility.Timer timer) {
    }

    private final List<ClassicCircle> classicCircles = new CopyOnWriteArrayList<>();
    private final List<net.minecraft.entity.player.PlayerEntity> classicCache = new CopyOnWriteArrayList<>();
    private Identifier custom;

    private void updateClassic() {
        if (mc.world == null) return;
        circles.clear();
        if (style.is(Style.Custom) && custom == null) {
            try {
                custom = thunder.hack.utility.ThunderUtility.getCustomImg("circle");
            } catch (Exception e) {
                sendMessage(".minecraft -> " + thunder.hack.core.manager.client.ConfigManager.CONFIG_FOLDER_NAME + " -> misc -> images -> circle.png");
            }
        }

        for (net.minecraft.entity.player.PlayerEntity pl : mc.world.getPlayers())
            if (!classicCache.contains(pl) && pl.isOnGround() && (mc.player == pl || !onlySelf.getValue()))
                classicCache.add(pl);

        classicCache.forEach(pl -> {
            if (pl != null && !pl.isOnGround()) {
                // only a real jump: leaving the ground while rising, not walking off a ledge
                boolean rising = pl.getY() - pl.lastY > 0.02 || (pl == mc.player && pl.getVelocity().y > 0.1);
                if (rising)
                    classicCircles.add(new ClassicCircle(new Vec3d(pl.getX(), Math.floor(pl.lastY) + 0.03, pl.getZ()), new thunder.hack.utility.Timer()));
                classicCache.remove(pl);
            }
        });

        classicCircles.removeIf(c -> c.timer().passedMs(CLASSIC_LIFETIME_MS));
    }

    private Identifier classicTexture() {
        if (style.is(Style.Custom)) return custom;
        return thunder.hack.utility.render.TextureStorage.jumpCircles[style.getValue().ordinal() - Style.Classic1.ordinal()];
    }

    /** Flat textured rings on the ground, one quad each. */
    private void renderClassic3D() {
        if (classicCircles.isEmpty() || mc.player == null || style.is(Style.Water)) return;
        final Identifier texture = classicTexture();
        if (texture == null) return;

        for (ClassicCircle c : classicCircles) {
            long passed = c.timer().getPassedTimeMs();
            float t = passed / (float) CLASSIC_LIFETIME_MS;
            if (t < 0f || t >= 1f) continue;

            float grow = easeOut.getValue() ? thunder.hack.utility.render.animation.Easing.CubicOut.applyClamped(t) : t;
            float radius = circleScale.getValue() * (0.4f + grow * 1.6f);
            float fade = 1f - t * t;

            Color tint = thunder.hack.utility.render.Render2DEngine.applyOpacity(HudEditor.getColor(0), fade);
            double angle = passed / 1000.0 * rotateSpeed.getValue();
            CloudanBatchRenderer.groundSprite(texture, c.pos(), radius, angle, tint, throughWalls.getValue());
        }
    }

    /** Water style: one ripple spreading over the ground, drawn with the per-pixel water shader in ring mode. */
    @Override
    public void onRender2D(net.minecraft.client.gui.DrawContext context) {
        if (!style.is(Style.Water) || classicCircles.isEmpty() || mc.player == null) return;
        final int seg = 72;
        int rgb = waterColor.getValue().getColor();

        for (ClassicCircle c : classicCircles) {
            float t = c.timer().getPassedTimeMs() / (float) CLASSIC_LIFETIME_MS;
            if (t < 0f || t >= 1f) continue;
            float grow = easeOut.getValue() ? thunder.hack.utility.render.animation.Easing.CubicOut.applyClamped(t) : t;
            float r = circleScale.getValue() * (0.15f + grow * 1.8f);
            float w = circleScale.getValue() * (0.16f + 0.1f * (1f - grow));
            float fade = (float) Math.pow(1f - t, 1.3);
            float inner = Math.max(0.01f, r - w), outer = r + w;

            Vec3d centre = c.pos().add(0, waterLift.getValue(), 0);
            float[][] in = new float[seg + 1][], out = new float[seg + 1][];
            for (int i = 0; i <= seg; i++) {
                double a = Math.PI * 2 * i / seg;
                in[i] = project(centre, Math.cos(a) * inner, Math.sin(a) * inner);
                out[i] = project(centre, Math.cos(a) * outer, Math.sin(a) * outer);
            }
            List<thunder.hack.utility.render.FxSurface.V> verts = new java.util.ArrayList<>();
            for (int i = 0; i < seg; i++) {
                if (in[i] == null || in[i + 1] == null || out[i] == null || out[i + 1] == null) continue;
                float u0 = (float) (Math.PI * 2 * i / seg * r), u1 = (float) (Math.PI * 2 * (i + 1) / seg * r);
                verts.add(new thunder.hack.utility.render.FxSurface.V(in[i][0], in[i][1], u0, 0f));
                verts.add(new thunder.hack.utility.render.FxSurface.V(out[i][0], out[i][1], u0, 1f));
                verts.add(new thunder.hack.utility.render.FxSurface.V(out[i + 1][0], out[i + 1][1], u1, 1f));
                verts.add(new thunder.hack.utility.render.FxSurface.V(in[i + 1][0], in[i + 1][1], u1, 0f));
            }
            float[] op = new float[verts.size()];
            java.util.Arrays.fill(op, Math.min(1f, fade * waveStrength.getValue() / 4f));
            thunder.hack.utility.render.FxSurface.draw(context, thunder.hack.utility.render.FxSurface.Kind.Water, verts, op, rgb, waterTint.getValue(), true);
        }
    }

    private static float[] project(Vec3d centre, double dx, double dz) {
        Vec3d s = Render3DEngine.worldSpaceToScreenSpace(centre.add(dx, 0, dz));
        return s.z > 0 && s.z < 1 ? new float[]{(float) s.x, (float) s.y} : null;
    }
}
