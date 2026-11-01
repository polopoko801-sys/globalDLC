package thunder.hack.gui.notification;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.util.Formatting;
import thunder.hack.core.manager.client.ModuleManager;
import thunder.hack.features.hud.impl.NotifyHud;
import thunder.hack.features.modules.client.HudEditor;
import thunder.hack.gui.font.FontRenderers;
import thunder.hack.utility.Timer;
import thunder.hack.utility.math.MathUtility;
import thunder.hack.utility.render.Render2DEngine;
import thunder.hack.utility.render.animation.EaseOutBack;

import java.awt.*;

import static thunder.hack.core.manager.client.NotificationManager.isDefault;
import static thunder.hack.features.modules.Module.mc;

/**
 * A single toast. Layout: accent bar + icon badge on the left, title/message on the right and a lifetime
 * progress bar along the bottom. It slides in from the side, fades, and can be anchored anywhere on the
 * screen through the draggable {@link NotifyHud} element.
 */
public class Notification {
    private final String message, title;
    private final String icon;
    private final Type type;
    private final int lifeTime;
    public final EaseOutBack animation;
    private float y, width, animationX, height = 25;
    private boolean direction = false;
    private final Timer timer = new Timer();

    public Notification(String title, String message, Type type, int time) {
        lifeTime = time;
        this.title = title;
        this.message = message;
        this.type = type;

        switch (type) {
            case INFO -> icon = "J";
            case ENABLED -> icon = "K";
            case DISABLED, ERROR -> icon = "I";
            case WARNING -> icon = "L";
            default -> icon = "H";
        }

        width = isDefault()
                ? Math.max(FontRenderers.sf_bold_big.getStringWidth(title), FontRenderers.sf_bold.getStringWidth(message)) + 58f
                : FontRenderers.sf_bold_mini.getStringWidth(title + " " + message) + 36f;
        height = isDefault() ? 36 : 20;

        animation = new EaseOutBack(isDefault() ? 10 : 20);

        animationX = width;
        y = anchoredY();
    }

    /* ------------------------------------------------------------------ */
    /* Placement                                                           */
    /* ------------------------------------------------------------------ */

    /** True when the user has placed the notification stack himself with the NotifyHud element. */
    private static boolean anchored() {
        NotifyHud hud = ModuleManager.notifyHud;
        return hud != null && hud.isEnabled();
    }

    /** Left edge of the toast, animation offset excluded. */
    private float baseX() {
        if (anchored()) {
            float px = ModuleManager.notifyHud.getPosX();
            return px > mc.getWindow().getScaledWidth() / 2f ? px - width : px;
        }
        return isDefault()
                ? mc.getWindow().getScaledWidth() - 6 - width
                : mc.getWindow().getScaledWidth() / 2f - width / 2f;
    }

    private float anchoredY() {
        if (anchored()) return ModuleManager.notifyHud.getPosY();
        return isDefault() ? mc.getWindow().getScaledHeight() - height : mc.getWindow().getScaledHeight() / 2f + 10;
    }

    /** Toasts slide out towards the nearest screen edge. */
    private float slideX() {
        boolean toRight = anchored() ? baseX() + width / 2f > mc.getWindow().getScaledWidth() / 2f : isDefault();
        return toRight ? animationX : (isDefault() ? animationX : 0f);
    }

    /* ------------------------------------------------------------------ */
    /* Rendering                                                           */
    /* ------------------------------------------------------------------ */

    public void render(DrawContext ctx, float getY) {
        direction = isFinished();
        animationX = (float) (width * animation.getAnimationd());
        y = animate(y, getY);

        float alpha = (float) MathUtility.clamp(1 - animation.getAnimationd(), 0f, 1f);
        int a255 = (int) (alpha * 255);
        float x = baseX() + slideX();

        Color accent = Render2DEngine.injectAlpha(type.getAccent(), a255);
        Color text = Render2DEngine.injectAlpha(HudEditor.textColor.getValue().getColorObject(), a255);
        Color sub = new Color(178, 181, 194, (int) (a255 * 0.85f));

        if (isDefault()) {
            // accent rail
            Render2DEngine.verticalGradient(ctx, x + 4f, y + 5f, x + 6.5f, y + height / 2f, Render2DEngine.injectAlpha(accent, (int) (a255 * 0.15f)), accent);
            Render2DEngine.verticalGradient(ctx, x + 4f, y + height / 2f, x + 6.5f, y + height - 5f, accent, Render2DEngine.injectAlpha(accent, (int) (a255 * 0.15f)));

            // icon badge
            Render2DEngine.drawRound(ctx, x + 11f, y + height / 2f - 10f, 20f, 20f, 6f, Render2DEngine.injectAlpha(accent, (int) (a255 * 0.16f)));
            FontRenderers.mid_icons.drawString(ctx, icon, x + 16f, y + height / 2f - 5f, accent.getRGB());

            FontRenderers.sf_bold_big.drawString(ctx, title, x + 38f, y + 9f, text.getRGB());
            FontRenderers.sf_bold.drawString(ctx, message, x + 38f, y + 22f, sub.getRGB());

            drawProgress(ctx, x + 10f, y + height - 4.5f, width - 20f, accent, a255);
        } else {
            Render2DEngine.drawRound(ctx, x + 4f, y + height / 2f - 6f, 12f, 12f, 4f, Render2DEngine.injectAlpha(accent, (int) (a255 * 0.18f)));
            FontRenderers.small_icons.drawString(ctx, icon, x + 7.5f, y + height / 2f - 2.5f, accent.getRGB());

            FontRenderers.sf_bold_mini.drawString(ctx, title, x + 21f, y + height / 2f - 3f, text.getRGB());
            FontRenderers.sf_bold_mini.drawString(ctx, message, x + 21f + FontRenderers.sf_bold_mini.getStringWidth(title + " "), y + height / 2f - 3f, sub.getRGB());

            drawProgress(ctx, x + 5f, y + height - 3f, width - 10f, accent, a255);
        }
    }

    /** Thin bar that drains as the toast runs out of time. */
    private void drawProgress(DrawContext ctx, float x, float y, float w, Color accent, int a255) {
        float left = 1f - (float) MathUtility.clamp(timer.getPassedTimeMs() / (double) lifeTime, 0d, 1d);
        Render2DEngine.drawRound(ctx, x, y, w, 1.5f, 0.75f, new Color(255, 255, 255, (int) (a255 * 0.10f)));
        if (left > 0.001f)
            Render2DEngine.drawRound(ctx, x, y, w * left, 1.5f, 0.75f, accent);
    }

    public void onUpdate() {
        animation.update(direction);
    }

    public void renderShaders(DrawContext ctx, float getY) {
        direction = isFinished();
        animationX = (float) (width * animation.getAnimationd());
        y = animate(y, getY);

        float alpha = (float) MathUtility.clamp(1 - animation.getAnimationd(), 0f, 1f);
        float x = baseX() + slideX();
        float radius = isDefault() ? 7f : 5f;

        // soft drop shadow + a faint tint of the accent so the toast lifts off the world
        Render2DEngine.drawBlurredShadow(ctx, x, y, width, height, 12, new Color(0, 0, 0, (int) (140 * alpha)));
        Render2DEngine.drawBlurredShadow(ctx, x, y, width, height, 16, Render2DEngine.injectAlpha(type.getAccent(), (int) (38 * alpha)));

        if (HudEditor.hudStyle.is(HudEditor.HudStyle.Blurry))
            Render2DEngine.drawHudBase2(ctx, x, y, width, height, radius, HudEditor.blurStrength.getValue(), HudEditor.blurOpacity.getValue(), alpha);
        else
            Render2DEngine.drawHudBase(ctx, x, y, width, height, radius, alpha);

        // glass highlight along the top edge
        Render2DEngine.horizontalGradient(ctx, x + radius, y + 0.5f, x + width - radius, y + 1f,
                new Color(255, 255, 255, 0), new Color(255, 255, 255, (int) (55 * alpha)));
    }

    /** Mock toast used by the HUD editor so the anchor is visible while dragging. */
    public static void drawPreview(DrawContext ctx, float x, float y, float width, float height) {
        Color accent = Type.INFO.getAccent();
        Render2DEngine.drawBlurredShadow(ctx, x, y, width, height, 12, new Color(0, 0, 0, 140));
        if (HudEditor.hudStyle.is(HudEditor.HudStyle.Blurry))
            Render2DEngine.drawHudBase2(ctx, x, y, width, height, 7f, HudEditor.blurStrength.getValue(), HudEditor.blurOpacity.getValue(), 1f);
        else
            Render2DEngine.drawHudBase(ctx, x, y, width, height, 7f, 1f);

        Render2DEngine.verticalGradient(ctx, x + 4f, y + 5f, x + 6.5f, y + height / 2f, Render2DEngine.injectAlpha(accent, 40), accent);
        Render2DEngine.verticalGradient(ctx, x + 4f, y + height / 2f, x + 6.5f, y + height - 5f, accent, Render2DEngine.injectAlpha(accent, 40));
        Render2DEngine.drawRound(ctx, x + 11f, y + height / 2f - 10f, 20f, 20f, 6f, Render2DEngine.injectAlpha(accent, 42));
        FontRenderers.mid_icons.drawString(ctx, "J", x + 16f, y + height / 2f - 5f, accent.getRGB());
        FontRenderers.sf_bold_big.drawString(ctx, "Notifications", x + 38f, y + 9f, HudEditor.textColor.getValue().getColor());
        FontRenderers.sf_bold.drawString(ctx, "drag me where you want them", x + 38f, y + 22f, new Color(178, 181, 194).getRGB());
        Render2DEngine.drawRound(ctx, x + 10f, y + height - 4.5f, width - 20f, 1.5f, 0.75f, new Color(255, 255, 255, 26));
        Render2DEngine.drawRound(ctx, x + 10f, y + height - 4.5f, (width - 20f) * 0.6f, 1.5f, 0.75f, accent);
    }

    private boolean isFinished() {
        return timer.passedMs(lifeTime);
    }

    public double getHeight() {
        return height;
    }

    public boolean shouldDelete() {
        return isFinished() && animationX >= width - 5;
    }

    /** Glides the toast to its slot in the stack - same speed at any frame rate (was 1/8 per frame). */
    public float animate(float value, float target) {
        return thunder.hack.utility.render.animation.AnimationUtility.fast(value, target, 12f);
    }

    public enum Type {
        SUCCESS("Success", Formatting.GREEN, new Color(105, 220, 130)),
        INFO("Information", Formatting.AQUA, new Color(95, 175, 255)),
        WARNING("Warning", Formatting.GOLD, new Color(245, 190, 80)),
        ERROR("Error", Formatting.RED, new Color(240, 95, 95)),
        ENABLED("Module enabled", Formatting.DARK_GREEN, new Color(120, 225, 145)),
        DISABLED("Module disabled", Formatting.DARK_RED, new Color(235, 90, 90));

        final String name;
        final Formatting color;
        final Color accent;

        Type(String name, Formatting color, Color accent) {
            this.name = name;
            this.color = color;
            this.accent = accent;
        }

        public String getName() {
            return name;
        }

        public Formatting getColor() {
            return color;
        }

        public Color getAccent() {
            return accent;
        }
    }
}
