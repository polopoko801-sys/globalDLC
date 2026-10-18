package thunder.hack.gui.mainmenu;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.util.Identifier;
import org.jetbrains.annotations.Nullable;
import thunder.hack.gui.font.FontRenderers;
import thunder.hack.utility.CoverArt;
import thunder.hack.utility.NowPlaying;
import thunder.hack.utility.render.Render2DEngine;
import thunder.hack.utility.render.SpotifyIcons;
import thunder.hack.utility.spotify.SpotifyArt;
import thunder.hack.utility.spotify.SpotifyAuth;
import thunder.hack.utility.spotify.SpotifyPlayer;

import java.awt.Color;

import static thunder.hack.features.modules.Module.mc;

/**
 * The now-playing card under the clock: artwork, track, artist, progress and transport.
 *
 * <p>Deliberately NOT the HUD's {@code MusicWidget}. That one is a {@code Module} - it is positioned by the
 * HUD editor, saved in the module config and only drawn while the client HUD is up, none of which applies on
 * a title screen. What the two genuinely share is the data, and that is what is reused: the same
 * {@link SpotifyPlayer} snapshot when you are logged in, the same {@link NowPlaying} window-title fallback
 * when you are not, the same {@link SpotifyArt}/{@link CoverArt} caches, the same {@link SpotifyIcons}
 * glyphs. Nothing here fetches anything of its own.
 *
 * <p><b>One snapshot per frame.</b> Everything drawn comes out of a single read, so the cover, title, artist
 * and position are always the same track - the thing a panel assembled from separate calls gets wrong the
 * moment you skip.
 *
 * <p>Laid out from one {@link #WIDTH}/{@link #HEIGHT} pair so the whole card scales with the menu rather than
 * being pinned to one resolution.
 */
public final class MenuMusicPanel {
    private MenuMusicPanel() {
    }

    /** Card size in scaled pixels. Everything else is derived from these two. */
    public static final float WIDTH = 236f;
    public static final float HEIGHT = 74f;

    private static final float PAD = 10f;
    private static final float COVER = 44f;
    private static final float RADIUS = 13f;

    private static final Color PLATE = new Color(18, 18, 22, 198);
    private static final Color TITLE = new Color(0xF2F2F5);
    private static final Color ARTIST = new Color(0x9B9BA6);
    private static final Color STAMP = new Color(0x8A8A95);
    private static final Color TRACK_BG = new Color(255, 255, 255, 48);

    /** Where the buttons were last drawn, so a click can be resolved against what is on screen. */
    private static float prevX, playX, nextX, buttonY, buttonSize;
    private static float barLeft, barWidth, barY;

    /** One frame's worth of truth, from whichever source is live. */
    private record View(boolean present, boolean live, String title, String artist,
                        @Nullable Identifier cover, long position, long duration, boolean playing) {
    }

    private static View read() {
        if (SpotifyAuth.authorised()) {
            // Tells the poller the panel is on screen; without this it goes quiet after a few seconds.
            SpotifyPlayer.touch(1000L);

            SpotifyPlayer.Snapshot snapshot = SpotifyPlayer.state();
            return new View(snapshot.present(), true, snapshot.title(), snapshot.artist(),
                    SpotifyArt.of(snapshot.imageUrl()),
                    snapshot.progressMs(), snapshot.durationMs(), snapshot.playing());
        }

        NowPlaying.Track track = NowPlaying.current();
        if (track == null) return new View(false, false, "", "", null, 0L, 0L, false);

        return new View(true, false, track.title(), track.artist(),
                CoverArt.of(track.artist(), track.title()),
                // A window title carries no position and no length. Rather than invent either, the bar is left
                // empty and the stamps blank - the honest answer, and the reason the Spotify path exists.
                0L, 0L, true);
    }

    /**
     * Draws the card centred on {@code centreX}, with its top at {@code top}.
     *
     * @param alpha the transition's opacity, 0 to 1
     * @return false when there is nothing playing and the card should not be drawn at all
     */
    public static boolean render(DrawContext context, float centreX, float top, float alpha, int mouseX, int mouseY) {
        View view = read();
        if (!view.present() || alpha <= 0.01f) {
            buttonSize = 0f;
            return false;
        }

        float left = centreX - WIDTH / 2f;
        int a = Math.round(Math.clamp(alpha, 0f, 1f) * 255f);

        // A soft shadow under the card is most of what lifts it off the wallpaper - without it the plate reads
        // as a hole cut in the image rather than as something lying on top of it.
        Render2DEngine.drawBlurredShadow(context, left, top, WIDTH, HEIGHT, 14,
                new Color(0, 0, 0, Math.round(110 * alpha)));
        Render2DEngine.drawRound(context, left, top, WIDTH, HEIGHT, RADIUS, fade(PLATE, alpha));

        /* Artwork, drawn as served and framed rather than clipped - the engine has no rounded scissor. */
        float coverX = left + PAD, coverY = top + PAD;
        Render2DEngine.drawRound(context, coverX, coverY, COVER, COVER, 7f,
                new Color(10, 10, 13, Math.round(230 * alpha)));

        if (view.cover() != null)
            Render2DEngine.renderTexture(context, view.cover(), coverX + 1.5f, coverY + 1.5f,
                    COVER - 3f, COVER - 3f, 0, 0, COVER, COVER, COVER, COVER,
                    new Color(255, 255, 255, a));

        float textX = coverX + COVER + 11f;
        float textW = left + WIDTH - PAD - textX;

        FontRenderers.sf_bold_mini.drawString(context, clip(view.title(), textW),
                textX, top + PAD + 2f, fade(TITLE, alpha).getRGB());
        FontRenderers.sf_medium_mini.drawString(context, clip(view.artist(), textW),
                textX, top + PAD + 14f, fade(ARTIST, alpha).getRGB());

        drawProgress(context, view, textX, top, left, alpha);
        drawTransport(context, view, centreX, top, alpha, mouseX, mouseY);
        return true;
    }

    private static void drawProgress(DrawContext context, View view, float textX, float top, float left, float alpha) {
        float y = top + PAD + 30f;

        String elapsed = view.duration() > 0 ? SpotifyPlayer.stamp(view.position()) : "";
        String total = view.duration() > 0 ? SpotifyPlayer.stamp(view.duration()) : "";

        float right = left + WIDTH - PAD;
        float elapsedW = elapsed.isEmpty() ? 0f : FontRenderers.sf_medium_mini.getStringWidth(elapsed);
        float totalW = total.isEmpty() ? 0f : FontRenderers.sf_medium_mini.getStringWidth(total);

        if (!elapsed.isEmpty())
            FontRenderers.sf_medium_mini.drawString(context, elapsed, textX, y - 1f, fade(STAMP, alpha).getRGB());
        if (!total.isEmpty())
            FontRenderers.sf_medium_mini.drawString(context, total, right - totalW, y - 1f, fade(STAMP, alpha).getRGB());

        barLeft = textX;
        barWidth = Math.max(8f, right - textX);
        barY = y + 9f;

        Render2DEngine.drawRound(context, barLeft, barY, barWidth, 2f, 1f, fade(TRACK_BG, alpha));

        if (view.duration() <= 0) return;

        float fraction = Math.clamp(view.position() / (float) view.duration(), 0f, 1f);
        Render2DEngine.drawRound(context, barLeft, barY, barWidth * fraction, 2f, 1f,
                fade(new Color(0xE8E8ED), alpha));
    }

    private static void drawTransport(DrawContext context, View view, float centreX, float top,
                                      float alpha, int mouseX, int mouseY) {
        buttonSize = 11f;
        buttonY = top + HEIGHT - 20f;

        float gap = 26f;
        playX = centreX - buttonSize / 2f;
        prevX = playX - gap;
        nextX = playX + gap;

        Color idle = fade(new Color(0xD6D6DC), alpha);
        Color hot = fade(Color.WHITE, alpha);

        SpotifyIcons.previous(context, prevX, buttonY, buttonSize, hovered(mouseX, mouseY, prevX) ? hot : idle);
        if (view.playing())
            SpotifyIcons.pause(context, playX, buttonY, buttonSize, hovered(mouseX, mouseY, playX) ? hot : idle);
        else
            SpotifyIcons.play(context, playX, buttonY, buttonSize, hovered(mouseX, mouseY, playX) ? hot : idle);
        SpotifyIcons.next(context, nextX, buttonY, buttonSize, hovered(mouseX, mouseY, nextX) ? hot : idle);
    }

    /* ------------------------------------------------------------------ */
    /* Input                                                               */
    /* ------------------------------------------------------------------ */

    private static boolean hovered(double mx, double my, float x) {
        if (buttonSize <= 0f) return false;
        return mx >= x - 5 && mx <= x + buttonSize + 5 && my >= buttonY - 5 && my <= buttonY + buttonSize + 5;
    }

    /** True when the cursor is anywhere on the card, so the menu knows not to treat the click as "open". */
    public static boolean covers(double mx, double my, float centreX, float top) {
        float left = centreX - WIDTH / 2f;
        return mx >= left && mx <= left + WIDTH && my >= top && my <= top + HEIGHT;
    }

    /**
     * Handles a click on the card.
     *
     * @return true when the click was consumed, so the start screen does not also open the menu
     */
    public static boolean click(double mx, double my) {
        if (buttonSize <= 0f) return false;

        boolean live = SpotifyAuth.authorised();

        if (hovered(mx, my, prevX)) {
            if (live) SpotifyPlayer.previous();
            else media(VK_MEDIA_PREV_TRACK);
            return true;
        }
        if (hovered(mx, my, playX)) {
            if (live) SpotifyPlayer.toggle();
            else media(VK_MEDIA_PLAY_PAUSE);
            return true;
        }
        if (hovered(mx, my, nextX)) {
            if (live) SpotifyPlayer.next();
            else media(VK_MEDIA_NEXT_TRACK);
            return true;
        }

        // Seeking by clicking the bar, but only where the position is real.
        if (live && barWidth > 0f && my >= barY - 5 && my <= barY + 7 && mx >= barLeft && mx <= barLeft + barWidth) {
            long duration = SpotifyPlayer.state().durationMs();
            if (duration > 0) {
                float fraction = (float) Math.clamp((mx - barLeft) / barWidth, 0.0, 1.0);
                SpotifyPlayer.seek((long) (fraction * duration));
            }
            return true;
        }

        return false;
    }

    /* Windows virtual-key codes for the media keys - the fallback transport, same as the HUD widget uses.
     * AWT has no constants for these, so the press goes through user32. */
    private static final byte VK_MEDIA_NEXT_TRACK = (byte) 0xB0;
    private static final byte VK_MEDIA_PREV_TRACK = (byte) 0xB1;
    private static final byte VK_MEDIA_PLAY_PAUSE = (byte) 0xB3;
    private static final int KEYEVENTF_KEYUP = 0x0002;

    private interface User32 extends com.sun.jna.Library {
        User32 INSTANCE = com.sun.jna.Native.load("user32", User32.class);

        void keybd_event(byte key, byte scan, int flags, int extra);
    }

    private static void media(byte key) {
        try {
            User32.INSTANCE.keybd_event(key, (byte) 0, 0, 0);
            User32.INSTANCE.keybd_event(key, (byte) 0, KEYEVENTF_KEYUP, 0);
        } catch (Throwable ignored) {
            // Not Windows, or user32 is not loadable - nothing to do and nothing worth logging.
        }
    }

    /* ------------------------------------------------------------------ */
    /* Helpers                                                             */
    /* ------------------------------------------------------------------ */

    private static Color fade(Color base, float alpha) {
        return new Color(base.getRed(), base.getGreen(), base.getBlue(),
                Math.round(base.getAlpha() * Math.clamp(alpha, 0f, 1f)));
    }

    private static String clip(String text, float maxWidth) {
        if (text == null || text.isEmpty()) return "";
        var font = FontRenderers.sf_medium_mini;
        if (font.getStringWidth(text) <= maxWidth) return text;

        StringBuilder sb = new StringBuilder();
        for (char c : text.toCharArray()) {
            if (font.getStringWidth(sb + String.valueOf(c) + "...") > maxWidth) break;
            sb.append(c);
        }
        return sb + "...";
    }
}
