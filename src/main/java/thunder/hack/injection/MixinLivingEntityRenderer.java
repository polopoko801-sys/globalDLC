package thunder.hack.injection;

import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.entity.LivingEntityRenderer;
import net.minecraft.client.render.entity.state.LivingEntityRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import thunder.hack.core.manager.client.ModuleManager;
import thunder.hack.core.rotation.PlayerRotationController;

/**
 * Chams on the 1.21.11 render-state pipeline.
 *
 * <p>The old hook (render(T, float, float, MatrixStack, VertexConsumerProvider, int)) is gone: models are
 * submitted to an {@code OrderedRenderCommandQueue} from a render STATE, and the entity is no longer around
 * at render time. What survived are the two overridable questions {@code LivingEntityRenderer.render} asks
 * right before {@code submitModel} - the mix colour the model is multiplied by, and the render layer it is
 * drawn with. Answering those is enough for chams, and it costs no extra draw of the model.
 *
 * <p>Still not ported from the old mixin: ClientSettings.renderRotations, the FreeCam track-entity cancel,
 * and the NoRender / TrueSight alpha overrides.
 */
@Mixin(LivingEntityRenderer.class)
public abstract class MixinLivingEntityRenderer {

    @Inject(method = "getMixColor", at = @At("RETURN"), cancellable = true)
    private void thChamsMixColor(LivingEntityRenderState state, CallbackInfoReturnable<Integer> cir) {
        if (!ModuleManager.chams.isEnabled()) return;

        Integer color = ModuleManager.chams.chamsColor(state);
        if (color != null) cir.setReturnValue(color);
    }

    /**
     * Independent body rotation.
     *
     * <p>The body is written here, on the RENDER STATE, and nowhere else - which is exactly what makes a
     * 360 degree spin safe. The camera reads from the camera controller, an attack reads from the aim
     * controller and a click reads from the camera; none of them look at this field, so turning the model
     * cannot drag the view round with it or change what you are about to interact with.
     */
    @Inject(method = "updateRenderState(Lnet/minecraft/entity/LivingEntity;Lnet/minecraft/client/render/entity/state/LivingEntityRenderState;F)V",
            at = @At("RETURN"))
    private void thBodyRotation(net.minecraft.entity.LivingEntity entity, LivingEntityRenderState state, float tickDelta, org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
        if (net.minecraft.client.MinecraftClient.getInstance().player != entity) return;

        // Silent aim (TargetLock off): show in F5 the rotation the server got, head and body on the target.
        // Render state only, so the camera and your clicks stay yours.
        if (ModuleManager.kilaura != null && ModuleManager.kilaura.showsSilentLook()) {
            state.bodyYaw = ModuleManager.kilaura.silentRenderYaw(tickDelta);
            state.relativeHeadYaw = 0f;
            state.pitch = ModuleManager.kilaura.silentRenderPitch(tickDelta);
        }

        if (!PlayerRotationController.isActive()) return;

        state.bodyYaw = PlayerRotationController.apply(state.bodyYaw);
    }

    @Inject(method = "getRenderLayer", at = @At("RETURN"), cancellable = true)
    private void thChamsLayer(LivingEntityRenderState state, boolean showBody, boolean translucent, boolean showOutline, CallbackInfoReturnable<RenderLayer> cir) {
        // a null layer means vanilla decided not to draw the model at all (invisible) - leave that alone
        if (!ModuleManager.chams.isEnabled() || cir.getReturnValue() == null) return;

        RenderLayer layer = ModuleManager.chams.chamsLayer(state);
        if (layer != null) cir.setReturnValue(layer);
    }
}
