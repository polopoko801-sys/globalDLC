package thunder.hack.gui.windows;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.util.Colors;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;
import thunder.hack.core.manager.client.ModuleManager;
import thunder.hack.gui.font.FontRenderers;
import thunder.hack.features.modules.client.ClickGui;
import thunder.hack.features.modules.client.HudEditor;
import thunder.hack.setting.Setting;
import thunder.hack.setting.impl.PositionSetting;
import thunder.hack.utility.math.MathUtility;
import thunder.hack.utility.render.Render2DEngine;
import thunder.hack.utility.render.Render3DEngine;
import thunder.hack.utility.render.animation.AnimationUtility;

import java.awt.*;

import static thunder.hack.core.manager.IManager.mc;

public class WindowBase {
    public final Setting<PositionSetting> position;

    private float x;
    private float y;
    private float width;
    private float height;
    private float dragX;
    private float dragY;
    private float scrollOffset;
    private float prevScrollOffset;

    private float maxElementsHeight;
    private final String name;
    private boolean dragging, hoveringWindow, scaling, scrolling;

    private boolean visible = true;
    private final Identifier icon;

    // new GUI theme (matches ClickGUI): liquid glass + violet accent + Montserrat. Package-visible so
    // WindowsScreen (taskbar) shares the exact same palette. TH_PANEL is only the flat stand-in used
    // when the user switches GlassBlur off.
    static final Color TH_PANEL  = new Color(20, 21, 28, 232);
    static final Color TH_HEADER = new Color(29, 30, 40);
    static final Color TH_ACCENT = new Color(139, 100, 246);
    static final Color TH_TEXT   = new Color(233, 234, 240);
    static final Color TH_DIM    = new Color(140, 143, 158);

    protected WindowBase(float x, float y, float width, float height, String name, Setting<PositionSetting> pos, Identifier icon) {
        setX(x);
        setY(y);
        setWidth(width);
        setHeight(height);
        this.name = name;
        this.position = pos;
        this.icon = icon;
    }

    protected void render(DrawContext context, int mouseX, int mouseY) {
        prevScrollOffset = AnimationUtility.fast(prevScrollOffset, scrollOffset, 12);

        thunder.hack.utility.render.RSCompat.enableBlend();
        thunder.hack.utility.render.RSCompat.defaultBlendFunc();

        Color acc = accent();

        // deep, soft shadow lifts the pane off the world, with a faint accent bloom under it
        Render2DEngine.drawBlurredShadow(context, x, y + 3, width + 10, height, 20, new Color(0, 0, 0, 140));
        Render2DEngine.drawBlurredShadow(context, x + 6, y + height - 6, width - 2, 8, 14, Render2DEngine.injectAlpha(acc, 36));

        // the pane itself: blurred + slightly magnified backdrop, translucent veil, top sheen and a rim
        // whose specular hot spot follows the cursor (that is what makes it read as liquid glass)
        boolean glass = glassPanel(context, x, y, width + 10, height, 9f, mouseX, mouseY);

        // header: no slab - a soft top sheen, the window's icon in an accent chip, the title, and a hairline
        // that fades out at both ends
        Render2DEngine.verticalGradient(context, x + 4, y + 1, x + width + 6, y + 16,
                new Color(255, 255, 255, glass ? 26 : 10), new Color(255, 255, 255, 0));
        float tx = x + 6;
        if (icon != null) {
            Render2DEngine.drawRound(context, x + 5, y + 3.5f, 10, 10, 3.5f, Render2DEngine.injectAlpha(acc, 70));
            Render2DEngine.renderTexture(context, icon, x + 6.5f, y + 5, 7, 7, 0, 0, 512, 512, 512, 512, TH_TEXT);
            tx = x + 18;
        }
        FontRenderers.monsterrat.drawString(context, name, tx, y + 5f, TH_TEXT.getRGB());
        float mid = x + (width + 10) / 2f;
        Render2DEngine.horizontalGradient(context, x + 6, y + 16.2f, mid, y + 16.9f, Render2DEngine.injectAlpha(acc, 0), Render2DEngine.injectAlpha(acc, 200));
        Render2DEngine.horizontalGradient(context, mid, y + 16.2f, x + width + 4, y + 16.9f, Render2DEngine.injectAlpha(acc, 200), Render2DEngine.injectAlpha(acc, 0));

        // close button: a bare cross that reddens under the cursor (hitbox kept at x+width-4,y+3,10x10
        // to match mouseClicked). No chip at rest - a disc behind every x is what made the headers look
        // like a row of little boxes.
        boolean hover1 = Render2DEngine.isHovered(mouseX, mouseY, x + width - 4, y + 3, 10, 10);
        cross(context, x + width + 1f, y + 8f, 7.5f, hover1);

        float ratio = (getHeight() - 35) / maxElementsHeight;

        // scrollbar: hairline track, slim accent thumb that widens on hover (hitbox stays 6px wide)
        boolean hover2 = Render2DEngine.isHovered(mouseX, mouseY, x + width, y + 19, 6, getHeight() - 34);
        float sbW = hover2 || scrolling ? 3.5f : 2.5f;
        float sbX = x + width + 3 - sbW / 2f;
        Render2DEngine.drawRound(context, sbX, y + 19, sbW, getHeight() - 34, sbW / 2f, new Color(255, 255, 255, glass ? 16 : 26));
        Render2DEngine.drawRound(context, sbX, Math.max(y + 19 - (scrollOffset * ratio), y + 19), sbW, Math.min((getHeight() - 34) * ratio, getHeight() - 34), sbW / 2f, Render2DEngine.injectAlpha(acc, hover2 ? 230 : 170));
        thunder.hack.utility.render.RSCompat.disableBlend();

        if (dragging) {
            setX(thunder.hack.utility.render.animation.AnimationUtility.fast(getX(), (normaliseX() - dragX), 9.75f));
            setY(thunder.hack.utility.render.animation.AnimationUtility.fast(getY(), (normaliseY() - dragY), 9.75f));
            if (position != null) {
                position.getValue().setX(getX() / mc.getWindow().getScaledWidth());
                position.getValue().setY(getY() / mc.getWindow().getScaledHeight());
            }
        }

        if (scaling) {
            setWidth(Math.max(thunder.hack.utility.render.animation.AnimationUtility.fast(getWidth(), (normaliseX() - dragX), 9.75f), getMinWidth()));
            setHeight(Math.max(thunder.hack.utility.render.animation.AnimationUtility.fast(getHeight(), (normaliseY() - dragY), 9.75f), getMinHeight()));
        }

        if (scrolling) {
            float diff = ((float) mouseY - y - 19) / (getHeight() - 34);
            scrollOffset =  -(diff * maxElementsHeight);
            scrollOffset = MathUtility.clamp(scrollOffset, -maxElementsHeight + (getHeight() - 40), 0);
        }

        hoveringWindow = Render2DEngine.isHovered(mouseX, mouseY, getX(), getY(), getWidth(), getHeight());

        Render2DEngine.drawLine(context, getX() + getWidth(), getY() + getHeight() - 3, getX() + getWidth() + 7, getY() + getHeight() - 10, TH_DIM.getRGB());
        Render2DEngine.drawLine(context, getX() + getWidth() + 5, getY() + getHeight() - 3, getX() + getWidth() + 7, getY() + getHeight() - 5, TH_DIM.getRGB());
    }

    // ------------------------------------------------------------- liquid glass

    /** The ClickGui module owns the glass look; the windows read the same switches (null-safe). */
    private static ClickGui clickGuiCfg() {
        try {
            return ModuleManager.clickGui;
        } catch (Throwable t) {
            return null;
        }
    }

    /** Mirrors the ClickGui module's GlassBlur switch so the whole client can be tuned in one place. */
    public static boolean glassOn() {
        ClickGui cfg = clickGuiCfg();
        return cfg == null || cfg.glass.getValue();
    }

    private static float blurStrength() {
        ClickGui cfg = clickGuiCfg();
        return cfg == null ? 12f : MathHelper.clamp(cfg.glassBlur.getValue(), 2f, 24f);
    }

    /** Veil opacity, shared with the ClickGui's GlassDarkness slider (0 = crystal clear, 240 = smoked). */
    private static int darkness() {
        ClickGui cfg = clickGuiCfg();
        return cfg == null ? 178 : MathHelper.clamp(cfg.glassDarkness.getValue(), 0, 240);
    }

    private static int alpha(int v) {
        return MathHelper.clamp(v, 0, 255);
    }

    /** Same pane as the centred overload, with the specular highlight parked in the middle. */
    static boolean glassPanel(DrawContext context, float x, float y, float w, float h, float r) {
        return glassPanel(context, x, y, w, h, r, x + w / 2f, y + h / 2f);
    }

    /**
     * Liquid-glass pane. The frame behind the window is blurred through the rounded rect with a slight
     * lens (the backdrop comes out a touch magnified, like looking through a drop of water), lifted with a
     * vibrancy boost so it stays saturated instead of grey, veiled with a cool translucent tint that is
     * lighter where the light enters at the top, then finished with a top sheen, a gloss band that slides
     * towards the pointer and a rim whose bright arc faces the cursor.
     *
     * <p>With no snapshot this frame (unsupported backend) it stays translucent - just a denser veil - so
     * a window never collapses into a flat slab.
     */
    public static boolean glassPanel(DrawContext context, float x, float y, float w, float h, float r, float mouseX, float mouseY) {
        if (!glassOn()) {
            Render2DEngine.drawRound(context, x, y, w, h, r, TH_PANEL);
            Render2DEngine.drawGlassRim(context, x, y, w, h, r, 1f, 0.5f);
            return false;
        }

        // refraction: sampling the backdrop slightly magnified around the pane centre reads as thickness
        boolean blurred = Render2DEngine.drawBackdropBlur(context, x, y, w, h, r, blurStrength(), 0.94f);
        if (blurred)
            Render2DEngine.drawBackdropBoost(context, x, y, w, h, r, 0.09f);

        int d = darkness() + (blurred ? 0 : 42);
        Color top = new Color(26, 28, 40, alpha(d - 86));
        Color bottom = new Color(9, 10, 16, alpha(d - 24));
        Render2DEngine.drawGradientRound(context, x, y, w, h, r, top, bottom, top, bottom);

        // sheen along the top edge
        Render2DEngine.verticalGradient(context, x + r * 0.4f, y + 0.7f, x + w - r * 0.4f, y + h * 0.55f,
                new Color(255, 255, 255, 22), new Color(255, 255, 255, 0));

        // gloss band: two ramps meeting under the pointer make a soft vertical highlight that drifts
        // with the cursor, like light moving over a wet surface
        float rel = MathHelper.clamp((mouseX - x) / Math.max(1f, w), 0.06f, 0.94f);
        float cx = x + rel * w;
        float half = Math.max(18f, w * 0.28f);
        float gloss = blurred ? 10f : 6f;
        Render2DEngine.horizontalGradient(context, Math.max(x + r, cx - half), y + 0.8f, cx, y + h - 0.8f,
                new Color(255, 255, 255, 0), new Color(255, 255, 255, (int) gloss));
        Render2DEngine.horizontalGradient(context, cx, y + 0.8f, Math.min(x + w - r, cx + half), y + h - 0.8f,
                new Color(255, 255, 255, (int) gloss), new Color(255, 255, 255, 0));

        Render2DEngine.drawGlassRim(context, x, y, w, h, r, 1.3f, blurred ? 0.95f : 0.7f,
                MathHelper.clamp((mouseX - x) / Math.max(1f, w), 0.03f, 0.97f),
                MathHelper.clamp((mouseY - y) / Math.max(1f, h), 0.03f, 0.97f));
        return blurred;
    }

    /** The client accent, so the windows follow the colour the rest of the GUI uses. */
    static Color accent() {
        try {
            return HudEditor.getColor(0);
        } catch (Throwable t) {
            return TH_ACCENT;
        }
    }

    /**
     * Soft translucent input well used by the list windows: faint fill, a highlight where light catches
     * its top lip, and a hairline rim that brightens under the cursor.
     */
    public static void field(DrawContext context, float x, float y, float w, float h, boolean hovered) {
        float r = Math.min(4f, h / 2f);
        Render2DEngine.drawRound(context, x, y, w, h, r, new Color(255, 255, 255, hovered ? 26 : 14));
        Render2DEngine.verticalGradient(context, x + 0.8f, y + 0.6f, x + w - 0.8f, y + h * 0.75f,
                new Color(255, 255, 255, hovered ? 20 : 12), new Color(255, 255, 255, 0));
        Render2DEngine.drawGlassRim(context, x, y, w, h, r, 0.7f, hovered ? 0.6f : 0.35f);
    }

    /** Small accent chip (Load / add button) with a glow and a glossy top lip when hovered. */
    public static void chip(DrawContext context, float x, float y, float w, float h, boolean hovered) {
        Color acc = accent();
        float r = Math.min(4f, h / 2f);
        if (hovered)
            Render2DEngine.drawBlurredShadow(context, x, y, w, h, 6, Render2DEngine.injectAlpha(acc, 110));
        Render2DEngine.drawGradientRound(context, x, y, w, h, r,
                Render2DEngine.injectAlpha(acc, hovered ? 230 : 170), Render2DEngine.injectAlpha(acc.darker(), hovered ? 230 : 170),
                Render2DEngine.injectAlpha(acc, hovered ? 230 : 170), Render2DEngine.injectAlpha(acc.darker(), hovered ? 230 : 170));
        Render2DEngine.verticalGradient(context, x + 0.6f, y + 0.5f, x + w - 0.6f, y + h * 0.8f,
                new Color(255, 255, 255, hovered ? 90 : 55), new Color(255, 255, 255, 0));
        Render2DEngine.drawGlassRim(context, x, y, w, h, r, 0.7f, 0.5f);
    }

    /** Row height (incl. gap) of the card lists in the Friends / Config windows. */
    public static final float CARD_STEP = 26f, CARD_H = 22f;

    /**
     * One list card: soft glass tile, a glossy top lip, and - for the active / hovered one - an accent wash
     * from the left with a glowing bar, so the selected row reads at a glance.
     */
    public static void card(DrawContext context, float x, float y, float w, float h, boolean hovered, boolean active) {
        Color acc = accent();
        float r = 5f;
        if (active)
            Render2DEngine.drawBlurredShadow(context, x + 2, y + 2, w - 4, h - 4, 8, Render2DEngine.injectAlpha(acc, 70));
        Render2DEngine.drawRound(context, x, y, w, h, r, new Color(255, 255, 255, hovered ? 20 : 11));
        if (active || hovered)
            Render2DEngine.drawGradientRound(context, x, y, w, h, r,
                    Render2DEngine.injectAlpha(acc, active ? 70 : 34), Render2DEngine.injectAlpha(acc, active ? 70 : 34),
                    Render2DEngine.injectAlpha(acc, 0), Render2DEngine.injectAlpha(acc, 0));
        Render2DEngine.verticalGradient(context, x + 1, y + 0.6f, x + w - 1, y + h * 0.5f,
                new Color(255, 255, 255, hovered ? 18 : 10), new Color(255, 255, 255, 0));
        Render2DEngine.drawGlassRim(context, x, y, w, h, r, 0.7f, active ? 0.75f : hovered ? 0.5f : 0.28f);
        if (active)
            Render2DEngine.drawRound(context, x + 1.5f, y + 5, 2f, h - 10, 1f, acc);
    }

    /** Round badge with one letter - stand-in avatar / config initial. */
    public static void letterBadge(DrawContext context, float x, float y, float size, String text, boolean lit) {
        Color acc = accent();
        Render2DEngine.drawGradientRound(context, x, y, size, size, size / 2f,
                Render2DEngine.injectAlpha(acc, lit ? 220 : 120), Render2DEngine.injectAlpha(acc.darker(), lit ? 220 : 120),
                Render2DEngine.injectAlpha(acc, lit ? 220 : 120), Render2DEngine.injectAlpha(acc.darker(), lit ? 220 : 120));
        Render2DEngine.verticalGradient(context, x + 1, y + 0.6f, x + size - 1, y + size * 0.55f,
                new Color(255, 255, 255, 60), new Color(255, 255, 255, 0));
        FontRenderers.sf_bold.drawCenteredString(context, text, x + size / 2f, y + size / 2f - 2.5f, TH_TEXT.getRGB());
    }

    /** Accent hairline under the window's input row, fading out at both ends. */
    public static void divider(DrawContext context, float x, float y, float w) {
        Color acc = accent();
        float mid = x + w / 2f;
        Render2DEngine.horizontalGradient(context, x, y, mid, y + 0.6f, Render2DEngine.injectAlpha(acc, 0), Render2DEngine.injectAlpha(acc, 170));
        Render2DEngine.horizontalGradient(context, mid, y, x + w, y + 0.6f, Render2DEngine.injectAlpha(acc, 170), Render2DEngine.injectAlpha(acc, 0));
    }

    /** Empty-list placeholder: dim icon chip + two lines. */
    public static void emptyState(DrawContext context, float cx, float cy, String title, String hint) {
        Color acc = accent();
        Render2DEngine.drawBlurredShadow(context, cx - 9, cy - 22, 18, 18, 10, Render2DEngine.injectAlpha(acc, 60));
        Render2DEngine.drawRound(context, cx - 9, cy - 22, 18, 18, 9, Render2DEngine.injectAlpha(acc, 70));
        FontRenderers.sf_bold.drawCenteredString(context, "+", cx, cy - 15.5f, TH_TEXT.getRGB());
        FontRenderers.sf_medium.drawCenteredString(context, title, cx, cy + 2, TH_TEXT.getRGB());
        FontRenderers.sf_medium_mini.drawCenteredString(context, hint, cx, cy + 12, TH_DIM.getRGB());
    }

    /**
     * A plain cross standing in for a delete / close action.
     *
     * <p>Two strokes, nothing behind them. The rows used to draw a rounded square (the {@code danger}
     * chip) with the icon font's glyph inside, and on this font that glyph is a tray with an arrow - so
     * a friends row read as "box, box" instead of "click x to remove". The cross is drawn in code, so
     * it looks the same in every window and cannot come out as a missing-glyph square.
     *
     * @param cx       centre of the cross
     * @param cy       centre of the cross
     * @param size     edge length of the square the cross fits in
     * @param hovered  reddens and glows under the cursor
     */
    public static void cross(DrawContext context, float cx, float cy, float size, boolean hovered) {
        float h = size / 2f;
        float t = Math.max(1.15f, size * 0.17f);
        Color col = hovered ? new Color(235, 92, 102) : TH_DIM;
        if (hovered)
            Render2DEngine.drawBlurredShadow(context, cx - h - 2, cy - h - 2, size + 4, size + 4, 5,
                    new Color(235, 80, 90, 120));
        Render2DEngine.thickLine(context, cx - h, cy - h, cx + h, cy + h, t, col);
        Render2DEngine.thickLine(context, cx + h, cy - h, cx - h, cy + h, t, col);
    }

    /** Subtle destructive chip (delete) - quiet until the cursor lands on it. */
    public static void danger(DrawContext context, float x, float y, float w, float h, boolean hovered) {
        float r = Math.min(4f, h / 2f);
        if (hovered)
            Render2DEngine.drawBlurredShadow(context, x, y, w, h, 6, new Color(235, 80, 90, 90));
        Render2DEngine.drawRound(context, x, y, w, h, r, new Color(235, 80, 90, hovered ? 175 : 55));
        Render2DEngine.verticalGradient(context, x + 0.6f, y + 0.5f, x + w - 0.6f, y + h * 0.8f,
                new Color(255, 255, 255, hovered ? 70 : 38), new Color(255, 255, 255, 0));
        Render2DEngine.drawGlassRim(context, x, y, w, h, r, 0.7f, hovered ? 0.6f : 0.3f);
    }

    protected void mouseClicked(double mouseX, double mouseY, int button) {

        if (Render2DEngine.isHovered(mouseX, mouseY, x + width - 4, y + 3, 10, 10)) {
            setVisible(false);
            return;
        }

        // whole header bar drags the window - subclasses check their own header widgets first and
        // only fall through to here when the click hit bare header
        if (Render2DEngine.isHovered(mouseX, mouseY, x, y, width + 10, 16)) {
            if (WindowsScreen.draggingWindow == null)
                dragging = true;

            if (WindowsScreen.draggingWindow == null)
                WindowsScreen.draggingWindow = this;

            WindowsScreen.lastClickedWindow = this;
            dragX = (int) (mouseX - getX());
            dragY = (int) (mouseY - getY());
            return;
        }

        if (Render2DEngine.isHovered(mouseX, mouseY, x + width, y + height - 10, 10, 10)) {
            WindowsScreen.lastClickedWindow = this;
            dragX = (int) (mouseX - getWidth());
            dragY = (int) (mouseY - getHeight());
            scaling = true;
            return;
        }

        if (Render2DEngine.isHovered(mouseX, mouseY, x + width, y + 19, 6, getHeight() - 34)) {
            WindowsScreen.lastClickedWindow = this;
            dragX = (int) (mouseX - getWidth());
            dragY = (int) (mouseY - getHeight());
            scrolling = true;
        }
    }

    protected void keyPressed(int keyCode, int scanCode, int modifiers) {
    }

    /** True while the window is capturing typed text - keeps ESC from closing the whole screen. */
    protected boolean isTyping() {
        return false;
    }

    /** Window bounds including the scrollbar strip and the resize corner. */
    public boolean contains(double mx, double my) {
        return mx >= getX() && mx <= getX() + getWidth() + 10
                && my >= getY() && my <= getY() + getHeight() + 3;
    }

    /** Moves the window and remembers the new spot in the config. */
    void place(float newX, float newY) {
        setX(newX);
        setY(newY);
        if (position != null) {
            position.getValue().setX(newX / mc.getWindow().getScaledWidth());
            position.getValue().setY(newY / mc.getWindow().getScaledHeight());
        }
    }

    protected void charTyped(char key, int keyCode) {
    }

    protected void mouseScrolled(int i) {
        if (hoveringWindow) {
            scrollOffset += i * 2;
            scrollOffset = MathUtility.clamp(scrollOffset, -maxElementsHeight + (getHeight() - 40), 0);
        }
    }

    public void mouseReleased(double mouseX, double mouseY, int button) {
        dragging = false;
        scaling = false;
        scrolling = false;
        WindowsScreen.draggingWindow = null;
    }

    protected float getX() {
        return x;
    }

    protected void setX(float x) {
        this.x = x;
    }

    protected float getY() {
        return y;
    }

    protected void setY(float y) {
        this.y = y;
    }

    protected float getWidth() {
        return width;
    }

    protected void setWidth(float width) {
        this.width = width;
    }

    protected float getHeight() {
        return height;
    }

    protected void setHeight(float height) {
        this.height = height;
    }

    protected int normaliseX() {
        return (int) (mc.mouse.getX() / Render3DEngine.getScaleFactor());
    }

    protected int normaliseY() {
        return (int) (mc.mouse.getY() / Render3DEngine.getScaleFactor());
    }

    protected float getScrollOffset() {
        return prevScrollOffset;
    }

    protected void resetScroll() {
        prevScrollOffset = 0;
        scrollOffset = 0;
    }

    protected int getMinWidth() {
        return 150;
    }

    protected int getMinHeight() {
        return 150;
    }

    protected void setMaxElementsHeight(float maxElementsHeight) {
        this.maxElementsHeight = maxElementsHeight;
    }

    public boolean isVisible() {
        return visible;
    }

    public void setVisible(boolean visible) {
        this.visible = visible;
    }

    public Identifier getIcon() {
        return icon;
    }
}
