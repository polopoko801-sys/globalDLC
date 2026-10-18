package thunder.hack.gui.clickui;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.resource.language.I18n;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;
import thunder.hack.core.Managers;
import thunder.hack.core.manager.client.ModuleManager;
import thunder.hack.features.modules.Module;
import thunder.hack.features.modules.client.HudEditor;
import thunder.hack.gui.clickui.impl.*;
import thunder.hack.gui.font.FontRenderer;
import thunder.hack.gui.font.FontRenderers;
import thunder.hack.utility.ColorPalette;
import thunder.hack.utility.render.Render2DEngine;
import thunder.hack.utility.render.ScreenCapture;
import thunder.hack.utility.render.animation.AnimationUtility;
import thunder.hack.utility.render.animation.EaseOutBack;

import java.awt.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static thunder.hack.features.modules.Module.mc;

/**
 * Gombal ClickGUI: one centred frosted-glass window - category sidebar with icon chips and a sliding
 * selection pill, a 2/3-column grid of module cards (icon, name, description, toggle switch, bind chip), an
 * always-visible search pill, and a detached glass settings panel on the right.
 *
 * <p>Glass = this frame's {@link ScreenCapture} blurred inside each panel ({@link Render2DEngine#drawBackdropBlur})
 * with a dark veil on top; toggled by ClickGui -> GlassBlur. Icons are white+alpha PNGs in
 * {@code textures/gui/icons}, tinted per state, so restyling an icon is swapping a file.
 */
public class ClickGUI extends Screen {

    // ---- kept for external API compatibility ----
    public static boolean anyHovered;
    public static boolean close = false;
    public static String currentDescription = "";
    public EaseOutBack imageAnimation = new EaseOutBack(6);

    // ---- palette ----
    private static final Color ACCENT_DEFAULT = new Color(139, 100, 246);
    private static final Color TEXT = new Color(238, 239, 245);
    private static final Color TEXT_DIM = new Color(172, 175, 192);
    private static final Color TEXT_MUTED = new Color(98, 101, 118);
    private static final Color SOLID_PANEL = new Color(17, 18, 25, 242);
    private static final Color CARD = new Color(0, 0, 0, 46);
    private static final Color CARD_HOVER = new Color(255, 255, 255, 18);
    private static final Color HAIRLINE = new Color(255, 255, 255, 20);
    private static final Color SWITCH_OFF = new Color(255, 255, 255, 34);

    private static final float SIDEBAR_W = 136f;
    private static final float HEADER_H = 46f;
    private static final float CARD_H = 54f;
    private static final float ROW_H = 20f;

    private static Color accent() {
        Color c = ColorPalette.getActive();
        return c == null ? ACCENT_DEFAULT : c;
    }

    private static Color accentText() {
        Color c = accent();
        return new Color(
                Math.min(255, (int) (c.getRed() * 0.45f + 140)),
                Math.min(255, (int) (c.getGreen() * 0.45f + 140)),
                Math.min(255, (int) (c.getBlue() * 0.45f + 140)));
    }

    // ---- icons ----
    private static final Map<String, Identifier> ICONS = new HashMap<>();

    private static Identifier icon(String name) {
        return ICONS.computeIfAbsent(name, n -> Identifier.of("gombal", "textures/gui/icons/" + n + ".png"));
    }

    private static void drawIcon(DrawContext context, String name, float x, float y, float size, Color color) {
        Render2DEngine.renderTexture(context, icon(name), x, y, size, size, 0, 0, 64, 64, 64, 64, color);
    }

    private static String categoryIcon(Module.Category cat) {
        if (cat == null || Module.Category.isCustomCategory(cat)) return "star";
        return switch (cat.getName().toLowerCase(Locale.ROOT)) {
            case "combat" -> "combat";
            case "render" -> "render";
            case "movement" -> "movement";
            case "player" -> "player";
            case "client" -> "client";
            case "misc" -> "misc";
            case "hud" -> "hud";
            default -> "star";
        };
    }

    // ---- state ----
    private Module.Category selectedCategory = Module.Category.COMBAT;
    private Module settingsModule;
    private Module bindingModule; // middle-click a card to bind: next key/mouse-button sets the bind
    /** Set when a key press was used for a bind, so its character is not typed into the search too. */
    private boolean swallowChar;
    private final Map<Module, List<AbstractElement>> elementCache = new HashMap<>();

    private final Map<Module, Float> cardHoverAnim = new HashMap<>();
    private final Map<Module, Float> cardEnableAnim = new HashMap<>();
    private final Map<String, Float> rowHoverAnim = new HashMap<>();

    private float openAnim, settingsAnim;
    private float catAnim = 1f;
    private float pillY = -1f;
    private float searchFocusAnim;
    private boolean closing;
    private static long arrowCursor = 0L;
    private float scrollGrid, scrollSettings;
    private float scrollGridTarget, scrollSettingsTarget;

    private boolean searchActive;
    private String searchText = "";
    private String hoveredDesc;

    // geometry rebuilt every frame, consumed by input handlers
    private final List<Row> sidebarRows = new ArrayList<>();
    private final List<float[]> paletteRects = new ArrayList<>();
    private final List<Card> cards = new ArrayList<>();
    private float searchX, searchY, searchW, searchH;
    private float spX, spY, spW, spH, spCloseX, spCloseY, spSwitchX, spSwitchY;
    private float gridTop, gridBottom;

    private record Row(Module.Category cat, String action, String label, float x, float y, float w, float h) {}

    private static final class Card {
        Module module;
        float x, y, w, h;
    }

    public ClickGUI() {
        super(Text.of("ClickGUI"));
        setInstance();
    }

    private static ClickGUI INSTANCE = new ClickGUI();

    public static ClickGUI getInstance() {
        if (INSTANCE == null) INSTANCE = new ClickGUI();
        return INSTANCE;
    }

    public static ClickGUI getClickGui() {
        ClickGUI gui = getInstance();
        gui.openAnim = 0f;
        gui.settingsModule = null;
        gui.bindingModule = null;
        gui.settingsAnim = 0f;
        gui.searchActive = false;
        gui.searchText = "";
        gui.closing = false;
        return gui;
    }

    private void setInstance() {
        INSTANCE = this;
    }

    @Override
    protected void init() {
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    // ------------------------------------------------------------------ glass

    private static boolean glassOn() {
        return ModuleManager.clickGui == null || ModuleManager.clickGui.glass.getValue();
    }

    /**
     * Frosted panel: blurred backdrop + dark veil + top hairline. Falls back to a solid panel when glass is
     * off or the backend gave us no snapshot this frame.
     */
    private static void glassPanel(DrawContext context, float x, float y, float w, float h, float r, boolean shadow) {
        if (shadow)
            Render2DEngine.drawBlurredShadow(context, x, y, w, h, 16, new Color(0, 0, 0, 150));

        boolean blurred = glassOn() && Render2DEngine.drawBackdropBlur(context, x, y, w, h, r,
                ModuleManager.clickGui.glassBlur.getValue());
        if (blurred) {
            // smoked glass: one veil, a touch lighter at the top where the light hits, darker at the
            // bottom so text in the lower half stays readable over a bright world
            int d = ModuleManager.clickGui.glassDarkness.getValue();
            Color top = new Color(16, 17, 25, Math.max(0, d - 30));
            Color bottom = new Color(10, 11, 16, Math.min(255, d + 15));
            // renderRoundedQuad2 corner order: top-right, bottom-right, top-left, bottom-left
            Render2DEngine.drawGradientRound(context, x, y, w, h, r, top, bottom, top, bottom);
        } else {
            Render2DEngine.drawRound(context, x, y, w, h, r, SOLID_PANEL);
        }

        // crisp rim on all four sides - a sharp edge is what makes a blurred pane read as glass
        float ins = Math.min(r, w / 2f);
        float vIns = Math.min(r, h / 2f);
        Render2DEngine.drawRect(context, x + ins, y, w - ins * 2f, 0.7f, new Color(255, 255, 255, blurred ? 70 : 30));
        Render2DEngine.drawRect(context, x + ins, y + h - 0.7f, w - ins * 2f, 0.7f, new Color(255, 255, 255, 14));
        Render2DEngine.drawRect(context, x, y + vIns, 0.7f, h - vIns * 2f, new Color(255, 255, 255, 22));
        Render2DEngine.drawRect(context, x + w - 0.7f, y + vIns, 0.7f, h - vIns * 2f, new Color(255, 255, 255, 22));
    }

    // ------------------------------------------------------------------ render

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        anyHovered = false;
        hoveredDesc = null;
        // fresh backdrop snapshot for the glass panels (the GUI itself is not in it - it draws later).
        // 1.21.11 already blurs+darkens the world behind any screen once per frame, so no applyBlur here.
        ScreenCapture.captureFrame();

        float sw = mc.getWindow().getScaledWidth();
        float sh = mc.getWindow().getScaledHeight();

        openAnim = AnimationUtility.fast(openAnim, closing ? 0f : 1f, 11f);
        if (closing && openAnim < 0.03f) {
            reallyClose();
            return;
        }
        float ease = easeOutCubic(openAnim);

        // the settings panel docks to the right; when the window + panel do not fit, the window slides left and
        // narrows instead of the panel covering the cards
        // a timed tween (not an exponential chase): constant progress over SETTINGS_SLIDE seconds, shaped with
        // ease-in-out, so the window starts moving gently and settles softly instead of jumping sideways
        float target = settingsModule != null ? 1f : 0f;
        float step = AnimationUtility.deltaTime() / SETTINGS_SLIDE;
        settingsAnim = settingsAnim < target ? Math.min(target, settingsAnim + step) : Math.max(target, settingsAnim - step);
        float sa = easeInOutCubic(settingsAnim);
        float fullW = Math.min(sw - 24f, 620f);
        float dockedW = Math.max(380f, Math.min(fullW, sw - 24f - 180f));
        float panelW = fullW + (dockedW - fullW) * sa;
        float panelH = Math.min(sh - 70f, 360f);
        float groupW = panelW + 180f * sa;
        float px = Math.max(12f, (sw - groupW) / 2f);
        float py = (sh - panelH - 40f) / 2f + (1f - ease) * 12f;

        float scale = 0.9f + 0.1f * ease;
        var matrix = context.getMatrices();
        matrix.pushMatrix();
        matrix.translate(sw / 2f, sh / 2f);
        matrix.scale(scale, scale);
        matrix.translate(-sw / 2f, -sh / 2f);

        if (Render2DEngine.isHovered(mouseX, mouseY, px, py, panelW, panelH))
            anyHovered = true;

        glassPanel(context, px, py, panelW, panelH, 12f, true);

        renderSidebar(context, mouseX, mouseY, px, py, panelH);
        renderContent(context, mouseX, mouseY, px, py, panelW, panelH);
        renderPalette(context, mouseX, mouseY, px, py, panelW, panelH);
        renderSettings(context, mouseX, mouseY, px, py, panelW, panelH, delta);

        matrix.popMatrix();

        if (!anyHovered && GLFW.glfwGetPlatform() != GLFW.GLFW_PLATFORM_WAYLAND) {
            if (arrowCursor == 0L) arrowCursor = GLFW.glfwCreateStandardCursor(GLFW.GLFW_ARROW_CURSOR);
            GLFW.glfwSetCursor(mc.getWindow().getHandle(), arrowCursor);
        }
    }

    // ------------------------------------------------------------------ sidebar

    private void renderSidebar(DrawContext context, int mouseX, int mouseY, float px, float py, float panelH) {
        // a slightly lighter strip, rounded on the left only (clip a wider rounded rect)
        Render2DEngine.addWindow(context, px, py, px + SIDEBAR_W, py + panelH, 1.0);
        Render2DEngine.drawRound(context, px, py, SIDEBAR_W + 14f, panelH, 12f, new Color(255, 255, 255, 7));
        Render2DEngine.popWindow();
        Render2DEngine.drawRect(context, px + SIDEBAR_W, py + 12f, 0.6f, panelH - 24f, HAIRLINE);

        // brand: animated glass G logo + name
        Color acc = accent();
        thunder.hack.utility.render.GombalLogo.draw(context, px + 9f, py + 9f, 24f, 1f);
        FontRenderers.monsterrat.drawString(context, "GlobalDLC", px + 36f, py + 15f, TEXT.getRGB());

        drawLicence(context, px, py, panelH);

        // layout first, so the selection pill can be drawn under the rows it slides between
        sidebarRows.clear();
        float rowX = px + 8f;
        float rowW = SIDEBAR_W - 16f;
        float ry = py + 44f;
        float labelFeaturesY = ry;
        ry += 12f;
        List<Module.Category> cats = Managers.MODULE.getCategories();
        float activeY = -1f;
        for (Module.Category cat : cats) {
            sidebarRows.add(new Row(cat, null, cat.getName(), rowX, ry, rowW, ROW_H));
            if (cat == selectedCategory) activeY = ry;
            ry += ROW_H + 2f;
        }
        ry += 6f;
        float labelOtherY = ry;
        ry += 12f;
        sidebarRows.add(new Row(null, "configs", "Configs", rowX, ry, rowW, ROW_H));
        ry += ROW_H + 2f;
        sidebarRows.add(new Row(null, "friends", "Friends", rowX, ry, rowW, ROW_H));
        ry += ROW_H + 2f;
        sidebarRows.add(new Row(null, "hud", "Edit HUD", rowX, ry, rowW, ROW_H));

        FontRenderers.sf_medium_mini.drawString(context, "FEATURES", rowX + 4f, labelFeaturesY, TEXT_MUTED.getRGB());
        FontRenderers.sf_medium_mini.drawString(context, "OTHER", rowX + 4f, labelOtherY, TEXT_MUTED.getRGB());

        // sliding pill
        boolean searching = searchActive && !searchText.isEmpty();
        if (activeY >= 0f && !searching) {
            pillY = pillY < 0f ? activeY : AnimationUtility.fast(pillY, activeY, 14f);
            Render2DEngine.drawBlurredShadow(context, rowX, pillY, rowW, ROW_H, 8, Render2DEngine.injectAlpha(acc, 40));
            Render2DEngine.drawRound(context, rowX, pillY, rowW, ROW_H, 6f, Render2DEngine.injectAlpha(acc, 52));
            Render2DEngine.drawRound(context, rowX, pillY + 5f, 2.5f, ROW_H - 10f, 1.25f, acc);
        }

        for (Row r : sidebarRows) drawSidebarRow(context, mouseX, mouseY, r, searching);
    }

    private void drawSidebarRow(DrawContext context, int mouseX, int mouseY, Row r, boolean searching) {
        boolean active = r.cat() != null && r.cat() == selectedCategory && !searching;
        boolean hovered = Render2DEngine.isHovered(mouseX, mouseY, r.x(), r.y(), r.w(), r.h());

        float hv = AnimationUtility.fast(rowHoverAnim.getOrDefault(r.label(), 0f), hovered ? 1f : 0f, 16f);
        rowHoverAnim.put(r.label(), hv);
        float act = AnimationUtility.fast(rowHoverAnim.getOrDefault(r.label() + "#a", 0f), active ? 1f : 0f, 14f);
        rowHoverAnim.put(r.label() + "#a", act);

        if (hv > 0.01f && act < 0.99f)
            Render2DEngine.drawRound(context, r.x(), r.y(), r.w(), r.h(), 6f, new Color(255, 255, 255, (int) (14 * hv * (1f - act))));

        // icon chip
        float chip = 14f;
        float cx = r.x() + 6f, cy = r.y() + (r.h() - chip) / 2f;
        Color chipBg = Render2DEngine.interpolateColorC(new Color(255, 255, 255, 12), Render2DEngine.injectAlpha(accent(), 90), act);
        Render2DEngine.drawRound(context, cx, cy, chip, chip, 4.5f, chipBg);
        String iconName = r.cat() != null ? categoryIcon(r.cat()) : switch (r.action()) {
            case "configs" -> "configs";
            case "friends" -> "friends";
            default -> "edithud";
        };
        Color iconCol = Render2DEngine.interpolateColorC(Render2DEngine.interpolateColorC(TEXT_DIM, TEXT, hv), Color.WHITE, act);
        drawIcon(context, iconName, cx + 2.5f, cy + 2.5f, chip - 5f, iconCol);

        Color textColor = Render2DEngine.interpolateColorC(Render2DEngine.interpolateColorC(TEXT_DIM, TEXT, hv), TEXT, act);
        FontRenderers.sf_medium.drawString(context, r.label(), r.x() + 26f, r.y() + 7f, textColor.getRGB());

    }

    /** The licence, bottom-left of the sidebar (rank over UID, optional avatar). */
    private void drawLicence(DrawContext context, float px, float py, float panelH) {
        var licence = thunder.hack.core.manager.client.ClientUid.licence();
        if (licence == null) return;

        float x = px + 12f;
        float bottom = py + panelH - 10f;

        var avatar = thunder.hack.core.manager.client.ClientUid.avatar();
        if (avatar != null) {
            float size = 18f;
            Render2DEngine.drawRound(context, x, bottom - 14f, size, size, 4f, new Color(0x33FFFFFF, true));
            Render2DEngine.renderTexture(context, avatar, x, bottom - 14f, size, size, 0, 0, size, size, size, size);
            x += size + 6f;
        }

        if (!licence.rank().isEmpty()) {
            String label = licence.banned() ? "BANNED" : licence.rank().toUpperCase();
            FontRenderers.monsterrat.drawString(context, label, x, bottom - 12f, rankColour(licence).getRGB());
        }

        String shown = thunder.hack.core.manager.client.ClientUid.shortId();
        FontRenderers.sf_medium_mini.drawString(context, "UID " + (shown == null ? licence.uid() : shown),
                x, bottom, TEXT_DIM.getRGB());
    }

    private static Color rankColour(thunder.hack.core.manager.client.ClientUid.Licence licence) {
        if (licence.banned()) return new Color(0xFF6B6B);
        return switch (licence.rank().toUpperCase()) {
            case "OWNER" -> new Color(0xFFC65A);
            case "SELLER" -> new Color(0x4ADE80);
            case "DEV" -> new Color(0x7C3AED);
            case "TESTER" -> new Color(0xFF9646);
            default -> HudEditor.getColor(0);
        };
    }

    // ------------------------------------------------------------------ content

    private void renderContent(DrawContext context, int mouseX, int mouseY, float px, float py, float panelW, float panelH) {
        float cx = px + SIDEBAR_W;
        float cw = panelW - SIDEBAR_W;
        List<Module> mods = filteredModules();
        boolean searching = searchActive && !searchText.isEmpty();

        // ---- header: icon + title + subtitle ----
        String title = searching ? "Search" : selectedCategory.getName();
        String headIcon = searching ? "search" : categoryIcon(selectedCategory);
        Render2DEngine.drawRound(context, cx + 14f, py + 12f, 22f, 22f, 7f, Render2DEngine.injectAlpha(accent(), 60));
        drawIcon(context, headIcon, cx + 18f, py + 16f, 14f, accentText());
        FontRenderers.monsterrat.drawString(context, title, cx + 44f, py + 13f, TEXT.getRGB());

        String subtitle = hoveredDescPrev != null ? hoveredDescPrev
                : "LMB toggle  •  RMB settings  •  MMB bind  •  type to search";

        // ---- search pill (always visible; typing anywhere also starts a search) ----
        searchW = 132f;
        searchH = 20f;
        searchX = cx + cw - searchW - 14f;
        searchY = py + 13f;
        searchFocusAnim = AnimationUtility.fast(searchFocusAnim, searchActive ? 1f : 0f, 14f);
        boolean searchHov = Render2DEngine.isHovered(mouseX, mouseY, searchX, searchY, searchW, searchH);
        if (searchFocusAnim > 0.01f)
            Render2DEngine.drawRound(context, searchX - 1f, searchY - 1f, searchW + 2f, searchH + 2f, 7f,
                    Render2DEngine.injectAlpha(accent(), (int) (120 * searchFocusAnim)));
        Render2DEngine.drawRound(context, searchX, searchY, searchW, searchH, 6.5f,
                new Color(20, 21, 28, searchHov || searchActive ? 235 : 200));
        drawIcon(context, "search", searchX + 7f, searchY + 5.5f, 9f, searchActive ? accentText() : TEXT_DIM);
        if (searchText.isEmpty()) {
            FontRenderers.sf_medium_mini.drawString(context, searchActive ? "Type to search..." : "Search modules",
                    searchX + 21f, searchY + 7f, TEXT_MUTED.getRGB());
        } else {
            String shown = trimLeft(FontRenderers.sf_medium_mini, searchText, searchW - 30f);
            FontRenderers.sf_medium_mini.drawString(context, shown, searchX + 21f, searchY + 7f, TEXT.getRGB());
            if (searchActive && (System.currentTimeMillis() / 500) % 2 == 0) {
                float tw = FontRenderers.sf_medium_mini.getStringWidth(shown);
                Render2DEngine.drawRect(context, searchX + 22f + tw, searchY + 5f, 0.8f, 10f, accentText());
            }
        }

        float subMaxW = searchX - (cx + 44f) - 10f;
        FontRenderers.sf_medium_mini.drawString(context, trim(FontRenderers.sf_medium_mini, subtitle, subMaxW),
                cx + 44f, py + 27f, TEXT_DIM.getRGB());

        Render2DEngine.drawRect(context, cx + 12f, py + HEADER_H - 1f, cw - 24f, 0.6f, HAIRLINE);

        // ---- grid ----
        float gx = cx + 12f;
        float gy = py + HEADER_H + 6f;
        float gw = cw - 24f;
        float gap = 8f;
        int cols = (ModuleManager.clickGui.threeColumns.getValue() && gw >= 430f) ? 3 : 2;
        float cardW = (gw - gap * (cols - 1)) / cols;
        float viewH = panelH - HEADER_H - 12f;
        gridTop = py + HEADER_H + 2f;
        gridBottom = py + panelH - 6f;

        int rows = (mods.size() + cols - 1) / cols;
        float totalH = rows * (CARD_H + gap);
        float maxScroll = Math.max(0f, totalH - viewH);
        scrollGridTarget = Math.max(-maxScroll, Math.min(0f, scrollGridTarget));
        scrollGrid = AnimationUtility.fast(scrollGrid, scrollGridTarget, 12f);

        cards.clear();
        catAnim = AnimationUtility.fast(catAnim, 1f, 6f);
        float slide = (1f - easeOutCubic(catAnim)) * 22f;
        Render2DEngine.addWindow(context, cx, gridTop, cx + cw, gridBottom, 1.0);
        for (int i = 0; i < mods.size(); i++) {
            Module m = mods.get(i);
            int col = i % cols;
            int row = i / cols;
            float cxp = gx + col * (cardW + gap);
            float cyp = gy + row * (CARD_H + gap) + scrollGrid;
            drawCard(context, mouseX, mouseY, m, cxp, cyp + slide, cardW, CARD_H, slide, searching);
        }
        Render2DEngine.popWindow();

        // scrollbar
        if (maxScroll > 0f) {
            float trackH = viewH - 8f;
            float thumbH = Math.max(18f, trackH * (viewH / totalH));
            float thumbY = gy + (trackH - thumbH) * (-scrollGrid / maxScroll);
            Render2DEngine.drawRound(context, cx + cw - 6f, thumbY, 2.5f, thumbH, 1.25f, new Color(255, 255, 255, 40));
        }

        if (mods.isEmpty()) {
            float mx = cx + cw / 2f, my = gy + viewH / 2f - 20f;
            drawIcon(context, "search", mx - 12f, my, 24f, TEXT_MUTED);
            FontRenderers.sf_medium.drawCenteredString(context, "Nothing found for \"" + searchText + "\"", mx, my + 32f, TEXT_DIM.getRGB());
        }

        hoveredDescPrev = hoveredDesc;
    }

    /** Last frame's hovered description, so the header can show it (cards are drawn after the header). */
    private String hoveredDescPrev;

    private void drawCard(DrawContext context, int mouseX, int mouseY, Module m, float x, float y, float w, float h, float slide, boolean searching) {
        if (y + h < gridTop || y > gridBottom) return; // text is not scissor-clipped, cull instead
        float my = mouseY; // cards are laid out in already-slid coordinates
        boolean hovered = Render2DEngine.isHovered(mouseX, my, x, y, w, h) && mouseY >= gridTop && mouseY <= gridBottom;
        boolean on = m.isEnabled();

        float hv = AnimationUtility.fast(cardHoverAnim.getOrDefault(m, 0f), hovered ? 1f : 0f, 16f);
        cardHoverAnim.put(m, hv);
        float en = AnimationUtility.fast(cardEnableAnim.getOrDefault(m, on ? 1f : 0f), on ? 1f : 0f, 9f);
        cardEnableAnim.put(m, en);
        Color acc = accent();

        // body: soft white film, brighter on hover; enabled cards get an accent wash from the left
        if (en > 0.01f)
            Render2DEngine.drawBlurredShadow(context, x, y, w, h, 8, Render2DEngine.injectAlpha(acc, (int) (38 * en)));
        Render2DEngine.drawRound(context, x, y, w, h, 8f, Render2DEngine.interpolateColorC(CARD, CARD_HOVER, hv));
        if (en > 0.01f) {
            Render2DEngine.drawGradientRound(context, x, y, w, h, 8f,
                    Render2DEngine.injectAlpha(acc, (int) (14 * en)), Render2DEngine.injectAlpha(acc, (int) (14 * en)),
                    Render2DEngine.injectAlpha(acc, (int) (70 * en)), Render2DEngine.injectAlpha(acc, (int) (70 * en)));
            Render2DEngine.drawRect(context, x + 8f, y, w - 16f, 0.6f, Render2DEngine.injectAlpha(accentText(), (int) (120 * en)));
        } else {
            Render2DEngine.drawRect(context, x + 8f, y, w - 16f, 0.6f, new Color(255, 255, 255, (int) (18 + 22 * hv)));
        }

        // icon chip (category icon; in search results it tells you where the module lives)
        float chip = 20f;
        float ix = x + 9f, iy = y + 9f;
        Render2DEngine.drawRound(context, ix, iy, chip, chip, 6f,
                Render2DEngine.interpolateColorC(new Color(255, 255, 255, 16), Render2DEngine.injectAlpha(acc, 150), en));
        drawIcon(context, categoryIcon(m.getCategory()), ix + 4.5f, iy + 4.5f, chip - 9f,
                Render2DEngine.interpolateColorC(TEXT_DIM, Color.WHITE, Math.max(en, hv * 0.6f)));

        // toggle switch (top-right)
        float swW = 22f, swH = 12f;
        float swX = x + w - swW - 9f, swY = y + 13f;
        Render2DEngine.drawRound(context, swX, swY, swW, swH, swH / 2f, Render2DEngine.interpolateColorC(SWITCH_OFF, acc, en));
        float knob = swH - 4f;
        Render2DEngine.drawRound(context, swX + 2f + (swW - knob - 4f) * en, swY + 2f, knob, knob, knob / 2f, Color.WHITE);

        // settings gear appears on hover, left of the switch
        if (hv > 0.02f && hasSettings(m))
            drawIcon(context, "settings", swX - 15f, swY + 1f, 10f, Render2DEngine.injectAlpha(TEXT_DIM, (int) (255 * hv)));

        // name
        float nameMax = (swX - 18f) - (ix + chip + 7f);
        Color nameColor = Render2DEngine.interpolateColorC(TEXT, Color.WHITE, en);
        FontRenderers.sf_medium.drawString(context, trim(FontRenderers.sf_medium, m.getName(), nameMax), ix + chip + 7f, y + 12f, nameColor.getRGB());
        if (searching) {
            FontRenderers.sf_medium_mini.drawString(context, m.getCategory().getName(), ix + chip + 7f, y + 22f, TEXT_MUTED.getRGB());
        }

        // description
        String desc = trim(FontRenderers.sf_medium_mini, I18n.translate(m.getDescription()), w - 18f - (hasBind(m) ? 34f : 0f));
        FontRenderers.sf_medium_mini.drawString(context, desc, x + 9f, y + h - 15f, TEXT_DIM.getRGB());

        // bind chip (bottom-right)
        String bindStr = bindingModule == m ? "..." : shortBind(m.getBind().getBind());
        if (!bindStr.isEmpty()) {
            float bw = FontRenderers.sf_medium_mini.getStringWidth(bindStr) + 8f;
            float bx = x + w - bw - 8f, by = y + h - 18f;
            Render2DEngine.drawRound(context, bx, by, bw, 11f, 3.5f,
                    bindingModule == m ? Render2DEngine.injectAlpha(acc, 140) : new Color(255, 255, 255, 18));
            FontRenderers.sf_medium_mini.drawString(context, bindStr, bx + 4f, by + 3f,
                    (bindingModule == m ? Color.WHITE : TEXT_DIM).getRGB());
        }

        if (hovered) hoveredDesc = I18n.translate(m.getDescription());

        Card c = new Card();
        c.module = m;
        c.x = x;
        c.y = y;
        c.w = w;
        c.h = h;
        cards.add(c);
    }

    private boolean hasBind(Module m) {
        return bindingModule == m || !shortBind(m.getBind().getBind()).isEmpty();
    }

    private boolean hasSettings(Module m) {
        for (AbstractElement el : getElements(m)) if (el.isVisible()) return true;
        return false;
    }

    // ------------------------------------------------------------------ palette

    private void renderPalette(DrawContext context, int mouseX, int mouseY, float px, float py, float panelW, float panelH) {
        paletteRects.clear();

        int n = ColorPalette.SWATCHES.size();
        float dot = 15f, gap = 7f, barPadX = 11f, barH = 27f;
        float rowW = n * dot + n * gap + 24f;
        float barW = rowW + barPadX * 2f;
        float barX = px + (panelW - barW) / 2f;
        float barY = py + panelH + 9f;

        if (Render2DEngine.isHovered(mouseX, mouseY, barX, barY, barW, barH))
            anyHovered = true;

        glassPanel(context, barX, barY, barW, barH, 9f, true);

        Color active = ColorPalette.getActive();
        float x = barX + barPadX;
        float y = barY + (barH - dot) / 2f;

        for (int i = 0; i < n; i++) {
            Color c = ColorPalette.SWATCHES.get(i);
            boolean hovered = Render2DEngine.isHovered(mouseX, mouseY, x, y, dot, dot);
            boolean selected = active != null && active.getRGB() == c.getRGB();

            float grow = hovered ? 1.5f : 0f;
            if (selected || hovered)
                Render2DEngine.drawBlurredShadow(context, x - 2f, y - 2f, dot + 4f, dot + 4f, 8,
                        Render2DEngine.injectAlpha(c, selected ? 150 : 90));
            Render2DEngine.drawRound(context, x - grow, y - grow, dot + grow * 2f, dot + grow * 2f, (dot + grow * 2f) / 2f, c);
            if (selected)
                Render2DEngine.drawRound(context, x + 4.5f, y + 4.5f, dot - 9f, dot - 9f, (dot - 9f) / 2f, Color.WHITE);

            paletteRects.add(new float[]{x, y, dot, dot, i});
            x += dot + gap;
        }

        float rw = 24f;
        boolean resetHov = Render2DEngine.isHovered(mouseX, mouseY, x, y, rw, dot);
        Render2DEngine.drawRound(context, x, y, rw, dot, 5f, resetHov ? CARD_HOVER : CARD);
        FontRenderers.sf_medium_mini.drawCenteredString(context, "RESET", x + rw / 2f, y + dot / 2f - 3f,
                (resetHov ? TEXT : TEXT_DIM).getRGB());
        paletteRects.add(new float[]{x, y, rw, dot, -1});
    }

    // ------------------------------------------------------------------ settings panel

    private void renderSettings(DrawContext context, int mouseX, int mouseY, float px, float py, float panelW, float panelH, float delta) {
        if (settingsAnim < 0.01f || settingsModule == null) return;

        float sw = mc.getWindow().getScaledWidth();
        spW = 172f;
        List<AbstractElement> els = getElements(settingsModule);
        float contentTotal = 0f;
        for (AbstractElement el : els) if (el.isVisible()) contentTotal += elementHeight(el);
        float headH = 34f;
        spH = Math.max(60f, Math.min(panelH, headH + contentTotal + 8f));
        spY = py;
        float targetX = px + panelW + 8f;
        float animatedX = targetX - (1f - easeInOutCubic(settingsAnim)) * 26f;
        spX = Math.min(animatedX, sw - spW - 4f);

        if (Render2DEngine.isHovered(mouseX, mouseY, spX, spY, spW, spH))
            anyHovered = true;

        glassPanel(context, spX, spY, spW, spH, 10f, true);

        // header: icon chip, name, enable switch, close
        Color acc = accent();
        Render2DEngine.drawRound(context, spX + 9f, spY + 9f, 16f, 16f, 5f, Render2DEngine.injectAlpha(acc, 90));
        drawIcon(context, categoryIcon(settingsModule.getCategory()), spX + 12f, spY + 12f, 10f, Color.WHITE);

        spCloseX = spX + spW - 17f;
        spCloseY = spY + 12f;
        spSwitchX = spCloseX - 28f;
        spSwitchY = spY + 11f;

        String title = trim(FontRenderers.sf_medium, settingsModule.getName(), spSwitchX - (spX + 31f) - 4f);
        FontRenderers.sf_medium.drawString(context, title, spX + 31f, spY + 14f, TEXT.getRGB());

        float en = cardEnableAnim.getOrDefault(settingsModule, settingsModule.isEnabled() ? 1f : 0f);
        Render2DEngine.drawRound(context, spSwitchX, spSwitchY, 22f, 12f, 6f, Render2DEngine.interpolateColorC(SWITCH_OFF, acc, en));
        Render2DEngine.drawRound(context, spSwitchX + 2f + 10f * en, spSwitchY + 2f, 8f, 8f, 4f, Color.WHITE);

        boolean closeHov = Render2DEngine.isHovered(mouseX, mouseY, spCloseX - 3f, spCloseY - 3f, 14f, 14f);
        if (closeHov) Render2DEngine.drawRound(context, spCloseX - 3f, spCloseY - 3f, 14f, 14f, 4f, CARD_HOVER);
        drawIcon(context, "close", spCloseX, spCloseY, 8f, closeHov ? TEXT : TEXT_DIM);
        Render2DEngine.drawRect(context, spX + 9f, spY + headH - 4f, spW - 18f, 0.6f, HAIRLINE);

        // elements
        float ex = spX + 9f;
        float ew = spW - 18f;
        float top = spY + headH;
        float viewH = spH - headH - 4f;

        float maxScroll = Math.max(0f, contentTotal - viewH);
        scrollSettingsTarget = Math.max(-maxScroll, Math.min(0f, scrollSettingsTarget));
        scrollSettings = AnimationUtility.fast(scrollSettings, scrollSettingsTarget, 12f);

        Render2DEngine.addWindow(context, spX, top, spX + spW, spY + spH - 4f, 1.0);
        float offY = scrollSettings;
        for (AbstractElement el : els) {
            if (!el.isVisible()) continue;
            el.setOffsetY(offY);
            el.setX(ex);
            el.setY(top);
            el.setWidth(ew);
            el.setHeight(13);
            if (el instanceof ColorPickerElement picker) el.setHeight(picker.getHeight());
            else if (el instanceof SliderElement) el.setHeight(18);
            if (el instanceof ModeElement combo) {
                combo.setWHeight(13);
                el.setHeight(combo.isOpen() ? 13 + (combo.getSetting().getModes().length * 12) : 13);
            }
            float elY = top + offY;
            if (elY + el.getHeight() >= top && elY <= spY + spH - 4f)
                el.render(context, mouseX, mouseY, delta);
            offY += el.getHeight();
        }
        Render2DEngine.popWindow();

        if (contentTotal <= 0.5f)
            FontRenderers.sf_medium_mini.drawString(context, "No configurable settings.", spX + 10f, top + 6f, TEXT_DIM.getRGB());
    }

    private float elementHeight(AbstractElement el) {
        if (el instanceof ColorPickerElement picker) return picker.getHeight();
        if (el instanceof SliderElement) return 18;
        if (el instanceof ModeElement combo)
            return combo.isOpen() ? 13 + (combo.getSetting().getModes().length * 12) : 13;
        return 13;
    }

    // ------------------------------------------------------------------ helpers

    private List<Module> filteredModules() {
        if (searchActive && !searchText.isEmpty())
            return Managers.MODULE.getModulesSearch(searchText);
        return Managers.MODULE.getModulesByCategory(selectedCategory);
    }

    private List<AbstractElement> getElements(Module module) {
        return elementCache.computeIfAbsent(module, ElementFactory::build);
    }

    private void openSettings(Module module) {
        settingsModule = module;
        scrollSettings = scrollSettingsTarget = 0f;
    }

    /** Dev hook (screenshot checks): open the settings panel of a module. */
    public void devOpenSettings(Module module) {
        openSettings(module);
    }

    private void closeSettings() {
        settingsModule = null;
    }

    private String shortBind(String b) {
        if (b == null || b.equalsIgnoreCase("none")) return "";
        return switch (b) {
            case "LEFT_CONTROL" -> "LCtrl";
            case "RIGHT_CONTROL" -> "RCtrl";
            case "LEFT_SHIFT" -> "LShift";
            case "RIGHT_SHIFT" -> "RShift";
            case "LEFT_ALT" -> "LAlt";
            case "RIGHT_ALT" -> "RAlt";
            default -> b;
        };
    }

    private String trim(FontRenderer fr, String s, float maxW) {
        if (s == null) return "";
        if (fr.getStringWidth(s) <= maxW) return s;
        while (s.length() > 1 && fr.getStringWidth(s + "...") > maxW)
            s = s.substring(0, s.length() - 1);
        return s + "...";
    }

    /** Keeps the END of the text (what you are typing) visible in the search pill. */
    private String trimLeft(FontRenderer fr, String s, float maxW) {
        while (s.length() > 1 && fr.getStringWidth(s) > maxW) s = s.substring(1);
        return s;
    }

    // ------------------------------------------------------------------ input

    @Override
    public boolean mouseClicked(net.minecraft.client.gui.Click click, boolean doubled) {
        int mx = (int) click.x();
        int my = (int) click.y();
        int button = click.button();

        // binding mode (entered via middle-click on a card): next mouse-button sets a mouse bind
        if (bindingModule != null) {
            bindingModule.setBind(button, true, false);
            bindingModule = null;
            return true;
        }

        // settings panel (topmost)
        if (settingsModule != null && settingsAnim > 0.5f) {
            if (Render2DEngine.isHovered(mx, my, spCloseX - 4f, spCloseY - 4f, 16f, 16f)) {
                closeSettings();
                return true;
            }
            if (Render2DEngine.isHovered(mx, my, spSwitchX - 2f, spSwitchY - 2f, 26f, 16f)) {
                if (settingsModule.isToggleable()) settingsModule.toggle();
                return true;
            }
            if (Render2DEngine.isHovered(mx, my, spX, spY, spW, spH)) {
                for (AbstractElement el : getElements(settingsModule))
                    if (el.isVisible())
                        el.mouseClicked(mx, my, button);
                return true;
            }
        }

        // search pill
        if (Render2DEngine.isHovered(mx, my, searchX, searchY, searchW, searchH)) {
            if (button == 1) searchText = "";
            searchActive = true;
            return true;
        }

        for (float[] r : paletteRects) {
            if (!Render2DEngine.isHovered(mx, my, r[0], r[1], r[2], r[3])) continue;
            int index = (int) r[4];
            if (index < 0) ColorPalette.reset();
            else ColorPalette.apply(ColorPalette.SWATCHES.get(index));
            return true;
        }

        for (Row r : sidebarRows) {
            if (!Render2DEngine.isHovered(mx, my, r.x(), r.y(), r.w(), r.h())) continue;
            if (r.cat() != null) {
                if (r.cat() != selectedCategory) catAnim = 0f;
                selectedCategory = r.cat();
                scrollGrid = scrollGridTarget = 0f;
                searchActive = false;
                searchText = "";
                closeSettings();
            } else {
                handleOtherAction(r.action());
            }
            return true;
        }

        if (my >= gridTop && my <= gridBottom) {
            for (Card c : cards) {
                if (!Render2DEngine.isHovered(mx, my, c.x, c.y, c.w, c.h)) continue;
                float swX = c.x + c.w - 22f - 9f;
                boolean switchZone = Render2DEngine.isHovered(mx, my, swX - 3f, c.y + 8f, 28f, 22f);
                boolean gearZone = Render2DEngine.isHovered(mx, my, swX - 18f, c.y + 8f, 15f, 22f);
                if (button == 2) {
                    bindingModule = c.module;
                } else if (button == 1 && c.module == ModuleManager.rynek) {
                    // AutoMarket carries its own window holding every setting the module has
                    mc.setScreen(new thunder.hack.features.modules.misc.rynek.RynekScreen());
                } else if (button == 1 && c.module == ModuleManager.autoBuild) {
                    // AutoBuild's window shows the schematic, its cost and every setting in one place
                    mc.setScreen(new thunder.hack.gui.build.BuildScreen());
                } else if (button == 1 || (button == 0 && gearZone && !switchZone)) {
                    if (settingsModule == c.module) closeSettings();
                    else openSettings(c.module);
                } else if (button == 0) {
                    if (c.module.isToggleable()) c.module.toggle();
                }
                return true;
            }
        }

        // a click on empty space ends an empty search
        if (searchActive && searchText.isEmpty()) searchActive = false;
        return true;
    }

    private void handleOtherAction(String action) {
        // Windows opens its screen directly: routing it through enable() did nothing whenever the
        // module was left flagged as enabled in the config.
        if ("configs".equals(action) || "friends".equals(action)) {
            ModuleManager.windows.open(action);
            return;
        }
        Module m = "hud".equals(action) ? Managers.MODULE.get("HudEditor") : null;
        if (m != null && !m.isEnabled())
            m.enable();
    }

    @Override
    public boolean mouseReleased(net.minecraft.client.gui.Click click) {
        int mx = (int) click.x();
        int my = (int) click.y();
        int button = click.button();
        if (settingsModule != null)
            for (AbstractElement el : getElements(settingsModule))
                el.mouseReleased(mx, my, button);
        return true;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        float amt = (float) (verticalAmount * 18D);
        if (settingsModule != null && settingsAnim > 0.5f
                && Render2DEngine.isHovered(mouseX, mouseY, spX, spY, spW, spH))
            scrollSettingsTarget += amt;
        else
            scrollGridTarget += amt;
        return true;
    }

    @Override
    public boolean charTyped(net.minecraft.client.input.CharInput input) {
        char key = (char) input.codepoint();
        // the key that just set a bind also arrives here as a character - it belongs to the bind, not the search
        if (swallowChar) {
            swallowChar = false;
            return true;
        }
        if (searchActive) {
            if (key >= ' ') {
                searchText += key;
                scrollGrid = scrollGridTarget = 0f;
            }
            return true;
        }
        if (settingsModule != null) {
            for (AbstractElement el : getElements(settingsModule))
                el.charTyped(key, input.modifiers());
            return true;
        }
        // nothing focused: just start typing to search
        if (bindingModule == null && Character.isLetterOrDigit(key)) {
            searchActive = true;
            searchText = String.valueOf(key);
            scrollGrid = scrollGridTarget = 0f;
        }
        return true;
    }

    @Override
    public boolean keyPressed(net.minecraft.client.input.KeyInput input) {
        int keyCode = input.key();

        if (bindingModule != null) {
            if (keyCode == GLFW.GLFW_KEY_ESCAPE || keyCode == GLFW.GLFW_KEY_DELETE)
                bindingModule.setBind(-1, false, false);
            else
                bindingModule.setBind(keyCode, false, false);
            bindingModule = null;
            swallowChar = keyCode >= GLFW.GLFW_KEY_SPACE && keyCode <= GLFW.GLFW_KEY_GRAVE_ACCENT;   // only keys that also type a character
            return true;
        }

        if (searchActive) {
            if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
                searchActive = false;
                searchText = "";
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_BACKSPACE) {
                if (!searchText.isEmpty())
                    searchText = searchText.substring(0, searchText.length() - 1);
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_ENTER && !searchText.isEmpty()) {
                // Enter toggles the top result
                List<Module> res = filteredModules();
                if (!res.isEmpty() && res.getFirst().isToggleable()) res.getFirst().toggle();
                return true;
            }
        }

        if (settingsModule != null)
            for (AbstractElement el : getElements(settingsModule))
                el.keyTyped(keyCode);

        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            if (settingsModule != null) {
                closeSettings();
                return true;
            }
            close();
            return true;
        }
        return false;
    }

    @Override
    public void close() {
        // Play the shrink/settle-out animation first, then actually close once it has finished.
        if (!closing) {
            closing = true;
            return;
        }
        reallyClose();
    }

    private void reallyClose() {
        for (List<AbstractElement> els : elementCache.values())
            els.forEach(AbstractElement::onClose);
        super.close();
    }

    /** How long the settings panel takes to slide in / out, in seconds. */
    private static final float SETTINGS_SLIDE = 0.45f;

    private static float easeInOutCubic(float x) {
        return x < 0.5f ? 4f * x * x * x : 1f - (float) Math.pow(-2f * x + 2f, 3) / 2f;
    }

    private static float easeOutCubic(float x) {
        float f = 1f - x;
        return 1f - f * f * f;
    }
}
