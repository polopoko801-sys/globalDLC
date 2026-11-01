package thunder.hack.gui.clickui.impl;

import net.minecraft.client.gui.DrawContext;
import thunder.hack.ThunderHack;
import thunder.hack.features.modules.client.HudEditor;
import thunder.hack.gui.clickui.AbstractElement;
import thunder.hack.gui.font.FontRenderers;
import thunder.hack.setting.Setting;
import thunder.hack.setting.impl.ColorSetting;
import thunder.hack.utility.ColorPalette;
import thunder.hack.utility.render.Render2DEngine;
import thunder.hack.utility.render.animation.AnimationUtility;

import java.awt.*;

/**
 * A colour setting: its name and a preview pill; click it to open the picker underneath -
 * saturation/brightness field, hue and opacity sliders with round knobs, a row of quick colours, the hex code
 * and pill buttons (Copy, Paste, Rainbow, Reset). Opens and closes with an eased height.
 */
public class ColorPickerElement extends AbstractElement {
    // picker state, all 0..1 (alpha 0..255)
    private float hue, saturation, brightness;
    private int alpha;
    private boolean sbFocused, hueFocused, alphaFocused;
    private int lastSynced;

    private boolean extended;
    private float open;            // 0 closed .. 1 open, eased
    private float headerHover;
    private final float[] btnHover = new float[4];

    private final Setting colorSetting;

    // geometry (relative to the element), shared by render and click
    private static final float HEAD = 15f, FIELD_H = 46f, SWATCH_ROW = 12f, BTN_ROW = 13f, PAD = 6f;
    private static final float FULL = HEAD + 4f + FIELD_H + 6f + SWATCH_ROW + 4f + BTN_ROW + 5f;
    private static final String[] BTN = {"Copy", "Paste", "RB", "Reset"};

    public ColorSetting getColorSetting() {
        return (ColorSetting) colorSetting.getValue();
    }

    public ColorPickerElement(Setting setting) {
        super(setting);
        this.colorSetting = setting;
        syncFromSetting();
    }

    private void syncFromSetting() {
        Color c = getColorSetting().getColorObject();
        float[] hsb = Color.RGBtoHSB(c.getRed(), c.getGreen(), c.getBlue(), null);
        // keep the hue when the colour is grey/black - otherwise dragging to black resets the hue slider
        if (hsb[1] > 0.01f && hsb[2] > 0.01f) hue = hsb[0];
        saturation = hsb[1];
        brightness = hsb[2];
        alpha = getColorSetting().getAlpha();
        lastSynced = c.getRGB();
    }

    @Override
    public float getHeight() {
        return HEAD + (FULL - HEAD) * open;
    }

    /* ---------------------------------------------------------------- geometry */

    private float fieldX() { return x + PAD; }
    private float fieldY() { return y + HEAD + 4f; }
    private float fieldW() { return width - PAD * 2f - 26f; }
    private float hueX() { return fieldX() + fieldW() + 6f; }
    private float alphaX() { return hueX() + 10f; }
    private float swatchY() { return fieldY() + FIELD_H + 6f; }
    private float btnY() { return swatchY() + SWATCH_ROW + 4f; }

    private float btnW() { return (width - PAD * 2f - 3f * 4f) / 4f; }

    /* ---------------------------------------------------------------- render */

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        Color cur = getColorSetting().getColorObject();
        if (cur.getRGB() != lastSynced && !sbFocused && !hueFocused && !alphaFocused) syncFromSetting();

        open = AnimationUtility.fast(open, extended ? 1f : 0f, 14f);
        boolean headHov = Render2DEngine.isHovered(mouseX, mouseY, x, y, width, HEAD);
        headerHover = AnimationUtility.fast(headerHover, headHov ? 1f : 0f, 16f);

        // header: name + preview pill (glows in its own colour, grows a little on hover)
        FontRenderers.sf_medium_mini.drawString(ctx, setting.getName(), x + 6, y + 6, new Color(-1).getRGB());
        float pw = 18f + 2f * headerHover, ph = 8f + headerHover;
        float pX = x + width - 8f - pw, pY = y + (HEAD - ph) / 2f;
        Render2DEngine.drawBlurredShadow(ctx, pX, pY, pw, ph, 8, Render2DEngine.injectAlpha(cur, 160));
        checker(ctx, pX, pY, pw, ph);
        Render2DEngine.drawRound(ctx, pX, pY, pw, ph, ph / 2f, cur);
        // a chevron that turns as it opens
        float chx = pX - 8f, chy = y + HEAD / 2f;
        Color chev = new Color(255, 255, 255, (int) (110 + 100 * headerHover));
        float rot = open; // 0 = pointing right, 1 = down
        for (int k = -2; k <= 2; k++) {
            float t = Math.abs(k) / 2f;
            float dx = rot < 0.5f ? (1f - t) * 1.6f : k * 0.9f;
            float dy = rot < 0.5f ? k * 0.9f : (1f - t) * 1.6f - 0.8f;
            Render2DEngine.drawRound(ctx, chx + dx - 0.6f, chy + dy - 0.6f, 1.2f, 1.2f, 0.6f, chev);
        }

        if (open < 0.03f) return;
        Render2DEngine.addWindow(ctx, x, y + HEAD, x + width, y + getHeight(), 1.0);
        try {
            renderPicker(ctx, mouseX, mouseY);
        } finally {
            Render2DEngine.popWindow();
        }
    }

    private void renderPicker(DrawContext ctx, int mouseX, int mouseY) {
        float fx = fieldX(), fy = fieldY(), fw = fieldW(), fh = FIELD_H;

        // dragging
        if (sbFocused) {
            saturation = Math.clamp((mouseX - fx) / fw, 0f, 1f);
            brightness = 1f - Math.clamp((mouseY - fy) / fh, 0f, 1f);
            apply();
        }
        if (hueFocused) {
            hue = Math.clamp((mouseY - fy) / fh, 0f, 0.999f);
            apply();
        }
        if (alphaFocused) {
            alpha = Math.round(255 * (1f - Math.clamp((mouseY - fy) / fh, 0f, 1f)));
            apply();
        }

        // saturation / brightness field: white -> hue across, transparent -> black down; framed and rounded
        Render2DEngine.drawRound(ctx, fx - 1f, fy - 1f, fw + 2f, fh + 2f, 4f, new Color(255, 255, 255, 30));
        Render2DEngine.horizontalGradient(ctx, fx, fy, fx + fw, fy + fh, Color.WHITE, Color.getHSBColor(hue, 1f, 1f));
        Render2DEngine.verticalGradient(ctx, fx, fy, fx + fw, fy + fh, new Color(0, 0, 0, 0), Color.BLACK);
        float kx = fx + fw * saturation, ky = fy + fh * (1f - brightness);
        Color solid = Color.getHSBColor(hue, saturation, brightness);
        Render2DEngine.drawRound(ctx, kx - 4f, ky - 4f, 8f, 8f, 4f, new Color(0, 0, 0, 90));
        Render2DEngine.drawRound(ctx, kx - 3.5f, ky - 3.5f, 7f, 7f, 3.5f, Color.WHITE);
        Render2DEngine.drawRound(ctx, kx - 2.2f, ky - 2.2f, 4.4f, 4.4f, 2.2f, solid);

        // hue slider (vertical rainbow pill)
        float hx = hueX();
        for (float i = 0; i < fh; i += 1f)
            Render2DEngine.drawRect(ctx, hx, fy + i, 6f, 1.05f, Color.getHSBColor(i / fh, 1f, 1f));
        knob(ctx, hx - 1.5f, fy + fh * hue, 9f, Color.getHSBColor(hue, 1f, 1f));

        // opacity slider over a checkerboard
        float ax = alphaX();
        checker(ctx, ax, fy, 6f, fh);
        Render2DEngine.verticalGradient(ctx, ax, fy, ax + 6f, fy + fh,
                new Color(solid.getRed(), solid.getGreen(), solid.getBlue(), 255), new Color(solid.getRed(), solid.getGreen(), solid.getBlue(), 0));
        knob(ctx, ax - 1.5f, fy + fh * (1f - alpha / 255f), 9f, new Color(solid.getRed(), solid.getGreen(), solid.getBlue(), Math.max(60, alpha)));

        // quick colours + hex code
        float sy = swatchY();
        float sx = fx;
        int active = getColorSetting().getColorObject().getRGB() | 0xFF000000;
        for (int i = 0; i < ColorPalette.SWATCHES.size(); i++) {
            Color c = ColorPalette.SWATCHES.get(i);
            if (sx + 9f > x + width - 44f) break;
            boolean hov = Render2DEngine.isHovered(mouseX, mouseY, sx, sy + 1f, 9f, 9f);
            float g = hov ? 1f : 0f;
            if ((c.getRGB() | 0xFF000000) == active)
                Render2DEngine.drawRound(ctx, sx - 1.5f, sy - 0.5f, 12f, 12f, 6f, Color.WHITE);
            Render2DEngine.drawRound(ctx, sx - g * 0.8f, sy + 1f - g * 0.8f, 9f + g * 1.6f, 9f + g * 1.6f, (9f + g * 1.6f) / 2f, c);
            sx += 11.5f;
        }
        String hex = String.format("#%06X", getColorSetting().getColorObject().getRGB() & 0xFFFFFF);
        float hw = FontRenderers.sf_medium_mini.getStringWidth(hex) + 8f;
        Render2DEngine.drawRound(ctx, x + width - PAD - hw, sy, hw, 11f, 3.5f, new Color(255, 255, 255, 14));
        FontRenderers.sf_medium_mini.drawString(ctx, hex, x + width - PAD - hw + 4f, sy + 3.5f, new Color(0xC8C8D2).getRGB());

        // pill buttons
        float by = btnY(), bw = btnW();
        Color acc = HudEditor.getColor(0);
        for (int i = 0; i < 4; i++) {
            float bx = fx + i * (bw + 4f);
            boolean hov = Render2DEngine.isHovered(mouseX, mouseY, bx, by, bw, BTN_ROW);
            btnHover[i] = AnimationUtility.fast(btnHover[i], hov ? 1f : 0f, 16f);
            boolean on = i == 2 && getColorSetting().isRainbow();
            Color bg = on ? Render2DEngine.injectAlpha(acc, 150)
                    : Render2DEngine.interpolateColorC(new Color(255, 255, 255, 16), new Color(255, 255, 255, 34), btnHover[i]);
            Render2DEngine.drawRound(ctx, bx, by, bw, BTN_ROW, BTN_ROW / 2f, bg);
            if (i == 1) // Paste shows what it will paste
                Render2DEngine.drawRound(ctx, bx + 3f, by + 3.5f, 6f, 6f, 3f, ThunderHack.copy_color);
            if (i == 3) // Reset shows the default it goes back to
                Render2DEngine.drawRound(ctx, bx + 3f, by + 3.5f, 6f, 6f, 3f, new Color(getColorSetting().getDefaultColor(), true));
            String label = BTN[i];
            float lw = FontRenderers.sf_medium_mini.getStringWidth(label);
            float lx = bx + (bw - lw) / 2f + (i == 1 || i == 3 ? 3f : 0f);
            Color tc = Render2DEngine.interpolateColorC(new Color(0xB4B4C0), Color.WHITE, Math.max(btnHover[i], on ? 1f : 0f));
            FontRenderers.sf_medium_mini.drawString(ctx, label, lx, by + 4.5f, tc.getRGB());
        }
    }

    /** A round slider knob: white with a dark edge, filled with the value's colour. */
    private static void knob(DrawContext ctx, float kx, float ky, float w, Color fill) {
        Render2DEngine.drawRound(ctx, kx - 0.5f, ky - 3f, w + 1f, 6f, 3f, new Color(0, 0, 0, 100));
        Render2DEngine.drawRound(ctx, kx, ky - 2.5f, w, 5f, 2.5f, Color.WHITE);
        Render2DEngine.drawRound(ctx, kx + 1.5f, ky - 1.2f, w - 3f, 2.4f, 1.2f, fill);
    }

    /** Grey checkerboard under anything with transparency. */
    private static void checker(DrawContext ctx, float cx, float cy, float w, float h) {
        float s = 3f;
        for (float yy = 0; yy < h; yy += s)
            for (float xx = 0; xx < w; xx += s) {
                boolean dark = ((int) (xx / s) + (int) (yy / s)) % 2 == 0;
                Render2DEngine.drawRect(ctx, cx + xx, cy + yy, Math.min(s, w - xx), Math.min(s, h - yy),
                        dark ? new Color(70, 70, 78) : new Color(110, 110, 118));
            }
    }

    private void apply() {
        Color c = Color.getHSBColor(hue, saturation, brightness);
        int argb = new Color(c.getRed(), c.getGreen(), c.getBlue(), alpha).getRGB();
        getColorSetting().setColor(argb);
        lastSynced = getColorSetting().getColorObject().getRGB();
    }

    /* ---------------------------------------------------------------- input */

    @Override
    public void mouseClicked(int mouseX, int mouseY, int button) {
        if (Render2DEngine.isHovered(mouseX, mouseY, x, y, width, HEAD)) {
            extended = !extended;
            return;
        }
        if (!extended || open < 0.9f || button != 0) return;

        float fx = fieldX(), fy = fieldY(), fw = fieldW();
        if (Render2DEngine.isHovered(mouseX, mouseY, fx - 2f, fy - 2f, fw + 4f, FIELD_H + 4f)) {
            sbFocused = true;
            return;
        }
        if (Render2DEngine.isHovered(mouseX, mouseY, hueX() - 2f, fy - 2f, 10f, FIELD_H + 4f)) {
            hueFocused = true;
            return;
        }
        if (Render2DEngine.isHovered(mouseX, mouseY, alphaX() - 2f, fy - 2f, 10f, FIELD_H + 4f)) {
            alphaFocused = true;
            return;
        }

        // quick colours keep this setting's opacity
        float sx = fx, sy = swatchY();
        for (int i = 0; i < ColorPalette.SWATCHES.size(); i++) {
            if (sx + 9f > x + width - 44f) break;
            if (Render2DEngine.isHovered(mouseX, mouseY, sx, sy + 1f, 9f, 9f)) {
                Color c = ColorPalette.SWATCHES.get(i);
                getColorSetting().setRainbow(false);
                getColorSetting().setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), getColorSetting().getAlpha()).getRGB());
                syncFromSetting();
                return;
            }
            sx += 11.5f;
        }

        float by = btnY(), bw = btnW();
        for (int i = 0; i < 4; i++) {
            float bx = fx + i * (bw + 4f);
            if (!Render2DEngine.isHovered(mouseX, mouseY, bx, by, bw, BTN_ROW)) continue;
            switch (i) {
                case 0 -> ThunderHack.copy_color = getColorSetting().getColorObject();
                case 1 -> getColorSetting().setColor(ThunderHack.copy_color.getRGB());
                case 2 -> getColorSetting().setRainbow(!getColorSetting().isRainbow());
                case 3 -> {
                    getColorSetting().setRainbow(false);
                    getColorSetting().setDefault();
                }
            }
            syncFromSetting();
            return;
        }
    }

    @Override
    public void mouseReleased(int mouseX, int mouseY, int button) {
        sbFocused = hueFocused = alphaFocused = false;
    }

    @Override
    public void onClose() {
        sbFocused = hueFocused = alphaFocused = false;
    }
}
