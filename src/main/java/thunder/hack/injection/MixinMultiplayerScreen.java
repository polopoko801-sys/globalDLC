package thunder.hack.injection;

import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.multiplayer.MultiplayerScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import thunder.hack.core.manager.client.ModuleManager;
import thunder.hack.gui.windows.WindowsScreen;
import thunder.hack.gui.windows.impl.*;

import static thunder.hack.core.manager.IManager.mc;

@Mixin(MultiplayerScreen.class)
public abstract class MixinMultiplayerScreen extends Screen {
    public MixinMultiplayerScreen(Text title) {
        super(title);
    }

    /**
     * Nothing is added to the server list any more.
     *
     * <p>There used to be a "⚡" button in the corner opening the windows screen. It sat on top of
     * vanilla's own layout, it is the one part of the client a stranger looking over your shoulder
     * notices first, and every window it opened is reachable from the ClickGUI anyway.
     *
     * <p>The mixin is kept rather than deleted so the entry in {@code thunderhack.mixins.json} stays
     * valid and anything that wants a hook here has a place to put it.
     */
    @Inject(method = "init", at = @At("RETURN"))
    private void initHook(CallbackInfo ci) {
    }
}