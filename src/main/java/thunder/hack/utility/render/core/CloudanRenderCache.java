package thunder.hack.utility.render.core;

import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * A bounded cache for anything a visual would otherwise rebuild every frame - a baked model, a resolved
 * sprite, a computed path.
 *
 * <p>Two things make this worth having over a plain {@link java.util.HashMap}. It is bounded, evicting
 * the least recently used entry instead of growing until the world changes, and every lookup is counted,
 * so the debug overlay can say whether a cache is actually earning its keep. A cache with a 5% hit rate
 * is a memory leak with extra steps, and without the counters nobody ever finds out.
 *
 * <p>Every instance registers itself so a world change can empty all of them at once - a cached block
 * state or entity reference from a world you left is exactly how a client ends up rendering ghosts.
 */
public final class CloudanRenderCache<K, V> {

    private static final List<CloudanRenderCache<?, ?>> REGISTRY = new ArrayList<>();

    private final String name;
    private final int capacity;
    private final Map<K, V> map;

    public CloudanRenderCache(@NotNull String name, int capacity) {
        this.name = name;
        this.capacity = Math.max(1, capacity);

        // accessOrder = true turns LinkedHashMap into an LRU; removeEldestEntry is what bounds it.
        this.map = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
                return size() > CloudanRenderCache.this.capacity;
            }
        };

        synchronized (REGISTRY) {
            REGISTRY.add(this);
        }
    }

    /**
     * The only way in: looks up, and builds the value through {@code loader} on a miss.
     *
     * <p>A separate get/put pair would let a caller record a hit and then not use it, and the counters
     * would stop meaning anything.
     */
    public V get(@NotNull K key, @NotNull Function<K, V> loader) {
        V value = map.get(key);
        if (value != null) {
            CloudanRenderCore.countCacheHit();
            return value;
        }

        CloudanRenderCore.countCacheMiss();
        value = loader.apply(key);
        if (value != null) map.put(key, value);
        return value;
    }

    public void invalidate(@NotNull K key) {
        map.remove(key);
    }

    public void clear() {
        map.clear();
    }

    public int size() {
        return map.size();
    }

    public @NotNull String name() {
        return name;
    }

    /** Empties every cache in the client. Called on world unload, dimension change and SelfDestruct. */
    public static void clearAll() {
        synchronized (REGISTRY) {
            for (CloudanRenderCache<?, ?> cache : REGISTRY) cache.clear();
        }
    }

    public static int totalEntries() {
        int total = 0;
        synchronized (REGISTRY) {
            for (CloudanRenderCache<?, ?> cache : REGISTRY) total += cache.size();
        }
        return total;
    }
}
