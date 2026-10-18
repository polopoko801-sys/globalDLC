package thunder.hack.features.modules.player;

import meteordevelopment.orbit.EventHandler;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.Identifier;
import thunder.hack.events.impl.EventSetting;
import thunder.hack.features.modules.Module;
import thunder.hack.setting.Setting;
import thunder.hack.utility.Timer;

/**
 * Hotbar preset. Hit {@code Save} once to memorise which item sits in which hotbar slot; from then on
 * enabling the module puts that exact layout back together — it hunts each remembered item down in your
 * inventory and swaps it into its slot, then turns itself off.
 *
 * <p>The layout is stored as nine item ids in a hidden setting, so it survives a restart with the config.
 */
public final class Loadout extends Module {
    /** Press to overwrite the stored layout with what you are carrying right now. */
    private final Setting<Boolean> save = new Setting<>("Save", false);
    /** Ticks between two swaps — moving all nine slots in one tick looks like a macro to the server. */
    private final Setting<Integer> delay = new Setting<>("Delay", 2, 0, 20);
    /** Say in chat what is missing instead of silently leaving the slot alone. */
    private final Setting<Boolean> report = new Setting<>("Report", true);
    /** Nine item ids separated by ';', empty entry = "leave this slot alone". Hidden: driven by Save. */
    private final Setting<String> stored = new Setting<>("Stored", "", v -> false);

    private final Timer timer = new Timer();
    private int cursor;

    public Loadout() {
        super("Loadout", Category.PLAYER);
    }

    @EventHandler
    @SuppressWarnings("unused")
    public void onSetting(EventSetting e) {
        if (e.getSetting() == save && save.getValue()) {
            saveLayout();
            save.setValueSilent(false);
        }
    }

    @Override
    public void onEnable() {
        if (fullNullCheck()) {
            disable();
            return;
        }
        if (stored.getValue().isEmpty()) {
            sendMessage("Nie masz zapisanego eq — kliknij najpierw Save.");
            disable();
            return;
        }
        cursor = 0;
        timer.reset();
    }

    @Override
    public void onUpdate() {
        if (mc.player == null || mc.interactionManager == null) return;
        if (!timer.passedMs(delay.getValue() * 50L)) return;

        String[] wanted = stored.getValue().split(";", -1);

        // one slot per pass, so the swaps are spread over ticks instead of fired as a burst
        while (cursor < 9 && cursor < wanted.length) {
            int slot = cursor++;
            if (!restoreSlot(slot, wanted[slot])) continue;
            timer.reset();
            return;
        }

        disable();
    }

    /**
     * @return true when a swap was actually sent (so the caller waits a tick before the next one)
     */
    private boolean restoreSlot(int hotbarSlot, String id) {
        if (id == null || id.isEmpty()) return false;

        var item = Registries.ITEM.get(Identifier.tryParse(id));
        if (item == Items.AIR) return false;

        // already in place?
        if (mc.player.getInventory().getStack(hotbarSlot).getItem() == item) return false;

        // look through the main inventory first, then the rest of the hotbar
        for (int i = 9; i < 36; i++)
            if (mc.player.getInventory().getStack(i).getItem() == item)
                return swap(i, hotbarSlot);

        for (int i = 0; i < 9; i++)
            if (i != hotbarSlot && mc.player.getInventory().getStack(i).getItem() == item)
                return swap(i, hotbarSlot);

        if (report.getValue())
            sendMessage("Brak w eq: " + id);
        return false;
    }

    /** Inventory index -> screen-handler slot, then a hotbar SWAP click (button = target hotbar slot). */
    private boolean swap(int inventoryIndex, int hotbarSlot) {
        int handlerSlot = inventoryIndex < 9 ? 36 + inventoryIndex : inventoryIndex;
        mc.interactionManager.clickSlot(mc.player.playerScreenHandler.syncId, handlerSlot, hotbarSlot,
                SlotActionType.SWAP, mc.player);
        return true;
    }

    private void saveLayout() {
        if (mc.player == null) return;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 9; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (i > 0) sb.append(';');
            if (!stack.isEmpty())
                sb.append(Registries.ITEM.getId(stack.getItem()));
        }
        stored.setValue(sb.toString());
        sendMessage("Zapisano hotbar.");
    }
}
