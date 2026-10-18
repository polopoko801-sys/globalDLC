package thunder.hack.features.hud.impl;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.util.Formatting;
import thunder.hack.features.hud.HudElement;
import thunder.hack.setting.Setting;
import thunder.hack.utility.math.FrameRateCounter;

/**
 * Celestial Premium 6.0 {@code InfoComponent}'s FPS line: "FPS: " in the client colour, the value white.
 * (Its Speed and XYZ lines are the Speedometer and Coords elements.)
 */
public class FpsCounter extends HudElement {
    public FpsCounter() {
        super("Fps", 3, 345);
    }

    private final Setting<Boolean> minecraftFont = new Setting<>("Minecraft Font", false);

    public void onRender2D(DrawContext context) {
        super.onRender2D(context);
        if (fullNullCheck()) return;
        String s = "FPS: " + Formatting.WHITE + FrameRateCounter.INSTANCE.getFps();
        CelestialText.drawStringWithShadow(context, s, getPosX(), getPosY(), thunder.hack.features.modules.client.HudEditor.getColor(0).getRGB(), minecraftFont.getValue());
        setBounds(getPosX(), getPosY(), CelestialText.getStringWidth(s, minecraftFont.getValue()), 9);
    }
}
