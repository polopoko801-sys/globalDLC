package thunder.hack.features.cmd.args;

import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.minecraft.command.CommandSource;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static thunder.hack.core.manager.IManager.mc;

/**
 * Any nickname, typed or pasted. {@link PlayerArgumentType} only accepts names that are in the tab list
 * right now, which makes it impossible to friend someone who is offline (or to paste a nick you were given
 * outside the game). This one takes the string as-is and only USES the player list for suggestions.
 */
public class PlayerNameArgumentType implements ArgumentType<String> {
    private static final Collection<String> EXAMPLES = List.of("pan4ur", "06ED");

    public static PlayerNameArgumentType create() {
        return new PlayerNameArgumentType();
    }

    @Override
    public String parse(StringReader reader) throws CommandSyntaxException {
        return reader.readString();
    }

    @Override
    public <S> CompletableFuture<Suggestions> listSuggestions(CommandContext<S> context, SuggestionsBuilder builder) {
        if (mc.getNetworkHandler() == null) return Suggestions.empty();
        return CommandSource.suggestMatching(mc.getNetworkHandler().getPlayerList().stream().map(p -> p.getProfile().name()), builder);
    }

    @Override
    public Collection<String> getExamples() {
        return EXAMPLES;
    }
}
