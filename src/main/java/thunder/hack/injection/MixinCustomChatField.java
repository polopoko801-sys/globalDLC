package thunder.hack.injection;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.TextFieldWidget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import thunder.hack.features.hud.impl.CustomChat;

/** The vanilla chat field is drawn by the custom chat in BK's style, so its own drawing is skipped. */
@Mixin(TextFieldWidget.class)
public class MixinCustomChatField {
    @Inject(method = "renderWidget", at = @At("HEAD"), cancellable = true)
    private void gombal$hideVanillaField(DrawContext context, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        if (CustomChat.hidesField((TextFieldWidget) (Object) this)) ci.cancel();
    }
}
