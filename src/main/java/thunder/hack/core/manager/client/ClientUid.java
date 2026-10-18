package thunder.hack.core.manager.client;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;

import static thunder.hack.features.modules.Module.mc;

/**
 * The licence id this copy of the client is running under.
 *
 * <p>Resolved once, from the first of these that answers:
 *
 * <ol>
 *   <li>a jar in the mods folder carrying a {@code uid.txt} - the card exported by the desktop app,
 *       which is what dropping that file into mods is supposed to do;</li>
 *   <li>{@code <gameDir>/GlobalDLC/uid.txt}, for setting one by hand;</li>
 *   <li>nothing, in which case the caller falls back to whatever it did before.</li>
 * </ol>
 *
 * <p>The mods folder is READ, not loaded: the card jar is looked into as a zip and its {@code uid.txt}
 * pulled out directly. That is deliberate - as a Fabric mod it would need a descriptor, a load order and
 * an entrypoint, and any of those going wrong would mean the id silently not arriving. A file inside a
 * zip either is there or is not.
 */
public final class ClientUid {
    private ClientUid() {
    }

    /**
     * What the id entitles its holder to, as written on the card.
     *
     * <p>Purely a label. Nothing in the client behaves differently because of it - it decides what the
     * menu prints in the corner and nothing else.
     */
    public record Licence(String uid, String status, String rank) {
        public boolean banned() {
            return "banned".equalsIgnoreCase(status);
        }
    }

    private static Licence cached;
    private static boolean resolved;
    /** The jar the licence came out of, so its avatar can be read from the same place. */
    private static Path sourceJar;

    /** The jar's own licence, worked out once - opening jars is not something to repeat. */
    private static Licence jarLicence;
    private static boolean jarResolved;

    /** When the loose files were last looked at, so this is not a disk read every frame. */
    private static long checkedAt;
    private static final long RECHECK_MS = 2000;

    /**
     * The licence, or null when this copy has none.
     *
     * <p>Re-read from the loose files a couple of times a second, so changing the active UID or its rank
     * in the desktop app shows up in the running game straight away - the alternative was building and
     * installing a fresh jar to change a label, which is an absurd price for a label.
     *
     * <p>The jar is only reopened once. It cannot change under a running game anyway, and scanning the
     * mods folder on a timer would be real work for an answer that is always the same.
     */
    public static synchronized Licence licence() {
        long now = System.currentTimeMillis();
        if (resolved && now - checkedAt < RECHECK_MS) return cached;

        checkedAt = now;
        resolved = true;
        cached = resolve();
        return cached;
    }

    /** The id, or null when this copy has none. */
    public static String get() {
        Licence licence = licence();
        return licence == null ? null : licence.uid();
    }

    /**
     * The id the way a person says it: {@code CLOUDAN-0000-0001} is UID {@code 1}.
     *
     * <p>The long form is the storage format - a fixed shape is what makes the id easy to match and
     * impossible to mistype. It is not what anybody calls it out loud, so the prefix, the dashes and the
     * padding come off everywhere it is shown.
     *
     * <p>Ids from before the digits-only format could carry letters; those are printed as they are
     * rather than mangled by stripping characters that mean something.
     */
    public static String shortId() {
        String uid = get();
        if (uid == null) return null;

        String body = (uid.startsWith("CLOUDAN-") ? uid.substring("CLOUDAN-".length()) : uid).replace("-", "");
        if (body.isEmpty() || !body.chars().allMatch(Character::isDigit)) return body.isEmpty() ? uid : body;

        String trimmed = body.replaceFirst("^0+", "");
        return trimmed.isEmpty() ? "0" : trimmed;
    }

    /** The rank, or null. */
    public static String rank() {
        Licence licence = licence();
        return licence == null || licence.rank().isEmpty() ? null : licence.rank();
    }

    /** Forgets everything, jar included, so a card dropped in while the game is running is picked up. */
    public static synchronized void forget() {
        resolved = false;
        jarResolved = false;
        avatarResolved = false;
        DiscordIdentity.forget();
    }

    private static net.minecraft.util.Identifier avatar;
    private static boolean avatarResolved;

    /**
     * The picture shown beside the licence, or null.
     *
     * <p>Your Discord avatar, detected by {@link DiscordIdentity} - nobody is asked to supply it. That
     * detection is asynchronous, so this returns null for the second or two it takes and the corner
     * simply has no picture in it until then.
     *
     * <p>A file is still honoured when Discord cannot be found or is not running: {@code uid_avatar.png}
     * inside the jar the licence came from, then {@code <gameDir>/GlobalDLC/misc/avatar.png}, then
     * {@code ~/.cloudan/avatar.png}. Those are the fallback, not the source.
     *
     * <p>The file, once read, is kept - re-reading a PNG every frame to draw an 18 pixel square would be
     * absurd. {@link #forget()} drops it if the file is replaced.
     */
    public static synchronized net.minecraft.util.Identifier avatar() {
        var discord = DiscordIdentity.avatar();
        if (discord != null) return discord;

        if (avatarResolved) return avatar;
        avatarResolved = true;

        byte[] png = avatarBytes();
        if (png == null) return null;

        try (InputStream in = new java.io.ByteArrayInputStream(png)) {
            var image = net.minecraft.client.texture.NativeImage.read(in);
            var id = net.minecraft.util.Identifier.of("gombal", "licence_avatar");
            mc.getTextureManager().registerTexture(id,
                    new net.minecraft.client.texture.NativeImageBackedTexture(id::toString, image));
            avatar = id;
        } catch (Throwable ignored) {
            // A missing or unreadable picture simply means no avatar; it is decoration, not a failure.
        }

        return avatar;
    }

    /** The avatar's raw bytes, from the licence jar or from the misc folder, or null for neither. */
    private static byte[] avatarBytes() {
        licence();

        try (InputStream in = ClientUid.class.getResourceAsStream("/" + AVATAR_ENTRY)) {
            if (in != null) return in.readAllBytes();
        } catch (Exception ignored) {
            // Falls through to the jar in the mods folder, then to the loose file.
        }

        if (sourceJar != null) {
            try (JarFile jar = new JarFile(sourceJar.toFile())) {
                JarEntry entry = jar.getJarEntry(AVATAR_ENTRY);
                if (entry != null) {
                    try (InputStream in = jar.getInputStream(entry)) {
                        return in.readAllBytes();
                    }
                }
            } catch (Exception ignored) {
                // A jar that cannot be reopened falls through to the file below.
            }
        }

        for (Path file : new Path[]{ConfigManager.MISC_FOLDER.toPath().resolve("avatar.png"),
                profileFile().resolveSibling("avatar.png")}) {
            try {
                if (Files.exists(file)) return Files.readAllBytes(file);
            } catch (Exception ignored) {
                // No file, or no permission to read it.
            }
        }

        return null;
    }

    private static final String AVATAR_ENTRY = "uid_avatar.png";

    /**
     * The loose file first, the jar second.
     *
     * <p>This order is what lets a licence change without a new jar. The file is the live answer - the
     * app rewrites it the moment you set an active UID or change its rank - and the jar is the one
     * baked in at export time, which a running game can never change. Whoever has the file has said
     * what they want; whoever has not falls back to what they were given.
     *
     * <p>The licence is a label and only a label - nothing in the client behaves differently because of
     * it - so letting a file win over the jar gives away nothing.
     */
    private static Licence resolve() {
        for (Path file : new Path[]{ConfigManager.MAIN_FOLDER.toPath().resolve("uid.txt"), profileFile()}) {
            try {
                if (!Files.exists(file)) continue;

                Licence found = parse(Files.readString(file));
                if (found != null) return found;
            } catch (Exception ignored) {
                // No file, or no permission to read it. Either way there is no id here.
            }
        }

        return fromJar();
    }

    /** The baked-in licence, worked out once and then remembered. */
    private static Licence fromJar() {
        if (jarResolved) return jarLicence;
        jarResolved = true;

        // The stamped jar is the one we are running out of, so its own resource is the first and by far
        // the cheapest answer: no folder scan, no reopening a file the loader already has open.
        jarLicence = fromOwnJar();
        if (jarLicence == null) jarLicence = fromModsFolder();
        return jarLicence;
    }

    /**
     * The licence the desktop app marked active, under your user profile.
     *
     * <p>This is what makes "USTAW AKTYWNY" in the app show up in a game that is already running: the
     * app writes the id, the status and the rank here, and the next refresh picks them up.
     */
    private static Path profileFile() {
        return Path.of(System.getProperty("user.home"), ".cloudan", "uid.txt");
    }

    /** The card writes the id, then its status, then its rank - one per line. */
    private static Licence parse(String text) {
        String[] lines = text.split("\\R");

        String uid = lines.length > 0 ? lines[0].trim() : "";
        if (uid.isEmpty()) return null;

        return new Licence(uid,
                lines.length > 1 ? lines[1].trim() : "active",
                lines.length > 2 ? lines[2].trim() : "");
    }

    /** The licence stamped into this very jar by the desktop app, or null when it was never stamped. */
    private static Licence fromOwnJar() {
        try (InputStream in = ClientUid.class.getResourceAsStream("/uid.txt")) {
            if (in == null) return null;
            return parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (Exception e) {
            return null;
        }
    }

    private static Licence fromModsFolder() {
        Path mods = mc.runDirectory.toPath().resolve("mods");
        if (!Files.isDirectory(mods)) return null;

        try (Stream<Path> jars = Files.list(mods)) {
            for (Path jar : jars.filter(p -> p.toString().toLowerCase().endsWith(".jar")).toList()) {
                Licence licence = readFromJar(jar);
                if (licence != null) {
                    sourceJar = jar;
                    return licence;
                }
            }
        } catch (Exception ignored) {
            // A locked or half-written jar in the folder is not worth failing over.
        }

        return null;
    }

    private static Licence readFromJar(Path path) {
        try (JarFile jar = new JarFile(path.toFile())) {
            JarEntry entry = jar.getJarEntry("uid.txt");
            if (entry == null) return null;

            try (InputStream in = jar.getInputStream(entry)) {
                return parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
        } catch (Exception e) {
            return null;
        }
    }

}
