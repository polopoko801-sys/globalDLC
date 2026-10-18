package thunder.hack.injection;

import thunder.hack.core.manager.client.ModuleManager;
import net.minecraft.client.gui.hud.InGameOverlayRenderer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.texture.Sprite;
import net.minecraft.client.util.math.MatrixStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(InGameOverlayRenderer.class)
public class MixinInGameOverlayRenderer {
    // 1.21.11: renderFireOverlay(MatrixStack, VertexConsumerProvider, Sprite).
    @Inject(method = "renderFireOverlay", at = @At("HEAD"), cancellable = true)
    private static void renderFireOverlayHook(MatrixStack matrices, VertexConsumerProvider vertexConsumers, Sprite sprite, CallbackInfo ci) {
        if (ModuleManager.noRender.isEnabled() && ModuleManager.noRender.fireOverlay.getValue()) {
            ci.cancel();
            return;
        }
        // LowFire module: push the fire overlay down the screen so it covers less of your view.
        if (ModuleManager.lowFire.isEnabled()) {
            matrices.push();
            matrices.translate(0f, -ModuleManager.lowFire.down.getValue(), 0f);
        }
    }

    @Inject(method = "renderFireOverlay", at = @At("RETURN"))
    private static void renderFireOverlayReturn(MatrixStack matrices, VertexConsumerProvider vertexConsumers, Sprite sprite, CallbackInfo ci) {
        if (ModuleManager.lowFire.isEnabled()
                && !(ModuleManager.noRender.isEnabled() && ModuleManager.noRender.fireOverlay.getValue()))
            matrices.pop();
    }

    // 1.21.11: signatures gained a VertexConsumerProvider param; hooks only cancel, so capture none.
    @Inject(method = "renderUnderwaterOverlay", at = @At("HEAD"), cancellable = true)
    private static void renderUnderwaterOverlayHook(CallbackInfo ci) {
        if (ModuleManager.noRender.isEnabled() && ModuleManager.noRender.waterOverlay.getValue() || ModuleManager.shaders.isEnabled())
            ci.cancel();
    }

    @Inject(method = "renderInWallOverlay", at = @At("HEAD"), cancellable = true)
    private static void renderInWallOverlayHook(CallbackInfo ci) {
        if (ModuleManager.noRender.isEnabled() && ModuleManager.noRender.blockOverlay.getValue() || ModuleManager.shaders.isEnabled())
            ci.cancel();
    }
}