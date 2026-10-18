package thunder.hack.gui.misc;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import thunder.hack.core.manager.client.AppLink;
import thunder.hack.features.modules.client.HudEditor;
import thunder.hack.gui.font.FontRenderers;
import thunder.hack.utility.render.Render2DEngine;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;

/**
 * Shown once on the title screen when the GlobalDLC App has published a newer release: the release number and
 * what was added (written in the app). The new jar is already downloaded and goes in when the game closes.
 */
public class UpdateScreen extends Screen {
    private final Screen parent;

    public UpdateScreen(Screen parent) {
        super(Text.literal("Aktualizacja"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int w = 150, y = height / 2 + 90;
        addDrawableChild(ButtonWidget.builder(Text.literal("Zamknij i zainstaluj"), b -> client.scheduleStop())
                .dimensions(width / 2 - w - 4, y, w, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Później"), b -> client.setScreen(parent))
                .dimensions(width / 2 + 4, y, w, 20).build());
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);
        float pw = 340, ph = 210;
        float px = width / 2f - pw / 2, py = height / 2f - ph / 2 - 15;
        Color accent = HudEditor.getColor(0);
        Render2DEngine.drawBlurredShadow(context, px, py, pw, ph, 14, new Color(0, 0, 0, 160));
        Render2DEngine.drawRound(context, px, py, pw, ph, 8, new Color(16, 17, 24, 235));
        Render2DEngine.drawRound(context, px, py, pw, 3, 1.5f, accent);

        FontRenderers.sf_bold.drawCenteredString(context, "AKTUALIZACJA", width / 2f, py + 12, accent.getRGB());
        FontRenderers.sf_medium.drawCenteredString(context, "GlobalDLC #" + AppLink.readyRelease()
                + "  -  zainstaluje się po zamknięciu gry", width / 2f, py + 28, new Color(170, 175, 190).getRGB());
        FontRenderers.sf_medium.drawString(context, "Co dodano:", px + 14, py + 48, -1);

        float y = py + 62;
        for (String line : wrap(AppLink.changelog().isBlank() ? "(brak opisu)" : AppLink.changelog(), pw - 34)) {
            if (y > py + ph - 14) break;
            FontRenderers.sf_medium_mini.drawString(context, line, px + 20, y, new Color(220, 224, 235).getRGB());
            y += 10;
        }
    }

    private static List<String> wrap(String text, float max) {
        List<String> out = new ArrayList<>();
        for (String para : text.split("\n")) {
            String prefix = para.isBlank() ? "" : (para.startsWith("-") || para.startsWith("•") ? "" : "• ");
            StringBuilder line = new StringBuilder(prefix);
            for (String word : para.split(" ")) {
                String next = line.length() == 0 ? word : line + " " + word;
                if (FontRenderers.sf_medium_mini.getStringWidth(next) > max && line.length() > 0) {
                    out.add(line.toString());
                    line = new StringBuilder("  " + word);
                } else {
                    line = new StringBuilder(next);
                }
            }
            out.add(line.toString());
        }
        return out;
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return true;
    }

    @Override
    public void close() {
        client.setScreen(parent);
    }
}
