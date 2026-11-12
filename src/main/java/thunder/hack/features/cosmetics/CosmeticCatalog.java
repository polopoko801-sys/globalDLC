package thunder.hack.features.cosmetics;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;

public final class CosmeticCatalog {
    private static final Map<CosmeticSlot, List<CosmeticEntry>> ENTRIES = load();
    private CosmeticCatalog() { }

    public static List<CosmeticEntry> entries(CosmeticSlot slot) {
        return ENTRIES.getOrDefault(slot, List.of());
    }

    public static CosmeticEntry find(CosmeticSlot slot, String id) {
        if (id == null || slot == null) return null;
        for (var entry : entries(slot)) if (entry.id().equals(id)) return entry;
        return null;
    }

    private static Map<CosmeticSlot, List<CosmeticEntry>> load() {
        var result = new EnumMap<CosmeticSlot, List<CosmeticEntry>>(CosmeticSlot.class);
        for (var slot : CosmeticSlot.values()) result.put(slot, new ArrayList<>());
        try (var stream = CosmeticCatalog.class.getResourceAsStream("/assets/bkclient/cosmetics/catalog.json")) {
            if (stream == null) throw new IllegalStateException("Missing cosmetics catalog");
            // Read by hand: record accessors are renamed by the obfuscator, so Gson cannot bind a record.
            var entries = JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonArray();
            var ids = new HashSet<String>();
            for (JsonElement element : entries) {
                var entry = parse(element.getAsJsonObject());
                if (entry.slot() == null || entry.id() == null || !entry.id().matches("[a-z0-9_]+")
                        || entry.name() == null || entry.name().isBlank() || !Float.isFinite(entry.scale()) || entry.scale() <= 0
                        || !Float.isFinite(entry.offsetY()) || !Float.isFinite(entry.offsetZ())
                        || !validPath(entry.model()) || !validPath(entry.texture())
                        || (entry.animation() != null && !validPath(entry.animation()))
                        || !ids.add(entry.slot() + ":" + entry.id())) throw new IllegalArgumentException("Invalid cosmetic catalog entry");
                result.get(entry.slot()).add(entry);
            }
        } catch (Exception failure) {
            System.err.println("[GlobalDLC Cosmetics] Cannot load catalog: " + failure);
            for (var slot : CosmeticSlot.values()) result.get(slot).clear();
        }
        result.replaceAll((slot, list) -> List.copyOf(list));
        return Collections.unmodifiableMap(result);
    }

    private static CosmeticEntry parse(JsonObject json) {
        return new CosmeticEntry(text(json, "id"), text(json, "name"), slot(text(json, "slot")),
                text(json, "model"), text(json, "texture"), text(json, "animation"),
                number(json, "scale", 1.0F), number(json, "offsetY", 0.0F), number(json, "offsetZ", 0.0F));
    }

    private static String text(JsonObject json, String key) {
        var value = json.get(key);
        return value == null || value.isJsonNull() ? null : value.getAsString();
    }

    private static float number(JsonObject json, String key, float fallback) {
        var value = json.get(key);
        return value == null || value.isJsonNull() ? fallback : value.getAsFloat();
    }

    private static CosmeticSlot slot(String name) {
        if (name == null) return null;
        for (var slot : CosmeticSlot.values()) if (slot.name().equals(name)) return slot;
        return null;
    }

    private static boolean validPath(String path) {
        return path != null && path.startsWith("cosmetics/") && !path.contains("..")
                && path.matches("[a-z0-9_./-]+");
    }
}
