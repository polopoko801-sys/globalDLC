package thunder.hack.features.modules.render;

import thunder.hack.features.modules.Module;
import thunder.hack.features.modules.client.HudEditor;
import thunder.hack.setting.Setting;
import thunder.hack.setting.impl.ColorSetting;
import thunder.hack.utility.render.Render2DEngine;

import java.awt.Color;

/**
 * Port of Celestial's {@code FogColor}: the fog (and the sky clear colour behind it) takes the Rainbow /
 * Client / Custom colour, and "Fog Distance" scales how far away the fog starts and ends (below 1 = closer,
 * thicker fog). Applied in {@code MixinFogRenderer}.
 */
public class FogColor extends Module {
    public FogColor() {
        super("FogColor", Category.RENDER);
    }

    private enum Mode {Rainbow, Client, Custom}

    private final Setting<Mode> colorMode = new Setting<>("Fog Color", Mode.Rainbow);
    public final Setting<Float> distance = new Setting<>("Fog Distance", 0.7f, 0.1f, 2.0f);
    private final Setting<ColorSetting> customColor = new Setting<>("Custom Fog", new ColorSetting(new Color(0xFFFFFF)), v -> colorMode.is(Mode.Custom));

    public Color getColor() {
        return switch (colorMode.getValue()) {
            case Rainbow -> Render2DEngine.rainbow(1, 1.0f, 1.0f);
            case Client -> HudEditor.getColor(0);
            case Custom -> customColor.getValue().getColorObject();
        };
    }
}
