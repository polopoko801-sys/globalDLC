package thunder.hack.utility.world;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.block.BlockState;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * The printer's todo list, bucketed into 16x16x16 cells so "what does the build need around me" is a
 * question about a handful of cells instead of a walk over the whole schematic.
 *
 * <p>The flat list this replaces could only ever look at its first 32768 entries per tick, which on a
 * 150k block schematic meant that standing anywhere past the beginning of it found nothing at all: no
 * blocks to place, no materials to fetch, no warning - a printer that just stood there.
 *
 * <p>Blocks are kept relative to the schematic origin, exactly as {@link Schematic} stores them. Progress
 * is a set of finished positions rather than removal from the list, so nothing has to be re-bucketed
 * while the build runs.
 */
public final class BuildQueue {
    /** One block of the build. {@code surface} is false for blocks walled in on all six sides. */
    public record Block(BlockPos rel, BlockState state, boolean surface) {
    }

    /** Cell key plus the distance its centre sits at, for nearest-first iteration. */
    public record Cell(long key, double distance) {
    }

    private static final int CELL_SHIFT = 4;         // 16 blocks
    private static final double CELL_RADIUS = 13.86; // half diagonal of a 16 cube

    private final Long2ObjectOpenHashMap<List<Block>> cells = new Long2ObjectOpenHashMap<>();
    private final LongOpenHashSet done = new LongOpenHashSet();
    private int total;

    /**
     * How many blocks each layer of the build still needs, indexed by the schematic's own Y. It is what
     * lets the printer work bottom up without ever walking the block list to ask "which layer now" -
     * placing is decrementing one counter, and the answer is a cursor that only ever moves up.
     */
    private int[] layerRemaining = new int[0];
    private int layerCursor;

    public void clear() {
        cells.clear();
        done.clear();
        layerRemaining = new int[0];
        layerCursor = 0;
        total = 0;
    }

    /** Buckets a schematic. Also works out which blocks are visible from outside, for the ghost. */
    public void load(@Nullable Schematic schematic) {
        clear();
        if (schematic == null) return;

        LongOpenHashSet filled = new LongOpenHashSet(schematic.size());
        int height = 1;
        for (BlockPos pos : schematic.getBlocks().keySet()) {
            filled.add(pos.asLong());
            height = Math.max(height, pos.getY() + 1);
        }
        layerRemaining = new int[height];

        for (Map.Entry<BlockPos, BlockState> entry : schematic.getBlocks().entrySet()) {
            BlockPos pos = entry.getKey();
            if (pos.getY() >= 0 && pos.getY() < height) layerRemaining[pos.getY()]++;
            boolean surface = !(filled.contains(pos.east().asLong()) && filled.contains(pos.west().asLong())
                    && filled.contains(pos.up().asLong()) && filled.contains(pos.down().asLong())
                    && filled.contains(pos.south().asLong()) && filled.contains(pos.north().asLong()));

            cells.computeIfAbsent(cellKey(pos), k -> new ArrayList<>()).add(new Block(pos, entry.getValue(), surface));
            total++;
        }
    }

    public int total() {
        return total;
    }

    public int remaining() {
        return Math.max(0, total - done.size());
    }

    public boolean isEmpty() {
        return total == 0;
    }

    public boolean isDone(@NotNull BlockPos rel) {
        return done.contains(rel.asLong());
    }

    public void markDone(@NotNull BlockPos rel) {
        if (!done.add(rel.asLong())) return;

        int y = rel.getY();
        if (y >= 0 && y < layerRemaining.length) layerRemaining[y]--;
    }

    /**
     * The lowest layer that still needs blocks - where a bottom-up printer works. The cursor only moves
     * upwards, so this costs nothing on the ticks where nothing changed.
     */
    public int activeLayer() {
        while (layerCursor < layerRemaining.length && layerRemaining[layerCursor] <= 0) layerCursor++;
        return layerCursor;
    }

    /** Blocks still missing from a given layer. */
    public int layerRemaining(int y) {
        return y >= 0 && y < layerRemaining.length ? Math.max(0, layerRemaining[y]) : 0;
    }

    public int height() {
        return layerRemaining.length;
    }

    /**
     * How many of each item have actually been verified as built, for the material list. Walks the
     * buckets rather than keeping a running tally, because the done set is also filled by the world
     * scan finding blocks the printer never placed - a partly built house loaded from a schematic.
     */
    public @NotNull Map<Item, Integer> doneByItem() {
        Map<Item, Integer> out = new java.util.LinkedHashMap<>();
        if (done.isEmpty()) return out;

        for (List<Block> list : cells.values())
            for (Block block : list) {
                if (!done.contains(block.rel().asLong())) continue;
                Item item = block.state().getBlock().asItem();
                if (item != Items.AIR) out.merge(item, 1, Integer::sum);
            }

        return out;
    }

    /**
     * Takes over another queue's buckets wholesale.
     *
     * <p>Used by the async load: the staging queue is filled on a worker in isolation and only handed
     * over here, on the main thread, so nothing can ever read a half-bucketed queue.
     */
    public void adopt(@NotNull BuildQueue other) {
        clear();
        cells.putAll(other.cells);
        total = other.total;
        layerRemaining = other.layerRemaining.clone();
        layerCursor = 0;
    }

    /** Forgets what was already built without dropping the buckets - used when the origin moves. */
    public void resetProgress() {
        done.clear();
        layerCursor = 0;

        java.util.Arrays.fill(layerRemaining, 0);
        for (List<Block> list : cells.values())
            for (Block block : list) {
                int y = block.rel().getY();
                if (y >= 0 && y < layerRemaining.length) layerRemaining[y]++;
            }
    }

    /** Lowest schematic Y a cell can hold - lets a layer-limited scan drop whole cells at a glance. */
    public static int cellMinY(long key) {
        return BlockPos.fromLong(key).getY() << CELL_SHIFT;
    }

    public List<Block> cell(long key) {
        List<Block> list = cells.get(key);
        return list == null ? List.of() : list;
    }

    /**
     * Every cell with anything left in it, nearest first, measured from a world-space point. Cells whose
     * nearest possible corner is further away than {@code maxDistance} are dropped.
     */
    public @NotNull List<Cell> cellsByDistance(@NotNull Vec3d point, @NotNull BlockPos origin, double maxDistance) {
        List<Cell> out = new ArrayList<>(cells.size());

        for (long key : cells.keySet()) {
            BlockPos cell = BlockPos.fromLong(key);
            double cx = origin.getX() + (cell.getX() << CELL_SHIFT) + 8.0;
            double cy = origin.getY() + (cell.getY() << CELL_SHIFT) + 8.0;
            double cz = origin.getZ() + (cell.getZ() << CELL_SHIFT) + 8.0;

            double distance = Math.sqrt(point.squaredDistanceTo(cx, cy, cz));
            if (distance - CELL_RADIUS > maxDistance) continue;

            out.add(new Cell(key, distance));
        }

        out.sort(Comparator.comparingDouble(Cell::distance));
        return out;
    }

    private static long cellKey(@NotNull BlockPos pos) {
        return new BlockPos(pos.getX() >> CELL_SHIFT, pos.getY() >> CELL_SHIFT, pos.getZ() >> CELL_SHIFT).asLong();
    }
}
