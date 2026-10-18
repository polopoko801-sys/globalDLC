package thunder.hack.core.manager.player;

import net.minecraft.client.network.AbstractClientPlayerEntity;
import org.jetbrains.annotations.NotNull;
import net.minecraft.entity.player.PlayerEntity;
import thunder.hack.core.manager.IManager;
import thunder.hack.core.manager.client.ConfigManager;

import java.io.*;
import java.util.*;

public class FriendManager implements IManager {
    public static List<String> friends = new ArrayList<>();

    public boolean isFriend(String name) {
        return friends.stream().anyMatch(friend -> friend.equalsIgnoreCase(name));
    }

    public boolean isFriend(@NotNull PlayerEntity player) {
        return isFriend(player.getName().getString());
    }

    public void removeFriend(String name) {
        if (friends.removeIf(friend -> friend.equalsIgnoreCase(name)))
            saveFriends();
    }

    /** Ignores blanks and names already on the list, and writes the file out right away so a friend
     *  added in the GUI survives an alt-F4 instead of only a clean shutdown. */
    public void addFriend(String friend) {
        if (friend == null) return;
        String name = friend.trim();
        if (name.isEmpty() || isFriend(name)) return;
        friends.add(name);
        saveFriends();
    }

    public List<String> getFriends() {
        return friends;
    }

    public void clear() {
        friends.clear();
    }

    public List<AbstractClientPlayerEntity> getNearFriends() {
        if (mc.world == null) return new ArrayList<>();

        return mc.world.getPlayers().stream()
                .filter(player -> isFriend(player.getName().getString()))
                .toList();
    }

    @SuppressWarnings("ResultOfMethodCallIgnored")
    public void saveFriends() {
        File file = new File(ConfigManager.CONFIG_FOLDER_NAME + "/misc/friends.txt");
        try {
            file.createNewFile();
        } catch (Exception ignored) {
        }

        try (final BufferedWriter writer = new BufferedWriter(new FileWriter(file))) {
            for (String friend : friends)
                writer.write(friend + "\n");
        } catch (Exception ignored) {
        }
    }

    public void loadFriends() {
        try {
            File file = new File(ConfigManager.CONFIG_FOLDER_NAME + "/misc/friends.txt");

            if (file.exists()) {
                try (final BufferedReader reader = new BufferedReader(new FileReader(file))) {
                    while (reader.ready()) {
                        String line = reader.readLine();
                        if (line == null) continue;
                        line = line.trim();
                        if (!line.isEmpty() && !isFriend(line))
                            friends.add(line);
                    }
                }
            }
        } catch (Exception ignored) {
        }
    }
}