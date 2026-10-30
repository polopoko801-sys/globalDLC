package thunder.hack.gui.mainmenu;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.util.Identifier;
import org.jetbrains.annotations.Nullable;
import thunder.hack.core.manager.client.ConfigManager;
import thunder.hack.utility.render.Render2DEngine;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static thunder.hack.features.modules.Module.mc;

/**
 * The wallpapers behind the menu: the folder, the categories, the GPU cache and the crossfade.
 *
 * <p>Every image dropped into {@code <gameDir>/GlobalDLC/misc/backgrounds/} shows up in the gallery, at any
 * resolution - 4K included, since the draw is a cover-fit that crops the overflowing axis and keeps the aspect
 * ratio. The choice is remembered in {@code selected.txt}; with nothing selected the menu falls back to the
 * vanilla panorama.
 *
 * <h2>Two caches, not one</h2>
 * A gallery page shows a dozen wallpapers at once. Uploading each one at full size would mean tens of
 * megabytes of VRAM to draw previews a few hundred pixels wide, so there are two caches: <b>thumbnails</b>,
 * capped at {@link #THUMB_WIDTH} and used for every card in the gallery, and <b>full size</b>, held only for
 * the wallpaper actually on screen and the one crossfading into it. That is the difference between a gallery
 * that opens instantly and one that stalls for a second per row.
 *
 * <h2>Nothing decodes on the render thread</h2>
 * Decoding a 3840x2160 PNG takes long enough to drop frames, so it happens on a small daemon pool and only the
 * GPU registration - which must be on the render thread - is handed back through {@code mc.execute}. A
 * wallpaper that is not ready yet simply is not drawn yet; the caller keeps whatever it had. This is why
 * opening the gallery never freezes, even the first time.
 */
public final class MenuBackground {
    public static final File FOLDER = new File(ConfigManager.MISC_FOLDER, "backgrounds");
    private static final File SELECTION = new File(FOLDER, "selected.txt");
    private static final File CATEGORIES = new File(FOLDER, "categories.txt");

    private static final long RESCAN_INTERVAL_MS = 1000L;
    private static final String[] EXTENSIONS = {".png", ".jpg", ".jpeg", ".gif"};

    /** Widest a gallery thumbnail is ever uploaded at. */
    private static final int THUMB_WIDTH = 480;

    /** How the gallery groups wallpapers. */
    public enum Category {
        Featured, Anime, Nature, Minecraft, Abstract, Dark, City, Space, Minimal, Other;

        public static Category parse(String raw) {
            if (raw == null) return Other;
            for (Category c : values())
                if (c.name().equalsIgnoreCase(raw.trim())) return c;
            return Other;
        }
    }

    private MenuBackground() {
    }

    /* ------------------------------------------------------------------ */
    /* Loading infrastructure                                              */
    /* ------------------------------------------------------------------ */

    private static final AtomicInteger THREADS = new AtomicInteger();

    /**
     * Two threads, bounded queue, discard when swamped.
     *
     * <p>Discarding is right here and wrong almost everywhere else: a dropped decode means one card draws its
     * placeholder for another second and is retried on the next scroll, which is far better than letting a
     * fast scroll through a hundred wallpapers queue a hundred full decodes.
     */
    private static final ExecutorService POOL = new ThreadPoolExecutor(
            1, 2, 20L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(24),
            runnable -> {
                Thread thread = new Thread(runnable, "Gombal-Wallpaper-" + THREADS.incrementAndGet());
                thread.setDaemon(true);
                // Below normal: a wallpaper decode must never be why the menu stutters.
                thread.setPriority(Thread.NORM_PRIORITY - 2);
                return thread;
            },
            new ThreadPoolExecutor.DiscardPolicy());

    /**
     * A wallpaper on the GPU: its texture and the source pixel size, which cover-fit needs. An animated one
     * (GIF) also carries every frame and its delay; {@link #current()} picks the frame for this instant.
     */
    private record Uploaded(Identifier id, int width, int height, long stamp, Identifier[] frames, int[] delays, int loopMs) {
        Uploaded(Identifier id, int width, int height, long stamp) {
            this(id, width, height, stamp, null, null, 0);
        }

        Identifier current() {
            if (frames == null || frames.length == 0 || loopMs <= 0) return id;
            long t = System.currentTimeMillis() % loopMs;
            for (int i = 0; i < frames.length; i++) {
                t -= delays[i];
                if (t < 0) return frames[i];
            }
            return frames[frames.length - 1];
        }
    }

    /** Thumbnails, access-ordered so the least recently drawn can be evicted first. */
    private static final LinkedHashMap<String, Uploaded> THUMBS = new LinkedHashMap<>(16, 0.75f, true);
    /** How many thumbnails may be resident. A gallery page is about a dozen. */
    private static final int THUMB_BUDGET = 24;

    /** Full-size wallpapers. Only ever the current one and the one fading in. */
    private static final Map<String, Uploaded> FULL = new ConcurrentHashMap<>();

    /** Files currently being decoded, so a per-frame caller cannot queue the same one repeatedly. */
    private static final Set<String> PENDING = ConcurrentHashMap.newKeySet();

    private static final List<String> FILES = new ArrayList<>();
    private static final Map<String, Category> TAGS = new ConcurrentHashMap<>();

    /** Placeholder for "nothing chosen yet": not a file, so the deleted-file check swaps in the first wallpaper. */
    private static final String FIRST_LAUNCH = "<first-launch>";

    private static String selected;         // file name, null = vanilla panorama
    private static boolean selectionRead;
    private static long lastScan;

    /* ------------------------------------------------------------------ */
    /* Folder and selection                                                */
    /* ------------------------------------------------------------------ */

    /** Cheap enough for every frame: the directory listing is throttled and nothing decodes here. */
    public static void refresh() {
        long now = System.currentTimeMillis();
        if (now - lastScan < RESCAN_INTERVAL_MS) return;
        lastScan = now;

        if (!FOLDER.exists() && !FOLDER.mkdirs()) return;
        if (!bundledChecked) {
            bundledChecked = true;
            installBundled();
        }

        FILES.clear();
        File[] found = FOLDER.listFiles((dir, name) -> {
            String lower = name.toLowerCase(Locale.ROOT);
            return Arrays.stream(EXTENSIONS).anyMatch(lower::endsWith);
        });
        if (found != null) {
            Arrays.sort(found, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
            for (File f : found) FILES.add(f.getName());
        }

        if (!selectionRead) {
            selectionRead = true;
            readSelection();
            readCategories();
        }

        // Selected file deleted behind our back (or a choice from an older build) -> the first wallpaper.
        if (selected != null && !FILES.contains(selected))
            select(FILES.isEmpty() ? null : FILES.contains("sakura-breeze.gif") ? "sakura-breeze.gif" : FILES.getFirst());
    }

    private static boolean bundledChecked;

    /** Wallpapers older builds unpacked and this one no longer ships - removed from the folder once. */
    private static final Set<String> RETIRED = Set.of(
            "bonfire-night.gif", "city-alley-night.gif", "fantasy-forest-tree.gif", "flowers-sunset.gif",
            "forest-scene.gif", "green-fields-wind.gif", "lake-house-pixel.gif", "moon-mountains-pixel.gif",
            "purple-landscape-pixel.gif", "rain-city-night-blue.gif", "rain-garden.gif", "rain-river.gif",
            "rain-samurai.gif", "rain-umbrella-walk.gif", "snowy-cottage-moon.gif", "train-sunrise-blossom.gif",
            "winter-snowfall.gif");

    /** The bundled names already unpacked once, so one you deleted yourself is not put back. */
    private static final File INSTALLED = new File(FOLDER, "bundled.txt");

    /**
     * The wallpapers ship INSIDE the jar ({@code assets/gombal/backgrounds}), so every copy of the client has
     * them. Each bundled file is unpacked once (tracked in {@code bundled.txt}), and the ones older builds
     * shipped are cleared out - anything else you added yourself is left alone.
     */
    private static void installBundled() {
        try (var idx = MenuBackground.class.getResourceAsStream("/assets/gombal/backgrounds/index.txt")) {
            if (idx == null) return;

            Set<String> done = new java.util.HashSet<>();
            if (INSTALLED.isFile())
                for (String line : Files.readAllLines(INSTALLED.toPath(), StandardCharsets.UTF_8))
                    if (!line.isBlank()) done.add(line.trim());

            if (!done.contains("<retired-v1>")) {
                for (String old : RETIRED) new File(FOLDER, old).delete();
                done.add("<retired-v1>");
            }

            String first = null;
            for (String name : new String(idx.readAllBytes(), StandardCharsets.UTF_8).split("\\R")) {
                name = name.trim();
                if (name.isEmpty() || name.contains("/") || name.contains("\\")) continue;
                if (first == null) first = name;
                if (!done.add(name) || new File(FOLDER, name).isFile()) continue;
                try (var in = MenuBackground.class.getResourceAsStream("/assets/gombal/backgrounds/" + name)) {
                    if (in == null) continue;
                    Files.copy(in, new File(FOLDER, name).toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            }
            Files.write(INSTALLED.toPath(), done, StandardCharsets.UTF_8);

            // an empty selection would leave the plain panorama up - start on a wallpaper instead
            String pick = new File(FOLDER, "sakura-breeze.gif").isFile() ? "sakura-breeze.gif" : first;
            boolean empty = !SELECTION.isFile() || Files.readString(SELECTION.toPath(), StandardCharsets.UTF_8).isBlank();
            if (pick != null && empty) Files.writeString(SELECTION.toPath(), pick, StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            // no bundled wallpapers is not an error - the panorama is the fallback
        }
    }

    private static void readSelection() {
        try {
            if (SELECTION.isFile()) {
                String name = Files.readString(SELECTION.toPath(), StandardCharsets.UTF_8).trim();
                if (!name.isEmpty()) selected = name;
            } else {
                selected = FIRST_LAUNCH; // resolved to the first wallpaper once the folder has been listed
            }
        } catch (Exception ignored) {
            // No selection is a valid state - the panorama is the fallback.
        }
    }

    /**
     * Reads {@code categories.txt}, one {@code file name = Category} per line.
     *
     * <p>A sidecar file rather than anything embedded in the image, because the point is that YOUR wallpapers
     * get categorised too, and nothing can rewrite a jpg you dropped in. A file with no entry is grouped by
     * its name prefix, and failing that lands in Other - so the gallery is never empty just because nobody
     * wrote a mapping.
     */
    private static void readCategories() {
        TAGS.clear();
        try {
            if (!CATEGORIES.isFile()) return;
            for (String line : Files.readAllLines(CATEGORIES.toPath(), StandardCharsets.UTF_8)) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;

                int eq = trimmed.indexOf('=');
                if (eq <= 0) continue;
                TAGS.put(trimmed.substring(0, eq).trim(), Category.parse(trimmed.substring(eq + 1)));
            }
        } catch (Exception ignored) {
        }
    }

    /** The category of a wallpaper: its mapping, else its name prefix, else Other. */
    public static Category categoryOf(String fileName) {
        if (fileName == null) return Category.Other;

        Category tagged = TAGS.get(fileName);
        if (tagged != null) return tagged;

        String lower = fileName.toLowerCase(Locale.ROOT);
        // the bundled desktop-style sets
        if (lower.startsWith("macos-") || lower.startsWith("plasma-") || lower.startsWith("windows-")) return Category.Abstract;
        if (lower.startsWith("sakura")) return Category.Nature;
        for (Category c : Category.values()) {
            if (c == Category.Other) continue;
            if (lower.startsWith(c.name().toLowerCase(Locale.ROOT))) return c;
        }
        return Category.Other;
    }

    public static List<String> getFiles() {
        return FILES;
    }

    /** The files in one category, or everything when {@code category} is null. */
    public static List<String> getFiles(@Nullable Category category) {
        if (category == null) return FILES;

        List<String> out = new ArrayList<>();
        for (String file : FILES) if (categoryOf(file) == category) out.add(file);
        return out;
    }

    /** Only the categories that actually contain something, so the gallery shows no empty tabs. */
    public static List<Category> populatedCategories() {
        List<Category> out = new ArrayList<>();
        for (Category c : Category.values())
            if (!getFiles(c).isEmpty()) out.add(c);
        return out;
    }

    public static String getSelected() {
        return selected;
    }

    public static void select(String fileName) {
        selected = fileName;
        try {
            if (!FOLDER.exists()) FOLDER.mkdirs();
            Files.writeString(SELECTION.toPath(), fileName == null ? "" : fileName, StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            // Not persisting only costs the choice on the next launch.
        }
    }

    /* ------------------------------------------------------------------ */
    /* Async decode                                                        */
    /* ------------------------------------------------------------------ */

    /**
     * The full-size wallpaper, or null while it is not ready.
     *
     * <p>Safe to call every frame: the first call queues one decode and the rest are map lookups.
     */
    private static @Nullable Uploaded full(String fileName) {
        if (fileName == null) return null;

        File file = new File(FOLDER, fileName);
        if (!file.isFile()) return null;

        long stamp = file.lastModified();
        Uploaded cached = FULL.get(fileName);
        // Re-decoded when the file on disk changes, which is what makes editing a wallpaper in place work.
        if (cached != null && cached.stamp() == stamp) return cached;

        queue(fileName, stamp, false);
        return cached;   // the stale copy keeps drawing until the new one lands, so nothing flickers
    }

    /** The thumbnail, or null while it is not ready. */
    private static @Nullable Uploaded thumb(String fileName) {
        if (fileName == null) return null;

        File file = new File(FOLDER, fileName);
        if (!file.isFile()) return null;

        long stamp = file.lastModified();

        Uploaded cached;
        synchronized (THUMBS) {
            cached = THUMBS.get(fileName);
        }
        if (cached != null && cached.stamp() == stamp) return cached;

        queue(fileName, stamp, true);
        return cached;
    }

    private static void queue(String fileName, long stamp, boolean thumbnail) {
        String key = (thumbnail ? "t:" : "f:") + fileName;
        if (!PENDING.add(key)) return;

        try {
            POOL.execute(() -> {
                try {
                    decode(fileName, stamp, thumbnail);
                } finally {
                    PENDING.remove(key);
                }
            });
        } catch (RuntimeException rejected) {
            // Queue full: drop it and let the next frame ask again.
            PENDING.remove(key);
        }
    }

    /** Off-thread: read, optionally downscale, then hand the registration to the render thread. */
    private static void decode(String fileName, long stamp, boolean thumbnail) {
        File file = new File(FOLDER, fileName);
        if (!file.isFile()) return;

        try {
            NativeImage image;

            if (thumbnail) {
                BufferedImage source;
                try (InputStream in = new FileInputStream(file)) {
                    source = javax.imageio.ImageIO.read(in);
                }
                if (source == null) return;

                BufferedImage scaled = downscale(source, THUMB_WIDTH);
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                // NativeImage reads PNG and nothing else, so a jpg has to be re-encoded regardless.
                javax.imageio.ImageIO.write(scaled, "png", out);
                image = NativeImage.read(new ByteArrayInputStream(out.toByteArray()));
            } else if (fileName.toLowerCase(Locale.ROOT).endsWith(".gif")) {
                // Animated: every composited frame becomes its own texture (the thumbnail above is frame one).
                GifFrames.Result gif = GifFrames.decode(file);
                List<NativeImage> frames = new ArrayList<>(gif.frames().size());
                for (BufferedImage frame : gif.frames()) frames.add(toNative(frame));
                registerAnimated(fileName, stamp, frames, gif.delaysMs());
                return;
            } else {
                try (InputStream in = new FileInputStream(file)) {
                    image = NativeImage.read(in);
                }
            }

            register(fileName, stamp, thumbnail, image);
        } catch (Throwable ignored) {
            // A broken or half-copied file just does not appear; the scan will try again when it changes.
        }
    }

    private static BufferedImage downscale(BufferedImage source, int maxWidth) {
        if (source.getWidth() <= maxWidth) return source;

        int height = Math.max(1, Math.round(source.getHeight() * (maxWidth / (float) source.getWidth())));
        BufferedImage out = new BufferedImage(maxWidth, height, BufferedImage.TYPE_INT_ARGB);

        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(source, 0, 0, maxWidth, height, null);
        g.dispose();

        return out;
    }

    private static void register(String fileName, long stamp, boolean thumbnail, NativeImage image) {
        // Texture registration is render-thread work; doing it off-thread is a race against the frame being
        // drawn and shows up as a texture that is bound but empty.
        mc.execute(() -> {
            try {
                Identifier id = Identifier.of("gombal", "textures/dynamic/"
                        + (thumbnail ? "thumb_" : "wall_") + sanitize(fileName));

                mc.getTextureManager().registerTexture(id,
                        new NativeImageBackedTexture(() -> "gombal_wallpaper", image));

                Uploaded uploaded = new Uploaded(id, image.getWidth(), image.getHeight(), stamp);

                if (thumbnail) {
                    synchronized (THUMBS) {
                        THUMBS.put(fileName, uploaded);
                        evictThumbs();
                    }
                } else {
                    FULL.put(fileName, uploaded);
                    trimFull();
                }
            } catch (Throwable ignored) {
                image.close();
            }
        });
    }

    private static NativeImage toNative(BufferedImage src) {
        NativeImage out = new NativeImage(src.getWidth(), src.getHeight(), false);
        for (int y = 0; y < src.getHeight(); y++)
            for (int x = 0; x < src.getWidth(); x++) out.setColorArgb(x, y, src.getRGB(x, y));
        return out;
    }

    private static void registerAnimated(String fileName, long stamp, List<NativeImage> images, int[] delays) {
        mc.execute(() -> {
            Identifier[] ids = new Identifier[images.size()];
            try {
                String base = "textures/dynamic/gif_" + sanitize(fileName) + "_";
                for (int i = 0; i < ids.length; i++) {
                    ids[i] = Identifier.of("gombal", base + i);
                    mc.getTextureManager().registerTexture(ids[i], new NativeImageBackedTexture(() -> "gombal_wallpaper", images.get(i)));
                }
                int loop = 0;
                for (int d : delays) loop += d;
                Uploaded uploaded = new Uploaded(ids[0], images.getFirst().getWidth(), images.getFirst().getHeight(),
                        stamp, ids, delays, loop);
                Uploaded previous = FULL.put(fileName, uploaded);
                if (previous != null && previous.frames() != uploaded.frames()) destroy(previous);
                trimFull();
            } catch (Throwable t) {
                images.forEach(NativeImage::close);
            }
        });
    }

    /** Frees the least recently drawn thumbnails once the budget is passed. Render thread only. */
    private static void evictThumbs() {
        if (THUMBS.size() <= THUMB_BUDGET) return;

        var iterator = THUMBS.entrySet().iterator();
        while (THUMBS.size() > THUMB_BUDGET && iterator.hasNext()) {
            Map.Entry<String, Uploaded> oldest = iterator.next();
            destroy(oldest.getValue());
            iterator.remove();
        }
    }

    /**
     * Keeps at most the current wallpaper and the one crossfading in.
     *
     * <p>A full-size 4K texture is about 33MB, so holding a third one is a real cost for something nothing can
     * be drawing - at most two are visible at any instant, by construction of the crossfade.
     */
    private static void trimFull() {
        if (FULL.size() <= 2) return;

        for (String key : new ArrayList<>(FULL.keySet())) {
            if (key.equals(selected) || key.equals(fadingFrom) || key.equals(fadingTo)) continue;
            Uploaded gone = FULL.remove(key);
            destroy(gone);
        }
    }

    private static void destroy(@Nullable Uploaded uploaded) {
        if (uploaded == null) return;
        try {
            if (uploaded.frames() != null) {
                for (Identifier frame : uploaded.frames()) mc.getTextureManager().destroyTexture(frame);
                return;
            }
            mc.getTextureManager().destroyTexture(uploaded.id());
        } catch (Throwable ignored) {
            // Already dropped by the manager - nothing to do.
        }
    }

    /** Identifiers only accept [a-z0-9_.-/]; file names accept far more than that. */
    private static String sanitize(String fileName) {
        return fileName.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_.-]", "_");
    }

    /* ------------------------------------------------------------------ */
    /* Crossfade                                                           */
    /* ------------------------------------------------------------------ */

    private static String fadingFrom;
    private static String fadingTo;
    private static long fadeStart;
    private static long fadeMs = 650L;

    /**
     * Starts a dissolve from the current wallpaper to a new one.
     *
     * <p>The selection is written immediately but the OLD texture is kept until the fade finishes, which is
     * what removes the black flash: there is never a frame with nothing to draw. If the new wallpaper has not
     * finished decoding when the fade starts, the old one simply holds at full opacity until it has - so a
     * slow disk delays the dissolve instead of showing a hole.
     */
    public static void crossfadeTo(@Nullable String fileName, long durationMs) {
        if (java.util.Objects.equals(fileName, selected)) return;

        fadingFrom = selected;
        fadingTo = fileName;
        fadeMs = Math.max(1L, durationMs);
        fadeStart = System.currentTimeMillis();

        select(fileName);

        // Start the decode now rather than on the first draw, so the fade has something to fade into.
        if (fileName != null) full(fileName);
    }

    /** 0..1 through the current dissolve, 1 when there is none in progress. */
    private static float fadeProgress() {
        if (fadingTo == null && fadingFrom == null) return 1f;
        return Math.clamp((System.currentTimeMillis() - fadeStart) / (float) fadeMs, 0f, 1f);
    }

    public static boolean crossfading() {
        return fadeProgress() < 1f;
    }

    /* ------------------------------------------------------------------ */
    /* Drawing                                                             */
    /* ------------------------------------------------------------------ */

    /**
     * Draws a wallpaper scaled to COVER the rect: it fills the whole area, the overflowing axis is cropped and
     * the aspect ratio is kept, so images of any resolution stay undistorted at any window size.
     *
     * @param thumbnail true for a gallery card - uses the small texture and never uploads a full-size one
     * @param zoom      1 for an exact fit; above 1 crops in further, which is what the slow cinematic zoom
     *                  rides on
     */
    public static boolean drawCover(DrawContext context, @Nullable String fileName,
                                    float x, float y, float width, float height,
                                    boolean thumbnail, float zoom, float panX, float panY, float alpha) {
        Uploaded wp = thumbnail ? thumb(fileName) : full(fileName);
        if (wp == null || alpha <= 0.004f) return wp != null;

        float scale = Math.max(width / wp.width(), height / wp.height()) * Math.max(1f, zoom);
        float regionW = width / scale, regionH = height / scale;

        // Centre the crop, then nudge it by the parallax offset - clamped so the window can never slide past
        // the edge of the image and sample outside it.
        float maxU = Math.max(0f, wp.width() - regionW);
        float maxV = Math.max(0f, wp.height() - regionH);
        float u = Math.clamp(maxU / 2f + panX * maxU / 2f, 0f, maxU);
        float v = Math.clamp(maxV / 2f + panY * maxV / 2f, 0f, maxV);

        int a = Math.round(Math.clamp(alpha, 0f, 1f) * 255f);
        Render2DEngine.renderTexture(context, wp.current(), x, y, width, height,
                u, v, regionW, regionH, wp.width(), wp.height(), new Color(255, 255, 255, a));
        return true;
    }

    /** The simple form, kept because the older screens call it. */
    public static boolean drawCover(DrawContext context, String fileName, float x, float y, float width, float height) {
        return drawCover(context, fileName, x, y, width, height, false, 1f, 0f, 0f, 1f);
    }

    /** A gallery card's preview. */
    public static boolean drawThumb(DrawContext context, String fileName, float x, float y, float width, float height) {
        return drawCover(context, fileName, x, y, width, height, true, 1f, 0f, 0f, 1f);
    }

    /**
     * The full-screen wallpaper, dissolving when one has just been chosen.
     *
     * @return false when there is nothing to draw at all - the caller renders the vanilla panorama instead
     */
    public static boolean render(DrawContext context, float width, float height, float zoom, float panX, float panY) {
        float progress = fadeProgress();

        if (progress >= 1f) {
            // Settled: drop whatever the fade was holding on to and draw the one wallpaper.
            if (fadingFrom != null || fadingTo != null) {
                fadingFrom = null;
                fadingTo = null;
                trimFull();
            }
            return drawCover(context, selected, 0, 0, width, height, false, zoom, panX, panY, 1f);
        }

        boolean incomingReady = fadingTo == null || FULL.containsKey(fadingTo);

        // The outgoing image holds at full opacity until the incoming one exists, so the dissolve can never
        // reveal an empty frame. This is the whole of "no black flash".
        float in = incomingReady ? progress : 0f;
        float out = incomingReady ? 1f - progress : 1f;

        boolean drewAnything = false;
        if (fadingFrom != null)
            drewAnything = drawCover(context, fadingFrom, 0, 0, width, height, false, zoom, panX, panY, out);
        if (fadingTo != null && in > 0f)
            drewAnything |= drawCover(context, fadingTo, 0, 0, width, height, false, zoom, panX, panY, in);

        // Fading from the panorama to an image: let the panorama show through underneath.
        return drewAnything && fadingFrom != null;
    }

    /** The plain form, for screens that want no cinematic motion. */
    public static boolean render(DrawContext context, float width, float height) {
        boolean drawn = render(context, width, height, 1f, 0f, 0f);
        if (drawn) Render2DEngine.drawRound(context, 0, 0, width, height, 0f, new Color(0, 0, 0, 90));
        return drawn;
    }

    /** Drops every upload, for a resource reload or a cleared folder. */
    public static void forget() {
        mc.execute(() -> {
            synchronized (THUMBS) {
                THUMBS.values().forEach(MenuBackground::destroy);
                THUMBS.clear();
            }
            FULL.values().forEach(MenuBackground::destroy);
            FULL.clear();
            PENDING.clear();
        });
    }
}
