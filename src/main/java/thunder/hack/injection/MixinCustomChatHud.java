package thunder.hack.injection;

import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.hud.ChatHud;
import net.minecraft.client.gui.hud.MessageIndicator;
import net.minecraft.network.message.MessageSignatureData;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import thunder.hack.features.hud.impl.CustomChat;

/** BKClient's Custom Chat ChatHud hooks (MIT, bkubiakdev): every message is captured, vanilla's lines are hidden. */
@Mixin(ChatHud.class)
public class MixinCustomChatHud {
    @Inject(method = "addMessage(Lnet/minecraft/text/Text;Lnet/minecraft/network/message/MessageSignatureData;Lnet/minecraft/client/gui/hud/MessageIndicator;)V",
            at = @At("TAIL"))
    private void gombal$capture(Text message, MessageSignatureData signature, MessageIndicator indicator, CallbackInfo ci) {
        CustomChat.captureMessage(message);
    }

    @Inject(method = "render(Lnet/minecraft/client/gui/DrawContext;Lnet/minecraft/client/font/TextRenderer;IIIZZ)V",
            at = @At("HEAD"), cancellable = true)
    private void gombal$hide(DrawContext context, TextRenderer renderer, int tick, int mouseX, int mouseY, boolean focused,
                             boolean insert, CallbackInfo ci) {
        if (CustomChat.active()) ci.cancel();
    }
}
