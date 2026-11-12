package thunder.hack.features.cosmetics.render;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.command.OrderedRenderCommandQueue;
import net.minecraft.client.render.entity.state.EntityRenderState;
import net.minecraft.client.render.entity.state.PlayerEntityRenderState;
import net.minecraft.client.util.math.MatrixStack;
import org.joml.Matrix4f;
import thunder.hack.features.cosmetics.*;

import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Replaces the local player's body with a character model. Ported from BKClient (MIT, bkubiakdev); 1.21.11 renamed
 * the render-state fields (limbSwingAmplitude, relativeHeadYaw) and draws through the command queue.
 */
public final class CharacterCosmeticRenderer {
    private static final Map<BedrockModel,CharacterPose.Fit> FITS = new IdentityHashMap<>();
    public static void reset() { FITS.clear(); }

    public static CosmeticEntry selected(EntityRenderState state) {
        var client = MinecraftClient.getInstance();
        if (!(state instanceof PlayerEntityRenderState player) || client.player == null
                || player.id != client.player.getId() || player.spectator || state.invisible) return null;
        var entry = CosmeticManager.getInstance().equipped(CosmeticSlot.CHARACTER);
        return entry != null && CosmeticRenderer.available(entry) ? entry : null;
    }

    public static void render(CosmeticEntry entry, PlayerEntityRenderState state, MatrixStack matrices,
            OrderedRenderCommandQueue queue, int light, int overlay, int color) {
        var model = CosmeticRenderer.model(entry);
        var fit = FITS.computeIfAbsent(model, CharacterPose::fit);
        float seconds = state.age / 20f;
        var pose = CharacterPose.sample(model, CosmeticRenderer.animations(entry), seconds,
                state.limbSwingAmplitude, state.handSwingProgress, state.relativeHeadYaw, state.pitch);
        // Undo the vanilla feet offset and convert the model's upward Y axis.
        // Body yaw, swimming, gliding, sleeping and death rotation are already in the stack.
        Matrix4f transform = new Matrix4f(matrices.peek().getPositionMatrix())
                .translate(0, 1.501f - (state.isInSneakingPose ? .15f : 0), 0)
                .rotateZ((float)Math.PI).scale(fit.scale()).translate(0, -fit.floor(), 0);
        CosmeticSink.Recorder sink = CosmeticSink.queued();
        CosmeticRenderer.renderCharacter(entry, transform, sink, light, overlay, color, pose, seconds);
        sink.flush(queue);
    }
    private CharacterCosmeticRenderer() { }
}
