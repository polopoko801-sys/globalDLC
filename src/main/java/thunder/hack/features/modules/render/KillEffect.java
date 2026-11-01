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
        SoulBurst,
        /** You turn into a ninja, draw a katana and cut the body into pieces (see NinjaKill). */
        Ninja
    }

    private final Setting<Boolean> ninjaOnlyMine = new Setting<>("OnlyMyKills", true, v -> mode.getValue() == Mode.Ninja);
    private final Setting<Boolean> ninjaBlood = new Setting<>("Blood", true, v -> mode.getValue() == Mode.Ninja);
    private final Setting<Boolean> ninjaSmoke = new Setting<>("Smoke", true, v -> mode.getValue() == Mode.Ninja);
    /** The camera leaves your head and orbits the cut while it plays, like a free cam shot. */
    private final Setting<Boolean> ninjaCinematic = new Setting<>("Cinematic", true);

    /** Who you hit last and when - Ninja only plays for kills that are really yours. */
    private final Map<Integer, Long> hitByMe = new ConcurrentHashMap<>();

    @meteordevelopment.orbit.EventHandler
    @SuppressWarnings("unused")
    public void onAttack(thunder.hack.events.impl.EventAttack event) {
        if (event.getEntity() != null) hitByMe.put(event.getEntity().getId(), System.currentTimeMillis());
    }

    /** Every way a death is noticed ends here once - it starts the effect for the current mode. */
    private void onDeath(Entity entity) {
        if (mc.player == null || mc.world == null || entity == mc.player) return;
        // YOU died / lost: no kill effect. Losing a duel moves you out and the opponent "disappears" from your view,
        // which would otherwise look exactly like one of your kills.
        if (iLost()) return;
        if (!(entity instanceof LivingEntity)) return;
        if (!(entity instanceof PlayerEntity) && !mobs.getValue()) return;
        if (renderEntities.containsKey(entity) || lightingEntities.containsKey(entity)) return;
        Long seen = deathSeen.get(entity.getId());
        if (seen != null && System.currentTimeMillis() - seen < 4000) return; // one death, one effect
        deathSeen.put(entity.getId(), System.currentTimeMillis());

        if (playSound.getValue() && mode.getValue() != Mode.Ninja)
            mc.world.playSound(mc.player, entity.getBlockPos(), Managers.SOUND.ORTHODOX_SOUNDEVENT, SoundCategory.BLOCKS, volume.getValue() / 100f, 1f);
        renderEntities.put(entity, System.currentTimeMillis());
    }

    private final Map<Integer, Long> deathSeen = new ConcurrentHashMap<>();

    /** When you last died or lost a fight (0 = not recently). */
    private volatile long myDeathAt;

    private boolean iLost() {
        return mc.player.getHealth() <= 0f || mc.player.isDead() || mc.currentScreen instanceof net.minecraft.client.gui.screen.DeathScreen
                || System.currentTimeMillis() - myDeathAt < 5000;
    }

    /** You died or lost: remember it, and stop anything already playing (it would have been for the wrong side). */
    private void markMyDeath() {
        myDeathAt = System.currentTimeMillis();
        mc.execute(() -> {
            renderEntities.clear();
            scenes.clear();
            bursts.clear();
            NinjaKill.clear();
        });
    }

    /**
     * Deaths the tick scan cannot see. Duel / minigame servers often never let a player die: the loser is
     * removed from the world (or teleported out) the moment the fight is decided, so there is no corpse at 0 HP
     * to find. Two packets catch it instead:
     * <ul>
     *   <li>the death status (3) the server sends when something really dies,</li>
     *   <li>a player you hit in the last 1.5 s, close to you, being removed from the world.</li>
     * </ul>
     * Runs on the netty thread; the work is handed to the main thread, queued BEFORE the game handles the packet,
     * so a removed entity is still there to read.
     */
    @meteordevelopment.orbit.EventHandler
    @SuppressWarnings("unused")
    public void onPacketReceive(thunder.hack.events.impl.PacketEvent.Receive event) {
        if (mc.player == null) return;
        if (event.getPacket() instanceof net.minecraft.network.packet.s2c.play.HealthUpdateS2CPacket hp && hp.getHealth() <= 0f) {
            markMyDeath();
            return;
        }
        if (event.getPacket() instanceof net.minecraft.network.packet.s2c.play.DeathMessageS2CPacket) {
            markMyDeath();
            return;
        }
        if (event.getPacket() instanceof net.minecraft.network.packet.s2c.play.GameMessageS2CPacket msg) {
            String low = msg.content().getString().toLowerCase(java.util.Locale.ROOT);
            if (low.contains("przegrałeś") || low.contains("przegrales") || low.contains("zginąłeś") || low.contains("zginales")
                    || low.contains("you lost") || low.contains("you died") || low.contains("defeat")) {
                markMyDeath();
                return;
            }
            onWinMessage(msg.content().getString());
            return;
        }
        if (event.getPacket() instanceof net.minecraft.network.packet.s2c.play.EntityStatusS2CPacket status && status.getStatus() == 3) {
            mc.execute(() -> {
                if (mc.world == null) return;
                Entity e = status.getEntity(mc.world);
                if (e != null) onDeath(e);
            });
        } else if (event.getPacket() instanceof net.minecraft.network.packet.s2c.play.EntitiesDestroyS2CPacket destroy) {
            long now = System.currentTimeMillis();
            for (int id : destroy.getEntityIds()) {
                Long hit = hitByMe.get(id);
                if (hit == null || now - hit > 1500) continue;
                mc.execute(() -> {
                    if (mc.world == null || mc.player == null) return;
                    Entity e = mc.world.getEntityById(id);
                    if (e instanceof PlayerEntity && e.squaredDistanceTo(mc.player) < 10 * 10) onDeath(e);
                });
            }
        }
    }

    /** Camera mixin: the Ninja cinematic pose {x,y,z,yaw,pitch}, or null. */
    public static double[] cinematicPose(net.minecraft.util.math.Vec3d cur, float yaw, float pitch) {
        if (!ModuleManagerHolder.on()) return null;
        double[] ninja = NinjaKill.cinematicPose(cur, yaw, pitch);
        if (ninja != null) return ninja;
        Scene s = thunder.hack.core.manager.client.ModuleManager.killEffect.cinematicScene();
        return s == null ? null : orbit(s.origin.add(0, s.height * 0.55, 0), s.height / 1.8f, s.age(), s.length, cur, yaw, pitch);
    }

    /** Hand renderer mixin: hide the first-person hand while the camera is out of your head. */
    public static boolean cinematicActive() {
        return ModuleManagerHolder.on() && (NinjaKill.cinematicActive()
                || thunder.hack.core.manager.client.ModuleManager.killEffect.cinematicScene() != null);
    }

    private static final class ModuleManagerHolder {
        static boolean on() {
            var ke = thunder.hack.core.manager.client.ModuleManager.killEffect;
            return ke != null && ke.isEnabled();
        }
    }

    /**
     * A fight decided by the server rather than by a death - "Wygrałeś walkę!" on duel servers: the effect plays on
     * whoever you hit last (in the last 5 s), right away instead of when the server gets round to removing them.
     */
    private void onWinMessage(String text) {
        String low = text.toLowerCase(java.util.Locale.ROOT);
        if (!(low.contains("wygrałeś") || low.contains("wygrales") || low.contains("you won") || low.contains("you win")
                || low.contains("victory") || low.contains("zwycięstwo"))) return;
        long now = System.currentTimeMillis();
        int best = -1;
        long bestAt = 0;
        for (var e : hitByMe.entrySet())
            if (now - e.getValue() < 5000 && e.getValue() > bestAt) {
                best = e.getKey();
                bestAt = e.getValue();
            }
        if (best == -1) return;
        int id = best;
        mc.execute(() -> {
            if (mc.world == null) return;
            Entity e = mc.world.getEntityById(id);
            if (e != null) onDeath(e);
        });
    }

    /** Ninja: the short white flash on each cut and the red one when the body falls apart. */
    @Override
    public void onRender2D(net.minecraft.client.gui.DrawContext context) {
        if (mode.getValue() == Mode.Ninja) NinjaKill.render2D(context);
    }

    /** Used by the entity renderer mixin: a body that has been cut is drawn only as its pieces. */
    public static boolean hideEntity(Entity e) {
        // the cut body, and - while the dashing ninja figure is on stage - your own body: the figure is you
        return NinjaKill.isHidden(e) || (e == mc.player && ModuleManagerHolder.on() && NinjaKill.figureActive());
    }

    @Override
    public void onDisable() {
        NinjaKill.clear();
        scenes.clear();
        bursts.clear();
    }

    private final Setting<Float> burstRadius = new Setting<>("BurstRadius", 7f, 2f, 12f, v -> mode.getValue() == Mode.SoulBurst);
    private final Setting<Integer> burstSparks = new Setting<>("BurstSparks", 48, 8, 160, v -> mode.getValue() == Mode.SoulBurst);

    /** One death being played: its scene (where - it follows you into a new world), whose face, when, spark directions. */
    private record Burst(Scene scene, Identifier skin, long start, Vec3d[] dirs) {
        Vec3d origin() {
            return scene.origin.add(0, scene.height * 0.6, 0);
        }
    }

    private final java.util.List<Burst> bursts = new java.util.concurrent.CopyOnWriteArrayList<>();

    // timeline, ms (half as slow again as it was - the burst had no time to be seen)
    private static final long RISE = 1300, OUT = 700, HOLD = 1400, BACK = 1000;
    private static final double RISE_HEIGHT = 2.5;

    /**
     * One kill effect being played by the non-Ninja modes. Holds its own copy of where the body was, so the effect
     * keeps playing after the entity is gone, and moves along with you if the server sends you to another world
     * (a duel ending in the lobby) - exactly like the Ninja scene.
     */
    private static final class Scene {
        Vec3d origin;
        net.minecraft.world.World world;
        final Vec3d fromPlayer;
        final long start, length;
        final float height, width;
        final boolean cinematic;
        int done;       // progress counter for effects that happen in steps (lava rows, lightning strikes)

        Scene(Entity e, long length, boolean cinematic) {
            this.origin = e.getEntityPos();
            this.world = mc.world;
            this.fromPlayer = origin.subtract(mc.player.getEntityPos());
            this.start = System.currentTimeMillis();
            this.length = length;
            this.height = Math.max(0.5f, e.getHeight());
            this.width = Math.max(0.3f, e.getWidth());
            this.cinematic = cinematic;
        }

        long age() {
            return System.currentTimeMillis() - start;
        }
    }

    private final java.util.List<Scene> scenes = new java.util.concurrent.CopyOnWriteArrayList<>();

    private long lengthOf(Mode m) {
        return switch (m) {
            case Orthodox -> 4000;
            case FallingLava -> 2600;
            case LightningBolt -> 2800;
            case SoulBurst -> RISE + OUT + HOLD + BACK;
            case Ninja -> 0;
        };
    }

    /** Drops finished scenes and carries live ones into a new world. */
    private void tickScenes() {
        for (Scene s : scenes) {
            if (s.age() > s.length) {
                scenes.remove(s);
                continue;
            }
            if (s.world != mc.world && mc.player != null) {
                s.origin = mc.player.getEntityPos().add(s.fromPlayer);
                s.world = mc.world;
            }
        }
    }

    @Override
    public void onRender3D(MatrixStack stack) {
        if (mc.world == null || mc.player == null) return;

        // every death the scan / packets found becomes a scene (Ninja keeps its own)
        if (mode.getValue() != Mode.Ninja) {
            renderEntities.forEach((entity, time) -> {
                renderEntities.remove(entity);
                Long hit = hitByMe.get(entity.getId());
                boolean mine = hit != null && System.currentTimeMillis() - hit < 3000;
                // the camera only flies for YOUR kills - not for every stranger dying nearby
                Scene s = new Scene(entity, lengthOf(mode.getValue()), ninjaCinematic.getValue() && mine);
                scenes.add(s);
                if (mode.getValue() == Mode.SoulBurst) bursts.add(newBurst(entity, s));
                if (mode.getValue() == Mode.LightningBolt) lightingEntities.put(entity, System.currentTimeMillis());
            });
            tickScenes();
        }

        switch (mode.getValue()) {
            // the cross rises slowly (Y Speed sets how fast) and fades out over its last second
            case Orthodox -> scenes.forEach(s -> {
                float t = s.age() / 1000f;
                double lift = calculateSpeed() * 20 * t;
                float fade = Math.clamp((s.length - s.age()) / 1000f, 0f, 1f);
                Color c = color.getValue().getColorObject();
                Color col = new Color(c.getRed(), c.getGreen(), c.getBlue(), Math.round(c.getAlpha() * fade));
                Vec3d o = s.origin;
                Render3DEngine.drawLine(o.add(0, lift, 0), o.add(0, 3 + lift, 0), col);
                Render3DEngine.drawLine(o.add(1, 2.3 + lift, 0), o.add(-1, 2.3 + lift, 0), col);
                Render3DEngine.drawLine(o.add(0.5, 1.2 + lift, 0), o.add(-0.5, 0.8 + lift, 0), col);
            });
            // the body melts from the head down over ~1.6 s instead of everything at once
            case FallingLava -> scenes.forEach(s -> {
                int rows = Math.max(1, Math.round(s.height * 10));
                int target = Math.min(rows, Math.round(rows * Math.min(1f, s.age() / 1600f)));
                int cols = Math.max(1, Math.round(s.width * 10));
                for (; s.done < target; s.done++) {
                    double y = s.origin.y + s.height - s.done * 0.1;
                    for (int j = 0; j < cols; j++)
                        for (int k = 0; k < cols; k++)
                            mc.world.addParticleClient(ParticleTypes.FALLING_LAVA,
                                    s.origin.x - s.width / 2 + j * 0.1, y, s.origin.z - s.width / 2 + k * 0.1, 0, 0, 0);
                }
            });
            case Ninja -> {
                renderEntities.forEach((entity, time) -> {
                    renderEntities.remove(entity);
                    Long hit = hitByMe.get(entity.getId());
                    boolean mine = hit != null && System.currentTimeMillis() - hit < 3000;
                    if (entity instanceof LivingEntity liv && (mine || !ninjaOnlyMine.getValue()))
                        NinjaKill.start(liv, ninjaBlood.getValue(), ninjaSmoke.getValue(), ninjaCinematic.getValue());
                });
                NinjaKill.render();
            }
            case SoulBurst -> renderBursts();
            // three strikes, 0.7 s apart, each a little off the last one
            case LightningBolt -> scenes.forEach(s -> {
                while (s.done < 3 && s.age() >= s.done * 700L) {
                    LightningEntity bolt = new LightningEntity(EntityType.LIGHTNING_BOLT, mc.world);
                    double ox = s.done == 0 ? 0 : (Math.random() - 0.5) * 1.6, oz = s.done == 0 ? 0 : (Math.random() - 0.5) * 1.6;
                    bolt.refreshPositionAfterTeleport(s.origin.x + ox, s.origin.y, s.origin.z + oz);
                    mc.world.addEntity(bolt);
                    s.done++;
                }
            });
            default -> {
            }
        }
    }

    /**
     * The orbit shot shared by every mode: the camera eases out of your head, swings round the spot and keeps it
     * framed, then eases back. {x,y,z,yaw,pitch}.
     */
    static double[] orbit(Vec3d focus, float scale, long t, long length, Vec3d cur, float curYaw, float curPitch) {
        long in = 450, out = 650;
        float k = ease(Math.min(1f, t / (float) in));
        if (t > length - out) k *= ease(Math.max(0f, (length - t) / (float) out));
        Vec3d me = mc.player.getEntityPos();
        double a0 = Math.atan2(me.z - focus.z, me.x - focus.x) + Math.toRadians(40);
        double a = a0 + Math.toRadians(85) * ease(t / (float) length);
        double r = 4.3 * scale + 0.6;
        Vec3d cam = focus.add(Math.cos(a) * r, 1.25 * scale + 0.3, Math.sin(a) * r);
        Vec3d d = focus.subtract(cam);
        float yaw = (float) Math.toDegrees(Math.atan2(-d.x, d.z));
        float pitch = (float) -Math.toDegrees(Math.atan2(d.y, Math.sqrt(d.x * d.x + d.z * d.z)));
        Vec3d pos = cur.lerp(cam, k);
        return new double[]{pos.x, pos.y, pos.z,
                curYaw + net.minecraft.util.math.MathHelper.wrapDegrees(yaw - curYaw) * k, curPitch + (pitch - curPitch) * k};
    }

    private static float ease(float x) {
        x = Math.clamp(x, 0f, 1f);
        return x < 0.5f ? 4f * x * x * x : 1f - (float) Math.pow(-2f * x + 2f, 3) / 2f;
    }

    /** The newest generic scene whose camera is still running, or null. */
    private Scene cinematicScene() {
        if (scenes.isEmpty()) return null;
        Scene s = scenes.get(scenes.size() - 1);
        return s.cinematic && s.age() < s.length ? s : null;
    }

    @Override
    public void onUpdate() {
        Managers.ASYNC.getAsyncEntities().forEach(entity -> {
            if (!(entity instanceof PlayerEntity) && !mobs.getValue()) return;
            if (!(entity instanceof LivingEntity liv)) return;

            // dead, OR at 0 HP while the game still calls it alive (some servers never let the death happen)
            if (entity.isAlive() && liv.getHealth() > 0) return;
            onDeath(entity);
        });

        if (!lightingEntities.isEmpty()) {
            lightingEntities.forEach((entity, time) -> {
                if (System.currentTimeMillis() - time > 5000) {
                    lightingEntities.remove(entity);
                }
            });
        }
    }

    private Burst newBurst(Entity entity, Scene scene) {
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
        return new Burst(scene, skin, System.currentTimeMillis(), dirs);
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