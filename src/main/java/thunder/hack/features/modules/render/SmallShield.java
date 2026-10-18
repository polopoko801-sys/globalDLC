package thunder.hack.features.modules.render;

import thunder.hack.features.modules.Module;
import thunder.hack.setting.Setting;

/**
 * Shrinks the first-person shield AND the totem so they cover less of the screen. Applied in
 * MixinHeldItemRenderer#renderFirstPersonItem (scales the matrix stack before the item is submitted).
 *
 * <p>The totem used to sit in a separate module, which is why turning this one on did nothing for it —
 * both live here now, each with its own switch and scale.
 */
public class SmallShield extends Module {
    public SmallShield() {
        super("SmallShieldTotem", Category.RENDER);
    }

    public final Setting<Boolean> shield = new Setting<>("Shield", true);
    public final Setting<Float> scale = new Setting<>("ShieldScale", 0.55f, 0.2f, 1.0f, v -> shield.getValue());
    public final Setting<Boolean> onlyBlocking = new Setting<>("OnlyBlocking", false, v -> shield.getValue());

    public final Setting<Boolean> totem = new Setting<>("Totem", true);
    public final Setting<Float> totemScale = new Setting<>("TotemScale", 0.55f, 0.2f, 1.0f, v -> totem.getValue());
}
