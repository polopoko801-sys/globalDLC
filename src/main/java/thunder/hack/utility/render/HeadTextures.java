package thunder.hack.utility.render;

import com.mojang.authlib.GameProfile;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.util.DefaultSkinHelper;
import net.minecraft.entity.player.SkinTextures;
import net.minecraft.util.Identifier;
import net.minecraft.util.Uuids;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Player heads outside of a world. The session {@link GameProfile} carries no texture properties, so the
 * full profile has to be pulled from the session server on a background thread; until that lands (and for
 * offline accounts) the default skin derived from the offline UUID is used, so there is never a gap.
 */
public final class HeadTextures {
    private static final Map<String, Supplier<SkinTextures>> CACHE = new ConcurrentHashMap<>();
    private static final Set<String> LOADING = ConcurrentHashMap.newKeySet();

    private HeadTextures() {
    }

    /** Skin of the account we are currently logged in on. */
    public static Identifier session() {
        MinecraftClient mc = MinecraftClient.getInstance();
        String name = mc.getSession() != null ? mc.getSession().getUsername() : "Player";
        return of(name, mc.getGameProfile());
    }

    /**
     * @param profile pass the real profile to get the real skin (only works for the signed-in account);
     *                {@code null} falls back to the default skin for that name
     */
    public static Identifier of(String name, GameProfile profile) {
        try {
            MinecraftClient mc = MinecraftClient.getInstance();
            String current = mc.getSession() != null ? mc.getSession().getUsername() : null;

            if (profile != null && name.equalsIgnoreCase(current)) {
                Supplier<SkinTextures> supplier = CACHE.get(name.toLowerCase());
                if (supplier != null) return supplier.get().body().texturePath();
                request(name, profile);
            }
            return DefaultSkinHelper.getSkinTextures(Uuids.getOfflinePlayerUuid(name)).body().texturePath();
        } catch (Exception ignored) {
            return null;
        }
    }

    /** Call after switching accounts so the next frame re-resolves the skin. */
    public static void invalidate() {
        CACHE.clear();
        LOADING.clear();
    }

    /** Skin of any player in the world: from the tab list when the server sent it, default skin otherwise. */
    public static Identifier ofPlayer(net.minecraft.entity.player.PlayerEntity player) {
        try {
            MinecraftClient mc = MinecraftClient.getInstance();
            if (mc.getNetworkHandler() != null) {
                var entry = mc.getNetworkHandler().getPlayerListEntry(player.getUuid());
                if (entry != null) return entry.getSkinTextures().body().texturePath();
            }
            return DefaultSkinHelper.getSkinTextures(player.getUuid()).body().texturePath();
        } catch (Exception ignored) {
            return null;
        }
    }

    private static void request(String name, GameProfile base) {
        if (!LOADING.add(name.toLowerCase())) return;
        CompletableFuture.runAsync(() -> {
            try {
                MinecraftClient mc = MinecraftClient.getInstance();
                var result = mc.getApiServices().sessionService().fetchProfile(base.id(), false);
                GameProfile full = result != null ? result.profile() : base;
                CACHE.put(name.toLowerCase(), mc.getSkinProvider().supplySkinTextures(full, false));
            } catch (Throwable ignored) {
            }
        });
    }

    /** Face + hat layer of a 64x64 skin. */
    public static void draw(DrawContext ctx, Identifier skin, float x, float y, float size) {
        if (skin == null) return;
        Render2DEngine.renderTexture(ctx, skin, x, y, size, size, 8, 8, 8, 8, 64, 64);
        Render2DEngine.renderTexture(ctx, skin, x, y, size, size, 40, 8, 8, 8, 64, 64);
    }
}
