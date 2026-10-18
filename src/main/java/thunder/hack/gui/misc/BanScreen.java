package thunder.hack.gui.misc;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import thunder.hack.core.manager.client.ClientUid;
import thunder.hack.gui.font.FontRenderers;
import thunder.hack.utility.render.Render2DEngine;

import java.awt.Color;

/**
 * The screen a banned UID is kept on (see {@code AppLink.tick}): it cannot be closed, and the only way out is
 * quitting the game. It goes away by itself once the GlobalDLC App lifts the ban.
 */
public class BanScreen extends Screen {
    public BanScreen() {
        super(Text.literal("Ban"));
    }

    @Override
    protected void init() {
        addDrawableChild(ButtonWidget.builder(Text.literal("Wyjdź z gry"), b -> client.scheduleStop())
                .dimensions(width / 2 - 75, height / 2 + 50, 150, 20).build());
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        context.fill(0, 0, width, height, 0xF00B0B10);
        super.render(context, mouseX, mouseY, delta);
        float pw = 320, ph = 120, px = width / 2f - pw / 2, py = height / 2f - 80;
        Render2DEngine.drawRound(context, px, py, pw, ph, 8, new Color(28, 12, 14, 240));
        Render2DEngine.drawRound(context, px, py, pw, 3, 1.5f, new Color(240, 70, 70));
        FontRenderers.sf_bold.drawCenteredString(context, "ZOSTAŁEŚ ZBANOWANY", width / 2f, py + 22, new Color(255, 95, 95).getRGB());
        String uid = ClientUid.shortId();
        FontRenderers.sf_medium.drawCenteredString(context, "UID " + (uid == null ? "?" : uid) + " ma zablokowany dostęp do GlobalDLC.",
                width / 2f, py + 48, new Color(220, 210, 212).getRGB());
        String reason = thunder.hack.core.manager.client.AppLink.banReason();
        if (!reason.isBlank())
            FontRenderers.sf_medium.drawCenteredString(context, "Powód: " + reason, width / 2f, py + 62, new Color(255, 170, 170).getRGB());
        FontRenderers.sf_medium_mini.drawCenteredString(context, "Jeśli to pomyłka, skontaktuj się z właścicielem.",
                width / 2f, py + 82, new Color(160, 150, 152).getRGB());
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }
}
