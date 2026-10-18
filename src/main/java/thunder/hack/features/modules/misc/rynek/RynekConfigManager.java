package thunder.hack.features.modules.misc.rynek;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Local, singleton config for the McRynek auto-sell list. Persists a single JSON file
 * ({@code config/mcrynek/autosell.json}) holding the {@link RynekItem} entries the module auto-sells.
 * No named profiles, no server sync, no licensing — deliberately a clean re-implementation of the
 * original mod's ConfigManager without its AuthSystem coupling.
 */
public final class RynekConfigManager {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type LIST_TYPE = new TypeToken<ArrayList<RynekItem>>() {}.getType();

    private static RynekConfigManager INSTANCE;

    private static final Type BUY_TYPE = new TypeToken<ArrayList<RynekBuyEntry>>() {}.getType();

    private final Path dir;
    private final Path file;
    private final Path buyFile;
    private List<RynekItem> items = new ArrayList<>();
    private List<RynekBuyEntry> buyEntries = new ArrayList<>();

    private RynekConfigManager() {
        this.dir = FabricLoader.getInstance().getConfigDir().resolve("mcrynek");
        this.file = dir.resolve("autosell.json");
        this.buyFile = dir.resolve("buywatch.json");
        load();
    }

    public static RynekConfigManager getInstance() {
        if (INSTANCE == null) INSTANCE = new RynekConfigManager();
        return INSTANCE;
    }

    public List<RynekItem> getItems() {
        return items;
    }

    public void addItem(RynekItem item) {
        items.add(item);
        save();
    }

    public void removeItem(RynekItem item) {
        items.remove(item);
        save();
    }

    public List<RynekBuyEntry> getBuyEntries() {
        return buyEntries;
    }

    public void addBuyEntry(RynekBuyEntry entry) {
        buyEntries.add(entry);
        saveBuy();
    }

    public void removeBuyEntry(RynekBuyEntry entry) {
        buyEntries.remove(entry);
        saveBuy();
    }

    public void load() {
        items = readList(file, LIST_TYPE);
        buyEntries = readList(buyFile, BUY_TYPE);
    }

    @SuppressWarnings("unchecked")
    private <E> List<E> readList(Path path, Type type) {
        try {
            if (!Files.exists(path)) return new ArrayList<>();
            try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                List<E> loaded = GSON.fromJson(reader, type);
                return loaded != null ? loaded : new ArrayList<>();
            }
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    public void save() {
        writeList(file, items, LIST_TYPE);
    }

    public void saveBuy() {
        writeList(buyFile, buyEntries, BUY_TYPE);
    }

    private void writeList(Path path, Object data, Type type) {
        try {
            Files.createDirectories(dir);
            try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
                GSON.toJson(data, type, writer);
            }
        } catch (IOException ignored) {
        }
    }
}
