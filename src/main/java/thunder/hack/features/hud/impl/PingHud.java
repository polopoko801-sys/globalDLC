package thunder.hack.features.hud.impl;

import net.minecraft.client.gui.DrawContext;
import thunder.hack.core.Managers;
import thunder.hack.features.hud.HudElement;
import thunder.hack.setting.Setting;

/** 1:1 Celestial Premium 6.0 {@code PingComponent}: "Ping: §7Xms" (0 in singleplayer). */
public class PingHud extends HudElement {
    public PingHud() {
        super("Ping", 100, 300);
    }

    private final Setting<Boolean> minecraftFont = new Setting<>("Minecraft Font", false);

    public void onRender2D(DrawContext context) {
        super.onRender2D(context);
        if (fullNullCheck()) return;
        int ping = mc.isInSingleplayer() ? 0 : Managers.SERVER.getPing();
        String s = "Ping: §7" + ping + "ms";
        CelestialText.drawStringWithShadow(context, s, getPosX(), getPosY(), -1, minecraftFont.getValue());
        setBounds(getPosX(), getPosY(), 55, 12);
    }
}
