package thunder.hack.injection;

import net.minecraft.block.BlockState;
import net.minecraft.block.SweetBerryBushBlock;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import thunder.hack.core.manager.client.ModuleManager;

@Mixin(SweetBerryBushBlock.class)
public class MixinSweetBerryBushBlock {
    // 1.21.11: onEntityCollision gained (EntityCollisionHandler, boolean) params
    @Inject(method = "onEntityCollision", at = @At("HEAD"), cancellable = true)
    public void onEntityCollisionHook(BlockState state, World world, BlockPos pos, Entity entity, net.minecraft.entity.EntityCollisionHandler handler, boolean bl, CallbackInfo ci) {
        if(ModuleManager.noSlow.isEnabled() && ModuleManager.noSlow.sweetBerryBush.getValue())
            ci.cancel();
    }
}
