package thunder.hack.features.modules.misc;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.WorldChunk;
import thunder.hack.core.Managers;
import thunder.hack.features.modules.Module;
import thunder.hack.setting.Setting;
import thunder.hack.setting.impl.ColorSetting;
import thunder.hack.utility.Timer;
import thunder.hack.utility.render.Render2DEngine;
import thunder.hack.utility.render.core.CloudanOutlineRenderer;
import thunder.hack.utility.render.core.CloudanTextRenderer;

import java.awt.Color;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A debug read-out of the chunks the client already has.
 *
 * <p>It looks at loaded chunk data and nothing else: no probing, no requesting, no guessing about chunks
 * the server has not sent. Everything it reports is something your own client could already tell you if
 * you flew through and counted by hand - it just counts faster and puts a number on it.
 *
 * <p>What it measures is <em>shape</em>: how much of a chunk is solid, how much of that is air pockets
 * underground, and how much of it is material that does not occur by itself. A chunk of untouched stone
 * and a chunk with a corridor cut through it have very different numbers, and the anomaly score is those
 * numbers combined into one.
 *
 * <p>Deliberately coarse. It is a diagnostic overlay for looking at terrain, and it is not wired to any
 * kind of alert, log or report.
 */
public class ChunkAnalyzer extends Module {
    public ChunkAnalyzer() {
        super("ChunkAnalyzer", Category.MISC);
    }

    private final Setting<Integer> radius = new Setting<>("Radius", 4, 1, 12);
    private final Setting<Integer> rescanSeconds = new Setting<>("RescanSeconds", 6, 1, 60);
    /** Only chunks at or above this score get drawn at all. */
    private final Setting<Integer> threshold = new Setting<>("Threshold", 35, 0, 100);
    private final Setting<Boolean> outline = new Setting<>("Outline", true);
    private final Setting<Boolean> label = new Setting<>("Label", true);
    private final Setting<ColorSetting> lowColor = new Setting<>("LowColor", new ColorSetting(0xFF4ADE80));
    private final Setting<ColorSetting> highColor = new Setting<>("HighColor", new ColorSetting(0xFFFF566E));
    private final Setting<Boolean> debug = new Setting<>("Debug", false);

    /** One chunk's numbers. Kept between scans so the overlay does not flicker while re-analysing. */
    public record Analysis(int density, int pockets, int clusters, int artificial, int score) {
    }

    private final Map<Long, Analysis> cache = new LinkedHashMap<>();
    private final Timer rescan = new Timer();

    /** Sampling step. Every fourth block in each axis - the shape survives, the cost does not. */
    private static final int STEP = 4;

    @Override
    public void onEnable() {
        cache.clear();
    }

    @Override
    public void onDisable() {
        cache.clear();
    }

    @Override
    public void onLogout() {
        cache.clear();
    }

    @Override
    public void onUpdate() {
        if (fullNullCheck()) return;
        if (!rescan.passedS(rescanSeconds.getValue())) return;
        rescan.reset();

        ChunkPos centre = mc.player.getChunkPos();
        int r = radius.getValue();

        // Off the render thread: even sampled, a 9x9 chunk sweep is far too much to do in a frame.
        Managers.ASYNC.run(() -> {
            Map<Long, Analysis> results = new LinkedHashMap<>();

            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    ChunkPos pos = new ChunkPos(centre.x + dx, centre.z + dz);
                    WorldChunk chunk = mc.world == null ? null : mc.world.getChunkManager().getWorldChunk(pos.x, pos.z);
                    // Not loaded = not our business. We never ask for it.
                    if (chunk == null) continue;

                    try {
                        results.put(pos.toLong(), analyse(chunk));
                    } catch (Throwable ignored) {
                        // A chunk unloading mid-scan is normal; drop it and carry on.
                    }
                }
            }

            mc.execute(() -> {
                cache.clear();
                cache.putAll(results);
            });
        });
    }

    /**
     * Samples one chunk and turns it into four numbers plus a score.
     *
     * <p>Density is how much of the sample is solid. Pockets are solid-surrounded air below sea level -
     * the signature of anything hollow. Clusters are storage blocks found next to each other. Artificial
     * counts materials that do not generate on their own at that depth.
     */
    private Analysis analyse(WorldChunk chunk) {
        int solid = 0, sampled = 0, pockets = 0, storage = 0, artificial = 0;

        int bottom = Math.max(chunk.getBottomY(), -60);
        int top = Math.min(chunk.getTopYInclusive(), 120);

        BlockPos.Mutable cursor = new BlockPos.Mutable();
        int baseX = chunk.getPos().getStartX();
        int baseZ = chunk.getPos().getStartZ();

        for (int x = 0; x < 16; x += STEP) {
            for (int z = 0; z < 16; z += STEP) {
                for (int y = bottom; y <= top; y += STEP) {
                    cursor.set(baseX + x, y, baseZ + z);
                    BlockState state = chunk.getBlockState(cursor);
                    sampled++;

                    if (state.isAir()) {
                        // Air with stone above AND below it, underground, is a hollow - a cave or a dug
                        // corridor. Above sea level it is just sky.
                        if (y < 56
                                && !chunk.getBlockState(cursor.set(baseX + x, y + 1, baseZ + z)).isAir()
                                && !chunk.getBlockState(cursor.set(baseX + x, y - 1, baseZ + z)).isAir())
                            pockets++;
                        continue;
                    }

                    solid++;
                    Block block = state.getBlock();

                    if (isStorage(block)) storage++;
                    if (isArtificial(block, y)) artificial++;
                }
            }
        }

        if (sampled == 0) return new Analysis(0, 0, 0, 0, 0);

        int density = Math.round(solid * 100f / sampled);
        int pocketScore = Math.min(100, Math.round(pockets * 600f / sampled));
        int clusterScore = Math.min(100, storage * 12);
        int artificialScore = Math.min(100, Math.round(artificial * 400f / sampled));

        // Storage and man-made material dominate: hollows alone are usually just caves.
        int score = Math.min(100, Math.round(
                clusterScore * 0.45f + artificialScore * 0.35f + pocketScore * 0.20f));

        return new Analysis(density, pocketScore, clusterScore, artificialScore, score);
    }

    private static boolean isStorage(Block block) {
        return block == Blocks.CHEST || block == Blocks.TRAPPED_CHEST || block == Blocks.BARREL
                || block == Blocks.ENDER_CHEST || block == Blocks.HOPPER
                || block.getName().getString().toLowerCase().contains("shulker");
    }

    /** Materials that do not occur by themselves where they were found. */
    private static boolean isArtificial(Block block, int y) {
        if (block == Blocks.CRAFTING_TABLE || block == Blocks.FURNACE || block == Blocks.ANVIL
                || block == Blocks.ENCHANTING_TABLE || block == Blocks.BEACON
                || block == Blocks.OBSIDIAN && y > 20)
            return true;

        String name = block.getName().getString().toLowerCase();
        return name.contains("planks") && y < 50
                || name.contains("glass") && y < 50
                || name.contains("concrete")
                || name.contains("wool") && y < 50;
    }

    /* ------------------------------------------------------------------ */
    /* Overlay                                                             */
    /* ------------------------------------------------------------------ */

    @Override
    public void onRender2D(DrawContext context) {
        if (fullNullCheck() || mc.options.hudHidden || cache.isEmpty()) return;

        Color low = lowColor.getValue().getColorObject();
        Color high = highColor.getValue().getColorObject();
        int floor = threshold.getValue();

        for (Map.Entry<Long, Analysis> entry : cache.entrySet()) {
            Analysis analysis = entry.getValue();
            if (analysis.score() < floor) continue;

            ChunkPos pos = new ChunkPos(entry.getKey());
            float t = analysis.score() / 100f;
            Color tint = blend(low, high, t);

            // Corner markers and a thin edge, never a solid slab of colour over the terrain.
            double y = mc.player.getY();
            Box box = new Box(pos.getStartX(), y - 1, pos.getStartZ(), pos.getEndX() + 1, y + 2, pos.getEndZ() + 1);

            if (outline.getValue())
                CloudanOutlineRenderer.box(box, null, Render2DEngine.injectAlpha(tint, 190), 1.2f, 256);

            if (label.getValue())
                CloudanTextRenderer.draw(context, analysis.score() + (debug.getValue()
                                ? "  d" + analysis.density() + " p" + analysis.pockets() + " c" + analysis.clusters()
                                : ""),
                        box.getCenter(), tint, 0.7f, true, 256);
        }
    }

    private static Color blend(Color from, Color to, float t) {
        float clamped = Math.max(0f, Math.min(1f, t));
        return new Color(
                Math.round(from.getRed() + (to.getRed() - from.getRed()) * clamped),
                Math.round(from.getGreen() + (to.getGreen() - from.getGreen()) * clamped),
                Math.round(from.getBlue() + (to.getBlue() - from.getBlue()) * clamped),
                255);
    }

    @Override
    public String getDisplayInfo() {
        return cache.isEmpty() ? null : cache.size() + "";
    }
}
