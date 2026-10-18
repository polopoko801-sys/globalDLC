package thunder.hack.gui.mainmenu;

import net.minecraft.client.gui.DrawContext;
import org.jetbrains.annotations.NotNull;
import thunder.hack.gui.font.FontRenderers;
import thunder.hack.utility.render.Render2DEngine;

import java.awt.Color;

import static thunder.hack.features.modules.Module.mc;

/** A wide list row for the custom main menu (icon + label + accent bar + chevron/badge, animated hover). */
public class MainMenuButton {
    public enum Icon { SWORD, PEOPLE, GLOBE, PUZZLE, GEAR, PERSON, SLIDERS, CROSS, IMAGE }

    static final Color ROW      = new Color(20, 18, 28, 175);
    static final Color ROW_HOV  = new Color(34, 28, 48, 215);
    static final Color ACCENT   = new Color(168, 85, 247);
    static final Color TEXT     = new Color(238, 238, 244);
    static final Color DIM      = new Color(150, 150, 166);

    public final float posX, posY, width, height;
    private final String label;
    private final Icon icon;
    private final Runnable action;
    private final boolean selected;
    private String badge;
    private boolean badgeAccent;
    private float hover;

    public MainMenuButton(float posX, float posY, float width, float height, @NotNull String label, Icon icon, boolean selected, Runnable action) {
        this.posX = posX;
        this.posY = posY;
        this.width = width;
        this.height = height;
        this.label = label;
        this.icon = icon;
        this.selected = selected;
        this.action = action;
    }

    public MainMenuButton badge(String text, boolean accent) {
        this.badge = text;
        this.badgeAccent = accent;
        return this;
    }

    private float x() { return mc.getWindow().getScaledWidth() / 2f + posX; }
    private float y() { return mc.getWindow().getScaledHeight() / 2f + posY; }

    public void onRender(DrawContext context, float mouseX, float mouseY) {
        float x = x(), y = y();
        boolean hov = Render2DEngine.isHovered(mouseX, mouseY, x, y, width, height);
        hover += ((hov ? 1f : 0f) - hover) * 0.2f;
        float act = Math.max(hover, selected ? 0.55f : 0f);

        if (act > 0.03f)
            Render2DEngine.drawBlurredShadow(context, x, y, width, height, 12, new Color(168, 85, 247, (int) (70 * act)));
        Render2DEngine.drawRound(context, x, y, width, height, 10f, mix(ROW, ROW_HOV, act));

        // bright left accent bar (prominent when selected)
        float barA = selected ? 255 : (40 + 200 * hover);
        Render2DEngine.drawRound(context, x, y + height * 0.16f, 3.5f, height * 0.68f, 2f, Render2DEngine.injectAlpha(ACCENT, (int) barA));

        drawIcon(context, icon, x + 24f, y + height / 2f, 13f, hov || selected ? ACCENT : DIM);
        FontRenderers.monsterrat.drawString(context, label, x + 42f, y + height / 2f - 4f, TEXT.getRGB());

        float rightX = x + width - 16f;
        if (badge != null) {
            float bw = FontRenderers.sf_medium_mini.getStringWidth(badge) + 14f;
            float bx = rightX - 14f - bw, by = y + height / 2f - 8f;
            if (badgeAccent) Render2DEngine.drawRound(context, bx, by, bw, 16f, 8f, ACCENT);
            else Render2DEngine.drawRound(context, bx, by, bw, 16f, 8f, new Color(255, 255, 255, 22));
            FontRenderers.sf_medium_mini.drawCenteredString(context, badge, bx + bw / 2f, by + 4.5f,
                    (badgeAccent ? Color.WHITE : DIM).getRGB());
        }
        // chevron
        FontRenderers.sf_medium.drawString(context, ">", rightX + 4f * hover, y + height / 2f - 5f,
                Render2DEngine.injectAlpha(ACCENT, (int) (70 + 185 * Math.max(hover, selected ? 0.4f : 0f))).getRGB());
    }

    public void onClick(float mouseX, float mouseY) {
        if (Render2DEngine.isHovered(mouseX, mouseY, x(), y(), width, height)) action.run();
    }

    // ---- primitive icons (line/shape based) ----
    public static void drawIcon(DrawContext ctx, Icon icon, float cx, float cy, float s, Color c) {
        int col = c.getRGB();
        float h = s / 2f;
        switch (icon) {
            case SWORD -> {
                line(ctx, cx - h + 1, cy + h, cx + h - 2, cy - h + 1, col);
                line(ctx, cx + h - 4, cy - h + 1, cx + h, cy - h + 1, col); // tip guard
                line(ctx, cx - h - 1, cy + h - 3, cx - h + 3, cy + h + 1, col); // hilt guard
            }
            case PEOPLE -> {
                Render2DEngine.drawRound(ctx, cx - h, cy - h, 5, 5, 2.5f, c);
                Render2DEngine.drawRound(ctx, cx - h - 1, cy, 7, 5, 2f, c);
                Render2DEngine.drawRound(ctx, cx + 1, cy - h + 1, 4.5f, 4.5f, 2.2f, c);
                Render2DEngine.drawRound(ctx, cx, cy + 1, 6, 4.5f, 2f, c);
            }
            case GLOBE -> {
                Render2DEngine.drawArc(ctx, cx - h, cy - h, s, s, h, 1.4f, 0, 360, c, c);
                line(ctx, cx, cy - h, cx, cy + h, col);
                line(ctx, cx - h, cy, cx + h, cy, col);
            }
            case PUZZLE -> {
                Render2DEngine.drawRound(ctx, cx - h, cy - h + 1.5f, s, s - 1.5f, 2.5f, c);
                Render2DEngine.drawRound(ctx, cx - 2.5f, cy - h - 1.5f, 5, 5, 2.5f, c);
            }
            case GEAR -> {
                Render2DEngine.drawArc(ctx, cx - h, cy - h, s, s, h, 1.6f, 0, 360, c, c);
                Render2DEngine.drawRound(ctx, cx - 2, cy - 2, 4, 4, 2f, c);
            }
            case PERSON -> {
                Render2DEngine.drawRound(ctx, cx - 3.5f, cy - h, 7, 7, 3.5f, c);
                Render2DEngine.drawRound(ctx, cx - 5, cy + 1.5f, 10, 6, 3f, c);
            }
            case SLIDERS -> {
                for (int i = 0; i < 3; i++) {
                    float ly = cy - h + 2 + i * 4f;
                    line(ctx, cx - h, ly, cx + h, ly, col);
                    Render2DEngine.drawRound(ctx, cx - h + (i == 1 ? 6 : 2), ly - 1.5f, 3, 3, 1.5f, c);
                }
            }
            case CROSS -> {
                line(ctx, cx - h + 1, cy - h + 1, cx + h - 1, cy + h - 1, col);
                line(ctx, cx - h + 1, cy + h - 1, cx + h - 1, cy - h + 1, col);
            }
            case IMAGE -> { // framed picture: mountain ridge + sun
                line(ctx, cx - h, cy - h + 1, cx + h, cy - h + 1, col);
                line(ctx, cx - h, cy + h - 1, cx + h, cy + h - 1, col);
                line(ctx, cx - h, cy - h + 1, cx - h, cy + h - 1, col);
                line(ctx, cx + h, cy - h + 1, cx + h, cy + h - 1, col);
                line(ctx, cx - h + 1, cy + h - 2, cx - 1, cy, col);
                line(ctx, cx - 1, cy, cx + h - 1, cy + h - 2, col);
                Render2DEngine.drawRound(ctx, cx + 1.5f, cy - h + 3f, 3.5f, 3.5f, 1.75f, c);
            }
        }
    }

    private static void line(DrawContext ctx, float x, float y, float x1, float y1, int col) {
        Render2DEngine.drawLine(ctx, x, y, x1, y1, col);
    }

    static Color mix(Color a, Color b, float t) {
        t = Math.max(0f, Math.min(1f, t));
        return new Color(
                (int) (a.getRed() + (b.getRed() - a.getRed()) * t),
                (int) (a.getGreen() + (b.getGreen() - a.getGreen()) * t),
                (int) (a.getBlue() + (b.getBlue() - a.getBlue()) * t),
                (int) (a.getAlpha() + (b.getAlpha() - a.getAlpha()) * t));
    }
}
