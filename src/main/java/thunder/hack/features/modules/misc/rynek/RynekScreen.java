package thunder.hack.features.modules.misc.rynek;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;
import thunder.hack.core.manager.client.ModuleManager;
import thunder.hack.features.modules.client.HudEditor;
import thunder.hack.gui.font.FontRenderers;
import thunder.hack.setting.Setting;
import thunder.hack.setting.impl.Bind;
import thunder.hack.setting.impl.SettingGroup;
import thunder.hack.utility.render.Render2DEngine;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;

import static thunder.hack.features.modules.Module.mc;

/**
 * The single AutoMarket window (opened by enabling the module or the {@code ]} keybind). Lists the
 * snipe offers and the auto-sell items. Clicking a snipe offer opens an INLINE editor (same window, no
 * separate screen) to set its max price and per-line filters; middle-clicking an item in {@code /ah}
 * opens the same editor as an overlay over the market.
 *
 * <p>Every accent in here comes from the client's own theme colour ({@code HudEditor.Color} /
 * {@code Color2}) rather than a hard-coded purple — an AutoMarket that stayed violet while the rest of
 * the client was green looked like somebody else's mod.
 */
public class RynekScreen extends Screen {
    /* ------------------------------------------------------------------ */
    /* Palette. Only the neutrals are fixed; every accent is the theme's.   */
    /* ------------------------------------------------------------------ */
    private static final Color BG       = new Color(19, 20, 27, 242);
    private static final Color TILE     = new Color(28, 29, 39, 235);
    private static final Color ROW      = new Color(25, 26, 35, 225);
    private static final Color ROW_HOV  = new Color(38, 40, 53, 240);
    private static final Color SLOT     = new Color(14, 15, 21, 235);
    private static final Color FIELD    = new Color(14, 15, 21, 235);
    private static final Color FIELD_A  = new Color(22, 24, 34, 240);
    private static final Color GREEN    = new Color(112, 220, 128);
    private static final Color RED      = new Color(242, 112, 112);
    private static final Color TEXT     = new Color(235, 236, 242);
    private static final Color DIM      = new Color(146, 149, 164);
    private static final Color MUTED    = new Color(96, 99, 114);
    private static final Color DIVIDER  = new Color(255, 255, 255, 16);
    /** Used only if the theme settings cannot be read for some reason. */
    private static final Color ACCENT_FALLBACK = new Color(139, 100, 246);

    /** The client's primary theme colour, opaque. */
    private static Color accent() {
        return themeColor(0);
    }

    /** The secondary theme colour. */
    private static Color accent2() {
        return themeColor(1);
    }

    /**
     * {@code HudEditor.getColor(n)}, NOT {@code hcolor1} directly.
     * <p>
     * Reading the colour setting straight ignores ColorMode — on Rainbow / Sky / Fade / DoubleColor /
     * Analogous the real colour is computed, and {@code hcolor1} still holds whatever raw value the
     * picker was left on. That is why the window stayed violet while the HUD, which has always used
     * {@code getColor}, was green.
     */
    private static Color themeColor(int index) {
        try {
            Color c = HudEditor.getColor(index);
            return new Color(c.getRed(), c.getGreen(), c.getBlue(), 255);
        } catch (Exception e) {
            return ACCENT_FALLBACK;
        }
    }

    private static Color alpha(Color c, int a) {
        return Render2DEngine.injectAlpha(c, a);
    }

    private float panelX, panelY;
    private final float panelW = 372f;
    /** Grown so six snipe offers fit, but never taller than the window it has to sit inside. */
    private float panelH = 460f;
    private final float headerH = 42f;
    private final float rowH = 26f, rowGap = 5f;
    /** Offer rows visible at once; the rest are reachable with the wheel. */
    private static final int OFFER_ROWS = 6;

    private record RowUI(RynekItem item, float minusX, float plusX, float removeX, float btnY, float rx, float ry, float rw) {}
    private record OfferUI(RynekBuyEntry entry, float editX, float editY, float editW, float removeX, float removeY) {}

    private final List<RowUI> rowUIs = new ArrayList<>();
    private final List<OfferUI> offerUIs = new ArrayList<>();

    // ---- scrolling ----
    /** First visible row of each list, plus the bounds the wheel hit-tests against (set every frame). */
    private int offerScroll, sellScroll;
    private int offerMaxScroll, sellMaxScroll;
    private float offersRegionY, offersRegionH, sellRegionY, sellRegionH;
    /** Scrollbar geometry, published by {@link #drawScrollbar} so the bars can be dragged. */
    private float barX, offerBarY, offerBarH, offerBarThumb, sellBarY, sellBarH, sellBarThumb;
    /** Which bar the mouse is currently dragging: -1 none, 0 offers, 1 sell list. */
    private int draggingBar = -1;

    // ---- tabs + settings ----
    /** 0 = offers and sell list, 1 = every AutoMarket setting. */
    private int tab = 0;
    private float tabOffersX, tabSettingsX, tabY, tabW, tabH;
    private float startX, startY, startW, startH;
    private record SettingUI(Setting<?> setting, float x, float y, float w, float h) {}
    private final List<SettingUI> settingUIs = new ArrayList<>();
    /** Setting whose text field has the keyboard, and the text being typed into it. */
    private Setting<?> focusedSetting;
    private String settingBuffer = "";
    /** Setting waiting for the next key press to become its bind. */
    private Setting<?> bindingSetting;

    // ---- inline editor state ----
    /** Shared offer editor — same widget the /ah overlay uses, so both places behave identically. */
    private RynekOfferEditor buyEditor;
    private RynekItem editingSell;          // editing a sell item's price
    private String sellPriceText = "";
    /** When set, confirming the buy editor chains into this item's sell-price editor. */
    private RynekItem pendingSellItem;
    private float priceX, priceY, priceW, fieldH, saveX, saveY, saveW, saveH, backX, backY, backW, backH;

    public RynekScreen() {
        super(Text.of("AutoMarket"));
    }

    public RynekScreen(RynekBuyEntry editing) {
        this();
        openEditor(editing, false);
    }

    /**
     * Middle-click flow: prepares the item for BOTH the snipe (buy) and sell lists and opens the window
     * with the BUY-price editor. Neither list is written until that editor is confirmed — backing out
     * with Escape has to leave nothing behind.
     */
    public static RynekScreen forNewOffer(ItemStack stack) {
        RynekBuyEntry entry = RynekBuyEntry.fromStack(stack);
        RynekScreen screen = new RynekScreen();
        screen.openEditor(entry, true);
        screen.pendingSellItem = new RynekItem(stack.getItem(), stack.getCount(), 0);
        return screen;
    }

    private void openEditor(RynekBuyEntry entry, boolean isNew) {
        buyEditor = new RynekOfferEditor(entry, isNew);
    }

    /**
     * @param confirmed true when the user pressed the editor's own button. Enter and Escape are always
     *                  a cancel: a brand-new offer is discarded rather than quietly added.
     */
    private void closeEditor(boolean confirmed) {
        if (buyEditor != null) {
            if (confirmed) buyEditor.commit();
            else buyEditor.cancel();
        }
        buyEditor = null;
        // middle-click "both" flow: after the buy price, chain into the sell-price editor
        RynekItem it = pendingSellItem;
        pendingSellItem = null;
        if (confirmed && it != null) {
            RynekConfigManager.getInstance().addItem(it);
            openSellEditor(it);
        }
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    private List<RynekItem> items() {
        return RynekConfigManager.getInstance().getItems();
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        float sw = mc.getWindow().getScaledWidth();
        float sh = mc.getWindow().getScaledHeight();
        // 460 is what six offer rows plus a workable sell list need; on a small scaled window we take
        // what there is and the lists just show fewer rows — the wheel reaches the rest either way.
        panelH = Math.max(300f, Math.min(460f, sh - 16f));
        panelX = (sw - panelW) / 2f;
        panelY = (sh - panelH) / 2f;
        float px = panelX, py = panelY;
        Color ac = accent(), ac2 = accent2();
        mouseXCache = mouseX;
        mouseYCache = mouseY;

        drawPanel(context, px, py, ac, ac2);
        drawHeader(context, px, py, ac, ac2);

        if (editingSell != null) {
            renderSellEditor(context, mouseX, mouseY, px, py, ac, ac2);
            return;
        }
        if (buyEditor != null) {
            // the shared widget paints its own panel — centre it over ours
            float ew = RynekOfferEditor.WIDTH, eh = buyEditor.height();
            buyEditor.render(context, px + (panelW - ew) / 2f, py + (panelH - eh) / 2f, mouseX, mouseY);
            return;
        }

        if (tab == 1) renderSettings(context, mouseX, mouseY, px, py, ac);
        else renderList(context, mouseX, mouseY, px, py, ac, ac2);

        drawStartButton(context, mouseX, mouseY, px, py, ac, ac2);
    }

    /* ------------------------------------------------------------------ */
    /* Chrome                                                              */
    /* ------------------------------------------------------------------ */

    /** Drop shadow, a flat body, and a single hairline rim. Nothing else — this is a tool window. */
    private void drawPanel(DrawContext context, float px, float py, Color ac, Color ac2) {
        Render2DEngine.drawBlurredShadow(context, px, py, panelW, panelH, 16, new Color(0, 0, 0, 150));
        // the rim is just a slightly larger rounded rect painted underneath the body
        Render2DEngine.drawRound(context, px - 1f, py - 1f, panelW + 2f, panelH + 2f, 10f, alpha(ac, 45));
        Render2DEngine.drawRound(context, px, py, panelW, panelH, 9f, BG);
    }

    private void drawHeader(DrawContext context, float px, float py, Color ac, Color ac2) {
        // title block
        Render2DEngine.drawRound(context, px + 13f, py + 11f, 3f, 19f, 1.5f, ac);
        FontRenderers.monsterrat.drawString(context, "AUTOMARKET", px + 24f, py + 10f, TEXT.getRGB());

        String subtitle;
        if (editingSell != null) subtitle = "Cena sprzedaży (2/2)";
        else if (buyEditor != null) subtitle = pendingSellItem != null ? "Cena kupna (1/2)" : "Edytor oferty";
        else subtitle = ModuleManager.rynek.isBuying()
                ? "Snajper pracuje  ·  " + ModuleManager.rynek.getSessionTime()
                : "Snajper rynku";
        boolean live = editingSell == null && buyEditor == null && ModuleManager.rynek.isBuying();
        float subX = px + 24f;
        if (live) {
            Render2DEngine.drawRound(context, subX, py + 24.5f, 4f, 4f, 2f, GREEN);
            subX += 8f;
        }
        FontRenderers.sf_medium_mini.drawString(context, subtitle, subX, py + 23f,
                (live ? GREEN : DIM).getRGB());

        // tabs live in the header, right-aligned — the content below needs every pixel it can get
        if (editingSell == null && buyEditor == null) {
            tabH = 20f;
            tabW = 74f;
            tabY = py + 11f;
            tabSettingsX = px + panelW - 13f - tabW;
            tabOffersX = tabSettingsX - 5f - tabW;
            drawTab(context, tabOffersX, "OFERTY", 0, (int) mouseXCache, (int) mouseYCache, ac, ac2);
            drawTab(context, tabSettingsX, "USTAWIENIA", 1, (int) mouseXCache, (int) mouseYCache, ac, ac2);
        }

        Render2DEngine.drawRect(context, px + 12f, py + headerH - 1f, panelW - 24f, 1f, DIVIDER);
    }

    /** Mouse position stashed by {@link #render} so the header can draw hover states. */
    private float mouseXCache, mouseYCache;

    private void drawTab(DrawContext context, float x, String label, int id, int mouseX, int mouseY, Color ac, Color ac2) {
        boolean active = tab == id;
        boolean hov = Render2DEngine.isHovered(mouseX, mouseY, x, tabY, tabW, tabH);
        Render2DEngine.drawRound(context, x, tabY, tabW, tabH, 4f,
                active ? alpha(ac, 225) : (hov ? ROW_HOV : ROW));
        FontRenderers.sf_medium.drawCenteredString(context, label, x + tabW / 2f, tabY + 6.5f,
                (active ? TEXT : (hov ? TEXT : DIM)).getRGB());
    }

    private void drawStartButton(DrawContext context, int mouseX, int mouseY, float px, float py, Color ac, Color ac2) {
        boolean running = ModuleManager.rynek.isBuying();
        startW = panelW - 24f;
        startH = 30f;
        startX = px + 12f;
        startY = py + panelH - 42f;
        boolean hov = Render2DEngine.isHovered(mouseX, mouseY, startX, startY, startW, startH);

        Color a = running ? RED : ac;
        Render2DEngine.drawRound(context, startX, startY, startW, startH, 6f, alpha(a, hov ? 245 : 190));
        FontRenderers.monsterrat.drawCenteredString(context, running ? "STOP" : "START",
                startX + startW / 2f, startY + 11f, TEXT.getRGB());
    }

    /* ------------------------------------------------------------------ */
    /* Settings tab                                                        */
    /* ------------------------------------------------------------------ */

    /**
     * The short list the window shows, in this order. Everything else the module has is a set-once
     * default (the /ah command, the sort name, the money regex) or a guard rail you configure before a
     * session rather than during one — showing all twenty turned the window into a wall of rows.
     * They stay in the config file and keep working; they are just not in your face here.
     */
    private static final List<String> SHOWN_SETTINGS =
            List.of("BuyDelay", "SortClicks", "ReopenAh", "ReopenDelay", "AutoJump", "JumpDelay",
                    "WhenFull", "KeepFreeSlots", "AutoSell", "SellDelay", "Webhook",
                    "OpenGUI", "StartBuy", "StopBuy");

    /** Settings whose value is a count of seconds; shown as "45 s" rather than a bare number. */
    private static final List<String> SECOND_SETTINGS = List.of("ReopenDelay", "JumpDelay");

    private Setting<?> findSetting(String name) {
        for (Setting<?> s : ModuleManager.rynek.getSettings())
            if (s != null && name.equals(s.getName())) return s;
        return null;
    }

    private void renderSettings(DrawContext context, int mouseX, int mouseY, float px, float py, Color ac) {
        settingUIs.clear();
        float y = py + headerH + 8f;
        // Roomy rows on purpose: this is a list you click during a session, and 17px rows with mini text
        // were a squint-and-poke affair.
        float rowW = panelW - 24f, rh = 26f;
        float bottom = py + panelH - 46f;

        for (String name : SHOWN_SETTINGS) {
            Setting<?> setting = findSetting(name);
            if (setting == null || setting.getValue() instanceof SettingGroup) continue;
            if (y + rh > bottom) break;

            float rx = px + 12f;
            boolean hov = Render2DEngine.isHovered(mouseX, mouseY, rx, y, rowW, rh - 3f);
            Render2DEngine.drawRound(context, rx, y, rowW, rh - 3f, 5f, hov ? ROW_HOV : ROW);
            if (hov) Render2DEngine.drawRound(context, rx, y + 4f, 2.5f, rh - 11f, 1.25f, ac);
            float textY = y + (rh - 3f - 8f) / 2f;
            FontRenderers.sf_medium.drawString(context, setting.getName(), rx + 10f, textY, TEXT.getRGB());

            Object value = setting.getValue();
            float valX = rx + rowW - 10f;

            if (value instanceof Boolean b) {
                float pw = 30f, ph = 14f;
                float pxx = rx + rowW - 10f - pw, pyy = y + (rh - 3f - ph) / 2f;
                Render2DEngine.drawRound(context, pxx, pyy, pw, ph, ph / 2f, b ? alpha(ac, 235) : SLOT);
                Render2DEngine.drawRound(context, b ? pxx + pw - ph + 2f : pxx + 2f, pyy + 2f,
                        ph - 4f, ph - 4f, (ph - 4f) / 2f, TEXT);
            } else if (setting == bindingSetting) {
                drawValue(context, "naciśnij klawisz...", valX, textY, ac);
            } else if (setting == focusedSetting) {
                boolean blink = (System.currentTimeMillis() / 500) % 2 == 0;
                drawValue(context, (settingBuffer.isEmpty() ? "" : settingBuffer) + (blink ? "_" : ""), valX, textY, ac);
            } else {
                drawValue(context, displayValue(setting), valX, textY, DIM);
            }

            settingUIs.add(new SettingUI(setting, rx, y, rowW, rh - 3f));
            y += rh;
        }
    }

    private void drawValue(DrawContext context, String text, float rightX, float y, Color color) {
        String shown = trimMini(text, 168f);
        FontRenderers.sf_medium.drawString(context, shown,
                rightX - FontRenderers.sf_medium.getStringWidth(shown), y, color.getRGB());
    }

    private static String displayValue(Setting<?> setting) {
        Object v = setting.getValue();
        if (v instanceof Bind bind) return bind.getBind();
        if (v instanceof Enum<?>) return setting.currentEnumName();
        if (SECOND_SETTINGS.contains(setting.getName())) return v + " s";
        return String.valueOf(v);
    }

    private static String trimMini(String s, float maxW) {
        if (s == null) return "";
        if (FontRenderers.sf_medium.getStringWidth(s) <= maxW) return s;
        while (s.length() > 1 && FontRenderers.sf_medium.getStringWidth(s + "..") > maxW)
            s = s.substring(0, s.length() - 1);
        return s + "..";
    }

    /* ------------------------------------------------------------------ */
    /* Offers + sell list                                                  */
    /* ------------------------------------------------------------------ */

    private void renderList(DrawContext context, int mouseX, int mouseY, float px, float py, Color ac, Color ac2) {
        // ---- stat tiles ----
        float tileY = py + headerH + 8f;
        float tileH = 38f;
        float tileGap = 7f;
        float tileW = (panelW - 24f - 2f * tileGap) / 3f;
        long profit = Rynek.stats.getProfit();
        drawTile(context, px + 12f, tileY, tileW, tileH, "SALDO",
                Rynek.balance >= 0 ? "$" + compact(Rynek.balance) : "—", ac);
        drawTile(context, px + 12f + tileW + tileGap, tileY, tileW, tileH, "ZYSK SESJI",
                (profit >= 0 ? "+" : "-") + compact(Math.abs(profit)), profit >= 0 ? GREEN : RED);
        drawTile(context, px + 12f + 2f * (tileW + tileGap), tileY, tileW, tileH, "KUPIONO",
                String.valueOf(Rynek.stats.getBoughtItems()), ac);

        List<RynekItem> its = items();
        List<RynekBuyEntry> offers = RynekConfigManager.getInstance().getBuyEntries();
        float listBottom = py + panelH - 46f;

        // ---- snipe offers section ----
        offerUIs.clear();
        float y = tileY + tileH + 11f;
        float offerRowH = 22f, offerGap = 4f;

        // How many offer rows fit while the sell list below still keeps a usable window. Capped at
        // OFFER_ROWS; anything past that is reached with the wheel instead of by growing the panel.
        float sellReserve = 9f + 14f + 2f * (rowH + rowGap);
        int visibleOffers = (int) Math.floor((listBottom - sellReserve - (y + 14f) + offerGap) / (offerRowH + offerGap));
        visibleOffers = Math.max(1, Math.min(OFFER_ROWS, visibleOffers));
        offerMaxScroll = Math.max(0, offers.size() - visibleOffers);
        offerScroll = Math.max(0, Math.min(offerScroll, offerMaxScroll));

        drawSectionHeader(context, px, y, "OFERTY SNAJPERA",
                offerMaxScroll > 0
                        ? (offerScroll + 1) + "-" + Math.min(offerScroll + visibleOffers, offers.size()) + "/" + offers.size()
                        : String.valueOf(offers.size()), ac);
        y += 14f;

        offersRegionY = y;
        if (offers.isEmpty()) {
            offersRegionH = 26f;
            Render2DEngine.drawRound(context, px + 12f, y, panelW - 24f, 26f, 6f, ROW);
            FontRenderers.sf_medium_mini.drawCenteredString(context, "Kliknij scrollem przedmiot w /ah, aby go tu dodać",
                    px + panelW / 2f, y + 10f, DIM.getRGB());
            y += 30f;
        } else {
            int shown = Math.min(visibleOffers, offers.size() - offerScroll);
            offersRegionH = shown * (offerRowH + offerGap) - offerGap;
            boolean scrollable = offerMaxScroll > 0;
            for (int i = offerScroll; i < offerScroll + shown; i++) {
                RynekBuyEntry entry = offers.get(i);
                float rx = px + 12f, rw = panelW - 24f - (scrollable ? 8f : 0f);
                boolean hov = Render2DEngine.isHovered(mouseX, mouseY, rx, y, rw, offerRowH);
                Render2DEngine.drawRound(context, rx, y, rw, offerRowH, 5f, hov ? ROW_HOV : ROW);
                if (hov) Render2DEngine.drawRound(context, rx, y + 3f, 2.5f, offerRowH - 6f, 1.25f, ac);

                Render2DEngine.drawRound(context, rx + 5f, y + 3f, 16f, 16f, 4f, SLOT);
                context.drawItem(offerStack(entry), (int) (rx + 5.5f), (int) (y + 3.5f));

                FontRenderers.sf_medium.drawString(context, trim(entry.getDisplayName(), 132f), rx + 26f, y + 3.5f, TEXT.getRGB());
                String sub = entry.getMaxPrice() > 0 ? "max $" + compact(entry.getMaxPrice()) : "bez ceny — kliknij";
                if (!entry.getUnwantedEnchants().isEmpty())
                    sub += "  ·  bez: " + trim(String.join(",", entry.getUnwantedEnchants()), 66f);
                FontRenderers.sf_medium_mini.drawString(context, sub, rx + 26f, y + 12.5f,
                        (entry.getMaxPrice() > 0 ? GREEN : RED).getRGB());

                float remX = rx + rw - 15f;
                boolean remHov = Render2DEngine.isHovered(mouseX, mouseY, remX - 4f, y + (offerRowH - 12f) / 2f, 15f, 12f);
                FontRenderers.sf_medium.drawCenteredString(context, "x", remX + 2f, y + (offerRowH - 12f) / 2f + 2f,
                        (remHov ? RED : MUTED).getRGB());
                if (hov && !remHov)
                    FontRenderers.sf_medium_mini.drawString(context, "edytuj", remX - 34f, y + (offerRowH - 8f) / 2f, ac.getRGB());

                offerUIs.add(new OfferUI(entry, rx, y, rw - 20f, remX - 4f, y + (offerRowH - 12f) / 2f));
                y += offerRowH + offerGap;
            }
            if (scrollable)
                drawScrollbar(context, px + panelW - 16f, offersRegionY, offersRegionH, offers.size(), visibleOffers,
                        offerScroll, offerMaxScroll, ac, 0);
        }

        Render2DEngine.drawRect(context, px + 12f, y + 2f, panelW - 24f, 1f, DIVIDER);
        y += 9f;

        // ---- sell items section ----
        float listTop = y;
        int visibleSell = Math.max(1, (int) Math.floor((listBottom - (listTop + 14f) + rowGap) / (rowH + rowGap)));
        sellMaxScroll = Math.max(0, its.size() - visibleSell);
        sellScroll = Math.max(0, Math.min(sellScroll, sellMaxScroll));

        drawSectionHeader(context, px, listTop, "PRZEDMIOTY (SPRZEDAŻ)",
                sellMaxScroll > 0
                        ? (sellScroll + 1) + "-" + Math.min(sellScroll + visibleSell, its.size()) + "/" + its.size()
                        : String.valueOf(its.size()), ac2);
        listTop += 14f;

        rowUIs.clear();
        sellRegionY = listTop;
        sellRegionH = Math.max(rowH, listBottom - listTop);

        if (its.isEmpty()) {
            Render2DEngine.drawRound(context, px + 12f, listTop, panelW - 24f, 30f, 6f, ROW);
            FontRenderers.sf_medium_mini.drawCenteredString(context, "Pusto — dodaj przedmiot scrollem w /ah",
                    px + panelW / 2f, listTop + 12f, DIM.getRGB());
            return;
        }

        boolean sellScrollable = sellMaxScroll > 0;
        float ry = listTop;
        for (int i = sellScroll; i < its.size() && i < sellScroll + visibleSell; i++) {
            RynekItem it = its.get(i);
            if (ry + rowH > listBottom) break;
            float rx = px + 12f, rw = panelW - 24f - (sellScrollable ? 8f : 0f);
            boolean hov = Render2DEngine.isHovered(mouseX, mouseY, rx, ry, rw, rowH);
            Render2DEngine.drawRound(context, rx, ry, rw, rowH, 5f, hov ? ROW_HOV : ROW);
            if (hov) Render2DEngine.drawRound(context, rx, ry + 4f, 2.5f, rowH - 8f, 1.25f, ac2);

            Render2DEngine.drawRound(context, rx + 5f, ry + 4f, 18f, 18f, 4f, SLOT);
            ItemStack stack = new ItemStack(it.getItem());
            context.drawItem(stack, (int) (rx + 6f), (int) (ry + 5f));

            FontRenderers.sf_medium.drawString(context, trim(stack.getName().getString(), 120f), rx + 28f, ry + 5f, TEXT.getRGB());
            String sub = it.getSellPrice() > 0
                    ? ("za $" + compact(it.getSellPrice()) + "  ·  " + it.getSellAmount() + " szt.")
                    : (it.getSellAmount() + " szt.  ·  PPM = ustaw cenę");
            FontRenderers.sf_medium_mini.drawString(context, sub, rx + 28f, ry + 15f,
                    (it.getSellPrice() > 0 ? GREEN : DIM).getRGB());

            float stepW = 62f, stepH = 16f;
            float stepX = rx + rw - 22f - stepW;
            float stepY = ry + (rowH - stepH) / 2f;
            Render2DEngine.drawRound(context, stepX, stepY, stepW, stepH, 5f, SLOT);
            boolean minusHov = Render2DEngine.isHovered(mouseX, mouseY, stepX, stepY, 16f, stepH);
            boolean plusHov = Render2DEngine.isHovered(mouseX, mouseY, stepX + stepW - 16f, stepY, 16f, stepH);
            FontRenderers.sf_medium.drawCenteredString(context, "-", stepX + 8f, stepY + 4f, (minusHov ? ac : DIM).getRGB());
            FontRenderers.sf_medium.drawCenteredString(context, String.valueOf(it.getSellAmount()), stepX + stepW / 2f, stepY + 4f, TEXT.getRGB());
            FontRenderers.sf_medium.drawCenteredString(context, "+", stepX + stepW - 8f, stepY + 4f, (plusHov ? ac : DIM).getRGB());

            float remX = rx + rw - 15f;
            boolean remHov = Render2DEngine.isHovered(mouseX, mouseY, remX - 4f, ry + (rowH - 12f) / 2f, 15f, 12f);
            FontRenderers.sf_medium.drawCenteredString(context, "x", remX + 2f, ry + (rowH - 12f) / 2f + 2f, (remHov ? RED : MUTED).getRGB());

            rowUIs.add(new RowUI(it, stepX, stepX + stepW - 16f, remX - 4f, stepY, rx, ry, rw));
            ry += rowH + rowGap;
        }

        if (sellScrollable)
            drawScrollbar(context, px + panelW - 16f, sellRegionY, Math.max(rowH, ry - listTop - rowGap),
                    its.size(), visibleSell, sellScroll, sellMaxScroll, ac2, 1);
    }

    /** Accent dot, label, a hairline out to the right, then the count. */
    private void drawSectionHeader(DrawContext context, float px, float y, String label, String count, Color ac) {
        Render2DEngine.drawRound(context, px + 13f, y + 1.5f, 3f, 3f, 1.5f, ac);
        FontRenderers.sf_medium_mini.drawString(context, label, px + 20f, y, MUTED.getRGB());
        float labelEnd = px + 24f + FontRenderers.sf_medium_mini.getStringWidth(label);
        float countW = FontRenderers.sf_medium_mini.getStringWidth(count);
        float ruleEnd = px + panelW - 18f - countW;
        if (ruleEnd > labelEnd)
            Render2DEngine.drawRect(context, labelEnd, y + 2.5f, ruleEnd - labelEnd, 1f, DIVIDER);
        FontRenderers.sf_medium_mini.drawString(context, count, px + panelW - 13f - countW, y, MUTED.getRGB());
    }

    /**
     * Thin track + thumb on the right edge of a list, drawn only while that list overflows its window.
     * Also publishes the track's bounds so it can be dragged — {@code which} is 0 for the offers, 1 for
     * the sell list.
     */
    private void drawScrollbar(DrawContext context, float x, float y, float h, int total, int visible,
                               int scroll, int maxScroll, Color ac, int which) {
        if (h <= 0f) return;
        float thumbH = Math.max(12f, h * (visible / (float) Math.max(1, total)));
        float travel = Math.max(0f, h - thumbH);
        float t = maxScroll <= 0 ? 0f : scroll / (float) maxScroll;
        boolean dragging = draggingBar == which;
        Render2DEngine.drawRound(context, x, y, 4f, h, 2f, SLOT);
        Render2DEngine.drawRound(context, x, y + travel * t, 4f, thumbH, 2f, alpha(ac, dragging ? 255 : 210));

        if (which == 0) {
            offerBarY = y;
            offerBarH = h;
            offerBarThumb = thumbH;
        } else {
            sellBarY = y;
            sellBarH = h;
            sellBarThumb = thumbH;
        }
        barX = x;
    }

    /**
     * Turns a mouse Y on a scrollbar track into a row offset. The grab is measured from the middle of
     * the thumb, so the list does not jump when you catch the bar off-centre.
     */
    private int scrollFromBar(float mouseY, float barY, float barH, float thumbH, int maxScroll) {
        float travel = Math.max(1f, barH - thumbH);
        float t = (mouseY - barY - thumbH / 2f) / travel;
        return Math.max(0, Math.min(Math.round(t * maxScroll), maxScroll));
    }

    private void drawTile(DrawContext context, float x, float y, float w, float h, String label, String value, Color c) {
        Render2DEngine.drawRound(context, x, y, w, h, 6f, TILE);
        Render2DEngine.drawRound(context, x + 1f, y + 7f, 2.5f, h - 14f, 1.25f, c);
        FontRenderers.sf_medium_mini.drawString(context, label, x + 10f, y + 8f, MUTED.getRGB());
        FontRenderers.monsterrat.drawString(context, value, x + 10f, y + 20f, c.getRGB());
    }

    /** 1 234 567 → "1.23M". Three stat tiles on a 372px panel have no room for raw balances. */
    private static String compact(long v) {
        if (v >= 1_000_000_000L) return String.format("%.2fB", v / 1_000_000_000d);
        if (v >= 1_000_000L) return String.format("%.2fM", v / 1_000_000d);
        if (v >= 10_000L) return String.format("%.1fk", v / 1000d);
        return String.valueOf(v);
    }

    /* ------------------------------------------------------------------ */
    /* Sell-price editor                                                   */
    /* ------------------------------------------------------------------ */

    private void renderSellEditor(DrawContext context, int mouseX, int mouseY, float px, float py, Color ac, Color ac2) {
        float y = py + headerH + 14f;
        ItemStack stack = new ItemStack(editingSell.getItem());
        Render2DEngine.drawRound(context, px + 12f, y, 22f, 22f, 5f, SLOT);
        context.drawItem(stack, (int) (px + 15f), (int) (y + 3f));
        FontRenderers.sf_medium.drawString(context, trim(stack.getName().getString(), panelW - 56f), px + 40f, y + 3f, TEXT.getRGB());
        FontRenderers.sf_medium_mini.drawString(context, "ilość na wystawę: " + editingSell.getSellAmount() + " szt.",
                px + 40f, y + 13f, DIM.getRGB());
        y += 42f;

        fieldH = 18f;
        FontRenderers.sf_medium_mini.drawString(context, "CENA SPRZEDAŻY ($)", px + 13f, y, MUTED.getRGB());
        priceX = px + 12f;
        priceY = y + 11f;
        priceW = panelW - 24f;
        drawField(context, priceX, priceY, priceW, sellPriceText, "np. 10000", true, ac);

        float bh = 26f, bw = (panelW - 24f - 8f) / 2f;
        backX = px + 12f;
        backY = py + panelH - 40f;
        backW = bw;
        backH = bh;
        saveX = backX + bw + 8f;
        saveY = backY;
        saveW = bw;
        saveH = bh;
        boolean backHov = Render2DEngine.isHovered(mouseX, mouseY, backX, backY, backW, backH);
        boolean saveHov = Render2DEngine.isHovered(mouseX, mouseY, saveX, saveY, saveW, saveH);
        Render2DEngine.drawRound(context, backX, backY, backW, backH, 6f, backHov ? ROW_HOV : ROW);
        FontRenderers.sf_medium.drawCenteredString(context, "Anuluj", backX + backW / 2f, backY + 9f, DIM.getRGB());
        Render2DEngine.drawRound(context, saveX, saveY, saveW, saveH, 6f, alpha(ac, saveHov ? 245 : 190));
        FontRenderers.sf_medium.drawCenteredString(context, "Zapisz cenę", saveX + saveW / 2f, saveY + 9f, TEXT.getRGB());
        FontRenderers.sf_medium_mini.drawCenteredString(context, "Enter = zapisz  ·  Esc = anuluj",
                px + panelW / 2f, backY - 11f, MUTED.getRGB());
    }

    private void openSellEditor(RynekItem item) {
        editingSell = item;
        buyEditor = null;
        sellPriceText = item.getSellPrice() > 0 ? String.valueOf(item.getSellPrice()) : "";
    }

    /** @param save false for the Anuluj button / Escape — the typed price is thrown away. */
    private void closeSellEditor(boolean save) {
        if (editingSell != null && save) {
            int price = 0;
            String digits = sellPriceText.replaceAll("[^0-9]", "");
            if (!digits.isEmpty()) {
                try {
                    price = Integer.parseInt(digits);
                } catch (NumberFormatException e) {
                    price = Integer.MAX_VALUE;
                }
            }
            editingSell.setSellPrice(price);
            RynekConfigManager.getInstance().save();
        }
        editingSell = null;
    }

    /** Same half-second beat as the offer editor: value, underline and caret all pulse together. */
    private void drawField(DrawContext context, float x, float y, float w, String value, String placeholder,
                           boolean active, Color ac) {
        boolean on = (System.currentTimeMillis() / 500) % 2 == 0;
        Render2DEngine.drawRound(context, x, y, w, fieldH, 5f, active ? FIELD_A : FIELD);
        Render2DEngine.drawRound(context, x, y + fieldH - 1.5f, w, 1.5f, 0.75f,
                alpha(ac, active ? (on ? 255 : 120) : 90));

        boolean empty = value.isEmpty();
        Color textColor = empty ? MUTED : (active && on ? ac : TEXT);
        FontRenderers.sf_medium.drawString(context, empty ? placeholder : value, x + 7f, y + 5f, textColor.getRGB());

        if (active && on) {
            float tw = empty ? 0f : FontRenderers.sf_medium.getStringWidth(value);
            Render2DEngine.drawRound(context, x + 8f + tw, y + 4f, 1.5f, fieldH - 8f, 0.75f, ac);
        }
    }

    /** The offer's item, drawn with the model the server had on it — see {@link RynekIcon}. */
    private ItemStack offerStack(RynekBuyEntry entry) {
        return entry.toIconStack();
    }

    private String trim(String s, float maxW) {
        if (s == null) return "";
        if (FontRenderers.sf_medium.getStringWidth(s) <= maxW) return s;
        while (s.length() > 1 && FontRenderers.sf_medium.getStringWidth(s + "...") > maxW)
            s = s.substring(0, s.length() - 1);
        return s + "...";
    }

    /* ------------------------------------------------------------------ */
    /* Input                                                               */
    /* ------------------------------------------------------------------ */

    @Override
    public void mouseMoved(double mouseX, double mouseY) {
        // the header draws its tabs before render() has the coordinates in hand
        mouseXCache = (float) mouseX;
        mouseYCache = (float) mouseY;
        super.mouseMoved(mouseX, mouseY);
    }

    @Override
    public boolean mouseClicked(net.minecraft.client.gui.Click click, boolean doubled) {
        int mx = (int) click.x();
        int my = (int) click.y();
        int button = click.button();

        if (editingSell != null) {
            if (Render2DEngine.isHovered(mx, my, saveX, saveY, saveW, saveH)) { closeSellEditor(true); return true; }
            if (Render2DEngine.isHovered(mx, my, backX, backY, backW, backH)) { closeSellEditor(false); return true; }
            return true;
        }

        if (buyEditor != null) {
            buyEditor.mouseClicked(mx, my, button);
            // the editor's own button is the ONLY thing that commits an offer
            if (buyEditor.consumeClose()) closeEditor(true);
            return true;
        }

        // scrollbars — grab either one and drag it. The hit-box is wider than the 4px bar on purpose;
        // hitting a hairline with the mouse is not a game anyone wants to play.
        if (tab == 0) {
            if (offerMaxScroll > 0 && Render2DEngine.isHovered(mx, my, barX - 3f, offerBarY, 10f, offerBarH)) {
                draggingBar = 0;
                offerScroll = scrollFromBar(my, offerBarY, offerBarH, offerBarThumb, offerMaxScroll);
                return true;
            }
            if (sellMaxScroll > 0 && Render2DEngine.isHovered(mx, my, barX - 3f, sellBarY, 10f, sellBarH)) {
                draggingBar = 1;
                sellScroll = scrollFromBar(my, sellBarY, sellBarH, sellBarThumb, sellMaxScroll);
                return true;
            }
        }

        // START / STOP
        if (Render2DEngine.isHovered(mx, my, startX, startY, startW, startH)) {
            commitSetting();
            ModuleManager.rynek.toggleBuying();
            return true;
        }

        // tabs
        if (Render2DEngine.isHovered(mx, my, tabOffersX, tabY, tabW, tabH)) { commitSetting(); tab = 0; return true; }
        if (Render2DEngine.isHovered(mx, my, tabSettingsX, tabY, tabW, tabH)) { commitSetting(); tab = 1; return true; }

        if (tab == 1) {
            for (SettingUI s : settingUIs) {
                if (!Render2DEngine.isHovered(mx, my, s.x(), s.y(), s.w(), s.h())) continue;
                commitSetting();
                Object value = s.setting().getValue();
                if (value instanceof Boolean b) {
                    setBool(s.setting(), !b);
                } else if (value instanceof Bind) {
                    bindingSetting = s.setting();
                } else if (value instanceof Enum<?>) {
                    s.setting().increaseEnum();
                } else {
                    focusedSetting = s.setting();
                    settingBuffer = String.valueOf(value);
                }
                return true;
            }
            commitSetting();
            return true;
        }

        // offer rows: the row opens the editor, the x removes
        for (OfferUI o : offerUIs) {
            if (Render2DEngine.isHovered(mx, my, o.removeX(), o.removeY(), 16f, 16f)) {
                RynekConfigManager.getInstance().removeBuyEntry(o.entry());
                return true;
            }
            if (Render2DEngine.isHovered(mx, my, o.editX(), o.editY(), o.editW(), 22f)) {
                openEditor(o.entry(), false);
                return true;
            }
        }

        // sell rows
        for (RowUI r : rowUIs) {
            if (Render2DEngine.isHovered(mx, my, r.minusX(), r.btnY(), 16f, 16f)) {
                r.item().setSellAmount(r.item().getSellAmount() - 1);
                RynekConfigManager.getInstance().save();
                return true;
            }
            if (Render2DEngine.isHovered(mx, my, r.plusX(), r.btnY(), 16f, 16f)) {
                r.item().setSellAmount(r.item().getSellAmount() + 1);
                RynekConfigManager.getInstance().save();
                return true;
            }
            if (Render2DEngine.isHovered(mx, my, r.removeX(), r.ry() + 4f, 16f, 16f)) {
                RynekConfigManager.getInstance().removeItem(r.item());
                return true;
            }
            if (button == GLFW.GLFW_MOUSE_BUTTON_RIGHT && Render2DEngine.isHovered(mx, my, r.rx(), r.ry(), r.rw() - 90f, rowH)) {
                openSellEditor(r.item());
                return true;
            }
        }
        return true;
    }

    @Override
    public boolean mouseDragged(net.minecraft.client.gui.Click click, double deltaX, double deltaY) {
        if (draggingBar == 0) {
            offerScroll = scrollFromBar((float) click.y(), offerBarY, offerBarH, offerBarThumb, offerMaxScroll);
            return true;
        }
        if (draggingBar == 1) {
            sellScroll = scrollFromBar((float) click.y(), sellBarY, sellBarH, sellBarThumb, sellMaxScroll);
            return true;
        }
        return super.mouseDragged(click, deltaX, deltaY);
    }

    @Override
    public boolean mouseReleased(net.minecraft.client.gui.Click click) {
        draggingBar = -1;
        return super.mouseReleased(click);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        // editors and the settings tab have nothing to scroll, but the wheel must not fall through to
        // the world (hotbar swaps) while the window is up
        if (tab != 0 || buyEditor != null || editingSell != null) return true;

        int dir = verticalAmount > 0 ? -1 : (verticalAmount < 0 ? 1 : 0);
        if (dir == 0) return true;

        boolean overSell = Render2DEngine.isHovered((int) mouseX, (int) mouseY,
                panelX, sellRegionY, panelW, sellRegionH);
        if (overSell) sellScroll = Math.max(0, Math.min(sellScroll + dir, sellMaxScroll));
        else offerScroll = Math.max(0, Math.min(offerScroll + dir, offerMaxScroll));
        return true;
    }

    @Override
    public boolean charTyped(net.minecraft.client.input.CharInput input) {
        char c = (char) input.codepoint();
        if (c < ' ') return true;
        if (editingSell != null) {
            if (Character.isDigit(c)) sellPriceText += c;
            return true;
        }
        if (focusedSetting != null) {
            // numeric settings take digits (and a sign / decimal point); string settings take anything
            if (!focusedSetting.isNumberSetting() || Character.isDigit(c) || c == '.' || c == '-')
                settingBuffer += c;
            return true;
        }
        // digits reach the editor through keyPressed (see RynekOfferEditor) — just swallow the char so
        // it cannot land twice
        return buyEditor != null;
    }

    /** Writes the text buffer back into the focused setting, clamped to its range, and drops focus. */
    private void commitSetting() {
        bindingSetting = null;
        Setting<?> s = focusedSetting;
        if (s == null) return;
        focusedSetting = null;
        String buffer = settingBuffer;
        settingBuffer = "";
        try {
            if (s.isInteger()) setInt(s, clamp(s, (int) Double.parseDouble(buffer.isEmpty() ? "0" : buffer)));
            else if (s.isFloat()) setFloat(s, clamp(s, (float) Double.parseDouble(buffer.isEmpty() ? "0" : buffer)));
            else setStr(s, buffer);
        } catch (NumberFormatException ignored) {
            // leave the setting as it was rather than writing a zero over a value the user meant to keep
        }
    }

    private static int clamp(Setting<?> s, int v) {
        if (s.getMin() instanceof Integer min) v = Math.max(min, v);
        if (s.getMax() instanceof Integer max) v = Math.min(max, v);
        return v;
    }

    private static float clamp(Setting<?> s, float v) {
        if (s.getMin() instanceof Float min) v = Math.max(min, v);
        if (s.getMax() instanceof Float max) v = Math.min(max, v);
        return v;
    }

    @SuppressWarnings("unchecked")
    private static void setBool(Setting<?> s, boolean v) {
        ((Setting<Boolean>) s).setValue(v);
    }

    @SuppressWarnings("unchecked")
    private static void setStr(Setting<?> s, String v) {
        ((Setting<String>) s).setValue(v);
    }

    @SuppressWarnings("unchecked")
    private static void setInt(Setting<?> s, int v) {
        ((Setting<Integer>) s).setValue(v);
    }

    @SuppressWarnings("unchecked")
    private static void setFloat(Setting<?> s, float v) {
        ((Setting<Float>) s).setValue(v);
    }

    @SuppressWarnings("unchecked")
    private static void setBind(Setting<?> s, int key) {
        ((Setting<Bind>) s).setValue(new Bind(key, false, false));
    }

    @Override
    public boolean keyPressed(net.minecraft.client.input.KeyInput in) {
        int key = in.key();
        boolean ctrl = (in.modifiers() & GLFW.GLFW_MOD_CONTROL) != 0;
        if (bindingSetting != null) {
            // Escape clears the bind instead of assigning Escape to it
            setBind(bindingSetting, key == GLFW.GLFW_KEY_ESCAPE ? -1 : key);
            bindingSetting = null;
            return true;
        }
        if (focusedSetting != null) {
            // A Discord webhook URL is ~120 characters of random base64 — it is meant to be pasted, and
            // without this there was simply no way to get one into the field.
            if (key == GLFW.GLFW_KEY_V && ctrl) {
                String clip = mc.keyboard.getClipboard();
                if (clip != null) {
                    clip = clip.replace("\n", "").replace("\r", "").trim();
                    if (focusedSetting.isNumberSetting()) clip = clip.replaceAll("[^0-9.-]", "");
                    settingBuffer += clip;
                }
                return true;
            }
            if (key == GLFW.GLFW_KEY_C && ctrl) {
                mc.keyboard.setClipboard(settingBuffer);
                return true;
            }
            if (key == GLFW.GLFW_KEY_A && ctrl) { // select-all here can only mean "clear it"
                settingBuffer = "";
                return true;
            }
            if (key == GLFW.GLFW_KEY_ESCAPE || key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) {
                commitSetting();
                return true;
            }
            if (key == GLFW.GLFW_KEY_BACKSPACE && !settingBuffer.isEmpty())
                settingBuffer = settingBuffer.substring(0, settingBuffer.length() - 1);
            return true;
        }
        if (editingSell != null) {
            // Enter saves the price you just typed; Escape drops it. Making both save was the same
            // trap the offer editor had — there was no way to back out of a mistyped number.
            if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) { closeSellEditor(true); return true; }
            if (key == GLFW.GLFW_KEY_ESCAPE) { closeSellEditor(false); return true; }
            if (key == GLFW.GLFW_KEY_BACKSPACE && !sellPriceText.isEmpty())
                sellPriceText = sellPriceText.substring(0, sellPriceText.length() - 1);
            return true;
        }
        if (buyEditor != null) {
            // Enter / Escape mean "do not add" — only the editor's button commits
            if (buyEditor.keyPressed(key)) closeEditor(false);
            return true;
        }
        if (key == GLFW.GLFW_KEY_ESCAPE) {
            close();
            return true;
        }
        return false;
    }
}
