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
    @Inject(method = "tick", at = @At("TAIL"), cancellable = true)
    private void onSneak(CallbackInfo ci) {
        if(Module.fullNullCheck()) return;
        EventKeyboardInput event = new EventKeyboardInput();
        ThunderHack.EVENT_BUS.post(event);
        if (event.isCancelled()) ci.cancel();
    }
}
