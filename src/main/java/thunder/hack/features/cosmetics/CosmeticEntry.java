package thunder.hack.features.cosmetics;

/** Resource paths are relative to assets/bkclient; animation can be absent. */
public record CosmeticEntry(String id, String name, CosmeticSlot slot, String model,
        String texture, String animation, float scale, float offsetY, float offsetZ) { }
