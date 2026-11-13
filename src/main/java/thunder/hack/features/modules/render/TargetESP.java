package thunder.hack.features.modules.render;

import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.DepthTestFunction;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.RenderSetup;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.RotationAxis;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import thunder.hack.features.modules.Module;
import thunder.hack.features.modules.client.HudEditor;
import thunder.hack.features.modules.combat.Aura;
import thunder.hack.setting.Setting;
import thunder.hack.setting.impl.ColorSetting;
import thunder.hack.utility.render.Render3DEngine;
import thunder.hack.utility.render.Render3DUtil;

import java.awt.Color;
import java.util.HashMap;
import java.util.Map;

/**
 * BKClient's Target ESP ({@code systems/modules/modules/visuals/TargetESP}, MIT, bkubiakdev): the billboard modes
 * with BK's own maths, textures and additive glow - Souls, Marker (all ten designs, souls or crystals motion),
 * Circles, Ring, Ring 2 and Ghosts - on the target you aim at (up to 24 blocks) or the Aura's target.
 */
public class TargetESP extends Module {
    public TargetESP() {
        super("TargetESP", Category.RENDER);
    }

    public enum Source {Crosshair, Aura}

    public enum Mode {Souls, Crystals, Marker, Circles, Ring, Ring2, Ghosts, GlassGhosts, Cubes, FragScan}

    public enum Design {AmongUs, Skull, Vegas, Marker, SciFi, Simple, Bo, Jeka, Legacy, Nova}

    public enum MarkerMotion {Nursultan, Souls, Crystals}

    public enum ColorSource {Theme, Custom, Rainbow}

    private final Setting<Source> targetSource = new Setting<>("TargetSource", Source.Crosshair);
    private final Setting<Mode> mode = new Setting<>("Mode", Mode.Souls);
    private final Setting<Boolean> distortion = new Setting<>("Distortion", true, v -> isRefractiveMode());
    private final Setting<Float> distortionStrength = new Setting<>("DistortionStrength", 0.10f, 0.05f, 0.15f,
            v -> isRefractiveMode() && distortion.getValue());
    private final Setting<Boolean> effectLighting = new Setting<>("Lighting", false, v -> isRefractiveMode());
    private final Setting<Float> scanDuration = new Setting<>("ScanDuration", 1.6f, 0.5f, 6f, v -> mode.is(Mode.FragScan));
    private final Setting<Float> scanWidth = new Setting<>("ScanWidth", 30f, 5f, 80f, v -> mode.is(Mode.FragScan));
    private final Setting<Float> scanStrength = new Setting<>("ScanStrength", 1f, 0.1f, 2f, v -> mode.is(Mode.FragScan));
    private final Setting<Design> design =new Setting<>("Design", Design.Marker, v -> mode.is(Mode.Marker));
    private final Setting<MarkerMotion> animationStyle = new Setting<>("AnimationStyle", MarkerMotion.Nursultan, v -> mode.is(Mode.Marker));
    private final Setting<Boolean> players = new Setting<>("Players", true, v -> targetSource.is(Source.Crosshair));
    private final Setting<Boolean> mobs = new Setting<>("Mobs", true, v -> targetSource.is(Source.Crosshair));
    private final Setting<Float> speed = new Setting<>("Speed", 0.5f, 0.1f, 1.0f);
    private final Setting<Float> size = new Setting<>("Size", 0.5f, 0.1f, 1.0f);
    private final Setting<Float> particleThickness = new Setting<>("ParticleThickness", 1.0f, 0.1f, 2.0f, v -> mode.is(Mode.Ring2));
    private final Setting<Boolean> changeColorOnDamage = new Setting<>("DamageColor", true);
    private final Setting<ColorSource> colorSource = new Setting<>("ColorSource", ColorSource.Custom);
    private final Setting<ColorSetting> colorTarget = new Setting<>("Color", new ColorSetting(new Color(140, 120, 255).getRGB()),
            v -> colorSource.is(ColorSource.Custom));

    /* ------------------------------------------------------------------ */
    /* BK's animation state                                                */
    /* ------------------------------------------------------------------ */

    /** BK's Animation(duration, easing) with update(target). */
    private static final class Anim {
        final long duration;
        final java.util.function.Function<Float, Float> easing;
        float value, start, target;
        long startTime;

        Anim(long duration, java.util.function.Function<Float, Float> easing) {
            this.duration = duration;
            this.easing = easing;
        }

        float update(float t) {
            long now = System.currentTimeMillis();
            if (t != target) {
                start = value;
                target = t;
                startTime = now;
            }
            long elapsed = now - startTime;
            value = elapsed >= duration ? target : start + (target - start) * easing.apply((float) elapsed / duration);
            return value;
        }
    }

    private static float figmaEaseInOut(float progress) {
        // cubic-bezier(0.42, 0, 0.58, 1)
        float x1 = 0.42f, x2 = 0.58f, y1 = 0f, y2 = 1f;
        float t = progress;
        for (int i = 0; i < 8; i++) {
            float x = 3f * (1f - t) * (1f - t) * t * x1 + 3f * (1f - t) * t * t * x2 + t * t * t;
            float dx = 3f * ((1f - t) * (1f - 3f * t) * x1 + (2f * t - 3f * t * t) * x2) + 3f * t * t;
            if (Math.abs(x - progress) < 1e-5f || Math.abs(dx) < 1e-6f) break;
            t = MathHelper.clamp(t - (x - progress) / dx, 0f, 1f);
        }
        return 3f * (1f - t) * (1f - t) * t * y1 + 3f * (1f - t) * t * t * y2 + t * t * t;
    }

    private final Anim animation = new Anim(300L, TargetESP::figmaEaseInOut);
    private final Anim moving = new Anim(70L, t -> t);
    private final Anim markerRotation = new Anim(50L, t -> t);

    private LivingEntity target;
    private LivingEntity prevTarget;
    private float ringDamageFlashIntensity;
    private long lastRingDamageTime;
    private static final long RING_DAMAGE_FLASH_DURATION = 300L;
    private static final double TARGET_REACH = 24.0;

    private static final Identifier BLOOM_TEXTURE = Identifier.of("bkclient", "textures/bloom.png");
    private static final Identifier GLOW_TEXTURE = Identifier.of("bkclient", "textures/target_render/glow.png");

    /* ------------------------------------------------------------------ */
    /* Additive world layers (BK: blendFunc(SRC_ALPHA, ONE), no depth write) */
    /* ------------------------------------------------------------------ */

    private static RenderPipeline spritePipeline(String path, DepthTestFunction depth) {
        return RenderPipeline.builder(RenderPipelines.POSITION_TEX_COLOR_SNIPPET)
                .withLocation(Identifier.of("gombal", path))
                .withVertexFormat(VertexFormats.POSITION_TEXTURE_COLOR, VertexFormat.DrawMode.QUADS)
                .withDepthTestFunction(depth)
                .withDepthWrite(false)
                .withCull(false)
                .withBlend(BlendFunction.LIGHTNING)
                .build();
    }

    private static RenderPipeline colorPipeline(String path, DepthTestFunction depth) {
        return RenderPipeline.builder(RenderPipelines.POSITION_COLOR_SNIPPET)
                .withLocation(Identifier.of("gombal", path))
                .withVertexFormat(RenderPipelines.DEBUG_QUADS.getVertexFormat(), RenderPipelines.DEBUG_QUADS.getVertexFormatMode())
                .withDepthTestFunction(depth)
                .withDepthWrite(false)
                .withCull(false)
                .withBlend(BlendFunction.LIGHTNING)
                .build();
    }

    private static final Map<DepthTestFunction, RenderPipeline> SPRITE_PIPELINES = new HashMap<>();
    private static final Map<DepthTestFunction, RenderLayer> COLOR_LAYERS = new HashMap<>();
    private static final Map<DepthTestFunction, Map<Identifier, RenderLayer>> SPRITE_LAYERS = new HashMap<>();

    private static String suffix(DepthTestFunction depth) {
        return depth.name().toLowerCase(java.util.Locale.ROOT);
    }

    private static RenderLayer colorLayer(DepthTestFunction depth) {
        return COLOR_LAYERS.computeIfAbsent(depth, d -> RenderLayer.of("bk_target_color_" + suffix(d),
                RenderSetup.builder(colorPipeline("pipeline/bk_target_color_" + suffix(d), d)).build()));
    }

    private static RenderLayer colorLayer(boolean depth) {
        return colorLayer(depth ? DepthTestFunction.LEQUAL_DEPTH_TEST : DepthTestFunction.NO_DEPTH_TEST);
    }

    private static net.minecraft.client.gl.GpuSampler sampler;

    private static net.minecraft.client.gl.GpuSampler sampler() {
        if (sampler == null)
            sampler = com.mojang.blaze3d.systems.RenderSystem.getDevice().createSampler(
                    com.mojang.blaze3d.textures.AddressMode.CLAMP_TO_EDGE, com.mojang.blaze3d.textures.AddressMode.CLAMP_TO_EDGE,
                    com.mojang.blaze3d.textures.FilterMode.LINEAR, com.mojang.blaze3d.textures.FilterMode.LINEAR,
                    1, java.util.OptionalDouble.empty());
        return sampler;
    }

    private static RenderLayer spriteLayer(Identifier texture, DepthTestFunction depth) {
        RenderPipeline pipeline = SPRITE_PIPELINES.computeIfAbsent(depth, d -> spritePipeline("pipeline/bk_target_sprite_" + suffix(d), d));
        return SPRITE_LAYERS.computeIfAbsent(depth, d -> new HashMap<>()).computeIfAbsent(texture, id ->
                RenderLayer.of("bk_target_sprite_" + suffix(depth) + "_" + id.getPath(),
                        RenderSetup.builder(pipeline).texture("Sampler0", id, TargetESP::sampler).build()));
    }

    /** One textured draw: camera-relative positions, rotated into view like Render3DEngine's world sprites. */
    private final class SpriteBatch {
        final RenderLayer layer;
        final VertexConsumer buffer;
        final Matrix4f view;
        final Quaternionf rotation;
        final Vector3f corner = new Vector3f();

        SpriteBatch(Identifier texture, boolean depth) {
            this(texture, depth ? DepthTestFunction.LEQUAL_DEPTH_TEST : DepthTestFunction.NO_DEPTH_TEST);
        }

        SpriteBatch(Identifier texture, DepthTestFunction depth) {
            layer = spriteLayer(texture, depth);
            buffer = Render3DUtil.buffer(layer);
            view = viewMatrix();
            rotation = mc.gameRenderer.getCamera().getRotation();
        }

        /** A camera-facing square of side {@code size} centred on (x, y, z), camera-relative. */
        void billboard(double x, double y, double z, float size, int argb, boolean flip) {
            if (size <= 0f || (argb >>> 24) == 0) return;
            float h = size / 2f;
            float[][] offsets = {{-h, -h}, {h, -h}, {h, h}, {-h, h}};
            float[][] uvs = flip ? new float[][]{{0, 0}, {1, 0}, {1, 1}, {0, 1}} : new float[][]{{0, 1}, {1, 1}, {1, 0}, {0, 0}};
            for (int i = 0; i < 4; i++) {
                corner.set(offsets[i][0], offsets[i][1], 0f);
                rotation.transform(corner);
                Render3DUtil.vertexTex(buffer, view, (float) x + corner.x, (float) y + corner.y, (float) z + corner.z,
                        uvs[i][0], uvs[i][1], argb);
            }
        }

        /** As {@link #billboard}, spun by {@code angle} radians around the view axis. */
        void billboardRotated(double x, double y, double z, float size, float angle, int argb, boolean flip) {
            if (size <= 0f || (argb >>> 24) == 0) return;
            float h = size / 2f, cos = (float) Math.cos(angle), sin = (float) Math.sin(angle);
            float[][] offsets = {{-h, -h}, {h, -h}, {h, h}, {-h, h}};
            float[][] uvs = flip ? new float[][]{{0, 0}, {1, 0}, {1, 1}, {0, 1}} : new float[][]{{0, 1}, {1, 1}, {1, 0}, {0, 0}};
            for (int i = 0; i < 4; i++) {
                float ox = offsets[i][0], oy = offsets[i][1];
                corner.set(ox * cos - oy * sin, ox * sin + oy * cos, 0f);
                rotation.transform(corner);
                Render3DUtil.vertexTex(buffer, view, (float) x + corner.x, (float) y + corner.y, (float) z + corner.z,
                        uvs[i][0], uvs[i][1], argb);
            }
        }

        void flush() {
            Render3DUtil.flush(layer);
        }
    }

    private static Matrix4f viewMatrix() {
        Camera camera = mc.gameRenderer.getCamera();
        MatrixStack matrices = new MatrixStack();
        matrices.multiply(RotationAxis.POSITIVE_X.rotationDegrees(camera.getPitch()));
        matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(camera.getYaw() + 180f));
        return new Matrix4f(matrices.peek().getPositionMatrix());
    }

    /* ------------------------------------------------------------------ */
    /* Frame                                                               */
    /* ------------------------------------------------------------------ */

    @Override
    public void onDisable() {
        target = prevTarget = null;
        cubes.clear();
        scanTarget = null;
        animation.value = animation.start = animation.target = 0f;
    }

    @Override
    public void onRender3D(MatrixStack matrices) {
        if (mc.world == null || mc.player == null) return;
        LivingEntity targetFound = getTarget();
        if (targetSource.is(Source.Aura) && targetFound == null) {
            target = prevTarget = null;
            animation.value = animation.start = animation.target = 0f;
            return;
        }
        if (mode.is(Mode.FragScan)) {
            cubes.clear();
            renderScan(targetFound);
            return;
        }
        scanTarget = null;
        if (mode.is(Mode.Cubes)) {
            renderCubes(targetFound);
            return;
        }
        cubes.clear();
        if (targetFound != null) target = targetFound;

        animation.update(targetFound != null ? 1f : 0f);
        float speedMul = speed.getValue();
        if (mode.is(Mode.Crystals)) speedMul *= 2.0F;
        moving.update(moving.value + 10.0f * speedMul + 50.0f * speedMul);
        markerRotation.update(markerRotation.value + 5.0f * speedMul);

        if (targetFound != null && prevTarget == targetFound && targetFound.hurtTime > 0 && mode.is(Mode.Ring))
            lastRingDamageTime = System.currentTimeMillis();
        if (target != null) prevTarget = target;
        if (prevTarget == null || animation.value == 0.0f) return;
        if (prevTarget.isRemoved() || prevTarget.getEntityWorld() != mc.world) {
            prevTarget = target = null;
            return;
        }

        if (isRefractiveMode()) {
            renderRefractive(prevTarget);
            return;
        }

        // BK: depth-tested, unless a wall stands between the camera and the target's eyes
        Vec3d camPos = mc.gameRenderer.getCamera().getCameraPos();
        boolean depth = mc.world.raycast(new RaycastContext(camPos, prevTarget.getEyePos(),
                RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, mc.player)).getType() == HitResult.Type.MISS;

        switch (mode.getValue()) {
            case Circles -> drawCircles(prevTarget, depth);
            case Ring -> drawRing(prevTarget, depth);
            case Ring2 -> drawRing2(prevTarget, depth);
            case Ghosts -> drawGhostsVurst(prevTarget, depth);
            case Marker -> drawMarker(prevTarget, depth);
            default -> drawGhosts(prevTarget, depth);
        }
    }

    /* ------------------------------------------------------------------ */
    /* Modes                                                               */
    /* ------------------------------------------------------------------ */

    private Vec3d relative(LivingEntity t) {
        return getRenderPos(t).subtract(mc.gameRenderer.getCamera().getCameraPos());
    }

    private void drawCircles(LivingEntity t, boolean depth) {
        Vec3d rel = relative(t);
        float movingValue = moving.value;
        float width = t.getWidth() * 1.45f * getSizeMapped();
        float baseVal = Math.max(0.5f, 0.7f + 0.1f - 0.1f * animation.value);
        Color color = baseColor();
        int step = 3;
        float particleSize = 0.4f * getSizeMapped();
        float bloomSize = 0.8f * getSizeMapped();
        int alpha = (int) (animation.value * 255);
        int bloomRgb = withAlpha(color, (int) (alpha * 0.1f));
        int coreRgb = withAlpha(color, alpha);
        double radAngle = Math.toRadians(movingValue);
        float waveValue = (float) ((1.0 - Math.cos(radAngle)) / 2.0);
        float yPos = (float) (rel.y + t.getHeight() * waveValue);

        SpriteBatch bloom = new SpriteBatch(BLOOM_TEXTURE, depth);
        for (int pass = 0; pass < 2; pass++) {
            for (int i = 0; i < 360; i += step) {
                if ((int) (i / 45.0f) % 2 == 0) continue;
                double rad = Math.toRadians(i + movingValue);
                float x = (float) (rel.x + Math.sin(rad) * width * baseVal);
                float z = (float) (rel.z + Math.cos(rad) * width * baseVal);
                if (pass == 0) bloom.billboard(x, yPos, z, bloomSize, bloomRgb, false);
                else bloom.billboard(x, yPos, z, particleSize, coreRgb, false);
            }
        }
        bloom.flush();
    }

    private void drawMarker(LivingEntity t, boolean depth) {
        Color color = baseColor();
        String texName = switch (design.getValue()) {
            case AmongUs -> "amongus";
            case Skull -> "skull";
            case Vegas -> "vegas";
            case SciFi -> "scifi";
            case Simple -> "simple";
            case Bo -> "bo";
            case Jeka -> "jeka";
            case Legacy -> "legacy";
            case Nova -> "markernowy";
            default -> "marker";
        };
        boolean flip = design.is(Design.AmongUs);
        SpriteBatch batch = new SpriteBatch(Identifier.of("bkclient", "textures/target_render/" + texName + ".png"), depth);
        Vec3d rel = relative(t);
        float width = t.getWidth() * 1.4f * getSizeMapped();
        int argb = withAlpha(color, (int) (255.0f * animation.value));

        if (animationStyle.is(MarkerMotion.Nursultan)) {
            // one camera-facing marker on the target's centre, spinning, growing in and turning red on hits
            float hurt = changeColorOnDamage.getValue() ? MathHelper.clamp(t.hurtTime / 10.0F, 0.0F, 1.0F) : 0.0F;
            Color tinted = new Color(
                    (int) MathHelper.lerp(hurt, color.getRed(), 255),
                    (int) MathHelper.lerp(hurt, color.getGreen(), 40),
                    (int) MathHelper.lerp(hurt, color.getBlue(), 40));
            float size = (t.getWidth() + t.getHeight()) * 0.55f * getSizeMapped() * (0.6f + 0.4f * animation.value) * (1.0f - 0.12f * hurt);
            float angle = (float) Math.toRadians(markerRotation.value);
            batch.billboardRotated(rel.x, rel.y + t.getHeight() / 2.0, rel.z, size, angle,
                    withAlpha(tinted, (int) (255.0f * animation.value)), flip);
            batch.flush();
            return;
        }
        if (animationStyle.is(MarkerMotion.Crystals)) {
            for (int i = 0; i < 360; i += 30) {
                float val = 1.1f - 0.2f * animation.value;
                double a = Math.toRadians(i + moving.value * 0.8f);
                float x = (float) (Math.sin(a) * width * val);
                float z = (float) (Math.cos(a) * width * val);
                double y = 0.1f + t.getHeight() * Math.abs(Math.sin(Math.toRadians(i)));
                batch.billboard(rel.x + x, rel.y + y, rel.z + z, 0.35f * getSizeMapped(), argb, flip);
            }
        } else {
            for (int i = 0; i < 360; i += 40) {
                float val = Math.max(0.5f, 1.2f - 0.5f * animation.value);
                double a = Math.toRadians(i + moving.value);
                float x = (float) (Math.sin(a) * width * val);
                float z = (float) (Math.cos(a) * width * val);
                double y = t.getHeight() / 1.5f + (t.getHeight() / 3.0f) * Math.sin(Math.toRadians(i / 2.0f + moving.value / 5.0f));
                batch.billboard(rel.x + x, rel.y + y, rel.z + z, 0.4f * getSizeMapped(), argb, flip);
            }
        }
        batch.flush();
    }

    private void drawGhosts(LivingEntity t, boolean depth) {
        float width = t.getWidth() * 1.5f;
        Vec3d rel = relative(t);
        SpriteBatch batch = new SpriteBatch(BLOOM_TEXTURE, depth);
        int step = 2, wormTick = 0, wormCD = 0;
        float sizeScale = getSizeMapped();
        for (int i = 0; i < 360; i += step) {
            float sz = (0.13f + 0.005f * wormTick) * sizeScale;
            float bigSize = (0.7f + 0.005f * wormTick) * sizeScale;
            if (wormCD > 0) {
                wormCD -= step;
                continue;
            }
            if ((wormTick += step) > 50) {
                wormCD = 100;
                wormTick = 0;
                continue;
            }
            float val = Math.max(0.5f, 1.2f - 0.5f * animation.value);
            double a = Math.toRadians(i + moving.value);
            float x = (float) (Math.sin(a) * width * val);
            float z = (float) (Math.cos(a) * width * val);
            float y = (float) (t.getHeight() / 1.5f + (t.getHeight() / 3.0f) * Math.sin(Math.toRadians(i / 2.0f + moving.value / 5.0f)));
            int c = getModeColor(i);
            int ca = (c >>> 24) & 0xFF;
            batch.billboard(rel.x + x, rel.y + y, rel.z + z, bigSize, withAlpha(c, (int) (ca * animation.value * 0.05f)), false);
            batch.billboard(rel.x + x, rel.y + y, rel.z + z, sz, withAlpha(c, (int) (ca * animation.value)), false);
        }
        batch.flush();
    }

    private void drawGhostsVurst(LivingEntity t, boolean depth) {
        float anim = MathHelper.clamp(animation.value, 0.0f, 1.0f);
        if (anim <= 0.0f) return;
        float scaleFactor = getSizeMapped();
        double radius = Math.max(0.45, t.getWidth() * 0.95) * scaleFactor;
        float speedVal = 27.0f / Math.max(0.1f, speed.getValue() * 2.0f);
        float spriteSize = 0.32f * scaleFactor;
        double distance = 15.0;
        int trailLength = 25, maxAlpha = 255, alphaFactor = 15;
        Vec3d rel = relative(t);
        Vec3d anchor = rel.add(0, t.getHeight() * 0.5 + 0.05, 0);
        long now = System.currentTimeMillis();
        SpriteBatch batch = new SpriteBatch(BLOOM_TEXTURE, depth);
        for (int orbit = 0; orbit < 3; orbit++) {
            for (int i = 0; i < trailLength; i++) {
                double angle = 0.15 * ((now % 100000L) - i * distance) / speedVal;
                double s = Math.sin(angle) * radius, c = Math.cos(angle) * radius;
                int alpha = clamp255(MathHelper.clamp(maxAlpha - i * alphaFactor, 0, maxAlpha) * anim);
                if (alpha <= 0) continue;
                int color = (alpha << 24) | (getModeColor(i * 360 / trailLength) & 0xFFFFFF);
                Vec3d p = switch (orbit) {
                    case 0 -> anchor.add(s, c, -c);
                    case 1 -> anchor.add(-s, s, -c);
                    default -> anchor.add(-s, -s, c);
                };
                batch.billboard(p.x, p.y, p.z, spriteSize, color, false);
            }
        }
        batch.flush();
    }

    private void drawRing(LivingEntity t, boolean depth) {
        Vec3d rel = relative(t);
        float scale = Math.max(0.35f, getSizeMapped());
        float radius = t.getWidth() * 0.85f * scale;
        float height = t.getHeight();
        double duration = 2000.0 / Math.max(0.1f, speed.getValue() * 2.0f);
        double elapsed = System.currentTimeMillis() % duration;
        boolean side = elapsed > duration / 2.0;
        double progress = elapsed / (duration / 2.0);
        progress = side ? progress - 1.0 : 1.0 - progress;
        progress = MathHelper.clamp((float) progress, 0.0f, 1.0f);
        progress = progress < 0.5 ? 2.0 * progress * progress : 1.0 - Math.pow(-2.0 * progress + 2.0, 2.0) / 2.0;
        double eased = (height / 2.0f) * (progress > 0.5 ? 1.0 - progress : progress) * (side ? -1.0 : 1.0);
        float y0 = (float) (rel.y + height * progress);
        float y1 = (float) (y0 + eased * 1.5);
        float alphaValue = easeOutCubic(animation.value);
        int glowAlpha = clamp255(145.0f * alphaValue) << 24;
        int coreAlpha = clamp255(17.1f * alphaValue) << 24;

        RenderLayer layer = colorLayer(depth);
        VertexConsumer vc = Render3DUtil.buffer(layer);
        Matrix4f view = viewMatrix();
        // BK's triangle strip, as quads between consecutive degrees
        for (int i = 0; i < 360; i++) {
            double a0 = Math.toRadians(i), a1 = Math.toRadians(i + 1);
            float x0 = (float) (rel.x + Math.cos(a0) * radius), z0 = (float) (rel.z + Math.sin(a0) * radius);
            float x1 = (float) (rel.x + Math.cos(a1) * radius), z1 = (float) (rel.z + Math.sin(a1) * radius);
            int rgb0 = getModeColor(i) & 0xFFFFFF, rgb1 = getModeColor(i + 1) & 0xFFFFFF;
            Render3DUtil.vertex(vc, view, x0, y0, z0, glowAlpha | rgb0);
            Render3DUtil.vertex(vc, view, x0, y1, z0, coreAlpha | rgb0);
            Render3DUtil.vertex(vc, view, x1, y1, z1, coreAlpha | rgb1);
            Render3DUtil.vertex(vc, view, x1, y0, z1, glowAlpha | rgb1);
        }
        Render3DUtil.flush(layer);

        // BK's outline line strip on the ring's lower edge
        VertexConsumer lines = Render3DUtil.buffer(Render3DUtil.LINES);
        for (int i = 0; i < 360; i++) {
            double a0 = Math.toRadians(i), a1 = Math.toRadians(i + 1);
            int rgb = getModeColor(i) & 0xFFFFFF;
            Render3DUtil.line(lines, view,
                    (float) (rel.x + Math.cos(a0) * radius), y0, (float) (rel.z + Math.sin(a0) * radius),
                    (float) (rel.x + Math.cos(a1) * radius), y0, (float) (rel.z + Math.sin(a1) * radius), coreAlpha | rgb);
        }
        Render3DUtil.flush(Render3DUtil.LINES);
    }

    private void drawRing2(LivingEntity t, boolean depth) {
        Vec3d rel = relative(t);
        float scale = getSizeMapped();
        float entityWidth = t.getWidth() * 0.9f * scale;
        float entityHeight = t.getHeight();
        float animationAlpha = easeOutCubic(animation.value);
        double step = System.currentTimeMillis() / 1000.0 * (speed.getValue() * 2.0f);
        double headY = Math.abs(Math.sin(step)) * entityHeight;
        double tailBaseY = Math.abs(Math.sin(step - 0.4)) * entityHeight;
        float headSize = 0.12f * scale * particleThickness.getValue();
        float tailSize = 0.08f * scale * particleThickness.getValue();
        int totalPoints = 138, tailSegments = 16;
        SpriteBatch batch = new SpriteBatch(GLOW_TEXTURE, depth);
        for (int i = 0; i < totalPoints; i++) {
            double ang = (Math.PI * 2) * i / totalPoints;
            float xo = (float) (Math.cos(ang) * entityWidth), zo = (float) (Math.sin(ang) * entityWidth);
            int rgb = getModeColor(i * 360 / totalPoints) & 0xFFFFFF;
            batch.billboard(rel.x + xo, rel.y + headY, rel.z + zo, headSize, packAlpha(animationAlpha * 0.9f * 255.0f) | rgb, false);
            for (int k = 1; k <= tailSegments; k++) {
                float tp = (float) k / (tailSegments + 1);
                double ty = headY + (tailBaseY - headY) * tp;
                float ta = animationAlpha * (1.0f - tp) * 0.6f;
                if (ta <= 0.0f) continue;
                batch.billboard(rel.x + xo, rel.y + ty, rel.z + zo, tailSize, packAlpha(ta * 255.0f) | rgb, false);
            }
        }
        batch.flush();
    }

    /* ------------------------------------------------------------------ */
    /* Refractive modes: Crystals and Glass Ghosts                          */
    /* ------------------------------------------------------------------ */

    private static final thunder.hack.utility.render.BkWorldPost LENS_POST =
            new thunder.hack.utility.render.BkWorldPost("bk_target_lens", "Lenses", 4 + 18 * 4);
    private static final thunder.hack.utility.render.BkWorldPost LIGHT_POST =
            new thunder.hack.utility.render.BkWorldPost("bk_particle_light", "Lights", 16 + 4 + 96 * 4);

    private final Anim effectOcclusion = new Anim(350L, t -> t < 0.5f ? 4f * t * t * t : 1f - (float) Math.pow(-2f * t + 2f, 3) / 2f);
    private float effectPassAlpha = 1f;
    private final long ghostAnimationStart = System.nanoTime();

    private record EffectEmitter(Vec3d position, float radius, float lightIntensity) {
    }

    private record GhostSample(Vec3d position, float size, float opacity, int index) {
    }

    private boolean isRefractiveMode() {
        return mode != null && (mode.is(Mode.Crystals) || mode.is(Mode.GlassGhosts));
    }

    /** BK's effectColor: theme / custom / rainbow, pulled to red while the target is hurt. */
    private Color effectColor() {
        Color color = colorSource.is(ColorSource.Rainbow)
                ? Color.getHSBColor((System.currentTimeMillis() % 3000L) / 3000.0F, 1, 1) : baseColor();
        if (changeColorOnDamage.getValue() && prevTarget != null) color = mixColor(color, Color.RED, prevTarget.hurtTime / 10.0F);
        return color;
    }

    private static Color mixColor(Color a, Color b, float t) {
        t = MathHelper.clamp(t, 0f, 1f);
        return new Color(Math.round(a.getRed() + (b.getRed() - a.getRed()) * t), Math.round(a.getGreen() + (b.getGreen() - a.getGreen()) * t),
                Math.round(a.getBlue() + (b.getBlue() - a.getBlue()) * t), Math.round(a.getAlpha() + (b.getAlpha() - a.getAlpha()) * t));
    }

    private void renderRefractive(LivingEntity t) {
        Vec3d cam = mc.gameRenderer.getCamera().getCameraPos();
        Vec3d origin = getRenderPos(t);
        float fade = animation.value;
        float scale = size.getValue();
        float radius = t.getWidth() * 1.5F * scale * (1.2F - 0.5F * fade);
        Matrix4f projection = new Matrix4f(Render3DEngine.lastProjMat);
        Matrix4f viewProjection = new Matrix4f(projection).mul(Render3DEngine.lastWorldSpaceMatrix);
        java.util.List<float[]> lenses = new java.util.ArrayList<>(18);
        java.util.List<float[]> lights = new java.util.ArrayList<>(6);
        Color color = effectColor();
        java.util.List<GhostSample> ghostSamples = mode.is(Mode.GlassGhosts) ? createGlassGhosts(t, origin) : java.util.List.of();
        java.util.List<EffectEmitter> emitters = new java.util.ArrayList<>(18);
        if (mode.is(Mode.GlassGhosts)) {
            for (GhostSample sample : ghostSamples)
                if (sample.index() % 3 == 0) emitters.add(new EffectEmitter(sample.position(),
                        sample.size() * (0.65F + sample.opacity() * 0.5F), sample.index() == 0 ? fade / 3F : 0));
        } else {
            for (int angle = 0; angle < 360; angle += 20) {
                double phase = Math.toRadians(angle + moving.value * 0.3F);
                emitters.add(new EffectEmitter(origin.add(Math.sin(phase) * radius,
                        0.1F + t.getHeight() * Math.abs(Math.sin(angle)), Math.cos(phase) * radius),
                        0.45F * scale, angle % 60 == 0 ? fade / 6F : 0));
            }
        }
        for (EffectEmitter emitter : emitters) {
            Vec3d position = emitter.position().subtract(cam);
            if (effectLighting.getValue() && emitter.lightIntensity() > 0)
                lights.add(new float[]{(float) position.x, (float) position.y, (float) position.z, 3.5F,
                        color.getRed() / 255F, color.getGreen() / 255F, color.getBlue() / 255F, emitter.lightIntensity()});
            if (distortion.getValue()) {
                org.joml.Vector4f clip = viewProjection.transform(new org.joml.Vector4f((float) position.x, (float) position.y, (float) position.z, 1));
                if (clip.w <= 0.05F) continue;
                float u = clip.x / clip.w * 0.5F + 0.5F, v = clip.y / clip.w * 0.5F + 0.5F;
                float lensRadius = Math.min(emitter.radius() * projection.m11() / clip.w * 0.5F, 0.35F);
                if (u < -lensRadius * 2 || u > 1 + lensRadius * 2 || v < -lensRadius * 2 || v > 1 + lensRadius * 2) continue;
                lenses.add(new float[]{u, v, clip.z / clip.w * 0.5F + 0.5F, lensRadius});
            }
        }
        if (!lights.isEmpty() && !LIGHT_POST.isBroken()) {
            float[] u = new float[LIGHT_POST.floats];
            new Matrix4f(viewProjection).invert().get(u, 0);
            u[16] = 1.0F;
            int n = Math.min(48, lights.size());
            u[17] = n;
            for (int i = 0; i < n; i++) System.arraycopy(lights.get(i), 0, u, 20 + i * 8, 8);
            LIGHT_POST.apply(u);
        }
        boolean hidden = t.isTouchingWater() || mc.world.raycast(new RaycastContext(cam, t.getEyePos(),
                RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, mc.player)).getType() != HitResult.Type.MISS;
        effectOcclusion.update(hidden ? 1f : 0f);

        effectPassAlpha = 1;
        if (mode.is(Mode.GlassGhosts)) drawGlassGhosts(ghostSamples, DepthTestFunction.LEQUAL_DEPTH_TEST);
        else drawCrystals(t, DepthTestFunction.LEQUAL_DEPTH_TEST);
        if (effectOcclusion.value > 0.01F) {
            effectPassAlpha = effectOcclusion.value;
            if (mode.is(Mode.GlassGhosts)) drawGlassGhosts(ghostSamples, DepthTestFunction.GREATER_DEPTH_TEST);
            else drawCrystals(t, DepthTestFunction.GREATER_DEPTH_TEST);
        }
        effectPassAlpha = 1;

        float strength = distortionStrength.getValue() * fade;
        if (!lenses.isEmpty() && strength > 0.001F && !LENS_POST.isBroken()) {
            float[] u = new float[LENS_POST.floats];
            int n = Math.min(18, lenses.size());
            u[0] = n;
            u[1] = projection.m11() / projection.m00();
            u[2] = strength;
            for (int i = 0; i < n; i++) System.arraycopy(lenses.get(i), 0, u, 4 + i * 4, 4);
            LENS_POST.apply(u);
        }
    }

    // BK's CrystalRenderer: an octahedron, each face shaded by a fixed brightness
    private static final float[][] CRYSTAL_VERTICES = {{0, 1.5f, 0}, {0, -1.5f, 0}, {1, 0, 0}, {-1, 0, 0}, {0, 0, 1}, {0, 0, -1}};
    private static final int[][] CRYSTAL_FACES = {{0, 2, 4}, {0, 4, 3}, {0, 3, 5}, {0, 5, 2}, {1, 4, 2}, {1, 3, 4}, {1, 5, 3}, {1, 2, 5}};
    private static final float[] CRYSTAL_BRIGHTNESS = {1.0F, 0.8F, 0.6F, 0.9F, 0.7F, 0.5F, 0.4F, 0.6F};

    private void drawCrystals(LivingEntity t, DepthTestFunction depth) {
        Color color = effectColor();
        float width = t.getWidth() * 1.5f * size.getValue();
        Vec3d rel = relative(t);
        Vec3d renderPos = getRenderPos(t);
        Vec3d targetPos = t.getEntityPos().add(0.0, t.getHeight() / 2.0, 0.0);
        int crystalArgb = withAlpha(color, (int) (255.0f * animation.value * effectPassAlpha));
        float sz = 0.1f * size.getValue();
        RenderLayer layer = colorLayer(depth);
        VertexConsumer vc = Render3DUtil.buffer(layer);
        Matrix4f view = viewMatrix();
        Vector3f p = new Vector3f();
        for (int i = 0; i < 360; i += 20) {
            float val = 1.2f - 0.5f * animation.value;
            double a = Math.toRadians(i + moving.value * 0.3f);
            float sx = (float) (Math.sin(a) * width * val), cz = (float) (Math.cos(a) * width * val);
            float y = (float) (0.1f + t.getHeight() * Math.abs(Math.sin(i)));
            Vec3d crystalPos = renderPos.add(sx, 1.0, cz);
            Vector3f dir = new Vector3f((float) (targetPos.x - crystalPos.x), (float) (targetPos.y - crystalPos.y),
                    (float) (targetPos.z - crystalPos.z)).normalize();
            Quaternionf rotation = new Quaternionf().rotationTo(new Vector3f(0, 1, 0), dir);
            float ox = (float) rel.x + sx, oy = (float) rel.y + y, oz = (float) rel.z + cz;
            for (int f = 0; f < CRYSTAL_FACES.length; f++) {
                int shaded = brightness(crystalArgb, CRYSTAL_BRIGHTNESS[f]);
                int[] face = CRYSTAL_FACES[f];
                float[][] pts = new float[3][];
                for (int k = 0; k < 3; k++) {
                    float[] v = CRYSTAL_VERTICES[face[k]];
                    p.set(v[0] * sz, v[1] * sz, v[2] * sz);
                    rotation.transform(p);
                    pts[k] = new float[]{ox + p.x, oy + p.y, oz + p.z};
                }
                // a triangle as a degenerate quad
                Render3DUtil.vertex(vc, view, pts[0][0], pts[0][1], pts[0][2], shaded);
                Render3DUtil.vertex(vc, view, pts[1][0], pts[1][1], pts[1][2], shaded);
                Render3DUtil.vertex(vc, view, pts[2][0], pts[2][1], pts[2][2], shaded);
                Render3DUtil.vertex(vc, view, pts[2][0], pts[2][1], pts[2][2], shaded);
            }
        }
        Render3DUtil.flush(layer);

        SpriteBatch glow = new SpriteBatch(BLOOM_TEXTURE, depth);
        float bigSize = size.getValue();
        int glowArgb = withAlpha(color, (int) (255.0f * animation.value * 0.2f * effectPassAlpha));
        for (int i = 0; i < 360; i += 20) {
            float val = 1.2f - 0.5f * animation.value;
            double a = Math.toRadians(i + moving.value * 0.3f);
            float sx = (float) (Math.sin(a) * width * val), cz = (float) (Math.cos(a) * width * val);
            glow.billboard(rel.x + sx, rel.y + 0.1f + t.getHeight() * Math.abs(Math.sin(i)), rel.z + cz, bigSize, glowArgb, false);
        }
        glow.flush();
    }

    private static int brightness(int color, float b) {
        int alpha = color >>> 24;
        int r = Math.min(255, Math.max(0, (int) (((color >> 16) & 0xFF) * b)));
        int g = Math.min(255, Math.max(0, (int) (((color >> 8) & 0xFF) * b)));
        int bl = Math.min(255, Math.max(0, (int) ((color & 0xFF) * b)));
        return alpha << 24 | r << 16 | g << 8 | bl;
    }

    private java.util.List<GhostSample> createGlassGhosts(LivingEntity t, Vec3d origin) {
        float scale = getSizeMapped();
        double radius = Math.max(0.45, t.getWidth() * 0.95) * scale;
        double speedScale = Math.max(0.1, speed.getValue() * 2);
        double elapsedMillis = (System.nanoTime() - ghostAnimationStart) / 1_000_000.0;
        Vec3d anchor = origin.add(0, t.getHeight() * 0.5 + 0.05, 0);
        java.util.List<GhostSample> samples = new java.util.ArrayList<>(51);
        for (int orbit = 0; orbit < 3; orbit++) {
            for (int i = 0; i < 17; i++) {
                double angle = (elapsedMillis - i * 15.0) * speedScale / 180.0;
                double sin = Math.sin(angle) * radius, cos = Math.cos(angle) * radius;
                Vec3d position = switch (orbit) {
                    case 0 -> anchor.add(sin, cos, -cos);
                    case 1 -> anchor.add(-sin, sin, -cos);
                    default -> anchor.add(-sin, -sin, cos);
                };
                float opacity = (255 - i * 15) / 255.0F;
                samples.add(new GhostSample(position, 0.32F * scale * (0.45F + 0.55F * opacity), opacity, i));
            }
        }
        return samples;
    }

    private void drawGlassGhosts(java.util.List<GhostSample> samples, DepthTestFunction depth) {
        SpriteBatch batch = new SpriteBatch(BLOOM_TEXTURE, depth);
        float fade = MathHelper.clamp(animation.value, 0, 1) * effectPassAlpha;
        Color tint = effectColor();
        Vec3d cam = mc.gameRenderer.getCamera().getCameraPos();
        for (GhostSample sample : samples) {
            Color color = tint;
            if (colorSource.is(ColorSource.Rainbow)) {
                float hue = ((System.currentTimeMillis() % 3000L) / 3000F + sample.index() / 25F) % 1;
                color = Color.getHSBColor(hue, 1, 1);
                if (changeColorOnDamage.getValue() && prevTarget != null) color = mixColor(color, Color.RED, prevTarget.hurtTime / 10F);
            }
            float alpha = fade * sample.opacity();
            Vec3d p = sample.position().subtract(cam);
            batch.billboard(p.x, p.y, p.z, sample.size() * 2.2F, withAlpha(color, (int) (255 * alpha * 0.18F)), false);
            batch.billboard(p.x, p.y, p.z, sample.size(),
                    withAlpha(mixColor(color, Color.WHITE, 0.25F * sample.opacity()), (int) (255 * alpha)), false);
        }
        batch.flush();
    }

    /* ------------------------------------------------------------------ */
    /* Cubes (BK's TargetCubesRenderer)                                     */
    /* ------------------------------------------------------------------ */

    private static final Identifier CUBE_BLOOM = Identifier.of("bkclient", "textures/target_cubes/bloom.png");
    private static final byte[][] CUBE_EDGES = {
            {-1, -1, -1, 1, -1, -1}, {1, -1, -1, 1, -1, 1}, {1, -1, 1, -1, -1, 1}, {-1, -1, 1, -1, -1, -1},
            {-1, 1, -1, 1, 1, -1}, {1, 1, -1, 1, 1, 1}, {1, 1, 1, -1, 1, 1}, {-1, 1, 1, -1, 1, -1},
            {-1, -1, -1, -1, 1, -1}, {1, -1, -1, 1, 1, -1}, {1, -1, 1, 1, 1, 1}, {-1, -1, 1, -1, 1, 1}};
    private final java.util.List<Cube> cubes = new java.util.ArrayList<>();
    private LivingEntity cubeTarget;
    private long cubeLastFrame;
    private float spawnAccumulator;

    private final class Cube {
        double x, y, z, worldX, worldY, worldZ;
        final long time = System.currentTimeMillis();
        LivingEntity entity;
        boolean fading;
        long fadeStartTime;
        float vx, vy, vz, rotX, rotY, rotZ;
        final float rotSpeedX, rotSpeedY, rotSpeedZ;

        Cube(LivingEntity entity, double x, double y, double z) {
            this.entity = entity;
            this.x = x;
            this.y = y;
            this.z = z;
            rotX = (float) (Math.random() * 360.0);
            rotY = (float) (Math.random() * 360.0);
            rotZ = (float) (Math.random() * 360.0);
            rotSpeedX = 1.4F + (float) Math.random() * 3.4F;
            rotSpeedY = 1.4F + (float) Math.random() * 3.4F;
            rotSpeedZ = 1.4F + (float) Math.random() * 3.4F;
            vx = (float) ((Math.random() - 0.5) * 0.0022);
            vy = 0.031F + (float) Math.random() * 0.02F;
            vz = (float) ((Math.random() - 0.5) * 0.0022);
        }

        void update(float dt, long now, LivingEntity current) {
            float k = dt * 60.0F;
            rotX += rotSpeedX * k;
            rotY += rotSpeedY * k;
            rotZ += rotSpeedZ * k;
            if (fading) return;
            x += vx * k;
            y += vy * k;
            z += vz * k;
            vx *= 0.992F;
            vz *= 0.992F;
            vy *= 0.989F;
            if (entity != null) {
                double top = Math.max(2.2, entity.getHeight() * 1.85);
                if (y >= top) {
                    y = top;
                    startFade(now);
                    return;
                }
            }
            boolean lost = current == null || entity == null || !entity.isAlive() || entity != current;
            if (lost || now - time >= 560L) startFade(now);
        }

        boolean shouldRemove(long now) {
            return fading && now - fadeStartTime >= 320L;
        }

        float alpha(long now) {
            if (!fading) {
                float in = MathHelper.clamp((now - time) / 140.0F, 0.0F, 1.0F);
                float out = 1.0F - MathHelper.clamp((now - time - 440L) / 120.0F, 0.0F, 0.35F);
                return in * out;
            }
            return 1.0F - MathHelper.clamp((now - fadeStartTime) / 320.0F, 0.0F, 1.0F);
        }

        void startFade(long now) {
            if (fading) return;
            Vec3d p = position(1.0F);
            worldX = p.x;
            worldY = p.y;
            worldZ = p.z;
            fadeStartTime = now;
            fading = true;
            entity = null;
        }

        Vec3d position(float pt) {
            return !fading && entity != null
                    ? new Vec3d(MathHelper.lerp(pt, entity.lastRenderX, entity.getX()) + x,
                    MathHelper.lerp(pt, entity.lastRenderY, entity.getY()) + y,
                    MathHelper.lerp(pt, entity.lastRenderZ, entity.getZ()) + z)
                    : new Vec3d(worldX, worldY, worldZ);
        }

        int color(int base, float hurt, long now) {
            float a = alpha(now);
            if (a <= 0.001F) return 0;
            return overCol(replAlpha(base, (int) (a * 255.0F)), replAlpha(0xFFFF0303, (int) (a * 255.0F)), hurt);
        }

        Matrix4f model(float pt, long now) {
            float shrink = fading ? MathHelper.lerp(MathHelper.clamp((now - fadeStartTime) / 320.0F, 0.0F, 1.0F), 1.0F, 0.45F) : 1.0F;
            float s = 0.12F * shrink;
            Vec3d p = position(pt).subtract(mc.gameRenderer.getCamera().getCameraPos());
            return new Matrix4f().translate((float) p.x, (float) p.y, (float) p.z)
                    .rotate(RotationAxis.POSITIVE_X.rotationDegrees(rotX))
                    .rotate(RotationAxis.POSITIVE_Y.rotationDegrees(rotY))
                    .rotate(RotationAxis.POSITIVE_Z.rotationDegrees(rotZ))
                    .scale(s, s, s);
        }
    }

    private static int replAlpha(int color, int alpha) {
        return color & 0xFFFFFF | MathHelper.clamp(alpha, 0, 255) << 24;
    }

    private static int overCol(int c1, int c2, float f) {
        f = Math.max(0.0F, Math.min(1.0F, f));
        int r = (int) (((c1 >> 16) & 0xFF) + (((c2 >> 16) & 0xFF) - ((c1 >> 16) & 0xFF)) * f);
        int g = (int) (((c1 >> 8) & 0xFF) + (((c2 >> 8) & 0xFF) - ((c1 >> 8) & 0xFF)) * f);
        int b = (int) ((c1 & 0xFF) + ((c2 & 0xFF) - (c1 & 0xFF)) * f);
        int a = (int) (((c1 >>> 24) & 0xFF) + (((c2 >>> 24) & 0xFF) - ((c1 >>> 24) & 0xFF)) * f);
        return a << 24 | r << 16 | g << 8 | b;
    }

    private void renderCubes(LivingEntity t) {
        boolean hasTarget = t != null && t.isAlive() && !t.isRemoved();
        long now = System.currentTimeMillis();
        if (cubeLastFrame == 0L) cubeLastFrame = now;
        float dt = Math.min((now - cubeLastFrame) / 1000.0F, 0.1F);
        cubeLastFrame = now;
        if (hasTarget) {
            cubeTarget = t;
            spawnAccumulator += dt;
            while (spawnAccumulator >= 0.022F) {
                spawnAccumulator -= 0.022F;
                if (cubes.size() >= 72) break;
                double a = Math.random() * 360.0;
                cubes.add(new Cube(t, Math.cos(Math.toRadians(a)) * 0.7, 0.02 + Math.random() * 0.1, Math.sin(Math.toRadians(a)) * 0.7));
            }
        } else spawnAccumulator = 0.0F;

        for (int i = cubes.size() - 1; i >= 0; i--) {
            Cube c = cubes.get(i);
            c.update(dt, now, hasTarget ? t : null);
            if (c.shouldRemove(now)) cubes.remove(i);
        }
        if (cubes.isEmpty()) return;

        float pt = mc.getRenderTickCounter().getTickProgress(false);
        LivingEntity hurtSource = hasTarget ? t : cubeTarget;
        float hurt = changeColorOnDamage.getValue() && hurtSource != null ? MathHelper.clamp(hurtSource.hurtTime / 10.0F, 0.0F, 1.0F) : 0.0F;
        int base = baseColor().getRGB();
        Matrix4f view = viewMatrix();

        // faces: a translucent box per cube, additive
        RenderLayer faces = colorLayer(DepthTestFunction.LEQUAL_DEPTH_TEST);
        VertexConsumer vc = Render3DUtil.buffer(faces);
        Matrix4f m = new Matrix4f();
        Vector3f v = new Vector3f();
        float[][] box = {
                {-.5f, -.5f, -.5f}, {.5f, -.5f, -.5f}, {.5f, -.5f, .5f}, {-.5f, -.5f, .5f},
                {-.5f, .5f, -.5f}, {-.5f, .5f, .5f}, {.5f, .5f, .5f}, {.5f, .5f, -.5f},
                {-.5f, -.5f, -.5f}, {-.5f, .5f, -.5f}, {.5f, .5f, -.5f}, {.5f, -.5f, -.5f},
                {-.5f, -.5f, .5f}, {.5f, -.5f, .5f}, {.5f, .5f, .5f}, {-.5f, .5f, .5f},
                {-.5f, -.5f, -.5f}, {-.5f, -.5f, .5f}, {-.5f, .5f, .5f}, {-.5f, .5f, -.5f},
                {.5f, -.5f, -.5f}, {.5f, .5f, -.5f}, {.5f, .5f, .5f}, {.5f, -.5f, .5f}};
        for (Cube c : cubes) {
            int col = c.color(base, hurt, now);
            if ((col >>> 24) == 0) continue;
            int faceCol = replAlpha(col, Math.max(1, (int) ((col >>> 24) * 0.16F)));
            m.set(view).mul(c.model(pt, now));
            for (float[] p : box) Render3DUtil.vertex(vc, m, p[0], p[1], p[2], faceCol);
        }
        Render3DUtil.flush(faces);

        // edges
        VertexConsumer lines = Render3DUtil.buffer(Render3DUtil.LINES);
        for (Cube c : cubes) {
            int col = c.color(base, hurt, now);
            if ((col >>> 24) == 0) continue;
            int lineCol = replAlpha(col, Math.max(1, (int) ((col >>> 24) * 0.7F)));
            m.set(view).mul(c.model(pt, now));
            for (byte[] e : CUBE_EDGES)
                Render3DUtil.line(lines, m, e[0] * 0.5F, e[1] * 0.5F, e[2] * 0.5F, e[3] * 0.5F, e[4] * 0.5F, e[5] * 0.5F, lineCol);
        }
        Render3DUtil.flush(Render3DUtil.LINES);

        // bloom
        SpriteBatch bloom = new SpriteBatch(CUBE_BLOOM, DepthTestFunction.LEQUAL_DEPTH_TEST);
        Vec3d cam = mc.gameRenderer.getCamera().getCameraPos();
        for (Cube c : cubes) {
            float a = c.alpha(now);
            if (a <= 0.001F) continue;
            float grow = c.fading ? MathHelper.lerp(MathHelper.clamp((now - c.fadeStartTime) / 320.0F, 0.0F, 1.0F), 1.0F, 0.55F) : 1.0F;
            int alpha = (int) (a * 0.15F * 255.0F);
            if (alpha <= 0) continue;
            int col = c.color(base, hurt, now);
            Vec3d p = c.position(pt).subtract(cam);
            bloom.billboard(p.x, p.y, p.z, 0.95F * grow, replAlpha(col, alpha), false);
        }
        bloom.flush();
    }

    /* ------------------------------------------------------------------ */
    /* Frag Scan                                                           */
    /* ------------------------------------------------------------------ */

    private static final thunder.hack.utility.render.BkWorldPost SCAN_POST =
            new thunder.hack.utility.render.BkWorldPost("bk_target_scan", "Scan", 32);
    private LivingEntity scanTarget;
    private long scanLastNanos = Long.MIN_VALUE;
    private double scanPhase;
    private float scanAlpha;

    private void renderScan(LivingEntity selected) {
        if (selected != null && selected != scanTarget) {
            scanTarget = selected;
            scanLastNanos = Long.MIN_VALUE;
            scanPhase = 0;
            scanAlpha = 0;
        }
        if (scanTarget == null || scanTarget.isRemoved() || !scanTarget.isAlive() || scanTarget.getEntityWorld() != mc.world) {
            scanTarget = null;
            return;
        }
        // BK's TargetScanTimeline: time based, so frame rate and camera cannot advance it
        long now = System.nanoTime();
        double dt = scanLastNanos == Long.MIN_VALUE ? 0 : Math.max(0, (now - scanLastNanos) / 1_000_000_000.0);
        scanLastNanos = now;
        scanPhase = (scanPhase + dt / (2 * Math.max(.5f, scanDuration.getValue()))) % 1;
        scanAlpha = (float) Math.clamp(scanAlpha + (selected != null ? dt : -dt) / .18, 0, 1);
        if (scanAlpha <= 0) {
            if (selected == null) scanTarget = null;
            return;
        }
        double angle = scanPhase * Math.PI * 2;
        float height = (float) (.5 - .5 * Math.cos(angle)), direction = (float) Math.sin(angle);

        Color color = effectColor();
        if (colorSource.is(ColorSource.Rainbow)) color = Color.getHSBColor((System.currentTimeMillis() % 3000L) / 3000.0F, 1, 1);
        var fb = mc.getFramebuffer();
        if (fb == null || fb.getColorAttachment() == null) return;
        int fw = fb.getColorAttachment().getWidth(0), fh = fb.getColorAttachment().getHeight(0);
        Vec3d rel = relative(scanTarget);
        float[] u = new float[SCAN_POST.floats];
        new Matrix4f(Render3DEngine.lastProjMat).mul(Render3DEngine.lastWorldSpaceMatrix).invert().get(u, 0);
        u[16] = (float) rel.x;
        u[17] = (float) rel.y;
        u[18] = (float) rel.z;
        u[19] = Math.max(.1f, scanTarget.getHeight());
        u[20] = color.getRed() / 255f;
        u[21] = color.getGreen() / 255f;
        u[22] = color.getBlue() / 255f;
        u[23] = scanAlpha * color.getAlpha() / 255f;
        u[24] = height;
        u[25] = direction;
        u[26] = scanWidth.getValue() / 100f;
        u[27] = scanStrength.getValue();
        u[28] = scanTarget.getWidth() / 2f;
        u[29] = scanTarget.getWidth() / 2f;
        u[30] = 1f / Math.max(1, fw);
        u[31] = 1f / Math.max(1, fh);
        SCAN_POST.apply(u);
    }

    /* ------------------------------------------------------------------ */
    /* Colour and target                                                   */
    /* ------------------------------------------------------------------ */

    private Color baseColor() {
        return colorSource.is(ColorSource.Theme) ? HudEditor.getColor(0) : colorTarget.getValue().getColorObject();
    }

    private int getModeColor(int angleDeg) {
        int rgb;
        if (colorSource.is(ColorSource.Rainbow)) {
            float timeFactor = (System.currentTimeMillis() % 3000L) / 3000.0f;
            float hue = (timeFactor + angleDeg / 360.0f) % 1.0f;
            rgb = Color.HSBtoRGB(hue, 1.0f, 1.0f) & 0xFFFFFF;
        } else {
            rgb = baseColor().getRGB() & 0xFFFFFF;
        }
        return applyRingDamageFlash(0xFF000000 | rgb);
    }

    private int applyRingDamageFlash(int color) {
        if (!changeColorOnDamage.getValue()) return color;
        float targetIntensity = 0.0f;
        long since = System.currentTimeMillis() - lastRingDamageTime;
        if (since < RING_DAMAGE_FLASH_DURATION) targetIntensity = 1.0f - easeOutCubic((float) since / RING_DAMAGE_FLASH_DURATION);
        ringDamageFlashIntensity = targetIntensity;
        if (ringDamageFlashIntensity < 0.05f) return color;
        int alpha = (color >> 24) & 0xFF, red = (color >> 16) & 0xFF, green = (color >> 8) & 0xFF, blue = color & 0xFF;
        int r = (int) MathHelper.lerp(ringDamageFlashIntensity, red, 255);
        int g = (int) MathHelper.lerp(ringDamageFlashIntensity, green, 50);
        int b = (int) MathHelper.lerp(ringDamageFlashIntensity, blue, 50);
        return alpha << 24 | r << 16 | g << 8 | b;
    }

    private LivingEntity getTarget() {
        if (mc.player == null || mc.world == null) return null;
        if (targetSource.is(Source.Aura)) {
            // BK: AuraRuntime.currentTarget() - whichever aura is running (Kilaura is this client's main one)
            Entity active = null;
            if (thunder.hack.core.manager.client.ModuleManager.kilaura.isEnabled()) active = thunder.hack.features.modules.combat.Kilaura.target;
            if (active == null && thunder.hack.core.manager.client.ModuleManager.aura.isEnabled()) active = Aura.target;
            if (active == null && thunder.hack.core.manager.client.ModuleManager.maceAura.isEnabled()) active = thunder.hack.core.manager.client.ModuleManager.maceAura.getTarget();
            return active instanceof LivingEntity l && l != mc.player && l.isAlive() && !l.isRemoved() ? l : null;
        }
        if (!players.getValue() && !mobs.getValue()) return null;
        Vec3d eyes = mc.player.getCameraPosVec(1.0f);
        Vec3d look = mc.player.getRotationVec(1.0f);
        Vec3d end = eyes.add(look.x * TARGET_REACH, look.y * TARGET_REACH, look.z * TARGET_REACH);
        Box searchBox = mc.player.getBoundingBox().stretch(look.multiply(TARGET_REACH)).expand(1.0);
        LivingEntity best = null;
        double bestDist = TARGET_REACH * TARGET_REACH;
        for (Entity entity : mc.world.getOtherEntities(mc.player, searchBox)) {
            if (!(entity instanceof LivingEntity living) || !isValid(living)) continue;
            var hit = entity.getBoundingBox().expand(0.35).raycast(eyes, end);
            if (hit.isEmpty()) continue;
            double dist = eyes.squaredDistanceTo(hit.get());
            if (dist < bestDist) {
                bestDist = dist;
                best = living;
            }
        }
        return best;
    }

    private boolean isValid(LivingEntity living) {
        if (!living.isAlive() || living.isRemoved()) return false;
        if (living instanceof PlayerEntity) return players.getValue();
        return mobs.getValue();
    }

    private float getSizeMapped() {
        return 0.8f + size.getValue() * 0.7f;
    }

    private Vec3d getRenderPos(LivingEntity t) {
        float tickDelta = mc.getRenderTickCounter().getTickProgress(false);
        return new Vec3d(MathHelper.lerp(tickDelta, t.lastX, t.getX()), MathHelper.lerp(tickDelta, t.lastY, t.getY()),
                MathHelper.lerp(tickDelta, t.lastZ, t.getZ()));
    }

    private static int withAlpha(Color c, int alpha) {
        return (MathHelper.clamp(alpha, 0, 255) << 24) | (c.getRGB() & 0xFFFFFF);
    }

    private static int withAlpha(int argb, int alpha) {
        return (MathHelper.clamp(alpha, 0, 255) << 24) | (argb & 0xFFFFFF);
    }

    private static int packAlpha(float alpha) {
        return (Math.round(Math.max(0.0F, Math.min(255.0F, alpha))) & 0xFF) << 24;
    }

    private static int clamp255(float value) {
        return MathHelper.clamp((int) value, 0, 255);
    }

    private static float easeOutCubic(float x) {
        return 1.0f - (float) Math.pow(1.0f - x, 3.0);
    }
}
