package thunder.hack.features.modules.misc;

import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.projectile.thrown.EnderPearlEntity;
import net.minecraft.util.Formatting;
import thunder.hack.core.Managers;
import thunder.hack.features.modules.Module;

/**
 * 1:1 port of Celestial's {@code PearlLogger}: when someone throws an ender pearl, the chat says who threw
 * it (the player closest to the pearl), which way it flies and where it is - once per pearl.
 */
public class PearlLogger extends Module {
    public PearlLogger() {
        super("PearlLogger", Category.MISC);
    }

    private boolean canSend;
    private PlayerEntity throwerEntity;

    @Override
    public void onEnable() {
        canSend = true;
    }

    @Override
    public void onUpdate() {
        if (fullNullCheck()) return;
        Entity enderPearl = null;
        for (Entity e : mc.world.getEntities()) {
            if (!(e instanceof EnderPearlEntity)) continue;
            enderPearl = e;
            break;
        }
        if (enderPearl == null) {
            canSend = true;
            return;
        }
        for (PlayerEntity entity : mc.world.getPlayers()) {
            if (entity == mc.player || throwerEntity != null && throwerEntity.distanceTo(enderPearl) <= entity.distanceTo(enderPearl)) continue;
            throwerEntity = entity;
        }
        String facing = enderPearl.getHorizontalFacing().toString();
        if (facing.equals("west")) facing = "east";
        else if (facing.equals("east")) facing = "west";
        if (throwerEntity == mc.player || throwerEntity == null) return;
        if (throwerEntity.getName().getString().equals(mc.player.getName().getString())) return;
        String pos = Formatting.GOLD + facing.toUpperCase() + Formatting.WHITE + " | " + Formatting.LIGHT_PURPLE
                + enderPearl.getBlockPos().getX() + " " + enderPearl.getBlockPos().getY() + " " + enderPearl.getBlockPos().getZ();
        String name = throwerEntity.getName().getString();
        String str = (Managers.FRIEND.isFriend(name) ? Formatting.GREEN : Formatting.RED) + name + Formatting.WHITE + " thrown pearl on " + pos;
        if (canSend) {
            sendMessage(str);
            canSend = false;
        }
    }
}
