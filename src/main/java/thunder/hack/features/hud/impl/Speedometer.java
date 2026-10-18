package thunder.hack.features.hud.impl;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.util.Formatting;
import thunder.hack.features.hud.HudElement;
import thunder.hack.setting.Setting;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Celestial Premium 6.0 {@code InfoComponent}'s Speed line: the tick's horizontal move x 15.5, as "x.xx b/s". */
public class Speedometer extends HudElement {
    public Speedometer() {
        super("Speedometer", 3, 354);
    }

    private final Setting<Boolean> minecraftFont = new Setting<>("Minecraft Font", false);

    public void onRender2D(DrawContext context) {
        super.onRender2D(context);
        if (fullNullCheck()) return;
        double prevPosX = mc.player.getX() - mc.player.lastX;
        double prevPosZ = mc.player.getZ() - mc.player.lastZ;
        float distance = (float) Math.sqrt(prevPosX * prevPosX + prevPosZ * prevPosZ);
        BigDecimal speedValue = new BigDecimal(distance * 15.5f).setScale(1, RoundingMode.HALF_UP);
        String speed = "Speed: " + Formatting.WHITE + String.format("%.2f b/s", speedValue);
        CelestialText.drawStringWithShadow(context, speed, getPosX(), getPosY(), thunder.hack.features.modules.client.HudEditor.getColor(0).getRGB(), minecraftFont.getValue());
        setBounds(getPosX(), getPosY(), CelestialText.getStringWidth(speed, minecraftFont.getValue()), 9);
    }
}
