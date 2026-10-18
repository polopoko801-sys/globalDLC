package thunder.hack.features.modules.misc.rynek;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import org.lwjgl.glfw.GLFW;
import thunder.hack.gui.font.FontRenderers;
import thunder.hack.utility.render.Render2DEngine;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;

/**
 * The offer editor: max buy price plus one clickable row per enchantment the item was captured with.
 * <p>
 * It is a plain panel widget rather than a {@link net.minecraft.client.gui.screen.Screen} on purpose.
 * The market editor has to be usable while {@code /ah} is open, and opening a Screen over a
 * HandledScreen makes the client send CloseHandledScreen — the server tears the market down, and
 * pressing Escape afterwards drops you into the world instead of back onto the page you were reading.
 * As a widget it can be drawn straight over the market by {@code MixinHandledScreen}, and the same
 * code backs the editor inside {@link RynekScreen}.
 */
public final class RynekOfferEditor {
    private static final Color PANEL   = new Color(20, 21, 28, 240);
    private static final Color ROW     = new Color(28, 29, 39, 220);
    private static final Color ROW_HOV = new Color(38, 40, 52, 232);
    private static final Color SLOT    = new Color(15, 16, 22, 230);
    private static final Color FIELD   = new Color(15, 16, 22, 230);
    private static final Color FIELD_A = new Color(22, 24, 34, 235);
    /** Used only if the theme settings cannot be read for some reason. */
    private static final Color ACCENT_FALLBACK = new Color(139, 100, 246);
    private static final Color GREEN   = new Color(120, 220, 130);
    private static final Color RED     = new Color(240, 120, 120);
    private static final Color TEXT    = new Color(233, 234, 240);
    private static final Color DIM     = new Color(140, 143, 158);
    private static final Color MUTED   = new Color(92, 95, 110);
    private static final Color DIVIDER = new Color(255, 255, 255, 18);

    /**
     * The client's primary theme colour. Hard-coding a violet here meant the market editor stayed
     * purple while the rest of the client was whatever the user picked.
     */
    private static Color accent() {
        return themeColor(0);
    }

    /**
     * {@code HudEditor.getColor(n)}, NOT {@code hcolor1} directly — reading the setting straight ignores
     * ColorMode, so on anything but Static the editor kept the picker's raw value while the rest of the
     * client showed the computed one.
     */
    private static Color themeColor(int index) {
        try {
            Color c = thunder.hack.features.modules.client.HudEditor.getColor(index);
            return new Color(c.getRed(), c.getGreen(), c.getBlue(), 255);
        } catch (Exception e) {
            return ACCENT_FALLBACK;
        }
    }

    private static Color alpha(Color c, int a) {
        return Render2DEngine.injectAlpha(c, a);
    }

    /** The secondary theme colour. */
    private static Color accent2() {
        return themeColor(1);
    }

    public static final float WIDTH = 250f;
    private static final float HEADER_H = 30f;
    private static final float FIELD_H = 16f;
    private static final float ENCH_H = 15f;
    private static final float BTN_H = 20f;

    private final RynekBuyEntry entry;
    private final ItemStack icon;
    private String priceText;
    /** Optional extra filters — empty/0 means "don't care", which is how most offers stay. */
    private String daysText;
    private String percentText;
    /** 0 = price, 1 = min days, 2 = min percent. */
    private int focus;

    /** Hit-boxes recomputed every frame, so the layout stays in one place — the render method. */
    private float x, y, w, h;
    private float priceX, priceY, priceW;
    private float daysX, daysY, daysW;
    private float pctX, pctY, pctW;
    private float nameX, nameY, nameW;
    private float doneX, doneY, doneW;
    private final List<float[]> enchantHits = new ArrayList<>(); // x, y, w, h, index
    private final List<float[]> loreHits = new ArrayList<>();
    /** Anarchia items can carry a wall of text; past this the panel would not fit on screen. */
    private static final int MAX_LORE_ROWS = 6;

    /**
     * True while this editor is holding an offer that is NOT in the config yet. Nothing is written
     * until the button is pressed, which is the whole point: escaping out of a mis-clicked item must
     * leave no rule behind.
     */
    private final boolean isNew;
    /** Set by the confirm button; the owner polls {@link #consumeClose()} and commits. */
    private boolean closeRequested;

    public RynekOfferEditor(RynekBuyEntry entry) {
        this(entry, false);
    }

    public RynekOfferEditor(RynekBuyEntry entry, boolean isNew) {
        this(entry, isNew, ItemStack.EMPTY);
    }

    /**
     * @param live the listing straight off the market when we have it (the middle-click path). Drawing
     *             the real stack rather than a rebuilt one is what makes the player's resource pack
     *             re-skin it exactly as it does in {@code /ah} — no component is lost on the way.
     */
    public RynekOfferEditor(RynekBuyEntry entry, boolean isNew, ItemStack live) {
        this.entry = entry;
        this.isNew = isNew;
        this.icon = live != null && !live.isEmpty() ? live : iconFor(entry);
        this.priceText = entry.getMaxPrice() > 0 ? String.valueOf(entry.getMaxPrice()) : "";
        this.daysText = entry.getMinDays() > 0 ? String.valueOf(entry.getMinDays()) : "";
        this.percentText = entry.getMinPercent() > 0 ? String.valueOf(entry.getMinPercent()) : "";
    }

    public RynekBuyEntry getEntry() {
        return entry;
    }

    public boolean isNew() {
        return isNew;
    }

    /** True once, right after the confirm button was clicked. */
    public boolean consumeClose() {
        boolean c = closeRequested;
        closeRequested = false;
        return c;
    }

    /** The confirm button: write the typed fields and, for a brand-new offer, register it. */
    public void commit() {
        save();
        if (isNew) RynekConfigManager.getInstance().addBuyEntry(entry);
        RynekConfigManager.getInstance().saveBuy();
    }

    /**
     * Enter / Escape. A new offer is thrown away entirely — the price fields are not written and the
     * rule never reaches the config, so backing out of an item you did not mean to capture costs
     * nothing. An offer that already existed simply keeps what it had.
     */
    public void cancel() {
        closeRequested = false;
        if (isNew) RynekConfigManager.getInstance().removeBuyEntry(entry);
    }

    /** Total height for the current number of enchantment rows. */
    public float height() {
        int rows = Math.max(1, entry.getEnchants().size());
        // The lore section is always laid out, even with nothing in it: when it silently disappeared
        // there was no way to tell "this item has no text" from "the capture dropped it".
        int loreRows = Math.max(1, Math.min(MAX_LORE_ROWS, entry.getLoreLines().size()));
        rows += loreRows + 1; // + the section label
        return HEADER_H + 34f + ENCH_H + 4f + 12f + rows * ENCH_H + 10f
                + 12f + FIELD_H + 10f      // max price
                + 12f + FIELD_H + 12f      // min days / min percent row
                + BTN_H + 22f;             // button + the "Enter / Esc" caption under it
    }

    /** Writes the typed price back into the entry and persists. Safe to call repeatedly. */
    public void save() {
        String digits = priceText.replaceAll("[^0-9]", "");
        long price = 0;
        if (!digits.isEmpty()) {
            try {
                price = Long.parseLong(digits);
            } catch (NumberFormatException ignored) {
            }
        }
        entry.setMaxPrice(price);
        entry.setMinDays(parseInt(daysText));
        entry.setMinPercent(parseInt(percentText));
        RynekConfigManager.getInstance().saveBuy();
    }

    public void render(DrawContext context, float px, float py, int mouseX, int mouseY) {
        x = px;
        y = py;
        w = WIDTH;
        h = height();

        Color ac = accent();
        Render2DEngine.drawBlurredShadow(context, x, y, w, h, 16, new Color(0, 0, 0, 160));
        // 1px accent rim: a slightly larger rounded rect painted underneath the body
        Render2DEngine.drawRound(context, x - 1f, y - 1f, w + 2f, h + 2f, 9f, alpha(ac, 45));
        Render2DEngine.drawRound(context, x, y, w, h, 8f, PANEL);
        Render2DEngine.drawRound(context, x + 11f, y + 9f, 3f, 12f, 1.5f, ac);
        FontRenderers.monsterrat.drawString(context, isNew ? "Nowa oferta" : "Oferta snajpera",
                x + 20f, y + 8f, TEXT.getRGB());
        Render2DEngine.drawRect(context, x + 9f, y + HEADER_H - 1f, w - 18f, 1f, DIVIDER);

        float cy = y + HEADER_H + 7f;

        // item header
        Render2DEngine.drawRound(context, x + 11f, cy, 20f, 20f, 4f, SLOT);
        context.drawItem(icon, (int) (x + 13f), (int) (cy + 2f));
        FontRenderers.sf_medium.drawString(context, trim(entry.getDisplayName(), w - 48f), x + 36f, cy + 2f, TEXT.getRGB());
        FontRenderers.sf_medium_mini.drawString(context, entry.getName(), x + 36f, cy + 12f, DIM.getRGB());
        cy += 27f;

        // name lock — the strongest filter on a server with custom gear, so it sits above the enchants
        nameX = x + 11f;
        nameY = cy;
        nameW = w - 22f;
        boolean nameHov = Render2DEngine.isHovered(mouseX, mouseY, nameX, nameY, nameW, ENCH_H - 2f);
        Render2DEngine.drawRound(context, nameX, nameY, nameW, ENCH_H - 2f, 3f, nameHov ? ROW_HOV : ROW);
        Color nameColor = entry.isMatchName() ? GREEN : DIM;
        Render2DEngine.drawRound(context, nameX + 3f, nameY + 3f, 3f, ENCH_H - 8f, 1.5f, nameColor);
        FontRenderers.sf_medium_mini.drawString(context, "dokładnie ta nazwa", nameX + 10f, nameY + 3.5f, TEXT.getRGB());
        String nameLabel = entry.isMatchName() ? "TAK" : "obojętne";
        FontRenderers.sf_medium_mini.drawString(context, nameLabel,
                nameX + nameW - FontRenderers.sf_medium_mini.getStringWidth(nameLabel) - 5f, nameY + 3.5f, nameColor.getRGB());
        cy += ENCH_H + 4f;

        // enchantment rows — click cycles required / any / unwanted
        FontRenderers.sf_medium_mini.drawString(context, "ENCHANTY (klik = zmiana)", x + 11f, cy, MUTED.getRGB());
        cy += 11f;
        enchantHits.clear();
        List<String> ench = entry.getEnchants();
        if (ench.isEmpty()) {
            FontRenderers.sf_medium_mini.drawString(context, "brak enchantów na tym przedmiocie", x + 13f, cy + 3f, DIM.getRGB());
            cy += ENCH_H;
        } else {
            for (int i = 0; i < ench.size(); i++) {
                String spec = ench.get(i);
                float rx = x + 11f, rw = w - 22f;
                boolean hov = Render2DEngine.isHovered(mouseX, mouseY, rx, cy, rw, ENCH_H - 2f);
                Render2DEngine.drawRound(context, rx, cy, rw, ENCH_H - 2f, 3f, hov ? ROW_HOV : ROW);

                RynekBuyEntry.EnchantState state = entry.stateOf(spec);
                Color c = switch (state) {
                    case REQUIRED -> GREEN;
                    case UNWANTED -> RED;
                    case ANY -> DIM;
                };
                String label = switch (state) {
                    case REQUIRED -> "MUSI MIEĆ";
                    case UNWANTED -> "NIE CHCĘ";
                    case ANY -> "obojętne";
                };
                Render2DEngine.drawRound(context, rx + 3f, cy + 3f, 3f, ENCH_H - 8f, 1.5f, c);
                FontRenderers.sf_medium_mini.drawString(context, spec, rx + 10f, cy + 3.5f, TEXT.getRGB());
                FontRenderers.sf_medium_mini.drawString(context, label,
                        rx + rw - FontRenderers.sf_medium_mini.getStringWidth(label) - 5f, cy + 3.5f, c.getRGB());

                enchantHits.add(new float[]{rx, cy, rw, ENCH_H - 2f, i});
                cy += ENCH_H;
            }
        }
        // the item's own anarchia text — same three states, same click
        loreHits.clear();
        List<String> lore = entry.getLoreLines();
        cy += 4f;
        FontRenderers.sf_medium_mini.drawString(context, "TEKST PRZEDMIOTU (klik = zmiana)", x + 11f, cy, MUTED.getRGB());
        cy += 11f;
        if (lore.isEmpty()) {
            FontRenderers.sf_medium_mini.drawString(context, "ten przedmiot nie ma opisu", x + 13f, cy + 3f, DIM.getRGB());
            cy += ENCH_H;
        } else {
            for (int i = 0; i < lore.size() && i < MAX_LORE_ROWS; i++) {
                String line = lore.get(i);
                float rx = x + 11f, rw = w - 22f;
                boolean hov = Render2DEngine.isHovered(mouseX, mouseY, rx, cy, rw, ENCH_H - 2f);
                Render2DEngine.drawRound(context, rx, cy, rw, ENCH_H - 2f, 3f, hov ? ROW_HOV : ROW);

                RynekBuyEntry.EnchantState state = entry.loreStateOf(line);
                Color c = switch (state) {
                    case REQUIRED -> GREEN;
                    case UNWANTED -> RED;
                    case ANY -> DIM;
                };
                String tag = switch (state) {
                    case REQUIRED -> "MUSI";
                    case UNWANTED -> "NIE";
                    case ANY -> "—";
                };
                Render2DEngine.drawRound(context, rx + 3f, cy + 3f, 3f, ENCH_H - 8f, 1.5f, c);
                float tagW = FontRenderers.sf_medium_mini.getStringWidth(tag);
                FontRenderers.sf_medium_mini.drawString(context, trim(line, rw - tagW - 22f), rx + 10f, cy + 3.5f, TEXT.getRGB());
                FontRenderers.sf_medium_mini.drawString(context, tag, rx + rw - tagW - 5f, cy + 3.5f, c.getRGB());

                loreHits.add(new float[]{rx, cy, rw, ENCH_H - 2f, i});
                cy += ENCH_H;
            }
        }

        cy += 8f;

        // price field
        FontRenderers.sf_medium_mini.drawString(context, "MAX CENA ZAKUPU ($)", x + 11f, cy, MUTED.getRGB());
        cy += 11f;
        priceX = x + 11f;
        priceY = cy;
        priceW = w - 22f;
        drawField(context, priceX, priceY, priceW, priceText, "np. 5000", focus == 0);
        cy += FIELD_H + 10f;

        // Optional extras, both "0 = nieważne": days left for anything that expires (pets, costumes) and
        // the damage roll for swords. Side by side because most offers use neither.
        FontRenderers.sf_medium_mini.drawString(context, "MIN DNI (pety/kostiumy)", x + 11f, cy, MUTED.getRGB());
        FontRenderers.sf_medium_mini.drawString(context, "MIN % (miecz)", x + 11f + (w - 22f) / 2f + 6f, cy, MUTED.getRGB());
        cy += 11f;
        float halfW = (w - 22f - 6f) / 2f;
        daysX = x + 11f;
        daysY = cy;
        daysW = halfW;
        drawField(context, daysX, daysY, daysW, daysText, "dowolnie", focus == 1);
        pctX = daysX + halfW + 6f;
        pctY = cy;
        pctW = halfW;
        drawField(context, pctX, pctY, pctW, percentText, "dowolnie", focus == 2);
        cy += FIELD_H + 12f;

        // done button
        doneX = x + 11f;
        doneY = cy;
        doneW = w - 22f;
        boolean doneHov = Render2DEngine.isHovered(mouseX, mouseY, doneX, doneY, doneW, BTN_H);
        Render2DEngine.drawRound(context, doneX, doneY, doneW, BTN_H, 6f, alpha(ac, doneHov ? 245 : 180));
        FontRenderers.sf_medium.drawCenteredString(context, isNew ? "Dodaj ofertę" : "Zapisz zmiany",
                doneX + doneW / 2f, doneY + 6f, TEXT.getRGB());
        FontRenderers.sf_medium_mini.drawCenteredString(context,
                isNew ? "Enter / Esc = nie dodawaj" : "Enter / Esc = anuluj",
                doneX + doneW / 2f, doneY + BTN_H + 3f, MUTED.getRGB());
    }

    /**
     * The focused field blinks on a half-second beat — the value alternates between white and the theme
     * colour and a caret bar comes and goes with it. An appended "_" was too easy to miss, and there was
     * nothing else on the panel saying which of the three fields the keyboard was going to land in.
     */
    private void drawField(DrawContext context, float fx, float fy, float fw, String text, String hint, boolean active) {
        Color ac = accent();
        boolean on = (System.currentTimeMillis() / 500) % 2 == 0;
        Render2DEngine.drawRound(context, fx, fy, fw, FIELD_H, 4f, active ? FIELD_A : FIELD);
        Render2DEngine.drawRound(context, fx, fy + FIELD_H - 1.5f, fw, 1.5f, 0.75f,
                active ? alpha(ac, on ? 255 : 120) : alpha(ac, 90));

        boolean empty = text.isEmpty();
        Color textColor = empty ? MUTED : (active && on ? ac : TEXT);
        FontRenderers.sf_medium.drawString(context, empty ? hint : text, fx + 5f, fy + 4f, textColor.getRGB());

        if (active && on) {
            float caretX = fx + 6f + (empty ? 0f : FontRenderers.sf_medium.getStringWidth(text));
            Render2DEngine.drawRound(context, caretX, fy + 3f, 1.5f, FIELD_H - 6f, 0.75f, ac);
        }
    }

    private static int parseInt(String s) {
        String digits = s == null ? "" : s.replaceAll("[^0-9]", "");
        if (digits.isEmpty()) return 0;
        try {
            return Integer.parseInt(digits);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** @return true when the click landed on the editor (so the caller must swallow it). */
    public boolean mouseClicked(double mx, double my, int button) {
        if (!Render2DEngine.isHovered((int) mx, (int) my, x, y, w, h)) return false;

        // The confirm button. It used to be decorative — it drew, said "Enter / Esc", and had no
        // hit-box, so the only way out was the keyboard.
        if (Render2DEngine.isHovered((int) mx, (int) my, doneX, doneY, doneW, BTN_H)) {
            closeRequested = true;
            return true;
        }
        if (Render2DEngine.isHovered((int) mx, (int) my, nameX, nameY, nameW, ENCH_H - 2f)) {
            entry.setMatchName(!entry.isMatchName());
            RynekConfigManager.getInstance().saveBuy();
            return true;
        }
        if (Render2DEngine.isHovered((int) mx, (int) my, priceX, priceY, priceW, FIELD_H)) { focus = 0; return true; }
        if (Render2DEngine.isHovered((int) mx, (int) my, daysX, daysY, daysW, FIELD_H)) { focus = 1; return true; }
        if (Render2DEngine.isHovered((int) mx, (int) my, pctX, pctY, pctW, FIELD_H)) { focus = 2; return true; }

        for (float[] hit : loreHits) {
            if (Render2DEngine.isHovered((int) mx, (int) my, hit[0], hit[1], hit[2], hit[3])) {
                entry.cycleLoreState(entry.getLoreLines().get((int) hit[4]));
                RynekConfigManager.getInstance().saveBuy();
                return true;
            }
        }
        for (float[] hit : enchantHits) {
            if (Render2DEngine.isHovered((int) mx, (int) my, hit[0], hit[1], hit[2], hit[3])) {
                String spec = entry.getEnchants().get((int) hit[4]);
                // right-click walks the cycle backwards, so overshooting a state is not three more clicks
                if (button == GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
                    entry.setState(spec, switch (entry.stateOf(spec)) {
                        case REQUIRED -> RynekBuyEntry.EnchantState.UNWANTED;
                        case UNWANTED -> RynekBuyEntry.EnchantState.ANY;
                        case ANY -> RynekBuyEntry.EnchantState.REQUIRED;
                    });
                } else {
                    entry.cycleState(spec);
                }
                RynekConfigManager.getInstance().saveBuy();
                return true;
            }
        }
        return true; // clicks anywhere on the panel stay in the panel
    }

    /**
     * @return true when the editor wants to be closed — and closing from the keyboard always means
     * CANCEL. Enter and Escape both back out without adding anything; the button is the only thing
     * that commits an offer.
     */
    public boolean keyPressed(int key) {
        if (key == GLFW.GLFW_KEY_ESCAPE || key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER)
            return true;
        if (key == GLFW.GLFW_KEY_TAB) {
            focus = (focus + 1) % 3;
            return false;
        }
        if (key == GLFW.GLFW_KEY_BACKSPACE) {
            String cur = focused();
            if (!cur.isEmpty()) setFocused(cur.substring(0, cur.length() - 1));
            return false;
        }
        // Digits are read here rather than from charTyped: HandledScreen does not declare charTyped at
        // all on this version, so over the open market there is no such callback to hook. Every field
        // here only ever takes a number, so a key-code mapping loses nothing.
        int digit = digitOf(key);
        if (digit >= 0) {
            String cur = focused();
            if (cur.length() < 12) setFocused(cur + (char) ('0' + digit));
        }
        return false;
    }

    private String focused() {
        return switch (focus) {
            case 1 -> daysText;
            case 2 -> percentText;
            default -> priceText;
        };
    }

    private void setFocused(String v) {
        switch (focus) {
            case 1 -> daysText = v;
            case 2 -> percentText = v;
            default -> priceText = v;
        }
    }

    /** 0-9 from the number row or the keypad; -1 for anything else. */
    private static int digitOf(int key) {
        if (key >= GLFW.GLFW_KEY_0 && key <= GLFW.GLFW_KEY_9) return key - GLFW.GLFW_KEY_0;
        if (key >= GLFW.GLFW_KEY_KP_0 && key <= GLFW.GLFW_KEY_KP_9) return key - GLFW.GLFW_KEY_KP_0;
        return -1;
    }

    /** Drawn with the model the server had on it, so a re-skinned item is not shown as its base item. */
    private static ItemStack iconFor(RynekBuyEntry entry) {
        ItemStack stack = entry.toIconStack();
        return stack.isEmpty() ? new ItemStack(Items.PAPER) : stack;
    }

    private static String trim(String s, float maxW) {
        if (s == null) return "";
        if (FontRenderers.sf_medium.getStringWidth(s) <= maxW) return s;
        while (s.length() > 1 && FontRenderers.sf_medium.getStringWidth(s + "...") > maxW)
            s = s.substring(0, s.length() - 1);
        return s + "...";
    }
}
