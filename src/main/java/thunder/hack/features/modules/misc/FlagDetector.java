package thunder.hack.features.modules.misc;

import meteordevelopment.orbit.EventHandler;
import net.minecraft.network.packet.s2c.play.PlayerPositionLookS2CPacket;
import thunder.hack.core.Managers;
import thunder.hack.core.manager.client.ModuleManager;
import thunder.hack.events.impl.PacketEvent;
import thunder.hack.features.modules.Module;
import thunder.hack.features.modules.combat.Kilaura;
import thunder.hack.gui.notification.Notification;

/**
 * Port of Celestial's {@code FlagDetector}: when the server sets you back (lag back), the movement module
 * that most likely caused it is turned off, one per setback, in Celestial's order. Its FastClimb and
 * WaterLeave checks are left out - this client has no such modules.
 */
public class FlagDetector extends Module {
    public FlagDetector() {
        super("FlagDetector", Category.MISC);
    }

    @EventHandler
    private void onReceivePacket(PacketEvent.Receive event) {
        if (fullNullCheck() || mc.player.age < 5) return;
        if (!(event.getPacket() instanceof PlayerPositionLookS2CPacket)) return;

        boolean inLiquid = mc.player.isTouchingWater() || mc.player.isInLava();
        if (ModuleManager.speed.isEnabled()) {
            featureAlert(ModuleManager.speed);
        } else if (ModuleManager.flight.isEnabled()) {
            featureAlert(ModuleManager.flight);
        } else if (ModuleManager.targetStrafe.isEnabled() && Kilaura.target != null) {
            featureAlert(ModuleManager.targetStrafe);
        } else if (ModuleManager.longJump.isEnabled()) {
            featureAlert(ModuleManager.longJump);
        } else if (ModuleManager.jesus.isEnabled() && inLiquid) {
            featureAlert(ModuleManager.jesus);
        } else if (ModuleManager.timer.isEnabled()) {
            featureAlert(ModuleManager.timer);
        } else if (ModuleManager.waterSpeed.isEnabled() && inLiquid) {
            featureAlert(ModuleManager.waterSpeed);
        }
    }

    private void featureAlert(Module module) {
        Managers.NOTIFICATION.publicity("Feature", "Disabling " + module.getName() + " due to lag back", 2, Notification.Type.WARNING);
        module.disable();
    }
}
