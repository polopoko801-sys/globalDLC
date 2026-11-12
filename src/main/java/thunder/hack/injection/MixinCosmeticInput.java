package thunder.hack.injection;

import net.minecraft.client.MinecraftClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import thunder.hack.features.cosmetics.render.MeleeCosmeticRenderer;

/** Swing animations of melee cosmetics follow the attack/use keys (ported from BKClient, MIT). Visual only. */
@Mixin(MinecraftClient.class)
public abstract class MixinCosmeticInput {
    @Inject(method = "doAttack", at = @At("HEAD"))
    private void gombal$primary(CallbackInfoReturnable<Boolean> ci) { MeleeCosmeticRenderer.attack(true); }

    @Inject(method = "doItemUse", at = @At("RETURN"))
    private void gombal$secondary(CallbackInfo ci) { MeleeCosmeticRenderer.attack(false); }
}
