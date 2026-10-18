package thunder.hack.features.modules.client;

import net.minecraft.client.render.entity.state.PlayerEntityRenderState;
import net.minecraft.entity.player.SkinTextures;
import net.minecraft.util.AssetInfo;
import net.minecraft.util.Identifier;
import thunder.hack.core.manager.client.ModuleManager;
import thunder.hack.features.modules.Module;
import thunder.hack.setting.Setting;

public final class GombalCape extends Module {
    public GombalCape() {
        super("GombalCape", Category.CLIENT);
    }

    public final Setting<Skin> skin = new Setting<>("Cape", Skin.Gombal);
    public final Setting<Boolean> selfOnly = new Setting<>("SelfOnly", true);

    public enum Skin {
        Gombal, Star, Dev, Tester, BkGroup, FbGroup
    }

    private static AssetInfo.TextureAsset asset(String name) {
        final Identifier id = Identifier.of("gombal", "textures/capes/" + name + ".png");
        return new AssetInfo.TextureAsset() {
            @Override
            public Identifier texturePath() {
                return id;
            }

            @Override
            public Identifier id() {
                return id;
            }
        };
    }

    private static final AssetInfo.TextureAsset GOMBAL = asset("gombal");
    private static final AssetInfo.TextureAsset STAR = asset("starcape");
    private static final AssetInfo.TextureAsset DEV = asset("dev");
    private static final AssetInfo.TextureAsset TESTER = asset("tester");
    private static final AssetInfo.TextureAsset BK_GROUP = asset("bkgroup");
    private static final AssetInfo.TextureAsset FB_GROUP = asset("fbgroup");

    private AssetInfo.TextureAsset getCape() {
        return switch (skin.getValue()) {
            case Star -> STAR;
            case Dev -> DEV;
            case Tester -> TESTER;
            case BkGroup -> BK_GROUP;
            case FbGroup -> FB_GROUP;
            default -> GOMBAL;
        };
    }

    /**
     * Swaps the cape of a player render state for our own texture, called from
     * MixinCapeFeatureRenderer right before the vanilla cape would be submitted.
     */
    public static void apply(PlayerEntityRenderState state) {
        final GombalCape module = ModuleManager.gombalCape;
        if (module == null || !module.isEnabled())
            return;

        final SkinTextures textures = state.skinTextures;
        if (textures == null)
            return;

        if (module.selfOnly.getValue() && (mc.player == null || state.id != mc.player.getId()))
            return;

        state.capeVisible = true;
        state.skinTextures = new SkinTextures(textures.body(), module.getCape(), textures.elytra(), textures.model(), textures.secure());
    }
}
