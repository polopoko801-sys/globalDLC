package thunder.hack.features.cosmetics;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.EnumMap;

/**
 * Local selections, independent of module configs and other players.
 *
 * <p>Ported from BKClient (MIT, bkubiakdev). BK wrote through its own SafeJsonFile helper; here the same
 * guarantee - a crash mid-save never leaves a half-written file - comes from writing a temp file and moving it
 * over the old one, with the previous file kept as {@code .bak} for recovery.
 */
public final class CosmeticPreferences {
    private final Path file;
    private final EnumMap<CosmeticSlot, CosmeticEntry> equipped = new EnumMap<>(CosmeticSlot.class);
    private String persistenceError;

    public CosmeticPreferences(Path file) {
        this.file = file;
        Path source = Files.exists(file) ? file : backup(file);
        if (!Files.exists(source)) return;
        try {
            JsonObject saved = JsonParser.parseString(Files.readString(source, StandardCharsets.UTF_8)).getAsJsonObject();
            for (var slot : CosmeticSlot.values()) {
                var value = saved.get(slot.name());
                if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) continue;
                var entry = CosmeticCatalog.find(slot, value.getAsString());
                if (entry != null) equipped.put(slot, entry);
            }
        } catch (Exception failure) { report(failure); }
    }

    public synchronized CosmeticEntry equipped(CosmeticSlot slot) { return equipped.get(slot); }
    public synchronized String persistenceError() { return persistenceError; }

    public synchronized void equip(CosmeticEntry entry) {
        if (entry == null) return;
        var canonical = CosmeticCatalog.find(entry.slot(), entry.id());
        if (canonical == null) throw new IllegalArgumentException("Unknown cosmetic");
        if (equipped.put(entry.slot(), canonical) != canonical) save();
    }

    public synchronized void unequip(CosmeticSlot slot) {
        if (equipped.remove(slot) != null) save();
    }

    private void save() {
        var saved = new JsonObject();
        saved.addProperty("version", 1);
        equipped.forEach((slot, entry) -> saved.addProperty(slot.name(), entry.id()));
        try {
            Files.createDirectories(file.getParent());
            Path temp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(temp, new GsonBuilder().setPrettyPrinting().create().toJson(saved), StandardCharsets.UTF_8);
            if (Files.exists(file)) Files.copy(file, backup(file), StandardCopyOption.REPLACE_EXISTING);
            try {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
            persistenceError = null;
        } catch (Exception failure) { report(failure); }
    }

    private static Path backup(Path file) {
        return file.resolveSibling(file.getFileName() + ".bak");
    }

    private void report(Exception failure) {
        persistenceError = "Could not save or restore cosmetics";
        System.err.println("[GlobalDLC Cosmetics] " + persistenceError + ": " + failure);
    }
}
