package thunder.hack.features.modules.client;

import thunder.hack.gui.windows.WindowsScreen;
import thunder.hack.gui.windows.impl.*;
import thunder.hack.features.modules.Module;
import thunder.hack.setting.Setting;
import thunder.hack.setting.impl.PositionSetting;

public class Windows extends Module {
    public Windows() {
        super("Windows", Category.CLIENT);
    }

    // todo size
    public final Setting<PositionSetting> macroPos = new Setting<>("macroPos", new PositionSetting(0.3f, 0.3f), v -> false);
    public final Setting<PositionSetting> configPos = new Setting<>("configPos", new PositionSetting(0.35f, 0.35f), v -> false);
    public final Setting<PositionSetting> friendPos = new Setting<>("friendPos", new PositionSetting(0.4f, 0.4f), v -> false);
    public final Setting<PositionSetting> waypointPos = new Setting<>("waypointPos", new PositionSetting(0.45f, 0.45f), v -> false);
    public final Setting<PositionSetting> proxyPos = new Setting<>("proxyPos", new PositionSetting(0.5f, 0.5f), v -> false);

    @Override
    public void onEnable() {
        open("configs");
        disable();
    }

    /**
     * Opens the window screen with Config and Friends only (the macro / waypoint / proxy windows
     * were stacked a few pixels apart on top of them and buried their contents) and puts the
     * requested one on top.
     */
    public void open(String focus) {
        ConfigWindow config = ConfigWindow.get(configPos.getValue().getX() * mc.getWindow().getScaledWidth(), configPos.getValue().getY() * mc.getWindow().getScaledHeight(), configPos);
        FriendsWindow friends = FriendsWindow.get(friendPos.getValue().getX() * mc.getWindow().getScaledWidth(), friendPos.getValue().getY() * mc.getWindow().getScaledHeight(), friendPos);

        config.setVisible(true);
        friends.setVisible(true);

        mc.setScreen(new WindowsScreen(config, friends));
        WindowsScreen.arrange(config, friends);
        // constructor clears the focus, so pick the window afterwards
        WindowsScreen.lastClickedWindow = "friends".equals(focus) ? friends : config;
    }
}
