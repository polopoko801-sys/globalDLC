package thunder.hack.setting.impl;

import net.minecraft.block.Block;
import net.minecraft.item.Item;

import java.util.List;

public class ItemSelectSetting {
    /**
     * What the picker is allowed to offer.
     *
     * <p>Nearly every block is also an item, so a picker showing both registries lists most things
     * twice: search "diamond_ore" and two identical-looking rows come back, one from
     * {@code Registries.BLOCK} and one from {@code Registries.ITEM}. Saying up front which of the two a
     * module actually wants removes the duplicate rather than trying to spot and merge it.
     */
    public enum Kind {
        /** Placeable / breakable blocks only - BlockESP, Nuker, the trap modules. */
        Blocks,
        /** Inventory items only - ChestStealer, InventoryCleaner. */
        Items,
        /** Both registries. Only for a setting that genuinely means either. */
        Both
    }

    private List<String> itemsById;
    private final Kind kind;

    public ItemSelectSetting(List<String> itemsById) {
        this(itemsById, Kind.Both);
    }

    public ItemSelectSetting(List<String> itemsById, Kind kind) {
        this.itemsById = itemsById;
        this.kind = kind;
    }

    public Kind getKind() {
        return kind;
    }

    public List<String> getItemsById() {
        return itemsById;
    }

    public void add(String s) {
        itemsById.add(s);
    }

    public void remove(String s) {
        itemsById.remove(s);
    }

    public boolean contains(String s) {
        return itemsById.contains(s);
    }

    public void add(Block b) {
        add(b.getTranslationKey().replace("block.minecraft.", ""));
    }

    public void add(Item i) {
        add(i.getTranslationKey().replace("item.minecraft.", ""));
    }

    public void remove(Block b) {
        remove(b.getTranslationKey().replace("block.minecraft.", ""));
    }

    public void remove(Item i) {
        remove(i.getTranslationKey().replace("item.minecraft.", ""));
    }

    public boolean contains(Block b) {
        return contains(b.getTranslationKey().replace("block.minecraft.", ""));
    }

    public boolean contains(Item i) {
        return contains(i.getTranslationKey().replace("item.minecraft.", ""));
    }

    public void clear() {
        itemsById.clear();
    }
}
