package thunder.hack.features.modules.client;

import thunder.hack.features.modules.Module;
import thunder.hack.setting.Setting;

public final class ClientSettings extends Module {
    public static Setting<Boolean> futureCompatibility = new Setting<>("FutureCompatibility", false);
    public static Setting<Boolean> customMainMenu = new Setting<>("CustomMainMenu", true);
    public static Setting<Boolean> customPanorama = new Setting<>("CustomPanorama", true);
    public static Setting<Boolean> customLoadingScreen = new Setting<>("CustomLoadingScreen", true);
    public static Setting<Boolean> scaleFactorFix = new Setting<>("ScaleFactorFix", false);
    public static Setting<Float> scaleFactorFixValue = new Setting<>("ScaleFactorFixValue", 2f, 0f, 4f);
    public static Setting<Boolean> renderRotations = new Setting<>("RenderRotations", true);
    public static Setting<Boolean> skullEmoji = new Setting<>("SkullEmoji", false);
    /**
     * Whether the client prints anything into chat at all.
     *
     * <p>Off. Every module that had something to say said it in chat - toggles, warnings, "za daleko",
     * the lot - and the result was a chat you could not read your own messages in. Nothing is lost by
     * silencing it: the HUD already shows what is enabled, and notifications have their own corner.
     *
     * <p>It is still a setting rather than deleted code, so turning it back on brings every message
     * back without touching the eighty-odd places that send one.
     */
    public static Setting<Boolean> clientMessages = new Setting<>("ClientMessages", false);
    public static Setting<Boolean> debug = new Setting<>("Debug", false);
    public static Setting<Boolean> customBob = new Setting<>("CustomBob", true);
    public static Setting<Boolean> telemetry = new Setting<>("Telemetry", true);
    public static Setting<Language> language = new Setting<>("Language", Language.ENG);
    public static Setting<String> prefix = new Setting<>("Prefix", "@");
    public static Setting<ClipCommandMode> clipCommandMode = new Setting<>("ClipCommandMode", ClipCommandMode.Matrix);

    /**
     * One dial for how much visual work the client is allowed to do. Modules multiply their own budgets
     * (how many preview blocks, how many particles, how much glow) by {@link #qualityScale()} instead of
     * each growing its own "Performance" toggle. It only ever scales work down - nothing here can put the
     * client into a state it cannot render.
     */
    public static Setting<Quality> quality = new Setting<>("Quality", Quality.High);

    /**
     * Cuts animation, glow and particle work for anyone who does not want movement on screen. Reads as
     * an accessibility switch and behaves like one: information never lives in the animation alone.
     */
    public static Setting<Boolean> reducedMotion = new Setting<>("ReducedMotion", false);

    /**
     * Below the target frame rate the visual budgets shrink (particles, then glow) until it holds again.
     * See {@link thunder.hack.utility.render.core.FrameBudget}.
     */
    public static Setting<Boolean> adaptiveQuality = new Setting<>("AdaptiveQuality", true);
    public static Setting<Integer> targetFps = new Setting<>("TargetFPS", 60, 20, 240, v -> adaptiveQuality.getValue());

    public enum Quality {
        Low, Medium, High, Ultra
    }

    /** Multiplier a render module applies to its own budget. 1.0 is the tuned default. */
    public static float qualityScale() {
        float base = switch (quality.getValue()) {
            case Low -> 0.25f;
            case Medium -> 0.6f;
            case High -> 1f;
            case Ultra -> 1.6f;
        };
        return base * thunder.hack.utility.render.core.FrameBudget.scale();
    }

    /** Scales a per-frame budget by the quality dial, never below 1. */
    public static int budget(int base) {
        return Math.max(1, Math.round(base * qualityScale()));
    }

    /** True when effects should be kept still - ReducedMotion, or the Low quality preset. */
    public static boolean reduceEffects() {
        return reducedMotion.getValue() || quality.getValue() == Quality.Low;
    }

    public enum Language {
        RU,
        ENG
    }

    public enum ClipCommandMode {
        Default,
        Matrix
    }

    public ClientSettings() {
        super("ClientSettings", Category.CLIENT);
    }

    public static boolean isRu() {
        return language.is(Language.RU);
    }

    @Override
    public boolean isToggleable() {
        return false;
    }
}
