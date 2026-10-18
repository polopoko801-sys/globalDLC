package thunder.hack.features.modules.render;

import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LightningEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.sound.SoundCategory;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Vec3d;
import thunder.hack.utility.render.Render2DEngine;
import thunder.hack.core.Managers;
import thunder.hack.features.modules.Module;
import thunder.hack.setting.Setting;
import thunder.hack.setting.impl.ColorSetting;
import thunder.hack.utility.render.Render3DEngine;

import java.awt.*;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class KillEffect extends Module {
    public KillEffect() {
        super("KillEffect", Category.RENDER);
    }

    private final Setting<Mode> mode = new Setting<>("Mode", Mode.Orthodox);
    private final Setting<Integer> speed = new Setting<>("Y Speed", 0, -10, 10, value -> mode.getValue() == Mode.Orthodox);
    public final Setting<Integer> volume = new Setting<>("Volume", 100, 0, 100);
    private final Setting<Boolean> playSound = new Setting<>("Play Sound", true);
    private final Setting<ColorSetting> color = new Setting<>("Color", new ColorSetting(new Color(255, 255, 0, 150)), value -> mode.getValue() == Mode.Orthodox);
    private final Setting<Boolean> mobs = new Setting<>("Mobs", false);

    private final Map<Entity, Long> renderEntities = new ConcurrentHashMap<>();
    private final Map<Entity, Long> lightingEntities = new ConcurrentHashMap<>();

    private enum Mode {
        Orthodox,
        FallingLava,
        LightningBolt,
        /** The face rises and bursts; the sparks fly out 7 blocks, hang for a second and fall back in. */
        SoulBurst
    }

    private final Setting<Float> burstRadius = new Setting<>("BurstRadius", 7f, 2f, 12f, v -> mode.getValue() == Mode.SoulBurst);
    private final Setting<Integer> burstSparks = new Setting<>("BurstSparks", 48, 8, 160, v -> mode.getValue() == Mode.SoulBurst);

    /** One death being played: where, whose face, when it started, and its own spark directions. */
    private record Burst(Vec3d origin, Identifier skin, long start, Vec3d[] dirs) {
    }

    private final java.util.List<Burst> bursts = new java.util.concurrent.CopyOnWriteArrayList<>();

    // timeline, ms
    private static final long RISE = 900, OUT = 500, HOLD = 1000, BACK = 700;
    private static final double RISE_HEIGHT = 2.5;

    @Override
    public void onRender3D(MatrixStack stack) {
        if (mc.world == null) return;

        switch (mode.getValue()) {
            case Orthodox -> renderEntities.forEach((entity, time) -> {
                if (System.currentTimeMillis() - time > 3000) {
                    renderEntities.remove(entity);
                } else {
                    Render3DEngine.drawLine(entity.getEntityPos().add(0, calculateSpeed(), 0), entity.getEntityPos().add(0, 3 + calculateSpeed(), 0), color.getValue().getColorObject());
                    Render3DEngine.drawLine(entity.getEntityPos().add(1, 2.3 + calculateSpeed(), 0), entity.getEntityPos().add(-1, 2.3 + calculateSpeed(), 0), color.getValue().getColorObject());
                    Render3DEngine.drawLine(entity.getEntityPos().add(0.5, 1.2 + calculateSpeed(), 0), entity.getEntityPos().add(-0.5, 0.8 + calculateSpeed(), 0), color.getValue().getColorObject());
                }
            });
            case FallingLava -> renderEntities.keySet().forEach(entity -> {
                for (int i = 0; i < entity.getHeight() * 10; i++) {
                    for (int j = 0; j < entity.getWidth() * 10; j++) {
                        for (int k = 0; k < entity.getWidth() * 10; k++) {
                            mc.world.addParticleClient(ParticleTypes.FALLING_LAVA, entity.getX() + j * 0.1, entity.getY() + i * 0.1, entity.getZ() + k * 0.1, 0, 0, 0);
                        }
                    }
                }

                renderEntities.remove(entity);
            });
            case SoulBurst -> {
                renderEntities.forEach((entity, time) -> {
                    bursts.add(newBurst(entity));
                    renderEntities.remove(entity);
                });
                renderBursts();
            }
            case LightningBolt -> renderEntities.forEach((entity, time) -> {
                LightningEntity lightningEntity = new LightningEntity(EntityType.LIGHTNING_BOLT, mc.world);
                lightningEntity.refreshPositionAfterTeleport(entity.getX(), entity.getY(), entity.getZ());
                mc.world.addEntity(lightningEntity);
                renderEntities.remove(entity);
                lightingEntities.put(entity, System.currentTimeMillis());
            });
        }
    }

    @Override
    public void onUpdate() {
        Managers.ASYNC.getAsyncEntities().forEach(entity -> {
            if (!(entity instanceof PlayerEntity) && !mobs.getValue()) return;
            if (!(entity instanceof LivingEntity liv)) return;

            if (entity == mc.player || renderEntities.containsKey(entity) || lightingEntities.containsKey(entity))
                return;
            if (entity.isAlive() || liv.getHealth() != 0) return;

            if (playSound.getValue())
                mc.world.playSound(mc.player, entity.getBlockPos(), Managers.SOUND.ORTHODOX_SOUNDEVENT, SoundCategory.BLOCKS, volume.getValue() / 100f, 1f);

            renderEntities.put(entity, System.currentTimeMillis());
        });

        if (!lightingEntities.isEmpty()) {
            lightingEntities.forEach((entity, time) -> {
                if (System.currentTimeMillis() - time > 5000) {
                    lightingEntities.remove(entity);
                }
            });
        }
    }

    private Burst newBurst(Entity entity) {
        Identifier skin = entity instanceof net.minecraft.client.network.AbstractClientPlayerEntity p
                ? p.getSkin().body().texturePath() : null;
        // evenly spread directions on a sphere (golden spiral), so the burst is round rather than clumpy
        int n = burstSparks.getValue();
        Vec3d[] dirs = new Vec3d[n];
        double golden = Math.PI * (3 - Math.sqrt(5));
        for (int i = 0; i < n; i++) {
            double y = 1 - 2 * (i + 0.5) / n;
            double r = Math.sqrt(1 - y * y);
            double a = golden * i;
            dirs[i] = new Vec3d(Math.cos(a) * r, y, Math.sin(a) * r);
        }
        return new Burst(entity.getEntityPos().add(0, entity.getHeight() * 0.6, 0), skin, System.currentTimeMillis(), dirs);
    }

    /**
     * The SoulBurst timeline, per death:
     * rise - the face floats up and turns; out - it bursts and the sparks fly to BurstRadius, braking hard;
     * hold - they hang there for a second, twinkling; back - they fall into the centre and shrink away.
     */
    private void renderBursts() {
        long now = System.currentTimeMillis();
        for (Burst b : bursts) {
            long t = now - b.start();
            if (t > RISE + OUT + HOLD + BACK) {
                bursts.remove(b);
                continue;
            }
            Vec3d apex = b.origin().add(0, RISE_HEIGHT, 0);

            if (t < RISE) {
                float k = t / (float) RISE;
                double ease = 1 - Math.pow(1 - k, 3);
                Vec3d at = b.origin().add(0, RISE_HEIGHT * ease, 0);
                double half = 0.32 + 0.12 * ease;
                float spin = 25f * (float) Math.sin(k * Math.PI * 2);
                if (b.skin() != null) {
                    // face (8,8)-(16,16) and the hat layer over it (40,8)-(48,16) of the 64x64 skin
                    Render3DEngine.drawBillboardRegion(b.skin(), at, half, 8 / 64f, 8 / 64f, 16 / 64f, 16 / 64f, 255, spin);
                    Render3DEngine.drawBillboardRegion(b.skin(), at, half * 1.12, 40 / 64f, 8 / 64f, 48 / 64f, 16 / 64f, 255, spin);
                }
                // a glow gathering under it, brightest just before it bursts
                thunder.hack.utility.render.core.CloudanBatchRenderer.billboard(
                        thunder.hack.utility.render.TextureStorage.fxSpark, at, half * (1.2 + k), 0,
                        Render2DEngine.injectAlpha(sparkColor(0), Math.round(120 * k)), false);
                continue;
            }

            long s = t - RISE;
            double dist;
            float alpha = 1f, size;
            if (s < OUT) {
                double k = s / (double) OUT;
                dist = burstRadius.getValue() * (1 - Math.pow(1 - k, 4)); // fast out, hard brake
                size = 0.16f;
            } else if (s < OUT + HOLD) {
                dist = burstRadius.getValue();
                size = 0.16f;
            } else {
                double k = (s - OUT - HOLD) / (double) BACK;
                dist = burstRadius.getValue() * (1 - k * k); // falls in, accelerating
                size = (float) (0.16 * (1 - 0.7 * k));
                alpha = (float) (1 - k * 0.6);
            }

            for (int i = 0; i < b.dirs().length; i++) {
                Vec3d at = apex.add(b.dirs()[i].multiply(dist));
                float twinkle = s >= OUT && s < OUT + HOLD ? 0.7f + 0.3f * (float) Math.sin(now / 90.0 + i) : 1f;
                Color c = Render2DEngine.injectAlpha(sparkColor(i * 7), Math.round(235 * alpha * twinkle));
                thunder.hack.utility.render.core.CloudanBatchRenderer.billboard(
                        thunder.hack.utility.render.TextureStorage.fxSpark, at, size, i * 0.7, c, false);
            }
        }
    }

    private Color sparkColor(int offset) {
        return thunder.hack.features.modules.client.HudEditor.getColor(offset);
    }

    private double calculateSpeed() {
        return (double) speed.getValue() / 100;
    }
}