package thunder.hack.features.modules.misc.rynek;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.util.Formatting;

import static thunder.hack.features.modules.Module.mc;

/**
 * The offer editor drawn ON TOP of the open {@code /ah}, never as a screen of its own.
 * <p>
 * That distinction is the entire feature. Swapping the market for another Screen makes vanilla fire
 * {@code HandledScreen.removed()}, which sends CloseHandledScreen; the server then closes the market,
 * and Escape out of the editor lands you in the world with the page you were reading gone. Kept as an
 * overlay, the market stays open and untouched underneath — Escape just puts the editor away and you
 * are still on the same page, scrolled where you left it.
 */
public final class RynekOfferOverlay {
    private static RynekOfferEditor editor;

    private RynekOfferOverlay() {
    }

    public static boolean isOpen() {
        return editor != null;
    }

    /**
     * Opens the editor for the market listing under the cursor. An offer for that item already in the
     * config is reused rather than duplicated — middle-clicking the same boots twice should correct
     * the price you set, not stack up a second identical rule.
     */
    public static void open(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return;
        dump(stack);
        String id = Registries.ITEM.getId(stack.getItem()).toString();

        // Match on item id AND display name. Matching on the id alone meant middle-clicking
        // "Anarchiczny hełm II" reopened the old "Anarchiczny hełm" rule — same netherite_helmet — and
        // showed its stale protection 5, even though the item under the cursor reads protection 6.
        // Two different tiers are two different offers.
        String display = Formatting.strip(stack.getName().getString());
        RynekBuyEntry entry = null;
        for (RynekBuyEntry e : RynekConfigManager.getInstance().getBuyEntries())
            if (id.equals(e.getName()) && sameDisplay(display, e.getDisplayName())) {
                entry = e;
                break;
            }

        // A brand-new offer is NOT written to the config here. It is held by the editor and only lands
        // in buywatch.json when the "Dodaj" button is pressed — Escape out of a mis-clicked listing has
        // to leave no rule behind.
        boolean fresh = entry == null;
        if (fresh) {
            entry = RynekBuyEntry.fromStack(stack);
        } else {
            // Same item, clicked again: refresh what was READ off it (enchants, text) while keeping what
            // the user DECIDED (price, per-line states, day/percent limits).
            entry.refreshFrom(stack);
            RynekConfigManager.getInstance().saveBuy();
        }
        // the LIVE stack goes into the editor, so its icon is the market's own item, pack and all
        editor = new RynekOfferEditor(entry, fresh, stack);
    }

    /**
     * Writes everything the client knows about the item being captured into the log: the enchantment
     * component, a book's stored enchantments, and every lore line verbatim.
     * <p>
     * "Ana bania 2 shows protection 5 but has 6" can have exactly two causes — the component really says
     * 5 and the 6 is only written in the text, or the text is worded in a way the parser does not
     * recognise. Guessing between them from the outside has cost several rounds; this says which it is.
     */
    private static void dump(ItemStack stack) {
        try {
            StringBuilder sb = new StringBuilder("[McRynek] capture \"")
                    .append(net.minecraft.util.Formatting.strip(stack.getName().getString()))
                    .append("\" item=").append(Registries.ITEM.getId(stack.getItem()));
            // What the resource pack keys its re-skin off. "Dynamit shows a candle" can only be answered
            // by knowing which of these the server actually set — guessing has cost a round already.
            sb.append("\n  item_model=").append(stack.get(net.minecraft.component.DataComponentTypes.ITEM_MODEL));
            sb.append("\n  custom_model_data=").append(stack.get(net.minecraft.component.DataComponentTypes.CUSTOM_MODEL_DATA));
            sb.append("\n  components=").append(stack.getComponentChanges());
            sb.append("\n  enchants(component)=")
                    .append(net.minecraft.enchantment.EnchantmentHelper.getEnchantments(stack));
            sb.append("\n  enchants(stored)=")
                    .append(stack.get(net.minecraft.component.DataComponentTypes.STORED_ENCHANTMENTS));
            var lore = stack.get(net.minecraft.component.DataComponentTypes.LORE);
            if (lore == null) {
                sb.append("\n  lore=<none>");
            } else {
                for (net.minecraft.text.Text line : lore.lines())
                    sb.append("\n  lore: \"")
                            .append(net.minecraft.util.Formatting.strip(line.getString())).append('"');
            }
            sb.append("\n  parsed=").append(RynekBuyEntry.readEnchants(stack));
            thunder.hack.ThunderHack.LOGGER.info(sb.toString());
        } catch (Exception ignored) {
        }
    }

    private static boolean sameDisplay(String a, String b) {
        if (a == null || b == null) return a == null && b == null;
        return a.trim().equalsIgnoreCase(b.trim());
    }

    /** Back out without adding anything — Escape, and the market window going away under us. */
    public static void close() {
        if (editor != null) editor.cancel();
        editor = null;
    }

    /** The "Dodaj" / "Zapisz" button was pressed: write the offer and put the editor away. */
    public static void confirm() {
        if (editor != null) editor.commit();
        editor = null;
    }

    public static void render(DrawContext context, int mouseX, int mouseY) {
        if (editor == null || mc.getWindow() == null) return;
        float sw = mc.getWindow().getScaledWidth();
        float sh = mc.getWindow().getScaledHeight();
        // pinned to the right so it never covers the listing you are picking from
        float px = Math.max(4f, sw - RynekOfferEditor.WIDTH - 8f);
        float py = Math.max(4f, (sh - editor.height()) / 2f);
        editor.render(context, px, py, mouseX, mouseY);
    }

    public static boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (editor == null) return false;
        boolean handled = editor.mouseClicked(mouseX, mouseY, button);
        if (editor.consumeClose()) confirm();
        return handled;
    }

    public static boolean keyPressed(int key) {
        if (editor == null) return false;
        if (editor.keyPressed(key)) close();
        return true;
    }

}
