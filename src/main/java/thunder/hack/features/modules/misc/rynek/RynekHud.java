package thunder.hack.features.modules.misc.rynek;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ChatScreen;
import net.minecraft.item.ItemStack;
import thunder.hack.core.manager.client.ModuleManager;
import thunder.hack.features.hud.HudElement;
import thunder.hack.features.modules.client.HudEditor;
import thunder.hack.gui.font.FontRenderers;
import thunder.hack.gui.hud.HudEditorGui;
import thunder.hack.utility.render.Render2DEngine;

import java.awt.Color;
import java.util.List;

/**
 * The market panel: what this session bought, the price of each line, and what it all came to.
 *
 * <p>One row per purchase: the item's icon, its name, its price. Deliberately nothing else — the lore
 * lines that used to be printed under the newest purchase ("to jest z wydarzenia" and friends) turned a
 * status panel into a wall of text. The icon carries that information already.
 *
 * <p>Sits in the top-left by default and can be dragged anywhere like every other HUD element (open
 * chat or the HUD editor and drag it). It draws only while AutoMarket is enabled — except in chat and
 * the HUD editor, where it is always visible so there is something to grab hold of.
 *
 * <p>Its own plain panel, deliberately — routing it through {@code drawHudBase} picked up the client's
 * glass/blur HudStyle, which is not what a dense table of numbers wants. It is simply mixed lighter
 * than it used to be, which was the actual complaint.
 */
public class RynekHud extends HudElement {
    private static final Color PANEL   = new Color(26, 27, 36, 224);
    private static final Color HEADER  = new Color(33, 34, 45, 234);
    private static final Color ROW     = new Color(255, 255, 255, 12);
    private static final Color GREEN   = new Color(120, 220, 130);
    private static final Color TEXT    = new Color(234, 235, 242);
    private static final Color DIM     = new Color(160, 163, 176);
    private static final Color MUTED   = new Color(112, 115, 130);
    private static final Color DIVIDER = new Color(255, 255, 255, 26);

    /** Newest purchases shown; older ones roll off so the panel cannot grow without bound. */
    private static final int MAX_ROWS = 7;
    /** Room the 16px item icon takes on a row. */
    private static final float ICON_W = 18f;

    private static final float PAD = 8f;
    private static final float HEADER_H = 22f;
    private static final float ROW_H = 16f;
    private static final float FOOTER_H = 18f;
    private static final float MIN_W = 168f;
    private static final float MAX_LABEL_W = 130f;

    public RynekHud() {
        // top-left by default; a saved config still wins
        super("AutoMarketHud", 170, 90, 0.006f, 0.02f);
    }

    @Override
    public void onRender2D(DrawContext context) {
        super.onRender2D(context);

        // Dragging works off the bounds this method publishes, so bailing out early while AutoMarket is
        // off would leave nothing to grab. In chat and the HUD editor it is always drawn.
        boolean placing = mc.currentScreen instanceof HudEditorGui || mc.currentScreen instanceof ChatScreen;
        if (!placing && !ModuleManager.rynek.isEnabled()) return;

        var font = FontRenderers.sf_medium_mini;
        var titleFont = FontRenderers.sf_medium;

        List<SessionStats.PurchaseEntry> all = Rynek.stats.getPurchases();
        int shown = Math.min(MAX_ROWS, all.size());
        int firstIndex = all.size() - shown; // newest at the bottom, oldest rolls off the top

        String time = ModuleManager.rynek.getSessionTime();
        String total = "$" + money(Rynek.stats.getSpent());
        String count = Rynek.stats.getBoughtItems() + " szt.";

        // ---- width from the widest line it actually has to hold ----
        float contentW = titleFont.getStringWidth("AUTO RYNEK") + 12f + font.getStringWidth(time);
        for (int i = firstIndex; i < all.size(); i++) {
            SessionStats.PurchaseEntry p = all.get(i);
            contentW = Math.max(contentW, ICON_W + font.getStringWidth(label(p)) + 14f
                    + font.getStringWidth("$" + money(p.price())));
        }
        contentW = Math.max(contentW, font.getStringWidth("RAZEM") + 14f + titleFont.getStringWidth(total));
        contentW = Math.max(contentW, font.getStringWidth(count) + 14f + font.getStringWidth("Saldo: —"));

        float width = Math.max(MIN_W, contentW + PAD * 2f);
        float bodyH = shown == 0 ? ROW_H : shown * ROW_H;
        float height = HEADER_H + 3f + bodyH + 4f + FOOTER_H;

        float x = getPosX() > mc.getWindow().getScaledWidth() / 2f ? getPosX() - width : getPosX();
        float y = getPosY();

        // ---- frame ----
        Render2DEngine.drawBlurredShadow(context, x, y, width, height, 12, new Color(0, 0, 0, 130));
        Render2DEngine.drawRound(context, x, y, width, height, 5f, PANEL);
        Render2DEngine.drawRound(context, x, y, width, HEADER_H, 5f, HEADER);
        Render2DEngine.drawRect(context, x, y + HEADER_H / 2f, width, HEADER_H / 2f, HEADER);

        // ---- header: title, live dot, session clock ----
        Color accent = HudEditor.getColor(0);
        Render2DEngine.drawRound(context, x + PAD, y + 6f, 2.5f, 10f, 1.25f, accent);
        titleFont.drawString(context, "AUTO RYNEK", x + PAD + 7f, y + 7f, TEXT.getRGB());
        font.drawString(context, time, x + width - PAD - font.getStringWidth(time), y + 8f, DIM.getRGB());
        Render2DEngine.drawRect(context, x + PAD, y + HEADER_H - 1f, width - PAD * 2f, 1f, DIVIDER);

        // ---- rows: one purchase each, price on the right ----
        float ry = y + HEADER_H + 3f;
        if (shown == 0) {
            font.drawString(context, "nic jeszcze nie kupiono", x + PAD, ry + 2f, MUTED.getRGB());
        } else {
            for (int i = firstIndex; i < all.size(); i++) {
                SessionStats.PurchaseEntry p = all.get(i);
                boolean isNewest = i == all.size() - 1;
                if (isNewest)
                    Render2DEngine.drawRound(context, x + PAD - 3f, ry - 1f, width - PAD * 2f + 6f, ROW_H, 3f, ROW);

                // Icon on EVERY row, not just the newest — it is the thing that tells two listings of the
                // same base item apart at a glance, which is what the row is for.
                ItemStack icon = iconFor(p);
                if (!icon.isEmpty()) context.drawItem(icon, (int) (x + PAD), (int) (ry - 1f));

                String name = label(p);
                String price = "$" + money(p.price());
                font.drawString(context, name, x + PAD + ICON_W, ry + 3f, (isNewest ? TEXT : DIM).getRGB());
                font.drawString(context, price, x + width - PAD - font.getStringWidth(price), ry + 3f,
                        (isNewest ? GREEN : DIM).getRGB());
                ry += ROW_H;
            }
        }

        // ---- footer: the total ----
        float fy = y + height - FOOTER_H;
        Render2DEngine.drawRect(context, x + PAD, fy - 1f, width - PAD * 2f, 1f, DIVIDER);
        font.drawString(context, "RAZEM", x + PAD, fy + 6f, MUTED.getRGB());
        titleFont.drawString(context, total, x + width - PAD - titleFont.getStringWidth(total), fy + 5f, accent.getRGB());
        font.drawString(context, count, x + PAD + font.getStringWidth("RAZEM") + 8f, fy + 6f, DIM.getRGB());

        setBounds(x, y, width, height);
    }

    /** The bought item, model components and all. Empty when nothing was captured. */
    private static ItemStack iconFor(SessionStats.PurchaseEntry p) {
        return p.icon().toStack();
    }

    /** "2x Ana buty", trimmed so one absurd item name cannot stretch the panel across the screen. */
    private static String label(SessionStats.PurchaseEntry p) {
        String name = p.name() == null || p.name().isBlank() ? "?" : p.name();
        return trim((p.qty() > 1 ? p.qty() + "x " : "") + name, MAX_LABEL_W);
    }

    private static String trim(String s, float maxW) {
        if (s == null) return "";
        var font = FontRenderers.sf_medium_mini;
        if (font.getStringWidth(s) <= maxW) return s;
        while (s.length() > 1 && font.getStringWidth(s + "..") > maxW)
            s = s.substring(0, s.length() - 1);
        return s + "..";
    }

    /** 1234567 -> "1 234 567". */
    private static String money(long value) {
        String digits = Long.toString(Math.abs(value));
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < digits.length(); i++) {
            if (i > 0 && (digits.length() - i) % 3 == 0) sb.append(' ');
            sb.append(digits.charAt(i));
        }
        return (value < 0 ? "-" : "") + sb;
    }
}
