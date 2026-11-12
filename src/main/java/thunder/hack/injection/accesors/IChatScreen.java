package thunder.hack.injection.accesors;

import net.minecraft.client.gui.screen.ChatScreen;
import net.minecraft.text.Style;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** The chat screen's link handling, for clicks on links in the custom chat's bubbles. */
@Mixin(ChatScreen.class)
public interface IChatScreen {
    @Invoker("handleClickEvent")
    boolean gombal$handleClickEvent(Style style, boolean insert);
}
