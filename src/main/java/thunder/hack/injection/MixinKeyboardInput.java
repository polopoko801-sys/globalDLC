package thunder.hack.injection;

import net.minecraft.client.input.KeyboardInput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import thunder.hack.ThunderHack;
import thunder.hack.events.impl.EventKeyboardInput;
import thunder.hack.features.modules.Module;

@Mixin(KeyboardInput.class)
public class MixinKeyboardInput {
    // 1.21.11: KeyboardInput.tick() is now no-arg and the old boolean fields (sneaking, etc.)
    // moved into a PlayerInput record. Fire at TAIL, after movementVector is computed, so the
    // event consumers' movementVector edits take effect.
    /** KillEffect cinematic: while the scene plays your keys do nothing - no walking off, no jumping, no sneaking. */
    @Inject(method = "tick", at = @At("TAIL"))
    private void freezeInCinematic(CallbackInfo ci) {
        if (!thunder.hack.features.modules.render.KillEffect.cinematicActive()) return;
        net.minecraft.client.input.Input in = (net.minecraft.client.input.Input) (Object) this;
        in.playerInput = net.minecraft.util.PlayerInput.DEFAULT;
        in.movementVector = net.minecraft.util.math.Vec2f.ZERO;
    }

    @Inject(method = "tick", at = @At("TAIL"), cancellable = true)
    private void onSneak(CallbackInfo ci) {
        if(Module.fullNullCheck()) return;
        EventKeyboardInput event = new EventKeyboardInput();
        ThunderHack.EVENT_BUS.post(event);
        if (event.isCancelled()) ci.cancel();
    }
}
