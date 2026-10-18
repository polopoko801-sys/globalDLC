import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.function.Predicate;

/**
 * The server inside the GlobalDLC App that every running client talks to (see {@code AppLink} in the mod).
 *
 * <ul>
 *     <li>{@code GET /api/check?uid=..&rel=..} - {@code release=N}, {@code banned=0|1} (for that UID) and
 *     {@code changelog=...} (newlines as {@code \n}), one per line;</li>
 *     <li>{@code GET /mod.jar} - the published release.</li>
 * </ul>
 * The published release lives in {@code data/release}: {@code release.txt} (number), {@code changelog.txt},
 * {@code mod.jar}. Runs while the app is open; its address is stamped into every jar it hands out.
 */
public final class LinkServer {
    private LinkServer() {
    }

    static final Path RELEASE_DIR = Paths.get("data", "release");
    static final Path PORT_FILE = Paths.get("data", "server-port.txt");
    static final int DEFAULT_PORT = 7332;

    private static HttpServer server;
    private static volatile int requests;
    private static volatile long lastRequest;

    public static int port() {
        try {
            return Integer.parseInt(Files.readString(PORT_FILE).trim());
        } catch (Exception e) {
            return DEFAULT_PORT;
        }
    }

    public static boolean running() {
        return server != null;
    }

    public static int requests() {
        return requests;
    }

    public static long lastRequest() {
        return lastRequest;
    }

    public static int release() {
        try {
            return Integer.parseInt(Files.readString(RELEASE_DIR.resolve("release.txt")).trim());
        } catch (Exception e) {
            return 0;
        }
    }

    public static String changelog() {
        try {
            return Files.readString(RELEASE_DIR.resolve("changelog.txt"), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * A UID in its comparable form: {@code CLOUDAN-0000-0005}, {@code 0000-0005} and {@code 5} are all "5".
     */
    public static String norm(String uid) {
        if (uid == null) return "";
        String s = uid.trim().toUpperCase();
        if (s.startsWith("CLOUDAN-")) s = s.substring(8);
        s = s.replace("-", "");
        if (!s.isEmpty() && s.chars().allMatch(Character::isDigit)) {
            s = s.replaceFirst("^0+", "");
            if (s.isEmpty()) s = "0";
        }
        return s;
    }

    /** Who the current release is for: empty = everybody. */
    public static java.util.Set<String> targets() {
        java.util.Set<String> out = new java.util.HashSet<>();
        try {
            for (String l : Files.readAllLines(RELEASE_DIR.resolve("targets.txt"))) if (!l.isBlank()) out.add(norm(l));
        } catch (Exception ignored) {
        }
        return out;
    }

    public static String targetsText() {
        java.util.Set<String> t = targets();
        return t.isEmpty() ? "wszyscy" : String.join(", ", t);
    }

    /** Publishes {@code jar} as the next release, for everybody ({@code targets} empty) or only those UIDs. */
    public static synchronized int publish(Path jar, String notes, java.util.Collection<String> targets) throws IOException {
        int n = publish(jar, notes);
        StringBuilder sb = new StringBuilder();
        for (String t : targets) if (!t.isBlank()) sb.append(norm(t)).append('\n');
        Files.writeString(RELEASE_DIR.resolve("targets.txt"), sb.toString());
        return n;
    }

    /** Publishes {@code jar} as the next release with {@code notes} as what was added. Returns the new number. */
    public static synchronized int publish(Path jar, String notes) throws IOException {
        Files.createDirectories(RELEASE_DIR);
        int next = release() + 1;
        // stamped with the app address, so whoever installs it connects back here without any setup
        stampAddress(jar, RELEASE_DIR.resolve("mod.jar.tmp"));
        Files.move(RELEASE_DIR.resolve("mod.jar.tmp"), RELEASE_DIR.resolve("mod.jar"), StandardCopyOption.REPLACE_EXISTING);
        Files.writeString(RELEASE_DIR.resolve("changelog.txt"), notes.trim(), StandardCharsets.UTF_8);
        Files.writeString(RELEASE_DIR.resolve("release.txt"), next + "\n");
        return next;
    }

    /* ------------------------------------------------------------------ */
    /* The address players' clients connect to - stamped into every jar    */
    /* ------------------------------------------------------------------ */

    static final Path ADDRESS_FILE = Paths.get("data", "public-address.txt");
    /** Entry name the client reads its built-in app address from. */
    static final String ADDRESS_ENTRY = "gombal_app_address.txt";

    /**
     * The address written into every jar this app hands out, so players never configure anything:
     * {@code data/public-address.txt}, or on first use this machine's public IP (asked from ipify) + the port.
     */
    private static boolean detectTried;

    public static synchronized String publicAddress() {
        try {
            String s = Files.readString(ADDRESS_FILE).trim();
            if (!s.isEmpty()) return s;
        } catch (Exception ignored) {
        }
        if (detectTried) return "http://127.0.0.1:" + port();
        detectTried = true;
        String ip = null;
        try {
            var http = java.net.http.HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(4)).build();
            var res = http.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create("https://api.ipify.org")).build(),
                    java.net.http.HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() == 200 && res.body().trim().matches("[0-9.]+")) ip = res.body().trim();
        } catch (Exception ignored) {
        }
        String address = "http://" + (ip == null ? "127.0.0.1" : ip) + ":" + port();
        if (ip != null) setPublicAddress(address);
        return address;
    }

    public static synchronized void setPublicAddress(String address) {
        try {
            Files.createDirectories(ADDRESS_FILE.getParent());
            Files.writeString(ADDRESS_FILE, address.trim() + "\n");
        } catch (IOException ignored) {
        }
    }

    /** Copies {@code in} to {@code out} with the app address entry (re)written into it. */
    public static void stampAddress(Path in, Path out) throws IOException {
        try (java.util.zip.ZipInputStream zis = new java.util.zip.ZipInputStream(Files.newInputStream(in));
             java.util.zip.ZipOutputStream zos = new java.util.zip.ZipOutputStream(Files.newOutputStream(out))) {
            java.util.zip.ZipEntry e;
            java.util.Set<String> seen = new java.util.HashSet<>();
            while ((e = zis.getNextEntry()) != null) {
                if (e.getName().equals(ADDRESS_ENTRY) || !seen.add(e.getName())) continue;
                zos.putNextEntry(new java.util.zip.ZipEntry(e.getName()));
                zis.transferTo(zos);
                zos.closeEntry();
            }
            zos.putNextEntry(new java.util.zip.ZipEntry(ADDRESS_ENTRY));
            zos.write(publicAddress().getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }
    }

    /* ------------------------------------------------------------------ */
    /* Admin API - used by the in-game GlobalPanel, from THIS machine only */
    /* ------------------------------------------------------------------ */

    /** What the in-game panel can do, implemented by the app (the UID book lives there). */
    public interface Admin {
        /** One row per UID: uid, rank, banned (0/1), ban reason. */
        java.util.List<String[]> users();

        void ban(String uid, String reason);

        void unban(String uid);
    }

    private static Admin admin;

    public static void setAdmin(Admin a) {
        admin = a;
    }

    static final Path AVATAR_DIR = Paths.get("data", "avatars");

    /** Avatar picture a client sent for this UID (normalised), or null. */
    public static Path avatarOf(String uid) {
        Path p = AVATAR_DIR.resolve(norm(uid) + ".png");
        return Files.exists(p) ? p : null;
    }

    /** Discord name a client sent for this UID, or null. */
    public static String discordNameOf(String uid) {
        try {
            return Files.readString(AVATAR_DIR.resolve(norm(uid) + ".txt"), StandardCharsets.UTF_8).trim();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * @param isBanned  takes a UID in {@link #norm} form
     * @param banReason why it is banned (may be empty)
     * @param onAvatar  called (any thread) when a client sent its Discord avatar
     */
    public static synchronized void start(Predicate<String> isBanned, java.util.function.Function<String, String> banReason, Runnable onAvatar) {
        if (server != null) return;
        try {
            server = HttpServer.create(new InetSocketAddress(port()), 0);
        } catch (IOException e) {
            System.err.println("GlobalDLC server: port " + port() + " busy - " + e.getMessage());
            server = null;
            return;
        }
        server.createContext("/api/check", ex -> {
            count();
            String uid = query(ex, "uid");
            String n = uid == null || uid.equals("none") ? "" : norm(uid);
            boolean banned = !n.isEmpty() && isBanned.test(n);
            // a release meant for somebody else is simply not offered
            java.util.Set<String> targets = targets();
            int rel = targets.isEmpty() || targets.contains(n) ? release() : -1;
            String reason = banned ? String.valueOf(banReason.apply(n)) : "";
            String body = "release=" + rel + "\n"
                    + "banned=" + (banned ? 1 : 0) + "\n"
                    + "banReason=" + reason.replace("\r", "").replace("\n", " ") + "\n"
                    + "changelog=" + changelog().replace("\r", "").replace("\n", "\\n") + "\n";
            send(ex, 200, "text/plain; charset=utf-8", body.getBytes(StandardCharsets.UTF_8));
        });
        server.createContext("/api/avatar", ex -> {
            count();
            String uid = query(ex, "uid");
            if (uid == null || uid.equals("none") || !"POST".equalsIgnoreCase(ex.getRequestMethod())) {
                send(ex, 400, "text/plain", new byte[0]);
                return;
            }
            byte[] png = ex.getRequestBody().readNBytes(512 * 1024);
            if (png.length > 8 && (png[0] & 0xFF) == 0x89 && png[1] == 0x50) {
                Files.createDirectories(AVATAR_DIR);
                Files.write(AVATAR_DIR.resolve(norm(uid) + ".png"), png);
                String name = query(ex, "name");
                if (name != null) Files.writeString(AVATAR_DIR.resolve(norm(uid) + ".txt"), name, StandardCharsets.UTF_8);
                onAvatar.run();
            }
            send(ex, 200, "text/plain", "ok".getBytes(StandardCharsets.UTF_8));
        });
        server.createContext("/api/avatar/get", ex -> {
            String uid = query(ex, "uid");
            Path p = uid == null ? null : avatarOf(uid);
            if (p == null) {
                send(ex, 404, "text/plain", new byte[0]);
                return;
            }
            send(ex, 200, "image/png", Files.readAllBytes(p));
        });
        server.createContext("/api/admin/", ex -> {
            // the panel in the game on THIS computer only - nobody else can ban or publish
            if (!ex.getRemoteAddress().getAddress().isLoopbackAddress() || admin == null) {
                send(ex, 403, "text/plain", "forbidden".getBytes(StandardCharsets.UTF_8));
                return;
            }
            String path = ex.getRequestURI().getPath();
            String out = "ok";
            try {
                switch (path) {
                    case "/api/admin/users" -> {
                        StringBuilder sb = new StringBuilder();
                        for (String[] u : admin.users()) {
                            String name = discordNameOf(u[0]);
                            sb.append(u[0]).append('|').append(u[1]).append('|').append(u[2]).append('|')
                                    .append(u[3].replace("|", "/")).append('|').append(name == null ? "" : name.replace("|", "/"))
                                    .append('\n');
                        }
                        out = sb.toString();
                    }
                    case "/api/admin/info" -> out = "release=" + release() + "\ntargets=" + targetsText() + "\naddress=" + publicAddress()
                            + "\nchangelog=" + changelog().replace("\r", "").replace("\n", "\\n") + "\n";
                    case "/api/admin/ban" -> admin.ban(query(ex, "uid"), new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                    case "/api/admin/unban" -> admin.unban(query(ex, "uid"));
                    case "/api/admin/publish" -> {
                        String notes = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                        java.util.List<String> who = new java.util.ArrayList<>();
                        String t = query(ex, "targets");
                        if (t != null) for (String s : t.split("[,;\\s]+")) if (!s.isBlank()) who.add(s.trim());
                        Path jar = UpdatePage.newestBuild();
                        if (jar == null) throw new IOException("brak jara klienta");
                        out = "release=" + publish(jar, notes, who);
                    }
                    default -> {
                        send(ex, 404, "text/plain", new byte[0]);
                        return;
                    }
                }
            } catch (Exception e) {
                send(ex, 500, "text/plain; charset=utf-8", String.valueOf(e.getMessage()).getBytes(StandardCharsets.UTF_8));
                return;
            }
            send(ex, 200, "text/plain; charset=utf-8", out.getBytes(StandardCharsets.UTF_8));
        });
        server.createContext("/mod.jar", ex -> {
            count();
            Path jar = RELEASE_DIR.resolve("mod.jar");
            if (!Files.exists(jar)) {
                send(ex, 404, "text/plain", "no release".getBytes(StandardCharsets.UTF_8));
                return;
            }
            ex.getResponseHeaders().set("Content-Type", "application/java-archive");
            ex.sendResponseHeaders(200, Files.size(jar));
            try (OutputStream os = ex.getResponseBody(); InputStream in = Files.newInputStream(jar)) {
                in.transferTo(os);
            }
        });
        server.setExecutor(java.util.concurrent.Executors.newFixedThreadPool(4, r -> {
            Thread t = new Thread(r, "globaldlc-server");
            t.setDaemon(true);
            return t;
        }));
        server.start();
    }

    private static void count() {
        requests++;
        lastRequest = System.currentTimeMillis();
    }

    private static String query(HttpExchange ex, String key) {
        String q = ex.getRequestURI().getRawQuery();
        if (q == null) return null;
        for (String part : q.split("&")) {
            int i = part.indexOf('=');
            if (i > 0 && part.substring(0, i).equals(key))
                return URLDecoder.decode(part.substring(i + 1), StandardCharsets.UTF_8);
        }
        return null;
    }

    private static void send(HttpExchange ex, int code, String type, byte[] body) throws IOException {
        ex.getResponseHeaders().set("Content-Type", type);
        ex.sendResponseHeaders(code, body.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(body);
        }
    }
}
