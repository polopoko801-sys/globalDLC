package thunder.hack.features.modules.render;

import org.joml.Matrix4f;
import thunder.hack.features.modules.Module;
import thunder.hack.setting.Setting;
import thunder.hack.setting.impl.ColorSetting;
import thunder.hack.utility.render.CustomSkyRenderer;

import java.awt.Color;

/**
 * Animowane niebo z shadera GPU (plazma, mgławica, zorza, galaktyka...). Cała praca jest w
 * {@code core/custom_sky.fsh}; tu są tylko ustawienia i czas.
 *
 * <p>Czas animacji to faza sumowana z realnego czasu klatki razy prędkość - zmiana suwaka Speed nie
 * przeskakuje wzoru, a przy 30 i 300 FPS wszystko płynie tak samo. Nic tu nie zmienia pogody, czasu świata
 * ani niczego po stronie serwera: shader maluje tylko piksele nieba, a po wyłączeniu modułu po prostu
 * przestaje, więc zostaje zwykłe niebo.
 */
public class CustomSky extends Module {
    public CustomSky() {
        super("CustomSky", Category.RENDER);
    }

    public enum SkyMode {Vanilla, Plasma, RainbowPlasma, Void, Nebula, Aurora, EnergyWaves, CyberSky, Galaxy,
        Sunset, Inferno, Storm, BlackHole, Synthwave, Crystal, Warp, Ocean, Daydream, Custom}

    public enum Style {Plasma, Void, Nebula, Aurora, EnergyWaves, CyberSky, Galaxy,
        Sunset, Inferno, Storm, BlackHole, Synthwave, Crystal, Warp, Ocean, Daydream}

    public enum ColorMode {Single, Dual, Gradient, Rainbow}

    public enum Direction {East, West, North, South, Up, Down, Swirl}

    public enum Quality {High, Low}

    public final Setting<SkyMode> skyMode = new Setting<>("Sky Mode", SkyMode.Plasma);
    private final Setting<Style> customStyle = new Setting<>("Custom Style", Style.Plasma, v -> skyMode.getValue() == SkyMode.Custom);

    private final Setting<Float> speed = new Setting<>("Plasma Speed", 1f, 0.05f, 4f);
    private final Setting<Float> scale = new Setting<>("Plasma Scale", 1.6f, 0.3f, 6f);
    private final Setting<Integer> complexity = new Setting<>("Plasma Complexity", 4, 1, 8);
    private final Setting<Float> intensity = new Setting<>("Plasma Intensity", 1f, 0.1f, 2f);
    private final Setting<Float> distortion = new Setting<>("Plasma Distortion", 0.8f, 0f, 2f);
    private final Setting<Float> glow = new Setting<>("Glow Strength", 0.6f, 0f, 2f);
    private final Setting<Float> noiseDetail = new Setting<>("Noise Detail", 0.5f, 0f, 1f);

    private final Setting<ColorMode> colorMode = new Setting<>("Color Mode", ColorMode.Gradient, v -> usesUserColors());
    private final Setting<ColorSetting> primary = new Setting<>("Primary Color", new ColorSetting(0xFF7A3CFF), v -> usesUserColors());
    private final Setting<ColorSetting> secondary = new Setting<>("Secondary Color", new ColorSetting(0xFF1EC8FF),
            v -> usesUserColors() && colorMode.getValue() != ColorMode.Single && colorMode.getValue() != ColorMode.Rainbow);
    private final Setting<ColorSetting> accent = new Setting<>("Accent Color", new ColorSetting(0xFFFF4FD8),
            v -> usesUserColors() && colorMode.getValue() == ColorMode.Gradient);

    private final Setting<Direction> direction = new Setting<>("Animation Direction", Direction.East);
    private final Setting<Float> stars = new Setting<>("Stars", 1f, 0f, 3f);
    private final Setting<Integer> opacity = new Setting<>("Opacity", 255, 30, 255);
    private final Setting<Quality> quality = new Setting<>("Quality", Quality.High);
    private final Setting<Boolean> skyDimensionsOnly = new Setting<>("Only Sky Dimensions", true);

    private float phase;
    private long lastNanos;
    private boolean warned;

    private boolean usesUserColors() {
        return skyMode.getValue() == SkyMode.Plasma || skyMode.getValue() == SkyMode.Custom;
    }

    @Override
    public void onEnable() {
        lastNanos = 0;
        warned = false;
        // a shader that failed earlier gets another chance (e.g. after a resource reload)
        CustomSkyRenderer.resetFailure();
    }

    /**
     * True while this module is painting the sky - then vanilla clouds are left out of the frame
     * (MixinWorldRendererSky), so they do not float across the shader sky.
     */
    public boolean ownsSky() {
        if (!isEnabled() || fullNullCheck() || skyMode.getValue() == SkyMode.Vanilla || CustomSkyRenderer.isBroken()) return false;
        return !skyDimensionsOnly.getValue() || mc.world.getDimension().hasSkyLight();
    }

    /** Called from the 3D hook with the frame's world projection and camera rotation. */
    public void renderSky(Matrix4f proj, Matrix4f view) {
        if (!isEnabled() || fullNullCheck() || skyMode.getValue() == SkyMode.Vanilla) return;
        if (skyDimensionsOnly.getValue() && !mc.world.getDimension().hasSkyLight()) return;

        long now = System.nanoTime();
        float dt = lastNanos == 0 ? 0f : Math.min(0.1f, (now - lastNanos) / 1_000_000_000f);
        lastNanos = now;
        phase += dt * speed.getValue();
        if (phase > 10000f) phase -= 10000f;

        int style;
        int cMode = colorMode.getValue().ordinal();
        Color p = primary.getValue().getColorObject(), s = secondary.getValue().getColorObject(), a = accent.getValue().getColorObject();
        switch (skyMode.getValue()) {
            case RainbowPlasma -> { style = 1; cMode = 3; }
            case Void -> { style = 3; cMode = 2; p = new Color(70, 20, 160); s = new Color(30, 60, 220); a = new Color(170, 90, 255); }
            case Nebula -> { style = 4; cMode = 2; p = new Color(220, 50, 160); s = new Color(60, 70, 230); a = new Color(40, 210, 200); }
            case Aurora -> { style = 5; cMode = 2; p = new Color(40, 255, 140); s = new Color(30, 200, 230); a = new Color(170, 80, 255); }
            case EnergyWaves -> { style = 6; cMode = 2; p = new Color(20, 120, 255); s = new Color(60, 230, 255); a = new Color(235, 245, 255); }
            case CyberSky -> { style = 7; cMode = 2; p = new Color(255, 40, 170); s = new Color(30, 230, 255); a = new Color(255, 230, 80); }
            case Galaxy -> { style = 8; cMode = 2; p = new Color(60, 90, 220); s = new Color(150, 80, 230); a = new Color(255, 215, 150); }
            case Sunset -> { style = 9; cMode = 2; p = new Color(255, 120, 40); s = new Color(255, 70, 120); a = new Color(40, 40, 120); }
            case Inferno -> { style = 10; cMode = 2; p = new Color(120, 10, 0); s = new Color(255, 110, 10); a = new Color(255, 230, 120); }
            case Storm -> { style = 12; cMode = 2; p = new Color(70, 80, 110); s = new Color(120, 140, 220); a = new Color(220, 210, 255); }
            case BlackHole -> { style = 13; cMode = 2; p = new Color(180, 50, 10); s = new Color(255, 150, 50); a = new Color(255, 240, 200); }
            case Synthwave -> { style = 14; cMode = 2; p = new Color(255, 40, 160); s = new Color(255, 140, 40); a = new Color(255, 230, 90); }
            case Crystal -> { style = 15; cMode = 2; p = new Color(60, 220, 255); s = new Color(170, 90, 255); a = new Color(255, 120, 220); }
            case Warp -> { style = 16; cMode = 2; p = new Color(90, 140, 255); s = new Color(120, 240, 255); a = new Color(255, 255, 255); }
            case Ocean -> { style = 17; cMode = 2; p = new Color(0, 30, 70); s = new Color(20, 140, 180); a = new Color(170, 255, 240); }
            case Daydream -> { style = 18; cMode = 2; p = new Color(60, 130, 240); s = new Color(190, 220, 255); a = new Color(170, 180, 210); }
            case Custom -> style = switch (customStyle.getValue()) {
                case Plasma -> 1;
                case Void -> 3;
                case Nebula -> 4;
                case Aurora -> 5;
                case EnergyWaves -> 6;
                case CyberSky -> 7;
                case Galaxy -> 8;
                case Sunset -> 9;
                case Inferno -> 10;
                case Storm -> 12;
                case BlackHole -> 13;
                case Synthwave -> 14;
                case Crystal -> 15;
                case Warp -> 16;
                case Ocean -> 17;
                case Daydream -> 18;
            };
            default -> style = 1;
        }

        float fx = 0, fy = 0, fz = 0;
        switch (direction.getValue()) {
            case East -> fx = 1;
            case West -> fx = -1;
            case North -> fz = -1;
            case South -> fz = 1;
            case Up -> fy = 1;
            case Down -> fy = -1;
            case Swirl -> fy = 0.25f;
        }

        CustomSkyRenderer.render(new CustomSkyRenderer.Params(style, cMode, quality.getValue() == Quality.High,
                direction.getValue() == Direction.Swirl, phase, scale.getValue(), complexity.getValue(),
                intensity.getValue(), distortion.getValue(), glow.getValue(), noiseDetail.getValue(),
                opacity.getValue() / 255f, fx, fy, fz, stars.getValue(), p, s, a), proj, view);

        if (CustomSkyRenderer.isBroken() && !warned) {
            warned = true;
            sendMessage("Shader nieba sie nie zaladowal (" + CustomSkyRenderer.error() + ") - zostaje zwykle niebo.");
        }
    }
}
