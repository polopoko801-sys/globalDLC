package thunder.hack.features.hud;

import net.minecraft.client.gui.DrawContext;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import thunder.hack.features.modules.client.HudEditor;
import thunder.hack.gui.font.FontRenderer;
import thunder.hack.gui.font.FontRenderers;
import thunder.hack.utility.render.Render2DEngine;

import java.awt.Color;

/**
 * One look for every stacked list on the HUD.
 *
 * <p>The ArrayList had three styles and everything else on the HUD had its own hand-rolled variation, so
 * turning the client one colour meant editing each of them. A row is a row: a plate, an accent edge on the
 * outer side, a name and a trailing bit of text. This draws that, and any list element can use it -
 * ArrayList, KeyBinds and whatever comes next - so they always match.
 */
public final class HudListStyle {
    private HudListStyle() {
    }

    public enum Mode {
        /** Dark rounded plate, bold white name, accent trail. The Cloudan default. */
        Glass,
        /** No plate: the text itself carries the accent colour. */
        ColorText,
        /** Flat accent plate with white text on it. */
        ColorRect
    }

    /** Where the trailing text sits: straight after the name, or pushed to the far edge of the row. */
    public enum TrailAlign {
        Inline,
        Right
    }

    /** Everything a row needs that is not its text. Built once per element, reused for every row. */
    public record Style(Mode mode, boolean small, boolean reverse, boolean glow, int glowStrength,
                        Color rectColor, Color sideRectColor, boolean blur) {
        public Style(Mode mode, boolean small, boolean reverse) {
            this(mode, small, reverse, false, 30, new Color(12, 12, 15, 208), new Color(0, 0, 0, 0), false);
        }
    }

    public static FontRenderer font(@NotNull Style style) {
        if (style.mode() == Mode.Glass)
            return style.small() ? FontRenderers.sf_bold_mini : FontRenderers.sf_bold;
        return style.small() ? FontRenderers.sf_bold_mini : FontRenderers.modules;
    }

    public static float rowHeight(@NotNull Style style) {
        if (style.mode() == Mode.Glass) return style.small() ? 12f : 14f;
        return style.small() ? 9f : 9f;
    }

    /** Width one row needs, including its padding. */
    public static float rowWidth(@NotNull Style style, @NotNull String main, @Nullable String trail) {
        FontRenderer font = font(style);
        float w = font.getStringWidth(main);
        if (trail != null) w += font.getStringWidth(trail);
        return w + (style.mode() == Mode.Glass ? 12f : 6f);
    }

    /**
     * Draws one row at {@code x, y}. {@code w} is the row width - pass the widest row in the stack for a
     * flush right edge, or this row's own width for a ragged one.
     *
     * @param offset the row's vertical offset in the stack; it drives the accent gradient
     */
    public static void drawRow(@NotNull DrawContext context, @NotNull Style style, float x, float y, float w,
                               @NotNull String main, @Nullable String trail, @NotNull TrailAlign align,
                               int offset, @NotNull Color mainColor) {
        FontRenderer font = font(style);
        float h = rowHeight(style) - 1f;
        Color accent = HudEditor.getColor(offset);
        boolean reverse = style.reverse();

        if (style.glow() && style.mode() != Mode.Glass)
            Render2DEngine.drawBlurredShadow(context, x, y - 1f, w + 4f, h + 1f, style.glowStrength(), accent);

        // The blur is a BACKDROP, never the whole row. It used to be drawn instead of the plate and the
        // accent edge, which is why turning glass on made every colour disappear: there was simply nothing
        // coloured left on screen. Now it goes down first and the plate, the edge and the text all land on
        // top of it exactly as they would without it.
        boolean blurred = style.blur() || HudEditor.hudStyle.is(HudEditor.HudStyle.Blurry);
        float radius = style.mode() == Mode.Glass ? 4f : 2f;
        if (blurred)
            Render2DEngine.drawRoundedBlur(context, x, y, w, h, radius, HudEditor.blurColor.getValue().getColorObject());

        switch (style.mode()) {
            case Glass -> {
                // Over a blur the plate is only a tint - a solid fill would hide what was just blurred.
                Color plate = blurred ? Render2DEngine.injectAlpha(style.rectColor(), 90) : style.rectColor();
                Render2DEngine.drawRound(context, x, y, w, h, radius, plate);
                Render2DEngine.drawRound(context, reverse ? x + w - 2.2f : x, y, 2.2f, h, 1.1f, accent);
            }
            case ColorRect -> {
                Render2DEngine.drawRect(context, x, y, w, h, blurred ? Render2DEngine.injectAlpha(accent, 170) : accent);
                Render2DEngine.drawRect(context, reverse ? x + w : x - 2f, y, 2f, h, style.sideRectColor());
            }
            case ColorText -> {
                if (!blurred) Render2DEngine.drawRect(context, x, y, w, h, style.rectColor());
                Render2DEngine.drawRect(context, reverse ? x + w : x - 2f, y, 2f, h, accent);
            }
        }

        float pad = style.mode() == Mode.Glass ? 6f : 3f;
        float textY = y + (h - font.getFontHeight(main)) / 2f + 0.5f;
        int nameColor = switch (style.mode()) {
            case Glass, ColorRect -> mainColor.getRGB();
            case ColorText -> accent.getRGB();
        };

        font.drawString(context, main, x + pad, textY, nameColor);

        if (trail == null) return;

        float trailW = font.getStringWidth(trail);
        float trailX = align == TrailAlign.Right ? x + w - trailW - pad : x + pad + font.getStringWidth(main);
        int trailColor = style.mode() == Mode.ColorRect ? mainColor.getRGB() : accent.getRGB();
        font.drawString(context, trail, trailX, textY, trailColor);
    }
}
