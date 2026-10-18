package thunder.hack.injection;

import net.minecraft.client.render.WorldRenderer;
import net.minecraft.client.render.chunk.ChunkBuilder;
import net.minecraft.util.math.BlockPos;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.Redirect;
import thunder.hack.core.manager.client.ModuleManager;

/**
 * ChunkAnimation hooks. {@code renderBlockLayers} walks the visible sections, reads each one's origin, and
 * builds its per-section uniform from a COPY of the model-view matrix - so translating that copy moves one
 * section and nothing else, which is exactly what Celestial's GlStateManager.translate did per RenderChunk.
 */
@Mixin(WorldRenderer.class)
public abstract class MixinWorldRendererChunks {
    @Unique
    private float thChunkOffset;

    @Redirect(method = "renderBlockLayers", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/render/chunk/ChunkBuilder$BuiltChunk;getOrigin()Lnet/minecraft/util/math/BlockPos;"))
    private BlockPos thTrackChunk(ChunkBuilder.BuiltChunk chunk) {
        BlockPos origin = chunk.getOrigin();
        thChunkOffset = 0f;
        if (ModuleManager.chunkAnimation != null && ModuleManager.chunkAnimation.isEnabled()) {
            ModuleManager.chunkAnimation.onChunkRender(chunk, origin);
            thChunkOffset = ModuleManager.chunkAnimation.chunkOffset(chunk, origin);
        }
        return origin;
    }

    @ModifyArg(method = "renderBlockLayers", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gl/DynamicUniforms$ChunkSectionsValue;<init>(Lorg/joml/Matrix4fc;IIIFII)V"), index = 0)
    private Matrix4fc thOffsetChunk(Matrix4fc modelView) {
        if (thChunkOffset == 0f) return modelView;
        return new Matrix4f(modelView).translate(0f, thChunkOffset, 0f);
    }
}
