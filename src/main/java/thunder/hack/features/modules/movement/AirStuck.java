package thunder.hack.features.modules.movement;

import meteordevelopment.orbit.EventHandler;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.util.math.Vec3d;
import thunder.hack.events.impl.PacketEvent;
import thunder.hack.events.impl.PlayerUpdateEvent;
import thunder.hack.features.modules.Module;

/**
 * Freezes the player in place mid-air: cancels outgoing movement packets so the server keeps your
 * last position, and locks the client position + zeroes velocity each tick. Disable to drop.
 */
public final class AirStuck extends Module {
    private Vec3d pos;

    public AirStuck() {
        super("AirStuck", Category.MOVEMENT);
    }

    @Override
    public void onEnable() {
        pos = fullNullCheck() ? null : mc.player.getEntityPos();
    }

    @Override
    public void onDisable() {
        pos = null;
    }

    @EventHandler
    public void onSend(PacketEvent.Send e) {
        if (e.getPacket() instanceof PlayerMoveC2SPacket) e.cancel();
    }

    @EventHandler
    public void onUpdate(PlayerUpdateEvent e) {
        if (fullNullCheck()) return;
        if (pos == null) pos = mc.player.getEntityPos();
        mc.player.setVelocity(0, 0, 0);
        mc.player.setPosition(pos.x, pos.y, pos.z);
    }
}
