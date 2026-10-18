package thunder.hack.features.modules.misc.rynek;

import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;

/**
 * A single auto-sell entry: which item to sell, how many per "/wystaw", and an optional
 * reference price used by the HUD / tooltip. Kept as a plain mutable bean so GSON can
 * (de)serialize it straight into the config profiles managed by {@link RynekConfigManager}.
 */
public class RynekItem {
    // stored as a registry id string, e.g. "minecraft:diamond"
    private String name;
    private int sellAmount;
    private int sellPrice;

    public RynekItem(String name, int sellAmount, int sellPrice) {
        this.name = name;
        this.sellAmount = sellAmount;
        this.sellPrice = sellPrice;
    }

    public RynekItem(Item item, int sellAmount, int sellPrice) {
        this(Registries.ITEM.getId(item).toString(), sellAmount, sellPrice);
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public int getSellAmount() {
        return sellAmount;
    }

    public void setSellAmount(int sellAmount) {
        this.sellAmount = Math.max(1, sellAmount);
    }

    public int getSellPrice() {
        return sellPrice;
    }

    public void setSellPrice(int sellPrice) {
        this.sellPrice = Math.max(0, sellPrice);
    }

    /** Resolves the stored registry id back to a real {@link Item} (AIR if unknown). */
    public Item getItem() {
        Identifier id = Identifier.tryParse(name);
        if (id == null) return Items.AIR;
        return Registries.ITEM.get(id);
    }
}
