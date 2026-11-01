package thunder.hack.injection;

import net.minecraft.client.render.Frustum;
import net.minecraft.client.render.entity.EntityRenderManager;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import thunder.hack.features.modules.render.KillEffect;

/** KillEffect Ninja: a body that has been cut into pieces is not drawn - only its flying pieces are. */
@Mixin(EntityRenderManager.class)
public class MixinEntityRenderManager {
    @Inject(method = "shouldRender", at = @At("HEAD"), cancellable = true)
    private <E extends Entity> void hideSliced(E entity, Frustum frustum, double x, double y, double z, CallbackInfoReturnable<Boolean> cir) {
        if (KillEffect.hideEntity(entity)) cir.setReturnValue(false);
    }
}
