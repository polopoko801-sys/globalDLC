package thunder.hack.features.cosmetics;

import thunder.hack.core.manager.client.ConfigManager;

/** Ported from BKClient (MIT, bkubiakdev); selections live in GlobalDLC/cosmetics.json. */
public final class CosmeticManager {
    private final CosmeticPreferences preferences;
    private CosmeticManager() {
        preferences = new CosmeticPreferences(ConfigManager.MAIN_FOLDER.toPath().resolve("cosmetics.json"));
    }
    private static final class Holder { private static final CosmeticManager INSTANCE = new CosmeticManager(); }
    public static CosmeticManager getInstance() { return Holder.INSTANCE; }
    public CosmeticEntry equipped(CosmeticSlot slot) { return preferences.equipped(slot); }
    public void equip(CosmeticEntry entry) { preferences.equip(entry); }
    public void unequip(CosmeticSlot slot) { preferences.unequip(slot); }
    public boolean isEquipped(CosmeticEntry entry) {
        var current = entry == null ? null : equipped(entry.slot());
        return current != null && current.id().equals(entry.id());
    }
    public String persistenceError() { return preferences.persistenceError(); }
}
