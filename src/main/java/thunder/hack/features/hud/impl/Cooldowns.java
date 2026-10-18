package thunder.hack.features.hud.impl;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.util.math.MathHelper;
import thunder.hack.features.hud.HudElement;
import thunder.hack.gui.font.FontRenderers;
import thunder.hack.setting.Setting;
import thunder.hack.utility.math.FrameRateCounter;
import thunder.hack.utility.render.Render2DEngine;

import java.awt.Color;

/**
 * 1:1 port of Celestial Premium 6.0's {@code IndicatorsComponent} (feature "Indicators"): a dark 118x45 panel with
 * a client-colour top line, "Indicators" title, and two bars - attack Cooldown and HurtTime - easing to their values.
 * Shadow / Glow are Celestial's Indicators settings; comfortaa -> sf_bold_mini, mc.fontRenderer -> vanilla font.
 */
public class Cooldowns extends HudElement {
    public Cooldowns() {
        super("Cooldowns", 100, 100);
    }

    private final Setting<Boolean> shadow = new Setting<>("Shadow", true);
    private final Setting<Integer> shadowRadius = new Setting<>("Shadow Radius", 10, 5, 20, v -> shadow.getValue());
    private final Setting<Boolean> glow = new Setting<>("Glow", true);

    private float cooldownAnim = Float.NaN, hurtAnim = Float.NaN;

    public void onRender2D(DrawContext context) {
        super.onRender2D(context);
        if (fullNullCheck() || mc.getDebugHud().shouldShowDebugHud()) return;

        float x = getPosX();
        float y = getPosY();
        float left = x;
        float top = y;
        float right = left + 48.0f + 70.0f;
        float bottom = top + 45.0f;
        Color client = thunder.hack.features.modules.client.HudEditor.getColor(0);
        int fps = FrameRateCounter.INSTANCE.getFps();

        if (shadow.getValue())
            Render2DEngine.drawBlurredShadow(context, left - 10.0f, top - 8.0f, 142.0f, 60.0f, shadowRadius.getValue(), Color.BLACK);
        Render2DEngine.drawRect(context, left, top, right - left, bottom - top, new Color(5, 5, 5, 175));
        Render2DEngine.drawRect(context, x, y - 1.0f, right - x, 1.0f, client);
        if (glow.getValue())
            Render2DEngine.drawBlurredShadow(context, x, y, 120.0f, 3.0f, 20, client);
        Render2DEngine.drawRect(context, x + 3.0f, y + 14.0f, right - 3.0f - (x + 3.0f), 0.5f, new Color(205, 205, 205));
        FontRenderers.sf_bold_mini.drawString(context, "Indicators", x + 33.0f + 0.5f, y + 5.0f + 0.5f, 0xFF3F3F3F);
        FontRenderers.sf_bold_mini.drawString(context, "Indicators", x + 33.0f, y + 5.0f, -1);
        if (fps > 5) {
            Render2DEngine.drawRect(context, left + 49.0f, top + 23.0f, 60.0f, 2.0f, new Color(35, 35, 35, 255));
            Render2DEngine.drawRect(context, left + 49.0f, top + 34.0f, 60.0f, 2.0f, new Color(35, 35, 35, 255));
            if (Float.isNaN(cooldownAnim) || Float.isNaN(hurtAnim)) {
                hurtAnim = mc.player.hurtTime;
                cooldownAnim = mc.player.getAttackCooldownProgress(0.0f);
            }
            float value = fps < 10 ? 0.25f : 6.0f / (float) fps;
            cooldownAnim = (float) MathHelper.lerp(value, cooldownAnim, mc.player.getAttackCooldownProgress(-1.0f));
            cooldownAnim = MathHelper.clamp(cooldownAnim, 0.01f, 1.0f);
            double endValue = MathHelper.clamp((double) ((float) mc.player.hurtTime / 7.0f), 0.1, 1.0);
            hurtAnim = (float) MathHelper.lerp(6.0f / (float) fps, hurtAnim, (float) endValue);
            if (glow.getValue()) {
                Color bright = client.brighter().brighter();
                Render2DEngine.drawBlurredShadow(context, x + 49.0f, y + 23.0f, cooldownAnim * 60.0f, 2.0f, 10, bright);
                Render2DEngine.drawBlurredShadow(context, x + 49.0f, y + 34.0f, hurtAnim * 60.0f, 2.0f, 10, bright);
            }
        }
        context.drawTextWithShadow(mc.textRenderer, "Cooldown", (int) (left + 3.0f), (int) (top + 20.0f), -1);
        Render2DEngine.drawRect(context, left + 49.0f, top + 23.0f, cooldownAnim * 60.0f, 2.0f, client);
        context.drawTextWithShadow(mc.textRenderer, "HurtTime", (int) (left + 3.0f), (int) (top + 31.0f), -1);
        Render2DEngine.drawRect(context, left + 49.0f, top + 34.0f, hurtAnim * 60.0f, 2.0f, client);

        setBounds(left, top - 1, right - left, bottom - top + 1);
    }
}
