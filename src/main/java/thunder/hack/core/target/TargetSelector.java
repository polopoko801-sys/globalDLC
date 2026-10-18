package thunder.hack.core.target;

import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.Monster;
import net.minecraft.entity.passive.AnimalEntity;
import net.minecraft.entity.passive.PassiveEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import thunder.hack.core.Managers;
import thunder.hack.core.manager.client.ModuleManager;
import thunder.hack.features.modules.combat.AntiBot;

import static thunder.hack.features.modules.Module.mc;

/**
 * Picks which entity is worth marking, and does nothing else.
 *
 * <p>A pure function of the world: it reads entities, applies the filters, scores what survives and hands
 * back a winner. It does not store a target, does not animate anything, and - the part that matters for
 * this module - does not touch the camera or the rotation state. Keeping selection separate from both state
 * and rotation is what lets a visual follow a target while your view stays entirely yours.
 *
 * <p>The checks are ordered by cost. A squared-distance compare throws out almost every entity in the world
 * for nothing; the field-of-view test is trigonometry on the survivors; the wall raycast, the only really
 * expensive one, runs last and only for entities that already passed everything else.
 */
public final class TargetSelector {
    private TargetSelector() {
    }

    /** What breaks a tie between two valid targets. */
    public enum Priority {
        /** Nearest first - the usual choice, and the one that matches what your hand is doing. */
        Distance,
        /** Lowest health first. */
        Health,
        /** Whatever is closest to the middle of the screen. */
        Crosshair
    }

    /**
     * Which entities may be marked at all.
     *
     * @param friends false means a friend is never a target. Separate from {@code players} on purpose:
     *                people want players on and friends off, which one flag cannot express.
     * @param bots    whether entities AntiBot has flagged count. Only consulted when AntiBot is on, since
     *                its list is empty and meaningless otherwise.
     */
    public record Filters(boolean players, boolean mobs, boolean animals,
                          boolean invisibles, boolean friends, boolean bots) {
    }

    /**
     * The best target, or null.
     *
     * @param fovDegrees the full cone width, centred on where you are actually looking. 360 disables the
     *                   test entirely. This reads the player's real rotation - it never sets it.
     */
    public static @Nullable LivingEntity pick(double range, float fovDegrees, @NotNull Priority priority,
                                              @NotNull Filters filters, boolean throughWalls) {
        if (mc.player == null || mc.world == null) return null;

        double rangeSq = range * range;
        LivingEntity best = null;
        double bestScore = Double.MAX_VALUE;

        for (Entity entity : mc.world.getEntities()) {
            if (!(entity instanceof LivingEntity living)) continue;
            if (!valid(living, rangeSq, filters, throughWalls)) continue;

            double score = score(living, priority, range);
            if (score < bestScore) {
                bestScore = score;
                best = living;
            }
        }

        if (best == null) return null;

        // The cone is applied after the winner is chosen rather than as a filter, so that turning away from
        // a target drops it instead of silently handing the mark to whatever else is on screen.
        return withinFov(best, fovDegrees) ? best : null;
    }

    /* ------------------------------------------------------------------ */
    /* Filtering                                                           */
    /* ------------------------------------------------------------------ */

    private static boolean valid(LivingEntity entity, double rangeSq, Filters filters, boolean throughWalls) {
        if (entity == mc.player) return false;
        if (!entity.isAlive() || entity.isRemoved() || entity.getHealth() <= 0f) return false;

        // Cheapest reject first: this is what keeps the loop affordable in a busy world.
        if (mc.player.squaredDistanceTo(entity) > rangeSq) return false;

        if (!filters.invisibles() && entity.isInvisible()) return false;

        if (entity instanceof PlayerEntity player) {
            if (!filters.players()) return false;
            // Neither can be damaged, so marking one is marking something you cannot act on.
            if (player.isSpectator() || player.isCreative()) return false;
            if (player.getName().getString().equalsIgnoreCase(mc.player.getName().getString())) return false;
            if (!filters.friends() && Managers.FRIEND.isFriend(player)) return false;
        } else if (entity instanceof Monster) {
            if (!filters.mobs()) return false;
        } else if (entity instanceof AnimalEntity || entity instanceof PassiveEntity) {
            if (!filters.animals()) return false;
        } else {
            // Anything else living - an armour stand, a boat's rider, a mod's entity. Grouped with mobs
            // rather than given a filter nobody would find.
            if (!filters.mobs()) return false;
        }

        if (!filters.bots() && ModuleManager.antiBot.isEnabled() && AntiBot.bots.contains(entity))
            return false;

        // Last, because it is the only check here that costs a raycast.
        return throughWalls || mc.player.canSee(entity);
    }

    /* ------------------------------------------------------------------ */
    /* Scoring - lower wins                                               */
    /* ------------------------------------------------------------------ */

    private static double score(LivingEntity entity, Priority priority, double range) {
        return switch (priority) {
            case Distance -> mc.player.squaredDistanceTo(entity);
            case Health -> entity.getHealth() + entity.getAbsorptionAmount();
            // Normalised to degrees so the number means the same thing at any range, and a target dead
            // centre at twenty blocks beats one off to the side at three.
            case Crosshair -> angleTo(entity);
        };
    }

    /** Degrees between where the player is looking and the direction of this entity. */
    public static double angleTo(@NotNull Entity entity) {
        Vec3d eye = mc.player.getEyePos();
        Vec3d to = TargetState.centreOf(entity).subtract(eye);

        double horizontal = Math.sqrt(to.x * to.x + to.z * to.z);
        float yaw = (float) Math.toDegrees(Math.atan2(to.z, to.x)) - 90f;
        float pitch = (float) -Math.toDegrees(Math.atan2(to.y, horizontal));

        float yawDelta = MathHelper.wrapDegrees(yaw - mc.player.getYaw());
        float pitchDelta = MathHelper.wrapDegrees(pitch - mc.player.getPitch());

        // The real angle between two directions, not the sum of two deltas: at a steep pitch a yaw error of
        // ninety degrees is barely any angle at all, and adding them would reject a target right under you.
        return Math.toDegrees(Math.acos(Math.clamp(
                Math.cos(Math.toRadians(yawDelta)) * Math.cos(Math.toRadians(pitchDelta)), -1.0, 1.0)));
    }

    private static boolean withinFov(Entity entity, float fovDegrees) {
        if (fovDegrees >= 360f) return true;
        return angleTo(entity) <= fovDegrees / 2f;
    }
}
