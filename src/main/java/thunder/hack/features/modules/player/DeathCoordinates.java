package thunder.hack.features.modules.player;

import net.minecraft.client.gui.screen.DeathScreen;
import thunder.hack.core.Managers;
import thunder.hack.features.modules.Module;
import thunder.hack.gui.notification.Notification;

/**
 * 1:1 port of Rockstar's {@code ru.rockstar.client.features.impl.player.DeathCoordinates}: while the death screen is
 * up, your death coordinates go to a notification and the chat once a second (every 20 ticks).
 */
public class DeathCoordinates extends Module {
    public DeathCoordinates() {
        super("DeathCoordinates", Category.PLAYER);
    }

    @Override
    public void onUpdate() {
        if (fullNullCheck()) return;
        if (mc.player.getHealth() < 1 && mc.currentScreen instanceof DeathScreen) {
            int x = mc.player.getBlockPos().getX();
            int y = mc.player.getBlockPos().getY();
            int z = mc.player.getBlockPos().getZ();
            if (mc.player.age % 20 == 0) {
                Managers.NOTIFICATION.publicity("Death Coordinates", "X: " + x + " Y: " + y + " Z: " + z, 2, Notification.Type.INFO);
                sendMessage("Death Coordinates: " + "X: " + x + " Y: " + y + " Z: " + z);
            }
        }
    }
}
