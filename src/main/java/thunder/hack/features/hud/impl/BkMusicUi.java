package thunder.hack.features.hud.impl;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.util.Identifier;
import org.joml.Matrix3x2f;
import thunder.hack.features.modules.client.HudEditor;
import thunder.hack.gui.font.BkFonts;
import thunder.hack.gui.font.FontRenderer;
import thunder.hack.utility.render.BkLiquidGlass;
import thunder.hack.utility.render.GuiRender;
import thunder.hack.utility.render.Render2DEngine;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;

/**
 * BKClient's music UI drawn 1:1 (layout from BK's {@code MusicPlayer} and {@code MainMenuMusicCard}, MIT,
 * bkubiakdev): the compact HUD player, the big player with live lyrics, and the main-menu card - same sizes,
 * offsets, fonts (SF Pro Display), colours (dark theme), slider and icons. Only the data source is GlobalDLC's
 * own (Spotify / Windows media session / lrclib), handed in as a {@link Track}.
 */
public final class BkMusicUi {
    private BkMusicUi() {
    }

    public static final Identifier NO_IMAGE = Identifier.of("bkclient", "icons/music/no_image.png");
    public static final Identifier PREVIOUS = Identifier.of("bkclient", "icons/music/previous.png");
    public static final Identifier NEXT = Identifier.of("bkclient", "icons/music/next.png");
    public static final Identifier PAUSE = Identifier.of("bkclient", "icons/music/pause.png");
    public static final Identifier PLAY = Identifier.of("bkclient", "icons/music/play.png");

    /** One frame of what is playing. Positions in milliseconds. */
    public record Track(boolean available, String title, String artist, Identifier cover,
                        long positionMs, long durationMs, boolean playing, String lyric) {
    }

    /** Clickable control regions of the frame just drawn (unscaled widget coordinates). */
    public record Buttons(float[] previous, float[] play, float[] next, float[] cover) {
    }

    // ---- BK MusicPlayer constants ----
    public static final float WIDTH = 116f, HEIGHT = 76f;
    public static final float HEADER_HEIGHT = 22f, INFO_HEIGHT = 25f, PROGRESS_HEIGHT = 13f, CONTROLS_HEIGHT = 16f;
    public static final float SIDE_PADDING = 7f;
    public static final float BIG_WIDTH = 146f, BIG_HEIGHT = 208f;

    // ---- BK dark theme ----
    private static final Color TEXT = Color.WHITE;

    public static Color accent() {
        return HudEditor.getColor(0);
    }

    static Color alpha(Color c, float a) {
        return new Color(c.getRed(), c.getGreen(), c.getBlue(), Math.max(0, Math.min(255, Math.round(a))));
    }

    static Color mulAlpha(Color c, float m) {
        return alpha(c, c.getAlpha() * m);
    }

    // ------------------------------------------------------------------ compact

    public static float compactHeight(boolean controls) {
        return controls ? HEIGHT : HEIGHT - CONTROLS_HEIGHT;
    }

    public static Buttons drawCompact(DrawContext ctx, float x, float y, Track t, boolean controls, boolean showArtist,
                                      boolean coverRight, String headerTitle, float alpha) {
        float w = WIDTH, h = compactHeight(controls);
        // HudList.renderContent: panel, accent glow, header title and icon
        BkLiquidGlass.panel(ctx, x, y, w, h, 7f, alpha);
        accentGlow(ctx, x, y, w, h, alpha, 7f);
        FontRenderer header = BkFonts.MEDIUM.get(7f);
        header.drawString(ctx, headerTitle, x + 7f, y + (18f - 7f) / 2f + 0.5f, mulAlpha(TEXT, alpha).getRGB());
        Render2DEngine.renderTexture(ctx, PLAY, x + w - 8f - 7f, y + 6f, 8, 8, 0, 0, 8, 8, 8, 8, mulAlpha(TEXT, alpha));

        // track info
        float infoY = y + HEADER_HEIGHT;
        float artSize = 17f;
        float artX = coverRight ? x + w - SIDE_PADDING - 17f : x + SIDE_PADDING;
        float artY = infoY + 4f;
        drawCover(ctx, t, artX, artY, artSize, 5f, 8f, alpha, 65f);
        FontRenderer titleFont = BkFonts.MEDIUM.get(7f);
        FontRenderer artistFont = BkFonts.REGULAR.get(6f);
        float textX = coverRight ? x + SIDE_PADDING : artX + artSize + 6f;
        float maxWidth = w - SIDE_PADDING * 2 - artSize - 6f;
        titleFont.drawString(ctx, ellipsize(titleFont, t.title(), maxWidth), textX, infoY + (showArtist ? 4.5f : 9f), mulAlpha(TEXT, alpha).getRGB());
        if (showArtist)
            artistFont.drawString(ctx, ellipsize(artistFont, t.artist(), maxWidth), textX, infoY + 14f, alpha(TEXT, 140 * alpha).getRGB());

        // progress
        float py = infoY + INFO_HEIGHT;
        Render2DEngine.drawRect(ctx, x, py, w, 0.5f, alpha(TEXT, 5.1f * alpha));
        FontRenderer time = BkFonts.MEDIUM.get(5f);
        float textY = py + (PROGRESS_HEIGHT - 5f) / 2f;
        time.drawString(ctx, formatTime(t.positionMs() / 1000), x + SIDE_PADDING, textY, mulAlpha(TEXT, alpha).getRGB());
        String total = t.durationMs() <= 0 ? "Live" : formatTime(t.durationMs() / 1000);
        time.drawString(ctx, total, x + w - SIDE_PADDING - time.getStringWidth(total), textY, mulAlpha(TEXT, alpha).getRGB());
        slider(ctx, x + 25f, py + 5.5f, w - 50f, progress(t), t.durationMs() > 0, alpha);

        if (!controls) return new Buttons(null, null, null, new float[]{artX, artY, artSize, artSize});

        // controls
        float cy = py + PROGRESS_HEIGHT;
        Render2DEngine.drawRect(ctx, x, cy, w, 0.5f, alpha(TEXT, 5.1f * alpha));
        float icon = 7f;
        float iconY = cy + (CONTROLS_HEIGHT - icon) / 2f;
        float center = x + w / 2f;
        icon(ctx, PREVIOUS, center - 22f, iconY, icon, mulAlpha(TEXT, alpha));
        float playBox = 12f, playX = center - playBox / 2f, playY = cy + (CONTROLS_HEIGHT - playBox) / 2f;
        Render2DEngine.drawRound(ctx, playX, playY, playBox, playBox, 5f, alpha(accent(), 190f * alpha));
        icon(ctx, t.playing() || !t.available() ? PAUSE : PLAY, center - 3.5f, playY + 2.5f, icon, mulAlpha(TEXT, alpha));
        icon(ctx, NEXT, center + 15f, iconY, icon, mulAlpha(TEXT, alpha));
        return new Buttons(new float[]{center - 25f, cy, 13f, CONTROLS_HEIGHT},
                new float[]{center - 8f, cy, 16f, CONTROLS_HEIGHT},
                new float[]{center + 12f, cy, 13f, CONTROLS_HEIGHT},
                new float[]{artX, artY, artSize, artSize});
    }

    // ------------------------------------------------------------------ big

    public static float bigHeight(boolean controls) {
        return controls ? BIG_HEIGHT : BIG_HEIGHT - 22f;
    }

    /** Lyric cross-fade state, owned by the caller so each widget animates on its own. */
    public static final class LyricFade {
        String last = "", prev = "";
        float fade = 1f;
    }

    public static Buttons drawBig(DrawContext ctx, float x, float y, Track t, boolean controls, boolean showArtist,
                                  LyricFade lf, float alpha) {
        float w = BIG_WIDTH, h = bigHeight(controls);
        BkLiquidGlass.panel(ctx, x, y, w, h, 14f, alpha);
        accentGlow(ctx, x, y, w, h, alpha, 7f);

        float pad = 8f, artW = w - pad * 2f, artH = 130f, artX = x + pad, artY = y + pad;
        // 1. cover
        if (t.cover() != null) roundedTexture(ctx, t.cover(), artX, artY, artW, artH, 10f, alpha(Color.WHITE, 255 * alpha));
        else {
            Render2DEngine.drawRound(ctx, artX, artY, artW, artH, 10f, alpha(accent(), 65f * alpha));
            icon(ctx, NO_IMAGE, artX + (artW - 28f) / 2f, artY + (artH - 28f) / 2f, 28f, mulAlpha(TEXT, alpha));
        }
        // dark gradient over the lower 60% of the cover (BK: BorderRadius.bottom(10), transparent -> black 225)
        Color clear = new Color(0, 0, 0, 0), dark = new Color(0, 0, 0, Math.round(225 * alpha));
        Render2DEngine.drawGradientRound(ctx, artX, artY + artH * 0.40f, artW, artH * 0.60f, 10f, clear, dark, clear, dark);

        // 2. title & artist on the cover
        FontRenderer titleFont = BkFonts.SEMIBOLD.get(7.2f);
        FontRenderer artistFont = BkFonts.MEDIUM.get(5.6f);
        float metaW = artW - 16f;
        String title = ellipsize(titleFont, t.title(), metaW);
        titleFont.drawString(ctx, title, artX + 8 + (metaW - titleFont.getStringWidth(title)) / 2f,
                artY + artH - (showArtist ? 23 : 17), alpha(Color.WHITE, 255 * alpha).getRGB());
        if (showArtist)
            artistFont.drawCenteredString(ctx, ellipsize(artistFont, t.artist(), metaW), artX + artW / 2f, artY + artH - 12f,
                    alpha(Color.WHITE, 165 * alpha).getRGB());

        // 3. live lyric line, cross-faded like BK's
        float lyricsY = artY + artH + 8.5f;
        String lyric = t.lyric() == null ? "" : t.lyric();
        if (!lyric.equals(lf.last)) {
            lf.prev = lf.last;
            lf.last = lyric;
            lf.fade = 0f;
        }
        if (lf.fade < 1f) lf.fade = Math.min(1f, lf.fade + 0.09f);
        float ease = (float) (1.0 - Math.pow(1.0 - lf.fade, 3));
        float centerX = artX + artW / 2f;
        if (lf.fade < 1f && !lf.prev.isBlank()) {
            float pa = 1f - ease, prevY = lyricsY - ease * 4f;
            FontRenderer f = fitted(lf.prev, artW);
            String s = ellipsize(f, lf.prev, artW - 8f);
            f.drawCenteredString(ctx, s, centerX + 0.5f, prevY + 0.5f, alpha(Color.BLACK, 90 * pa * alpha).getRGB());
            f.drawCenteredString(ctx, s, centerX, prevY, alpha(Color.WHITE, 255 * pa * alpha).getRGB());
        }
        if (!lyric.isBlank()) {
            float curY = lyricsY + (1f - ease) * 4f;
            FontRenderer f = fitted(lyric, artW);
            String s = ellipsize(f, lyric, artW - 8f);
            f.drawCenteredString(ctx, s, centerX + 0.5f, curY + 0.5f, alpha(Color.BLACK, 100 * ease * alpha).getRGB());
            f.drawCenteredString(ctx, s, centerX, curY, alpha(Color.WHITE, 255 * ease * alpha).getRGB());
        }

        // 4. progress & timestamps
        float barY = lyricsY + 15f;
        FontRenderer time = BkFonts.MEDIUM.get(5.2f);
        String total = t.durationMs() <= 0 ? "Live" : formatTime(t.durationMs() / 1000);
        time.drawString(ctx, formatTime(t.positionMs() / 1000), artX, barY - 1f, alpha(TEXT, 190 * alpha).getRGB());
        time.drawString(ctx, total, artX + artW - time.getStringWidth(total), barY - 1f, alpha(TEXT, 190 * alpha).getRGB());
        slider(ctx, artX + 22f, barY + 1.5f, artW - 44f, progress(t), t.durationMs() > 0, alpha);

        float[] cover = {artX, artY, artW, artH};
        if (!controls) return new Buttons(null, null, null, cover);

        // 5. controls
        float controlsY = barY + 13f, center = x + w / 2f, iconSize = 8.5f;
        icon(ctx, PREVIOUS, center - 24f, controlsY, iconSize, alpha(TEXT, 220 * alpha));
        float playBox = 16f, playX = center - playBox / 2f, playY = controlsY - 3.5f;
        Render2DEngine.drawRound(ctx, playX, playY, playBox, playBox, 8f, alpha(accent(), 200 * alpha));
        icon(ctx, t.playing() || !t.available() ? PAUSE : PLAY, center - 4.5f, playY + 3.5f, 9f, mulAlpha(TEXT, alpha));
        icon(ctx, NEXT, center + 16f, controlsY, iconSize, alpha(TEXT, 220 * alpha));
        return new Buttons(new float[]{center - 32f, controlsY - 5f, 18f, 21f},
                new float[]{center - 12f, controlsY - 5f, 24f, 21f},
                new float[]{center + 14f, controlsY - 5f, 18f, 21f}, cover);
    }

    // ------------------------------------------------------------------ main menu card

    public static final float CARD_WIDTH = 215f, CARD_HEIGHT = 76f;
    private static final Color CARD_BG = new Color(58, 58, 58);

    /** BK's MainMenuMusicCard; {@code hover} is the card hover animation 0..1. Returns its control regions. */
    public static Buttons drawCard(DrawContext ctx, float x, float y, Track t, float hover, float[] buttonHover, float alpha) {
        float W = CARD_WIDTH, H = CARD_HEIGHT, PAD = 11f, ART = 32f, CTRL = 10f;
        // liquid glass with BK's 0.08 distortion, then the card tint
        if (BkLiquidGlass.glass(ctx, x - 1f, y - 1f, W + 2f, H + 2f, 16f, 0.08f, alpha))
            Render2DEngine.drawRound(ctx, x, y, W, H, 16f, alpha(CARD_BG, 255f * (0.15f * alpha + 0.08f * hover * alpha)));
        else
            Render2DEngine.drawRound(ctx, x, y, W, H, 16f, alpha(CARD_BG, 255f * (0.33f * alpha + 0.15f * hover * alpha)));

        float artX = x + PAD, artY = y + 10f;
        if (t.cover() != null) roundedTexture(ctx, t.cover(), artX, artY, ART, ART, 8f, alpha(Color.WHITE, 255 * alpha));
        else {
            Render2DEngine.drawRound(ctx, artX, artY, ART, ART, 8f, alpha(CARD_BG, 255f * 0.35f * alpha));
            icon(ctx, NO_IMAGE, artX + (ART - 14f) / 2f, artY + (ART - 14f) / 2f, 14f, alpha(Color.WHITE, 180 * alpha));
        }
        FontRenderer titleFont = BkFonts.MEDIUM.get(8f), artistFont = BkFonts.REGULAR.get(7f);
        float textX = artX + ART + 10f, maxW = x + W - PAD - textX;
        titleFont.drawString(ctx, ellipsize(titleFont, t.title(), maxW), textX, artY + 3f, alpha(Color.WHITE, 240 * alpha).getRGB());
        artistFont.drawString(ctx, ellipsize(artistFont, t.artist(), maxW), textX, artY + 16f, alpha(Color.WHITE, 140 * alpha).getRGB());

        float barY = artY + ART + 10f, barX = x + PAD, barW = W - PAD * 2f;
        FontRenderer time = BkFonts.REGULAR.get(6f);
        String total = t.durationMs() <= 0 ? "Live" : formatTime(t.durationMs() / 1000);
        time.drawString(ctx, formatTime(t.positionMs() / 1000), barX, barY - 7.5f, alpha(Color.WHITE, 140 * alpha).getRGB());
        time.drawString(ctx, total, barX + barW - time.getStringWidth(total), barY - 7.5f, alpha(Color.WHITE, 140 * alpha).getRGB());
        slider(ctx, barX, barY, barW, progress(t), t.durationMs() > 0, alpha);

        float controlsY = barY + 9f, center = x + W / 2f;
        float prevX = center - 26f, playX = center - CTRL / 2f, nextX = center + 16f;
        icon(ctx, PREVIOUS, prevX, controlsY, CTRL, alpha(Color.WHITE, (190 + 65 * buttonHover[0]) * alpha));
        icon(ctx, t.playing() || !t.available() ? PAUSE : PLAY, playX, controlsY, CTRL, alpha(Color.WHITE, (220 + 35 * buttonHover[1]) * alpha));
        icon(ctx, NEXT, nextX, controlsY, CTRL, alpha(Color.WHITE, (190 + 65 * buttonHover[2]) * alpha));
        float hit = 16f, o = (hit - CTRL) / 2f;
        return new Buttons(new float[]{prevX - o, controlsY - o, hit, hit}, new float[]{playX - o, controlsY - o, hit, hit},
                new float[]{nextX - o, controlsY - o, hit, hit}, new float[]{artX, artY, ART, ART});
    }

    // ------------------------------------------------------------------ pieces

    /** BK's MusicSlider: 1.5px rail (white 27/255), accent fill, 5.5x3.5 white thumb with a soft shadow. */
    public static void slider(DrawContext ctx, float x, float y, float width, float end, boolean thumb, float alpha) {
        if (width <= 0) return;
        float rail = 1.5f, centerY = y + 1f;
        float b = width * Math.max(0f, Math.min(1f, end));
        Render2DEngine.drawRound(ctx, x, centerY - rail / 2f, width, rail, rail / 2f, alpha(TEXT, 27 * alpha));
        if (b > 0) Render2DEngine.drawRound(ctx, x, centerY - rail / 2f, b, rail, Math.min(rail, b) / 2f, mulAlpha(accent(), alpha));
        if (!thumb) return;
        float tw = Math.min(5.5f, width), th = 3.5f;
        float tip = x + Math.max(tw / 2f, Math.min(width - tw / 2f, b));
        Render2DEngine.drawBlurredShadow(ctx, tip - tw / 2f, centerY - th / 2f, tw, th, 5, alpha(Color.BLACK, 64 * alpha));
        Render2DEngine.drawRound(ctx, tip - tw / 2f, centerY - th / 2f, tw, th, Math.min(1.5f, tw / 2f), alpha(Color.WHITE, 255 * alpha));
    }

    /** BK's HudTheme.drawAccentGlow: accent from 51/255 at the left edge to clear over 69% of the width. */
    static void accentGlow(DrawContext ctx, float x, float y, float w, float h, float alpha, float radius) {
        if (w <= 0 || h <= 0) return;
        float gw = w * 0.69f, r = Math.min(radius, Math.min(h, gw) / 2f);
        Color a = accent(), from = alpha(a, 51f * Math.max(0f, Math.min(1f, alpha))), to = alpha(a, 0);
        // drawGradientRound corner order: top-right, bottom-right, top-left, bottom-left
        Render2DEngine.drawGradientRound(ctx, x, y, gw, h, r, to, to, from, from);
    }

    static void drawCover(DrawContext ctx, Track t, float x, float y, float size, float radius, float glyph, float alpha, float placeholderAlpha) {
        if (t.cover() != null) {
            roundedTexture(ctx, t.cover(), x, y, size, size, radius, alpha(Color.WHITE, 255 * alpha));
            return;
        }
        Render2DEngine.drawRound(ctx, x, y, size, size, radius, alpha(accent(), placeholderAlpha * alpha));
        icon(ctx, NO_IMAGE, x + (size - glyph) / 2f, y + (size - glyph) / 2f, glyph, mulAlpha(TEXT, alpha));
    }

    static void icon(DrawContext ctx, Identifier id, float x, float y, float size, Color color) {
        Render2DEngine.renderTexture(ctx, id, x, y, size, size, 0, 0, size, size, size, size, color);
    }

    /** A texture clipped to a rounded rectangle: the rounded outline as a triangle fan with matching UVs. */
    public static void roundedTexture(DrawContext ctx, Identifier texture, float x, float y, float w, float h, float r, Color tint) {
        r = Math.max(0f, Math.min(r, Math.min(w, h) / 2f));
        List<float[]> pts = new ArrayList<>();
        int seg = Math.max(4, Math.min(16, Math.round(r * 1.5f)));
        corner(pts, x + w - r, y + r, r, -90, 0, seg);
        corner(pts, x + w - r, y + h - r, r, 0, 90, seg);
        corner(pts, x + r, y + h - r, r, 90, 180, seg);
        corner(pts, x + r, y + r, r, 180, 270, seg);
        Matrix3x2f pose = GuiRender.pose(ctx);
        int argb = tint.getRGB();
        float cx = x + w / 2f, cy = y + h / 2f;
        GuiRender.textured(ctx, texture, Render2DEngine.currentClip(), vc -> {
            for (int i = 0; i < pts.size(); i++) {
                float[] a = pts.get(i), b = pts.get((i + 1) % pts.size());
                GuiRender.vertexTex(vc, pose, cx, cy, 0.5f, 0.5f, argb);
                GuiRender.vertexTex(vc, pose, a[0], a[1], (a[0] - x) / w, (a[1] - y) / h, argb);
                GuiRender.vertexTex(vc, pose, b[0], b[1], (b[0] - x) / w, (b[1] - y) / h, argb);
                GuiRender.vertexTex(vc, pose, b[0], b[1], (b[0] - x) / w, (b[1] - y) / h, argb);
            }
        });
    }

    private static void corner(List<float[]> pts, float cx, float cy, float r, float from, float to, int seg) {
        for (int i = 0; i <= seg; i++) {
            double a = Math.toRadians(from + (to - from) * i / seg);
            pts.add(new float[]{(float) (cx + Math.cos(a) * r), (float) (cy + Math.sin(a) * r)});
        }
    }

    static float progress(Track t) {
        return t.durationMs() <= 0 ? 0f : Math.min(1f, t.positionMs() / (float) t.durationMs());
    }

    private static FontRenderer fitted(String text, float maxWidth) {
        float base = 6.2f;
        FontRenderer font = BkFonts.SEMIBOLD.get(base);
        float tw = font.getStringWidth(text);
        if (tw > maxWidth && tw > 0f) {
            float scale = Math.max(0.70f, Math.min(1f, maxWidth / tw));
            return BkFonts.SEMIBOLD.get(Math.max(4.5f, Math.round(base * scale * 10f) / 10f));
        }
        return font;
    }

    public static String ellipsize(FontRenderer font, String text, float maxWidth) {
        if (text == null || text.isBlank() || maxWidth <= 0f) return "";
        String value = text.trim();
        if (font.getStringWidth(value) <= maxWidth) return value;
        String suffix = "...";
        if (font.getStringWidth(suffix) > maxWidth) return "";
        int low = 0, high = value.length();
        while (low < high) {
            int mid = (low + high + 1) >>> 1;
            if (font.getStringWidth(value.substring(0, mid).stripTrailing() + suffix) <= maxWidth) low = mid;
            else high = mid - 1;
        }
        return value.substring(0, low).stripTrailing() + suffix;
    }

    public static String formatTime(long seconds) {
        seconds = Math.max(0, seconds);
        return String.format("%d:%02d", seconds / 60L, seconds % 60L);
    }

    public static boolean inside(float[] r, double mx, double my) {
        return r != null && mx >= r[0] && mx <= r[0] + r[2] && my >= r[1] && my <= r[1] + r[3];
    }
}
