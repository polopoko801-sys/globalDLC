package thunder.hack.features.modules.render;

import thunder.hack.features.modules.Module;
import thunder.hack.setting.Setting;

/**
 * LowFire: pushes the first-person fire overlay down the screen so being on fire covers far less of your
 * view. The actual transform is applied in MixinInGameOverlayRenderer#renderFireOverlay.
 */
public class LowFire extends Module {
    public LowFire() {
        super("LowFire", Category.RENDER);
    }

    public final Setting<Float> down = new Setting<>("Down", 0.4f, 0.0f, 1.0f);
}
