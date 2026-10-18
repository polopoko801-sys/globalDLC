package thunder.hack.core.manager.client;

import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientCommandSource;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.command.CommandSource;
import net.minecraft.util.Formatting;
import org.jetbrains.annotations.NotNull;
import thunder.hack.features.cmd.Command;
import thunder.hack.features.cmd.impl.*;
import thunder.hack.core.manager.IManager;

import java.util.ArrayList;
import java.util.List;

public class CommandManager implements IManager {
    private String prefix = "@";

    private final CommandDispatcher<CommandSource> dispatcher = new CommandDispatcher<>();
    private final CommandSource source = new ClientCommandSource(null, MinecraftClient.getInstance(), net.minecraft.command.permission.PermissionPredicate.ALL);
    private final List<Command> commands = new ArrayList<>();

    public CommandManager() {
        add(new RpcCommand());
        add(new KitCommand());
        add(new GpsCommand());
        add(new CfgCommand());
        add(new RctCommand());
        add(new BindCommand());
        add(new DrawCommand());
        add(new HelpCommand());
        add(new NukerCommand());
        add(new EClipCommand());
        add(new HClipCommand());
        add(new LoginCommand());
        add(new MacroCommand());
        add(new StaffCommand());
        add(new VClipCommand());
        add(new AddonsCommand());
        add(new GetNbtCommand());
        add(new FriendCommand());
        add(new ModuleCommand());
        add(new PrefixCommand());
        add(new SelfDestructCommand());
        add(new SpotifyCommand());
        add(new TrackerCommand());
        add(new GamemodeCommand());
        add(new DropAllCommand());
        add(new TreasureCommand());
        add(new WayPointCommand());
        add(new TabParseCommand());
        add(new BlockESPCommand());
        add(new BuildCommand());
        add(new AnimCommand());
        add(new BenchMarkCommand());
        add(new HorseSpeedCommand());
        add(new OpenFolderCommand());
        add(new ResetBindsCommand());
        add(new InvCleanerCommand());
        add(new GotoWaypointCommand());
        add(new ChestStealerCommand());
        add(new GarbageCleanerCommand());
    }

    private void add(@NotNull Command command) {
        command.register(dispatcher);
        commands.add(command);
    }

    public String getPrefix() {
        return prefix;
    }

    public void setPrefix(String prefix) {
        this.prefix = prefix;
    }

    public Command get(Class<? extends Command> commandClass) {
        for (Command command : commands)
            if (command.getClass().equals(commandClass)) return command;

        return null;
    }

    public static @NotNull String getClientMessage() {
        return Formatting.WHITE + "⌊" + Formatting.GOLD + "⚡" + Formatting.WHITE + "⌉" + Formatting.RESET;
    }

    public List<Command> getCommands() {
        return commands;
    }

    public CommandSource getSource() {
        return source;
    }

    public CommandDispatcher<CommandSource> getDispatcher() {
        return dispatcher;
    }

    /** True if the client dispatcher knows a command by this literal name. */
    public boolean isClientCommand(String name) {
        return name != null && !name.isEmpty() && dispatcher.getRoot().getChild(name) != null;
    }

    /**
     * True if this is OUR command and the server has no command of that name.
     * <p>
     * That is what lets a client command be typed with a slash as well as with the prefix: the chat
     * suggestor highlights it instead of painting it red, and the send hook runs it locally. When the
     * server does have a command of the same name (/kit, /help, ...) the server always wins.
     */
    public boolean isClientOnlyCommand(String name) {
        if (!isClientCommand(name)) return false;

        ClientPlayNetworkHandler handler = MinecraftClient.getInstance().getNetworkHandler();
        return handler == null || handler.getCommandDispatcher().getRoot().getChild(name) == null;
    }

    /**
     * Whether the client dispatcher should own a half-typed slashed command. An exact client-only
     * command always qualifies; a partial word qualifies only when the server has nothing starting the
     * same way, so server autocomplete is never hijacked ("/b" on a server with /ban stays the
     * server's, "/bui" on a server with no b-commands becomes ours and completes to /build).
     */
    public boolean ownsSlashedInput(@NotNull String input) {
        String name = commandName(input);
        if (isClientOnlyCommand(name)) return true;
        if (name.isEmpty() || !name.equals(input)) return false; // a space was typed: needs an exact match

        ClientPlayNetworkHandler handler = MinecraftClient.getInstance().getNetworkHandler();
        if (handler != null)
            for (com.mojang.brigadier.tree.CommandNode<?> node : handler.getCommandDispatcher().getRoot().getChildren())
                if (node.getName().startsWith(name)) return false;

        for (com.mojang.brigadier.tree.CommandNode<CommandSource> node : dispatcher.getRoot().getChildren())
            if (node.getName().startsWith(name)) return true;

        return false;
    }

    /** The literal in front of the first space - the command name of a "name args..." string. */
    public static @NotNull String commandName(@NotNull String input) {
        int space = input.indexOf(' ');
        return space == -1 ? input : input.substring(0, space);
    }

    public void registerCommand(Command command) {
        if (command == null) return;

        command.register(dispatcher);
        this.commands.add(command);
    }
}
