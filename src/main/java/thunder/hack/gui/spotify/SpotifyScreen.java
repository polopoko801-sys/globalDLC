package thunder.hack.gui.spotify;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.Util;
import org.jetbrains.annotations.NotNull;
import org.lwjgl.glfw.GLFW;
import thunder.hack.features.modules.client.HudEditor;
import thunder.hack.gui.font.FontRenderers;
import thunder.hack.utility.render.Render2DEngine;
import thunder.hack.utility.render.SpotifyIcons;
import thunder.hack.utility.spotify.SpotifyApi;
import thunder.hack.utility.spotify.SpotifyArt;
import thunder.hack.utility.spotify.SpotifyAuth;
import thunder.hack.utility.spotify.SpotifyLibrary;
import thunder.hack.utility.spotify.SpotifyModels.Device;
import thunder.hack.utility.spotify.SpotifyModels.Entry;
import thunder.hack.utility.spotify.SpotifyModels.Hit;
import thunder.hack.utility.spotify.SpotifyModels.Kind;
import thunder.hack.utility.spotify.SpotifyModels.Playlist;
import thunder.hack.utility.spotify.SpotifyPlayer;

import java.awt.Color;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;

/**
 * The full Spotify panel: everything the island has no room for.
 *
 * <p>This exists because of one hard limit. A HUD element cannot own the cursor - nothing is capturing
 * mouse input while you are playing, so a menu floated over the HUD could be drawn but never clicked, and
 * a search box there could never take a keystroke. So everything that needs text or a menu lives here, in
 * a real screen, and the island keeps what can be worked with a single click.
 *
 * <p><b>Nothing in here blocks.</b> Every tab draws from whatever {@link SpotifyLibrary} and
 * {@link SpotifyPlayer} already hold, and asks them to load on the way past. A tab that has no data yet
 * says which of the three reasons applies - loading, empty, or an error with the reason - rather than
 * showing a blank box.
 *
 * <p><b>Spotify's content.</b> Covers are drawn as served. Every row can be opened in Spotify or have its
 * link copied, and no row is ever drawn with the artwork altered.
 */
public class SpotifyScreen extends Screen {
    public enum Tab {
        NowPlaying("Now Playing"),
        Search("Search"),
        Playlists("My Playlists"),
        Queue("Queue"),
        Devices("Devices");

        final String label;

        Tab(String label) {
            this.label = label;
        }
    }

    private static final float WIDTH = 470f;
    private static final float HEIGHT = 310f;
    private static final float SIDEBAR = 112f;
    private static final float ROW = 30f;

    private static final Color PLATE = new Color(16, 16, 21, 242);
    private static final Color CARD = new Color(26, 26, 33, 255);
    private static final Color CARD_HOVER = new Color(36, 36, 46, 255);
    private static final Color TEXT = new Color(0xEDEDF2);
    private static final Color DIM = new Color(0x8A8A96);
    private static final Color FAINT = new Color(0x5E5E6A);
    private static final Color GREEN = new Color(0x1DB954);

    private Tab tab;

    public SpotifyScreen(Tab tab) {
        super(Text.literal("Spotify"));
        this.tab = tab;
    }

    /** Opens the panel on a tab. Safe from a HUD click - both are on the main thread. */
    public static void open(Tab tab) {
        var mc = net.minecraft.client.MinecraftClient.getInstance();
        if (mc == null) return;

        SpotifyScreen screen = new SpotifyScreen(tab);
        mc.setScreen(screen);
    }

    /** Opens on Now Playing with the current track's context menu already up. */
    public static void openWithMenu() {
        var mc = net.minecraft.client.MinecraftClient.getInstance();
        if (mc == null) return;

        SpotifyScreen screen = new SpotifyScreen(Tab.NowPlaying);
        mc.setScreen(screen);
        screen.openTrackMenu(SpotifyPlayer.state().trackUri(), SpotifyPlayer.state().link(), 0f, 0f, true);
    }

    /* ------------------------------------------------------------------ */
    /* Interaction state                                                   */
    /* ------------------------------------------------------------------ */

    /** A region from the frame just drawn, with what to do about each button. */
    private record Hot(float x, float y, float w, float h, Runnable left, Runnable right) {
        boolean covers(double mx, double my) {
            return mx >= x && mx <= x + w && my >= y && my <= y + h;
        }
    }

    private final List<Hot> hots = new ArrayList<>();
    /** Index in {@link #hots} below which regions are covered by an overlay, or -1 when none is open. */
    private int overlayFloor = -1;

    /** A one-line text input. Deliberately minimal: a caret, a selection-free buffer, a placeholder. */
    private static final class Field {
        String text = "";
        boolean focused;
        final String placeholder;
        final int limit;

        Field(String placeholder, int limit) {
            this.placeholder = placeholder;
            this.limit = limit;
        }

        boolean type(char c) {
            if (text.length() >= limit) return false;
            if (c < 32 || c == 127) return false;
            text += c;
            return true;
        }

        boolean backspace() {
            if (text.isEmpty()) return false;
            text = text.substring(0, text.length() - 1);
            return true;
        }
    }

    private final Field searchField = new Field("Search Spotify...", 120);
    private final Field playlistFilter = new Field("Search playlist...", 60);
    private final Field newName = new Field("Name", 90);
    private final Field newDescription = new Field("Description", 180);
    private boolean newPublic;

    /** Which kinds the search asks for. All six, so the tabs always have something to switch between. */
    private static final List<Kind> KINDS = List.of(Kind.Track, Kind.Album, Kind.Artist,
            Kind.Playlist, Kind.Show, Kind.Episode);
    private Kind searchKind = Kind.Track;

    private float scroll;
    /** The playlist being looked inside, or null for the list of them. */
    private Playlist openPlaylist;
    /** True while the create-playlist form is up. */
    private boolean creating;

    /** The picker that "Add to playlist" opens, and the uri it will add. */
    private String pickerUri;

    /** A floating menu. */
    private record MenuItem(String label, Runnable action, boolean enabled) {
    }

    private List<MenuItem> menu;
    private float menuX, menuY;

    /* ------------------------------------------------------------------ */
    /* Geometry                                                            */
    /* ------------------------------------------------------------------ */

    private float panelX() {
        return (width - WIDTH) / 2f;
    }

    private float panelY() {
        return (height - HEIGHT) / 2f;
    }

    private float contentX() {
        return panelX() + SIDEBAR;
    }

    private float contentY() {
        return panelY() + 46f;
    }

    private float contentW() {
        return WIDTH - SIDEBAR - 16f;
    }

    private float contentH() {
        return HEIGHT - 46f - 14f;
    }

    /* ------------------------------------------------------------------ */
    /* Render                                                              */
    /* ------------------------------------------------------------------ */

    @Override
    public void render(@NotNull DrawContext context, int mouseX, int mouseY, float delta) {
        renderBackground(context, mouseX, mouseY, delta);
        hots.clear();

        // The panel is the only thing on screen that needs the player fresh, so it asks for a fast poll
        // while it is open and lets it fall back the moment it closes.
        SpotifyPlayer.touch(1000L);

        float x = panelX(), y = panelY();

        Render2DEngine.drawRound(context, x, y, WIDTH, HEIGHT, 12f, PLATE);
        Render2DEngine.drawRound(context, x, y, SIDEBAR, HEIGHT, 12f, new Color(11, 11, 15, 240));

        drawSidebar(context, mouseX, mouseY, x, y);

        if (!SpotifyAuth.authorised()) {
            drawLogin(context, mouseX, mouseY);
        } else {
            switch (tab) {
                case NowPlaying -> drawNowPlaying(context, mouseX, mouseY);
                case Search -> drawSearch(context, mouseX, mouseY);
                case Playlists -> drawPlaylists(context, mouseX, mouseY);
                case Queue -> drawQueue(context, mouseX, mouseY);
                case Devices -> drawDevices(context, mouseX, mouseY);
            }
        }

        // Overlays, outermost last. The floor is where the overlay's own regions start: with one open, a
        // click below the floor is a click on the tab behind it, which must not go through - a modal that
        // lets you press the thing it is covering is not a modal.
        overlayFloor = (pickerUri != null || creating) ? hots.size() : -1;

        if (pickerUri != null) drawPicker(context, mouseX, mouseY);
        if (creating) drawCreate(context, mouseX, mouseY);

        // The menu is above even an overlay, and raises the floor again so only it is clickable.
        if (menu != null) {
            overlayFloor = hots.size();
            drawMenu(context, mouseX, mouseY);
        }

        drawFooter(context);
    }

    private void drawSidebar(DrawContext context, int mouseX, int mouseY, float x, float y) {
        Color accent = HudEditor.getColor(0);

        FontRenderers.sf_bold.drawString(context, "GOMBAL", x + 14f, y + 16f, TEXT.getRGB());
        FontRenderers.sf_bold_mini.drawString(context, "SPOTIFY", x + 14f, y + 28f, GREEN.getRGB());

        float ty = y + 54f;
        for (Tab option : Tab.values()) {
            boolean active = option == tab;
            boolean hovered = Render2DEngine.isHovered(mouseX, mouseY, x + 8f, ty, SIDEBAR - 16f, 22f);

            if (active || hovered)
                Render2DEngine.drawRound(context, x + 8f, ty, SIDEBAR - 16f, 22f, 6f,
                        active ? Render2DEngine.injectAlpha(accent, 48) : new Color(255, 255, 255, 14));
            if (active)
                Render2DEngine.drawRound(context, x + 8f, ty + 5f, 2.5f, 12f, 1.25f, accent);

            FontRenderers.sf_medium_mini.drawString(context, option.label, x + 18f, ty + 7f,
                    (active ? TEXT : DIM).getRGB());

            Tab target = option;
            hot(x + 8f, ty, SIDEBAR - 16f, 22f, () -> {
                tab = target;
                scroll = 0f;
                openPlaylist = null;
            }, null);

            ty += 25f;
        }

        // Close, bottom of the sidebar, because Escape is not discoverable and this panel is deep enough
        // that people end up inside a playlist wondering how to get out.
        float closeY = y + HEIGHT - 30f;
        boolean hovered = Render2DEngine.isHovered(mouseX, mouseY, x + 8f, closeY, SIDEBAR - 16f, 20f);
        Render2DEngine.drawRound(context, x + 8f, closeY, SIDEBAR - 16f, 20f, 6f,
                hovered ? new Color(255, 255, 255, 22) : new Color(255, 255, 255, 10));
        SpotifyIcons.close(context, x + 16f, closeY + 6f, 8f, DIM);
        FontRenderers.sf_medium_mini.drawString(context, "Close", x + 28f, closeY + 6f, DIM.getRGB());
        hot(x + 8f, closeY, SIDEBAR - 16f, 20f, this::close, null);
    }

    /** The status strip: what went wrong most recently, and nothing when all is well. */
    private void drawFooter(DrawContext context) {
        String message = SpotifyPlayer.error();
        if (message.isEmpty()) message = SpotifyLibrary.lastError();
        if (message.isEmpty()) message = SpotifyApi.lastError();
        if (message.isEmpty() && SpotifyApi.rateLimited()) message = "limit zapytan - chwila przerwy";
        if (message.isEmpty()) return;

        FontRenderers.sf_medium_mini.drawString(context, message,
                contentX(), panelY() + HEIGHT - 12f, new Color(0xC06A6A).getRGB());
    }

    /* ------------------------------------------------------------------ */
    /* Login                                                               */
    /* ------------------------------------------------------------------ */

    private void drawLogin(DrawContext context, int mouseX, int mouseY) {
        float x = contentX(), y = contentY();
        Color accent = HudEditor.getColor(0);

        FontRenderers.sf_bold.drawString(context, "Polacz ze Spotify", x, y, TEXT.getRGB());

        String[] lines = SpotifyAuth.clientId().isEmpty()
                ? new String[]{
                "1. Wejdz na developer.spotify.com -> Dashboard -> Create app",
                "2. Jako Redirect URI wklej dokladnie:",
                "   " + SpotifyAuth.REDIRECT,
                "3. Skopiuj Client ID i wpisz w czacie:",
                "   .spotify clientid <id>",
                "",
                "Haslo do Spotify nigdy nie przechodzi przez klienta - logujesz",
                "sie u siebie w przegladarce, tutaj zostaje tylko token."}
                : new String[]{
                "Client ID jest ustawiony. Kliknij ponizej - otworze przegladarke,",
                "zalogujesz sie na stronie Spotify, a tu wroci sam token.",
                "",
                "Token trzymam w GlobalDLC/misc/spotify-token.dat, poza configiem,",
                "wiec wyslanie komus configu nie wysyla logowania.",
                "Wylogowanie: .spotify logout"};

        float ly = y + 20f;
        for (String line : lines) {
            FontRenderers.sf_medium_mini.drawString(context, line, x, ly, DIM.getRGB());
            ly += 11f;
        }

        if (!SpotifyAuth.clientId().isEmpty()) {
            boolean hovered = Render2DEngine.isHovered(mouseX, mouseY, x, ly + 10f, 128f, 24f);
            Render2DEngine.drawRound(context, x, ly + 10f, 128f, 24f, 7f,
                    Render2DEngine.injectAlpha(accent, hovered ? 230 : 180));
            FontRenderers.sf_medium.drawCenteredString(context, "Zaloguj przez Spotify", x + 64f, ly + 18f, -1);
            hot(x, ly + 10f, 128f, 24f, () -> SpotifyAuth.login(null), null);
            ly += 40f;
        }

        if (!SpotifyAuth.message().isEmpty())
            FontRenderers.sf_medium_mini.drawString(context, SpotifyAuth.message(), x, ly + 12f, GREEN.getRGB());
    }

    /* ------------------------------------------------------------------ */
    /* Now playing                                                         */
    /* ------------------------------------------------------------------ */

    private void drawNowPlaying(DrawContext context, int mouseX, int mouseY) {
        SpotifyPlayer.Snapshot snapshot = SpotifyPlayer.state();
        SpotifyPlayer.refreshQueue();
        SpotifyPlayer.refreshDevices();

        float x = contentX(), y = contentY();
        Color accent = HudEditor.getColor(0);

        if (!snapshot.present()) {
            FontRenderers.sf_bold.drawString(context, "Nic nie gra", x, y, TEXT.getRGB());
            FontRenderers.sf_medium_mini.drawString(context,
                    "Odtworz cokolwiek w Spotify albo wybierz urzadzenie w Devices.",
                    x, y + 18f, DIM.getRGB());
            return;
        }

        /* Cover. Drawn as served, inside a frame rather than clipped by one. */
        float cover = 112f;
        Identifier art = SpotifyArt.of(snapshot.imageUrl());
        Render2DEngine.drawRound(context, x, y, cover, cover, 8f, new Color(10, 10, 14));
        if (art != null)
            Render2DEngine.renderTexture(context, art, x + 2f, y + 2f, cover - 4f, cover - 4f,
                    0, 0, cover, cover, cover, cover, Color.WHITE);
        if (!snapshot.link().isEmpty())
            hot(x, y, cover, cover, () -> openLink(snapshot.link()), null);

        float textX = x + cover + 16f;
        float textW = contentW() - cover - 16f;

        FontRenderers.sf_bold.drawString(context, clip(FontRenderers.sf_bold, snapshot.title(), textW),
                textX, y + 2f, TEXT.getRGB());
        FontRenderers.sf_medium.drawString(context, clip(FontRenderers.sf_medium, snapshot.artist(), textW),
                textX, y + 18f, DIM.getRGB());
        FontRenderers.sf_medium_mini.drawString(context, clip(FontRenderers.sf_medium_mini, snapshot.album(), textW),
                textX, y + 32f, FAINT.getRGB());
        FontRenderers.sf_medium_mini.drawString(context, "Track ID  " + snapshot.trackId(),
                textX, y + 44f, FAINT.getRGB());

        /* Save, add, queue, context menu. */
        float buttonY = y + 60f;
        float bx = textX;

        bx += pill(context, mouseX, mouseY, bx, buttonY, snapshot.saved() ? "Saved" : "Save",
                snapshot.saved() ? GREEN : null, SpotifyLibrary::toggleSaved) + 6f;
        bx += pill(context, mouseX, mouseY, bx, buttonY, "+ Playlist", null,
                () -> openPicker(snapshot.trackUri())) + 6f;
        bx += pill(context, mouseX, mouseY, bx, buttonY, "Queue", null, () -> {
            tab = Tab.Queue;
            scroll = 0f;
        }) + 6f;
        float menuAnchor = bx;
        pill(context, mouseX, mouseY, bx, buttonY, "...", null,
                () -> openTrackMenu(snapshot.trackUri(), snapshot.link(), menuAnchor, buttonY + 22f, true));

        /* Progress, with a draggable handle. */
        float barY = y + cover + 18f;
        drawSeekBar(context, snapshot, x, barY, contentW());

        /* Transport. */
        float transportY = barY + 20f;
        float centre = x + contentW() / 2f;
        float size = 18f;

        icon(context, mouseX, mouseY, centre - 52f, transportY, size,
                (c, ix, iy, s, col) -> SpotifyIcons.previous(c, ix, iy, s, col), accent, SpotifyPlayer::previous);
        icon(context, mouseX, mouseY, centre - size / 2f, transportY, size,
                snapshot.playing()
                        ? (c, ix, iy, s, col) -> SpotifyIcons.pause(c, ix, iy, s, col)
                        : (c, ix, iy, s, col) -> SpotifyIcons.play(c, ix, iy, s, col),
                accent, SpotifyPlayer::toggle);
        icon(context, mouseX, mouseY, centre + 34f, transportY, size,
                (c, ix, iy, s, col) -> SpotifyIcons.next(c, ix, iy, s, col), accent, SpotifyPlayer::next);

        /* Shuffle, repeat, volume. */
        float modeY = transportY + 28f;

        icon(context, mouseX, mouseY, x, modeY, 13f,
                (c, ix, iy, s, col) -> SpotifyIcons.shuffle(c, ix, iy, s, col),
                snapshot.shuffle() ? accent : DIM, () -> SpotifyPlayer.shuffle(!snapshot.shuffle()));
        FontRenderers.sf_medium_mini.drawString(context, snapshot.shuffle() ? "Shuffle on" : "Shuffle off",
                x + 18f, modeY + 3f, DIM.getRGB());

        float repeatX = x + 86f;
        boolean repeatOn = snapshot.repeat() != thunder.hack.utility.spotify.SpotifyModels.Repeat.off;
        icon(context, mouseX, mouseY, repeatX, modeY, 13f,
                (c, ix, iy, s, col) -> SpotifyIcons.repeat(c, ix, iy, s, col,
                        snapshot.repeat() == thunder.hack.utility.spotify.SpotifyModels.Repeat.track),
                repeatOn ? accent : DIM, () -> SpotifyPlayer.repeat(snapshot.repeat().next()));
        FontRenderers.sf_medium_mini.drawString(context, "Repeat " + snapshot.repeat().name(),
                repeatX + 18f, modeY + 3f, DIM.getRGB());

        float volumeX = x + 186f;
        SpotifyIcons.volume(context, volumeX, modeY, 13f, DIM, snapshot.volume());
        if (snapshot.volume() >= 0) {
            float sliderX = volumeX + 18f;
            float sliderW = Math.max(40f, x + contentW() - sliderX);
            float level = snapshot.volume() / 100f;

            Render2DEngine.drawRound(context, sliderX, modeY + 5f, sliderW, 3f, 1.5f, new Color(0x40FFFFFF, true));
            Render2DEngine.drawRound(context, sliderX, modeY + 5f, sliderW * level, 3f, 1.5f, DIM);
            Render2DEngine.drawRound(context, sliderX + sliderW * level - 2.5f, modeY + 2f, 5f, 9f, 2.5f, Color.WHITE);

            volumeTrackX = sliderX;
            volumeTrackW = sliderW;
            hot(sliderX - 3f, modeY, sliderW + 6f, 14f, () -> dragging = Drag.Volume, null);
        }

        /* The device line, and whichever track is next. */
        float footY = modeY + 24f;
        SpotifyIcons.device(context, x, footY, 10f, FAINT);
        String device = snapshot.deviceName().isEmpty() ? "brak urzadzenia" : snapshot.deviceName()
                + (snapshot.deviceType().isEmpty() ? "" : "  (" + snapshot.deviceType() + ")");
        FontRenderers.sf_medium_mini.drawString(context, clip(FontRenderers.sf_medium_mini, device, 180f),
                x + 14f, footY + 1f, FAINT.getRGB());
        hot(x, footY, 194f, 12f, () -> {
            tab = Tab.Devices;
            scroll = 0f;
        }, null);

        List<Entry> queue = SpotifyPlayer.queue();
        if (!queue.isEmpty()) {
            String next = "NEXT  " + queue.get(0).title() + "  -  " + queue.get(0).artist();
            FontRenderers.sf_medium_mini.drawString(context,
                    clip(FontRenderers.sf_medium_mini, next, contentW() - 200f),
                    x + 200f, footY + 1f, FAINT.getRGB());
        }

        if (!snapshot.controllable())
            FontRenderers.sf_medium_mini.drawString(context,
                    "To urzadzenie nie przyjmuje komend - przelacz w Devices.",
                    x, footY + 14f, new Color(0xC09A5A).getRGB());
    }

    private void drawSeekBar(DrawContext context, SpotifyPlayer.Snapshot snapshot, float x, float y, float width) {
        Color accent = HudEditor.getColor(0);

        String elapsed = SpotifyPlayer.stamp(snapshot.progressMs());
        String total = SpotifyPlayer.stamp(snapshot.durationMs());

        float elapsedW = FontRenderers.sf_medium_mini.getStringWidth(elapsed);
        float totalW = FontRenderers.sf_medium_mini.getStringWidth(total);

        float barLeft = x + elapsedW + 8f;
        float barWidth = Math.max(20f, width - elapsedW - totalW - 16f);

        FontRenderers.sf_medium_mini.drawString(context, elapsed, x, y - 2f, DIM.getRGB());
        FontRenderers.sf_medium_mini.drawString(context, total, barLeft + barWidth + 8f, y - 2f, DIM.getRGB());

        float fraction = snapshot.fraction();
        Render2DEngine.drawRound(context, barLeft, y, barWidth, 3f, 1.5f, new Color(0x40FFFFFF, true));
        Render2DEngine.drawRound(context, barLeft, y, barWidth * fraction, 3f, 1.5f, accent);
        Render2DEngine.drawRound(context, barLeft + barWidth * fraction - 2.5f, y - 3f, 5f, 9f, 2.5f, Color.WHITE);

        seekTrackX = barLeft;
        seekTrackW = barWidth;
        hot(barLeft - 3f, y - 6f, barWidth + 6f, 15f, () -> dragging = Drag.Seek, null);
    }

    /* ------------------------------------------------------------------ */
    /* Search                                                              */
    /* ------------------------------------------------------------------ */

    private void drawSearch(DrawContext context, int mouseX, int mouseY) {
        float x = contentX(), y = contentY();

        drawField(context, mouseX, mouseY, searchField, x, y, contentW(), true);

        /* The kind tabs. */
        float tx = x;
        float ty = y + 28f;
        for (Kind kind : KINDS) {
            String label = kind.name();
            float w = FontRenderers.sf_medium_mini.getStringWidth(label) + 14f;
            boolean active = kind == searchKind;
            boolean hovered = Render2DEngine.isHovered(mouseX, mouseY, tx, ty, w, 17f);

            Render2DEngine.drawRound(context, tx, ty, w, 17f, 5f,
                    active ? Render2DEngine.injectAlpha(HudEditor.getColor(0), 200)
                            : new Color(255, 255, 255, hovered ? 24 : 12));
            FontRenderers.sf_medium_mini.drawCenteredString(context, label, tx + w / 2f, ty + 5f,
                    (active ? Color.WHITE : DIM).getRGB());

            Kind target = kind;
            hot(tx, ty, w, 17f, () -> {
                searchKind = target;
                scroll = 0f;
            }, null);

            tx += w + 5f;
        }

        float listY = ty + 24f;
        float listH = contentY() + contentH() - listY;

        if (searchField.text.isBlank()) {
            FontRenderers.sf_medium_mini.drawString(context,
                    "Wpisz czego szukasz. Limit " + SpotifyLibrary.LIMIT + " wynikow na typ.",
                    x, listY, FAINT.getRGB());
            return;
        }

        SpotifyLibrary.Results results = SpotifyLibrary.results();
        List<Hit> hits = results.of(searchKind);

        if (hits.isEmpty()) {
            String state = switch (SpotifyLibrary.searchStatus()) {
                case Loading -> "szukam...";
                case Error -> SpotifyLibrary.searchError();
                default -> "brak wynikow";
            };
            FontRenderers.sf_medium_mini.drawString(context, state, x, listY, DIM.getRGB());
            return;
        }

        Render2DEngine.addWindow(context, x, listY, x + contentW(), listY + listH, 1f);
        float row = listY - scroll;

        for (Hit hit : hits) {
            if (row + ROW > listY && row < listY + listH)
                drawHitRow(context, mouseX, mouseY, hit, x, row);
            row += ROW + 3f;
        }

        Render2DEngine.popWindow();
        clampScroll(hits.size(), listH);
    }

    private void drawHitRow(DrawContext context, int mouseX, int mouseY, Hit hit, float x, float y) {
        boolean hovered = Render2DEngine.isHovered(mouseX, mouseY, x, y, contentW(), ROW);
        Render2DEngine.drawRound(context, x, y, contentW(), ROW, 6f, hovered ? CARD_HOVER : CARD);

        drawThumb(context, hit.imageUrl(), x + 3f, y + 3f, ROW - 6f);

        float textX = x + ROW + 4f;
        FontRenderers.sf_medium_mini.drawString(context,
                clip(FontRenderers.sf_medium_mini, hit.name(), contentW() - ROW - 110f),
                textX, y + 6f, TEXT.getRGB());
        FontRenderers.sf_medium_mini.drawString(context,
                clip(FontRenderers.sf_medium_mini, hit.subtitle(), contentW() - ROW - 110f),
                textX, y + 17f, DIM.getRGB());

        /* Play and, for anything that can be a queue entry, enqueue. */
        float bx = x + contentW() - 8f;

        bx -= 14f;
        float menuAnchor = bx;
        icon(context, mouseX, mouseY, bx, y + 8f, 13f,
                (c, ix, iy, s, col) -> SpotifyIcons.more(c, ix, iy, s, col), DIM,
                () -> openHitMenu(hit, menuAnchor, y + 22f));

        if (hit.kind() == Kind.Track || hit.kind() == Kind.Episode) {
            bx -= 18f;
            icon(context, mouseX, mouseY, bx, y + 8f, 13f,
                    (c, ix, iy, s, col) -> SpotifyIcons.list(c, ix, iy, s, col), DIM,
                    () -> SpotifyPlayer.enqueue(hit.uri()));

            bx -= 18f;
            icon(context, mouseX, mouseY, bx, y + 8f, 13f,
                    (c, ix, iy, s, col) -> SpotifyIcons.plus(c, ix, iy, s, col), DIM,
                    () -> openPicker(hit.uri()));
        }

        bx -= 18f;
        icon(context, mouseX, mouseY, bx, y + 8f, 13f,
                (c, ix, iy, s, col) -> SpotifyIcons.play(c, ix, iy, s, col), HudEditor.getColor(0),
                () -> SpotifyPlayer.play(hit.uri()));

        // The row itself: left plays, right opens the menu, which is the convention everywhere else.
        hot(x, y, contentW() - 80f, ROW, () -> SpotifyPlayer.play(hit.uri()),
                () -> openHitMenu(hit, mouseX, mouseY));
    }

    /* ------------------------------------------------------------------ */
    /* Playlists                                                           */
    /* ------------------------------------------------------------------ */

    private void drawPlaylists(DrawContext context, int mouseX, int mouseY) {
        SpotifyLibrary.loadPlaylists(false);

        if (openPlaylist != null) {
            drawPlaylistDetail(context, mouseX, mouseY);
            return;
        }

        float x = contentX(), y = contentY();

        drawField(context, mouseX, mouseY, playlistFilter, x, y, contentW() - 92f, false);
        pill(context, mouseX, mouseY, x + contentW() - 88f, y, "Create playlist", null, () -> creating = true);

        float listY = y + 28f;
        float listH = contentY() + contentH() - listY;

        List<Playlist> playlists = SpotifyLibrary.filterPlaylists(playlistFilter.text);

        if (playlists.isEmpty()) {
            String state = switch (SpotifyLibrary.playlists().status()) {
                case Loading -> "wczytuje playlisty...";
                case Error -> SpotifyLibrary.playlists().error();
                default -> playlistFilter.text.isBlank() ? "brak playlist" : "nic nie pasuje";
            };
            FontRenderers.sf_medium_mini.drawString(context, state, x, listY, DIM.getRGB());
            return;
        }

        Render2DEngine.addWindow(context, x, listY, x + contentW(), listY + listH, 1f);
        float row = listY - scroll;

        for (Playlist playlist : playlists) {
            if (row + ROW > listY && row < listY + listH)
                drawPlaylistRow(context, mouseX, mouseY, playlist, x, row);
            row += ROW + 3f;
        }

        Render2DEngine.popWindow();
        clampScroll(playlists.size(), listH);
    }

    private void drawPlaylistRow(DrawContext context, int mouseX, int mouseY, Playlist playlist, float x, float y) {
        boolean hovered = Render2DEngine.isHovered(mouseX, mouseY, x, y, contentW(), ROW);
        Render2DEngine.drawRound(context, x, y, contentW(), ROW, 6f, hovered ? CARD_HOVER : CARD);

        drawThumb(context, playlist.imageUrl(), x + 3f, y + 3f, ROW - 6f);

        float textX = x + ROW + 4f;
        FontRenderers.sf_medium_mini.drawString(context,
                clip(FontRenderers.sf_medium_mini, playlist.name(), contentW() - ROW - 120f),
                textX, y + 6f, TEXT.getRGB());

        String meta = playlist.owner() + "   -   " + playlist.total() + " items"
                + (playlist.collaborative() ? "   -   collab" : "");
        FontRenderers.sf_medium_mini.drawString(context,
                clip(FontRenderers.sf_medium_mini, meta, contentW() - ROW - 120f),
                textX, y + 17f, DIM.getRGB());

        float bx = x + contentW() - 8f;

        bx -= 14f;
        icon(context, mouseX, mouseY, bx, y + 8f, 13f,
                (c, ix, iy, s, col) -> SpotifyIcons.external(c, ix, iy, s, col), DIM,
                () -> openLink("https://open.spotify.com/playlist/" + playlist.id()));

        // Add the track that is playing - the single most common thing anybody does on this screen.
        String current = SpotifyPlayer.state().trackUri();
        if (!current.isEmpty() && playlist.editableBy(SpotifyLibrary.meId())) {
            bx -= 18f;
            icon(context, mouseX, mouseY, bx, y + 8f, 13f,
                    (c, ix, iy, s, col) -> SpotifyIcons.plus(c, ix, iy, s, col), GREEN,
                    () -> SpotifyLibrary.addToPlaylist(playlist.id(), current));
        }

        bx -= 18f;
        icon(context, mouseX, mouseY, bx, y + 8f, 13f,
                (c, ix, iy, s, col) -> SpotifyIcons.play(c, ix, iy, s, col), HudEditor.getColor(0),
                () -> SpotifyPlayer.play(playlist.uri()));

        hot(x, y, contentW() - 80f, ROW, () -> {
            openPlaylist = playlist;
            scroll = 0f;
            SpotifyLibrary.loadTracks(playlist.id(), false);
        }, null);
    }

    /** Inside one playlist: its tracks, with Remove only where this account may remove. */
    private void drawPlaylistDetail(DrawContext context, int mouseX, int mouseY) {
        Playlist playlist = openPlaylist;
        SpotifyLibrary.loadTracks(playlist.id(), false);

        float x = contentX(), y = contentY();

        icon(context, mouseX, mouseY, x, y, 12f,
                (c, ix, iy, s, col) -> SpotifyIcons.chevron(c, ix, iy, s, col, 2), DIM,
                () -> {
                    openPlaylist = null;
                    scroll = 0f;
                });

        FontRenderers.sf_bold.drawString(context, clip(FontRenderers.sf_bold, playlist.name(), contentW() - 120f),
                x + 18f, y, TEXT.getRGB());

        boolean editable = playlist.editableBy(SpotifyLibrary.meId());
        FontRenderers.sf_medium_mini.drawString(context,
                playlist.owner() + "   -   " + playlist.total() + " items"
                        + (editable ? "" : "   -   tylko do odczytu"),
                x + 18f, y + 14f, DIM.getRGB());

        pill(context, mouseX, mouseY, x + contentW() - 44f, y, "Play", null,
                () -> SpotifyPlayer.play(playlist.uri()));

        float listY = y + 32f;
        float listH = contentY() + contentH() - listY;

        SpotifyLibrary.Async<List<Entry>> holder = SpotifyLibrary.tracks(playlist.id());
        List<Entry> tracks = holder.get();

        if (tracks.isEmpty()) {
            String state = switch (holder.status()) {
                case Loading -> "wczytuje...";
                case Error -> holder.error();
                default -> "pusta playlista";
            };
            FontRenderers.sf_medium_mini.drawString(context, state, x, listY, DIM.getRGB());
            return;
        }

        Render2DEngine.addWindow(context, x, listY, x + contentW(), listY + listH, 1f);
        float row = listY - scroll;

        for (Entry entry : tracks) {
            if (row + 24f > listY && row < listY + listH)
                drawTrackRow(context, mouseX, mouseY, entry, x, row, playlist, editable);
            row += 27f;
        }

        Render2DEngine.popWindow();
        clampScroll(tracks.size(), listH);
    }

    private void drawTrackRow(DrawContext context, int mouseX, int mouseY, Entry entry, float x, float y,
                              Playlist playlist, boolean editable) {
        boolean hovered = Render2DEngine.isHovered(mouseX, mouseY, x, y, contentW(), 24f);
        Render2DEngine.drawRound(context, x, y, contentW(), 24f, 5f, hovered ? CARD_HOVER : CARD);

        drawThumb(context, entry.imageUrl(), x + 2f, y + 2f, 20f);

        FontRenderers.sf_medium_mini.drawString(context,
                clip(FontRenderers.sf_medium_mini, entry.title(), contentW() - 190f),
                x + 26f, y + 3f, TEXT.getRGB());
        FontRenderers.sf_medium_mini.drawString(context,
                clip(FontRenderers.sf_medium_mini, entry.artist(), contentW() - 190f),
                x + 26f, y + 13f, DIM.getRGB());

        String length = SpotifyPlayer.stamp(entry.durationMs());
        FontRenderers.sf_medium_mini.drawString(context, length,
                x + contentW() - 92f - FontRenderers.sf_medium_mini.getStringWidth(length), y + 8f, FAINT.getRGB());

        float bx = x + contentW() - 6f;

        bx -= 13f;
        float menuAnchor = bx;
        icon(context, mouseX, mouseY, bx, y + 6f, 12f,
                (c, ix, iy, s, col) -> SpotifyIcons.more(c, ix, iy, s, col), DIM,
                () -> openEntryMenu(entry, playlist, editable, menuAnchor, y + 18f));

        // Remove is drawn only where the permission exists: a button that is always there and sometimes
        // refused teaches people to distrust every button next to it.
        if (editable) {
            bx -= 16f;
            icon(context, mouseX, mouseY, bx, y + 6f, 12f,
                    (c, ix, iy, s, col) -> SpotifyIcons.close(c, ix, iy, s, col), new Color(0xC06A6A),
                    () -> SpotifyLibrary.removeFromPlaylist(playlist.id(), entry.uri()));
        }

        bx -= 16f;
        icon(context, mouseX, mouseY, bx, y + 6f, 12f,
                (c, ix, iy, s, col) -> SpotifyIcons.list(c, ix, iy, s, col), DIM,
                () -> SpotifyPlayer.enqueue(entry.uri()));

        bx -= 16f;
        icon(context, mouseX, mouseY, bx, y + 6f, 12f,
                (c, ix, iy, s, col) -> SpotifyIcons.play(c, ix, iy, s, col), HudEditor.getColor(0),
                // Within the playlist, so the rest of it follows instead of the track playing alone.
                () -> SpotifyPlayer.playWithin(playlist.uri(), entry.uri()));

        hot(x, y, contentW() - 70f, 24f,
                () -> SpotifyPlayer.playWithin(playlist.uri(), entry.uri()),
                () -> openEntryMenu(entry, playlist, editable, mouseX, mouseY));
    }

    /* ------------------------------------------------------------------ */
    /* Queue                                                               */
    /* ------------------------------------------------------------------ */

    private void drawQueue(DrawContext context, int mouseX, int mouseY) {
        SpotifyPlayer.refreshQueue();

        float x = contentX(), y = contentY();
        SpotifyPlayer.Snapshot snapshot = SpotifyPlayer.state();

        FontRenderers.sf_bold.drawString(context, "Queue", x, y, TEXT.getRGB());
        pill(context, mouseX, mouseY, x + contentW() - 88f, y - 2f, "Add from Search", null, () -> {
            tab = Tab.Search;
            searchField.focused = true;
            scroll = 0f;
        });

        /* Now playing, as the head of the queue. */
        float headY = y + 20f;
        if (snapshot.present()) {
            Render2DEngine.drawRound(context, x, headY, contentW(), 28f, 6f,
                    Render2DEngine.injectAlpha(HudEditor.getColor(0), 40));
            drawThumb(context, snapshot.imageUrl(), x + 3f, headY + 3f, 22f);
            FontRenderers.sf_medium_mini.drawString(context, "NOW", x + contentW() - 28f, headY + 10f, GREEN.getRGB());
            FontRenderers.sf_medium_mini.drawString(context,
                    clip(FontRenderers.sf_medium_mini, snapshot.title(), contentW() - 80f),
                    x + 29f, headY + 5f, TEXT.getRGB());
            FontRenderers.sf_medium_mini.drawString(context,
                    clip(FontRenderers.sf_medium_mini, snapshot.artist(), contentW() - 80f),
                    x + 29f, headY + 16f, DIM.getRGB());
        }

        float listY = headY + 34f;
        float listH = contentY() + contentH() - listY - 12f;

        List<Entry> queue = SpotifyPlayer.queue();
        if (queue.isEmpty()) {
            FontRenderers.sf_medium_mini.drawString(context, "Kolejka jest pusta.", x, listY, DIM.getRGB());
        } else {
            Render2DEngine.addWindow(context, x, listY, x + contentW(), listY + listH, 1f);
            float row = listY - scroll;

            for (int i = 0; i < queue.size(); i++) {
                Entry entry = queue.get(i);
                if (row + 24f > listY && row < listY + listH)
                    drawQueueRow(context, mouseX, mouseY, entry, i + 1, x, row);
                row += 27f;
            }

            Render2DEngine.popWindow();
            clampScroll(queue.size(), listH);
        }

        // Said plainly rather than offered as a button that could only fail: there is no remove-from-queue
        // endpoint in the Web API at any scope, so the way to drop a queue is to start something else.
        if (!SpotifyPlayer.canRemoveFromQueue())
            FontRenderers.sf_medium_mini.drawString(context,
                    "Spotify nie ma API do usuwania z kolejki - kolejke czysci dopiero odtworzenie czegos innego.",
                    x, contentY() + contentH() - 10f, FAINT.getRGB());
    }

    private void drawQueueRow(DrawContext context, int mouseX, int mouseY, Entry entry, int index, float x, float y) {
        boolean hovered = Render2DEngine.isHovered(mouseX, mouseY, x, y, contentW(), 24f);
        Render2DEngine.drawRound(context, x, y, contentW(), 24f, 5f, hovered ? CARD_HOVER : CARD);

        FontRenderers.sf_medium_mini.drawString(context, String.valueOf(index), x + 6f, y + 8f, FAINT.getRGB());
        drawThumb(context, entry.imageUrl(), x + 18f, y + 2f, 20f);

        FontRenderers.sf_medium_mini.drawString(context,
                clip(FontRenderers.sf_medium_mini, entry.title(), contentW() - 150f),
                x + 42f, y + 3f, TEXT.getRGB());
        FontRenderers.sf_medium_mini.drawString(context,
                clip(FontRenderers.sf_medium_mini, entry.artist(), contentW() - 150f),
                x + 42f, y + 13f, DIM.getRGB());

        String length = SpotifyPlayer.stamp(entry.durationMs());
        FontRenderers.sf_medium_mini.drawString(context, length,
                x + contentW() - 60f - FontRenderers.sf_medium_mini.getStringWidth(length), y + 8f, FAINT.getRGB());

        float bx = x + contentW() - 6f;

        bx -= 13f;
        float menuAnchor = bx;
        icon(context, mouseX, mouseY, bx, y + 6f, 12f,
                (c, ix, iy, s, col) -> SpotifyIcons.more(c, ix, iy, s, col), DIM,
                () -> openEntryMenu(entry, null, false, menuAnchor, y + 18f));

        bx -= 16f;
        icon(context, mouseX, mouseY, bx, y + 6f, 12f,
                (c, ix, iy, s, col) -> SpotifyIcons.plus(c, ix, iy, s, col), DIM,
                () -> openPicker(entry.uri()));

        bx -= 16f;
        icon(context, mouseX, mouseY, bx, y + 6f, 12f,
                (c, ix, iy, s, col) -> SpotifyIcons.play(c, ix, iy, s, col), HudEditor.getColor(0),
                () -> SpotifyPlayer.play(entry.uri()));

        hot(x, y, contentW() - 70f, 24f, () -> SpotifyPlayer.play(entry.uri()),
                () -> openEntryMenu(entry, null, false, mouseX, mouseY));
    }

    /* ------------------------------------------------------------------ */
    /* Devices                                                             */
    /* ------------------------------------------------------------------ */

    private void drawDevices(DrawContext context, int mouseX, int mouseY) {
        SpotifyPlayer.refreshDevices();

        float x = contentX(), y = contentY();
        FontRenderers.sf_bold.drawString(context, "Devices", x, y, TEXT.getRGB());

        List<Device> devices = SpotifyPlayer.devices();
        if (devices.isEmpty()) {
            FontRenderers.sf_medium_mini.drawString(context,
                    "Brak urzadzen. Otworz Spotify gdziekolwiek - na telefonie, w aplikacji,",
                    x, y + 20f, DIM.getRGB());
            FontRenderers.sf_medium_mini.drawString(context,
                    "w przegladarce - i pojawi sie tutaj.", x, y + 31f, DIM.getRGB());
            return;
        }

        float row = y + 22f;
        for (Device device : devices) {
            boolean hovered = Render2DEngine.isHovered(mouseX, mouseY, x, row, contentW(), 28f);
            Render2DEngine.drawRound(context, x, row, contentW(), 28f, 6f,
                    device.active() ? Render2DEngine.injectAlpha(HudEditor.getColor(0), 46)
                            : hovered ? CARD_HOVER : CARD);

            SpotifyIcons.device(context, x + 7f, row + 8f, 12f, device.active() ? GREEN : DIM);
            FontRenderers.sf_medium_mini.drawString(context, device.name(), x + 25f, row + 5f, TEXT.getRGB());

            String meta = device.type()
                    + (device.volume() >= 0 ? "   -   vol " + device.volume() + "%" : "")
                    + (device.restricted() ? "   -   nie przyjmuje komend" : "")
                    + (device.active() ? "   -   aktywne" : "");
            FontRenderers.sf_medium_mini.drawString(context, meta, x + 25f, row + 16f, DIM.getRGB());

            if (!device.restricted())
                hot(x, row, contentW(), 28f, () -> SpotifyPlayer.transfer(device.id()), null);

            row += 31f;
        }
    }

    /* ------------------------------------------------------------------ */
    /* Playlist picker                                                     */
    /* ------------------------------------------------------------------ */

    private void openPicker(String uri) {
        if (uri == null || uri.isEmpty()) return;

        pickerUri = uri;
        playlistFilter.focused = true;
        SpotifyLibrary.loadPlaylists(false);
    }

    private void drawPicker(DrawContext context, int mouseX, int mouseY) {
        float w = 260f, h = 220f;
        float x = panelX() + (WIDTH - w) / 2f;
        float y = panelY() + (HEIGHT - h) / 2f;

        Render2DEngine.drawRound(context, x, y, w, h, 10f, new Color(13, 13, 18, 250));

        FontRenderers.sf_bold.drawString(context, "Your playlists", x + 12f, y + 12f, TEXT.getRGB());
        icon(context, mouseX, mouseY, x + w - 24f, y + 12f, 12f,
                (c, ix, iy, s, col) -> SpotifyIcons.close(c, ix, iy, s, col), DIM, () -> pickerUri = null);

        drawField(context, mouseX, mouseY, playlistFilter, x + 12f, y + 30f, w - 24f, false);

        float listY = y + 58f;
        float listH = h - 58f - 38f;

        List<Playlist> playlists = SpotifyLibrary.filterPlaylists(playlistFilter.text);
        String uri = pickerUri;

        if (playlists.isEmpty()) {
            FontRenderers.sf_medium_mini.drawString(context,
                    SpotifyLibrary.playlists().status() == SpotifyLibrary.Status.Loading
                            ? "wczytuje..." : "nic nie pasuje",
                    x + 12f, listY, DIM.getRGB());
        } else {
            Render2DEngine.addWindow(context, x + 12f, listY, x + w - 12f, listY + listH, 1f);
            float row = listY - scroll;

            for (Playlist playlist : playlists) {
                if (row + 22f > listY && row < listY + listH) {
                    boolean editable = playlist.editableBy(SpotifyLibrary.meId());
                    boolean hovered = Render2DEngine.isHovered(mouseX, mouseY, x + 12f, row, w - 24f, 22f);

                    Render2DEngine.drawRound(context, x + 12f, row, w - 24f, 22f, 5f,
                            hovered && editable ? CARD_HOVER : CARD);
                    drawThumb(context, playlist.imageUrl(), x + 14f, row + 2f, 18f);
                    FontRenderers.sf_medium_mini.drawString(context,
                            clip(FontRenderers.sf_medium_mini, playlist.name(), w - 70f),
                            x + 36f, row + 3f, (editable ? TEXT : FAINT).getRGB());
                    FontRenderers.sf_medium_mini.drawString(context,
                            editable ? playlist.total() + " items" : "tylko do odczytu",
                            x + 36f, row + 12f, DIM.getRGB());

                    if (editable) {
                        float rowY = row;
                        hot(x + 12f, rowY, w - 24f, 22f, () -> {
                            SpotifyLibrary.addToPlaylist(playlist.id(), uri);
                            pickerUri = null;
                        }, null);
                    }
                }
                row += 25f;
            }

            Render2DEngine.popWindow();
            clampScroll(playlists.size(), listH);
        }

        pill(context, mouseX, mouseY, x + 12f, y + h - 30f, "Create playlist", null, () -> {
            creating = true;
            pickerUri = uri;
        });
    }

    /* ------------------------------------------------------------------ */
    /* Create playlist                                                     */
    /* ------------------------------------------------------------------ */

    private void drawCreate(DrawContext context, int mouseX, int mouseY) {
        float w = 250f, h = 160f;
        float x = panelX() + (WIDTH - w) / 2f;
        float y = panelY() + (HEIGHT - h) / 2f;

        Render2DEngine.drawRound(context, x, y, w, h, 10f, new Color(13, 13, 18, 252));

        FontRenderers.sf_bold.drawString(context, "Create playlist", x + 12f, y + 12f, TEXT.getRGB());
        icon(context, mouseX, mouseY, x + w - 24f, y + 12f, 12f,
                (c, ix, iy, s, col) -> SpotifyIcons.close(c, ix, iy, s, col), DIM, () -> creating = false);

        drawField(context, mouseX, mouseY, newName, x + 12f, y + 32f, w - 24f, false);
        drawField(context, mouseX, mouseY, newDescription, x + 12f, y + 60f, w - 24f, false);

        /* Public / private, as two mutually exclusive pills - clearer than a checkbox whose label has to
         * explain which way round it is. */
        float toggleY = y + 88f;
        float half = (w - 30f) / 2f;

        for (int i = 0; i < 2; i++) {
            boolean isPublic = i == 1;
            boolean active = newPublic == isPublic;
            float bx = x + 12f + i * (half + 6f);
            boolean hovered = Render2DEngine.isHovered(mouseX, mouseY, bx, toggleY, half, 20f);

            Render2DEngine.drawRound(context, bx, toggleY, half, 20f, 5f,
                    active ? Render2DEngine.injectAlpha(HudEditor.getColor(0), 200)
                            : new Color(255, 255, 255, hovered ? 24 : 12));
            FontRenderers.sf_medium_mini.drawCenteredString(context, isPublic ? "Public" : "Private",
                    bx + half / 2f, toggleY + 6f, (active ? Color.WHITE : DIM).getRGB());

            hot(bx, toggleY, half, 20f, () -> newPublic = isPublic, null);
        }

        String addTo = pickerUri;
        if (addTo != null && !addTo.isEmpty())
            FontRenderers.sf_medium_mini.drawString(context, "Doda tez aktualny utwor.",
                    x + 12f, y + 114f, FAINT.getRGB());

        boolean valid = !newName.text.isBlank();
        float buttonY = y + h - 30f;
        boolean hovered = Render2DEngine.isHovered(mouseX, mouseY, x + 12f, buttonY, w - 24f, 22f);

        Render2DEngine.drawRound(context, x + 12f, buttonY, w - 24f, 22f, 6f,
                valid ? Render2DEngine.injectAlpha(GREEN, hovered ? 240 : 190) : new Color(255, 255, 255, 14));
        FontRenderers.sf_medium.drawCenteredString(context, "Create", x + w / 2f, buttonY + 7f,
                (valid ? Color.WHITE : FAINT).getRGB());

        if (valid)
            hot(x + 12f, buttonY, w - 24f, 22f, () -> {
                SpotifyLibrary.createPlaylist(newName.text, newDescription.text, newPublic, addTo);
                newName.text = "";
                newDescription.text = "";
                creating = false;
                pickerUri = null;
            }, null);
    }

    /* ------------------------------------------------------------------ */
    /* Context menus                                                       */
    /* ------------------------------------------------------------------ */

    private void openTrackMenu(String uri, String link, float x, float y, boolean current) {
        if (uri == null || uri.isEmpty()) return;

        SpotifyPlayer.Snapshot snapshot = SpotifyPlayer.state();
        List<MenuItem> items = new ArrayList<>();

        items.add(new MenuItem("Play", () -> SpotifyPlayer.play(uri), true));
        items.add(new MenuItem("Pause", SpotifyPlayer::pause, snapshot.playing()));
        items.add(new MenuItem("Next", SpotifyPlayer::next, true));
        items.add(new MenuItem("Add to Queue", () -> SpotifyPlayer.enqueue(uri), true));
        items.add(new MenuItem("Add to Playlist", () -> openPicker(uri), true));
        items.add(new MenuItem(current && snapshot.saved() ? "Remove from Library" : "Save to Library",
                () -> {
                    if (current) SpotifyLibrary.toggleSaved();
                    else SpotifyLibrary.setSaved(thunder.hack.utility.spotify.SpotifyModels.idOf(uri), uri, true);
                }, true));
        items.add(new MenuItem("Open in Spotify", () -> openLink(resolve(link, uri)), true));
        items.add(new MenuItem("Copy Spotify Link", () -> copy(resolve(link, uri)), true));

        showMenu(items, x, y);
    }

    private void openHitMenu(Hit hit, float x, float y) {
        List<MenuItem> items = new ArrayList<>();
        boolean track = hit.kind() == Kind.Track || hit.kind() == Kind.Episode;

        items.add(new MenuItem("Play", () -> SpotifyPlayer.play(hit.uri()), true));
        items.add(new MenuItem("Pause", SpotifyPlayer::pause, SpotifyPlayer.state().playing()));
        items.add(new MenuItem("Next", SpotifyPlayer::next, true));
        items.add(new MenuItem("Add to Queue", () -> SpotifyPlayer.enqueue(hit.uri()), track));
        items.add(new MenuItem("Add to Playlist", () -> openPicker(hit.uri()), track));
        items.add(new MenuItem("Save to Library",
                () -> SpotifyLibrary.setSaved(hit.id(), hit.uri(), true), hit.kind() == Kind.Track));
        items.add(new MenuItem("Open in Spotify", () -> openLink(resolve(hit.link(), hit.uri())), true));
        items.add(new MenuItem("Copy Spotify Link", () -> copy(resolve(hit.link(), hit.uri())), true));

        showMenu(items, x, y);
    }

    private void openEntryMenu(Entry entry, Playlist playlist, boolean editable, float x, float y) {
        List<MenuItem> items = new ArrayList<>();

        items.add(new MenuItem("Play", () -> {
            if (playlist != null) SpotifyPlayer.playWithin(playlist.uri(), entry.uri());
            else SpotifyPlayer.play(entry.uri());
        }, true));
        items.add(new MenuItem("Pause", SpotifyPlayer::pause, SpotifyPlayer.state().playing()));
        items.add(new MenuItem("Next", SpotifyPlayer::next, true));
        items.add(new MenuItem("Add to Queue", () -> SpotifyPlayer.enqueue(entry.uri()), true));
        items.add(new MenuItem("Add to Playlist", () -> openPicker(entry.uri()), true));
        items.add(new MenuItem("Save to Library",
                () -> SpotifyLibrary.setSaved(entry.id(), entry.uri(), true), true));

        if (playlist != null)
            items.add(new MenuItem("Remove from this playlist",
                    () -> SpotifyLibrary.removeFromPlaylist(playlist.id(), entry.uri()), editable));

        items.add(new MenuItem("Open in Spotify", () -> openLink(resolve("", entry.uri())), true));
        items.add(new MenuItem("Copy Spotify Link", () -> copy(resolve("", entry.uri())), true));

        showMenu(items, x, y);
    }

    private void showMenu(List<MenuItem> items, float x, float y) {
        menu = items;

        float w = 168f;
        float h = items.size() * 17f + 8f;

        // Kept inside the panel: a menu opened near the bottom edge would otherwise run off the screen
        // and lose its last entries, which are the Open and Copy ones people reach for most.
        menuX = Math.clamp(x, panelX() + 4f, panelX() + WIDTH - w - 4f);
        menuY = Math.clamp(y, panelY() + 4f, panelY() + HEIGHT - h - 4f);
    }

    private void drawMenu(DrawContext context, int mouseX, int mouseY) {
        List<MenuItem> items = menu;
        float w = 168f;
        float h = items.size() * 17f + 8f;

        Render2DEngine.drawRound(context, menuX, menuY, w, h, 7f, new Color(20, 20, 26, 252));

        float row = menuY + 4f;
        for (MenuItem item : items) {
            boolean hovered = item.enabled() && Render2DEngine.isHovered(mouseX, mouseY, menuX + 3f, row, w - 6f, 16f);
            if (hovered)
                Render2DEngine.drawRound(context, menuX + 3f, row, w - 6f, 16f, 4f, new Color(255, 255, 255, 22));

            FontRenderers.sf_medium_mini.drawString(context, item.label(), menuX + 9f, row + 4f,
                    (item.enabled() ? TEXT : FAINT).getRGB());

            if (item.enabled()) {
                float rowY = row;
                hot(menuX + 3f, rowY, w - 6f, 16f, () -> {
                    item.action().run();
                    menu = null;
                }, null);
            }
            row += 17f;
        }
    }

    /* ------------------------------------------------------------------ */
    /* Shared widgets                                                      */
    /* ------------------------------------------------------------------ */

    /** A cover thumbnail, or a neutral plate while there is not one. */
    private void drawThumb(DrawContext context, String url, float x, float y, float size) {
        Identifier art = SpotifyArt.of(url);
        Render2DEngine.drawRound(context, x, y, size, size, 4f, new Color(10, 10, 14));

        if (art != null)
            Render2DEngine.renderTexture(context, art, x + 1f, y + 1f, size - 2f, size - 2f,
                    0, 0, size, size, size, size, Color.WHITE);
    }

    /** A labelled button. Returns its width so callers can lay a row out left to right. */
    private float pill(DrawContext context, int mouseX, int mouseY, float x, float y, String label,
                       Color tint, Runnable action) {
        float w = FontRenderers.sf_medium_mini.getStringWidth(label) + 16f;
        boolean hovered = Render2DEngine.isHovered(mouseX, mouseY, x, y, w, 20f);

        Render2DEngine.drawRound(context, x, y, w, 20f, 5f,
                tint != null ? Render2DEngine.injectAlpha(tint, hovered ? 230 : 180)
                        : new Color(255, 255, 255, hovered ? 28 : 14));
        FontRenderers.sf_medium_mini.drawCenteredString(context, label, x + w / 2f, y + 6f,
                (tint != null ? Color.WHITE : TEXT).getRGB());

        hot(x, y, w, 20f, action, null);
        return w;
    }

    /** A square icon button. The painter is passed in so one hitbox serves every glyph. */
    private interface Painter {
        void paint(DrawContext context, float x, float y, float size, Color color);
    }

    private void icon(DrawContext context, int mouseX, int mouseY, float x, float y, float size,
                      Painter painter, Color color, Runnable action) {
        boolean hovered = Render2DEngine.isHovered(mouseX, mouseY, x - 3f, y - 3f, size + 6f, size + 6f);
        if (hovered)
            Render2DEngine.drawRound(context, x - 3f, y - 3f, size + 6f, size + 6f, 4f, new Color(255, 255, 255, 20));

        painter.paint(context, x, y, size, hovered ? Render2DEngine.brighter(color, 0.25f) : color);
        hot(x - 3f, y - 3f, size + 6f, size + 6f, action, null);
    }

    /** A text input, with a blinking caret while it has focus. */
    private void drawField(DrawContext context, int mouseX, int mouseY, Field field,
                           float x, float y, float w, boolean magnifier) {
        boolean hovered = Render2DEngine.isHovered(mouseX, mouseY, x, y, w, 20f);

        Render2DEngine.drawRound(context, x, y, w, 20f, 5f,
                field.focused ? new Color(255, 255, 255, 26) : new Color(255, 255, 255, hovered ? 20 : 12));
        if (field.focused)
            Render2DEngine.drawRound(context, x, y + 18f, w, 1.5f, 0.75f,
                    Render2DEngine.injectAlpha(HudEditor.getColor(0), 220));

        float textX = x + 8f;
        if (magnifier) {
            SpotifyIcons.search(context, x + 6f, y + 5f, 10f, DIM);
            textX = x + 20f;
        }

        boolean empty = field.text.isEmpty();
        String shown = empty ? field.placeholder : field.text;
        // Clipped from the left so the caret end stays visible: a field that scrolls off the right is a
        // field you cannot see yourself typing in.
        shown = clipLeft(FontRenderers.sf_medium_mini, shown, w - (textX - x) - 14f);

        FontRenderers.sf_medium_mini.drawString(context, shown, textX, y + 6f,
                (empty ? FAINT : TEXT).getRGB());

        if (field.focused && (System.currentTimeMillis() / 500L) % 2 == 0) {
            float caretX = textX + FontRenderers.sf_medium_mini.getStringWidth(empty ? "" : shown) + 1f;
            Render2DEngine.drawRound(context, caretX, y + 5f, 1f, 10f, 0.5f, TEXT);
        }

        hot(x, y, w, 20f, () -> focus(field), null);
    }

    /** Focus is exclusive - two carets blinking in two boxes is never what anybody meant. */
    private void focus(Field field) {
        searchField.focused = false;
        playlistFilter.focused = false;
        newName.focused = false;
        newDescription.focused = false;
        field.focused = true;
    }

    private Field focused() {
        if (searchField.focused) return searchField;
        if (playlistFilter.focused) return playlistFilter;
        if (newName.focused) return newName;
        if (newDescription.focused) return newDescription;
        return null;
    }

    /* ------------------------------------------------------------------ */
    /* Input                                                               */
    /* ------------------------------------------------------------------ */

    private enum Drag {
        None, Seek, Volume
    }

    private Drag dragging = Drag.None;
    private float seekTrackX, seekTrackW, volumeTrackX, volumeTrackW;

    @Override
    public boolean mouseClicked(net.minecraft.client.gui.Click click, boolean doubled) {
        double mx = click.x(), my = click.y();

        // A click anywhere else closes an open menu, which is what every menu everywhere does. Checked
        // before the hot list so the menu's own rows still get their click.
        boolean onMenu = menu != null && mx >= menuX && mx <= menuX + 168f
                && my >= menuY && my <= menuY + menu.size() * 17f + 8f;

        int floor = Math.max(0, overlayFloor);

        if (click.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT || click.button() == GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
            for (int i = hots.size() - 1; i >= floor; i--) {
                Hot hot = hots.get(i);
                if (!hot.covers(mx, my)) continue;

                Runnable action = click.button() == GLFW.GLFW_MOUSE_BUTTON_RIGHT ? hot.right() : hot.left();
                if (action == null) continue;

                action.run();
                return true;
            }
        }

        if (menu != null && !onMenu) {
            menu = null;
            return true;
        }

        // Clicking empty space drops focus, so Escape is not the only way out of a text box.
        Field field = focused();
        if (field != null) field.focused = false;
        return true;
    }

    @Override
    public boolean mouseDragged(net.minecraft.client.gui.Click click, double deltaX, double deltaY) {
        if (dragging == Drag.Seek && seekTrackW > 0f) {
            long duration = SpotifyPlayer.state().durationMs();
            if (duration > 0) {
                float fraction = (float) Math.clamp((click.x() - seekTrackX) / seekTrackW, 0.0, 1.0);
                SpotifyPlayer.seek((long) (fraction * duration));
            }
            return true;
        }

        if (dragging == Drag.Volume && volumeTrackW > 0f) {
            float fraction = (float) Math.clamp((click.x() - volumeTrackX) / volumeTrackW, 0.0, 1.0);
            SpotifyPlayer.volume(Math.round(fraction * 100f));
            return true;
        }

        return super.mouseDragged(click, deltaX, deltaY);
    }

    @Override
    public boolean mouseReleased(net.minecraft.client.gui.Click click) {
        dragging = Drag.None;
        return super.mouseReleased(click);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        scroll = Math.max(0f, scroll - (float) verticalAmount * 22f);
        return true;
    }

    /** Stops the list scrolling past its end, from the count that was actually drawn. */
    private void clampScroll(int rows, float viewHeight) {
        float total = rows * 27f;
        float max = Math.max(0f, total - viewHeight);
        if (scroll > max) scroll = max;
    }

    @Override
    public boolean charTyped(net.minecraft.client.input.CharInput input) {
        Field field = focused();
        if (field == null) return false;

        if (field.type((char) input.codepoint())) afterTyping(field);
        return true;
    }

    @Override
    public boolean keyPressed(net.minecraft.client.input.KeyInput input) {
        int key = input.key();
        Field field = focused();

        if (field != null) {
            switch (key) {
                case GLFW.GLFW_KEY_BACKSPACE -> {
                    if (field.backspace()) afterTyping(field);
                    return true;
                }
                case GLFW.GLFW_KEY_ESCAPE -> {
                    field.focused = false;
                    return true;
                }
                case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> {
                    if (field == searchField) SpotifyLibrary.search(field.text, KINDS);
                    else if (field == newName || field == newDescription) field.focused = false;
                    return true;
                }
                case GLFW.GLFW_KEY_V -> {
                    // Ctrl+V, because a search you pasted a link into is how most people get here.
                    if ((input.modifiers() & GLFW.GLFW_MOD_CONTROL) != 0) {
                        String clip = client == null ? "" : client.keyboard.getClipboard();
                        for (char c : clip.toCharArray()) field.type(c);
                        afterTyping(field);
                        return true;
                    }
                }
                default -> {
                }
            }
        }

        if (key == GLFW.GLFW_KEY_ESCAPE) {
            // Unwind one layer at a time: a menu, then an overlay, then the view, then the screen. One
            // Escape closing the whole panel from three layers deep loses whatever was half-filled in.
            if (menu != null) menu = null;
            else if (creating) creating = false;
            else if (pickerUri != null) pickerUri = null;
            else if (openPlaylist != null) openPlaylist = null;
            else close();
            return true;
        }

        return super.keyPressed(input);
    }

    /** The search box searches as you type; the other fields only filter what is already loaded. */
    private void afterTyping(Field field) {
        if (field == searchField) {
            SpotifyLibrary.search(field.text, KINDS);
            scroll = 0f;
        } else if (field == playlistFilter) {
            scroll = 0f;
        }
    }

    /* ------------------------------------------------------------------ */
    /* Helpers                                                             */
    /* ------------------------------------------------------------------ */

    /** A share link, built from the uri when the object did not carry one. */
    private static String resolve(String link, String uri) {
        if (link != null && !link.isEmpty()) return link;
        if (uri == null || uri.isEmpty()) return "";

        // spotify:track:ID -> open.spotify.com/track/ID, which is the shareable form of the same thing.
        String[] parts = uri.split(":");
        return parts.length < 3 ? "" : "https://open.spotify.com/" + parts[1] + "/" + parts[2];
    }

    private void openLink(String link) {
        if (link == null || link.isEmpty()) return;
        try {
            Util.getOperatingSystem().open(URI.create(link));
        } catch (Throwable ignored) {
            // No handler for the scheme; nothing useful to say about it.
        }
    }

    private void copy(String text) {
        if (text == null || text.isEmpty() || client == null) return;
        client.keyboard.setClipboard(text);
    }

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

    /** Keeps the END of the string, for a text field being typed into. */
    private static String clipLeft(thunder.hack.gui.font.FontRenderer font, String text, float maxWidth) {
        if (text == null || text.isEmpty()) return "";
        if (font.getStringWidth(text) <= maxWidth) return text;

        int from = 0;
        while (from < text.length() - 1 && font.getStringWidth(text.substring(from)) > maxWidth) from++;
        return text.substring(from);
    }

    private void hot(float x, float y, float w, float h, Runnable left, Runnable right) {
        hots.add(new Hot(x, y, w, h, left, right));
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}
