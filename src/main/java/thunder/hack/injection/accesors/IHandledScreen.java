package thunder.hack.injection.accesors;

import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.screen.slot.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Exposes the (protected) slot the mouse is currently over in any container screen, so features like
 * AutoMarket's scroll-to-list can read the hovered item from outside the screen class.
 */
@Mixin(HandledScreen.class)
public interface IHandledScreen {
    @Accessor("focusedSlot")
    Slot getFocusedSlot();
}
