package thunder.hack.gui.mainmenu;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.screen.multiplayer.MultiplayerScreen;
import net.minecraft.client.gui.screen.option.OptionsScreen;
import net.minecraft.client.gui.screen.world.SelectWorldScreen;
import net.minecraft.client.resource.language.I18n;
import net.minecraft.text.Text;
import org.jetbrains.annotations.NotNull;
import org.lwjgl.glfw.GLFW;
import thunder.hack.gui.font.FontRenderer;
import thunder.hack.gui.font.FontRenderers;
import thunder.hack.utility.render.Render2DEngine;
import thunder.hack.utility.render.animation.Easing;

import java.awt.Color;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static thunder.hack.features.modules.Module.mc;

/**
 * The main menu: a lock screen that opens into a row of circular buttons.
 *
 * <h2>Two states, one screen</h2>
 * <b>Start</b> is the date, a large clock, a quiet hint and the now-playing card. <b>Menu</b> is the circular
 * navigation and Customize. One click moves between them, and the move is the point: the start content rises
 * out of the way while the buttons arrive from the left, one after another.
 *
 * <p>They are two states of one screen rather than two screens because a screen swap cannot cross-fade -
 * Minecraft tears the old one down before the new one draws, so there would be a frame of nothing in the
 * middle. Holding both and animating between them is what makes it continuous.
 *
 * <h2>Layout</h2>
 * Everything is derived from the window size each frame: no coordinate is stored, so the same code centres
 * correctly at 1280x720 and at 3840x2160 and through every GUI scale. The button row shrinks its diameter and
 * its spacing together once the window gets narrow, so the five circles never collide.
 *
 * <p>Carries no branding of any kind - the wallpaper is the identity.
 */
public class MainMenuScreen extends Screen {

    /** false => stay on the vanilla title screen until the game restarts. */
    public static boolean enabled = true;

    private static final Color CLOCK = new Color(0xE9E9EE);
    private static final Color DATE = new Color(0xC9C9D2);
    private static final Color HINT = new Color(0x8E8E99);

    /** Where the start content sits, as a fraction of the window height. */
    private static final float CLOCK_Y = 0.17f;

    private final List<CircleButton> buttons = new ArrayList<>();

    /**
     * The one transition both states share.
     *
     * <p>At 0 the start content is in place; at 1 the menu is. Running it forwards lifts the clock away and
     * brings the buttons in, and running it backwards does exactly the reverse - which is why there is one of
     * them rather than one per direction.
     */
    private final MenuTransition open = new MenuTransition(MenuTransition.Kind.FADE_AND_SLIDE, 620L, 0f,
            // CubicOut: most of the distance early, settling without overshoot. The brief asked for smooth
            // with no bounce, and Spring is the one easing here that bounces.
            Easing.CubicOut);

    private boolean menuOpen;

    private static MainMenuScreen INSTANCE;

    protected MainMenuScreen() {
        super(Text.of("MainMenu"));
        INSTANCE = this;

        buttons.add(new CircleButton(CircleButton.Kind.SINGLEPLAYER, I18n.translate("menu.singleplayer"),
                () -> mc.setScreen(new SelectWorldScreen(this))));
        buttons.add(new CircleButton(CircleButton.Kind.MULTIPLAYER, I18n.translate("menu.multiplayer"),
                () -> mc.setScreen(new MultiplayerScreen(this))));
        buttons.add(new CircleButton(CircleButton.Kind.SETTINGS, I18n.translate("menu.options"),
                () -> mc.setScreen(new OptionsScreen(this, mc.options))));
        buttons.add(new CircleButton(CircleButton.Kind.EXIT, I18n.translate("menu.quit"),
                mc::scheduleStop));
        // Account switcher removed from the menu per request.
    }

    public static MainMenuScreen getInstance() {
        if (INSTANCE == null) INSTANCE = new MainMenuScreen();
        INSTANCE.reset();
        return INSTANCE;
    }

    /** Back to the lock screen, with every animation at its start - never mid-flight from a previous visit. */
    private void reset() {
        menuOpen = false;
        open.snap(false);
        buttons.forEach(CircleButton::reset);
    }

    /* ------------------------------------------------------------------ */
    /* Render                                                              */
    /* ------------------------------------------------------------------ */

    @Override
    public void render(@NotNull DrawContext context, int mouseX, int mouseY, float delta) {
        float w = width, h = height;

        MenuBackdrop.render(context, this, w, h, mouseX, mouseY, 0.22f, delta);

        // Advanced exactly once per frame; every later read uses peek().
        float progress = open.direction(menuOpen).advance();

        drawStart(context, w, h, mouseX, mouseY, 1f - progress);
        drawMenu(context, w, h, mouseX, mouseY, progress);
    }

    /* ------------------------------------------------------------------ */
    /* Start state                                                         */
    /* ------------------------------------------------------------------ */

    /**
     * Date, clock, hint and the music card - the whole block rising as the menu takes over.
     *
     * @param presence 1 fully shown, 0 fully gone
     */
    private void drawStart(DrawContext context, float w, float h, int mouseX, int mouseY, float presence) {
        if (presence <= 0.004f) return;

        // The lift is proportional to the window, so the block leaves the screen at any resolution rather than
        // travelling a fixed number of pixels that is a long way at 720p and nothing at 4K.
        float lift = (1f - presence) * h * 0.22f;

        var matrices = context.getMatrices();
        matrices.pushMatrix();
        matrices.translate(0f, -lift);

        float cx = w / 2f;
        float scale = uiScale(w, h);

        LocalDateTime now = LocalDateTime.now();
        String date = now.format(DateTimeFormatter.ofPattern("EEEE, d MMMM", Locale.ENGLISH));
        String clock = now.format(DateTimeFormatter.ofPattern("HH:mm"));

        /* Date, above the clock and much smaller. */
        FontRenderer dateFont = FontRenderers.sf_medium;
        float dateWidth = dateFont.getStringWidth(date, scale);
        float dateY = h * CLOCK_Y;
        drawScaled(context, dateFont, date, cx - dateWidth * scale * 0.5f, dateY, scale, fade(DATE, presence));

        /* The clock: the biggest thing on the screen. */
        FontRenderer clockFont = FontRenderers.brand != null ? FontRenderers.brand : FontRenderers.sf_bold_big;
        float clockScale = scale * 1.25f;
        float clockWidth = clockFont.getStringWidth(clock, clockScale);
        float clockY = dateY + 20f * scale;
        drawScaled(context, clockFont, clock, cx - clockWidth * clockScale * 0.5f, clockY, clockScale,
                fade(CLOCK, presence));

        /* The now-playing card. */
        MenuMusicPanel.render(context, cx, musicTop(h), presence, mouseX, mouseY);

        matrices.popMatrix();
    }

    private float musicTop(float h) {
        return h * CLOCK_Y + 118f;
    }

    /* ------------------------------------------------------------------ */
    /* Menu state                                                          */
    /* ------------------------------------------------------------------ */

    /**
     * The circular row and Customize.
     *
     * <p>Each button gets its own slice of the timeline through {@link MenuTransition#stagger}, so they arrive
     * left to right and the last one still lands exactly when the transition ends - no straggler finishing
     * after everything else has settled.
     */
    private void drawMenu(DrawContext context, float w, float h, int mouseX, int mouseY, float progress) {
        if (progress <= 0.004f) return;

        float size = buttonSize(w, h);
        float gap = size * 0.52f;
        float span = buttons.size() * size + (buttons.size() - 1) * gap;
        float startX = w / 2f - span / 2f + size / 2f;
        float cy = h / 2f;

        for (int i = 0; i < buttons.size(); i++) {
            float local = MenuTransition.ease(
                    MenuTransition.stagger(progress, i, buttons.size(), 0.45f));
            buttons.get(i).render(context, startX + i * (size + gap), cy, size, mouseX, mouseY, local, progress);
        }

        /* Customize, under the row, arriving last. */
        float customizeProgress = MenuTransition.ease(
                MenuTransition.stagger(progress, buttons.size() - 1, buttons.size(), 0.45f));
        drawCustomize(context, w / 2f, cy + size * 0.95f, mouseX, mouseY, customizeProgress * progress);

        drawNews(context, w, h, MenuTransition.ease(progress));
    }

    /**
     * "Co nowego": the message of the latest release from the GlobalDLC App, on the left of the menu. Nothing
     * is drawn until the app has answered (or if it never does).
     */
    private void drawNews(DrawContext context, float w, float h, float alpha) {
        String notes = thunder.hack.core.manager.client.AppLink.changelog();
        if (notes == null || notes.isBlank() || alpha <= 0.01f) return;
        int rel = thunder.hack.core.manager.client.AppLink.latestRelease();

        float pw = Math.min(230f, w * 0.26f);
        float x = 16f - (1f - alpha) * 40f, y = h * 0.22f;
        List<String> lines = new java.util.ArrayList<>();
        for (String para : notes.split("\n")) {
            if (para.isBlank()) continue;
            String text = para.trim().startsWith("-") || para.trim().startsWith("•") ? para.trim().substring(1).trim() : para.trim();
            StringBuilder line = new StringBuilder("• ");
            for (String word : text.split(" ")) {
                String next = line + (line.length() > 2 ? " " : "") + word;
                if (FontRenderers.sf_medium_mini.getStringWidth(next) > pw - 22 && line.length() > 2) {
                    lines.add(line.toString());
                    line = new StringBuilder("  " + word);
                } else line = new StringBuilder(next);
            }
            lines.add(line.toString());
            if (lines.size() > 16) break;
        }
        float ph = 34f + lines.size() * 10f;
        int a = (int) (alpha * 255);
        java.awt.Color accent = thunder.hack.features.modules.client.HudEditor.getColor(0);
        Render2DEngine.drawRound(context, x, y, pw, ph, 7, new java.awt.Color(14, 15, 22, (int) (alpha * 200)));
        Render2DEngine.drawRound(context, x, y, 3, ph, 1.5f, Render2DEngine.injectAlpha(accent, a));
        FontRenderers.sf_bold.drawString(context, "CO NOWEGO" + (rel > 0 ? "  ·  #" + rel : ""), x + 12, y + 9,
                Render2DEngine.injectAlpha(accent, a).getRGB());
        float ly = y + 26;
        for (String l : lines) {
            FontRenderers.sf_medium_mini.drawString(context, l, x + 12, ly, new java.awt.Color(225, 228, 238, a).getRGB());
            ly += 10;
        }
    }

    private float[] customizeRect = {-1, -1, 0, 0};

    private void drawCustomize(DrawContext context, float cx, float y, int mouseX, int mouseY, float alpha) {
        if (alpha <= 0.01f) {
            customizeRect = new float[]{-1, -1, 0, 0};
            return;
        }

        String label = "Customize";
        float textWidth = FontRenderers.sf_medium.getStringWidth(label);
        float boxW = textWidth + 30f, boxH = 22f;
        float x = cx - boxW / 2f;

        boolean hovered = Render2DEngine.isHovered(mouseX, mouseY, x, y, boxW, boxH);
        customizeRect = new float[]{x, y, boxW, boxH};

        Render2DEngine.drawBlurredShadow(context, x, y, boxW, boxH, 8,
                new Color(0, 0, 0, Math.round(70 * alpha)));
        Render2DEngine.drawRound(context, x, y, boxW, boxH, boxH / 2f,
                new Color(28, 28, 34, Math.round((hovered ? 170 : 120) * alpha)));

        FontRenderers.sf_medium.drawString(context, label, cx - textWidth / 2f, y + 6f,
                new Color(255, 255, 255, Math.round((hovered ? 255 : 220) * alpha)).getRGB());
    }

    /* ------------------------------------------------------------------ */
    /* Responsive sizing                                                   */
    /* ------------------------------------------------------------------ */

    /**
     * How large the circles may be.
     *
     * <p>Driven by the window width divided between the five buttons and their gaps, then capped - so a narrow
     * window shrinks the row instead of letting it run off the sides, and a very wide one does not grow the
     * buttons into dinner plates.
     */
    private float buttonSize(float w, float h) {
        float budget = w * 0.62f;
        float perButton = budget / (buttons.size() + (buttons.size() - 1) * 0.52f);
        return Math.clamp(perButton, 26f, Math.min(52f, h * 0.11f));
    }

    /** A gentle text scale so the clock stays proportionate on very small and very large windows. */
    private static float uiScale(float w, float h) {
        return Math.clamp(Math.min(w / 640f, h / 360f), 0.82f, 1.6f);
    }

    /**
     * Draws text at an arbitrary scale.
     *
     * <p>Translating to the anchor first and back after is what keeps the text where it was asked for
     * instead of flying away from the origin. The scale is also handed to the renderer: a glyph is a
     * bitmap, so scaling the matrix alone just magnifies its texels - which is why the clock used to
     * look pixelated the moment the window (or the GUI scale) got big.
     */
    private static void drawScaled(DrawContext context, FontRenderer font, String text,
                                   float x, float y, float scale, Color color) {
        var matrices = context.getMatrices();
        matrices.pushMatrix();
        matrices.translate(x, y);
        matrices.scale(scale, scale);
        font.drawString(context, text, 0, 0, color.getRGB(), scale);
        matrices.popMatrix();
    }

    private static Color fade(Color base, float alpha) {
        return new Color(base.getRed(), base.getGreen(), base.getBlue(),
                Math.round(255 * Math.clamp(alpha, 0f, 1f)));
    }

    /* ------------------------------------------------------------------ */
    /* Input                                                               */
    /* ------------------------------------------------------------------ */

    @Override
    public boolean mouseClicked(net.minecraft.client.gui.Click click, boolean doubled) {
        double mx = click.x(), my = click.y();

        if (!menuOpen) {
            // The card's own controls come first: pressing pause must not also open the menu.
            if (MenuMusicPanel.click(mx, my)) return true;
            if (MenuMusicPanel.covers(mx, my, width / 2f, musicTop(height))) return true;

            menuOpen = true;
            return true;
        }

        for (CircleButton button : buttons)
            if (button.click(mx, my)) return true;

        if (Render2DEngine.isHovered(mx, my, customizeRect[0], customizeRect[1], customizeRect[2], customizeRect[3])) {
            mc.setScreen(new WallpaperGallery(this));
            return true;
        }

        // Clicking the empty space goes back to the lock screen, which is the only other thing a click there
        // could reasonably mean.
        menuOpen = false;
        return true;
    }

    @Override
    public boolean keyPressed(net.minecraft.client.input.KeyInput input) {
        if (input.key() == GLFW.GLFW_KEY_ESCAPE) {
            // Escape unwinds one step rather than doing nothing: menu back to the lock screen, and from the
            // lock screen nothing, because there is nowhere above the main menu to go.
            if (menuOpen) menuOpen = false;
            return true;
        }

        // Any other key opens the menu from the lock screen - the same affordance as a click.
        if (!menuOpen) {
            menuOpen = true;
            return true;
        }

        return super.keyPressed(input);
    }

    /** Leaves the custom menu for the vanilla one until the game is restarted. */
    public void backToVanilla() {
        enabled = false;
        mc.setScreen(new TitleScreen());
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }
}
