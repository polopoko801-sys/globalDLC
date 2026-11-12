package thunder.hack.injection;

import net.minecraft.client.render.entity.PlayerEntityRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import thunder.hack.features.cosmetics.render.CosmeticFeatureRenderer;
import thunder.hack.injection.accesors.ILivingEntityRendererFeatures;

/** Backpacks and wings ride on the player model as one extra feature (ported from BKClient, MIT). */
@Mixin(PlayerEntityRenderer.class)
public abstract class MixinCosmeticPlayerRenderer {
    @Inject(method = "<init>", at = @At("TAIL"))
    private void gombal$cosmetics(CallbackInfo ci) {
        ((ILivingEntityRendererFeatures) (Object) this).gombal$addFeature(new CosmeticFeatureRenderer((PlayerEntityRenderer<?>) (Object) this));
    }
}
