package thunder.hack.core.manager.client;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ServerInfo;
import thunder.hack.ThunderHack;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Sends a heartbeat to the ThunderWatch panel so the owner can see who is running the client.
 * <p>
 * Everything is configured from {@code config/thunderhack/watch.txt} — one line holding the panel URL, e.g.
 * {@code http://127.0.0.1:7331}. The file is created on first run; an empty file (or a line starting with
 * {@code #}) disables reporting entirely. All failures are swallowed: the panel being down must never cost
 * the player a frame or a log spam line.
 */
public final class WatchReporter {

    private static final long PERIOD_MS = 20_000L;
    private static final int TIMEOUT_MS = 4_000;

    private static String endpoint = "";
    private static String uid = "-";
    private static Thread thread;

    private WatchReporter() {
    }

    public static void start() {
        if (thread != null) return;

        loadConfig();
        if (endpoint.isEmpty()) return;

        thread = new Thread(WatchReporter::loop, "thunderhack-watch");
        thread.setDaemon(true);
        thread.setPriority(Thread.MIN_PRIORITY);
        thread.start();
    }

    private static void loadConfig() {
        try {
            Path dir = FabricLoader.getInstance().getConfigDir().resolve(ThunderHack.MOD_ID);
            Files.createDirectories(dir);

            Path cfg = dir.resolve("watch.txt");
            if (!Files.exists(cfg))
                Files.writeString(cfg, """
                        # ThunderWatch panel address. One line, e.g. http://127.0.0.1:7331
                        # Leave empty (or comment out) to disable usage reporting.
                        """);

            for (String line : Files.readAllLines(cfg)) {
                String s = line.trim();
                if (s.isEmpty() || s.startsWith("#")) continue;
                endpoint = s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
                break;
            }

            Path uidFile = dir.resolve("uid.txt");
            if (Files.exists(uidFile)) {
                String s = Files.readString(uidFile).trim();
                if (!s.isEmpty()) uid = s;
            }
        } catch (Exception ignored) {
        }
    }

    private static void loop() {
        while (true) {
            try {
                Thread.sleep(PERIOD_MS);
            } catch (InterruptedException e) {
                return;
            }
            try {
                beat();
            } catch (Exception ignored) {
            }
        }
    }

    private static void beat() throws Exception {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null || mc.player == null) return;

        ServerInfo info = mc.getCurrentServerEntry();
        String server = info != null ? info.address : (mc.isInSingleplayer() ? "singleplayer" : "-");

        String nick = mc.player.getGameProfile().name();

        // Świadomie wysyłamy tylko OBECNOŚĆ (nick + serwer + wersja). Haseł/tokenów innych graczy NIE
        // przesyłamy do panelu — to byłoby wykradanie cudzych danych logowania.
        String body = form(
                "nick", nick,
                "uid", uid,
                "ver", ThunderHack.VERSION,
                "build", ThunderHack.BUILD_DATE,
                "mc", mc.getGameVersion(),
                "server", server);

        HttpURLConnection c = (HttpURLConnection) URI.create(endpoint + "/api/beat").toURL().openConnection();
        c.setRequestMethod("POST");
        c.setConnectTimeout(TIMEOUT_MS);
        c.setReadTimeout(TIMEOUT_MS);
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
        try (OutputStream os = c.getOutputStream()) {
            os.write(body.getBytes(StandardCharsets.UTF_8));
        }
        c.getResponseCode();
        c.disconnect();
    }

    private static String form(String... kv) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            if (!sb.isEmpty()) sb.append('&');
            sb.append(URLEncoder.encode(kv[i], StandardCharsets.UTF_8)).append('=')
                    .append(URLEncoder.encode(kv[i + 1] == null ? "-" : kv[i + 1], StandardCharsets.UTF_8));
        }
        return sb.toString();
    }
}
