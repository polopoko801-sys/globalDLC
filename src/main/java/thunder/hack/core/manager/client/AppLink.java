package thunder.hack.core.manager.client;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import thunder.hack.ThunderHack;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * The live link between the client and the GlobalDLC App.
 *
 * <p>The app runs a small HTTP server while it is open. The client asks it, at start and then every 20 seconds:
 * {@code GET /api/check?uid=<uid>&rel=<installed release>} and gets back {@code key=value} lines -
 * {@code release} (newest published release number), {@code banned} (0/1, for this UID) and
 * {@code changelog} (what was added, newlines escaped as {@code \n}). {@code GET /mod.jar} is the release itself.
 * The app's address is stamped into the jar by the app ({@code gombal_app_address.txt}); {@code GlobalDLC/app.txt} overrides it.
 *
 * <p><b>Updates.</b> A newer release is downloaded in the background into the mods folder as
 * {@code *.jar.pending} (Fabric ignores it), with the licence card files ({@code uid.txt} and friends) copied
 * over from the running jar so the UID survives. The running jar is locked by Windows, so the swap happens
 * after the game closes: a shutdown hook starts a detached {@code cmd} that waits for the JVM to exit and moves
 * the new jar over the old one. The title screen shows the AKTUALIZACJA screen with the changelog.
 *
 * <p><b>Bans.</b> A banned UID (from the app, or the card's own status) disables the client at once - the
 * player is thrown out of any world and kept on a ban screen. The verdict is remembered on disk
 * ({@code ban.flag}), so closing the app does not lift it; only the app saying "not banned" does.
 */
public final class AppLink {
    private AppLink() {
    }

    public static final String DEFAULT_ADDRESS = "http://127.0.0.1:7332";
    private static final long POLL_MS = 20_000L;

    private static volatile boolean banned;
    private static volatile int remoteRelease = -1;
    private static volatile String changelog = "";
    private static volatile String banReason = "";
    /** Which avatar (by size+hash) was already sent to the app, so it is uploaded once, not every 20 s. */
    private static volatile int sentAvatar;
    /** Downloaded and waiting for the game to close. */
    private static volatile boolean updateReady;
    private static volatile int readyRelease;
    /** Whether the update screen was already shown this session. */
    private static boolean updateShown;
    private static boolean hookInstalled;

    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(4)).build();

    public static boolean banned() {
        ClientUid.Licence licence = ClientUid.licence();
        return banned || (licence != null && licence.banned());
    }

    public static boolean updateReady() {
        return updateReady;
    }

    public static int readyRelease() {
        return readyRelease;
    }

    /** Number of the newest release the app announced (for the menu's "Co nowego"), -1 if unknown. */
    public static int latestRelease() {
        return remoteRelease;
    }

    public static String changelog() {
        return changelog;
    }

    /** Why the app banned this UID (empty when it gave no reason, or the ban came from the card). */
    public static String banReason() {
        return banReason;
    }

    public static int installedRelease() {
        try {
            return Integer.parseInt(Files.readString(dir().resolve("release.txt")).trim());
        } catch (Exception e) {
            return 0;
        }
    }

    private static Path dir() {
        return FabricLoader.getInstance().getGameDir().resolve(ConfigManager.CONFIG_FOLDER_NAME);
    }

    /**
     * Where the app is: {@code GlobalDLC/app.txt} if somebody wrote one (an override, never created), else the
     * address the GlobalDLC App stamped into this jar ({@code gombal_app_address.txt}) - so players set nothing
     * up - else this machine.
     */
    static String address() {
        Path f = dir().resolve("app.txt");
        try {
            if (Files.exists(f))
                for (String line : Files.readAllLines(f)) {
                    String s = line.trim();
                    if (!s.isEmpty() && !s.startsWith("#")) return trimSlash(s);
                }
        } catch (IOException ignored) {
        }
        try (InputStream in = AppLink.class.getClassLoader().getResourceAsStream("gombal_app_address.txt")) {
            if (in != null) {
                String s = new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
                if (!s.isEmpty()) return trimSlash(s);
            }
        } catch (IOException ignored) {
        }
        return DEFAULT_ADDRESS;
    }

    private static String trimSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    /** The app on THIS machine, for the owner's in-game panel (admin calls are refused from anywhere else). */
    public static String localAddress() {
        String a = address();
        int colon = a.lastIndexOf(':');
        String port = colon > a.indexOf("//") + 2 ? a.substring(colon + 1) : "7332";
        return "http://127.0.0.1:" + port.replaceAll("[^0-9]", "");
    }

    /** One blocking request to the local app's admin API; null when it is not reachable. */
    public static String admin(String path, String body) {
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(localAddress() + path)).timeout(Duration.ofSeconds(8));
            b = body == null ? b.GET() : b.POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
            HttpResponse<String> res = HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return res.statusCode() == 200 ? res.body() : "ERR " + res.statusCode() + " " + res.body();
        } catch (Exception e) {
            return null;
        }
    }

    /** The avatar PNG the app has for a UID, or null. */
    public static byte[] avatarBytes(String uid) {
        try {
            HttpResponse<byte[]> res = HTTP.send(HttpRequest.newBuilder(URI.create(localAddress() + "/api/avatar/get?uid="
                    + URLEncoder.encode(uid, StandardCharsets.UTF_8))).timeout(Duration.ofSeconds(6)).GET().build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            return res.statusCode() == 200 ? res.body() : null;
        } catch (Exception e) {
            return null;
        }
    }

    public static void start() {
        banned = Files.exists(dir().resolve("ban.flag"));
        Thread t = new Thread(() -> {
            boolean first = true;
            while (true) {
                try {
                    check(first);
                } catch (Throwable e) {
                    // app closed / unreachable: keep the last verdict and try again later
                }
                first = false;
                try {
                    Thread.sleep(POLL_MS);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }, "GlobalDLC-AppLink");
        t.setDaemon(true);
        t.start();
    }

    private static void check(boolean startup) throws Exception {
        String uid = ClientUid.get();
        String url = address() + "/api/check?uid=" + URLEncoder.encode(uid == null ? "none" : uid, StandardCharsets.UTF_8)
                + "&rel=" + installedRelease();
        HttpResponse<String> res = HTTP.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(6)).GET().build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (res.statusCode() != 200) return;

        int rel = -1;
        boolean ban = false;
        String notes = "";
        for (String line : res.body().split("\n")) {
            int i = line.indexOf('=');
            if (i < 0) continue;
            String k = line.substring(0, i).trim(), v = line.substring(i + 1).trim();
            switch (k) {
                case "release" -> rel = Integer.parseInt(v);
                case "banned" -> ban = v.equals("1") || v.equalsIgnoreCase("true");
                case "changelog" -> notes = v.replace("\\n", "\n");
                case "banReason" -> banReason = v;
            }
        }
        setBanned(ban);
        remoteRelease = rel;
        changelog = notes;

        sendAvatar(uid);

        // updates are taken at start only - the player sees AKTUALIZACJA when the game opens
        if (startup && rel > installedRelease() && !updateReady) download(rel);
    }

    /**
     * Sends the Discord avatar (and name) DiscordIdentity found to the app, which shows it next to this UID.
     * Once per picture - the hash of the bytes is remembered.
     */
    private static void sendAvatar(String uid) {
        byte[] png = DiscordIdentity.avatarPng;
        if (uid == null || png == null) return;
        int sig = java.util.Arrays.hashCode(png);
        if (sig == sentAvatar) return;
        try {
            String name = DiscordIdentity.name();
            String url = address() + "/api/avatar?uid=" + URLEncoder.encode(uid, StandardCharsets.UTF_8)
                    + (name == null ? "" : "&name=" + URLEncoder.encode(name, StandardCharsets.UTF_8));
            HttpResponse<String> res = HTTP.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(8))
                    .POST(HttpRequest.BodyPublishers.ofByteArray(png)).build(), HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() == 200) sentAvatar = sig;
        } catch (Exception ignored) {
            // app unreachable - try again on the next poll
        }
    }

    private static void setBanned(boolean ban) throws IOException {
        Path flag = dir().resolve("ban.flag");
        if (ban && !banned) {
            Files.createDirectories(flag.getParent());
            Files.writeString(flag, "banned\n");
        } else if (!ban && banned) {
            Files.deleteIfExists(flag);
        }
        banned = ban;
    }

    /** The jar this client is running from, when it is a plain file in a mods folder. */
    private static Path ownJar() {
        try {
            for (Path p : FabricLoader.getInstance().getModContainer("gombal").orElseThrow().getOrigin().getPaths())
                if (Files.isRegularFile(p) && p.toString().endsWith(".jar")) return p;
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static void download(int rel) throws Exception {
        Path own = ownJar();
        if (own == null) {
            ThunderHack.LOGGER.warn("[GlobalDLC] update {} available, but the client is not running from a mods jar - skipped", rel);
            return;
        }
        Path pending = own.resolveSibling(own.getFileName() + ".pending");
        Path raw = own.resolveSibling(own.getFileName() + ".download");
        HttpResponse<Path> res = HTTP.send(HttpRequest.newBuilder(URI.create(address() + "/mod.jar")).timeout(Duration.ofMinutes(2)).GET().build(),
                HttpResponse.BodyHandlers.ofFile(raw));
        if (res.statusCode() != 200 || Files.size(raw) < 1024) {
            Files.deleteIfExists(raw);
            return;
        }
        mergeCard(raw, own, pending);
        Files.deleteIfExists(raw);
        readyRelease = rel;
        updateReady = true;
        installSwapHook(pending, own, rel);
        ThunderHack.LOGGER.info("[GlobalDLC] update {} downloaded, installs when the game closes", rel);
    }

    /** New jar + every entry of the old one it does not have (the licence card: uid.txt, uid_avatar.png...). */
    private static void mergeCard(Path fresh, Path old, Path out) throws IOException {
        Set<String> names = new HashSet<>();
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(out))) {
            try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(fresh))) {
                ZipEntry e;
                while ((e = zis.getNextEntry()) != null) {
                    if (!names.add(e.getName())) continue;
                    zos.putNextEntry(new ZipEntry(e.getName()));
                    zis.transferTo(zos);
                    zos.closeEntry();
                }
            }
            try (ZipFile zf = new ZipFile(old.toFile())) {
                var it = zf.entries();
                while (it.hasMoreElements()) {
                    ZipEntry e = it.nextElement();
                    String n = e.getName();
                    if (names.contains(n) || !(n.startsWith("uid") || n.startsWith("licence") || n.startsWith("license"))) continue;
                    names.add(n);
                    zos.putNextEntry(new ZipEntry(n));
                    try (InputStream in = zf.getInputStream(e)) {
                        in.transferTo(zos);
                    }
                    zos.closeEntry();
                }
            }
        }
    }

    private static synchronized void installSwapHook(Path pending, Path target, int rel) {
        if (hookInstalled) return;
        hookInstalled = true;
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                Files.writeString(dir().resolve("release.txt"), rel + "\n");
                // A tiny script outside the game: keeps trying to move the new jar over the old one until the
                // game has let go of it (Windows locks a running jar), then deletes itself.
                Path bat = dir().resolve("apply-update.bat");
                Files.writeString(bat, "@echo off\r\n"
                        + "for /l %%i in (1,1,40) do (\r\n"
                        + "  ping -n 2 127.0.0.1 >nul\r\n"
                        + "  move /y \"" + pending + "\" \"" + target + "\" >nul 2>&1 && goto done\r\n"
                        + ")\r\n"
                        + ":done\r\n"
                        + "del \"%~f0\"\r\n");
                new ProcessBuilder("cmd", "/c", "start", "\"\"", "/min", bat.toString()).start();
            } catch (Throwable ignored) {
            }
        }, "GlobalDLC-update-swap"));
    }

    /** Called every client tick: keeps a banned player out, and shows AKTUALIZACJA once on the title screen. */
    public static void tick() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (banned()) {
            if (mc.world != null) {
                mc.world.disconnect(net.minecraft.text.Text.literal("GlobalDLC: banned"));
                mc.disconnect(new thunder.hack.gui.misc.BanScreen(), false);
                return;
            }
            if (!(mc.currentScreen instanceof thunder.hack.gui.misc.BanScreen))
                mc.setScreen(new thunder.hack.gui.misc.BanScreen());
            return;
        }
        // ban lifted while the screen was up
        if (mc.currentScreen instanceof thunder.hack.gui.misc.BanScreen)
            mc.setScreen(new net.minecraft.client.gui.screen.TitleScreen());
        if (updateReady && !updateShown && mc.world == null && mc.currentScreen instanceof net.minecraft.client.gui.screen.TitleScreen) {
            updateShown = true;
            mc.setScreen(new thunder.hack.gui.misc.UpdateScreen(mc.currentScreen));
        }
    }
}
