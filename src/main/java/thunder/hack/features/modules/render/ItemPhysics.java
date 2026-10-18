package thunder.hack.features.modules.render;

import thunder.hack.features.modules.Module;
import thunder.hack.setting.Setting;

/**
 * Port of Celestial's {@code ItemPhysics}: dropped items no longer float and spin - flat items lie on the
 * ground, block items stand still, and items in the air tumble at "Physics Speed". The work is done in
 * {@code MixinItemEntityRenderer}, just as Celestial kept it in its item renderer.
 */
public class ItemPhysics extends Module {
    public ItemPhysics() {
        super("ItemPhysics", Category.RENDER);
    }

    public final Setting<Float> physicsSpeed = new Setting<>("Physics Speed", 0.5f, 0.1f, 5.0f);
}
