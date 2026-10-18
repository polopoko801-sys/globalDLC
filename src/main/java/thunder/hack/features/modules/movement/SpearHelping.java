package thunder.hack.features.modules.movement;

import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.tag.ItemTags;
import thunder.hack.events.impl.PlayerUpdateEvent;
import thunder.hack.features.modules.Module;
import thunder.hack.setting.Setting;

/**
 * Dead simple: the moment YOU switch to a spear, after holding it for a tiny delay it gets left-clicked once. No
 * modes, no auto-swap - it only reacts to you putting a spear in your hand.
 *
 * <p>Checked every frame as well as every tick: ticks are 50 ms apart, so a tick-only check fired anywhere
 * from 50 to 100 ms after the switch instead of after the set delay.
 */
public final class SpearHelping extends Module {
    /** Seconds between the spear landing in your hand and the click. */
    private final Setting<Float> delay = new Setting<>("Delay", 0.05f, 0f, 0.5f);
    /** Hotbar slot (1-9) the spear sits in; 0 = recognise spears by their item tag. */
    private final Setting<Integer> spearSlot = new Setting<>("Spear Slot", 0, 0, 9);

    private boolean wasSpear;
    private boolean clicked;
    private long spearSince;

    public SpearHelping() {
        super("SpearHelping", Category.MOVEMENT);
    }

    @Override
    public void onEnable() {
        wasSpear = false;
        clicked = false;
        spearSince = 0L;
    }

    @EventHandler
    public void onUpdate(PlayerUpdateEvent e) {
        check();
    }

    @Override
    public void onRender2D(DrawContext context) {
        check();
    }

    private void check() {
        if (mc.player == null) return;
        boolean nowSpear = spearSlot.getValue() > 0
                ? mc.player.getInventory().selectedSlot == spearSlot.getValue() - 1
                : isSpear(heldStack());

        if (nowSpear && !wasSpear) {       // you just switched onto a spear
            spearSince = System.currentTimeMillis();
            clicked = false;
        }

        if (nowSpear) {
            if (!clicked && System.currentTimeMillis() - spearSince >= Math.round(delay.getValue() * 1000f)) {
                ((thunder.hack.injection.accesors.IMinecraftClient) mc).idoAttack();
                clicked = true;
            }
        } else {
            clicked = false;
        }

        wasSpear = nowSpear;
    }

    private ItemStack heldStack() {
        return mc.player == null ? ItemStack.EMPTY
                : mc.player.getInventory().getStack(mc.player.getInventory().selectedSlot);
    }

    private boolean isSpear(ItemStack stack) {
        return stack != null && stack.isIn(ItemTags.SPEARS);
    }
}
