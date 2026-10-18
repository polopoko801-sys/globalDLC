package thunder.hack.features.modules.combat;

import net.minecraft.entity.player.PlayerEntity;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import thunder.hack.core.Managers;
import thunder.hack.core.manager.player.CombatManager;
import thunder.hack.features.modules.base.TrapModule;
import thunder.hack.setting.Setting;
import thunder.hack.utility.world.HoleUtility;

public final class AutoTrap extends TrapModule {
    private final Setting<CombatManager.TargetBy> targetBy = new Setting<>("Target By", CombatManager.TargetBy.Distance);
    private final Setting<Boolean> targetMovingPlayers = new Setting<>("MovingPlayers", false);
    private final Setting<Require> require = new Setting<>("Require", Require.Any);

    public AutoTrap() {
        super("AutoTrap", Category.COMBAT);
    }

    @Override
    protected boolean needNewTarget() {
        return target == null
                || target.distanceTo(mc.player) > range.getValue()
                || target.getHealth() + target.getAbsorptionAmount() <= 0
                || target.isDead()
                // Re-check the same gate targeting used: a target that ran off or climbed out of its hole
                // is not worth spending obsidian on, so drop it now instead of once it leaves range.
                || !isValidTarget(target);
    }

    @Override
    protected @Nullable PlayerEntity getTarget() {
        return Managers.COMBAT.getTarget(range.getValue(), targetBy.getValue(), this::isValidTarget);
    }

    private boolean isValidTarget(@NotNull PlayerEntity player) {
        if (!targetMovingPlayers.getValue() && player.getVelocity().lengthSquared() >= 0.08)
            return false;

        return switch (require.getValue()) {
            case OnGround -> player.isOnGround();
            case InHole -> HoleUtility.isHole(player.getBlockPos());
            default -> true;
        };
    }

    public enum Require {
        Any,
        OnGround,
        InHole
    }
}
