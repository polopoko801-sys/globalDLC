package thunder.hack.gui.clickui;

import net.minecraft.client.gui.DrawContext;
import thunder.hack.core.Managers;
import thunder.hack.core.manager.client.ModuleManager;
import thunder.hack.features.modules.Module;
import thunder.hack.features.modules.client.ClickGui;
import thunder.hack.gui.font.FontRenderers;
import thunder.hack.utility.ColorPalette;
import thunder.hack.utility.render.Render2DEngine;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;

import static thunder.hack.features.modules.Module.mc;

/**
 * The Edit GUI tab of the ClickGUI: the window's colour, size and style (Liquid Glass - the frosted window - or
 * Matt, BKClient's solid dark GUI), with a live preview of the window beside the controls. Edit HUD is a button
 * at the bottom.
 */
final class EditGuiPanel {
    private static final Color TEXT = new Color(238, 239, 245);
    private static final Color TEXT_DIM = new Color(172, 175, 192);
    private static final Color TEXT_MUTED = new Color(98, 101, 118);
    private static final Color FIELD = new Color(255, 255, 255, 14);
    private static final Color FIELD_HOVER = new Color(255, 255, 255, 26);
    private static final float CONTROLS_W = 190f;
    private static final float SIZE_MIN = 0.75f, SIZE_MAX = 1.3f;

    private record Hot(float x, float y, float w, float h, Runnable action) {
        boolean covers(double mx, double my) {
            return mx >= x && mx <= x + w && my >= y && my <= y + h;
        }
    }

    private final List<Hot> hots = new ArrayList<>();
    private boolean draggingSize;
    private float sliderX, sliderW;
    /** The size being dragged: applied on release, so the window does not rescale under the cursor mid-drag. */
    private float pendingSize = -1f;

    private static ClickGui gui() {
        return ModuleManager.clickGui;
    }

    private float shownSize() {
        return pendingSize > 0 ? pendingSize : ClickGUI.guiSize();
    }

    /** @param x, y, w, h the content area under the tab header */
    /** What the cursor is over this frame: the preview shows it before it is clicked. */
    private Color hoverColor;
    private Boolean hoverMatt;

    void render(DrawContext ctx, int mouseX, int mouseY, float x, float y, float w, float h) {
        hots.clear();
        hoverColor = null;
        hoverMatt = null;
        if (draggingSize && sliderW > 0) pendingSize = sizeAt(mouseX);

        float cx = x + 14f, cy = y + 12f;

        section(ctx, "STYL", cx, cy);
        cy += 12f;
        float half = (CONTROLS_W - 4f) / 2f;
        boolean glass = !ClickGUI.matt();
        choice(ctx, mouseX, mouseY, cx, cy, half, 22f, "Liquid Glass", glass,
                () -> gui().guiStyle.setValue(ClickGui.GuiStyle.LiquidGlass));
        choice(ctx, mouseX, mouseY, cx + half + 4f, cy, half, 22f, "Matt", !glass,
                () -> gui().guiStyle.setValue(ClickGui.GuiStyle.Matt));
        if (Render2DEngine.isHovered(mouseX, mouseY, cx, cy, half, 22f)) hoverMatt = false;
        else if (Render2DEngine.isHovered(mouseX, mouseY, cx + half + 4f, cy, half, 22f)) hoverMatt = true;
        cy += 34f;

        section(ctx, "KOLOR", cx, cy);
        cy += 12f;
        Color active = ColorPalette.getActive();
        float dot = 16f, step = 25f;
        int perRow = 7;
        for (int i = 0; i < ColorPalette.SWATCHES.size(); i++) {
            Color c = ColorPalette.SWATCHES.get(i);
            float dx = cx + (i % perRow) * step + 2f, dy = cy + (i / perRow) * 21f + 2f;
            boolean on = active != null && active.getRGB() == c.getRGB();
            boolean hov = Render2DEngine.isHovered(mouseX, mouseY, dx - 2, dy - 2, dot + 4, dot + 4);
            if (hov) hoverColor = c;
            if (on) Render2DEngine.drawRoundSmooth(ctx, dx - 2.5f, dy - 2.5f, dot + 5f, dot + 5f, (dot + 5f) / 2f, TEXT);
            Render2DEngine.drawRoundSmooth(ctx, dx, dy, dot, dot, dot / 2f, hov ? c.brighter() : c);
            final Color pick = c;
            hots.add(new Hot(dx - 2, dy - 2, dot + 4, dot + 4, () -> ColorPalette.apply(pick)));
        }
        cy += 2 * 21f + 6f;
        choice(ctx, mouseX, mouseY, cx, cy, 74f, 18f, "Domyślny", active == null, ColorPalette::reset);
        cy += 30f;

        section(ctx, "ROZMIAR", cx, cy);
        String pct = Math.round(shownSize() * 100f) + "%";
        FontRenderers.sf_medium_mini.drawString(ctx, pct, cx + CONTROLS_W - FontRenderers.sf_medium_mini.getStringWidth(pct),
                cy, TEXT_DIM.getRGB());
        cy += 14f;
        sliderX = cx;
        sliderW = CONTROLS_W;
        float t = (shownSize() - SIZE_MIN) / (SIZE_MAX - SIZE_MIN);
        Color acc = ClickGUI.accent();
        Render2DEngine.drawRoundSmooth(ctx, cx, cy + 3f, CONTROLS_W, 4f, 2f, FIELD_HOVER);
        Render2DEngine.drawRoundSmooth(ctx, cx, cy + 3f, Math.max(4f, CONTROLS_W * t), 4f, 2f, acc);
        Render2DEngine.drawRoundSmooth(ctx, cx + CONTROLS_W * t - 5f, cy, 10f, 10f, 5f, TEXT);
        hots.add(new Hot(cx - 4f, cy - 4f, CONTROLS_W + 8f, 18f, () -> draggingSize = true));

        button(ctx, mouseX, mouseY, cx, y + h - 34f, CONTROLS_W, 22f, "Edit HUD", () -> {
            Module m = Managers.MODULE.get("HudEditor");
            if (m != null && !m.isEnabled()) m.enable();
        });

        // ---- preview
        float pvX = cx + CONTROLS_W + 14f, pvY = y + 24f;
        float pvW = x + w - 14f - pvX, pvH = y + h - 12f - pvY;
        section(ctx, "PODGLĄD", pvX, y + 12f);
        Render2DEngine.drawRoundSmooth(ctx, pvX, pvY, pvW, pvH, 8f, new Color(0, 0, 0, 60));
        Render2DEngine.addWindow(ctx, pvX, pvY, pvX + pvW, pvY + pvH, 1.0);
        try {
            drawPreview(ctx, pvX, pvY, pvW, pvH);
        } finally {
            Render2DEngine.popWindow();
        }
    }

    /** A small ClickGUI as it will look: the window, the sidebar with the selected tab, and module cards. */
    private void drawPreview(DrawContext ctx, float bx, float by, float bw, float bh) {
        float winW = 400f, winH = 250f;
        float fit = Math.min((bw - 12f) / (winW * SIZE_MAX), (bh - 12f) / (winH * SIZE_MAX));
        float s = shownSize() * fit;
        var m = ctx.getMatrices();
        m.pushMatrix();
        m.translate(bx + bw / 2f, by + bh / 2f);
        m.scale(s, s);
        m.translate(-winW / 2f, -winH / 2f);
        try {
            // hovering a colour or a style shows it here before it is clicked
            Color acc = hoverColor != null ? hoverColor : ClickGUI.accent();
            boolean matt = hoverMatt != null ? hoverMatt : ClickGUI.matt();
            ClickGUI.glassPanel(ctx, 0, 0, winW, winH, 12f, true, matt);
            Render2DEngine.drawRect(ctx, 110f, 12f, 0.6f, winH - 24f, new Color(255, 255, 255, 20));
            thunder.hack.utility.render.GombalLogo.draw(ctx, 9f, 9f, 22f, 1f);
            FontRenderers.monsterrat.drawString(ctx, "GlobalDLC", 34f, 15f, TEXT.getRGB());
            String[] rows = {"Combat", "Movement", "Render", "Player", "Misc"};
            float ry = 46f;
            for (int i = 0; i < rows.length; i++) {
                if (i == 2) {
                    Render2DEngine.drawRound(ctx, 8f, ry, 94f, 20f, 6f, Render2DEngine.injectAlpha(acc, 52));
                    Render2DEngine.drawRound(ctx, 8f, ry + 5f, 2.5f, 10f, 1.25f, acc);
                }
                FontRenderers.sf_medium.drawString(ctx, rows[i], 18f, ry + 6.5f, (i == 2 ? TEXT : TEXT_DIM).getRGB());
                ry += 22f;
            }
            FontRenderers.sf_bold.drawString(ctx, "Render", 124f, 18f, TEXT.getRGB());
            String[] mods = {"TargetESP", "JumpCircle", "GlassVapor", "Fullbright"};
            boolean[] on = {true, true, false, false};
            for (int i = 0; i < mods.length; i++) {
                float cx = 124f + (i % 2) * 136f, cy = 46f + (i / 2) * 62f;
                Render2DEngine.drawRound(ctx, cx, cy, 128f, 54f, 8f, matt ? new Color(24, 24, 27) : new Color(0, 0, 0, 46));
                FontRenderers.sf_medium.drawString(ctx, mods[i], cx + 10f, cy + 12f, TEXT.getRGB());
                FontRenderers.sf_medium_mini.drawString(ctx, "Render", cx + 10f, cy + 26f, TEXT_MUTED.getRGB());
                float swX = cx + 128f - 31f, swY = cy + 10f;
                Render2DEngine.drawRound(ctx, swX, swY, 22f, 12f, 6f, on[i] ? acc : new Color(255, 255, 255, 34));
                Render2DEngine.drawRound(ctx, on[i] ? swX + 11f : swX + 1f, swY + 1f, 10f, 10f, 5f, TEXT);
            }
        } finally {
            m.popMatrix();
        }
    }

    private void section(DrawContext ctx, String label, float x, float y) {
        FontRenderers.sf_medium_mini.drawString(ctx, label, x, y, TEXT_MUTED.getRGB());
    }

    private void choice(DrawContext ctx, int mx, int my, float x, float y, float w, float h, String label, boolean selected, Runnable action) {
        boolean hov = Render2DEngine.isHovered(mx, my, x, y, w, h);
        Color acc = ClickGUI.accent();
        Render2DEngine.drawRoundSmooth(ctx, x, y, w, h, 6f, selected ? Render2DEngine.injectAlpha(acc, 70) : hov ? FIELD_HOVER : FIELD);
        if (selected) Render2DEngine.drawRound(ctx, x, y + 5f, 2.5f, h - 10f, 1.25f, acc);
        float tw = FontRenderers.sf_medium.getStringWidth(label);
        FontRenderers.sf_medium.drawString(ctx, label, x + (w - tw) / 2f, y + h / 2f - 3.5f, (selected ? TEXT : TEXT_DIM).getRGB());
        hots.add(new Hot(x, y, w, h, action));
    }

    private void button(DrawContext ctx, int mx, int my, float x, float y, float w, float h, String label, Runnable action) {
        boolean hov = Render2DEngine.isHovered(mx, my, x, y, w, h);
        Render2DEngine.drawRoundSmooth(ctx, x, y, w, h, 6f, hov ? FIELD_HOVER : FIELD);
        float tw = FontRenderers.sf_medium.getStringWidth(label);
        FontRenderers.sf_medium.drawString(ctx, label, x + (w - tw) / 2f, y + h / 2f - 3.5f, TEXT.getRGB());
        hots.add(new Hot(x, y, w, h, action));
    }

    private float sizeAt(double mx) {
        float t = (float) Math.clamp((mx - sliderX) / sliderW, 0.0, 1.0);
        return Math.round((SIZE_MIN + (SIZE_MAX - SIZE_MIN) * t) * 20f) / 20f;
    }

    /** @return true when the click landed on a control */
    boolean click(double mx, double my, int button) {
        if (button != 0) return false;
        for (int i = hots.size() - 1; i >= 0; i--) {
            Hot h = hots.get(i);
            if (h.covers(mx, my)) {
                thunder.hack.utility.UiSounds.play(thunder.hack.utility.UiSounds.Sound.TAP, 0.5f);
                h.action().run();
                if (draggingSize) pendingSize = sizeAt(mx);
                return true;
            }
        }
        return false;
    }

    void release() {
        if (draggingSize && pendingSize > 0) gui().guiSize.setValue(pendingSize);
        draggingSize = false;
        pendingSize = -1f;
    }
}
