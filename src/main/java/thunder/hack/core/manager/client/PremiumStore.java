package thunder.hack.core.manager.client;

import net.minecraft.client.MinecraftClient;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Zapamiętane konta premium (Microsoft). Trzymamy nick, UUID, refresh token i czas ostatniego użycia w
 * {@code thunderhack/premium.txt} (linia: {@code nick|uuid|refreshToken|lastMillis}). Refresh token pozwala
 * później zalogować się na to konto PONOWNIE bez wpisywania hasła Microsoftu — czyli wejść na nie, nawet
 * gdy się zapomni hasła. Panel ThunderWatch czyta ten sam plik i pokazuje te konta obok offline.
 */
public final class PremiumStore {

    public record Entry(String nick, UUID uuid, String refreshToken, long lastMillis) {
    }

    private PremiumStore() {
    }

    public static Path file() {
        return MinecraftClient.getInstance().runDirectory.toPath()
                .resolve("gombal").resolve("premium.txt");
    }

    /**
     * Zapisuje AKTUALNIE zalogowane konto, jeśli jest premium (ma prawdziwy access token — konto offline ma
     * pusty albo "0"). Wołane przy wejściu na serwer, żeby konto premium pokazało się w panelu nawet gdy
     * zalogowałeś się przez launcher (Modrinth App), a nie przez okno Microsoft w kliencie. Refresh token
     * zostaje pusty (launcher go nam nie daje) — {@link #save} nie nadpisze wtedy tokenu, jeśli już jakiś
     * mamy z wcześniejszego logowania w kliencie.
     */
    public static void captureCurrentIfPremium() {
        try {
            MinecraftClient mc = MinecraftClient.getInstance();
            if (mc == null || mc.getSession() == null) return;

            String token = mc.getSession().getAccessToken();
            if (token == null || token.length() < 6 || token.equals("0")) return; // offline

            String nick = mc.getSession().getUsername();
            UUID uuid = mc.getSession().getUuidOrNull();
            if (nick == null || nick.isBlank() || uuid == null) return;

            save(nick, uuid, "");
        } catch (Exception ignored) {
        }
    }

    /** Dodaje lub aktualizuje konto (po nicku); świeży refresh token nadpisuje stary, pusty go nie kasuje. */
    public static synchronized void save(String nick, UUID uuid, String refreshToken) {
        try {
            if (nick == null || nick.isBlank()) return;
            List<Entry> all = load();
            List<Entry> out = new ArrayList<>();
            boolean added = false;
            for (Entry e : all) {
                if (e.nick.equalsIgnoreCase(nick)) {
                    String rt = (refreshToken == null || refreshToken.isBlank()) ? e.refreshToken : refreshToken;
                    out.add(new Entry(nick, uuid, rt, System.currentTimeMillis()));
                    added = true;
                } else {
                    out.add(e);
                }
            }
            if (!added)
                out.add(new Entry(nick, uuid, refreshToken == null ? "" : refreshToken, System.currentTimeMillis()));
            write(out);
        } catch (Exception ignored) {
        }
    }

    public static synchronized List<Entry> load() {
        List<Entry> out = new ArrayList<>();
        try {
            Path f = file();
            if (!Files.exists(f)) return out;
            for (String line : Files.readAllLines(f)) {
                String s = line.trim();
                if (s.isEmpty() || s.startsWith("#")) continue;
                String[] p = s.split("\\|", -1);
                if (p.length < 2) continue;
                UUID uuid;
                try {
                    uuid = UUID.fromString(p[1]);
                } catch (Exception e) {
                    continue;
                }
                String rt = p.length >= 3 ? p[2] : "";
                long last = p.length >= 4 ? parseLong(p[3]) : 0L;
                out.add(new Entry(p[0], uuid, rt, last));
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    private static void write(List<Entry> entries) throws Exception {
        Path f = file();
        Files.createDirectories(f.getParent());
        List<String> lines = new ArrayList<>();
        lines.add("# nick|uuid|refreshToken|lastMillis  (konta premium — zapamietane do ponownego logowania)");
        for (Entry e : entries)
            lines.add(e.nick + "|" + e.uuid + "|" + e.refreshToken + "|" + e.lastMillis);
        Files.write(f, lines);
    }

    private static long parseLong(String s) {
        try {
            return Long.parseLong(s.trim());
        } catch (Exception e) {
            return 0L;
        }
    }
}
