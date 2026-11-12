package thunder.hack.gui.clickui;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;
import thunder.hack.features.modules.render.LyricsText;
import thunder.hack.gui.font.FontRenderers;
import thunder.hack.setting.Setting;
import thunder.hack.utility.ColorPalette;
import thunder.hack.utility.render.Render2DEngine;
import thunder.hack.utility.render.ScreenCapture;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static thunder.hack.features.modules.Module.mc;

/**
 * The Text in Minecraft editor: every setting of the module next to a small screen that plays sample lines through
 * the module's own drawing - layout, animation, colours, highlight, glow and style change there as you click.
 */
public class TextEditorScreen extends Screen {
    private final Screen parent;
    private final LyricsText module;

    public TextEditorScreen(Screen parent, LyricsText module) {
        super(Text.of("Text in Minecraft"));
        this.parent = parent;
        this.module = module;
    }

    private static final Color TEXT = new Color(238, 239, 245);
    private static final Color TEXT_DIM = new Color(172, 175, 192);
    private static final Color TEXT_MUTED = new Color(98, 101, 118);
    private static final Color FIELD = new Color(255, 255, 255, 14);
    private static final Color FIELD_HOVER = new Color(255, 255, 255, 26);
    private static final float W = 600f, H = 340f, CONTROLS_W = 236f;

    private record Hot(float x, float y, float w, float h, Runnable action) {
        boolean covers(double mx, double my) {
            return mx >= x && mx <= x + w && my >= y && my <= y + h;
        }
    }

    private final List<Hot> hots = new ArrayList<>();
    private Setting<Float> dragging;
    private float dragX, dragW, dragMin, dragMax;
    private float scroll, scrollTarget, contentH, viewTop, viewH;

    /* ------------------------------------------------------------------ */
    /* Preview                                                             */
    /* ------------------------------------------------------------------ */

    private static final String[] SAMPLE = {
            "tekst pojawia się słowo po słowie",
            "każda linijka w innym miejscu",
            "kolor i animacja widać od razu",
            "tak to będzie wyglądać w grze"
    };
    private static final long WORD_MS = 230L, HOLD_MS = 1400L;

    private final Random random = new Random();
    private int sampleIndex = -1;
    private long lineStart;
    private List<LyricsText.Word> words;
    private List<int[]> groups;
    private float[][] spots;
    private int highlight;
    private LyricsText.Layout builtFor;

    private void nextLine(long now) {
        sampleIndex = (sampleIndex + 1) % SAMPLE.length;
        String[] tokens = SAMPLE[sampleIndex].split(" ");
        words = new ArrayList<>();
        for (String t : tokens) words.add(new LyricsText.Word(t, 0));
        builtFor = module.layout.getValue();
        groups = LyricsText.groupsFor(builtFor, words.size(), random);
        spots = null;
        highlight = module.highlight.getValue() ? random.nextInt(words.size()) : -1;
        lineStart = now;
    }

    private void drawPreview(DrawContext ctx, float bx, float by, float bw, float bh) {
        long now = System.currentTimeMillis();
        if (words == null || builtFor != module.layout.getValue()) nextLine(now);
        long shownFor = words.size() * WORD_MS + HOLD_MS;
        long exitLen = 300L + LyricsText.EXIT_STAGGER_MS * words.size();
        if (now - lineStart > shownFor + exitLen + 250L) nextLine(now);
        if (highlight < 0 && module.highlight.getValue()) highlight = random.nextInt(words.size());
        if (!module.highlight.getValue()) highlight = -1;

        boolean wordByWord = module.wordByWord.getValue();
        for (int i = 0; i < words.size(); i++)
            words.get(i).appearedAt = now - lineStart >= (wordByWord ? i * WORD_MS : 0) ? lineStart + (wordByWord ? i * WORD_MS : 0) : 0L;
        long exitAt = now - lineStart > shownFor ? lineStart + shownFor : 0L;

        // a sky-to-ground backdrop so outline and shadow read like they do in the world
        Render2DEngine.drawGradientRound(ctx, bx, by, bw, bh, 8f,
                new Color(120, 170, 220), new Color(70, 110, 60), new Color(120, 170, 220), new Color(70, 110, 60));

        float scale = module.scale.getValue() * 0.42f;
        if (spots == null) {
            spots = new float[groups.size()][];
            float spread = module.spread.getValue();
            for (int g = 0; g < groups.size(); g++) {
                if (builtFor == LyricsText.Layout.Line) {
                    spots[g] = new float[]{bx + bw / 2f + (random.nextFloat() - 0.5f) * bw * 0.4f * spread,
                            by + bh / 2f + (random.nextFloat() - 0.5f) * bh * 0.4f * spread};
                } else {
                    spots[g] = new float[]{bx + bw * (0.2f + 0.6f * random.nextFloat()), by + bh * (0.15f + 0.7f * random.nextFloat())};
                }
            }
        }
        Render2DEngine.addWindow(ctx, bx, by, bx + bw, by + bh, 1.0);
        try {
            module.drawGroups(ctx, words, groups, spots, now, exitAt, scale, highlight, 1f);
        } finally {
            Render2DEngine.popWindow();
        }
    }

    /* ------------------------------------------------------------------ */
    /* Screen                                                              */
    /* ------------------------------------------------------------------ */

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        ScreenCapture.captureFrame();
        hots.clear();
        float x = Math.round((width - W) / 2f), y = Math.round((height - H) / 2f);
        if (dragging != null) setSlider(mouseX);

        ClickGUI.glassPanel(ctx, x, y, W, H, 12f, true);
        Render2DEngine.drawRound(ctx, x + 14f, y + 13f, 20f, 20f, 6f, Render2DEngine.injectAlpha(ClickGUI.accent(), 70));
        FontRenderers.sf_bold.drawString(ctx, "T", x + 21f, y + 20f, TEXT.getRGB());
        FontRenderers.sf_bold.drawString(ctx, "Text in Minecraft", x + 42f, y + 15f, TEXT.getRGB());
        FontRenderers.sf_medium_mini.drawString(ctx, "Edytor efektów tekstu", x + 42f, y + 28f, TEXT_MUTED.getRGB());
        button(ctx, mouseX, mouseY, x + W - 84f, y + 13f, 70f, 20f, "Gotowe", true, this::close);

        // ---- controls, scrollable
        float cx = x + 14f;
        viewTop = y + 46f;
        viewH = H - 46f - 12f;
        scroll += (scrollTarget - scroll) * 0.3f;
        Render2DEngine.addWindow(ctx, cx - 4f, viewTop, cx + CONTROLS_W + 4f, viewTop + viewH, 1.0);
        float cy = viewTop + scroll;
        try {
            cy = chips(ctx, mouseX, mouseY, cx, cy, "UKŁAD", module.layout,
                    new String[]{"Linijka", "Kawałki", "Słowa"});
            cy = chips(ctx, mouseX, mouseY, cx, cy, "ANIMACJA", module.anim,
                    new String[]{"Pop", "Fade", "Slide", "Pisanie"});
            cy = chips(ctx, mouseX, mouseY, cx, cy, "KOLOR", module.colorMode,
                    new String[]{"Biały", "Własny", "Motyw", "Tęcza", "Gradient"});
            cy = swatches(ctx, mouseX, mouseY, cx, cy, "KOLOR TEKSTU", module.textColor);
            cy = toggle(ctx, mouseX, mouseY, cx, cy, "Losowo podświetlone słowo", module.highlight);
            cy = swatches(ctx, mouseX, mouseY, cx, cy, "KOLOR PODŚWIETLENIA", module.highlightColor);
            cy = toggle(ctx, mouseX, mouseY, cx, cy, "Poświata", module.glow);
            cy = toggle(ctx, mouseX, mouseY, cx, cy, "Słowo po słowie", module.wordByWord);
            cy = chips(ctx, mouseX, mouseY, cx, cy, "STYL", module.style, new String[]{"Obrys", "Cień"});
            cy = slider(ctx, mouseX, mouseY, cx, cy, "Rozmiar", module.scale, 1f, 6f, "%.1f");
            cy = slider(ctx, mouseX, mouseY, cx, cy, "Rozrzut", module.spread, 0f, 1f, "%.2f");
            cy = slider(ctx, mouseX, mouseY, cx, cy, "Odległość", module.distance, 3f, 30f, "%.0f");
            cy = toggle(ctx, mouseX, mouseY, cx, cy, "Status u góry", module.showStatus);
        } finally {
            Render2DEngine.popWindow();
        }
        contentH = cy - (viewTop + scroll);
        scrollTarget = Math.clamp(scrollTarget, Math.min(0f, viewH - contentH), 0f);

        // ---- preview
        float pvX = cx + CONTROLS_W + 16f, pvY = y + 58f;
        float pvW = x + W - 14f - pvX, pvH = y + H - 14f - pvY;
        FontRenderers.sf_medium_mini.drawString(ctx, "PODGLĄD", pvX, y + 46f, TEXT_MUTED.getRGB());
        drawPreview(ctx, pvX, pvY, pvW, pvH);
    }

    private <E extends Enum<E>> float chips(DrawContext ctx, int mx, int my, float x, float y, String label, Setting<E> setting, String[] names) {
        section(ctx, label, x, y);
        y += 11f;
        E[] values = setting.getValue().getDeclaringClass().getEnumConstants();
        float gap = 4f, w = (CONTROLS_W - gap * (values.length - 1)) / values.length;
        for (int i = 0; i < values.length; i++) {
            E v = values[i];
            float bx = x + i * (w + gap);
            boolean on = setting.getValue() == v;
            choice(ctx, mx, my, bx, y, w, 18f, i < names.length ? names[i] : v.name(), on, () -> setting.setValue(v));
        }
        return y + 18f + 10f;
    }

    private float toggle(DrawContext ctx, int mx, int my, float x, float y, String label, Setting<Boolean> setting) {
        boolean on = setting.getValue();
        boolean hov = Render2DEngine.isHovered(mx, my, x, y, CONTROLS_W, 18f);
        if (hov) Render2DEngine.drawRoundSmooth(ctx, x - 3f, y - 1f, CONTROLS_W + 6f, 20f, 5f, FIELD);
        FontRenderers.sf_medium.drawString(ctx, label, x, y + 5.5f, (on ? TEXT : TEXT_DIM).getRGB());
        float swX = x + CONTROLS_W - 22f, swY = y + 3f;
        Render2DEngine.drawRound(ctx, swX, swY, 22f, 12f, 6f, on ? ClickGUI.accent() : new Color(255, 255, 255, 34));
        Render2DEngine.drawRound(ctx, on ? swX + 11f : swX + 1f, swY + 1f, 10f, 10f, 5f, TEXT);
        hots.add(new Hot(x, y, CONTROLS_W, 18f, () -> setting.setValue(!setting.getValue())));
        return y + 18f + 8f;
    }

    private float swatches(DrawContext ctx, int mx, int my, float x, float y, String label,
                           Setting<thunder.hack.setting.impl.ColorSetting> setting) {
        section(ctx, label, x, y);
        y += 11f;
        float dot = 13f, step = 16.8f;
        int current = setting.getValue().getColor() & 0xFFFFFF;
        for (int i = 0; i < ColorPalette.SWATCHES.size(); i++) {
            Color c = ColorPalette.SWATCHES.get(i);
            float dx = x + i * step + 1.5f, dy = y + 1.5f;
            boolean on = (c.getRGB() & 0xFFFFFF) == current;
            boolean hov = Render2DEngine.isHovered(mx, my, dx - 1.5f, dy - 1.5f, dot + 3, dot + 3);
            if (on) Render2DEngine.drawRoundSmooth(ctx, dx - 2f, dy - 2f, dot + 4f, dot + 4f, (dot + 4f) / 2f, TEXT);
            Render2DEngine.drawRoundSmooth(ctx, dx, dy, dot, dot, dot / 2f, hov ? c.brighter() : c);
            final Color pick = c;
            hots.add(new Hot(dx - 1.5f, dy - 1.5f, dot + 3, dot + 3, () -> setting.getValue().setColor(pick.getRGB() | 0xFF000000)));
        }
        return y + dot + 3f + 10f;
    }

    private float slider(DrawContext ctx, int mx, int my, float x, float y, String label, Setting<Float> setting,
                         float min, float max, String fmt) {
        FontRenderers.sf_medium.drawString(ctx, label, x, y + 2f, TEXT_DIM.getRGB());
        String v = String.format(fmt, setting.getValue());
        FontRenderers.sf_medium_mini.drawString(ctx, v, x + CONTROLS_W - FontRenderers.sf_medium_mini.getStringWidth(v), y + 3f,
                TEXT_DIM.getRGB());
        float sy = y + 13f;
        float t = Math.clamp((setting.getValue() - min) / (max - min), 0f, 1f);
        Color acc = ClickGUI.accent();
        Render2DEngine.drawRoundSmooth(ctx, x, sy + 3f, CONTROLS_W, 4f, 2f, FIELD_HOVER);
        Render2DEngine.drawRoundSmooth(ctx, x, sy + 3f, Math.max(4f, CONTROLS_W * t), 4f, 2f, acc);
        Render2DEngine.drawRoundSmooth(ctx, x + CONTROLS_W * t - 5f, sy, 10f, 10f, 5f, TEXT);
        hots.add(new Hot(x - 4f, sy - 4f, CONTROLS_W + 8f, 18f, () -> {
            dragging = setting;
            dragX = x;
            dragW = CONTROLS_W;
            dragMin = min;
            dragMax = max;
        }));
        return sy + 10f + 12f;
    }

    private void setSlider(double mx) {
        float t = (float) Math.clamp((mx - dragX) / dragW, 0.0, 1.0);
        float v = dragMin + (dragMax - dragMin) * t;
        dragging.setValue(Math.round(v * 100f) / 100f);
    }

    private void section(DrawContext ctx, String label, float x, float y) {
        FontRenderers.sf_medium_mini.drawString(ctx, label, x, y, TEXT_MUTED.getRGB());
    }

    private void choice(DrawContext ctx, int mx, int my, float x, float y, float w, float h, String label, boolean selected, Runnable action) {
        boolean hov = Render2DEngine.isHovered(mx, my, x, y, w, h);
        Color acc = ClickGUI.accent();
        Render2DEngine.drawRoundSmooth(ctx, x, y, w, h, 5f, selected ? Render2DEngine.injectAlpha(acc, 80) : hov ? FIELD_HOVER : FIELD);
        float tw = FontRenderers.sf_medium_mini.getStringWidth(label);
        FontRenderers.sf_medium_mini.drawString(ctx, label, x + (w - tw) / 2f, y + h / 2f - 3f, (selected ? TEXT : TEXT_DIM).getRGB());
        hots.add(new Hot(x, y, w, h, action));
    }

    private void button(DrawContext ctx, int mx, int my, float x, float y, float w, float h, String label, boolean primary, Runnable action) {
        boolean hov = Render2DEngine.isHovered(mx, my, x, y, w, h);
        Color acc = ClickGUI.accent();
        Render2DEngine.drawRoundSmooth(ctx, x, y, w, h, 6f, primary ? (hov ? acc.brighter() : acc) : (hov ? FIELD_HOVER : FIELD));
        float tw = FontRenderers.sf_medium.getStringWidth(label);
        FontRenderers.sf_medium.drawString(ctx, label, x + (w - tw) / 2f, y + h / 2f - 3.5f, TEXT.getRGB());
        hots.add(new Hot(x, y, w, h, action));
    }

    @Override
    public boolean mouseClicked(net.minecraft.client.gui.Click click, boolean doubled) {
        if (click.button() != 0) return true;
        for (int i = hots.size() - 1; i >= 0; i--) {
            Hot h = hots.get(i);
            if (h.covers(click.x(), click.y())) {
                // controls scrolled out of the list's window do not take clicks
                boolean inControls = h.x() < (width - W) / 2f + 14f + CONTROLS_W + 8f;
                if (inControls && h.y() + h.h() > viewTop && (click.y() < viewTop || click.y() > viewTop + viewH)) continue;
                thunder.hack.utility.UiSounds.play(thunder.hack.utility.UiSounds.Sound.TAP, 0.5f);
                h.action().run();
                if (dragging != null) setSlider(click.x());
                return true;
            }
        }
        return true;
    }

    @Override
    public boolean mouseReleased(net.minecraft.client.gui.Click click) {
        dragging = null;
        return true;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontal, double vertical) {
        scrollTarget += (float) vertical * 22f;
        return true;
    }

    @Override
    public void close() {
        mc.setScreen(parent);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}
