package thunder.hack.injection;

import net.minecraft.client.render.command.OrderedRenderCommandQueue;
import net.minecraft.client.render.entity.feature.CapeFeatureRenderer;
import net.minecraft.client.render.entity.state.PlayerEntityRenderState;
import net.minecraft.client.util.math.MatrixStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import thunder.hack.features.modules.client.GombalCape;

// 1.21.11: render pipeline moved to the render-state model — CapeFeatureRenderer.render is now
// render(MatrixStack, OrderedRenderCommandQueue, int, PlayerEntityRenderState, float, float), and the
// interpolated cape/body-yaw values live in PlayerEntityRenderState (populated in updateRenderState).
// The old @ModifyVariable STORE-ordinal cape-swing tweak no longer has a matching local, so it stays
// disabled pending the render-state rewrite (cosmetic only — cape falls back to vanilla animation).
@Mixin(CapeFeatureRenderer.class)
public class MixinCapeFeatureRenderer {
    // Vanilla bails out at the head of render() when the cape part is hidden or the profile has no
    // cape, so the swap has to happen before that — the render state is rebuilt every frame anyway.
    @Inject(method = "render(Lnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/command/OrderedRenderCommandQueue;ILnet/minecraft/client/render/entity/state/PlayerEntityRenderState;FF)V", at = @At("HEAD"))
    private void applyCustomCapeHook(MatrixStack matrices, OrderedRenderCommandQueue queue, int light, PlayerEntityRenderState state, float limbAngle, float limbDistance, CallbackInfo ci) {
        GombalCape.apply(state);
    }
}
