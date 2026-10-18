package thunder.hack.features.modules.misc.rynek;

import java.util.ArrayList;
import java.util.List;

/**
 * Tracks what was bought/sold during the current session so the HUD / GUI can show running totals.
 * Fed by chat parsing in {@link thunder.hack.features.modules.misc.rynek.Rynek} (e.g. "KUPIONO" lines).
 */
public final class SessionStats {
    /**
     * @param icon    what was bought, model components and all, so the HUD draws the server's texture
     *                rather than the base item's — a "Dynamit" is a candle underneath.
     * @param details the item's own text — enchantments and the anarchia lore lines it was listed
     *                with. The server's "KUPIONO" chat line carries none of that, so without it the
     *                HUD could only ever say "1x Anarchiczny hełm" and leave you guessing which tier.
     */
    public record PurchaseEntry(String name, int qty, long price, long time, RynekIcon icon, List<String> details) {
        public PurchaseEntry {
            details = details == null ? List.of() : List.copyOf(details);
            icon = icon == null ? new RynekIcon() : icon;
        }
    }

    private final List<PurchaseEntry> purchases = new ArrayList<>();
    private long spent;
    private long earned;
    private int boughtItems;

    public void addPurchase(String name, int qty, long price) {
        addPurchase(name, qty, price, null, List.of());
    }

    public void addPurchase(String name, int qty, long price, RynekIcon icon, List<String> details) {
        purchases.add(new PurchaseEntry(name, qty, price, System.currentTimeMillis(), icon, details));
        spent += price;
        boughtItems += Math.max(1, qty);
    }

    /** Items bought, counting stack sizes — "5x Diamond" is five things, not one purchase. */
    public int getBoughtItems() {
        return boughtItems;
    }

    public void addSale(long price) {
        earned += price;
    }

    public List<PurchaseEntry> getPurchases() {
        return purchases;
    }

    public int getPurchaseCount() {
        return purchases.size();
    }

    public long getSpent() {
        return spent;
    }

    public long getEarned() {
        return earned;
    }

    public long getProfit() {
        return earned - spent;
    }

    public void reset() {
        purchases.clear();
        spent = 0;
        earned = 0;
        boughtItems = 0;
    }
}
