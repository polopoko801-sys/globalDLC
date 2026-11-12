package thunder.hack.gui.hud;

import net.minecraft.client.gui.DrawContext;
import thunder.hack.core.Managers;
import thunder.hack.features.hud.HudElement;
import thunder.hack.features.modules.Module;
import thunder.hack.gui.font.FontRenderers;
import thunder.hack.utility.render.BkLiquidGlass;
import thunder.hack.utility.render.Render2DEngine;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static thunder.hack.features.modules.Module.mc;

/**
 * Right-click anywhere in the open chat that is not the chat itself: a pane of liquid glass pops up at the cursor
 * with every HUD element, each with its switch. It scrolls, and a click outside closes it.
 */
public final class ChatHudPopup {
    private ChatHudPopup() {
    }

    private static final float W = 156f, ROW = 18f, MAX_H = 220f, PAD = 6f, HEAD = 22f;
    private static final Color TEXT = new Color(238, 239, 245);
    private static final Color TEXT_DIM = new Color(172, 175, 192);

    private static boolean open;
    private static long changedAt;
    private static float x, y, h;
    private static float scroll, scrollTarget;
    private static final List<HudElement> elements = new ArrayList<>();

    public static boolean isOpen() {
        return open || visibility() > 0.01f;
    }

    public static void open(double mx, double my) {
        elements.clear();
        for (Module m : Managers.MODULE.modules)
            if (m instanceof HudElement e && !(e instanceof thunder.hack.features.hud.impl.CustomChat)) elements.add(e);
        elements.sort(Comparator.comparing(Module::getName, String.CASE_INSENSITIVE_ORDER));
        h = Math.min(MAX_H, HEAD + elements.size() * ROW + PAD);
        float sw = mc.getWindow().getScaledWidth(), sh = mc.getWindow().getScaledHeight();
        x = (float) Math.min(mx, sw - W - 4f);
        y = (float) Math.min(my, sh - h - 4f);
        scroll = scrollTarget = 0f;
        open = true;
        changedAt = System.currentTimeMillis();
        thunder.hack.utility.UiSounds.play(thunder.hack.utility.UiSounds.Sound.TAP, 0.5f);
    }

    public static void close() {
        if (!open) return;
        open = false;
        changedAt = System.currentTimeMillis();
    }

    /** 0..1: opening eases out with a little overshoot, closing is quicker. */
    private static float visibility() {
        float t = (System.currentTimeMillis() - changedAt) / (open ? 220f : 140f);
        t = Math.clamp(t, 0f, 1f);
        return open ? t : 1f - t;
    }

    private static float easeOutBack(float t) {
        float c1 = 1.70158f, c3 = c1 + 1f, u = t - 1f;
        return 1f + c3 * u * u * u + c1 * u * u;
    }

    public static void render(DrawContext ctx, int mouseX, int mouseY) {
        float v = visibility();
        if (v <= 0.01f) return;
        float s = open ? 0.85f + 0.15f * easeOutBack(v) : 0.85f + 0.15f * v;
        var m = ctx.getMatrices();
        m.pushMatrix();
        m.translate(x, y);
        m.scale(s, s);
        m.translate(-x, -y);
        try {
            BkLiquidGlass.panel(ctx, x, y, W, h, 10f, v);
            FontRenderers.sf_medium.drawString(ctx, "HUD", x + 10f, y + 8f, alpha(TEXT, v).getRGB());
            Render2DEngine.drawRect(ctx, x + 8f, y + HEAD - 3f, W - 16f, 0.6f, alpha(new Color(255, 255, 255, 30), v));

            float viewTop = y + HEAD, viewH = h - HEAD - 4f;
            float maxScroll = Math.max(0f, elements.size() * ROW - viewH);
            scrollTarget = Math.clamp(scrollTarget, -maxScroll, 0f);
            scroll += (scrollTarget - scroll) * 0.3f;
            Render2DEngine.addWindow(ctx, x, viewTop, x + W, viewTop + viewH, 1.0);
            try {
                Color acc = thunder.hack.features.modules.client.HudEditor.getColor(0);
                float ry = viewTop + scroll;
                for (HudElement e : elements) {
                    if (ry + ROW >= viewTop && ry <= viewTop + viewH) {
                        boolean hov = Render2DEngine.isHovered(mouseX, mouseY, x + 4f, ry, W - 8f, ROW) && mouseY >= viewTop;
                        if (hov) Render2DEngine.drawRound(ctx, x + 4f, ry + 1f, W - 8f, ROW - 2f, 5f, alpha(new Color(255, 255, 255, 20), v));
                        boolean on = e.isEnabled();
                        FontRenderers.sf_medium_mini.drawString(ctx, e.getName(), x + 10f, ry + 6.5f, alpha(on ? TEXT : TEXT_DIM, v).getRGB());
                        float swX = x + W - 32f, swY = ry + 3.5f;
                        Render2DEngine.drawRound(ctx, swX, swY, 20f, 11f, 5.5f, alpha(on ? acc : new Color(255, 255, 255, 34), v));
                        Render2DEngine.drawRound(ctx, on ? swX + 10f : swX + 1f, swY + 1f, 9f, 9f, 4.5f, alpha(TEXT, v));
                    }
                    ry += ROW;
                }
            } finally {
                Render2DEngine.popWindow();
            }
            if (maxScroll > 0f) {
                float trackH = viewH - 4f, thumbH = Math.max(14f, trackH * viewH / (elements.size() * ROW));
                float thumbY = viewTop + 2f + (-scroll / maxScroll) * (trackH - thumbH);
                Render2DEngine.drawRound(ctx, x + W - 6f, thumbY, 2.5f, thumbH, 1.25f, alpha(new Color(255, 255, 255, 60), v));
            }
        } finally {
            m.popMatrix();
        }
    }

    /** @return true when the popup took the click */
    public static boolean click(double mx, double my, int button) {
        if (!open) return false;
        if (mx < x || mx > x + W || my < y || my > y + h) {
            close();
            return true;
        }
        float viewTop = y + HEAD;
        if (my < viewTop) return true;
        int index = (int) ((my - viewTop - scroll) / ROW);
        if (index >= 0 && index < elements.size()) {
            elements.get(index).toggle();
            thunder.hack.utility.UiSounds.play(thunder.hack.utility.UiSounds.Sound.TAP, 0.5f);
        }
        return true;
    }

    /** @return true when the scroll was over the popup */
    public static boolean scroll(double mx, double my, double amount) {
        if (!open || mx < x || mx > x + W || my < y || my > y + h) return false;
        scrollTarget += (float) amount * 20f;
        return true;
    }

    private static Color alpha(Color c, float a) {
        return new Color(c.getRed(), c.getGreen(), c.getBlue(), Math.round(c.getAlpha() * Math.clamp(a, 0f, 1f)));
    }
}
