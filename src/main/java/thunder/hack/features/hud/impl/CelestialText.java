package thunder.hack.features.hud.impl;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.util.Formatting;
import thunder.hack.gui.font.FontRenderer;
import thunder.hack.gui.font.FontRenderers;

/**
 * Celestial's text HUD drawing: {@code ClientHelper.getFontRender().drawStringWithShadow} - its default ClientFont is
 * RobotoRegular, whose closest font here is sf_medium - or {@code mc.fontRendererObj} when "Minecraft Font" is on.
 */
final class CelestialText {
    private CelestialText() {
    }

    /** ClientHelper.getFontRender(). */
    static FontRenderer font() {
        return FontRenderers.sf_medium;
    }

    static void drawStringWithShadow(DrawContext context, String s, float x, float y, int color, boolean minecraftFont) {
        if (minecraftFont) {
            context.drawTextWithShadow(MinecraftClient.getInstance().textRenderer, s, (int) x, (int) y, color);
            return;
        }
        drawStringWithShadow(context, font(), s, x, y, color);
    }

    static void drawStringWithShadow(DrawContext context, FontRenderer font, String s, float x, float y, int color) {
        font.drawString(context, Formatting.strip(s), x + 0.5f, y + 0.5f, 0xFF3F3F3F);
        font.drawString(context, s, x, y, color);
    }

    static float getStringWidth(String s, boolean minecraftFont) {
        return minecraftFont ? MinecraftClient.getInstance().textRenderer.getWidth(s) : font().getStringWidth(s);
    }
}
