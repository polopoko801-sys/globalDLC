package thunder.hack.features.hud.impl;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.util.Formatting;
import thunder.hack.features.hud.HudElement;
import thunder.hack.setting.Setting;

/**
 * Heap in use against the heap the JVM may take. Neither Rockstar Free 2.0 nor Celestial Premium 6.0 has a memory
 * HUD, so this is drawn like Celestial's InfoComponent lines ("Memory: " client colour, value white).
 */
public class MemoryHud extends HudElement {
    public MemoryHud() {
        super("MemoryHud", 3, 372);
    }

    private final Setting<Boolean> minecraftFont = new Setting<>("Minecraft Font", false);

    public void onRender2D(DrawContext context) {
        super.onRender2D(context);
        long max = Runtime.getRuntime().maxMemory();
        long used = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
        String s = "Memory: " + Formatting.WHITE + (used * 100L / max) + "% " + toMiB(used) + "/" + toMiB(max) + "MB";
        CelestialText.drawStringWithShadow(context, s, getPosX(), getPosY(), thunder.hack.features.modules.client.HudEditor.getColor(0).getRGB(), minecraftFont.getValue());
        setBounds(getPosX(), getPosY(), CelestialText.getStringWidth(s, minecraftFont.getValue()), 9);
    }

    private static long toMiB(long bytes) {
        return bytes / 1024L / 1024L;
    }
}
