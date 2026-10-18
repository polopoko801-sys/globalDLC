package thunder.hack.features.modules.render;

import thunder.hack.features.modules.Module;
import thunder.hack.features.modules.client.HudEditor;
import thunder.hack.setting.Setting;
import thunder.hack.setting.impl.ColorSetting;
import thunder.hack.utility.render.Render2DEngine;

import java.awt.Color;

/**
 * Port of Celestial's {@code WorldColor}: tints the light map with the Astolfo / Rainbow / Client / Custom
 * colour. On 1.21.11 the light map is built on the GPU, so the colour multiplies its sky-light and ambient
 * colours ({@code MixinLightmapTextureManager}); torch light keeps its own colour.
 */
public class WorldColor extends Module {
    public WorldColor() {
        super("WorldColor", Category.RENDER);
    }

    private enum Mode {Astolfo, Rainbow, Client, Custom}

    private final Setting<Mode> worldColorMode = new Setting<>("LightMap Mode", Mode.Custom);
    private final Setting<ColorSetting> lightMapColor = new Setting<>("LightMap Color", new ColorSetting(Color.WHITE.getRGB()), v -> worldColorMode.is(Mode.Custom));

    public Color getColor() {
        return switch (worldColorMode.getValue()) {
            case Astolfo -> Render2DEngine.astolfo(false, 1);
            case Rainbow -> Render2DEngine.rainbow(1, 1.0f, 1.0f);
            case Client -> HudEditor.getColor(0);
            case Custom -> lightMapColor.getValue().getColorObject();
        };
    }
}
