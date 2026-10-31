package thunder.hack.features.hud.impl;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.util.Identifier;
import org.jetbrains.annotations.NotNull;
import org.lwjgl.glfw.GLFW;
import thunder.hack.core.manager.client.ConfigManager;
import thunder.hack.events.impl.EventMouse;
import thunder.hack.features.hud.HudElement;
import thunder.hack.features.modules.client.HudEditor;
import thunder.hack.gui.font.FontRenderer;
import thunder.hack.gui.font.FontRenderers;
import thunder.hack.gui.spotify.SpotifyScreen;
import thunder.hack.setting.Setting;
import thunder.hack.setting.impl.ColorSetting;
import thunder.hack.utility.NowPlaying;
import thunder.hack.utility.Timer;
import thunder.hack.utility.render.Render2DEngine;
import thunder.hack.utility.render.SpotifyIcons;
import thunder.hack.utility.render.TextureStorage;
import thunder.hack.utility.spotify.SpotifyArt;
import thunder.hack.utility.spotify.SpotifyAuth;
import thunder.hack.utility.spotify.SpotifyLibrary;
import thunder.hack.utility.spotify.SpotifyModels.Entry;
import thunder.hack.utility.spotify.SpotifyModels.Repeat;
import thunder.hack.utility.spotify.SpotifyPlayer;

import java.awt.Color;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The now-playing island, and - when you are logged in - a working Spotify remote.
 *
 * <p><b>Two sources, one widget.</b> Without a Spotify login this reads the window title of whatever is
 * playing, exactly as it always did: a title, a guessed cover, elapsed time measured locally, and buttons
 * that press the system media keys. With a login it talks to the Spotify Web API instead, and everything
 * the HUD could only approximate becomes real - the true position inside the track, the duration, the
 * album, the album art that belongs to this track id, the device, shuffle and repeat, your library, your
 * playlists, the queue. The widget looks the same either way; what differs is how much of it is honest.
 *
 * <p><b>Why the snapshot matters here.</b> Everything drawn in one frame comes out of a single
 * {@link SpotifyPlayer.Snapshot}, so the cover, title, artist, position and play state are always the same
 * track. That is the one thing a music HUD built from separate calls always gets wrong.
 *
 * <p><b>Nothing here waits.</b> No draw call makes a request. The poller in {@link SpotifyPlayer} fills a
 * value in memory and this reads it; the only thing render does is say "I am visible" so the poller knows
 * to keep going. Hide the widget and the network traffic stops by itself.
 *
 * <p><b>Spotify's content.</b> Album art is drawn as served - never cropped, tinted or composited on. Any
 * cover, title or row can be opened in Spotify, and the island carries the service's name whenever it is
 * showing the service's data.
 */
public class MusicWidget extends HudElement {
    public MusicWidget() {
        super("MusicWidget", 150, 32);
    }

    /** Where the track comes from. */
    public enum Source {
        /** Spotify when logged in, the window title otherwise. */
        Auto,
        /** Spotify only - says so rather than falling back, which is what you want while setting it up. */
        Spotify,
        /** The window title, even when logged in. */
        System
    }

    public enum Layout {
        /** One line: cover, track, artist, save, play. */
        Compact,
        /** The full panel: big cover, progress, transport, shuffle, repeat, volume. */
        Expanded
    }

    /** The word highlight: off, the client accent, or plain white. */
    public enum Highlight {
        Off, Accent, White
    }

    private final Setting<Source> source = new Setting<>("Source", Source.Auto);
    private final Setting<Layout> layout = new Setting<>("Layout", Layout.Expanded);
    private final Setting<Float> scale = new Setting<>("Scale", 1.3f, 0.6f, 3f);

    private final Setting<Boolean> showCover = new Setting<>("Cover", true);
    /**
     * Look the artwork up from the track name - the fallback path only.
     *
     * <p>Irrelevant with a Spotify login, where the image URL arrives inside the track itself and no
     * guessing is involved, so it hides itself in that case.
     */
    private final Setting<Boolean> artwork = new Setting<>("Artwork", true,
            v -> showCover.getValue() && !spotify());

    private final Setting<Boolean> showProgress = new Setting<>("Progress", true);
    private final Setting<Boolean> controls = new Setting<>("Controls", true);
    private final Setting<Boolean> showAlbum = new Setting<>("Album", true,
            v -> layout.getValue() == Layout.Expanded);
    /** The Spotify track id, under the album line. Off by default - it is a debugging aid, not a label. */
    private final Setting<Boolean> showTrackId = new Setting<>("TrackID", false,
            v -> layout.getValue() == Layout.Expanded);
    private final Setting<Boolean> showDevice = new Setting<>("Device", true,
            v -> layout.getValue() == Layout.Expanded);
    /** One line of "next up", straight off the real queue. */
    private final Setting<Boolean> upNext = new Setting<>("UpNext", true,
            v -> layout.getValue() == Layout.Expanded);

    private final Setting<Highlight> highlight = new Setting<>("Highlight", Highlight.Accent);
    private final Setting<Integer> wordMs = new Setting<>("WordMs", 480, 120, 2000,
            v -> highlight.getValue() != Highlight.Off);

    /** Glass: the real blurred backdrop behind the panel, with the plate reduced to a light veil over it. */
    private final Setting<Boolean> blur = new Setting<>("Glass", true);
    /** The current line of the song, fetched from lrclib.net (free, synced where available). */
    private final Setting<Boolean> lyrics = new Setting<>("Lyrics", true);

    /** The Lyrics switch - the main menu's music card follows it too. */
    public boolean showsLyrics() {
        return lyrics.getValue();
    }
    private final Setting<ColorSetting> plateColor = new Setting<>("PlateColor", new ColorSetting(new Color(22, 22, 28, 235)));
    /**
     * How often the player state is refreshed.
     *
     * <p>This is a poll rate against a rate-limited API, not a frame rate: the progress bar is smooth
     * regardless, because position is carried forward from a clock between polls. A second is plenty.
     */
    private final Setting<Integer> refreshMs = new Setting<>("RefreshMs", 1000, 800, 5000);

    /** When the buttons accept clicks. */
    public enum Interact {
        /** Only while a screen is open - chat, the HUD editor, an inventory. */
        Screens,
        /** Always, including with the cursor grabbed. */
        Always
    }

    /**
     * Default {@link Interact#Screens}.
     *
     * <p>With {@code Always}, any left click whose cursor happens to be over the island is a button press
     * - which in a fight means attacking pauses your music. Requiring a screen costs one keypress and
     * removes a whole class of accident.
     */
    private final Setting<Interact> interact = new Setting<>("Interact", Interact.Screens);
    /** The service name under the panel, shown whenever Spotify's data is on screen. */
    private final Setting<Boolean> attribution = new Setting<>("Attribution", true);
    /** Prints every visible window the fallback scan can see, once, then turns itself off. */
    private final Setting<Boolean> debug = new Setting<>("DebugWindows", false);

    /* ------------------------------------------------------------------ */
    /* Layout, in unscaled pixels                                          */
    /* ------------------------------------------------------------------ */

    private static final float PAD = 9f;
    private static final float GAP = 9f;

    private static final float COMPACT_COVER = 18f;
    private static final float COMPACT_WIDTH = 184f;
    private static final float COMPACT_HEIGHT = 36f;
    /** The extra row under a compact widget: elapsed time, the bar, the length. */
    private static final float COMPACT_BAR = 11f;

    private static final float BIG_COVER = 44f;
    private static final float WIDE_WIDTH = 196f;

    /* ------------------------------------------------------------------ */
    /* Fallback source state                                               */
    /* ------------------------------------------------------------------ */

    private final Timer poll = new Timer();
    private String fallbackTitle = "";
    private String fallbackArtist = "";
    private boolean fallbackAvailable;
    private long fallbackStartedAt;
    private String fallbackKey = "";

    private static Path source() {
        return ConfigManager.MISC_FOLDER.toPath().resolve("music.txt");
    }

    /* ------------------------------------------------------------------ */
    /* Per-frame interaction                                               */
    /* ------------------------------------------------------------------ */

    /**
     * A clickable region from the frame just drawn.
     *
     * <p>Collected while drawing rather than computed again in the input handler: a button's hitbox and
     * the pixels it was painted at are then the same number by construction, which is the only way a
     * layout this conditional stays clickable where it looks clickable.
     */
    private record Hot(float x, float y, float w, float h, int button, Runnable action) {
        boolean covers(double mx, double my) {
            return mx >= x && mx <= x + w && my >= y && my <= y + h;
        }
    }

    private final List<Hot> hots = new ArrayList<>();

    /** What a press is currently dragging, so the bar or the slider follows the cursor. */
    private enum Drag {
        None, Seek, Volume
    }

    private Drag dragging = Drag.None;
    private float seekLeft, seekWidth, volumeLeft, volumeWidth;

    /* ------------------------------------------------------------------ */
    /* The unified view both sources are drawn through                     */
    /* ------------------------------------------------------------------ */

    /**
     * One frame's worth of truth.
     *
     * @param durationKnown false for the window-title source, where only elapsed time is real. The total
     *                      stamp is then left blank rather than invented, which is what the old widget
     *                      did and is still the honest answer.
     */
    private record View(boolean available, boolean live, String title, String artist, String album,
                        String trackId, String uri, String link, Identifier cover,
                        long position, long duration, boolean durationKnown,
                        boolean playing, boolean saved, boolean savedKnown,
                        boolean shuffle, Repeat repeat, int volume,
                        String device, boolean controllable, String error) {
    }

    private boolean spotify() {
        return source.getValue() != Source.System && SpotifyAuth.authorised();
    }

    /* ------------------------------------------------------------------ */
    /* Render                                                              */
    /* ------------------------------------------------------------------ */

    @Override
    public void onRender2D(DrawContext context) {
        super.onRender2D(context);

        if (debug.getValue()) {
            debug.setValue(false);
            sendMessage("§7Windows the music scan can see:");
            for (String line : NowPlaying.describe()) sendMessage("§8" + line);
        }

        View view = spotify() ? fromSpotify() : fromWindow();

        hots.clear();

        float s = scale.getValue();
        boolean wide = layout.getValue() == Layout.Expanded;
        float width = wide ? WIDE_WIDTH : COMPACT_WIDTH;
        float height = wide ? wideHeight(view) : COMPACT_HEIGHT + (showProgress.getValue() && view.available() ? COMPACT_BAR : 0f);

        boolean reverse = getPosX() > (mc.getWindow().getScaledWidth() / 2f);
        float left = reverse ? getPosX() - width : getPosX();
        float top = getPosY();

        context.getMatrices().pushMatrix();
        context.getMatrices().translate(getPosX(), getPosY());
        context.getMatrices().scale(s, s);
        context.getMatrices().translate(-getPosX(), -getPosY());

        if (blur.getValue()) {
            // the client's liquid glass on its own - the dark plate on top of it was the black slab behind the widget
            Render2DEngine.drawRoundedBlur(context, left, top, width, height, 12f,
                    HudEditor.blurColor.getValue().getColorObject());
        } else {
            Render2DEngine.drawRoundSmooth(context, left, top, width, height, 12f, plateColor.getValue().getColorObject());
        }

        if (wide) drawWide(context, view, left, top, width);
        else drawCompact(context, view, left, top, width, height);

        context.getMatrices().popMatrix();

        setBounds(getPosX(), getPosY(), (int) (width * s) * (reverse ? -1 : 1), (int) (height * s));

        // Dragging is continuous, so it is resolved here, from the live cursor, rather than from the
        // discrete press events - a slider that only moved on click would be unusable.
        if (dragging != Drag.None) applyDrag(view);
    }

    private float wideHeight(View view) {
        float height = PAD + BIG_COVER + 6f;
        if (showProgress.getValue()) height += 16f;
        if (controls.getValue()) height += 40f;
        if (showDevice.getValue() || attribution.getValue()) height += 11f;
        if (upNext.getValue() && view.live()) height += 11f;
        if (lyrics.getValue() && view.live()) height += 12f;
        return height + PAD - 4f;
    }

    /* ------------------------------------------------------------------ */
    /* Compact                                                             */
    /* ------------------------------------------------------------------ */

    /** {@code COVER | TITLE - ARTIST | heart | play}, on one line. */
    private void drawCompact(DrawContext context, View view, float left, float top, float width, float height) {
        Color accent = HudEditor.getColor(0);
        float centre = top + COMPACT_HEIGHT / 2f;   // the cover-title-buttons row; the time row sits under it
        float x = left + PAD;

        if (showCover.getValue()) {
            drawCover(context, view, x, centre - COMPACT_COVER / 2f, COMPACT_COVER, 4f);
            x += COMPACT_COVER + 7f;
        }

        float buttons = controls.getValue() ? 66f : 0f;
        float textWidth = Math.max(20f, left + width - PAD - buttons - x - 4f);

        if (!view.available()) {
            text(context, FontRenderers.sf_bold_mini, idleLine(view), x, centre - 4f,
                    new Color(0x8E8E96).getRGB());
        } else {
            drawTitle(context, view, x, centre - 8f, textWidth);
            text(context, FontRenderers.sf_medium_mini,
                    trim(view.artist().toUpperCase(), textWidth), x, centre + 2f,
                    new Color(0x9A9AA5).getRGB());
        }

        // The time row under it, like Spotify's: time listened on the left of the bar, the song's length on the
        // right ("--:--" while the length is unknown).
        if (showProgress.getValue() && view.available()) {
            var font = FontRenderers.sf_medium_mini;
            String elapsed = SpotifyPlayer.stamp(view.position());
            String total = view.duration() > 0 ? SpotifyPlayer.stamp(view.duration()) : "--:--";
            float rowY = top + COMPACT_HEIGHT - 3f;
            float l = left + PAD, r = left + width - PAD;
            float elapsedW = font.getStringWidth(elapsed), totalW = font.getStringWidth(total);
            Color stamp = new Color(0xA6A6B0);

            text(context, font, elapsed, l, rowY, stamp.getRGB());
            text(context, font, total, r - totalW, rowY, stamp.getRGB());

            float barX = l + elapsedW + 6f, barW = Math.max(8f, (r - totalW - 6f) - barX), barY = rowY + 3f;
            float fraction = view.duration() > 0 ? Math.clamp(view.position() / (float) view.duration(), 0f, 1f) : 0f;
            Render2DEngine.drawRoundSmooth(context, barX, barY, barW, 2f, 1f, new Color(255, 255, 255, 45));
            Render2DEngine.drawRoundSmooth(context, barX, barY, barW * fraction, 2f, 1f, accent);
        }

        if (!controls.getValue()) return;

        // right to left: next, play/pause, previous, then the heart
        float size = 11f, gap = 5f;
        float iconY = centre - size / 2f;
        float nextX = left + width - PAD - size;
        float playX = nextX - gap - size;
        float prevX = playX - gap - size;
        float heartX = prevX - gap - 3f - size;

        if (view.live()) {
            SpotifyIcons.heart(context, heartX, iconY, size,
                    view.saved() ? new Color(0x1DB954) : accent, view.saved() && view.savedKnown());
            hot(heartX, iconY, size, size, GLFW.GLFW_MOUSE_BUTTON_LEFT, SpotifyLibrary::toggleSaved);
        }

        SpotifyIcons.previous(context, prevX, iconY, size, accent);
        hot(prevX - 2f, iconY - 2f, size + 4f, size + 4f, GLFW.GLFW_MOUSE_BUTTON_LEFT, () -> previous(view));

        if (view.playing()) SpotifyIcons.pause(context, playX, iconY, size, accent);
        else SpotifyIcons.play(context, playX, iconY, size, accent);
        hot(playX - 2f, iconY - 2f, size + 4f, size + 4f, GLFW.GLFW_MOUSE_BUTTON_LEFT, () -> toggle(view));

        SpotifyIcons.next(context, nextX, iconY, size, accent);
        hot(nextX - 2f, iconY - 2f, size + 4f, size + 4f, GLFW.GLFW_MOUSE_BUTTON_LEFT, () -> next(view));
    }

    /* ------------------------------------------------------------------ */
    /* Expanded                                                            */
    /* ------------------------------------------------------------------ */

    private void drawWide(DrawContext context, View view, float left, float top, float width) {
        Color accent = HudEditor.getColor(0);
        Color dim = new Color(0x9A9AA5);

        float textX = left + PAD;
        if (showCover.getValue()) {
            drawCover(context, view, left + PAD, top + PAD, BIG_COVER, 6f);
            textX += BIG_COVER + GAP;
        }

        float textWidth = left + width - PAD - textX;
        float y = top + PAD;

        if (!view.available()) {
            text(context, FontRenderers.sf_bold_mini, idleLine(view), textX, y + 2f, dim.getRGB());
            if (!view.error().isEmpty())
                text(context, FontRenderers.sf_medium_mini, trim(view.error(), textWidth),
                        textX, y + 13f, new Color(0xC06A6A).getRGB());
            // Still worth drawing the transport: with no active device, pressing play is exactly what
            // wakes one up, and an island with no buttons would give you nothing to press.
        } else {
            drawTitle(context, view, textX, y, textWidth);
            text(context, FontRenderers.sf_medium_mini, trim(view.artist(), textWidth),
                    textX, y + 11f, dim.getRGB());

            float line = y + 22f;
            if (showAlbum.getValue() && !view.album().isEmpty()) {
                text(context, FontRenderers.sf_medium_mini, trim(view.album(), textWidth),
                        textX, line, new Color(0x7A7A86).getRGB());
                line += 10f;
            }
            if (showTrackId.getValue() && !view.trackId().isEmpty()) {
                text(context, FontRenderers.sf_medium_mini, "ID " + view.trackId(),
                        textX, line, new Color(0x5E5E6A).getRGB());
            }
        }

        float cursor = top + PAD + BIG_COVER + 6f;

        if (showProgress.getValue()) {
            drawProgress(context, view, left, cursor, width, accent);
            cursor += 16f;
        }

        if (controls.getValue()) {
            drawTransport(context, view, left, cursor, width, accent);
            cursor += 40f;
        }

        if (upNext.getValue() && view.live()) {
            List<Entry> queue = SpotifyPlayer.queue();
            String next = queue.isEmpty()
                    ? "kolejka pusta"
                    : "NEXT  " + queue.get(0).title() + "  -  " + queue.get(0).artist();

            text(context, FontRenderers.sf_medium_mini, trim(next, width - PAD * 2),
                    left + PAD, cursor, new Color(0x6E6E7A).getRGB());
            // Clicking the line opens the full queue, which is the only place the rest of it fits.
            hot(left + PAD, cursor - 2f, width - PAD * 2, 11f, GLFW.GLFW_MOUSE_BUTTON_LEFT,
                    () -> SpotifyScreen.open(SpotifyScreen.Tab.Queue));
            cursor += 11f;
        }

        if (lyrics.getValue() && view.live()) {
            String line = thunder.hack.utility.spotify.Lyrics.get(view.title(), view.artist(), view.duration()) == null ? ""
                    : thunder.hack.utility.spotify.Lyrics.get(view.title(), view.artist(), view.duration()).lineAt(view.position());
            if (!line.isEmpty())
                text(context, FontRenderers.sf_medium_mini, trim(line, width - PAD * 2), left + PAD, cursor,
                        HudEditor.getColor(0).getRGB());
            cursor += 12f;
        }

        if (showDevice.getValue() || attribution.getValue()) drawFooter(context, view, left, cursor, width);
    }

    /**
     * Elapsed, the bar, and the total.
     *
     * <p>With Spotify the fill is the real position in the real duration and the bar can be dragged to
     * seek. Without it, the fill is time since the title last changed against a five minute span, and the
     * right-hand stamp stays blank - a window title carries no duration, and inventing one would make the
     * bar a decoration that looks like information.
     */
    private void drawProgress(DrawContext context, View view, float left, float y, float width, Color accent) {
        long position = view.position();
        String elapsed = SpotifyPlayer.stamp(position);
        String total = view.durationKnown() ? SpotifyPlayer.stamp(view.duration()) : "";

        float elapsedWidth = width(FontRenderers.sf_medium_mini, elapsed);
        float totalWidth = total.isEmpty() ? 0f : width(FontRenderers.sf_medium_mini, total) + 6f;

        float barLeft = left + PAD + elapsedWidth + 6f;
        float barRight = left + width - PAD - totalWidth;
        float barWidth = Math.max(4f, barRight - barLeft);

        text(context, FontRenderers.sf_medium_mini, elapsed, left + PAD, y + 1f, new Color(0x9A9AA5).getRGB());
        if (!total.isEmpty())
            text(context, FontRenderers.sf_medium_mini, total, barRight + 6f, y + 1f, new Color(0x9A9AA5).getRGB());

        Render2DEngine.drawRoundSmooth(context, barLeft, y, barWidth, 2.5f, 1.25f, new Color(0x40FFFFFF, true));

        float fraction = view.durationKnown()
                ? (view.duration() <= 0 ? 0f : Math.clamp(position / (float) view.duration(), 0f, 1f))
                : Math.min(1f, position / 300_000f);

        if (view.available() && fraction > 0)
            Render2DEngine.drawRoundSmooth(context, barLeft, y, barWidth * fraction, 2.5f, 1.25f, accent);

        if (!view.live() || !view.durationKnown()) return;

        // The handle only exists where seeking is real, so a bar you cannot drag never grows a grip.
        float handleX = barLeft + barWidth * fraction;
        Render2DEngine.drawRoundSmooth(context, handleX - 2f, y - 1.75f, 4f, 6f, 2f, Color.WHITE);

        seekLeft = barLeft;
        seekWidth = barWidth;
        // A generous vertical reach: the bar is two and a half pixels tall, and asking anybody to hit
        // that with a mouse would be a joke.
        hot(barLeft - 2f, y - 5f, barWidth + 4f, 12f, GLFW.GLFW_MOUSE_BUTTON_LEFT, () -> dragging = Drag.Seek);
    }

    private void drawTransport(DrawContext context, View view, float left, float y, float width, Color accent) {
        Color idle = new Color(0xB4B4C0);
        boolean live = view.live();

        /* Row one: the library and list actions, which only exist with an account behind them. */
        if (live) {
            float rowY = y;
            float size = 10f;
            float x = left + PAD;

            SpotifyIcons.heart(context, x, rowY, size, view.saved() ? new Color(0x1DB954) : idle,
                    view.saved() && view.savedKnown());
            text(context, FontRenderers.sf_medium_mini, view.saved() ? "Saved" : "Save",
                    x + size + 3f, rowY + 1f, (view.saved() ? new Color(0x1DB954) : idle).getRGB());
            float saveWidth = size + 3f + width(FontRenderers.sf_medium_mini, view.saved() ? "Saved" : "Save");
            hot(x, rowY - 2f, saveWidth, size + 4f, GLFW.GLFW_MOUSE_BUTTON_LEFT, SpotifyLibrary::toggleSaved);

            x += saveWidth + 11f;
            SpotifyIcons.plus(context, x, rowY, size, idle);
            text(context, FontRenderers.sf_medium_mini, "Playlist", x + size + 3f, rowY + 1f, idle.getRGB());
            float addWidth = size + 3f + width(FontRenderers.sf_medium_mini, "Playlist");
            hot(x, rowY - 2f, addWidth, size + 4f, GLFW.GLFW_MOUSE_BUTTON_LEFT,
                    () -> SpotifyScreen.open(SpotifyScreen.Tab.Playlists));

            x += addWidth + 11f;
            SpotifyIcons.list(context, x, rowY, size, idle);
            text(context, FontRenderers.sf_medium_mini, "Queue", x + size + 3f, rowY + 1f, idle.getRGB());
            float queueWidth = size + 3f + width(FontRenderers.sf_medium_mini, "Queue");
            hot(x, rowY - 2f, queueWidth, size + 4f, GLFW.GLFW_MOUSE_BUTTON_LEFT,
                    () -> SpotifyScreen.open(SpotifyScreen.Tab.Queue));
        }

        /* Row two: previous, play, next - centred, because that is where the hand expects them. */
        float transportY = y + (live ? 15f : 4f);
        float big = 13f;
        float span = big * 3 + 30f;
        float prevX = left + (width - span) / 2f;
        float playX = prevX + big + 15f;
        float nextX = playX + big + 15f;

        Color transport = view.controllable() ? accent : Render2DEngine.injectAlpha(accent, 90);

        SpotifyIcons.previous(context, prevX, transportY, big, transport);
        if (view.playing()) SpotifyIcons.pause(context, playX, transportY, big, transport);
        else SpotifyIcons.play(context, playX, transportY, big, transport);
        SpotifyIcons.next(context, nextX, transportY, big, transport);

        hot(prevX - 3f, transportY - 3f, big + 6f, big + 6f, GLFW.GLFW_MOUSE_BUTTON_LEFT, () -> previous(view));
        hot(playX - 3f, transportY - 3f, big + 6f, big + 6f, GLFW.GLFW_MOUSE_BUTTON_LEFT, () -> toggle(view));
        hot(nextX - 3f, transportY - 3f, big + 6f, big + 6f, GLFW.GLFW_MOUSE_BUTTON_LEFT, () -> next(view));

        if (!live) return;

        /* Row three: shuffle, repeat, volume. */
        float modeY = transportY + big + 7f;
        float small = 10f;
        float x = left + PAD;

        SpotifyIcons.shuffle(context, x, modeY, small, view.shuffle() ? accent : idle);
        hot(x - 2f, modeY - 2f, small + 4f, small + 4f, GLFW.GLFW_MOUSE_BUTTON_LEFT,
                () -> SpotifyPlayer.shuffle(!view.shuffle()));

        x += small + 9f;
        SpotifyIcons.repeat(context, x, modeY, small,
                view.repeat() == Repeat.off ? idle : accent, view.repeat() == Repeat.track);
        hot(x - 2f, modeY - 2f, small + 4f, small + 4f, GLFW.GLFW_MOUSE_BUTTON_LEFT,
                () -> SpotifyPlayer.repeat(view.repeat().next()));

        x += small + 11f;
        SpotifyIcons.volume(context, x, modeY, small, idle, view.volume());
        x += small + 5f;

        float sliderWidth = Math.max(18f, left + width - PAD - x);
        float sliderY = modeY + small / 2f - 1.25f;
        // A device that reports no volume - a speaker with its own knob - gets a disabled track rather
        // than a slider at zero that would look like silence.
        boolean knowsVolume = view.volume() >= 0;
        float level = knowsVolume ? view.volume() / 100f : 0f;

        Render2DEngine.drawRoundSmooth(context, x, sliderY, sliderWidth, 2.5f, 1.25f, new Color(0x40FFFFFF, true));
        if (knowsVolume) {
            Render2DEngine.drawRoundSmooth(context, x, sliderY, sliderWidth * level, 2.5f, 1.25f, idle);
            Render2DEngine.drawRoundSmooth(context, x + sliderWidth * level - 2f, sliderY - 1.75f, 4f, 6f, 2f, Color.WHITE);

            volumeLeft = x;
            volumeWidth = sliderWidth;
            hot(x - 2f, modeY - 2f, sliderWidth + 4f, small + 4f, GLFW.GLFW_MOUSE_BUTTON_LEFT,
                    () -> dragging = Drag.Volume);
        }
    }

    /** Device, and the service the data came from. */
    private void drawFooter(DrawContext context, View view, float left, float y, float width) {
        Color dim = new Color(0x5E5E6A);

        if (showDevice.getValue() && view.live()) {
            float size = 8f;
            SpotifyIcons.device(context, left + PAD, y, size, dim);

            String device = view.device().isEmpty() ? "brak urzadzenia" : view.device();
            text(context, FontRenderers.sf_medium_mini, trim(device, width * 0.5f),
                    left + PAD + size + 3f, y + 0.5f, dim.getRGB());

            float reach = size + 3f + width(FontRenderers.sf_medium_mini, trim(device, width * 0.5f));
            hot(left + PAD, y - 2f, reach, size + 4f, GLFW.GLFW_MOUSE_BUTTON_LEFT,
                    () -> SpotifyScreen.open(SpotifyScreen.Tab.Devices));
        }

        if (!attribution.getValue()) return;

        // Named, not logo'd: a redrawn mark is a modified mark, and this has no licence to modify one.
        // The word, in the service's own green, next to its data, is the attribution that is actually
        // asked for - and it is also the honest label for where everything above came from.
        String mark = view.live() ? "SPOTIFY" : "SYSTEM";
        float markWidth = width(FontRenderers.sf_bold_mini, mark);
        text(context, FontRenderers.sf_bold_mini, mark, left + width - PAD - markWidth, y + 0.5f,
                (view.live() ? new Color(0x1DB954) : dim).getRGB());

        if (view.live() && !view.link().isEmpty())
            hot(left + width - PAD - markWidth - 2f, y - 2f, markWidth + 4f, 11f,
                    GLFW.GLFW_MOUSE_BUTTON_LEFT, () -> openInSpotify(view.link()));
    }

    /* ------------------------------------------------------------------ */
    /* Pieces                                                              */
    /* ------------------------------------------------------------------ */

    /**
     * The artwork, framed rather than clipped.
     *
     * <p>The engine has no rounded scissor, so a square image with a thin dark frame around it is what
     * reads as a framed thumbnail; drawing a rounded outline over a square image just looks broken. The
     * image itself is untouched - the frame is beside it, never on it.
     */
    private void drawCover(DrawContext context, View view, float x, float y, float size, float radius) {
        Identifier cover = view.cover();

        if (cover != null) {
            // the artwork alone - the near-black frame round it read as a black box next to the glass
            Render2DEngine.renderTexture(context, cover, x, y, size, size,
                    0, 0, size, size, size, size, Color.WHITE);

            if (view.live() && !view.link().isEmpty())
                hot(x, y, size, size, GLFW.GLFW_MOUSE_BUTTON_LEFT, () -> openInSpotify(view.link()));
            return;
        }

        Render2DEngine.drawRoundSmooth(context, x, y, size, size, radius,
                view.available() ? coverColor(view) : new Color(58, 58, 66));

        float glyph = size * 0.5f;
        Render2DEngine.renderTexture(context, TextureStorage.noteIcon,
                x + (size - glyph) / 2f, y + (size - glyph) / 2f, glyph, glyph,
                0, 0, glyph, glyph, glyph, glyph, new Color(0xFFFFFFFF, true));
    }

    /**
     * A colour for this track, derived from its name.
     *
     * <p>Only reached when there is no real cover. Deterministic, so the same song always gets the same
     * placeholder, and a cover that CHANGES when the song does is most of what makes it read as artwork.
     */
    private Color coverColor(View view) {
        int hash = (view.artist() + " - " + view.title()).hashCode();
        return Color.getHSBColor(((hash >>> 8) & 0xFFFF) / 65535f, 0.55f, 0.46f);
    }

    /** What the island says when there is no track: the reason, when there is one worth giving. */
    private String idleLine(View view) {
        if (source.getValue() == Source.Spotify && !SpotifyAuth.authorised())
            return switch (SpotifyAuth.stage()) {
                case NoClientId -> ".spotify clientid <id>";
                case Waiting -> "dokoncz login w przegladarce";
                case Failed -> "login sie nie udal";
                default -> ".spotify login";
            };

        return spotify() ? "nic nie gra na spotify" : "nic nie gra";
    }

    /**
     * The track name: scrolled when it is too long, with the word it is on picked out.
     *
     * <p>Cutting a title off hides the half you usually want - the track - behind the half you already
     * know. Scrolling shows all of it, and the highlight walks word by word so the line is never a static
     * block of text you stop reading.
     *
     * <p>The highlight is not lyrics and does not pretend to be: nothing in any API here gives word
     * timing. It advances on a fixed beat, which marks the rhythm of the widget rather than of the song.
     */
    private void drawTitle(DrawContext context, View view, float x, float y, float maxWidth) {
        var font = FontRenderers.sf_bold_mini;
        String title = view.title();
        String[] words = title.split(" ");

        Color accent = highlight.getValue() == Highlight.White ? Color.WHITE : HudEditor.getColor(0);
        int dim = new Color(0xC8C8D2).getRGB();

        // With Spotify, the clock driving the scroll and the highlight is the real position in the track,
        // so both restart exactly when the track does rather than whenever the widget noticed.
        long elapsed = Math.max(0L, view.position());
        int lit = highlight.getValue() == Highlight.Off || words.length == 0
                ? -1
                : (int) ((elapsed / Math.max(120L, wordMs.getValue().longValue())) % words.length);

        float full = width(font, title);
        float offset = 0f;

        if (full > maxWidth) {
            float span = full - maxWidth + 12f;
            float phase = (elapsed % 6000L) / 6000f;
            // Out and back with a pause at each end, so the start of the title is always readable.
            float eased = phase < 0.1f ? 0f
                    : phase < 0.5f ? (phase - 0.1f) / 0.4f
                    : phase < 0.6f ? 1f
                    : 1f - (phase - 0.6f) / 0.4f;
            offset = -span * eased;
        }

        Render2DEngine.addWindow(context, x, y - 2f, x + maxWidth, y + 10f, 1f);

        float wx = x + offset;
        for (int i = 0; i < words.length; i++) {
            String word = words[i] + (i < words.length - 1 ? " " : "");
            font.drawString(context, word, wx, y, i == lit ? accent.getRGB() : dim, scale.getValue());
            wx += width(font, word);
        }

        Render2DEngine.popWindow();
    }

    /* ------------------------------------------------------------------ */
    /* Sources                                                             */
    /* ------------------------------------------------------------------ */

    private View fromSpotify() {
        // The one line of coupling between drawing and the network: it says the widget is on screen and
        // how fresh it wants to be. The poller does the rest, on its own thread.
        SpotifyPlayer.touch(refreshMs.getValue());

        if (upNext.getValue()) SpotifyPlayer.refreshQueue();
        if (showDevice.getValue()) SpotifyPlayer.refreshDevices();

        SpotifyPlayer.Snapshot snapshot = SpotifyPlayer.state();

        return new View(
                snapshot.present(), true,
                snapshot.title(), snapshot.artist(), snapshot.album(),
                snapshot.trackId(), snapshot.trackUri(), snapshot.link(),
                showCover.getValue() ? SpotifyArt.of(snapshot.imageUrl()) : null,
                snapshot.progressMs(), snapshot.durationMs(), snapshot.durationMs() > 0,
                snapshot.playing(), snapshot.saved(), snapshot.savedKnown(),
                snapshot.shuffle(), snapshot.repeat(), snapshot.volume(),
                snapshot.deviceName(), snapshot.controllable(),
                SpotifyPlayer.error());
    }

    private View fromWindow() {
        if (poll.passedMs(refreshMs.getValue())) {
            poll.reset();
            read();
        }

        // The exact cover Spotify shows for this track when its web player can be read; searching iTunes and
        // Deezer by artist and title is only the fallback - it misses remixes and smaller artists.
        Identifier cover = null;
        if (fallbackAvailable && showCover.getValue() && artwork.getValue()) {
            String exact = thunder.hack.utility.spotify.SpotifyCard.coverUrl(fallbackTitle);
            cover = exact != null ? SpotifyArt.of(exact) : null;
            if (cover == null) cover = thunder.hack.utility.CoverArt.of(fallbackArtist, fallbackTitle);
        }

        // The window title has no position and no length: the shared TitleClock supplies the position (and
        // pauses with Spotify), lrclib the length - so the progress bar works without a login too.
        boolean fromSpotifyWindow = thunder.hack.utility.spotify.TitleClock.current() != null;
        long position = fallbackAvailable
                ? (fromSpotifyWindow ? thunder.hack.utility.spotify.TitleClock.position() : System.currentTimeMillis() - fallbackStartedAt)
                : 0L;
        // length: the Windows media session's when it has one, else lrclib's
        long sessionLength = fromSpotifyWindow ? thunder.hack.utility.spotify.TitleClock.duration() : 0L;
        var found = fallbackAvailable ? thunder.hack.utility.spotify.Lyrics.get(fallbackTitle, fallbackArtist, sessionLength) : null;
        long length = sessionLength > 0 ? sessionLength : found != null ? found.durationMs() : 0L;

        return new View(
                fallbackAvailable, false,
                fallbackTitle, fallbackArtist, "", "", "", "",
                cover,
                length > 0 ? Math.min(position, length) : position,
                length, length > 0,
                // Spotify's own player bar says whether it is paused; for anything else a title that is still
                // there is very nearly always still playing, so the icon follows "there is a track".
                fallbackAvailable && (!fromSpotifyWindow || thunder.hack.utility.spotify.TitleClock.playing()), false, false,
                false, Repeat.off, -1,
                "", true, "");
    }

    private void read() {
        NowPlaying.Track track = NowPlaying.current();
        if (track != null) {
            apply(track.title(), track.artist());
            return;
        }

        try {
            Path path = source();
            if (!Files.exists(path)) {
                fallbackAvailable = false;
                return;
            }

            List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
            apply(lines.isEmpty() ? "" : lines.get(0).trim(), lines.size() > 1 ? lines.get(1).trim() : "");
        } catch (IOException | RuntimeException e) {
            // A half-written file while something else updates it is normal; keep the last good read.
            fallbackAvailable = !fallbackTitle.isEmpty();
        }
    }

    private void apply(String newTitle, String newArtist) {
        String key = newArtist + " " + newTitle;
        if (!key.equals(fallbackKey)) {
            fallbackKey = key;
            fallbackStartedAt = System.currentTimeMillis();
        }

        fallbackTitle = newTitle;
        fallbackArtist = newArtist;
        fallbackAvailable = !fallbackTitle.isEmpty();
    }

    /* ------------------------------------------------------------------ */
    /* Commands                                                            */
    /* ------------------------------------------------------------------ */

    private void toggle(View view) {
        if (view.live()) SpotifyPlayer.toggle();
        else media(VK_MEDIA_PLAY_PAUSE);
    }

    private void next(View view) {
        if (view.live()) SpotifyPlayer.next();
        else media(VK_MEDIA_NEXT_TRACK);
    }

    private void previous(View view) {
        if (view.live()) SpotifyPlayer.previous();
        else media(VK_MEDIA_PREV_TRACK);
    }

    private void openInSpotify(String link) {
        try {
            net.minecraft.util.Util.getOperatingSystem().open(java.net.URI.create(link));
        } catch (Throwable ignored) {
            // No browser, no handler - nothing to do and nothing worth a chat line over.
        }
    }

    /* ------------------------------------------------------------------ */
    /* Input                                                               */
    /* ------------------------------------------------------------------ */

    @Override
    public void onMouse(@NotNull EventMouse event) {
        super.onMouse(event);

        if (event.getAction() == GLFW.GLFW_RELEASE) {
            dragging = Drag.None;
            return;
        }
        if (event.getAction() != GLFW.GLFW_PRESS) return;
        if (!controls.getValue()) return;

        // With Interact.Screens, a click only counts while a screen is open, which is what stops an
        // attack from pausing the music whenever the island happens to be under the crosshair.
        if (interact.getValue() == Interact.Screens && mc.currentScreen == null) return;

        float s = scale.getValue();
        double mx = (mc.mouse.getScaledX(mc.getWindow()) - getPosX()) / s + getPosX();
        double my = (mc.mouse.getScaledY(mc.getWindow()) - getPosY()) / s + getPosY();

        // Right-click anywhere on the island is the context menu. It has to open the panel rather than
        // float a menu over the HUD: a menu drawn on the HUD could not take the clicks that follow,
        // because nothing is capturing the cursor until a screen is open.
        if (event.getButton() == GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
            if (!inside(mx, my)) return;
            if (spotify()) SpotifyScreen.openWithMenu();
            return;
        }

        if (event.getButton() != GLFW.GLFW_MOUSE_BUTTON_LEFT) return;

        // Last drawn wins: the lists above add their regions in draw order, and a later region is drawn
        // on top of an earlier one, so it is the one the cursor is actually over.
        for (int i = hots.size() - 1; i >= 0; i--) {
            Hot hot = hots.get(i);
            if (hot.button() == event.getButton() && hot.covers(mx, my)) {
                hot.action().run();
                return;
            }
        }
    }

    private boolean inside(double mx, double my) {
        float s = scale.getValue();
        float width = Math.abs(getWidth()) / s;
        float height = getHeight() / s;
        float left = getWidth() < 0 ? getPosX() - width : getPosX();
        return mx >= left && mx <= left + width && my >= getPosY() && my <= getPosY() + height;
    }

    /** Seek and volume while the button is held, read from the live cursor. */
    private void applyDrag(View view) {
        if (!view.live()) {
            dragging = Drag.None;
            return;
        }

        float s = scale.getValue();
        double mx = (mc.mouse.getScaledX(mc.getWindow()) - getPosX()) / s + getPosX();

        if (dragging == Drag.Seek && seekWidth > 0f && view.duration() > 0) {
            float fraction = (float) Math.clamp((mx - seekLeft) / seekWidth, 0.0, 1.0);
            SpotifyPlayer.seek((long) (fraction * view.duration()));
        } else if (dragging == Drag.Volume && volumeWidth > 0f) {
            float fraction = (float) Math.clamp((mx - volumeLeft) / volumeWidth, 0.0, 1.0);
            SpotifyPlayer.volume(Math.round(fraction * 100f));
        }
    }

    private void hot(float x, float y, float w, float h, int button, Runnable action) {
        hots.add(new Hot(x, y, w, h, button, action));
    }

    /* ------------------------------------------------------------------ */
    /* Media keys - the fallback transport                                 */
    /* ------------------------------------------------------------------ */

    /* Windows virtual-key codes. AWT has no constants for these - Robot only speaks AWT key codes and
     * none of them map to the transport keys - so the press goes through user32. */
    private static final byte VK_MEDIA_NEXT_TRACK = (byte) 0xB0;
    private static final byte VK_MEDIA_PREV_TRACK = (byte) 0xB1;
    private static final byte VK_MEDIA_PLAY_PAUSE = (byte) 0xB3;
    private static final int KEYEVENTF_KEYUP = 0x0002;

    private interface User32 extends com.sun.jna.Library {
        User32 INSTANCE = com.sun.jna.Native.load("user32", User32.class);

        void keybd_event(byte key, byte scan, int flags, int extra);
    }

    private void media(byte key) {
        try {
            User32.INSTANCE.keybd_event(key, (byte) 0, 0, 0);
            User32.INSTANCE.keybd_event(key, (byte) 0, KEYEVENTF_KEYUP, 0);
        } catch (Throwable ignored) {
            // Not Windows, or user32 is not loadable - nothing to do and nothing worth logging.
        }
    }

    private String trim(String text, float maxWidth) {
        if (text == null || text.isEmpty()) return "";
        if (width(FontRenderers.sf_medium_mini, text) <= maxWidth) return text;

        StringBuilder sb = new StringBuilder();
        for (char c : text.toCharArray()) {
            if (width(FontRenderers.sf_medium_mini, sb + String.valueOf(c) + "...") > maxWidth) break;
            sb.append(c);
        }
        return sb + "...";
    }

    /* ------------------------------------------------------------------ */
    /* Text at the widget's own size                                       */
    /* ------------------------------------------------------------------ */

    /**
     * Every line in this widget is drawn inside a matrix scaled by {@link #scale}, and a font is a
     * bitmap: stretching it here is what made the text go pixelated as the island grew. Handing the
     * scale to the renderer instead bakes the glyphs at the size they will actually appear at. Widths
     * stay in unscaled units, so all the layout above is unchanged.
     */
    private void text(DrawContext context, FontRenderer font, String s, float x, float y, int color) {
        font.drawString(context, s, x, y, color, scale.getValue());
    }

    private float width(FontRenderer font, String s) {
        return font.getStringWidth(s, scale.getValue());
    }
}
