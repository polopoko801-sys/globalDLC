package thunder.hack.features.modules.render;

import thunder.hack.features.modules.Module;
import thunder.hack.setting.Setting;

/**
 * Port of Celestial's {@code NightMode}: makes the world darker. The brightness (gamma) option is ignored
 * and sky and block light are scaled down by "Dark Modifier" in the light map
 * ({@code MixinLightmapTextureManager}).
 */
public class NightMode extends Module {
    public NightMode() {
        super("NightMode", Category.RENDER);
    }

    public final Setting<Float> darkModifier = new Setting<>("Dark Modifier", 0.2f, 0.0f, 1.0f);

    @Override
    public String getDisplayInfo() {
        return String.valueOf(Math.round(darkModifier.getValue() * 100f) / 100f);
    }
}
