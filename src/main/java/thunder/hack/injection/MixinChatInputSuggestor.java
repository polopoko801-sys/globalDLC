package thunder.hack.injection;

import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.suggestion.Suggestions;
import net.minecraft.client.gui.screen.ChatInputSuggestor;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.command.CommandSource;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.LocalCapture;
import thunder.hack.ThunderHack;
import thunder.hack.core.Managers;
import thunder.hack.core.manager.client.CommandManager;
import thunder.hack.features.modules.Module;

import java.util.concurrent.CompletableFuture;

@Mixin(ChatInputSuggestor.class)
public abstract class MixinChatInputSuggestor {
    @Final @Shadow TextFieldWidget textField;
    @Shadow boolean completingSuggestions;
    @Shadow private ParseResults<CommandSource> parse;
    @Shadow private CompletableFuture<Suggestions> pendingSuggestions;
    @Shadow private ChatInputSuggestor.SuggestionWindow window;

    @Shadow protected abstract void showCommandSuggestions();

    /**
     * Client commands are parsed by OUR dispatcher, both when they are typed with the client prefix
     * (@build) and when they are typed with a slash (/build) while the server has no command of that
     * name. Without the slash branch the vanilla parse fails, and a failed parse is what paints the
     * whole line red and kills the suggestions.
     */
    @Inject(method = "refresh", at = @At(value = "INVOKE", target = "Lcom/mojang/brigadier/StringReader;canRead()Z", remap = false), cancellable = true, locals = LocalCapture.CAPTURE_FAILHARD)
    public void refreshHook(CallbackInfo ci, String string, StringReader reader) {
        if(Module.fullNullCheck()) return;

        String prefix = Managers.COMMAND.getPrefix();
        int start = reader.getCursor();
        int skip = 0;

        if (!prefix.isEmpty() && reader.canRead(prefix.length()) && reader.getString().startsWith(prefix, start))
            skip = prefix.length();
        else if (reader.canRead() && reader.getString().startsWith("/", start)
                && Managers.COMMAND.ownsSlashedInput(reader.getString().substring(start + 1)))
            skip = 1;

        if (skip == 0) return;

        reader.setCursor(start + skip);

        if (parse == null)
            parse = Managers.COMMAND.getDispatcher().parse(reader, Managers.COMMAND.getSource());

        final int cursor = textField.getCursor();

        if (cursor >= 1 && (window == null || !completingSuggestions)) {
            pendingSuggestions = Managers.COMMAND.getDispatcher().getCompletionSuggestions(parse, cursor);
            pendingSuggestions.thenRun(() -> {
                if (pendingSuggestions.isDone()) showCommandSuggestions();
            });
        }

        ci.cancel();
    }
}
