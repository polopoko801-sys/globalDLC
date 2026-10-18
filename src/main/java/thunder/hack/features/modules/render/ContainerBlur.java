package thunder.hack.features.modules.render;

import thunder.hack.features.modules.Module;
import thunder.hack.setting.Setting;

/**
 * Port of Celestial's {@code ContainerBlur}: blurs the world behind the inventory, chests and every other
 * container screen. Drawn from {@code MixinHandledScreen} with the client's backdrop blur (capped at 40 px).
 */
public class ContainerBlur extends Module {
    public ContainerBlur() {
        super("ContainerBlur", Category.RENDER);
    }

    public final Setting<Float> blurStrength = new Setting<>("Blur Strength", 20.0f, 5.0f, 50.0f);
}
