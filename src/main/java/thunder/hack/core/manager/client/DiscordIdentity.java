package thunder.hack.core.manager.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.URI;
import java.net.UnixDomainSocketAddress;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static thunder.hack.features.modules.Module.mc;

/**
 * Who you are on Discord, worked out without being told.
 *
 * <p>The Discord app running on this machine listens on a local pipe for rich-presence clients. Saying
 * hello on it - the same handshake the {@code DiscordRPC} module performs, done here in plain Java so it
 * needs neither the native library nor that module to be enabled - is answered with a READY frame that
 * carries the logged-in account: its id, its name and the hash of its avatar. That is the detection.
 * Nothing is asked of the user and no token is involved; the local Discord app volunteers it to anything
 * that can open the pipe, which is how every game with a "playing" status knows who you are.
 *
 * <p>The avatar image itself is then one GET to Discord's CDN, using the hash the handshake gave us, and
 * it is written next to the config so the fetch happens once per avatar rather than once per launch.
 *
 * <p>All of it runs on its own daemon thread and every failure is silent: no Discord, an old Discord, a
 * pipe we may not open, no network - each simply means there is no face to draw, which is a decoration
 * going missing, not a problem worth a log line in someone's game.
 */
public final class DiscordIdentity {
    private DiscordIdentity() {
    }

    /** The client's own Discord application - the one the RPC module already presents itself as. */
    private static final String CLIENT_ID = "1093053626198523935";

    /** The account the local Discord app is signed in to. */
    public record User(String id, String name, String avatarHash) {
        boolean hasAvatar() {
            return avatarHash != null && !avatarHash.isBlank();
        }
    }

    private static volatile User user;
    private static volatile net.minecraft.util.Identifier avatar;
    /** The avatar PNG itself, once fetched - AppLink sends it to the GlobalDLC App so it shows next to the UID. */
    public static volatile byte[] avatarPng;
    private static boolean started;

    /** The detected account, or null while nothing has answered yet. */
    public static User user() {
        begin();
        return user;
    }

    /** The detected account's name, or null. */
    public static String name() {
        User found = user();
        return found == null ? null : found.name();
    }

    /**
     * The detected avatar, or null until it arrives.
     *
     * <p>Returning null rather than blocking is deliberate: this is called from the render loop, and a
     * pipe handshake plus a download is not something a frame can wait for. The corner simply has no
     * picture in it for the second or two the detection takes, and then it has one.
     */
    public static net.minecraft.util.Identifier avatar() {
        begin();
        return avatar;
    }

    /** Runs the detection once per launch, off the render thread. */
    private static synchronized void begin() {
        if (started) return;
        started = true;

        Thread thread = new Thread(DiscordIdentity::detect, "Cloudan-DiscordDetect");
        thread.setDaemon(true);
        thread.start();
    }

    /** Forgets what was found, so the next ask runs the detection again. */
    public static synchronized void forget() {
        started = false;
        user = null;
        avatar = null;
    }

    private static void detect() {
        try {
            User found = handshake();
            if (found == null) return;
            user = found;

            if (!found.hasAvatar()) return;
            byte[] png = avatarBytes(found);
            if (png != null) {
                avatarPng = png;
                register(png);
            }
        } catch (Throwable ignored) {
            // Detection is best-effort by design - see the class comment.
        }
    }

    /* ------------------------------------------------------------------ */
    /* The handshake                                                       */
    /* ------------------------------------------------------------------ */

    /**
     * Says hello on the first Discord pipe that answers and reads back who is signed in.
     *
     * <p>Discord numbers its pipes: a second instance takes {@code -1}, a third {@code -2}, and so on,
     * so all ten are tried rather than assuming the first.
     */
    private static User handshake() {
        for (int i = 0; i < 10; i++) {
            try (Pipe pipe = Pipe.open(i)) {
                if (pipe == null) continue;

                JsonObject hello = new JsonObject();
                hello.addProperty("v", 1);
                hello.addProperty("client_id", CLIENT_ID);
                pipe.send(0, hello.toString());

                // Discord may send other frames before the one we want; a handful of reads is plenty,
                // and the cap is what stops a chatty or wedged pipe from holding this thread forever.
                for (int frame = 0; frame < 8; frame++) {
                    String payload = pipe.receive();
                    if (payload == null) break;

                    User found = readUser(payload);
                    if (found != null) return found;
                }
            } catch (Exception ignored) {
                // That pipe is not Discord, or not ours to talk to. Try the next one.
            }
        }
        return null;
    }

    /** Pulls the account out of a READY frame, or returns null for any other frame. */
    private static User readUser(String payload) {
        JsonObject json = JsonParser.parseString(payload).getAsJsonObject();
        if (!json.has("evt") || !"READY".equals(json.get("evt").getAsString())) return null;
        if (!json.has("data")) return null;

        JsonObject data = json.getAsJsonObject("data");
        if (!data.has("user")) return null;

        JsonObject account = data.getAsJsonObject("user");
        String id = account.has("id") ? account.get("id").getAsString() : null;
        if (id == null) return null;

        // The display name when one is set, the handle otherwise - what Discord itself shows.
        String name = account.has("global_name") && !account.get("global_name").isJsonNull()
                ? account.get("global_name").getAsString()
                : account.has("username") ? account.get("username").getAsString() : id;

        String hash = account.has("avatar") && !account.get("avatar").isJsonNull()
                ? account.get("avatar").getAsString()
                : null;

        return new User(id, name, hash);
    }

    /* ------------------------------------------------------------------ */
    /* The picture                                                         */
    /* ------------------------------------------------------------------ */

    /**
     * The avatar, from the cache beside the config or from Discord's CDN the first time.
     *
     * <p>Keyed by the hash, so changing your avatar on Discord fetches the new one and leaves the old
     * file behind harmlessly rather than showing a stale face forever.
     */
    private static byte[] avatarBytes(User found) {
        Path cached = ConfigManager.MISC_FOLDER.toPath()
                .resolve("discord-" + found.id() + "-" + found.avatarHash() + ".png");

        try {
            if (Files.exists(cached)) return Files.readAllBytes(cached);
        } catch (IOException ignored) {
            // Unreadable cache: fall through and fetch it again.
        }

        try (HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://cdn.discordapp.com/avatars/"
                            + found.id() + "/" + found.avatarHash() + ".png?size=64"))
                    .timeout(Duration.ofSeconds(8))
                    .GET()
                    .build();

            HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200) return null;

            byte[] png = response.body();
            try {
                Files.createDirectories(cached.getParent());
                Files.write(cached, png);
            } catch (IOException ignored) {
                // Not being able to cache it only costs one fetch on the next launch.
            }
            return png;
        } catch (Exception e) {
            return null;
        }
    }

    /** Hands the bytes to the texture manager, which may only be touched on the render thread. */
    private static void register(byte[] png) {
        mc.execute(() -> {
            try (var in = new java.io.ByteArrayInputStream(png)) {
                var image = net.minecraft.client.texture.NativeImage.read(in);
                var id = net.minecraft.util.Identifier.of("gombal", "discord_avatar");
                mc.getTextureManager().registerTexture(id,
                        new net.minecraft.client.texture.NativeImageBackedTexture(id::toString, image));
                avatar = id;
            } catch (Throwable ignored) {
                // Something that is not a PNG, or a texture manager that is gone. No face, no harm.
            }
        });
    }

    /* ------------------------------------------------------------------ */
    /* The pipe                                                            */
    /* ------------------------------------------------------------------ */

    /**
     * One end of the Discord IPC pipe, in whichever form this platform has.
     *
     * <p>Frames are a little-endian opcode, a little-endian length, then that many bytes of UTF-8 JSON.
     */
    private abstract static class Pipe implements AutoCloseable {
        static Pipe open(int index) {
            String name = "discord-ipc-" + index;

            if (System.getProperty("os.name", "").toLowerCase().contains("win")) {
                try {
                    return new WindowsPipe(new RandomAccessFile("\\\\.\\pipe\\" + name, "rw"));
                } catch (Exception e) {
                    return null;
                }
            }

            // Everywhere else it is a unix socket, in whichever runtime directory the desktop uses.
            for (String dir : new String[]{System.getenv("XDG_RUNTIME_DIR"), System.getenv("TMPDIR"),
                    System.getenv("TMP"), System.getenv("TEMP"), "/tmp"}) {
                if (dir == null || dir.isBlank()) continue;

                for (String prefix : new String[]{"", "app/com.discordapp.Discord/", "snap.discord/"}) {
                    try {
                        Path socket = Path.of(dir, prefix + name);
                        if (!Files.exists(socket)) continue;

                        SocketChannel channel = SocketChannel.open(UnixDomainSocketAddress.of(socket));
                        return new UnixPipe(channel);
                    } catch (Exception ignored) {
                        // Not there, or not connectable. Keep looking.
                    }
                }
            }
            return null;
        }

        void send(int opcode, String payload) throws IOException {
            byte[] body = payload.getBytes(StandardCharsets.UTF_8);
            ByteBuffer frame = ByteBuffer.allocate(8 + body.length).order(ByteOrder.LITTLE_ENDIAN);
            frame.putInt(opcode);
            frame.putInt(body.length);
            frame.put(body);
            write(frame.array());
        }

        /** The JSON of the next frame, or null when the pipe ended. */
        String receive() throws IOException {
            byte[] header = readFully(8);
            if (header == null) return null;

            ByteBuffer buffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
            buffer.getInt();
            int length = buffer.getInt();
            // A frame claiming to be enormous is a frame we are not reading into memory.
            if (length <= 0 || length > 1 << 20) return null;

            byte[] body = readFully(length);
            return body == null ? null : new String(body, StandardCharsets.UTF_8);
        }

        abstract void write(byte[] bytes) throws IOException;

        /** Exactly {@code count} bytes, or null if the pipe ran out first. */
        abstract byte[] readFully(int count) throws IOException;
    }

    private static final class WindowsPipe extends Pipe {
        private final RandomAccessFile file;

        WindowsPipe(RandomAccessFile file) {
            this.file = file;
        }

        @Override
        void write(byte[] bytes) throws IOException {
            file.write(bytes);
        }

        @Override
        byte[] readFully(int count) throws IOException {
            byte[] bytes = new byte[count];
            try {
                file.readFully(bytes);
            } catch (java.io.EOFException e) {
                return null;
            }
            return bytes;
        }

        @Override
        public void close() throws IOException {
            file.close();
        }
    }

    private static final class UnixPipe extends Pipe {
        private final SocketChannel channel;

        UnixPipe(SocketChannel channel) {
            this.channel = channel;
        }

        @Override
        void write(byte[] bytes) throws IOException {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) channel.write(buffer);
        }

        @Override
        byte[] readFully(int count) throws IOException {
            ByteBuffer buffer = ByteBuffer.allocate(count);
            while (buffer.hasRemaining()) {
                if (channel.read(buffer) < 0) return null;
            }
            return buffer.array();
        }

        @Override
        public void close() throws IOException {
            channel.close();
        }
    }
}
