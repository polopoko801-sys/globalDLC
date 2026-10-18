package thunder.hack.features.hud.impl;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.util.Formatting;
import thunder.hack.gui.font.FontRenderer;
import thunder.hack.utility.render.Render2DEngine;

import java.awt.Color;

/**
 * Rockstar Free 2.0's drawing helpers, reproduced for its HUD panels (KeyBinds, Potions):
 * RoundedUtil (the roundedRect / roundedRectGradient SDF shaders), DrawHelper.drawGradientRoundedRect,
 * Gui/DrawHelper.drawRect, FontRenderer.drawStringWithShadow and Translate.arrayListAnim.
 */
final class RockstarRender {
    private RockstarRender() {
    }

    /** One framebuffer pixel in GUI units - the SDF shaders work in real pixels. */
    private static float px() {
        return (float) (1.0 / MinecraftClient.getInstance().getWindow().getScaleFactor());
    }

    // ------------------------------------------------------------------ RoundedUtil

    /**
     * RoundedUtil.drawRound. The shader's shape is {@code roundSDF(p, halfSize - radius - 1, radius)} with
     * {@code 1 - smoothstep(0, 1, d)}: fully covered up to 1 pixel inside the rect, fading to nothing at its edge.
     * Drawn as that 1-pixel-inset core plus the edge ring at half strength.
     */
    static void drawRound(DrawContext context, float x, float y, float width, float height, float radius, Color color) {
        drawGradientRound(context, x, y, width, height, radius, color, color, color, color);
    }

    /**
     * RoundedUtil.drawGradientRound(x, y, w, h, radius, "bottomLeft", "topLeft", "bottomRight", "topRight").
     * Despite the names, drawQuads puts texture t = 0 at the TOP, so in the shader's
     * {@code mix(mix(c1, c2, t), mix(c3, c4, t), s)} c1 is the top-left corner, c2 bottom-left, c3 top-right and c4
     * bottom-right. Alpha comes from c1 only ({@code * color1.a}).
     */
    static void drawGradientRound(DrawContext context, float x, float y, float width, float height, float radius,
                                  Color c1, Color c2, Color c3, Color c4) {
        int a = c1.getAlpha();
        float p = px();
        // edge ring (the anti-aliased last pixel)
        gradientRoundRaw(context, x, y, width, height, radius, a / 2, c1, c2, c3, c4);
        // core, 1 px in; its alpha tops the half-strength ring back up to the colour's own alpha
        float ringA = a / 2f / 255f, want = a / 255f;
        int coreA = ringA >= 1f ? 255 : Math.round(Math.min(1f, (want - ringA) / (1f - ringA)) * 255f);
        gradientRoundRaw(context, x + p, y + p, width - 2 * p, height - 2 * p, radius, coreA, c1, c2, c3, c4);
    }

    private static void gradientRoundRaw(DrawContext context, float x, float y, float w, float h, float r, int alpha,
                                         Color tl, Color bl, Color tr, Color br) {
        if (w <= 0 || h <= 0) return;
        r = Math.max(0, Math.min(r, Math.min(w, h) / 2f));
        // Render2DEngine.drawGradientRound(c = top-right, c2 = bottom-right, c3 = top-left, c4 = bottom-left)
        Render2DEngine.drawGradientRound(context, x, y, w, h, r,
                withAlpha(tr, alpha), withAlpha(br, alpha), withAlpha(tl, alpha), withAlpha(bl, alpha));
    }

    /** RoundedUtil.drawGradientHorizontal(left, right) = drawGradientRound(left, left, right, right). */
    static void drawGradientHorizontal(DrawContext context, float x, float y, float width, float height, float radius, Color left, Color right) {
        drawGradientRound(context, x, y, width, height, radius, left, left, right, right);
    }

    /**
     * RoundedUtil.drawHorizontalGradientOutlinedRoundedRect: NOT an outline - a brighter gradient panel with the
     * normal gradient panel {@code expand} smaller (radius - 1) on top of it, which leaves a bright rim.
     */
    static void drawHorizontalGradientOutlinedRoundedRect(DrawContext context, float x, float y, float width, float height,
                                                          float radius, float expand, Color color, Color color2) {
        drawGradientHorizontal(context, x, y, width, height, radius, color.brighter(), color2.brighter());
        float e = expand;
        drawGradientHorizontal(context, x + e, y + e, width - e - e, height - e - e, radius - 1, color, color2);
    }

    /** ...WithGlow: RenderUtils.drawBlurredShadow(x + 1, y, w, h, glow, color2.brighter()) first. */
    static void drawHorizontalGradientOutlinedRoundedRectWithGlow(DrawContext context, float x, float y, float width, float height,
                                                                  float radius, float expand, int glowIntencivity, Color color, Color color2) {
        Render2DEngine.drawBlurredShadow(context, x + 1, y, width, height, glowIntencivity, color2.brighter());
        drawHorizontalGradientOutlinedRoundedRect(context, x, y, width, height, radius, expand, color, color2);
    }

    /**
     * Not Rockstar: the client's liquid glass (real backdrop blur, see Render2DEngine.drawRoundedBlur) tinted with
     * {@code tint} - the GUI colour - so a green GUI gives green glass.
     */
    static void drawLiquidGlass(DrawContext context, float x, float y, float width, float height, float radius, Color tint) {
        Render2DEngine.drawRoundedBlur(context, x, y, width, height, radius, tint);
        // bright rim along the top edge, in the tint
        Render2DEngine.horizontalGradient(context, x + radius, y, x + width / 2f, y + 0.6f, withAlpha(tint.brighter(), 0), withAlpha(tint.brighter(), 170));
        Render2DEngine.horizontalGradient(context, x + width / 2f, y, x + width - radius, y + 0.6f, withAlpha(tint.brighter(), 170), withAlpha(tint.brighter(), 0));
    }

    // ------------------------------------------------------------------ DrawHelper

    /** DrawHelper.drawRect / Gui.drawRect(left, top, right, bottom): corners in any order. */
    static void drawRect(DrawContext context, double left, double top, double right, double bottom, Color color) {
        double l = Math.min(left, right), r = Math.max(left, right), t = Math.min(top, bottom), b = Math.max(top, bottom);
        Render2DEngine.drawRect(context, (float) l, (float) t, (float) (r - l), (float) (b - t), color);
    }

    /** DrawHelper.drawGradientSideways: colour 1 on the left edge, colour 2 on the right. */
    private static void drawGradientSideways(DrawContext context, double left, double top, double right, double bottom, Color c1, Color c2) {
        Render2DEngine.horizontalGradient(context, (float) left, (float) top, (float) right, (float) bottom, c1, c2);
    }

    /** DrawHelper.polygon(x, y, sideLength, 360, filled): a filled circle of radius sideLength / 2 with its box at (x, y). */
    private static void polygon(DrawContext context, double x, double y, double sideLength, Color color) {
        float d = (float) sideLength;
        Render2DEngine.drawRound(context, (float) x, (float) y, d, d, d / 2f, color);
    }

    /** DrawHelper.drawGradientRoundedRect, call for call (including its y + height2 slip in the last-but-one bar). */
    static void drawGradientRoundedRect(DrawContext context, double x, double y, double width, double height, float radius, Color color, Color color2) {
        float x2 = (float) (x + ((radius / 2F) + 0.5F));
        float y2 = (float) (y + ((radius / 2F) + 0.5F));
        float width2 = (float) (width - ((radius / 2F) + 0.5F));
        float height2 = (float) (height - ((radius / 2F) + 0.5F));

        drawRect(context, x2, y2, x2 + width2, y2 + height2, color);

        polygon(context, x, y, radius * 2, color);
        polygon(context, x + width2 - radius + 1.2, y, radius * 2, color2);

        polygon(context, x + width2 - radius + 1.2, y + height2 - radius + 1, radius * 2, color2);
        polygon(context, x, y + height2 - radius + 1, radius * 2, color);

        drawGradientSideways(context, x2 - radius / 2 - 0.5F, y2 + radius / 2, x2 + width2, y2 + height2 - radius / 2, color, color2);
        drawGradientSideways(context, x2, y2 + radius / 2, x2 + width2 + radius / 2 + 0.5f, y2 + height2 - radius / 2, color, color);

        drawGradientSideways(context, x2 + 10, y2 + radius / 2, x2 + width2 + radius / 2 + 0.5f, y2 + height2 - radius / 2, color2, color2);

        drawGradientSideways(context, x2 + radius / 2, y2 - radius / 2 - 0.5F, x2 + width2 - radius / 2, y + height2 - radius / 2, color, color2);
        drawGradientSideways(context, x2 + radius / 2, y2, x2 + width2 - radius / 2, y2 + height2 + radius / 2 + 0.5f, color, color2);
    }

    // ------------------------------------------------------------------ FontRenderer

    /**
     * FontRenderer.drawStringWithShadow: the shadow pass at (x + 0.9, y + 0.7) in
     * {@code (c & 0xFCFCFC) >> 2 | c & 0xC8141414}, colour codes switching to their dark (index + 16) variants,
     * then the text itself.
     */
    static void drawStringWithShadow(DrawContext context, FontRenderer font, String s, float x, float y, int color) {
        if ((color & 0xFC000000) == 0) color |= 0xFF000000;
        int shadow = ((color & 0xFCFCFC) >> 2) | (color & 0xC8141414);
        int alphaBits = shadow & 0xFF000000;
        int cur = shadow;
        float cx = x + 0.9f;
        StringBuilder seg = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '§' && i + 1 < s.length()) {
                cx = flush(context, font, seg, cx, y + 0.7f, cur);
                Formatting f = Formatting.byCode(s.charAt(++i));
                if (f != null && f.isColor() && f.getColorValue() != null)
                    cur = alphaBits | ((f.getColorValue() & 0xFCFCFC) >> 2);
                else if (f == Formatting.RESET)
                    cur = shadow;
                continue;
            }
            seg.append(c);
        }
        flush(context, font, seg, cx, y + 0.7f, cur);
        font.drawString(context, s, x, y, color);
    }

    private static float flush(DrawContext context, FontRenderer font, StringBuilder seg, float x, float y, int color) {
        if (seg.isEmpty()) return x;
        String part = seg.toString();
        font.drawString(context, part, x, y, color);
        seg.setLength(0);
        return x + font.getStringWidth(part);
    }

    // ------------------------------------------------------------------ Translate

    /** Feature.getTranslate(): starts at (0, 0); moved by arrayListAnim with AnimationHelper.calculateCompensation. */
    static final class Translate {
        float x, y;

        void arrayListAnim(float targetX, float targetY, float xSpeed, float ySpeed, double frameTime) {
            int deltaX = (int) (Math.abs(targetX - x) * xSpeed);
            int deltaY = (int) (Math.abs(targetY - y) * ySpeed);
            x = calculateCompensation(targetX, x, (long) frameTime, deltaX);
            y = calculateCompensation(targetY, y, (long) frameTime, deltaY);
        }
    }

    /** AnimationHelper.calculateCompensation. */
    static float calculateCompensation(float target, float current, long delta, double speed) {
        float diff = current - target;
        if (delta < 1) delta = 1;
        if (delta > 1000) delta = 16;
        if (diff > speed) {
            double xD = (speed * delta / (1000 / 60) < 0.5 ? 0.5 : speed * delta / (1000 / 60));
            current -= xD;
            if (current < target) current = target;
        } else if (diff < -speed) {
            double xD = (speed * delta / (1000 / 60) < 0.5 ? 0.5 : speed * delta / (1000 / 60));
            current += xD;
            if (current > target) current = target;
        } else {
            current = target;
        }
        return current;
    }

    private static Color withAlpha(Color c, int a) {
        return new Color(c.getRed(), c.getGreen(), c.getBlue(), Math.max(0, Math.min(255, a)));
    }
}
