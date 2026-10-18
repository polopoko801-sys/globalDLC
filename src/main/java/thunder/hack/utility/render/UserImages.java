package thunder.hack.utility.render;

import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.util.Identifier;
import org.jetbrains.annotations.Nullable;
import thunder.hack.core.manager.client.ConfigManager;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static thunder.hack.features.modules.Module.mc;

/**
 * Your own pictures for visuals - a folder under {@code GlobalDLC/misc/} per visual, one file name per slot.
 *
 * <p>The same contract everywhere: drop {@code <name>.png} (or .jpg) into the folder and the visual uses it
 * instead of its built-in texture; delete it and the built-in one comes back. Files are decoded off the render
 * thread and re-read when they change on disk, so editing a picture in place shows up without a restart.
 * Each folder gets a README on first use saying which names it looks for.
 */
public final class UserImages {
    private UserImages() {
    }

    private static final String[] EXTENSIONS = {".png", ".jpg", ".jpeg"};
    private static final long RECHECK_MS = 1500L;

    private record Entry(Identifier id, long stamp) {
    }

    private record Probe(@Nullable File file, long checkedAt) {
    }

    private static final Map<String, Entry> LOADED = new ConcurrentHashMap<>();
    private static final Map<String, Probe> PROBES = new ConcurrentHashMap<>();
    private static final Set<String> PENDING = ConcurrentHashMap.newKeySet();
    private static final Set<String> READMES = ConcurrentHashMap.newKeySet();

    private static final ExecutorService POOL = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Gombal-UserImages");
        t.setDaemon(true);
        t.setPriority(Thread.NORM_PRIORITY - 2);
        return t;
    });

    public static File folder(String folder) {
        return new File(ConfigManager.MISC_FOLDER, folder);
    }

    /**
     * The picture {@code GlobalDLC/misc/<folder>/<name>.png}, or null while there is none (or it is still
     * loading) - the caller then draws its built-in texture.
     *
     * @param readme written into the folder once, so the folder explains itself
     */
    public static @Nullable Identifier get(String folder, String name, String readme) {
        String key = folder + "/" + name;
        ensureFolder(folder, readme);

        long now = System.currentTimeMillis();
        Probe probe = PROBES.get(key);
        if (probe == null || now - probe.checkedAt() > RECHECK_MS) {
            probe = new Probe(resolve(folder, name), now);
            PROBES.put(key, probe);
        }

        File file = probe.file();
        if (file == null) {
            Entry gone = LOADED.remove(key);
            if (gone != null) mc.execute(() -> mc.getTextureManager().destroyTexture(gone.id()));
            return null;
        }

        long stamp = file.lastModified();
        Entry entry = LOADED.get(key);
        if (entry == null || entry.stamp() != stamp) queue(key, file, stamp);
        // keep showing the previous version until the new one is decoded, so a re-save does not blink
        return entry == null ? null : entry.id();
    }

    private static @Nullable File resolve(String folder, String name) {
        File dir = folder(folder);
        for (String ext : EXTENSIONS) {
            File f = new File(dir, name + ext);
            if (f.isFile()) return f;
        }
        return null;
    }

    private static void ensureFolder(String folder, String readme) {
        if (!READMES.add(folder)) return;
        File dir = folder(folder);
        try {
            if (!dir.exists() && !dir.mkdirs()) return;
            File note = new File(dir, "README.txt");
            if (!note.exists()) Files.writeString(note.toPath(), readme, StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }

    private static void queue(String key, File file, long stamp) {
        if (!PENDING.add(key)) return;
        POOL.execute(() -> {
            try {
                NativeImage image = read(file);
                if (image == null) return;
                mc.execute(() -> {
                    try {
                        Identifier id = Identifier.of("gombal", "user/" + key.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_./-]", "_"));
                        mc.getTextureManager().registerTexture(id, new NativeImageBackedTexture(id::toString, image));
                        LOADED.put(key, new Entry(id, stamp));
                    } catch (Throwable t) {
                        image.close();
                    }
                });
            } catch (Throwable ignored) {
                // half-copied or broken file: it simply does not show; the next change retries
            } finally {
                PENDING.remove(key);
            }
        });
    }

    private static @Nullable NativeImage read(File file) throws Exception {
        if (file.getName().toLowerCase(Locale.ROOT).endsWith(".png")) {
            try (InputStream in = new FileInputStream(file)) {
                return NativeImage.read(in);
            }
        }
        // NativeImage reads PNG only - a jpg is transcoded here, off the render thread
        var buffered = javax.imageio.ImageIO.read(file);
        if (buffered == null) return null;
        var out = new ByteArrayOutputStream();
        javax.imageio.ImageIO.write(buffered, "png", out);
        return NativeImage.read(new ByteArrayInputStream(out.toByteArray()));
    }
}
