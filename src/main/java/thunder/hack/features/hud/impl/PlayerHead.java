package thunder.hack.features.hud.impl;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.client.util.DefaultSkinHelper;
import net.minecraft.util.Identifier;
import thunder.hack.core.manager.client.ModuleManager;
import thunder.hack.features.hud.HudElement;
import thunder.hack.features.modules.client.HudEditor;
import thunder.hack.features.modules.client.Media;
import thunder.hack.features.modules.misc.NameProtect;
import thunder.hack.gui.font.FontRenderers;
import thunder.hack.setting.Setting;
import thunder.hack.utility.render.Render2DEngine;

import java.awt.*;

/**
 * Shows the head (skin face + hat layer) of the account you are currently playing on, by default in the
 * top-right corner of the screen. Draggable in the HUD editor like every other element.
 */
public class PlayerHead extends HudElement {
    public PlayerHead() {
        super("PlayerHead", 30, 30, 0.955f, 0.015f);
        setEnabled(true); // on out of the box; a saved config still wins over this
    }

    private final Setting<Float> size = new Setting<>("Size", 24f, 12f, 48f);
    private final Setting<Boolean> background = new Setting<>("Background", true);
    private final Setting<Boolean> name = new Setting<>("Name", true);
    private final Setting<Boolean> health = new Setting<>("Health", false);

    @Override
    public void onRender2D(DrawContext context) {
        super.onRender2D(context);
        if (mc.player == null) return;

        String username = ((ModuleManager.media.isEnabled() && Media.nickProtect.getValue()) || ModuleManager.nameProtect.isEnabled())
                ? (ModuleManager.nameProtect.isEnabled() ? NameProtect.getCustomName() : "Protected")
                : mc.getSession().getUsername();

        float head = size.getValue();
        float pad = 4f;
        float textW = name.getValue() ? FontRenderers.sf_bold.getStringWidth(username) + 6f : 0f;
        float width = head + pad * 2 + textW;
        float height = head + pad * 2;

        // right-anchored elements grow to the left so they never run off the screen edge
        float x = getPosX() > mc.getWindow().getScaledWidth() / 2f ? getPosX() - width : getPosX();
        float y = getPosY();

        if (background.getValue()) {
            if (HudEditor.hudStyle.is(HudEditor.HudStyle.Blurry))
                Render2DEngine.drawRoundedBlur(context, x, y, width, height, HudEditor.hudRound.getValue(), HudEditor.blurColor.getValue().getColorObject());
            else
                Render2DEngine.drawHudBase(context, x, y, width, height, HudEditor.hudRound.getValue());
        }

        Identifier skin = getSkin();
        if (skin != null) {
            Render2DEngine.renderTexture(context, skin, x + pad, y + pad, head, head, 8, 8, 8, 8, 64, 64);
            Render2DEngine.renderTexture(context, skin, x + pad, y + pad, head, head, 40, 8, 8, 8, 64, 64);
        }

        if (name.getValue()) {
            float textX = x + pad + head + 5f;
            if (health.getValue()) {
                int hp = (int) (mc.player.getHealth() + mc.player.getAbsorptionAmount());
                Color hpColor = hp > 15 ? new Color(120, 220, 130) : hp > 7 ? new Color(235, 200, 90) : new Color(235, 100, 100);
                FontRenderers.sf_bold.drawString(context, username, textX, y + height / 2f - 6f, HudEditor.textColor.getValue().getColor());
                FontRenderers.sf_bold_mini.drawString(context, hp + " HP", textX, y + height / 2f + 3f, hpColor.getRGB());
            } else {
                FontRenderers.sf_bold.drawString(context, username, textX, y + height / 2f - 2.5f, HudEditor.textColor.getValue().getColor());
            }
        }

        setBounds(x, y, width, height);
    }

    /**
     * The local player's skin: taken from the tab-list entry when the server has sent it, otherwise the
     * default skin derived from our UUID (singleplayer / before the player list arrives).
     */
    private Identifier getSkin() {
        try {
            if (mc.getNetworkHandler() != null) {
                PlayerListEntry entry = mc.getNetworkHandler().getPlayerListEntry(mc.player.getUuid());
                if (entry != null)
                    return entry.getSkinTextures().body().texturePath();
            }
            return DefaultSkinHelper.getSkinTextures(mc.player.getUuid()).body().texturePath();
        } catch (Exception ignored) {
            return null;
        }
    }
}
