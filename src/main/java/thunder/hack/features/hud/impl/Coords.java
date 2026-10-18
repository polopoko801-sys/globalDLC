package thunder.hack.features.hud.impl;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.util.Formatting;
import thunder.hack.features.hud.HudElement;
import thunder.hack.setting.Setting;

/** Celestial Premium 6.0 {@code InfoComponent}'s XYZ line: "XYZ: " in the client colour, the rounded position white. */
public class Coords extends HudElement {
    public Coords() {
        super("Coords", 3, 363);
    }

    private final Setting<Boolean> minecraftFont = new Setting<>("Minecraft Font", false);

    public void onRender2D(DrawContext context) {
        super.onRender2D(context);
        if (fullNullCheck()) return;
        String s = "XYZ: " + Formatting.WHITE + Math.round(mc.player.getX()) + " " + Math.round(mc.player.getY()) + " " + Math.round(mc.player.getZ());
        CelestialText.drawStringWithShadow(context, s, getPosX(), getPosY(), thunder.hack.features.modules.client.HudEditor.getColor(0).getRGB(), minecraftFont.getValue());
        setBounds(getPosX(), getPosY(), CelestialText.getStringWidth(s, minecraftFont.getValue()), 9);
    }
}
