package thunder.hack.injection.accesors;

import net.minecraft.client.render.model.BakedModelManager;
import net.minecraft.client.texture.AtlasManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * The atlas manager is the only {@link net.minecraft.client.texture.SpriteHolder} the client keeps, and
 * a {@link net.minecraft.client.util.SpriteIdentifier} needs one to resolve into a vertex consumer.
 * Block-entity models (chest, shulker, bed) are drawn off entity atlases, so the real-model renderer
 * cannot draw them without it.
 */
@Mixin(BakedModelManager.class)
public interface IBakedModelManager {
    @Accessor("atlasManager")
    AtlasManager thAtlasManager();
}
