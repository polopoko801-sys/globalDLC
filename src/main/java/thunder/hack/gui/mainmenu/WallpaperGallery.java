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
    private float shownIndex;                 // where the row is, sprung toward index
    private final thunder.hack.utility.render.core.Spring slide = new thunder.hack.utility.render.core.Spring(150f, 22f, 0f);
    /** 0 -> 1 as the screen opens: the header drops in and the cards rise and grow into place. */
    private final thunder.hack.utility.render.core.Spring open = new thunder.hack.utility.render.core.Spring(120f, 19f, 0f);
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
        // the glass panes below blur the wallpaper, so snapshot it before any of them is drawn
        MenuGlass.capture();
        frameStep = step;

        cardRects.clear();
        cardFiles.clear();

        // opening: the header drops in from above, the cards rise and grow into place, the footer comes up
        float p = open.update(1f, step);
        var m = context.getMatrices();

        m.pushMatrix();
        m.translate(0f, -(1f - p) * 16f);
        drawHeader(context, mouseX, mouseY);
        m.popMatrix();

        m.pushMatrix();
        float grow = 0.93f + 0.07f * p;
        m.translate(width / 2f, height / 2f + (1f - p) * 24f);
        m.scale(grow, grow);
        m.translate(-width / 2f, -height / 2f);
        drawGrid(context, mouseX, mouseY, step);
        m.popMatrix();

        m.pushMatrix();
        m.translate(0f, (1f - p) * 16f);
        drawFooter(context, mouseX, mouseY);
        m.popMatrix();

        // and everything fades up out of the dark
        float veil = Math.max(0f, Math.min(1f, 1f - p));
        if (veil > 0.01f) Render2DEngine.drawRect(context, 0, 0, width, height, new Color(6, 6, 10, Math.round(220 * veil)));
    }

    /** Time since the last frame, for the eased bits drawn outside render() itself. */
    private float frameStep = 16f;
    /** The accent pill under the active tab, gliding between tabs: its eased left and right edges. */
    private float tabPillX = -1f, tabPillW;
    /** Eased width of the active dot, so the page indicator stretches rather than jumps. */
    private float dotHead = -1f, dotTail;
    private final thunder.hack.utility.render.core.Spring dotHeadSpring = new thunder.hack.utility.render.core.Spring(300f, 31f, 0f);
    private final thunder.hack.utility.render.core.Spring dotTailSpring = new thunder.hack.utility.render.core.Spring(130f, 22f, 0f);
    private thunder.hack.utility.render.core.Spring tabLeft, tabRight;

    /** Per control, a spring resting at 1: a click kicks it down and it bounces back - the press. */
    private final java.util.Map<String, thunder.hack.utility.render.core.Spring> presses = new java.util.HashMap<>();

    private float press(String key) {
        return presses.computeIfAbsent(key, k -> new thunder.hack.utility.render.core.Spring(420f, 16f, 1f)).update(1f, frameStep);
    }

    private void bump(String key) {
        presses.computeIfAbsent(key, k -> new thunder.hack.utility.render.core.Spring(420f, 16f, 1f)).velocity = -2.6f;
    }

    /** Runs {@code draw} scaled about (cx, cy) by the control's press spring. */
    private void pressed(DrawContext context, String key, float cx, float cy, Runnable draw) {
        float s = press(key);
        var m = context.getMatrices();
        m.pushMatrix();
        m.translate(cx, cy);
        m.scale(s, s);
        m.translate(-cx, -cy);
        draw.run();
        m.popMatrix();
    }
    /** Eased hover per control, keyed by name. */
    private final java.util.Map<String, Float> hoverOf = new java.util.HashMap<>();

    private float hoverFor(String key, boolean hovered) {
        float v = thunder.hack.utility.render.core.CloudanAnimationEngine.approach(
                hoverOf.getOrDefault(key, 0f), hovered ? 1f : 0f, 60f, frameStep);
        hoverOf.put(key, v);
        return v;
    }

    /**
     * The title with a count under it on the left, and the categories as one glass segmented control on the
     * right - the active one marked by an accent pill that slides over to whichever tab is picked.
     */
    private void drawHeader(DrawContext context, int mouseX, int mouseY) {
        FontRenderers.sf_bold.drawString(context, "Wallpapers", 22f, 16f, TEXT.getRGB());
        int count = MenuBackground.getFiles(category).size() + 1;
        FontRenderers.sf_medium_mini.drawString(context, count + (count == 1 ? " wallpaper" : " wallpapers")
                + (category == null ? "" : " in " + category.name()), 22f, 30f, FAINT.getRGB());

        tabRects.clear();
        tabValues.clear();

        List<String> labels = new ArrayList<>();
        List<MenuBackground.Category> values = new ArrayList<>();
        labels.add("All");
        values.add(null);
        for (MenuBackground.Category c : MenuBackground.populatedCategories()) {
            labels.add(c.name());
            values.add(c);
        }

        var font = FontRenderers.sf_medium_mini;
        float pad = 12f, h = 20f, y = 16f;
        float total = 4f;
        List<Float> widths = new ArrayList<>();
        for (String l : labels) {
            float w = font.getStringWidth(l) + pad * 2f;
            // a narrow window keeps the tabs that fit instead of running off the edge
            if (total + w + 4f > width * 0.6f) break;
            widths.add(w);
            total += w;
        }
        total += 4f;
        float x0 = width - 22f - total;

        MenuGlass.draw(context, x0, y - 3f, total, h + 6f, (h + 6f) / 2f, 1f, 0f);

        float x = x0 + 4f;
        float activeX = -1f, activeW = 0f;
        for (int i = 0; i < widths.size(); i++) {
            float w = widths.get(i);
            if (values.get(i) == category) {
                activeX = x;
                activeW = w;
            }
            tabRects.add(new float[]{x, y, w, h});
            tabValues.add(values.get(i));
            x += w;
        }

        if (activeX >= 0f) {
            float targetL = activeX, targetR = activeX + activeW;
            if (tabPillX < 0f) {
                tabPillX = targetL;
                tabPillW = targetR;
            }
            // the same worm as the page dots: the edge on the side it is heading leads, the other one trails
            boolean right = targetL > tabPillX;
            // springs: the leading edge is tight and snaps over with a little overshoot, the trailing one is
            // loose and follows - the pill stretches, lands, and wobbles back to its tab's width
            if (tabLeft == null) {
                tabLeft = new thunder.hack.utility.render.core.Spring(300f, 26f, targetL);
                tabRight = new thunder.hack.utility.render.core.Spring(300f, 26f, targetR);
            }
            tabLeft.tune(right ? 130f : 320f, right ? 19f : 25f);
            tabRight.tune(right ? 320f : 130f, right ? 25f : 19f);
            tabPillX = tabLeft.update(targetL, frameStep);
            tabPillW = tabRight.update(targetR, frameStep);
            float pw = Math.max(h, tabPillW - tabPillX);
            Color accent = HudEditor.getColor(0);
            Render2DEngine.drawRound(context, tabPillX - 2f, y - 2f, pw + 4f, h + 4f, h / 2f + 2f, Render2DEngine.injectAlpha(accent, 40));
            Render2DEngine.drawRound(context, tabPillX, y, pw, h, h / 2f, Render2DEngine.injectAlpha(accent, 205));
        }

        for (int i = 0; i < widths.size(); i++) {
            float[] r = tabRects.get(i);
            boolean active = values.get(i) == category;
            float hov = hoverFor("tab" + i, !active && Render2DEngine.isHovered(mouseX, mouseY, r[0], r[1], r[2], r[3]));
            // how much of this tab the moving pill covers, so its text brightens as the pill arrives under it
            float covered = Math.max(0f, Math.min(r[0] + r[2], tabPillW) - Math.max(r[0], tabPillX)) / r[2];
            float on = Math.max(hoverFor("tabOn" + i, active) * 0.4f + covered * 0.6f, 0f);
            if (hov > 0.01f)
                Render2DEngine.drawRound(context, r[0], r[1], r[2], r[3], h / 2f, new Color(255, 255, 255, Math.round(22 * hov)));
            Color base = mix(DIM, TEXT, hov);
            int labelColor = mix(base, Color.WHITE, Math.min(1f, on)).getRGB();
            String label = labels.get(i);
            pressed(context, "tab" + i, r[0] + r[2] / 2f, r[1] + r[3] / 2f,
                    () -> font.drawCenteredString(context, label, r[0] + r[2] / 2f, r[1] + 7f, labelColor));
        }
    }

    private static Color mix(Color a, Color b, float t) {
        t = Math.max(0f, Math.min(1f, t));
        return new Color(Math.round(a.getRed() + (b.getRed() - a.getRed()) * t),
                Math.round(a.getGreen() + (b.getGreen() - a.getGreen()) * t),
                Math.round(a.getBlue() + (b.getBlue() - a.getBlue()) * t));
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
        // a spring, not an ease: the row carries a little momentum and settles, like a real carousel
        shownIndex = slide.update(index, step);

        float top = HEADER + 10f;
        float bottom = height - FOOTER;
        float areaH = bottom - top;

        // room under the card for the page dots, the name and the Apply button
        float cardW = Math.min(width * 0.62f, (areaH - 120f) * 16f / 9f);
        float cardH = cardW * 9f / 16f;
        float cx = width / 2f;
        float cy = top + (areaH - 70f) / 2f;
        float gap = cardW * 0.82f;            // spacing between neighbours

        String selected = MenuBackground.getSelected();

        // Two neighbours either side, drawn farthest first so nearer cards overlap them. Everything about a card -
        // size, how far it sits below the centre (the row bends into a gentle arc), how dim it is - follows its
        // continuous distance from the centre, so a slide is one smooth motion with no card snapping into focus.
        int base = Math.round(shownIndex);
        int[] order = {-2, 2, -1, 1, 0};
        for (int d : order) {
            int i = base + d;
            if (i < 0 || i >= n) continue;
            float off = i - shownIndex;
            float dist = Math.abs(off);
            if (dist > 2.6f) continue;

            float focus = Math.max(0f, 1f - dist);
            float scale = 1f - Math.min(dist, 2.5f) * 0.2f;
            float w = cardW * scale, h = cardH * scale;
            float x = cx + Math.signum(off) * gap * (float) Math.pow(Math.min(dist, 2.5f), 0.85) - w / 2f;
            float y = cy - h / 2f + dist * dist * 6f;

            String file = files.get(i);
            boolean active = file == null ? selected == null : file.equals(selected);
            drawCard(context, x, y, w, h, file, active, focus);

            // the farther from the centre, the deeper in shadow
            float dim = Math.min(1f, dist) * 150f + Math.max(0f, dist - 1f) * 60f;
            if (dim > 1f)
                Render2DEngine.drawRound(context, x, y, w, h, 12f, new Color(8, 8, 12, Math.round(Math.min(210f, dim))));
            if (dist < 0.5f) {
                cardRects.add(new float[]{x, y, w, h});
                cardFiles.add(file);
            }
        }

        // arrows: round glass buttons either side of the centre card
        float ar = 30f, ay = cy - ar / 2f;
        arrowLeft = new float[]{cx - cardW / 2f - ar - 18f, ay, ar, ar};
        arrowRight = new float[]{cx + cardW / 2f + 18f, ay, ar, ar};
        boolean canLeft = index > 0, canRight = index < n - 1;
        pressed(context, "left", arrowLeft[0] + ar / 2f, ay + ar / 2f, () -> drawArrow(context, arrowLeft, true, canLeft, mouseX, mouseY));
        pressed(context, "right", arrowRight[0] + ar / 2f, ay + ar / 2f, () -> drawArrow(context, arrowRight, false, canRight, mouseX, mouseY));

        // Page indicator: the dots never move. The accent pill travels over them like a worm - its leading end
        // races to the new dot, the trailing end follows a beat later, so it stretches across the gap and then
        // gathers itself up on arrival. Dots under it fade out of the way.
        dotRects.clear();
        int shownDots = Math.min(n, 30);
        float dotGap = 13f, dot = 5f;
        float dx = cx - (shownDots - 1) * dotGap / 2f, dy = cy + cardH / 2f + 18f;
        float target = Math.min(index, shownDots - 1);
        if (dotHead < 0f) {
            dotHeadSpring.snap(target);
            dotTailSpring.snap(target);
        }
        // the head is a tight spring that overshoots the dot a hair, the tail a loose one that catches up
        dotHead = dotHeadSpring.update(target, frameStep);
        dotTail = dotTailSpring.update(target, frameStep);
        float lo = Math.min(dotHead, dotTail), hi = Math.max(dotHead, dotTail);

        // Clean and quiet, like a phone's page dots: smooth anti-aliased shapes (the old 4-segment rounding made
        // them jagged), no halo, and the dots stay put - the pill simply slides over them, drawn on top.
        for (int i = 0; i < shownDots; i++) {
            float x = Render2DEngine.snap(dx + i * dotGap);
            Render2DEngine.drawRoundSmooth(context, x - dot / 2f, dy - dot / 2f, dot, dot, dot / 2f, new Color(255, 255, 255, 70));
            dotRects.add(new float[]{x - dotGap / 2f, dy - 7f, dotGap, 14f});
        }
        float left = dx + lo * dotGap - dot / 2f - 1f, right = dx + hi * dotGap + dot / 2f + 1f;
        // the pill is at least a little longer than a dot, so it reads as "this page" even at rest
        float minLen = dot + 8f;
        if (right - left < minLen) {
            float mid = (left + right) / 2f;
            left = mid - minLen / 2f;
            right = mid + minLen / 2f;
        }
        Render2DEngine.drawRoundSmooth(context, left, dy - dot / 2f - 0.5f, right - left, dot + 1f, (dot + 1f) / 2f, HudEditor.getColor(0));

        // the centred wallpaper's name, and the button that applies it
        String file = files.get(index);
        boolean active = file == null ? selected == null : file.equals(selected);
        String name = file == null ? "Default panorama" : stripExtension(file);
        FontRenderers.sf_bold.drawCenteredString(context, clip(name, cardW), cx, dy + 13f, TEXT.getRGB());

        String verb = active ? "Applied" : "Apply";
        float bw = FontRenderers.sf_medium_mini.getStringWidth(verb) + (active ? 34f : 28f), bh = 20f;
        applyRect = new float[]{cx - bw / 2f, dy + 28f, bw, bh};
        applyFile = file;
        float hov = hoverFor("apply", !active && Render2DEngine.isHovered(mouseX, mouseY, applyRect[0], applyRect[1], bw, bh));
        // the button squashes on a click and bounces back (the press spring scales everything drawn below)
        float ps = press("apply");
        var pm = context.getMatrices();
        pm.pushMatrix();
        pm.translate(cx, applyRect[1] + bh / 2f);
        pm.scale(ps, ps);
        pm.translate(-cx, -(applyRect[1] + bh / 2f));
        drawApply(context, active, verb, bw, bh, cx, hov);
        pm.popMatrix();
    }

    private void drawApply(DrawContext context, boolean active, String verb, float bw, float bh, float cx, float hov) {
        if (active) {
            MenuGlass.draw(context, applyRect[0], applyRect[1], bw, bh, bh / 2f, 1f, 0f);
            // a tick before the word
            float tx = applyRect[0] + 11f, ty = applyRect[1] + bh / 2f;
            int tick = HudEditor.getColor(0).getRGB();
            Render2DEngine.drawThickLine(context, tx - 3f, ty, tx - 0.8f, ty + 2.4f, tick, 1.6f);
            Render2DEngine.drawThickLine(context, tx - 0.8f, ty + 2.4f, tx + 3.5f, ty - 2.6f, tick, 1.6f);
            FontRenderers.sf_medium_mini.drawString(context, verb, tx + 7f, applyRect[1] + 7f, DIM.getRGB());
        } else {
            Color accent = HudEditor.getColor(0);
            Render2DEngine.drawRound(context, applyRect[0], applyRect[1], bw, bh, bh / 2f,
                    Render2DEngine.injectAlpha(accent, Math.round(190 + 60 * hov)));
            FontRenderers.sf_medium_mini.drawCenteredString(context, verb, cx, applyRect[1] + 7f, Color.WHITE.getRGB());
        }
    }

    private float[] applyRect = {-1, -1, 0, 0};
    private String applyFile;

    private void drawArrow(DrawContext context, float[] r, boolean left, boolean enabled, int mouseX, int mouseY) {
        float hov = hoverFor(left ? "left" : "right", enabled && Render2DEngine.isHovered(mouseX, mouseY, r[0], r[1], r[2], r[3]));
        MenuGlass.draw(context, r[0], r[1], r[2], r[3], r[2] / 2f, enabled ? 1f : 0.45f, hov);
        Color c = new Color(255, 255, 255, enabled ? Math.round(210 + 45 * hov) : 70);
        float mx = r[0] + r[2] / 2f + (left ? -1f : 1f) * hov * 1.5f, my = r[1] + r[3] / 2f;
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
        Render2DEngine.drawRound(context, x - 4f, y - 2f, w + 8f, h + 10f, 14f, new Color(0, 0, 0, Math.round(80 + 40 * hoverAmount)));
        Render2DEngine.drawRound(context, x, y, w, h, 12f, new Color(12, 12, 16, 235));

        if (file == null) {
            // The panorama slot has no image of its own to show.
            FontRenderers.sf_medium.drawCenteredString(context, "Default panorama", cx, cy - 4f, DIM.getRGB());
        } else if (!MenuBackground.drawThumb(context, file, x + 1.5f, y + 1.5f, w - 3f, h - 3f)) {
            FontRenderers.sf_medium_mini.drawCenteredString(context, "loading...", cx, cy - 4f, FAINT.getRGB());
        }

        /* The current wallpaper: an accent rim and an "Active" badge, findable at a glance. */
        if (active) {
            Color rim = Render2DEngine.injectAlpha(HudEditor.getColor(0), 210);
            float t = 1.6f;
            Render2DEngine.drawRound(context, x + 6f, y, w - 12f, t, t / 2f, rim);
            Render2DEngine.drawRound(context, x + 6f, y + h - t, w - 12f, t, t / 2f, rim);
            Render2DEngine.drawRound(context, x, y + 6f, t, h - 12f, t / 2f, rim);
            Render2DEngine.drawRound(context, x + w - t, y + 6f, t, h - 12f, t / 2f, rim);

            float bw = FontRenderers.sf_medium_mini.getStringWidth("Active") + 20f;
            Render2DEngine.drawRound(context, x + 8f, y + 8f, bw, 15f, 7.5f, new Color(10, 10, 14, 190));
            Render2DEngine.drawRound(context, x + 14f, y + 13.5f, 4f, 4f, 2f, HudEditor.getColor(0));
            FontRenderers.sf_medium_mini.drawString(context, "Active", x + 21f, y + 13f, TEXT.getRGB());
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
        float hov = hoverFor("pill" + label, Render2DEngine.isHovered(mouseX, mouseY, rect[0], rect[1], rect[2], rect[3]));
        MenuGlass.draw(context, rect[0], rect[1], rect[2], rect[3], rect[3] / 2f, 1f, hov);
        FontRenderers.sf_medium_mini.drawCenteredString(context, label,
                rect[0] + rect[2] / 2f, rect[1] + 5f, (hov > 0.5f ? TEXT : DIM).getRGB());
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
                bump("tab" + i);
                scroll = 0f;
                return true;
            }
        }

        if (Render2DEngine.isHovered(mx, my, arrowLeft[0], arrowLeft[1], arrowLeft[2], arrowLeft[3])) {
            index--;
            bump("left");
            return true;
        }
        if (Render2DEngine.isHovered(mx, my, arrowRight[0], arrowRight[1], arrowRight[2], arrowRight[3])) {
            index++;
            bump("right");
            return true;
        }
        for (int i = 0; i < dotRects.size(); i++) {
            float[] r = dotRects.get(i);
            if (Render2DEngine.isHovered(mx, my, r[0], r[1], r[2], r[3])) {
                index = i;
                return true;
            }
        }

        if (Render2DEngine.isHovered(mx, my, applyRect[0], applyRect[1], applyRect[2], applyRect[3])) {
            bump("apply");
            MenuBackground.crossfadeTo(applyFile, 650L);
            MenuAmbience.play(applyFile);
            return true;
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
