package thunder.hack.gui.mainmenu;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;
import net.minecraft.util.Util;
import org.jetbrains.annotations.NotNull;
import org.lwjgl.glfw.GLFW;
import thunder.hack.core.manager.client.ConfigManager;
import thunder.hack.gui.font.FontRenderers;
import thunder.hack.utility.render.Render2DEngine;
import thunder.hack.utility.render.core.CloudanAnimationEngine;
import thunder.hack.features.modules.client.HudEditor;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;

import static thunder.hack.features.modules.Module.mc;

/**
 * The wallpaper picker: category tabs and a grid of large preview cards.
 *
 * <p>Applying one is a {@link MenuBackground#crossfadeTo crossfade}, not a swap, and the gallery stays open
 * while it runs - so you watch the dissolve happen behind the grid and can try several in a row without the
 * screen flashing black between them. The old image is held at full opacity until the new one has finished
 * decoding, which is what removes the flash entirely.
 *
 * <p>Every preview is a thumbnail, never the full-size image: a page of a dozen 4K wallpapers would be hundreds
 * of megabytes of texture to draw cards a few hundred pixels wide. {@link MenuBackground} keeps the two caches
 * apart and decodes both off the render thread, so scrolling never stalls.
 */
public class WallpaperGallery extends Screen {
    private static final Color TEXT = new Color(0xECECF1);
    private static final Color DIM = new Color(0x9A9AA6);
    private static final Color FAINT = new Color(0x6C6C78);

    private static final float CARD_GAP = 12f;
    private static final float HEADER = 54f;
    private static final float FOOTER = 34f;

    private final Screen parent;

    /** null means "everything", which is also what the first tab shows. */
    private MenuBackground.Category category;

    private float scroll;
    /** Eased hover per card index, so the scale-up is smooth rather than a step. */
    private final List<Float> hover = new ArrayList<>();
    private long lastFrame;

    /** Hit rectangles from the frame just drawn: {x, y, w, h} per visible card, plus its file name. */
    private final List<float[]> cardRects = new ArrayList<>();
    private final List<String> cardFiles = new ArrayList<>();
    private final List<float[]> tabRects = new ArrayList<>();
    private final List<MenuBackground.Category> tabValues = new ArrayList<>();

    private float[] folderRect = {-1, -1, 0, 0};
    private float[] backRect = {-1, -1, 0, 0};

    /* Carousel: one big preview at a time, arrows and dots. */
    private int index;
    private float shownIndex;                 // eased, so sliding between wallpapers glides
    private float[] arrowLeft = {-1, -1, 0, 0};
    private float[] arrowRight = {-1, -1, 0, 0};
    private final List<float[]> dotRects = new ArrayList<>();

    public WallpaperGallery(Screen parent) {
        super(Text.of("Wallpaper"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        super.init();
        // the scene's sound: rain for night streets, birds and wind for the rest
        MenuAmbience.play(MenuBackground.getSelected());
    }

    @Override
    public void removed() {
        MenuAmbience.stop();
        super.removed();
    }

    /* ------------------------------------------------------------------ */
    /* Render                                                              */
    /* ------------------------------------------------------------------ */

    @Override
    public void render(@NotNull DrawContext context, int mouseX, int mouseY, float delta) {
        long now = System.currentTimeMillis();
        float step = lastFrame == 0L ? 16f : Math.min(250f, now - lastFrame);
        lastFrame = now;

        // Darker than the menu so the cards read against it; the wallpaper is still visibly the thing you are
        // about to change, which is the point of not using a flat background here.
        MenuBackdrop.render(context, this, width, height, mouseX, mouseY, 0.46f, delta);

        cardRects.clear();
        cardFiles.clear();

        drawHeader(context, mouseX, mouseY);
        drawGrid(context, mouseX, mouseY, step);
        drawFooter(context, mouseX, mouseY);
    }

    private void drawHeader(DrawContext context, int mouseX, int mouseY) {
        FontRenderers.sf_bold.drawString(context, "Wallpapers", 20f, 18f, TEXT.getRGB());

        /* Category tabs, left to right, only the ones that actually contain something. */
        tabRects.clear();
        tabValues.clear();

        List<MenuBackground.Category> categories = MenuBackground.populatedCategories();

        float x = 20f;
        float y = 36f;

        x += tab(context, x, y, "All", category == null, mouseX, mouseY, null) + 6f;
        for (MenuBackground.Category c : categories) {
            x += tab(context, x, y, c.name(), category == c, mouseX, mouseY, c) + 6f;
            // Wrap rather than run off the edge on a narrow window.
            if (x > width - 90f) break;
        }
    }

    private float tab(DrawContext context, float x, float y, String label, boolean active,
                      int mouseX, int mouseY, MenuBackground.Category value) {
        float w = FontRenderers.sf_medium_mini.getStringWidth(label) + 16f;
        float h = 17f;
        boolean hovered = Render2DEngine.isHovered(mouseX, mouseY, x, y, w, h);

        Render2DEngine.drawRound(context, x, y, w, h, 5f,
                active ? new Color(255, 255, 255, 46) : new Color(255, 255, 255, hovered ? 26 : 12));
        FontRenderers.sf_medium_mini.drawCenteredString(context, label, x + w / 2f, y + 5f,
                (active ? TEXT : DIM).getRGB());

        tabRects.add(new float[]{x, y, w, h});
        tabValues.add(value);
        return w;
    }

    /**
     * The carousel: one large wallpaper at a time, the neighbours peeking in dimmed at the sides, arrows to
     * move, and a row of dots showing where you are. Sliding between wallpapers is eased so it glides.
     */
    private void drawGrid(DrawContext context, int mouseX, int mouseY, float step) {
        List<String> files = new ArrayList<>();
        files.add(null); // the vanilla panorama, always first and selectable
        files.addAll(MenuBackground.getFiles(category));
        int n = files.size();

        index = Math.max(0, Math.min(n - 1, index));
        shownIndex = CloudanAnimationEngine.approach(shownIndex, index, 140f, step);

        float top = HEADER + 10f;
        float bottom = height - FOOTER;
        float areaH = bottom - top;

        float cardW = Math.min(width * 0.62f, (areaH - 70f) * 16f / 9f);
        float cardH = cardW * 9f / 16f;
        float cx = width / 2f;
        float cy = top + (areaH - 26f) / 2f;
        float gap = cardW * 0.82f;            // spacing between neighbours

        String selected = MenuBackground.getSelected();

        // draw neighbours first (so the centre overlaps them), then the centre card
        for (int d = -1; d <= 1; d++) {
            int i = Math.round(shownIndex) + d;
            // use eased offset for smooth travel
            float off = (i - shownIndex);
            if (i < 0 || i >= n) continue;
            if (Math.abs(off) > 2.2f) continue;

            float scale = Math.max(0.55f, 1f - Math.abs(off) * 0.22f);
            float w = cardW * scale, h = cardH * scale;
            float x = cx + off * gap - w / 2f;
            float y = cy - h / 2f;
            boolean centre = Math.abs(off) < 0.5f;

            String file = files.get(i);
            boolean active = file == null ? selected == null : file.equals(selected);
            drawCard(context, x, y, w, h, file, active, centre ? 1f : 0f);

            // dim the side cards so the centre reads as the focus
            if (!centre)
                Render2DEngine.drawRound(context, x, y, w, h, 8f, new Color(8, 8, 12, Math.round(150 * Math.min(1f, Math.abs(off)))));
            else {
                cardRects.add(new float[]{x, y, w, h});
                cardFiles.add(file);
            }
        }

        // arrows
        float ay = cy - 13f;
        arrowLeft = new float[]{cx - cardW / 2f - 46f, ay, 26f, 26f};
        arrowRight = new float[]{cx + cardW / 2f + 20f, ay, 26f, 26f};
        drawArrow(context, arrowLeft, true, index > 0, mouseX, mouseY);
        drawArrow(context, arrowRight, false, index < n - 1, mouseX, mouseY);

        // dots
        dotRects.clear();
        float dotGap = 14f, dw = Math.min(n, 30) * dotGap;
        float dx = cx - dw / 2f, dy = cy + cardH / 2f + 16f;
        for (int i = 0; i < n && i < 30; i++) {
            boolean on = i == index;
            float r = on ? 4f : 2.5f;
            Render2DEngine.drawRound(context, dx + i * dotGap - r, dy - r, r * 2, r * 2, r,
                    on ? HudEditor.getColor(0) : new Color(255, 255, 255, 70));
            dotRects.add(new float[]{dx + i * dotGap - 7f, dy - 7f, 14f, 14f});
        }

        // the name of the centred wallpaper
        String name = files.get(index) == null ? "Default panorama" : stripExtension(files.get(index));
        FontRenderers.sf_medium.drawCenteredString(context, name, cx, dy + 12f, TEXT.getRGB());
    }

    private void drawArrow(DrawContext context, float[] r, boolean left, boolean enabled, int mouseX, int mouseY) {
        boolean hov = enabled && Render2DEngine.isHovered(mouseX, mouseY, r[0], r[1], r[2], r[3]);
        Render2DEngine.drawRound(context, r[0], r[1], r[2], r[3], 8f,
                new Color(255, 255, 255, !enabled ? 8 : hov ? 50 : 24));
        Color c = new Color(255, 255, 255, enabled ? 230 : 60);
        float mx = r[0] + r[2] / 2f, my = r[1] + r[3] / 2f;
        float s = 5f;
        // a chevron from two short lines
        if (left) {
            Render2DEngine.drawThickLine(context, mx + s * 0.6f, my - s, mx - s * 0.4f, my, c.getRGB(), 2f);
            Render2DEngine.drawThickLine(context, mx - s * 0.4f, my, mx + s * 0.6f, my + s, c.getRGB(), 2f);
        } else {
            Render2DEngine.drawThickLine(context, mx - s * 0.6f, my - s, mx + s * 0.4f, my, c.getRGB(), 2f);
            Render2DEngine.drawThickLine(context, mx + s * 0.4f, my, mx - s * 0.6f, my + s, c.getRGB(), 2f);
        }
    }

    /**
     * One preview card: the image, a darkening overlay on hover, its name and an Apply pill.
     *
     * <p>The hover scale is applied about the card's own centre so it grows in place rather than drifting
     * towards a corner, and it is small - a card that leaps at the cursor makes a grid feel unstable.
     */
    private void drawCard(DrawContext context, float x, float y, float w, float h,
                          String file, boolean active, float hoverAmount) {
        float grow = 1f + hoverAmount * 0.035f;
        float cx = x + w / 2f, cy = y + h / 2f;

        var matrices = context.getMatrices();
        matrices.pushMatrix();
        matrices.translate(cx, cy);
        matrices.scale(grow, grow);
        matrices.translate(-cx, -cy);

        // A plain rounded shadow, NOT drawBlurredShadow: the carousel rescales cards every frame, and the
        // blurred shadow recomputes a Gaussian blur for each new size every frame (and leaks cache entries) -
        // that was the heavy lag when flipping pages. A soft dark rounded rect is free.
        Render2DEngine.drawRound(context, x - 3f, y - 2f, w + 6f, h + 8f, 10f, new Color(0, 0, 0, Math.round(70 + 40 * hoverAmount)));
        Render2DEngine.drawRound(context, x, y, w, h, 8f, new Color(12, 12, 16, 235));

        if (file == null) {
            // The panorama slot has no image of its own to show.
            FontRenderers.sf_medium.drawCenteredString(context, "Default panorama", cx, cy - 4f, DIM.getRGB());
        } else if (!MenuBackground.drawThumb(context, file, x + 1.5f, y + 1.5f, w - 3f, h - 3f)) {
            FontRenderers.sf_medium_mini.drawCenteredString(context, "loading...", cx, cy - 4f, FAINT.getRGB());
        }

        /* Hover: a dark veil with the name and an Apply pill over it. */
        if (hoverAmount > 0.01f) {
            Render2DEngine.drawRound(context, x, y, w, h, 8f,
                    new Color(0, 0, 0, Math.round(130 * hoverAmount)));

            String name = file == null ? "Default panorama" : stripExtension(file);
            FontRenderers.sf_medium_mini.drawCenteredString(context, clip(name, w - 16f), cx, y + h - 30f,
                    new Color(255, 255, 255, Math.round(235 * hoverAmount)).getRGB());

            String verb = active ? "Applied" : "Apply";
            float pillW = FontRenderers.sf_medium_mini.getStringWidth(verb) + 22f;
            Render2DEngine.drawRound(context, cx - pillW / 2f, y + h - 19f, pillW, 15f, 7.5f,
                    new Color(255, 255, 255, Math.round((active ? 40 : 60) * hoverAmount)));
            FontRenderers.sf_medium_mini.drawCenteredString(context, verb, cx, y + h - 15f,
                    new Color(255, 255, 255, Math.round(240 * hoverAmount)).getRGB());
        }

        /* The current wallpaper keeps a bright rim so it is findable without hovering every card. */
        if (active) {
            Color rim = new Color(255, 255, 255, 170);
            Render2DEngine.drawRound(context, x, y, w, 1.4f, 0.7f, rim);
            Render2DEngine.drawRound(context, x, y + h - 1.4f, w, 1.4f, 0.7f, rim);
            Render2DEngine.drawRound(context, x, y, 1.4f, h, 0.7f, rim);
            Render2DEngine.drawRound(context, x + w - 1.4f, y, 1.4f, h, 0.7f, rim);
        }

        matrices.popMatrix();
    }

    private void drawFooter(DrawContext context, int mouseX, int mouseY) {
        float y = height - 24f;

        String hint = "Drop images into " + ConfigManager.CONFIG_FOLDER_NAME + "/misc/backgrounds";
        FontRenderers.sf_medium_mini.drawString(context, hint, 20f, y + 4f, FAINT.getRGB());

        float folderW = FontRenderers.sf_medium_mini.getStringWidth("Open folder") + 20f;
        float backW = FontRenderers.sf_medium_mini.getStringWidth("Back") + 20f;

        folderRect = new float[]{width - 20f - folderW, y, folderW, 18f};
        backRect = new float[]{width - 26f - folderW - backW, y, backW, 18f};

        pill(context, folderRect, "Open folder", mouseX, mouseY);
        pill(context, backRect, "Back", mouseX, mouseY);
    }

    private void pill(DrawContext context, float[] rect, String label, int mouseX, int mouseY) {
        boolean hovered = Render2DEngine.isHovered(mouseX, mouseY, rect[0], rect[1], rect[2], rect[3]);
        Render2DEngine.drawRound(context, rect[0], rect[1], rect[2], rect[3], rect[3] / 2f,
                new Color(255, 255, 255, hovered ? 40 : 20));
        FontRenderers.sf_medium_mini.drawCenteredString(context, label,
                rect[0] + rect[2] / 2f, rect[1] + 5f, (hovered ? TEXT : DIM).getRGB());
    }

    /* ------------------------------------------------------------------ */
    /* Input                                                               */
    /* ------------------------------------------------------------------ */

    @Override
    public boolean mouseClicked(net.minecraft.client.gui.Click click, boolean doubled) {
        double mx = click.x(), my = click.y();

        for (int i = 0; i < tabRects.size(); i++) {
            float[] r = tabRects.get(i);
            if (Render2DEngine.isHovered(mx, my, r[0], r[1], r[2], r[3])) {
                category = tabValues.get(i);
                scroll = 0f;
                return true;
            }
        }

        if (Render2DEngine.isHovered(mx, my, arrowLeft[0], arrowLeft[1], arrowLeft[2], arrowLeft[3])) {
            index--;
            return true;
        }
        if (Render2DEngine.isHovered(mx, my, arrowRight[0], arrowRight[1], arrowRight[2], arrowRight[3])) {
            index++;
            return true;
        }
        for (int i = 0; i < dotRects.size(); i++) {
            float[] r = dotRects.get(i);
            if (Render2DEngine.isHovered(mx, my, r[0], r[1], r[2], r[3])) {
                index = i;
                return true;
            }
        }

        // Clicking the big centre card applies that wallpaper.
        for (int i = 0; i < cardRects.size(); i++) {
            float[] r = cardRects.get(i);
            if (!Render2DEngine.isHovered(mx, my, r[0], r[1], r[2], r[3])) continue;
            MenuBackground.crossfadeTo(cardFiles.get(i), 650L);
            MenuAmbience.play(cardFiles.get(i));
            return true;
        }

        if (Render2DEngine.isHovered(mx, my, folderRect[0], folderRect[1], folderRect[2], folderRect[3])) {
            if (!MenuBackground.FOLDER.exists()) MenuBackground.FOLDER.mkdirs();
            Util.getOperatingSystem().open(MenuBackground.FOLDER);
            return true;
        }

        if (Render2DEngine.isHovered(mx, my, backRect[0], backRect[1], backRect[2], backRect[3])) {
            close();
            return true;
        }

        return true;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        // scroll wheel moves the carousel one wallpaper at a time
        if (verticalAmount > 0) index--;
        else if (verticalAmount < 0) index++;
        return true;
    }

    @Override
    public boolean keyPressed(net.minecraft.client.input.KeyInput input) {
        if (input.key() == GLFW.GLFW_KEY_ESCAPE) {
            close();
            return true;
        }
        if (input.key() == GLFW.GLFW_KEY_LEFT) {
            index--;
            return true;
        }
        if (input.key() == GLFW.GLFW_KEY_RIGHT) {
            index++;
            return true;
        }
        if (input.key() == GLFW.GLFW_KEY_ENTER && !cardFiles.isEmpty()) {
            MenuBackground.crossfadeTo(cardFiles.get(0), 650L);
            MenuAmbience.play(cardFiles.get(0));
            return true;
        }
        return super.keyPressed(input);
    }

    @Override
    public void close() {
        mc.setScreen(parent);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    /* ------------------------------------------------------------------ */
    /* Helpers                                                             */
    /* ------------------------------------------------------------------ */

    private static String stripExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot <= 0 ? name : name.substring(0, dot);
    }

    private static String clip(String text, float maxWidth) {
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
