package thunder.hack.gui.notification;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import thunder.hack.gui.font.BkFonts;
import thunder.hack.gui.font.FontRenderer;
import thunder.hack.utility.Timer;
import thunder.hack.utility.render.BkLiquidGlass;
import thunder.hack.utility.render.Render2DEngine;

import java.awt.Color;

/**
 * A toast, as BKClient draws them ({@code systems/notifications/NotificationOther}, MIT, bkubiakdev): a pill of
 * liquid glass at the bottom centre with the type's icon, a bold title and a line under it. It springs in with
 * BK's overshoot curve, stacks upwards 30 px per toast, and springs back out.
 */
public class Notification {
    private final String message, title;
    private final Type type;
    private final int lifeTime;
    private final Timer timer = new Timer();

    /** BK's animation (400 ms, BAKEK in, BAKEK_BACK out) and the stack offset easing (300 ms, BAKEK_SIZE). */
    private float anim, animFrom, animTarget = -1f;
    private long animStart;
    private float offY, offFrom, offTarget = Float.NaN;
    private long offStart;

    public Notification(String title, String message, Type type, int time) {
        this.lifeTime = Math.max(500, time);
        this.title = title;
        this.message = message;
        this.type = type;
    }

    /* ------------------------------------------------------------------ */
    /* BK's easing                                                         */
    /* ------------------------------------------------------------------ */

    private static float bezier(float progress, float x1, float y1, float x2, float y2) {
        if (progress <= 0f) return 0f;
        if (progress >= 1f) return 1f;
        float t = progress;
        for (int i = 0; i < 8; i++) {
            float x = 3f * (1f - t) * (1f - t) * t * x1 + 3f * (1f - t) * t * t * x2 + t * t * t;
            float dx = 3f * ((1f - t) * (1f - 3f * t) * x1 + (2f * t - 3f * t * t) * x2) + 3f * t * t;
            if (Math.abs(x - progress) < 1e-5f || Math.abs(dx) < 1e-6f) break;
            t = Math.clamp(t - (x - progress) / dx, 0f, 1f);
        }
        return 3f * (1f - t) * (1f - t) * t * y1 + 3f * (1f - t) * t * t * y2 + t * t * t;
    }

    private void updateAnim() {
        float target = isFinished() ? 0f : 1f;
        long now = System.currentTimeMillis();
        if (target != animTarget) {
            animFrom = animTarget < 0 ? 0f : anim;
            animTarget = target;
            animStart = now;
        }
        float p = Math.min(1f, (now - animStart) / 400f);
        float e = target > 0 ? bezier(p, 0.45f, 1.45f, 0.49f, 1.15f) : bezier(p, 0.62f, -0.16f, 0.8f, 0.37f);
        anim = animFrom + (animTarget - animFrom) * e;
    }

    private float updateOff(float off) {
        long now = System.currentTimeMillis();
        if (Float.isNaN(offTarget)) {
            offY = offFrom = offTarget = off;
            return offY;
        }
        if (off != offTarget) {
            offFrom = offY;
            offTarget = off;
            offStart = now;
        }
        float p = Math.min(1f, (now - offStart) / 300f);
        offY = offFrom + (offTarget - offFrom) * bezier(p, 0.27f, 1.09f, 0.49f, 1.06f);
        return offY;
    }

    /* ------------------------------------------------------------------ */
    /* Drawing                                                             */
    /* ------------------------------------------------------------------ */

    /**
     * BK's NotificationOther.draw.
     *
     * @param baseY the bottom line the stack grows up from (BK: screen height - 90)
     * @param off   this toast's place in the stack, in pixels
     */
    public void draw(DrawContext ctx, float centreX, float baseY, float off) {
        updateAnim();
        FontRenderer titleFont = BkFonts.BOLD.get(7f), descFont = BkFonts.MEDIUM.get(6f);
        float textWidth = Math.max(titleFont.getStringWidth(title), descFont.getStringWidth(message));
        float width = textWidth + 32f, height = 26f;
        float x = centreX - width / 2f;
        float y = baseY - updateOff(off);
        float a = Math.clamp(anim, 0f, 1.2f);
        float alpha = Math.clamp(anim, 0f, 1f);
        if (alpha <= 0.005f) return;

        var m = ctx.getMatrices();
        m.pushMatrix();
        float s = 0.5f + 0.5f * a;
        float pivotY = y + 12f + height / 2f;
        m.translate(x + width / 2f, pivotY);
        m.scale(s, s);
        m.translate(-(x + width / 2f), -pivotY);
        try {
            if (!BkLiquidGlass.glass(ctx, x, y, width, height, 7f, 0.08f, alpha))
                Render2DEngine.drawRoundSmooth(ctx, x, y, width, height, 7f, new Color(20, 20, 26, Math.round(200 * alpha)));
            Render2DEngine.drawRoundSmooth(ctx, x, y, width, height, 7f, new Color(12, 12, 12, Math.round(255 * 0.2f * alpha)));

            Color accent = type.getAccent();
            Render2DEngine.renderTexture(ctx, type.icon(), x + height / 2f - 4.5f, y + height / 2f - 4.5f, 10, 10, 0, 0, 10, 10, 10, 10,
                    new Color(accent.getRed(), accent.getGreen(), accent.getBlue(), Math.round(255 * alpha * 0.8f)));
            int white = new Color(255, 255, 255, Math.round(255 * alpha)).getRGB();
            titleFont.drawString(ctx, title, x + 27f, y + 7f, white);
            descFont.drawString(ctx, message, x + 27f, y + 15f, white);
        } finally {
            m.popMatrix();
        }
    }

    /** Kept for callers of the old two-pass drawing; the BK toast draws in one pass ({@link #draw}). */
    public void render(DrawContext ctx, float y) {
    }

    public void renderShaders(DrawContext ctx, float y) {
    }

    /** Mock toast used by the HUD editor so the anchor is visible while dragging. */
    public static void drawPreview(DrawContext ctx, float x, float y, float width, float height) {
        FontRenderer titleFont = BkFonts.BOLD.get(7f), descFont = BkFonts.MEDIUM.get(6f);
        float h = 26f;
        if (!BkLiquidGlass.glass(ctx, x, y, width, h, 7f, 0.08f, 1f))
            Render2DEngine.drawRoundSmooth(ctx, x, y, width, h, 7f, new Color(20, 20, 26, 200));
        Render2DEngine.drawRoundSmooth(ctx, x, y, width, h, 7f, new Color(12, 12, 12, 51));
        Color accent = Type.INFO.getAccent();
        Render2DEngine.renderTexture(ctx, Type.INFO.icon(), x + h / 2f - 4.5f, y + h / 2f - 4.5f, 10, 10, 0, 0, 10, 10, 10, 10,
                new Color(accent.getRed(), accent.getGreen(), accent.getBlue(), 204));
        titleFont.drawString(ctx, "Notifications", x + 27f, y + 7f, -1);
        descFont.drawString(ctx, "drag me where you want them", x + 27f, y + 15f, -1);
    }

    /** Whether the toast still holds its place in the stack (BK: shown at least halfway, or its time not up). */
    public boolean holdsPlace() {
        return anim >= 0.5f || !isFinished();
    }

    private boolean isFinished() {
        return timer.passedMs(lifeTime);
    }

    public double getHeight() {
        return 26;
    }

    public boolean shouldDelete() {
        return isFinished() && animTarget == 0f && anim <= 0.001f && System.currentTimeMillis() - animStart >= 400L;
    }

    public void onUpdate() {
    }

    public enum Type {
        SUCCESS("Success", Formatting.GREEN, new Color(0, 255, 0), "success"),
        INFO("Information", Formatting.AQUA, new Color(234, 179, 8), "info"),
        WARNING("Warning", Formatting.GOLD, new Color(234, 179, 8), "info"),
        ERROR("Error", Formatting.RED, new Color(255, 0, 0), "error"),
        ENABLED("Module enabled", Formatting.DARK_GREEN, new Color(0, 255, 0), "success"),
        DISABLED("Module disabled", Formatting.DARK_RED, new Color(255, 0, 0), "error");

        final String name;
        final Formatting color;
        final Color accent;
        final Identifier icon;

        Type(String name, Formatting color, Color accent, String icon) {
            this.name = name;
            this.color = color;
            this.accent = accent;
            this.icon = Identifier.of("bkclient", "icons/" + icon + ".png");
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

        public Identifier icon() {
            return icon;
        }
    }
}
