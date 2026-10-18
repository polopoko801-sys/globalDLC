package thunder.hack.features.modules.render;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.SpawnReason;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import thunder.hack.features.modules.Module;
import thunder.hack.setting.Setting;
import thunder.hack.utility.render.Render3DEngine;

/**
 * A real Minecraft animal that follows you around - a fox, a frog, a cat... It is a client-side entity that
 * only exists in your world (nobody else sees it, the server never hears of it), so it is drawn with the
 * game's own model, lighting and walk animation.
 *
 * <p>Its position is set every frame (smoothly chasing a spot behind your shoulder) with the render
 * interpolation pinned to it, so it glides instead of stepping 20 times a second; the game's own tick still
 * sees the movement and plays the walking animation. Flyers (parrot, bee, allay) hover and bob instead.
 * Kilaura and the other combat code skip it via {@link #isPet}.
 */
public class Pets extends Module {
    public Pets() {
        super("Pets", Category.RENDER);
    }

    public enum Type {
        Fox(EntityType.FOX, false), Frog(EntityType.FROG, false), Cat(EntityType.CAT, false), Axolotl(EntityType.AXOLOTL, false),
        Dog(EntityType.WOLF, false), Rabbit(EntityType.RABBIT, false), Chicken(EntityType.CHICKEN, false),
        Parrot(EntityType.PARROT, true), Bee(EntityType.BEE, true), Allay(EntityType.ALLAY, true);

        final EntityType<? extends LivingEntity> type;
        final boolean flies;

        Type(EntityType<? extends LivingEntity> type, boolean flies) {
            this.type = type;
            this.flies = flies;
        }
    }

    private final Setting<Type> petType = new Setting<>("Pet", Type.Fox);
    private final Setting<Boolean> baby = new Setting<>("Baby", false, v -> !petType.getValue().flies);
    private final Setting<Boolean> sounds = new Setting<>("Sounds", false);
    private final Setting<Float> followSpeed = new Setting<>("FollowSpeed", 4f, 1f, 10f);

    private static final int PET_ID = -1338420;
    private static LivingEntity pet;
    private Type spawnedType;
    private boolean spawnedBaby;
    private Vec3d cur;
    private long lastNanos;
    private float petYaw;

    /** True for the pet entity, so aura / ESP / interaction code can leave it alone. */
    public static boolean isPet(Entity e) {
        return e != null && (e == pet || e.getId() == PET_ID);
    }

    @Override
    public void onDisable() {
        despawn();
    }

    @Override
    public void onLogout() {
        pet = null;
        cur = null;
    }

    private void despawn() {
        if (pet != null && mc.world != null && pet.getEntityWorld() == mc.world)
            mc.world.removeEntity(PET_ID, Entity.RemovalReason.DISCARDED);
        pet = null;
        cur = null;
    }

    private void spawn() {
        despawn();
        LivingEntity e = petType.getValue().type.create(mc.world, SpawnReason.LOAD);
        if (e == null) return;
        e.setId(PET_ID);
        e.setSilent(!sounds.getValue());
        e.setNoGravity(true);
        if (e instanceof net.minecraft.entity.passive.PassiveEntity p) p.setBaby(baby.getValue() && !petType.getValue().flies);
        // a dog is a tamed wolf in Minecraft (collar, friendly face) - same for the cat and the parrot
        if (e instanceof net.minecraft.entity.passive.TameableEntity tame) tame.setTamed(true, true);
        cur = target(1f);
        e.refreshPositionAndAngles(cur.x, cur.y, cur.z, mc.player.getYaw(), 0);
        mc.world.addEntity(e);
        pet = e;
        spawnedType = petType.getValue();
        spawnedBaby = baby.getValue();
    }

    /** The spot the pet heads for: a little behind and to the right of you, on your feet's level (or hovering). */
    private Vec3d target(float td) {
        double px = MathHelper.lerp(td, mc.player.lastRenderX, mc.player.getX());
        double py = MathHelper.lerp(td, mc.player.lastRenderY, mc.player.getY());
        double pz = MathHelper.lerp(td, mc.player.lastRenderZ, mc.player.getZ());
        double yaw = Math.toRadians(mc.player.getBodyYaw());
        // right = (-cos, -sin), forward = (-sin, cos) for Minecraft yaw; 0.9 to the right, 0.8 behind
        double x = px - Math.cos(yaw) * 0.9 + Math.sin(yaw) * 0.8;
        double z = pz - Math.sin(yaw) * 0.9 - Math.cos(yaw) * 0.8;
        double y = py;
        if (petType.getValue().flies)
            y += 1.3 + Math.sin(System.currentTimeMillis() / 1000.0 * 2.2) * 0.15;
        return new Vec3d(x, y, z);
    }

    @Override
    public void onUpdate() {
        if (fullNullCheck()) return;
        if (pet == null || pet.getEntityWorld() != mc.world || pet.isRemoved()
                || spawnedType != petType.getValue() || spawnedBaby != baby.getValue())
            spawn();
        if (pet != null) pet.setSilent(!sounds.getValue());
    }

    @Override
    public void onRender2D(DrawContext context) {
        if (fullNullCheck() || pet == null || cur == null) return;
        long now = System.nanoTime();
        float dt = lastNanos == 0 ? 0.016f : Math.min(0.1f, (now - lastNanos) / 1.0E9f);
        lastNanos = now;

        Vec3d tgt = target(Render3DEngine.getTickDelta());
        if (cur.distanceTo(tgt) > 12) cur = tgt;
        Vec3d prev = cur;
        double k = 1 - Math.exp(-dt * followSpeed.getValue());
        cur = cur.add(tgt.subtract(cur).multiply(k));

        // Face the way it walks only while really walking (you moving it along); while it just shuffles round
        // you as you turn, it looks where you look - facing the shuffle direction is what turned its back on
        // you. The turn itself is smoothed so it never snaps around.
        Vec3d move = cur.subtract(prev);
        double speed = Math.sqrt(move.horizontalLengthSquared()) / Math.max(dt, 1.0E-3);
        float want = speed > 2.5 ? (float) Math.toDegrees(Math.atan2(-move.x, move.z)) : mc.player.getYaw();
        float yaw = petYaw + MathHelper.wrapDegrees(want - petYaw) * (float) (1 - Math.exp(-dt * 8));
        petYaw = yaw;
        pet.setPosition(cur.x, cur.y, cur.z);
        pet.lastRenderX = cur.x;
        pet.lastRenderY = cur.y;
        pet.lastRenderZ = cur.z;
        pet.setYaw(yaw);
        pet.lastYaw = yaw;
        pet.setBodyYaw(yaw);
        pet.setHeadYaw(yaw);
    }
}
