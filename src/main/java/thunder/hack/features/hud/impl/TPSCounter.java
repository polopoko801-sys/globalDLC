package thunder.hack.features.hud.impl;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.util.Formatting;
import thunder.hack.ThunderHack;
import thunder.hack.core.Managers;
import thunder.hack.core.manager.client.ClientUid;
import thunder.hack.features.hud.HudElement;
import thunder.hack.gui.font.FontRenderer;
import thunder.hack.gui.font.FontRenderers;
import thunder.hack.setting.Setting;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 1:1 port of Celestial Premium 6.0's {@code ClientInfoComponent} (HUD "Client Info"): "Build - x | User - y" with
 * "TPS - z" right-aligned on the line above it. Celestial.version -> this client's version, getLicenseName() ->
 * the UID from the card (or the account name without one); TpsHelper -> ServerManager, the same averaging.
 */
public class TPSCounter extends HudElement {
    public TPSCounter() {
        super("TPS", 801, 366);
    }

    private final Setting<Boolean> minecraftFont = new Setting<>("Minecraft Font", false);

    public void onRender2D(DrawContext context) {
        super.onRender2D(context);
        if (fullNullCheck()) return;

        String uid = ClientUid.shortId();
        String buildStr = Formatting.GRAY + "Build - " + Formatting.WHITE + ThunderHack.VERSION + Formatting.GRAY + " | User - " + Formatting.WHITE + (uid != null ? uid : mc.getSession().getUsername());
        String tpsStr = Formatting.GRAY + "TPS - " + Formatting.WHITE + new BigDecimal(Managers.SERVER.getTPS()).setScale(1, RoundingMode.HALF_UP);
        float x = getPosX();
        float y = getPosY();

        drawStringWithShadow(context, buildStr, x, y);
        drawStringWithShadow(context, tpsStr, x + getStringWidth(buildStr) - 1 - getStringWidth(tpsStr), y - 10);

        setBounds(x, y, Math.max(120, getStringWidth(buildStr)), 15);
    }

    private float getStringWidth(String s) {
        return minecraftFont.getValue() ? mc.textRenderer.getWidth(s) : FontRenderers.getModulesRenderer().getStringWidth(s);
    }

    private void drawStringWithShadow(DrawContext context, String s, float x, float y) {
        if (minecraftFont.getValue()) {
            context.drawTextWithShadow(mc.textRenderer, s, (int) x, (int) y, -1);
            return;
        }
        FontRenderer font = FontRenderers.getModulesRenderer();
        font.drawString(context, Formatting.strip(s), x + 0.5f, y + 0.5f, 0xFF3F3F3F);
        font.drawString(context, s, x, y, -1);
    }
}
