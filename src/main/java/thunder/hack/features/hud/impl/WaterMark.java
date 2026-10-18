package thunder.hack.features.hud.impl;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.util.Formatting;
import thunder.hack.ThunderHack;
import thunder.hack.core.Managers;
import thunder.hack.core.manager.client.ModuleManager;
import thunder.hack.gui.font.FontRenderers;
import thunder.hack.features.hud.HudElement;
import thunder.hack.features.modules.client.HudEditor;
import thunder.hack.features.modules.client.Media;
import thunder.hack.features.modules.misc.NameProtect;
import thunder.hack.setting.Setting;
import thunder.hack.utility.render.Render2DEngine;
import thunder.hack.utility.render.TextureStorage;

import java.awt.*;
import java.text.SimpleDateFormat;
import java.util.Date;

public class WaterMark extends HudElement {
    public WaterMark() {
        super("WaterMark", 100, 35);
    }

    public static final Setting<Mode> mode = new Setting<>("Mode", Mode.GlassBlur);

    // what the GlassBlur bar shows — each part toggleable
    private final Setting<Boolean> showLogo = new Setting<>("Logo", true, v -> mode.getValue() == Mode.GlassBlur);
    private final Setting<Boolean> showName = new Setting<>("CheatName", true, v -> mode.getValue() == Mode.GlassBlur);
    private final Setting<Boolean> showNick = new Setting<>("Nick", true, v -> mode.getValue() == Mode.GlassBlur);
    private final Setting<Boolean> showUid = new Setting<>("UID", true, v -> mode.getValue() == Mode.GlassBlur);
    private final Setting<Boolean> showTime = new Setting<>("Time", true, v -> mode.getValue() == Mode.GlassBlur);
    private final Setting<Boolean> showUpdate = new Setting<>("UpdateNotice", true, v -> mode.getValue() == Mode.GlassBlur);

    /** Licence id from the card in the mods folder when there is one, else a stable per-account hash. */
    private static String uidOf(String name) {
        String licence = thunder.hack.core.manager.client.ClientUid.shortId();
        return licence != null ? licence : String.valueOf(Math.abs(name.hashCode()) % 100000);
    }

    private enum Mode {GlassBlur, Classic}


    public void onRender2D(DrawContext context) {
        super.onRender2D(context);
        String username = ((ModuleManager.media.isEnabled() && Media.nickProtect.getValue()) || ModuleManager.nameProtect.isEnabled())
                ? (ModuleManager.nameProtect.isEnabled() ? NameProtect.getCustomName() : "Protected")
                : mc.getSession().getUsername();

        if (mode.getValue() == Mode.GlassBlur) {
            java.util.List<String> parts = new java.util.ArrayList<>();
            if (showName.getValue()) parts.add("GlobalDLC");
            if (showNick.getValue()) parts.add(username);
            if (showUid.getValue()) parts.add("UID: " + uidOf(username));
            if (showTime.getValue()) parts.add(new SimpleDateFormat("h:mm a").format(new Date()));
            if (showUpdate.getValue() && ThunderHack.isOutdated) parts.add("UPDATE");
            if (parts.isEmpty()) parts.add("GlobalDLC");

            var font = FontRenderers.sf_bold;
            float sepW = 7f;
            float textW = 0f;
            for (int i = 0; i < parts.size(); i++) {
                textW += font.getStringWidth(parts.get(i));
                if (i < parts.size() - 1) textW += sepW;
            }
            float padX = 8f, boxH = 16f;
            float logoW = showLogo.getValue() ? 16f : 0f;
            float boxW = textW + padX * 2f + logoW;

            Render2DEngine.drawRoundedBlur(context, getPosX(), getPosY(), boxW, boxH, 4, HudEditor.blurColor.getValue().getColorObject());

            if (showLogo.getValue())
                thunder.hack.utility.render.GombalLogo.draw(context, getPosX() + 3.5f, getPosY() + 1.5f, 13f, 1f);

            float tx = getPosX() + padX + logoW;
            float ty = getPosY() + 4.5f;
            for (int i = 0; i < parts.size(); i++) {
                font.drawString(context, parts.get(i), tx, ty, HudEditor.textColor.getValue().getColor());
                tx += font.getStringWidth(parts.get(i)) + (i < parts.size() - 1 ? sepW : 0f);
            }
            setBounds(getPosX(), getPosY(), boxW, boxH);
        } else {
            // Classic: plain bar, gradient brand + server info
            String server = mc.world == null ? ""
                    : (mc.isInSingleplayer() ? "SinglePlayer"
                    : (mc.getNetworkHandler() != null && mc.getNetworkHandler().getServerInfo() != null
                    ? mc.getNetworkHandler().getServerInfo().address : "Server"));
            String info = Formatting.DARK_GRAY + "| " + Formatting.RESET + username
                    + Formatting.DARK_GRAY + " | " + Formatting.RESET + Managers.SERVER.getPing() + " ms"
                    + (server.isEmpty() ? "" : Formatting.DARK_GRAY + " | " + Formatting.RESET + server);
            float width = FontRenderers.sf_bold.getStringWidth("GlobalDLC " + info) + 5;
            Render2DEngine.drawHudBase(context, getPosX(), getPosY(), width, 10, 3);
            FontRenderers.sf_bold.drawGradientString(context, "GlobalDLC ", getPosX() + 2, getPosY() + 2.5f, 10);
            FontRenderers.sf_bold.drawString(context, info, getPosX() + 2 + FontRenderers.sf_bold.getStringWidth("GlobalDLC "), getPosY() + 2.5f, HudEditor.textColor.getValue().getColor());
            setBounds(getPosX(), getPosY(), width, 10);
        }
    }
}
