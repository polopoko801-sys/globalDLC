package thunder.hack.injection;

import net.minecraft.client.render.WorldRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import thunder.hack.core.manager.client.ModuleManager;

/**
 * Vanilla's blocky clouds would float across the CustomSky shader sky (they write depth, so the shader leaves
 * them alone), so while CustomSky owns the sky the cloud pass is not added to the frame at all. Module off or
 * Vanilla mode = clouds as usual.
 */
@Mixin(WorldRenderer.class)
public abstract class MixinWorldRendererSky {

    @Inject(method = "renderClouds", at = @At("HEAD"), cancellable = true)
    private void thHideClouds(CallbackInfo ci) {
        if (ModuleManager.customSky != null && ModuleManager.customSky.ownsSky())
            ci.cancel();
    }
}
