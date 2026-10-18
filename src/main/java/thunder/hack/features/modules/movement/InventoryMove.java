package thunder.hack.features.modules.movement;

import thunder.hack.features.modules.Module;
import thunder.hack.setting.Setting;

/**
 * Lets you keep walking (WASD / jump) while a GUI such as the inventory or ClickGUI is open. The
 * actual key-forcing lives in {@code MixinKeyBinding.isPressed} (chat/sign screens are excluded there).
 */
public final class InventoryMove extends Module {
    public final Setting<Boolean> sneak = new Setting<>("Sneak", false);

    public InventoryMove() {
        super("InventoryMove", Category.MOVEMENT);
    }
}
