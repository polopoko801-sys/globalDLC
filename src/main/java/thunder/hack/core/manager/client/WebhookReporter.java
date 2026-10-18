package thunder.hack.core.manager.client;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ServerInfo;
import thunder.hack.ThunderHack;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Wysyła powiadomienie OBECNOŚCI na Discord webhook, gdy wejdziesz na serwer — żebyś (i ekipa) widział, kto
 * gra. Każdy wkleja swój własny URL do {@code config/thunderhack/webhook.txt} (pusty / z {@code #} = wyłączone).
 * <p>
 * ŚWIADOMIE wysyłamy tylko: nick, serwer i wersję klienta. Żadnych haseł, tokenów ani danych logowania —
 * to jest wyłącznie „kto jest online", nie zbieranie cudzych danych.
 */
public final class WebhookReporter {

    private static final int TIMEOUT_MS = 4_000;
    /** Nie spamuj tym samym serwerem częściej niż co tyle ms (respawn/redirect nie robi 10 wpisów). */
    private static final long COOLDOWN_MS = 60_000L;

    private static String url = "";
    private static boolean loaded = false;
    private static String lastServer = "";
    private static long lastSent = 0L;

    private WebhookReporter() {
    }

    private static void ensureLoaded() {
        if (loaded) return;
        loaded = true;
        try {
            Path dir = FabricLoader.getInstance().getConfigDir().resolve(ThunderHack.MOD_ID);
            Files.createDirectories(dir);
            Path cfg = dir.resolve("webhook.txt");
            if (!Files.exists(cfg)) {
                Files.writeString(cfg, """
                        # Discord webhook URL — wysyła powiadomienie \"kto wszedł na serwer\" (nick + serwer).
                        # Wklej swój webhook w jednej linii. Pusto / z # = wyłączone. Zero haseł jest wysyłanych.
                        """);
                return;
            }
            for (String line : Files.readAllLines(cfg)) {
                String s = line.trim();
                if (s.isEmpty() || s.startsWith("#")) continue;
                if (s.startsWith("http")) {
                    url = s;
                    break;
                }
            }
        } catch (Exception ignored) {
        }
    }

    /** Woła się przy wejściu na serwer/świat (z mixina onGameJoin). */
    public static void onJoin() {
        ensureLoaded();
        if (url.isEmpty()) return;

        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null || mc.player == null) return;

        ServerInfo info = mc.getCurrentServerEntry();
        String server = info != null ? info.address : (mc.isInSingleplayer() ? "singleplayer" : "-");

        long now = System.currentTimeMillis();
        if (server.equals(lastServer) && now - lastSent < COOLDOWN_MS) return;
        lastServer = server;
        lastSent = now;

        String nick = mc.player.getGameProfile().name();
        String ver = ThunderHack.VERSION;

        Thread t = new Thread(() -> post(nick, server, ver), "thunderhack-webhook");
        t.setDaemon(true);
        t.start();
    }

    private static void post(String nick, String server, String ver) {
        try {
            // Prosty embed Discorda — tylko obecność.
            String json = "{"
                    + "\"username\":\"GombalWatch\","
                    + "\"embeds\":[{"
                    + "\"title\":\"🟢 " + esc(nick) + " wszedł na serwer\","
                    + "\"color\":3145525,"
                    + "\"fields\":["
                    + "{\"name\":\"Serwer\",\"value\":\"" + esc(server) + "\",\"inline\":true},"
                    + "{\"name\":\"Wersja\",\"value\":\"" + esc(ver) + "\",\"inline\":true}"
                    + "]}]}";

            HttpURLConnection c = (HttpURLConnection) URI.create(url).toURL().openConnection();
            c.setRequestMethod("POST");
            c.setConnectTimeout(TIMEOUT_MS);
            c.setReadTimeout(TIMEOUT_MS);
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            try (OutputStream os = c.getOutputStream()) {
                os.write(json.getBytes(StandardCharsets.UTF_8));
            }
            c.getResponseCode();
            c.disconnect();
        } catch (Exception ignored) {
        }
    }

    /** Minimalne escapowanie do wartości JSON. */
    private static String esc(String s) {
        if (s == null) return "-";
        StringBuilder sb = new StringBuilder();
        for (char ch : s.toCharArray()) {
            switch (ch) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (ch < 0x20) sb.append(String.format("\\u%04x", (int) ch));
                    else sb.append(ch);
                }
            }
        }
        return sb.toString();
    }
}
