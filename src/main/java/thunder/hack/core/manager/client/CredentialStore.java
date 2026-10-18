package thunder.hack.core.manager.client;

import net.minecraft.client.MinecraftClient;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Podsłuchuje komendy logowania wpisywane na serwerach (np. anarchia.gg) i zapisuje wpisane hasło do
 * {@code thunderhack/accounts.txt} pod aktualnym nickiem — dokładnie ten sam plik, który czyta
 * {@code AccountSwitcherScreen} i panel ThunderWatch. Dzięki temu hasło, które kiedyś wpisałeś przez
 * {@code /login}, jest widoczne później, gdy je zapomnisz.
 * <p>
 * Plik ma format {@code nick|hasło} w linii. Nic nie wysyłamy nigdzie — to lokalny zapis obok gry.
 */
public final class CredentialStore {

    /** Komendy, których PIERWSZY argument jest hasłem (login / register / auth). */
    private static final List<String> PASS_FIRST = List.of(
            "login", "l", "log", "reg", "register", "auth", "authme");

    /** Komendy zmiany hasła: {@code /changepassword <stare> <nowe>} — bierzemy DRUGI argument. */
    private static final List<String> PASS_SECOND = List.of(
            "changepassword", "changepass", "changepw", "passwd", "password");

    private CredentialStore() {
    }

    /**
     * @param command komenda BEZ wiodącego ukośnika, tak jak dostaje ją {@code sendChatCommand}
     *                (np. {@code "login tajne123"})
     */
    public static void capture(String command) {
        try {
            if (command == null || command.isBlank()) return;
            String[] parts = command.trim().split("\\s+");
            if (parts.length < 2) return;

            String cmd = parts[0].toLowerCase(Locale.ROOT);
            String pass = null;
            if (PASS_FIRST.contains(cmd))
                pass = parts[1];
            else if (PASS_SECOND.contains(cmd) && parts.length >= 3)
                pass = parts[2];

            if (pass == null || pass.isBlank()) return;

            MinecraftClient mc = MinecraftClient.getInstance();
            if (mc == null || mc.getSession() == null) return;
            String nick = mc.getSession().getUsername();
            if (nick == null || nick.isBlank()) return;

            save(mc, nick, pass);
        } catch (Exception ignored) {
        }
    }

    private static synchronized void save(MinecraftClient mc, String nick, String pass) throws Exception {
        Path file = mc.runDirectory.toPath().resolve("gombal").resolve("accounts.txt");
        Files.createDirectories(file.getParent());

        List<String> lines = Files.exists(file) ? new ArrayList<>(Files.readAllLines(file)) : new ArrayList<>();
        String newLine = nick + "|" + pass;

        boolean found = false;
        for (int i = 0; i < lines.size(); i++) {
            String s = lines.get(i).trim();
            if (s.isEmpty()) continue;
            int bar = s.indexOf('|');
            String existingNick = bar < 0 ? s : s.substring(0, bar).trim();
            if (existingNick.equalsIgnoreCase(nick)) {
                lines.set(i, newLine);
                found = true;
                break;
            }
        }
        if (!found) lines.add(newLine);

        Files.write(file, lines);
    }
}
