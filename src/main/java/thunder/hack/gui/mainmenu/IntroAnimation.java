package thunder.hack.gui.mainmenu;

import net.minecraft.client.gui.DrawContext;
import thunder.hack.gui.font.FontRenderers;
import thunder.hack.utility.render.Render2DEngine;
import thunder.hack.utility.render.TextureStorage;

import java.awt.*;

/**
 * Short intro that plays once, the first time the GOMBAL menu opens after launching the game: the letters
 * of the wordmark fly in one by one over a dark backdrop, a light sweep runs across them, the tagline types
 * itself out and the whole thing dissolves into the menu. Click or press a key to skip it.
 */
public final class IntroAnimation {
    private static final long LETTERS = 900;   // wordmark assembled
    private static final long HOLD = 1500;     // tagline + sweep
    private static final long FADE = 700;      // dissolve into the menu
    private static final long TOTAL = LETTERS + HOLD + FADE;

    private static final String BRAND = "GOMBAL";
    private static final String TAGLINE = "recode  //  1.21.11";

    private static final Color ACCENT = new Color(168, 85, 247);
    private static final Color ACCENT2 = new Color(120, 90, 255);

    private static long start = -1;
    private static boolean done = false;

    private IntroAnimation() {
    }

    public static boolean isPlaying() {
        if (done) return false;
        if (start == -1) start = System.currentTimeMillis();
        if (System.currentTimeMillis() - start > TOTAL) {
            done = true;
            return false;
        }
        return true;
    }

    public static void skip() {
        done = true;
    }

    /** Lets the intro run again (used by the "replay intro" click on the wordmark). */
    public static void replay() {
        done = false;
        start = System.currentTimeMillis();
    }

    public static void render(DrawContext ctx, float w, float h) {
        long t = System.currentTimeMillis() - start;
        float cx = w / 2f, cy = h / 2f;

        // backdrop: solid until the fade phase, then dissolves onto the menu below
        float backdrop = t < LETTERS + HOLD ? 1f : 1f - (t - LETTERS - HOLD) / (float) FADE;
        backdrop = clamp(backdrop);
        Render2DEngine.drawRect(ctx, 0, 0, w, h, new Color(6, 6, 10, (int) (245 * backdrop)));

        // ---- glass G logo: turns in from edge-on, overshoots a little and settles, then catches the sweep ----
        {
            float lp = clamp(t / 750f);
            float grow = easeOutBack(lp);
            float turn = (float) Math.cos((float) Math.pow(1 - lp, 3) * Math.PI);
            if (Math.abs(turn) < 0.05f) turn = Math.copySign(0.05f, turn);
            float logoA = clamp(t / 300f) * backdrop;
            float bob = (float) Math.sin(t / 520.0) * 2f * clamp((t - 750) / 400f);
            float size = 58f;
            float lx = cx, ly = cy - 92f + bob;

            // accent glow behind it
            float gs = size * 1.6f * grow;
            Color glow = alpha(ACCENT, 90, logoA);
            Render2DEngine.renderGradientTexture(ctx, TextureStorage.miniLogo, lx - gs / 2f, ly - gs / 2f, gs, gs,
                    0, 0, 128, 128, 128, 128, glow, glow, glow, glow, true);

            ctx.getMatrices().pushMatrix();
            ctx.getMatrices().translate(lx, ly);
            ctx.getMatrices().scale(grow * turn, grow);
            ctx.getMatrices().translate(-lx, -ly);
            Render2DEngine.renderTexture(ctx, TextureStorage.miniLogo, lx - size / 2f, ly - size / 2f, size, size,
                    0, 0, 128, 128, 128, 128, new Color(255, 255, 255, (int) (255 * logoA)));
            float glint = (float) Math.sin(clamp((t - LETTERS) / 700f) * Math.PI);
            if (glint > 0.01f) {
                Color shine = new Color(255, 255, 255, (int) (140 * glint * logoA));
                Render2DEngine.renderGradientTexture(ctx, TextureStorage.miniLogo, lx - size / 2f, ly - size / 2f, size, size,
                        0, 0, 128, 128, 128, 128, shine, shine, shine, shine, true);
            }
            ctx.getMatrices().popMatrix();
        }

        // ---- wordmark, letter by letter ----
        var font = FontRenderers.brand != null ? FontRenderers.brand : FontRenderers.monsterrat;
        float spacing = 6f;
        float total = 0f;
        for (int i = 0; i < BRAND.length(); i++)
            total += font.getStringWidth(String.valueOf(BRAND.charAt(i))) + spacing;
        total -= spacing;

        float x = cx - total / 2f;
        float baseY = cy - 34f;
        for (int i = 0; i < BRAND.length(); i++) {
            String ch = String.valueOf(BRAND.charAt(i));
            float cw = font.getStringWidth(ch);

            long delay = i * 90L;
            float p = clamp((t - delay) / 420f);
            float ease = 1f - (float) Math.pow(1 - p, 3);          // easeOutCubic
            float offY = (1f - ease) * 26f;
            float alpha = ease * backdrop;

            // light sweep travelling across the word once the letters are in place
            float sweep = clamp((t - LETTERS) / 700f);
            float sweepPos = sweep * (total + 60f) - 30f;
            float dist = Math.abs((x - (cx - total / 2f)) + cw / 2f - sweepPos);
            float hot = sweep > 0f && sweep < 1f ? clamp(1f - dist / 45f) : 0f;

            Color c = mix(new Color(240, 240, 248), new Color(214, 180, 255), hot);
            font.drawString(ctx, ch, x, baseY + offY, c.getRed() / 255f, c.getGreen() / 255f, c.getBlue() / 255f, alpha);
            x += cw + spacing;
        }

        // ---- accent rule under the wordmark ----
        float rule = clamp((t - 350) / 600f);
        float ruleW = (1f - (float) Math.pow(1 - rule, 3)) * (total * 0.85f);
        if (ruleW > 1f) {
            float ry = cy + 6f;
            Render2DEngine.horizontalGradient(ctx, cx - ruleW / 2f, ry, cx, ry + 1.6f,
                    alpha(ACCENT2, 0, backdrop), alpha(ACCENT, 235, backdrop));
            Render2DEngine.horizontalGradient(ctx, cx, ry, cx + ruleW / 2f, ry + 1.6f,
                    alpha(ACCENT, 235, backdrop), alpha(ACCENT2, 0, backdrop));
        }

        // ---- tagline, typed out ----
        var small = FontRenderers.brandSmall != null ? FontRenderers.brandSmall : FontRenderers.sf_medium;
        float typed = clamp((t - LETTERS + 250) / 650f);
        int chars = (int) (typed * TAGLINE.length());
        if (chars > 0) {
            String shown = TAGLINE.substring(0, chars);
            float caret = (t / 350) % 2 == 0 && typed < 1f ? 1f : 0f;
            small.drawCenteredString(ctx, shown + (caret > 0 ? "_" : ""), cx, cy + 20f,
                    0.78f, 0.72f, 0.88f, backdrop);
        }

        // ---- version chip + loader ----
        float barW = 150f, barH = 2f;
        float progress = clamp(t / (float) (LETTERS + HOLD));
        float by = cy + 56f;
        Render2DEngine.drawRound(ctx, cx - barW / 2f, by, barW, barH, 1f, new Color(255, 255, 255, (int) (28 * backdrop)));
        Render2DEngine.drawRound(ctx, cx - barW / 2f, by, barW * progress, barH, 1f, alpha(ACCENT, 230, backdrop));

        FontRenderers.sf_medium_mini.drawCenteredString(ctx, "click to skip", cx, h - 22f,
                new Color(150, 150, 168, (int) (140 * backdrop)).getRGB());
    }

    private static float easeOutBack(float x) {
        float c1 = 1.70158f, c3 = c1 + 1f;
        return 1f + c3 * (float) Math.pow(x - 1, 3) + c1 * (float) Math.pow(x - 1, 2);
    }

    private static float clamp(float v) {
        return v < 0f ? 0f : Math.min(v, 1f);
    }

    private static Color alpha(Color c, int a, float factor) {
        return new Color(c.getRed(), c.getGreen(), c.getBlue(), (int) (a * factor));
    }

    private static Color mix(Color a, Color b, float f) {
        return new Color(
                (int) (a.getRed() + (b.getRed() - a.getRed()) * f),
                (int) (a.getGreen() + (b.getGreen() - a.getGreen()) * f),
                (int) (a.getBlue() + (b.getBlue() - a.getBlue()) * f));
    }
}
