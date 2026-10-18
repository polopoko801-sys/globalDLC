package thunder.hack.features.modules.client;

import thunder.hack.features.modules.Module;
import thunder.hack.gui.misc.GlobalPanelScreen;

/**
 * Opens the owner's GlobalPanel (UIDs with Discord avatars, bans with reasons, publishing updates). It talks
 * to the GlobalDLC App on this computer, so it only works for whoever runs the app.
 */
public class GlobalPanel extends Module {
    public GlobalPanel() {
        super("GlobalPanel", Category.CLIENT);
    }

    @Override
    public void onEnable() {
        mc.execute(() -> mc.setScreen(new GlobalPanelScreen()));
        disable();
    }
}
