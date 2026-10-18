package thunder.hack.features.cmd.args;

import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.minecraft.command.CommandSource;
import thunder.hack.utility.world.Schematic;

import java.util.Collection;
import java.util.concurrent.CompletableFuture;

public class SchematicArgumentType implements ArgumentType<String> {
    public static SchematicArgumentType create() {
        return new SchematicArgumentType();
    }

    @Override
    public String parse(StringReader reader) throws CommandSyntaxException {
        return reader.readString();
    }

    @Override
    public <S> CompletableFuture<Suggestions> listSuggestions(CommandContext<S> context, SuggestionsBuilder builder) {
        return CommandSource.suggestMatching(Schematic.getSavedNames(), builder);
    }

    @Override
    public Collection<String> getExamples() {
        return Schematic.getSavedNames().stream().limit(5).toList();
    }
}
