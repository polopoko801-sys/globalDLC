package thunder.hack.features.modules.render;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.item.ItemDisplayContext;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Arm;
import thunder.hack.features.modules.Module;
import thunder.hack.features.modules.client.HudEditor;
import thunder.hack.setting.Setting;
import thunder.hack.setting.impl.ColorSetting;
import thunder.hack.utility.render.Render2DEngine;
import thunder.hack.utility.render.TextureStorage;

import java.awt.Color;

/**
 * An outline-and-glow shader for the item you hold.
 *
 * <p>How it is drawn (see {@code MixinHeldItemRenderer} and {@code MixinItemRenderer}): the item model is
 * re-drawn a handful of times with tiny offsets, each time painted in one flat glowing colour - the overlapping
 * offset copies form a bright border around the item (the outline). The item itself is then drawn lit to
 * full-bright and tinted towards the same colour, so it reads as a glowing blade inside its own outline.
 *
 * <p>Purely visual - it changes nothing about the item, only how it is rendered.
 */
public class ItemShader extends Module {
    public ItemShader() {
        super("ItemShader", Category.RENDER);
    }

    public enum Apply {Held, Everywhere}

    public enum Quality {Low, Medium, High, Ultra}

    public enum Style {Outline, Legacy, Mist}

    /** The order is the shader's pattern index (item_outline_v.fsh) - append only. */
    public enum PlasmaType {Neon, Liquid, Electric, Galaxy, Cells, Waves, Fire, Aurora, Lava, Frost, Rainbow, Hex, Storm, Vortex, Glitter, Toxic, Ocean, Crystal, Ink, Pulse, Circuit, Holo, PlasmaBall, Halftone}

    // Outline = the real shader outline (Rockstar outline.frag, see ItemOutlineRenderer). Legacy = the same outline
    // with the item filled by the animated plasma of the Custom Sky. The old model-copy tricks are gone: they drew
    // scaled copies of the item, which is exactly what showed up as a doubled sword.
    private final Setting<Style> style = new Setting<>("Style", Style.Outline);
    private final Setting<Integer> outlineWidth = new Setting<>("OutlineWidth", 3, 1, 12, v -> !style.is(Style.Mist));
    /** Mist: how far the haze reaches outside the item, in real pixels. */
    private final Setting<Integer> mistSize = new Setting<>("MistSize", 26, 6, 64, v -> style.is(Style.Mist));
    private final Setting<Float> mistStrength = new Setting<>("MistStrength", 0.85f, 0.1f, 1.5f, v -> style.is(Style.Mist));
    private final Setting<ColorSetting> mistColor = new Setting<>("MistColor", new ColorSetting(0xFF3CF0D2), v -> style.is(Style.Mist));
    /** Mist: seconds the wave lingers where the item has been. Above 0 only that wave is drawn (no glow around the still item); 0 = the plain glow. */
    private final Setting<Float> mistTrail = new Setting<>("MistTrail", 0f, 0f, 1.5f, v -> style.is(Style.Mist));
    private final Setting<Boolean> rainbow = new Setting<>("Rainbow", false);
    private final Setting<Float> rainbowSpeed = new Setting<>("RainbowSpeed", 0.5f, 0f, 5f, v -> rainbow.getValue());
    private final Setting<Float> rainbowStrength = new Setting<>("RainbowStrength", 0.25f, 0.05f, 2f, v -> rainbow.getValue());
    private final Setting<Float> saturation = new Setting<>("Saturation", 0.5f, 0f, 1f, v -> rainbow.getValue());
    private final Setting<Integer> fillAlpha = new Setting<>("FillAlpha", 0, 0, 255, v -> style.is(Style.Outline));

    // Legacy: plasma inside the outline
    /** Which pattern fills the item. All of them use Color / PlasmaColor2 / PlasmaColor3 as their palette. */
    private final Setting<PlasmaType> plasmaType = new Setting<>("PlasmaType", PlasmaType.Neon, v -> style.is(Style.Legacy));
    private final Setting<Integer> plasmaAlpha =new Setting<>("PlasmaAlpha", 210, 20, 255, v -> style.is(Style.Legacy));
    private final Setting<Float> plasmaSpeed = new Setting<>("PlasmaSpeed", 1f, 0.05f, 4f, v -> style.is(Style.Legacy));
    private final Setting<Float> plasmaScale = new Setting<>("PlasmaScale", 1f, 0.2f, 4f, v -> style.is(Style.Legacy));
    private final Setting<Float> plasmaIntensity = new Setting<>("PlasmaIntensity", 1f, 0.2f, 2f, v -> style.is(Style.Legacy));
    private final Setting<ColorSetting> plasmaColor2 = new Setting<>("PlasmaColor2", new ColorSetting(0xFF1EC8FF), v -> style.is(Style.Legacy));
    private final Setting<ColorSetting> plasmaColor3 = new Setting<>("PlasmaColor3", new ColorSetting(0xFFFF4FD8), v -> style.is(Style.Legacy));
    private float plasmaPhase;
    private long plasmaLast;

    private final Setting<Apply> apply = new Setting<>("ApplyTo", Apply.Held, v -> style.is(Style.Legacy));
    private final Setting<ColorSetting> color = new Setting<>("Color", new ColorSetting(new Color(0xC85CFF)), v -> !gradientOn());
    private final Setting<Boolean> gradient = new Setting<>("Gradient", false);
    private final Setting<Float> gradientSpeed = new Setting<>("GradientSpeed", 1f, 0.1f, 5f, v -> gradient.getValue());
    // The neon AURA from scaled model copies. OFF by default: the item render only supports MULTIPLY (setShaderColor),
    // not additive, so the copies come out as a dark translucent halo rather than a glow (confirmed in-game). A real
    // additive glow needs a custom emissive pipeline - left as an opt-in until that exists.
    private final Setting<Boolean> outline = new Setting<>("Glow", false);
    private final Setting<Quality> quality = new Setting<>("Quality", Quality.High, v -> outline.getValue());
    private final Setting<Float> glowRadius = new Setting<>("GlowRadius", 0.10f, 0.02f, 0.3f, v -> outline.getValue());
    private final Setting<Integer> glowAlpha = new Setting<>("GlowStrength", 170, 30, 255, v -> outline.getValue());
    private final Setting<Boolean> pulse = new Setting<>("Pulse", true, v -> outline.getValue());
    private final Setting<Float> pulseSpeed = new Setting<>("PulseSpeed", 1f, 0.2f, 4f, v -> outline.getValue() && pulse.getValue());
    // the inner tint that recolours the item itself (works for GUI/world items via MixinItemRenderer)
    private final Setting<Boolean> fillGlow = new Setting<>("FillGlow", true);
    private final Setting<Float> fillStrength = new Setting<>("FillStrength", 0.8f, 0f, 1f, v -> fillGlow.getValue());

    // The HUD glow behind the held item. This is the part that ALWAYS shows: a 2D additive glow drawn where the
    // first-person item sits. The deferred item render can't be recoloured reliably on 1.21.11, so the visible
    // ItemShader effect lives here instead.
    private final Setting<Boolean> hudGlow = new Setting<>("HeldGlow", true);
    private final Setting<Float> hudSize = new Setting<>("GlowSize", 170f, 30f, 400f, v -> hudGlow.getValue());
    private final Setting<Integer> hudOpacity = new Setting<>("GlowOpacity", 210, 20, 255, v -> hudGlow.getValue());
    private final Setting<Float> offX = new Setting<>("GlowOffsetX", 0f, -1f, 1f, v -> hudGlow.getValue());
    private final Setting<Float> offY = new Setting<>("GlowOffsetY", 0f, -1f, 1f, v -> hudGlow.getValue());

    /** Set by MixinHeldItemRenderer each frame: the item's real screen position + apparent size. */
    public static float itemScreenX, itemScreenY, itemScreenSize;
    public static boolean itemScreenValid;

    /**
     * Prawdziwy glow w 2D. Kopie modelu itemu mnożą się tylko (MULTIPLY), więc nigdy nie dały prawdziwej
     * poświaty - zamiast tego rysujemy sprite'y halo w ekranowej przestrzeni tam, gdzie item faktycznie jest.
     *
     * <p>Pozycję bierzemy z rzutu, który MixinHeldItemRenderer liczy z prawdziwej macierzy ręki; jak rzut
     * jest niedostępny (klatka bez ręki, brak itemu), zostaje kotwica - item pierwszej osoby siedzi
     * zawsze w tym samym miejscu ekranu, więc awaryjnie trafia w to samo miejsce.
     */
    public void onRender2D(DrawContext context) {
        itemScreenValid = false; // odświeżany przez mixin przy każdym renderze ręki
        if (!thunder.hack.utility.render.ItemOutlineRenderer.isBroken()) {
            // an open screen (ClickGUI, inventory, chat...) keeps the outline: the HUD pass still runs behind it
            if (mc.player == null || mc.player.isSubmergedInWater() || mc.player.isOnFire()) return;
            float phase = (float) (System.currentTimeMillis() / 1000.0 * rainbowSpeed.getValue());
            boolean plasma = style.is(Style.Legacy);
            // plasma clock: real frame time * speed, so the speed slider never makes the pattern jump
            long now = System.nanoTime();
            if (plasmaLast != 0) plasmaPhase += Math.min(0.1f, (now - plasmaLast) / 1e9f) * plasmaSpeed.getValue();
            plasmaLast = now;
            if (plasmaPhase > 10000f) plasmaPhase -= 10000f;
            boolean mist = style.is(Style.Mist);
            // Mist leaves the item itself alone (fill 0) and only hazes the space around it
            thunder.hack.utility.render.ItemOutlineRenderer.render(mist ? mistColor.getValue().getColorObject() : glowColor(),
                    // Mist: the fire reaches 1.5x MistSize out, so it reads clearly around the item
                    mist ? Math.round(mistSize.getValue() * 1.5f) : outlineWidth.getValue(), rainbow.getValue(), phase,
                    saturation.getValue(), rainbowStrength.getValue(),
                    mist ? 0f : (plasma ? plasmaAlpha.getValue() : fillAlpha.getValue()) / 255f,
                    plasma ? plasmaType.getValue().ordinal() + 1 : 0, plasmaPhase, plasmaScale.getValue(), plasmaIntensity.getValue(),
                    plasmaColor2.getValue().getColorObject(), plasmaColor3.getValue().getColorObject(),
                    // the trail fades exponentially, so it is given twice the time to stay clearly visible for MistTrail
                    mist, mistStrength.getValue(), mistTrail.getValue() * 2f);
            return;
        }
        if (!hudGlow.getValue() || mc.player == null || mc.options.hudHidden || mc.currentScreen != null) return;
        if (mc.player.getMainHandStack().isEmpty()) return;

        float sw = mc.getWindow().getScaledWidth(), sh = mc.getWindow().getScaledHeight();
        boolean right = mc.player.getMainArm() == Arm.RIGHT;

        float cx, cy, size;
        if (itemScreenValid) {
            // pozycja z rzutu, GlowSize jako skalowanie względem domyślnych 170px
            cx = itemScreenX + offX.getValue() * 40f;
            cy = itemScreenY + offY.getValue() * 40f;
            size = itemScreenSize * (hudSize.getValue() / 170f);
        } else {
            cx = (right ? sw * 0.84f : sw * 0.16f) + offX.getValue() * sw * 0.25f;
            cy = sh * 0.60f + offY.getValue() * sh * 0.25f;
            size = hudSize.getValue();
        }

        float p = 1f;
        if (pulse.getValue())
            p = 0.8f + 0.2f * (float) ((Math.sin(System.currentTimeMillis() / 1000.0 * pulseSpeed.getValue() * Math.PI) + 1) / 2);

        // Warstwy od najszerszej (przygaszona) do najciaśniejszej (najjaśniejsza): to daje miękkie
        // przejście zamiast jednego przygaszonego krążka, a nastepnie twarde białe serce w środku.
        int rings = Math.max(2, glowRings());
        for (int i = rings; i >= 1; i--) {
            float f = (i - 1) / Math.max(1f, rings - 1f);
            float s = size * (0.5f + 0.8f * f);
            float fall = 1f - f * 0.7f;
            Color c = Render2DEngine.injectAlpha(glowColor(),
                    Math.round(hudOpacity.getValue() * p * fall * 1.6f / rings));
            Render2DEngine.renderGradientTexture(context, TextureStorage.fxHalo,
                    cx - s / 2f, cy - s / 2f, s, s, 0, 0, 256, 256, 256, 256, c, c, c, c, true);
        }

        float core = size * 0.3f;
        Color coreColor = Render2DEngine.injectAlpha(Color.WHITE, Math.round(190f * p));
        Render2DEngine.renderGradientTexture(context, TextureStorage.fxHalo,
                cx - core / 2f, cy - core / 2f, core, core, 0, 0, 256, 256, 256, 256,
                coreColor, coreColor, coreColor, coreColor, true);
    }

    /** The old model-copy tricks are retired (they doubled the item); both styles go through the outline shader. */
    private boolean legacy() {
        return false;
    }

    /** Whether the 3D hook should snapshot the frame before the hand (outline style only). */
    public boolean wantsOutline() {
        return isEnabled() && !thunder.hack.utility.render.ItemOutlineRenderer.isBroken();
    }

    private boolean gradientOn() {
        return gradient.getValue();
    }

    /** Alpha used for the current solid (glow) pass, set by beginSolid. */
    private int solidAlpha = 255;

    /** True while the extra silhouette passes are being drawn, so the model comes out flat glow colour. */
    private boolean solidPass;

    // Shape glow: scaled tinted copies of the real model behind it = a glow hugging the item silhouette (teal look).
    private final Setting<Boolean> shapeGlow = new Setting<>("ShapeGlow", true);
    private final Setting<Float> shapeRadius = new Setting<>("ShapeRadius", 0.12f, 0.03f, 0.4f, v -> shapeGlow.getValue());
    private final Setting<Integer> shapeAlpha = new Setting<>("ShapeStrength", 180, 40, 255, v -> shapeGlow.getValue());

    public boolean shapeGlowOn() {
        return isEnabled() && legacy() && shapeGlow.getValue();
    }

    public float shapeRadius() {
        return shapeRadius.getValue();
    }

    public int shapeAlpha() {
        return shapeAlpha.getValue();
    }

    public boolean outlineEnabled() {
        return isEnabled() && legacy() && outline.getValue();
    }

    /** How many stacked aura rings - more = smoother/softer glow, by Quality. */
    public int glowRings() {
        return switch (quality.getValue()) {
            case Low -> 2;
            case Medium -> 3;
            case High -> 5;
            case Ultra -> 8;
        };
    }

    public float glowRadiusBlocks() {
        return glowRadius.getValue();
    }

    public int glowPeakAlpha() {
        float p = 1f;
        if (pulse.getValue()) {
            // 0.85..1.0 breathing, frame-rate independent (wall clock)
            p = 0.85f + 0.15f * (float) ((Math.sin(System.currentTimeMillis() / 1000.0 * pulseSpeed.getValue() * Math.PI) + 1) / 2);
        }
        return Math.round(glowAlpha.getValue() * p);
    }

    public void beginSolid(int alpha) {
        solidPass = true;
        solidAlpha = alpha;
    }

    public boolean appliesTo(ItemDisplayContext ctx) {
        if (!isEnabled()) return false;
        if (apply.getValue() == Apply.Everywhere) return true;
        return ctx == ItemDisplayContext.FIRST_PERSON_LEFT_HAND
                || ctx == ItemDisplayContext.FIRST_PERSON_RIGHT_HAND
                || ctx == ItemDisplayContext.THIRD_PERSON_LEFT_HAND
                || ctx == ItemDisplayContext.THIRD_PERSON_RIGHT_HAND;
    }

    public boolean fillGlowEnabled() {
        return isEnabled() && legacy() && fillGlow.getValue();
    }

    /** Inner glow = tint the real item towards the colour (the "aura inside"). Reuses the FillGlow settings. */
    public boolean innerGlowEnabled() {
        return isEnabled() && legacy() && fillGlow.getValue();
    }

    public float innerStrength() {
        return fillStrength.getValue();
    }

    public void endSolid() {
        solidPass = false;
    }

    public boolean isSolidPass() {
        return solidPass;
    }

    /** The glow colour this frame - flat colour, or a point on the moving accent gradient. */
    public Color glowColor() {
        if (!gradient.getValue()) return color.getValue().getColorObject();
        int phase = (int) ((System.currentTimeMillis() * 0.05 * gradientSpeed.getValue()) % 360);
        return HudEditor.getColor(phase);
    }

    /** The flat ARGB the silhouette passes paint the whole model with, at the current ring's alpha. */
    public int solidArgb() {
        Color c = glowColor();
        return ((solidAlpha & 0xFF) << 24) | (c.getRed() << 16) | (c.getGreen() << 8) | c.getBlue();
    }

    /**
     * Recolours one baked-quad colour of the item towards the glow. The OLD version multiplied, so a white/bright
     * texel stayed white (k=1 did almost nothing on a steel blade - "it does nothing"). This LERPs the item toward
     * the glow colour modulated by the texel's brightness: the blade keeps its shading but becomes a glowing
     * coloured/chroma item, which is actually visible.
     */
    public int fill(int argb) {
        Color c = glowColor();
        float k = fillStrength.getValue();
        int a = argb >>> 24;
        int r = (argb >> 16) & 0xFF, g = (argb >> 8) & 0xFF, b = argb & 0xFF;
        // brightness of this texel (keeps the model's own light/shadow), lifted so dark parts still take colour
        float lum = Math.max(0.35f, (0.299f * r + 0.587f * g + 0.114f * b) / 255f);
        int tr = Math.round(c.getRed() * lum), tg = Math.round(c.getGreen() * lum), tb = Math.round(c.getBlue() * lum);
        r = Math.round(r + (tr - r) * k);
        g = Math.round(g + (tg - g) * k);
        b = Math.round(b + (tb - b) * k);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }
}
