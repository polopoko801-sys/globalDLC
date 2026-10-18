package thunder.hack.features.cmd.impl;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.command.CommandSource;
import org.jetbrains.annotations.NotNull;
import thunder.hack.ThunderHack;
import thunder.hack.core.Managers;
import thunder.hack.features.cmd.Command;
import thunder.hack.features.cmd.args.FriendArgumentType;
import thunder.hack.features.cmd.args.PlayerNameArgumentType;

import static com.mojang.brigadier.Command.SINGLE_SUCCESS;

public class FriendCommand extends Command {
    public FriendCommand() {
        super("friend", "friends");
    }

    @Override
    public void executeBuild(@NotNull LiteralArgumentBuilder<CommandSource> builder) {
        builder.then(literal("reset").executes(context -> {
            Managers.FRIEND.clear();
            sendMessage("Friends got reset.");

            return SINGLE_SUCCESS;
        }));

        // any nickname, typed or pasted - it does not have to be someone standing next to you right now
        builder.then(literal("add").then(arg("player", PlayerNameArgumentType.create()).executes(context -> {
            String nickname = context.getArgument("player", String.class);

            if (Managers.FRIEND.isFriend(nickname)) {
                sendMessage(nickname + " is already friended");
                return SINGLE_SUCCESS;
            }

            Managers.FRIEND.addFriend(nickname);
            sendMessage(nickname + " has been friended");
            return SINGLE_SUCCESS;
        })));

        builder.then(literal("remove").then(arg("player", FriendArgumentType.create()).executes(context -> {
            String nickname = context.getArgument("player", String.class);

            Managers.FRIEND.removeFriend(nickname);
            sendMessage(nickname + " has been unfriended");
            return SINGLE_SUCCESS;
        })));

        builder.then(literal("is").then(arg("player", PlayerNameArgumentType.create()).executes(context -> {
            String nickname = context.getArgument("player", String.class);
            sendMessage(nickname + (Managers.FRIEND.isFriend(nickname) ? " is friended." : " isn't friended."));

            return SINGLE_SUCCESS;
        })));

        builder.executes(context -> {
            if (Managers.FRIEND.getFriends().isEmpty()) {
                sendMessage("Friend list empty D:");
            } else {
                StringBuilder f = new StringBuilder("Friends: ");
                for (String friend : Managers.FRIEND.getFriends()) {
                    try {
                        f.append(friend).append(", ");
                    } catch (Exception ignored) {
                    }
                }
                sendMessage(f.toString());
            }
            return SINGLE_SUCCESS;
        });
    }
}
