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

    /** Card size in scaled pixels - BKClient's MainMenuMusicCard (215 x 76). */
    public static final float WIDTH = thunder.hack.features.hud.impl.BkMusicUi.CARD_WIDTH;
    public static final float HEIGHT = thunder.hack.features.hud.impl.BkMusicUi.CARD_HEIGHT;

    /** BK's hover animations: the card (300 ms) and each button (200 ms), eased. */
    private static float cardHover;
    private static final float[] buttonHover = new float[3];
    private static long lastFrame;
    private static thunder.hack.features.hud.impl.BkMusicUi.Buttons lastButtons;

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
    public record View(boolean present, boolean live, String title, String artist,
                        @Nullable Identifier cover, long position, long duration, boolean playing) {
    }

    public static View read() {
        if (SpotifyAuth.authorised()) {
            // Tells the poller the panel is on screen; without this it goes quiet after a few seconds.
            SpotifyPlayer.touch(1000L);

            SpotifyPlayer.Snapshot snapshot = SpotifyPlayer.state();
            return new View(snapshot.present(), true, snapshot.title(), snapshot.artist(),
                    SpotifyArt.of(snapshot.imageUrl()),
                    snapshot.progressMs(), snapshot.durationMs(), snapshot.playing());
        }

        // Not logged in: the window title gives the track, the shared TitleClock the position (from when the
        // song changed, paused while Spotify is paused), and lrclib the length - so the bar and the minute work too.
        NowPlaying.Track track = thunder.hack.utility.spotify.TitleClock.current();
        if (track == null) return new View(false, false, "", "", null, 0L, 0L, false);

        // length: the Windows media session's when it has one, else lrclib's
        long sessionLength = thunder.hack.utility.spotify.TitleClock.duration();
        var lyrics = thunder.hack.utility.spotify.Lyrics.get(track.title(), track.artist(), sessionLength);
        long length = sessionLength > 0 ? sessionLength : lyrics != null ? lyrics.durationMs() : 0L;
        long position = thunder.hack.utility.spotify.TitleClock.position();
        return new View(true, false, track.title(), track.artist(),
                CoverArt.of(track.artist(), track.title()),
                length > 0 ? Math.min(position, length) : position, length,
                thunder.hack.utility.spotify.TitleClock.playing());
    }

    /**
     * Draws the card centred on {@code centreX}, with its top at {@code top}.
     *
     * @param alpha the transition's opacity, 0 to 1
     * @return false when there is nothing playing and the card should not be drawn at all
     */
    public static boolean render(DrawContext context, float centreX, float top, float alpha, int mouseX, int mouseY) {
        View view = read();
        if (alpha <= 0.01f) {
            buttonSize = 0f;
            return false;
        }
        // The card always sits under the clock; with nothing playing it says so and the transport still
        // sends the media keys, which is how you start something from here.
        if (!view.present())
            view = new View(true, view.live(), "Nic nie gra", "Włącz muzykę w Spotify", null, 0L, 0L, false);

        float left = centreX - WIDTH / 2f;

        // the current lyric, when MusicWidget's Lyrics setting is on and there is one to show
        boolean lyricsOn = lyricsOn();
        String lyric = lyricsOn ? lyricLine(view) : "";

        // BK's hover animations, eased toward their targets at BK's durations
        long now = System.nanoTime();
        float dt = lastFrame == 0L ? 0f : Math.min(0.1f, (now - lastFrame) / 1e9f);
        lastFrame = now;
        boolean onCard = mouseX >= left && mouseX <= left + WIDTH && mouseY >= top && mouseY <= top + HEIGHT;
        cardHover = approach(cardHover, onCard ? 1f : 0f, dt / 0.30f);
        var prev = lastButtons;
        for (int i = 0; i < 3; i++) {
            float[] r = prev == null ? null : i == 0 ? prev.previous() : i == 1 ? prev.play() : prev.next();
            boolean hov = thunder.hack.features.hud.impl.BkMusicUi.inside(r, mouseX, mouseY);
            buttonHover[i] = approach(buttonHover[i], hov ? 1f : 0f, dt / 0.20f);
        }

        var track = new thunder.hack.features.hud.impl.BkMusicUi.Track(true, view.title(), view.artist(), view.cover(),
                view.position(), view.duration(), view.playing(), lyric);
        lastButtons = thunder.hack.features.hud.impl.BkMusicUi.drawCard(context, left, top, track,
                easeInOut(cardHover), new float[]{easeInOut(buttonHover[0]), easeInOut(buttonHover[1]), easeInOut(buttonHover[2])},
                Math.clamp(alpha, 0f, 1f));
        float[] p = lastButtons.play();
        buttonSize = 16f;
        buttonY = p[1];
        prevX = lastButtons.previous()[0];
        playX = p[0];
        nextX = lastButtons.next()[0];
        barLeft = left + 11f;
        barWidth = WIDTH - 22f;
        barY = top + 10f + 32f + 10f;

        if (lyricsOn) {
            String shown = lyric.isEmpty() ? "..." : lyric;
            FontRenderers.sf_medium_mini.drawCenteredString(context, clip(shown, WIDTH - PAD * 2), centreX,
                    top + HEIGHT + 2f, fade(lyric.isEmpty() ? STAMP : TITLE, alpha).getRGB());
        }
        return true;
    }

    /* ------------------------------------------------------------------ */
    /* Lyrics                                                              */
    /* ------------------------------------------------------------------ */

    /** Extra height the card grows by while the lyric line is shown. */
    private static final float LYRIC_ROW = 16f;

    /** Switched in the MusicWidget module's settings (Lyrics), not on the card. */
    private static boolean lyricsOn() {
        return thunder.hack.core.manager.client.ModuleManager.musicWidget.showsLyrics();
    }

    /** The card's height right now, lyric row included. */
    public static float height() {
        return HEIGHT + (lyricsOn() ? LYRIC_ROW : 0f);
    }

    private static String lyricLine(View view) {
        // live: Spotify's own length helps lrclib match; window-title mode looks up by name alone
        var lyrics = thunder.hack.utility.spotify.Lyrics.get(view.title(), view.artist(), view.live() ? view.duration() : 0L);
        return lyrics == null || !lyrics.synced() ? "" : lyrics.lineAt(view.position());
    }

    /**
     * Spotify's bottom row, across the whole card: elapsed time on the left, the bar, the length on the right.
     * With no real position (not logged in) the stamps read "--:--" and the bar stays empty rather than guess.
     */
    private static void drawProgress(DrawContext context, View view, float textX, float top, float left, float alpha) {
        var font = FontRenderers.sf_medium_mini;
        boolean known = view.duration() > 0;
        String elapsed = SpotifyPlayer.stamp(view.position());   // the clock always runs; only the length can be unknown
        String total = known ? SpotifyPlayer.stamp(view.duration()) : "--:--";

        float rowY = top + HEIGHT - 15f;                  // text baseline row at the bottom of the card
        float l = left + PAD + 2f, r = left + WIDTH - PAD - 2f;
        float elapsedW = font.getStringWidth(elapsed), totalW = font.getStringWidth(total);

        font.drawString(context, elapsed, l, rowY, fade(STAMP, alpha).getRGB());
        font.drawString(context, total, r - totalW, rowY, fade(STAMP, alpha).getRGB());

        barLeft = l + elapsedW + 7f;
        barWidth = Math.max(8f, (r - totalW - 7f) - barLeft);
        barY = rowY + 3f;

        Render2DEngine.drawRound(context, barLeft, barY, barWidth, 2f, 1f, fade(TRACK_BG, alpha));

        if (view.duration() <= 0) return;

        float fraction = Math.clamp(view.position() / (float) view.duration(), 0f, 1f);
        Render2DEngine.drawRound(context, barLeft, barY, barWidth * fraction, 2f, 1f,
                fade(new Color(0xE8E8ED), alpha));
    }

    private static void drawTransport(DrawContext context, View view, float centreX, float top,
                                      float alpha, int mouseX, int mouseY) {
        buttonSize = 11f;
        buttonY = top + PAD + 33f;   // under the title and artist; the bottom row is the progress bar

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

    /** BK's hit box: 16 x 16 around each 10 px control (prevX/playX/nextX already hold the box corner). */
    private static boolean hovered(double mx, double my, float x) {
        if (buttonSize <= 0f) return false;
        return mx >= x && mx <= x + buttonSize && my >= buttonY && my <= buttonY + buttonSize;
    }

    private static float approach(float v, float target, float step) {
        return v < target ? Math.min(target, v + step) : Math.max(target, v - step);
    }

    /** BK's FIGMA_EASE_IN_OUT, close enough as a cubic in-out. */
    private static float easeInOut(float x) {
        return x < 0.5f ? 4f * x * x * x : 1f - (float) Math.pow(-2f * x + 2f, 3) / 2f;
    }

    /** True when the cursor is anywhere on the card, so the menu knows not to treat the click as "open". */
    public static boolean covers(double mx, double my, float centreX, float top) {
        float left = centreX - WIDTH / 2f;
        return mx >= left && mx <= left + WIDTH && my >= top && my <= top + height();
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

    /** Transport for other surfaces (the Dynamic Island): Spotify when logged in, the media keys otherwise. */
    public static void previous() {
        if (SpotifyAuth.authorised()) SpotifyPlayer.previous();
        else media(VK_MEDIA_PREV_TRACK);
    }

    public static void toggle() {
        if (SpotifyAuth.authorised()) SpotifyPlayer.toggle();
        else media(VK_MEDIA_PLAY_PAUSE);
    }

    public static void next() {
        if (SpotifyAuth.authorised()) SpotifyPlayer.next();
        else media(VK_MEDIA_NEXT_TRACK);
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
        return clip(FontRenderers.sf_medium_mini, text, maxWidth);
    }

    /** Clips by the font the text is actually drawn in - measuring bold text with the regular font let it overflow. */
    private static String clip(thunder.hack.gui.font.FontRenderer font, String text, float maxWidth) {
        if (text == null || text.isEmpty()) return "";
        if (font.getStringWidth(text) <= maxWidth) return text;

        StringBuilder sb = new StringBuilder();
        for (char c : text.toCharArray()) {
            if (font.getStringWidth(sb + String.valueOf(c) + "...") > maxWidth) break;
            sb.append(c);
        }
        return sb + "...";
    }
}
