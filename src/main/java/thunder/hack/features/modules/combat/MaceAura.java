package thunder.hack.features.modules.combat;

import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.AxeItem;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
import net.minecraft.util.Hand;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import thunder.hack.core.Managers;
import thunder.hack.core.manager.client.ModuleManager;
import thunder.hack.events.impl.EventSync;
import thunder.hack.events.impl.PlayerUpdateEvent;
import thunder.hack.features.modules.Module;
import thunder.hack.setting.Setting;

/**
 * While you are in the air and coming down with a mace in the hotbar, this switches to the mace and hits
 * a nearby player. The smash bonus scales with how far you have fallen, so it strikes as soon as a target
 * is in reach: waiting longer risks landing, which zeroes the bonus entirely.
 *
 * <p>There is no minimum fall. The bonus scales smoothly from the first block, so a short hop is a small
 * smash rather than no smash - a threshold below which it refused to swing simply threw those away.
 *
 * <p>Reach is bounded by what the server will actually accept - the entity interaction range attribute
 * plus vanilla's one block of leniency - not only by the Range setting. An attack outside that is
 * rejected server-side and comes back as a swing with no damage, which is what a ghost hit is.
 */
public final class MaceAura extends Module {
    /** Upper bound on reach. The server's own limit still applies on top - see {@link #serverReach}. */
    private final Setting<Float> range = new Setting<>("Range", 4.0f, 2f, 6f);
    private final Setting<Boolean> onlyPlayers = new Setting<>("OnlyPlayers", true);
    /** Swing while you are gliding too — needed to reach someone who wind-charged away on elytra. */
    private final Setting<Boolean> whileGliding = new Setting<>("WhileGliding", true);
    private final Setting<Boolean> silentSwitch = new Setting<>("SilentSwitch", false);
    // Client = your view actually snaps onto the target (you aim at them); Silent = server-only, camera
    // stays put; Off = no aiming. Client makes the smash feel/land right, so it's the default.
    private final Setting<Rotate> rotate = new Setting<>("Rotate", Rotate.Client);
    /**
     * How hard the view is pulled onto the target each frame. 1 snaps in one frame.
     *
     * <p>This only affects how the turn LOOKS while the module is lining up. The tick that actually
     * attacks snaps the view exactly onto the target regardless, because the server resolves the hit
     * against the angles it was sent with.
     */
    private final Setting<Float> smooth = new Setting<>("Smooth", 1f, 0.1f, 1f, v -> rotate.getValue() != Rotate.Off);
    private final Setting<Boolean> keepMace = new Setting<>("KeepMace", true, v -> !silentSwitch.getValue());
    /**
     * How charged the attack has to be before it swings.
     *
     * <p>A full charge is the biggest SINGLE smash, but the cooldown resets on every hit, so insisting
     * on one means exactly one strike per fall. The mace swings slowly - a full charge takes well over a
     * second, which is longer than most falls last - so waiting for it is what limited this to one hit.
     * A quarter charge fits three or four strikes into the same drop, and three partial smashes beat one
     * full one by a wide margin.
     */
    private final Setting<Float> minCharge = new Setting<>("MinCharge", 0.25f, 0.05f, 1f);
    /**
     * Swing at everyone in range, not only the nearest.
     *
     * <p>The mace's own smash lands on one target, but a swing that reaches two players is two hits, and
     * refusing to take the second one because it was a few blocks further away is simply damage left on
     * the floor.
     */
    // OFF by default: attacking several entities in one tick is impossible in vanilla and is exactly GrimAC's
    // BadPacketsA flag. The mace smash already damages nearby entities SERVER-SIDE from one charged hit, so this
    // is both redundant and detectable - leave it off on anticheat servers.
    private final Setting<Boolean> multiTarget = new Setting<>("MultiTarget", false);
    private final Setting<Integer> maxTargets = new Setting<>("MaxTargets", 3, 2, 8, v -> multiTarget.getValue());
    /**
     * Steel slam: an axe hit to break the shield, then the mace immediately after.
     *
     * <p>A mace smash into a raised shield is simply blocked, and the fall distance it was worth is
     * gone. An axe hit disables the shield for several seconds, so the order matters far more than the
     * timing does - axe first, mace second, in the same tick.
     */
    private final Setting<Boolean> steelSlam = new Setting<>("SteelSlam", true);
    /** Break the shield even when the target is not visibly blocking yet. */
    private final Setting<Boolean> alwaysAxe = new Setting<>("AlwaysAxe", false, v -> steelSlam.getValue());

    private LivingEntity target;
    private float rotYaw, rotPitch;

    public enum Rotate { Client, Silent, Off }

    public MaceAura() {
        super("MaceAura", Category.COMBAT);
    }

    @Override
    public void onEnable() {
        target = null;
        if (mc.player != null) {
            rotYaw = mc.player.getYaw();
            rotPitch = mc.player.getPitch();
        }
    }

    @Override
    public void onDisable() {
        target = null;
        ModuleManager.rotations.fixRotation = Float.NaN;
    }

    @EventHandler
    public void onUpdate(PlayerUpdateEvent e) {
        target = null;
        pendingSmash = -1;

        if (mc.player.isOnGround() || mc.player.isTouchingWater())
            return;

        // Airborne and coming down is the whole requirement now. There is no minimum fall any more: the
        // mace's bonus scales smoothly from the first block, so a metre-high hop is a small smash rather
        // than no smash, and refusing to swing below some threshold just threw those away.
        if (mc.player.isGliding()) {
            if (!whileGliding.getValue()) return;
        } else if (mc.player.getVelocity().y > 0.0) {
            return;
        }

        int maceSlot = findMace();
        if (maceSlot == -1) return;

        target = findTarget();
        if (target == null) return;

        // aim angles for both the silent server rotation and the visible client turn
        Vec3d diff = aimPoint(target).subtract(mc.player.getEyePos());
        rotYaw = (float) (Math.toDegrees(Math.atan2(diff.z, diff.x)) - 90d);
        rotPitch = MathHelper.clamp((float) (-Math.toDegrees(Math.atan2(diff.y, Math.hypot(diff.x, diff.z)))), -90f, 90f);

        // only smash when charged: one strong hit per fall instead of spamming weak taps into the target
        if (mc.player.getAttackCooldownProgress(0.5f) < minCharge.getValue())
            return;

        // Everything else is ready, so the view is SNAPPED here rather than waited on.
        //
        // <p>This used to ease the turn over several frames in the render pass and refuse to attack until
        // it had caught up. On a fall you have well under a second, and easing ate most of it - which is
        // the "it points at him, stares, and does nothing" behaviour. The eased turn still happens in
        // render while the module is lining up; this is the last tick, and on the last tick the angles
        // have to be exact anyway or the server resolves the hit against the wrong direction.
        if (rotate.getValue() == Rotate.Client) {
            mc.player.setYaw(rotYaw);
            mc.player.setPitch(rotPitch);
        }

        // Do NOT attack here (tick HEAD, before the rotation packet). Queue it and fire from onSync - the HEAD of
        // sendMovementPackets - so the swing goes in vanilla order (after the look is set, before the move packet
        // leaves). This is the same ordering that fixed Kilaura's GrimAC flags.
        pendingSmash = maceSlot;
    }

    /** Set in onUpdate when a smash is ready; fired from onSync in the correct packet order. -1 = nothing queued. */
    private int pendingSmash = -1;

    /**
     * How far the SERVER will let you reach.
     *
     * <p>NOT just the entity interaction range attribute. Vanilla checks the hit with
     * {@code canInteractWithEntity(box, 1.0)}, which adds a blocks worth of leniency on top of the
     * attribute before rejecting anything - so the real limit is attribute + 1, about four blocks for a
     * normal player, and clamping to the bare attribute costs you a block of legitimate reach.
     *
     * <p>The attribute is read live, so a server that grants extra reach raises this on its own and the
     * Range setting becomes the thing that decides. Whichever is smaller wins.
     */
    private double serverReach() {
        double attribute = mc.player.getAttributeValue(EntityAttributes.ENTITY_INTERACTION_RANGE);
        double allowed = (attribute > 0 ? attribute : 3.0) + VANILLA_LENIENCY;
        return Math.min(range.getValue(), allowed);
    }

    /** The slack vanilla adds to the attribute before it refuses an attack. */
    private static final double VANILLA_LENIENCY = 1.0;

    // Client mode: snap the REAL view onto the target so you aim at them (the head turns for you).
    public void onRender3D(MatrixStack stack) {
        if (rotate.getValue() != Rotate.Client || target == null) return;
        float pull = MathHelper.clamp(smooth.getValue(), 0.1f, 1f);
        float ny = mc.player.getYaw() + MathHelper.wrapDegrees(rotYaw - mc.player.getYaw()) * pull;
        float np = mc.player.getPitch() + MathHelper.wrapDegrees(rotPitch - mc.player.getPitch()) * pull;
        mc.player.setYaw(ny);
        mc.player.setPitch(MathHelper.clamp(np, -90f, 90f));
    }

    private void smashAttack(int maceSlot) {
        int prevSlot = mc.player.getInventory().selectedSlot;

        // The axe goes first, while the shield is still up. Doing it the other way round wastes the
        // smash on a block and the fall distance with it.
        if (steelSlam.getValue() && shouldBreakShield()) breakShield();

        boolean alreadyHolding = mc.player.getInventory().selectedSlot == maceSlot;

        if (!alreadyHolding) {
            sendPacket(new UpdateSelectedSlotC2SPacket(maceSlot));
            if (!silentSwitch.getValue()) mc.player.getInventory().selectedSlot = maceSlot;
        }

        // The one the rotation is actually pointing at goes first, then anyone else within reach. Order
        // matters: the first hit is the one the charge is spent on.
        mc.interactionManager.attackEntity(mc.player, target);
        mc.player.swingHand(Hand.MAIN_HAND);

        if (multiTarget.getValue()) {
            int hit = 1;
            for (Entity entity : mc.world.getEntities()) {
                if (hit >= maxTargets.getValue()) break;
                if (entity == target || !(entity instanceof LivingEntity living) || !valid(living)) continue;

                mc.interactionManager.attackEntity(mc.player, living);
                hit++;
            }
        }

        // silent switch always restores; a real switch restores unless we're told to keep the mace out
        if (!alreadyHolding && (silentSwitch.getValue() || !keepMace.getValue())) {
            sendPacket(new UpdateSelectedSlotC2SPacket(prevSlot));
            if (!silentSwitch.getValue()) mc.player.getInventory().selectedSlot = prevSlot;
        }
    }

    // Silent mode: rotate for the server only (model + packet), restore the camera afterwards.
    @EventHandler
    public void onSync(EventSync e) {
        if (target == null) return;

        boolean silent = rotate.getValue() == Rotate.Silent;
        float realYaw = mc.player.getYaw();
        float realPitch = mc.player.getPitch();

        // Silent: feed the server the aim rotation on THIS move packet; the camera is restored afterwards.
        if (silent) {
            mc.player.setYaw(rotYaw);
            mc.player.setPitch(rotPitch);
            mc.player.setBodyYaw(rotYaw);
            mc.player.setHeadYaw(rotYaw);
        }

        // Fire the queued smash here: rotation is set, and this runs before the move packet leaves (vanilla order).
        if (pendingSmash >= 0) {
            smashAttack(pendingSmash);
            pendingSmash = -1;
        }

        if (silent)
            e.addPostAction(() -> {
                mc.player.setYaw(realYaw);
                mc.player.setPitch(realPitch);
            });
    }

    private Vec3d aimPoint(LivingEntity t) {
        return t.getEntityPos().add(0, t.getHeight() / 2f, 0);
    }

    private int findMace() {
        for (int i = 0; i < 9; i++)
            if (mc.player.getInventory().getStack(i).getItem() == Items.MACE)
                return i;
        return -1;
    }

    /** The first axe in the hotbar, or -1. Any axe disables a shield; the tier only changes the damage. */
    private int findAxe() {
        for (int i = 0; i < 9; i++)
            if (mc.player.getInventory().getStack(i).getItem() instanceof AxeItem)
                return i;
        return -1;
    }

    /** Whether an axe hit is worth spending on this target right now. */
    private boolean shouldBreakShield() {
        if (!(target instanceof PlayerEntity player)) return false;
        if (findAxe() == -1) return false;

        // A shield that is already disabled does not need breaking again, and an axe hit spent on it is
        // one the mace could have had.
        if (player.getItemCooldownManager().isCoolingDown(player.getOffHandStack())) return false;

        return alwaysAxe.getValue() || player.isBlocking();
    }

    /**
     * One axe hit, to disable the shield.
     *
     * <p>Switched to and swung exactly like the mace is, and deliberately NOT switched back afterwards -
     * {@link #smashAttack} switches to the mace immediately, so putting the previous item back in
     * between is a wasted slot change the server has to process before the hit that matters.
     */
    private void breakShield() {
        int axe = findAxe();
        if (axe == -1) return;

        if (mc.player.getInventory().selectedSlot != axe) {
            sendPacket(new UpdateSelectedSlotC2SPacket(axe));
            if (!silentSwitch.getValue()) mc.player.getInventory().selectedSlot = axe;
        }

        mc.interactionManager.attackEntity(mc.player, target);
        mc.player.swingHand(Hand.MAIN_HAND);
    }

    /** Distance from the eyes to the closest point of the entity's hitbox (0 when inside it). */
    private double boxDistance(LivingEntity entity) {
        Vec3d eye = mc.player.getEyePos();
        net.minecraft.util.math.Box box = entity.getBoundingBox();
        double dx = Math.max(Math.max(box.minX - eye.x, 0), eye.x - box.maxX);
        double dy = Math.max(Math.max(box.minY - eye.y, 0), eye.y - box.maxY);
        double dz = Math.max(Math.max(box.minZ - eye.z, 0), eye.z - box.maxZ);
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private LivingEntity findTarget() {
        LivingEntity best = null;
        double bestDist = Double.MAX_VALUE;
        for (Entity entity : mc.world.getEntities()) {
            if (!(entity instanceof LivingEntity living) || !valid(living)) continue;
            double dist = boxDistance(living);
            if (dist < bestDist) {
                bestDist = dist;
                best = living;
            }
        }
        return best;
    }

    private boolean valid(LivingEntity entity) {
        if (entity == mc.player || !entity.isAlive() || entity.getHealth() <= 0f) return false;
        if (entity instanceof ArmorStandEntity) return false;
        // reach measured to the nearest point of the hitbox, like the server does — centre-to-centre put a
        // target that was boosted straight above you out of range while he was still perfectly hittable
        if (boxDistance(entity) > serverReach()) return false;
        if (entity instanceof PlayerEntity p) {
            if (Managers.FRIEND.isFriend(p)) return false;
            if (p.getName().getString().equalsIgnoreCase(mc.player.getName().getString())) return false;
            return true;
        }
        return !onlyPlayers.getValue();
    }
}
