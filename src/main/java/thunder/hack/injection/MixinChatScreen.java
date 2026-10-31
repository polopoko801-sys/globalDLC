package thunder.hack.injection;

import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ChatScreen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import thunder.hack.core.manager.client.ModuleManager;
import thunder.hack.features.modules.client.HudEditor;
import thunder.hack.gui.font.FontRenderers;
import thunder.hack.utility.render.Render2DEngine;

import java.awt.Color;
import java.util.Locale;

/**
 * The LearnAI badge in the chat screen: a small "AI" chip, always in the top-left corner. A left click opens the
 * AI chat window; a right click starts an order in the normal chat - the field is filled with "ai " and focused,
 * so you just type what it should do and press Enter. While the message is addressed to it, the chip lights up and a hint shows a few
 * example orders.
 */
@Mixin(ChatScreen.class)
public abstract class MixinChatScreen {
    @Shadow
    protected TextFieldWidget chatField;

    @Unique
    private static final float BADGE_X = 6f, BADGE_Y = 6f, BADGE_W = 30f, BADGE_H = 16f;
    @Unique
    private float thunderHack$glow;
    @Unique
    private long thunderHack$lastFrame;

    @Inject(method = "render", at = @At("TAIL"))
    private void thunderHack$drawAiBadge(DrawContext context, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        long now = System.currentTimeMillis();
        float dt = thunderHack$lastFrame == 0L ? 16f : Math.min(100f, now - thunderHack$lastFrame);
        thunderHack$lastFrame = now;

        boolean addressed = chatField != null && chatField.getText().toLowerCase(Locale.ROOT).startsWith("ai ");
        boolean hovered = Render2DEngine.isHovered(mouseX, mouseY, BADGE_X, BADGE_Y, BADGE_W, BADGE_H);
        float target = addressed ? 1f : hovered ? 0.55f : 0f;
        thunderHack$glow += (target - thunderHack$glow) * Math.min(1f, dt / 90f);
        float g = thunderHack$glow;

        Color accent = HudEditor.getColor(0);
        if (g > 0.02f)
            Render2DEngine.drawRoundSmooth(context, BADGE_X - 3f, BADGE_Y - 3f, BADGE_W + 6f, BADGE_H + 6f, BADGE_H / 2f + 3f,
                    Render2DEngine.injectAlpha(accent, Math.round(70 * g)));
        Render2DEngine.drawRoundSmooth(context, BADGE_X, BADGE_Y, BADGE_W, BADGE_H, BADGE_H / 2f,
                Render2DEngine.interpolateColorC(new Color(18, 18, 26, 220), Render2DEngine.injectAlpha(accent, 235), g));
        // a little spark before the letters
        float sx = BADGE_X + 8f, sy = BADGE_Y + BADGE_H / 2f;
        Render2DEngine.drawRoundSmooth(context, sx - 2f, sy - 2f, 4f, 4f, 2f,
                g > 0.5f ? Color.WHITE : accent);
        FontRenderers.sf_bold_mini.drawString(context, "AI", BADGE_X + 13f, BADGE_Y + 5f, Color.WHITE.getRGB());

        if (addressed || hovered) {
            String hint = addressed
                    ? "Polecenie dla LearnAI - np. walcz, stop, klikaj prawym, serwery, statystyki"
                    : "Lewy: okno rozmowy z AI  ·  prawy: polecenie w czacie";
            float hw = FontRenderers.sf_medium_mini.getStringWidth(hint) + 14f;
            float hx = BADGE_X + BADGE_W + 6f;
            Render2DEngine.drawRoundSmooth(context, hx, BADGE_Y, hw, BADGE_H, 5f, new Color(14, 14, 20, 200));
            FontRenderers.sf_medium_mini.drawString(context, hint, hx + 7f, BADGE_Y + 5.5f, new Color(225, 225, 235).getRGB());
        }
    }

    @Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true)
    private void thunderHack$clickAiBadge(Click click, boolean doubled, CallbackInfoReturnable<Boolean> cir) {
        if (chatField == null) return;
        if (!Render2DEngine.isHovered(click.x(), click.y(), BADGE_X, BADGE_Y, BADGE_W, BADGE_H)) return;
        // left click: the AI chat window
        if (click.button() == 0) {
            net.minecraft.client.MinecraftClient.getInstance().setScreen(new thunder.hack.gui.misc.AiChatScreen());
            cir.setReturnValue(true);
            return;
        }
        // right click: start an order in the normal chat - "ai " in the field, cursor after it
        String text = chatField.getText();
        if (!text.toLowerCase(Locale.ROOT).startsWith("ai ")) chatField.setText("ai " + text);
        chatField.setFocused(true);
        chatField.setCursorToEnd(false);
        cir.setReturnValue(true);
    }
}
