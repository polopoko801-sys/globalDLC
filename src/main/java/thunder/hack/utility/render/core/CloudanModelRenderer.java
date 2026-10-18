package thunder.hack.utility.render.core;

import net.minecraft.block.BlockState;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.NotNull;
import thunder.hack.utility.render.BlockModelRender;

import java.awt.Color;

/**
 * Real Minecraft block models, drawn through the core: culled, LOD-tiered and counted.
 *
 * <p>The actual geometry still comes from {@link BlockModelRender} - baked models for everything, entity
 * models for the handful of blocks vanilla draws that way (chests, shulkers, beds). Wrapping rather than
 * rewriting is deliberate: that class is what the AutoBuild preview, Block ESP and Anchor ESP already
 * draw with, and a second implementation would immediately start disagreeing with the first.
 *
 * <p>What this adds is the part every caller was doing badly on its own: not submitting models that are
 * behind you, not submitting them at full detail from ninety blocks away, and keeping the count honest.
 */
public final class CloudanModelRenderer {
    private CloudanModelRenderer() {
    }

    /** Below this tier a block model is not worth its vertices; the caller can draw a box instead. */
    private static final CloudanCullingSystem.Lod MIN_TIER = CloudanCullingSystem.Lod.Low;

    /**
     * Queues one block model. Buffered - the core flushes at the end of the frame.
     *
     * @param tint        multiplied into the model; white leaves it looking like the real block
     * @param maxDistance beyond this the block is not drawn at all
     * @return the tier it was drawn at, or {@code Skipped} when it was culled
     */
    public static CloudanCullingSystem.Lod submit(@NotNull MatrixStack matrices, @NotNull BlockState state,
                                                  @NotNull BlockPos pos, int alpha, float scale,
                                                  @NotNull Color tint, boolean throughWalls, double maxDistance) {
        if (state.isAir()) return CloudanCullingSystem.Lod.Skipped;

        // A block is at most a unit cube, so its bounding sphere is half a diagonal from the centre.
        CloudanCullingSystem.Lod lod =
                CloudanCullingSystem.evaluate(pos.toCenterPos(), 0.87, maxDistance);

        if (!lod.drawable() || lod.ordinal() > MIN_TIER.ordinal()) return CloudanCullingSystem.Lod.Skipped;

        BlockModelRender.render(matrices, state, pos, alpha, scale,
                tint.getRed() / 255f, tint.getGreen() / 255f, tint.getBlue() / 255f, throughWalls);
        return lod;
    }

    /** The common case: a model at full brightness with no tint. */
    public static CloudanCullingSystem.Lod submit(@NotNull MatrixStack matrices, @NotNull BlockState state,
                                                  @NotNull BlockPos pos, int alpha, boolean throughWalls,
                                                  double maxDistance) {
        return submit(matrices, state, pos, alpha, 1f, Color.WHITE, throughWalls, maxDistance);
    }

    /** True for blocks vanilla draws from an entity model - chests, shulkers, beds. */
    public static boolean isBlockEntityModel(@NotNull BlockState state) {
        return BlockModelRender.isBlockEntityModel(state);
    }

    /** The box a block occupies, for callers that want to outline what they just drew. */
    public static @NotNull Box boxOf(@NotNull BlockPos pos) {
        return new Box(pos);
    }

    /**
     * Pushes everything queued to the GPU. Called by the core when the frame closes; a visual should not
     * need to call it, and calling it mid-frame only costs an extra draw call.
     */
    public static void flush() {
        BlockModelRender.flush();
        CloudanRenderCore.countDrawCall();
    }
}
