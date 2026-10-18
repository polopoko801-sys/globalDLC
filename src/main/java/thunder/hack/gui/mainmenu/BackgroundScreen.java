package thunder.hack.gui.mainmenu;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;
import net.minecraft.util.Util;
import org.jetbrains.annotations.NotNull;
import thunder.hack.gui.font.FontRenderers;
import thunder.hack.utility.render.Render2DEngine;

import java.awt.Color;
import java.util.List;

import static thunder.hack.features.modules.Module.mc;

/** Wallpaper picker for the GOMBAL main menu: a grid of previews of misc/backgrounds/ plus the vanilla panorama. */
public class BackgroundScreen extends Screen {
    private static final Color ACCENT = new Color(168, 85, 247);
    private static final Color TEXT   = new Color(238, 238, 244);
    private static final Color DIM    = new Color(160, 160, 176);
    private static final Color CARD     = new Color(20, 18, 28, 190);
    private static final Color CARD_HOV = new Color(36, 30, 50, 225);

    private static final int COLUMNS = 3;
    private static final float CARD_W = 150f, CARD_H = 96f, GAP = 12f, GRID_TOP = 64f;

    private final Screen parent;
    private float[] rBack = {-1, -1, 0, 0}, rFolder = {-1, -1, 0, 0};

    public BackgroundScreen(Screen parent) {
        super(Text.of("Wallpaper"));
        this.parent = parent;
    }

    @Override
    public void render(@NotNull DrawContext context, int mouseX, int mouseY, float delta) {
        float w = mc.getWindow().getScaledWidth(), h = mc.getWindow().getScaledHeight();

        MenuBackground.refresh();
        if (!MenuBackground.render(context, w, h)) {
            renderPanoramaBackground(context, delta);
            renderDarkening(context);
        }
        Render2DEngine.drawRound(context, 0, 0, w, h, 0f, new Color(0, 0, 0, 110));

        FontRenderers.monsterrat.drawCenteredString(context, "WALLPAPER", w / 2f, 24, TEXT.getRGB());
        FontRenderers.sf_medium_mini.drawCenteredString(context, "Drop images into " + thunder.hack.core.manager.client.ConfigManager.CONFIG_FOLDER_NAME + "/misc/backgrounds", w / 2f, 40, DIM.getRGB());

        List<String> files = MenuBackground.getFiles();
        String selected = MenuBackground.getSelected();
        float gridW = COLUMNS * CARD_W + (COLUMNS - 1) * GAP;
        float x0 = (w - gridW) / 2f;

        // slot 0 is the panorama, the files follow
        for (int i = 0; i <= files.size(); i++) {
            float cx = x0 + (i % COLUMNS) * (CARD_W + GAP);
            float cy = GRID_TOP + (float) (i / COLUMNS) * (CARD_H + GAP + 14f);
            String file = i == 0 ? null : files.get(i - 1);
            boolean active = i == 0 ? selected == null : file.equals(selected);
            drawCard(context, cx, cy, file, active, mouseX, mouseY);
        }

        rFolder = pill(context, w / 2f - 150f, h - 40f, 140f, 24f, "Open folder", mouseX, mouseY);
        rBack = pill(context, w / 2f + 10f, h - 40f, 140f, 24f, "Back", mouseX, mouseY);
    }

    private void drawCard(DrawContext ctx, float x, float y, String file, boolean active, float mx, float my) {
        boolean hov = Render2DEngine.isHovered(mx, my, x, y, CARD_W, CARD_H);
        if (active || hov)
            Render2DEngine.drawBlurredShadow(ctx, x, y, CARD_W, CARD_H, 12, new Color(168, 85, 247, active ? 90 : 45));
        Render2DEngine.drawRound(ctx, x - 2, y - 2, CARD_W + 4, CARD_H + 4, 10f, active ? ACCENT : (hov ? CARD_HOV : CARD));

        if (file == null || !MenuBackground.drawCover(ctx, file, x, y, CARD_W, CARD_H)) {
            Render2DEngine.drawRound(ctx, x, y, CARD_W, CARD_H, 8f, new Color(26, 22, 36, 235));
            FontRenderers.sf_medium.drawCenteredString(ctx, "Vanilla panorama", x + CARD_W / 2f, y + CARD_H / 2f - 4f, DIM.getRGB());
        }

        String label = file == null ? "Default" : trim(file, CARD_W);
        FontRenderers.sf_medium_mini.drawCenteredString(ctx, label, x + CARD_W / 2f, y + CARD_H + 4f,
                (active ? ACCENT : (hov ? TEXT : DIM)).getRGB());
    }

    private String trim(String text, float maxWidth) {
        if (FontRenderers.sf_medium_mini.getStringWidth(text) <= maxWidth) return text;
        StringBuilder sb = new StringBuilder();
        for (char c : text.toCharArray()) {
            if (FontRenderers.sf_medium_mini.getStringWidth(sb.toString() + c + "...") > maxWidth) break;
            sb.append(c);
        }
        return sb + "...";
    }

    private float[] pill(DrawContext ctx, float x, float y, float wdt, float hgt, String label, float mx, float my) {
        boolean hov = Render2DEngine.isHovered(mx, my, x, y, wdt, hgt);
        Render2DEngine.drawRound(ctx, x, y, wdt, hgt, 7f, hov ? CARD_HOV : CARD);
        FontRenderers.sf_medium.drawCenteredString(ctx, label, x + wdt / 2f, y + hgt / 2f - 4f, (hov ? TEXT : DIM).getRGB());
        return new float[]{x, y, wdt, hgt};
    }

    @Override
    public boolean mouseClicked(net.minecraft.client.gui.Click click, boolean doubled) {
        float mx = (float) click.x(), my = (float) click.y();
        float w = mc.getWindow().getScaledWidth();

        List<String> files = MenuBackground.getFiles();
        float gridW = COLUMNS * CARD_W + (COLUMNS - 1) * GAP;
        float x0 = (w - gridW) / 2f;

        for (int i = 0; i <= files.size(); i++) {
            float cx = x0 + (i % COLUMNS) * (CARD_W + GAP);
            float cy = GRID_TOP + (float) (i / COLUMNS) * (CARD_H + GAP + 14f);
            if (Render2DEngine.isHovered(mx, my, cx, cy, CARD_W, CARD_H)) {
                MenuBackground.select(i == 0 ? null : files.get(i - 1));
                return true;
            }
        }

        if (hovered(mx, my, rFolder)) Util.getOperatingSystem().open(MenuBackground.FOLDER);
        if (hovered(mx, my, rBack)) close();
        return false;
    }

    private static boolean hovered(float mx, float my, float[] r) {
        return Render2DEngine.isHovered(mx, my, r[0], r[1], r[2], r[3]);
    }

    @Override
    public void close() {
        mc.setScreen(parent);
    }
}
