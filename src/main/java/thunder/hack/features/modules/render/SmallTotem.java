package thunder.hack.features.modules.render;

import thunder.hack.features.modules.Module;
import thunder.hack.setting.Setting;

/**
 * SmallTotem: shrinks the first-person Totem of Undying so it covers less of the screen. Applied in
 * MixinHeldItemRenderer#renderFirstPersonItem (scales the matrix stack for totem items).
 */
public class SmallTotem extends Module {
    public SmallTotem() {
        super("SmallTotem", Category.RENDER);
    }

    public final Setting<Float> scale = new Setting<>("Scale", 0.55f, 0.2f, 1.0f);
}
