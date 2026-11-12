package thunder.hack.injection;

import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ChatScreen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import thunder.hack.features.hud.HudElement;
import thunder.hack.features.hud.impl.CustomChat;
import thunder.hack.gui.hud.ChatHudPopup;

/**
 * BKClient's Custom Chat ChatScreen hooks (MIT, bkubiakdev): the field is drawn by the custom chat, clicks and
 * scrolls go to it. Plus the HUD popup: a right click anywhere that is not the chat opens it.
 */
@Mixin(ChatScreen.class)
public abstract class MixinCustomChatScreen {
    @Shadow
    protected TextFieldWidget chatField;

    @Inject(method = "render", at = @At("HEAD"))
    private void gombal$bind(DrawContext context, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        CustomChat.bind((ChatScreen) (Object) this, chatField);
    }

    @Inject(method = "render", at = @At("TAIL"))
    private void gombal$popup(DrawContext context, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        ChatHudPopup.render(context, mouseX, mouseY);
    }

    @Inject(method = "removed", at = @At("TAIL"))
    private void gombal$closePopup(CallbackInfo ci) {
        ChatHudPopup.close();
    }

    @WrapWithCondition(method = "render", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/DrawContext;fill(IIIII)V"))
    private boolean gombal$hideFieldBackground(DrawContext context, int x1, int y1, int x2, int y2, int color) {
        return !CustomChat.active();
    }

    @Inject(method = "sendMessage", at = @At("HEAD"))
    private void gombal$sendSound(String text, boolean addToHistory, CallbackInfo ci) {
        CustomChat.onSend(text);
    }

    @Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true)
    private void gombal$click(Click click, boolean doubled, CallbackInfoReturnable<Boolean> cir) {
        if (ChatHudPopup.click(click.x(), click.y(), click.button())) {
            cir.setReturnValue(true);
            return;
        }
        if (CustomChat.click(click.x(), click.y(), click.button())) {
            cir.setReturnValue(true);
            return;
        }
        // a right click that lands on neither the chat nor a HUD element opens the HUD popup there
        if (click.button() == 1 && !overChat(click.x(), click.y()) && !HudElement.anyHovered) {
            ChatHudPopup.open(click.x(), click.y());
            cir.setReturnValue(true);
        }
    }

    private static boolean overChat(double mx, double my) {
        var mc = net.minecraft.client.MinecraftClient.getInstance();
        float sh = mc.getWindow().getScaledHeight();
        if (my >= sh - 16) return true; // the input line
        if (CustomChat.active()) return CustomChat.covers(mx, my);
        // vanilla's chat box: the bottom-left corner up to its height
        int w = net.minecraft.client.gui.hud.ChatHud.getWidth(mc.options.getChatWidth().getValue());
        int h = net.minecraft.client.gui.hud.ChatHud.getHeight(mc.options.getChatHeightFocused().getValue());
        return mx <= w + 8 && my >= sh - 40 - h;
    }

    @Inject(method = "mouseScrolled", at = @At("HEAD"), cancellable = true)
    private void gombal$scroll(double mouseX, double mouseY, double horizontal, double vertical, CallbackInfoReturnable<Boolean> cir) {
        if (ChatHudPopup.scroll(mouseX, mouseY, vertical) || CustomChat.scroll(mouseX, mouseY, vertical)) cir.setReturnValue(true);
    }
}
