package thunder.hack.gui.build;

import net.minecraft.block.BlockState;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix3x2f;
import thunder.hack.features.modules.misc.AutoBuild;
import thunder.hack.utility.render.GuiRender;
import thunder.hack.utility.render.Render2DEngine;
import thunder.hack.utility.world.Schematic;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static thunder.hack.features.modules.Module.mc;

/**
 * The isometric picture of the loaded schematic drawn inside {@link BuildScreen}.
 *
 * <p>Every voxel is drawn as three flat quads (top plus the two vertical faces that face the viewer) in
 * the block's own map colour. The projection is linear, so the eight cube corners are the base point
 * plus three per-frame offset vectors instead of eight rotations per block - which is what makes a 20k
 * block schematic drawable at all.
 *
 * <p>Big builds are not thinned out by dropping every n-th block - that turns a house into a cloud of
 * loose cubes. They are merged onto a coarser grid instead: a cell is solid when anything inside it is,
 * so the silhouette, the walls and the roof all survive, the model just gets chunkier.
 *
 * <p>Nothing here touches the 3D pipeline: the world renderer's queue double-rotates on 1.21.11, and a
 * GUI window has no business going near it anyway.
 */
public class BuildPreview {
    /** Above this many drawn cubes the model is merged onto a coarser grid (see {@link #chooseCell}). */
    private static final int MAX_VOXELS = 45000;

    private static final int FACE_EAST = 1;   // +X
    private static final int FACE_WEST = 2;   // -X
    private static final int FACE_SOUTH = 4;  // +Z
    private static final int FACE_NORTH = 8;  // -Z
    private static final int FACE_UP = 16;

    /** {@code ao} counts the neighbours sitting one level up around the block: the top face's shadow. */
    private record Voxel(int x, int y, int z, int rgb, int faces, int ao, boolean covered) {
    }

    private final List<Voxel> voxels = new ArrayList<>();
    /** Draw order, packed as (depth key << 32 | index) so re-sorting a big model needs no boxing. */
    private long[] order = new long[0];

    /** What the cache was built from - a different schematic (or a rotate/mirror) rebuilds it. */
    private Schematic cachedFor;
    private int cachedSize = -1;
    private int cachedHash;
    private boolean orderDirty = true;
    /** How many blocks one preview cube stands for on each axis; 1 = the real thing. */
    private int cell = 1;

    // ---- view ----
    private float yaw = 0.7f, pitch = 0.55f, zoom = 1f;
    private float panX, panY;
    private float sortedYaw = Float.NaN, sortedPitch = Float.NaN;

    private int maxY = Integer.MAX_VALUE;
    private int height = 1;

    public void rotate(float dYaw, float dPitch) {
        yaw += dYaw;
        pitch = Math.max(0.08f, Math.min(1.45f, pitch + dPitch));
        orderDirty = true;
    }

    public void pan(float dx, float dy) {
        panX += dx;
        panY += dy;
    }

    public void zoom(float factor) {
        zoom = Math.max(0.15f, Math.min(8f, zoom * factor));
    }

    public void resetView() {
        yaw = 0.7f;
        pitch = 0.55f;
        zoom = 1f;
        panX = panY = 0f;
        maxY = Integer.MAX_VALUE;
        orderDirty = true;
    }

    public int getHeight() {
        return height;
    }

    /** 1..height, where height means "show everything". */
    public int getLayer() {
        return maxY == Integer.MAX_VALUE ? height : Math.min(maxY + 1, height);
    }

    public void setLayer(int layer) {
        int clamped = Math.max(1, Math.min(height, layer));
        maxY = clamped >= height ? Integer.MAX_VALUE : clamped - 1;
        orderDirty = true;
    }

    public boolean isThinnedOut() {
        return cell > 1;
    }

    /** How many blocks one drawn cube stands for on each axis. */
    public int getCell() {
        return cell;
    }

    public int getVisibleBlocks() {
        return voxels.size();
    }

    /**
     * Draws the schematic into the given rectangle. Returns false when there is nothing loaded, so the
     * caller can paint its "no schematic" hint instead.
     */
    public boolean render(DrawContext context, float x, float y, float w, float h) {
        sync(AutoBuild.schematic);
        if (voxels.isEmpty()) return false;

        double cosYaw = Math.cos(yaw), sinYaw = Math.sin(yaw);
        double cosPitch = Math.cos(pitch), sinPitch = Math.sin(pitch);

        // Unit steps of the three world axes in screen space, at scale 1.
        float ax = (float) cosYaw, ay = (float) (sinYaw * sinPitch);
        float bx = 0f, by = (float) -cosPitch;
        float cx = (float) -sinYaw, cy = (float) (cosYaw * sinPitch);

        // Fit: project the bounding box corners, then scale the result into the viewport. In cell units,
        // because that is what the voxel coordinates are in.
        Schematic schematic = AutoBuild.schematic;
        int dimX = ceilDiv(schematic.getDimensions().getX(), cell);
        int dimY = ceilDiv(Math.min(schematic.getDimensions().getY(), getLayer()), cell);
        int dimZ = ceilDiv(schematic.getDimensions().getZ(), cell);

        float minSx = Float.MAX_VALUE, maxSx = -Float.MAX_VALUE, minSy = Float.MAX_VALUE, maxSy = -Float.MAX_VALUE;
        for (int i = 0; i < 8; i++) {
            float px = (i & 1) == 0 ? 0 : dimX;
            float py = (i & 2) == 0 ? 0 : dimY;
            float pz = (i & 4) == 0 ? 0 : dimZ;
            float sx = px * ax + py * bx + pz * cx;
            float sy = px * ay + py * by + pz * cy;
            minSx = Math.min(minSx, sx);
            maxSx = Math.max(maxSx, sx);
            minSy = Math.min(minSy, sy);
            maxSy = Math.max(maxSy, sy);
        }

        float spanX = Math.max(0.001f, maxSx - minSx);
        float spanY = Math.max(0.001f, maxSy - minSy);
        float scale = Math.min((w - 16f) / spanX, (h - 16f) / spanY) * zoom;
        float baseX = x + w / 2f - (minSx + maxSx) / 2f * scale + panX;
        float baseY = y + h / 2f - (minSy + maxSy) / 2f * scale + panY;

        ax *= scale; ay *= scale;
        bx *= scale; by *= scale;
        cx *= scale; cy *= scale;

        sortIfNeeded(cosYaw, sinYaw, cosPitch, sinPitch);

        boolean eastVisible = sinYaw > 0, westVisible = sinYaw < 0;
        boolean southVisible = cosYaw > 0, northVisible = cosYaw < 0;

        final float fax = ax, fay = ay, fbx = bx, fby = by, fcx = cx, fcy = cy;
        final float fBaseX = baseX, fBaseY = baseY;
        // The layer slider counts blocks; the model may be built out of coarser cells.
        final int layerTop = maxY == Integer.MAX_VALUE ? Integer.MAX_VALUE : maxY / cell;

        Matrix3x2f pose = GuiRender.pose(context);
        GuiRender.quads(context, Render2DEngine.currentClip(), vc -> {
            for (long entry : order) {
                Voxel v = voxels.get((int) (entry & 0xFFFFFFFFL));
                if (v.y() > layerTop) continue;

                float ox = fBaseX + v.x() * fax + v.y() * fbx + v.z() * fcx;
                float oy = fBaseY + v.x() * fay + v.y() * fby + v.z() * fcy;

                // corner(dx, dy, dz) = origin + dx*A + dy*B + dz*C
                float x000 = ox, y000 = oy;
                float x100 = ox + fax, y100 = oy + fay;
                float x010 = ox + fbx, y010 = oy + fby;
                float x001 = ox + fcx, y001 = oy + fcy;
                float x110 = x100 + fbx, y110 = y100 + fby;
                float x101 = x100 + fcx, y101 = y100 + fcy;
                float x011 = x010 + fcx, y011 = y010 + fcy;
                float x111 = x110 + fcx, y111 = y110 + fcy;

                // A block whose top is buried still needs one when the slider cuts the build open there.
                boolean cut = v.y() == layerTop && v.covered();
                if ((v.faces() & FACE_UP) != 0 || cut) {
                    // Cut faces go flat and darker, so a sliced layer reads as the inside of the build.
                    float light = cut ? 0.72f : 1f - 0.06f * v.ao();
                    face(vc, pose, x010, y010, x110, y110, x111, y111, x011, y011, shade(v.rgb(), light));
                }

                float sideLight = v.covered() && !cut ? 0.70f : 0.80f;
                if (eastVisible && (v.faces() & FACE_EAST) != 0) {
                    face(vc, pose, x100, y100, x110, y110, x111, y111, x101, y101, shade(v.rgb(), sideLight));
                } else if (westVisible && (v.faces() & FACE_WEST) != 0) {
                    face(vc, pose, x000, y000, x010, y010, x011, y011, x001, y001, shade(v.rgb(), sideLight));
                }

                float frontLight = sideLight - 0.18f;
                if (southVisible && (v.faces() & FACE_SOUTH) != 0) {
                    face(vc, pose, x001, y001, x101, y101, x111, y111, x011, y011, shade(v.rgb(), frontLight));
                } else if (northVisible && (v.faces() & FACE_NORTH) != 0) {
                    face(vc, pose, x000, y000, x100, y100, x110, y110, x010, y010, shade(v.rgb(), frontLight));
                }
            }
        });

        return true;
    }

    /** Rebuilds the voxel cache when the schematic changed under us (load, rotate, mirror, clear). */
    private void sync(@Nullable Schematic schematic) {
        if (schematic == null) {
            if (cachedFor != null) {
                voxels.clear();
                order = new long[0];
                cachedFor = null;
                cachedSize = -1;
                cell = 1;
                height = 1;
            }
            return;
        }

        // A rotate/mirror keeps the same object and the same block count, but every position moves -
        // the first key is enough to notice that.
        int hash = schematic.getBlocks().isEmpty() ? 0 : schematic.getBlocks().keySet().iterator().next().hashCode();
        if (schematic == cachedFor && schematic.size() == cachedSize && hash == cachedHash) return;

        cachedFor = schematic;
        cachedSize = schematic.size();
        cachedHash = hash;
        rebuild(schematic);
    }

    /**
     * Turns the schematic into the cubes we actually draw: merged onto a grid coarse enough to stay
     * drawable, then reduced to the cells that have at least one exposed face - the inside of a build is
     * never seen, except through the layer slider.
     */
    private void rebuild(@NotNull Schematic schematic) {
        voxels.clear();
        height = Math.max(1, schematic.getDimensions().getY());
        cell = chooseCell(schematic);

        // Cell -> colour of the highest block in it. The top of a cell is what the eye sees of it, so a
        // roof stays roof-coloured instead of picking up whatever the merge happened to hit first.
        Map<Long, int[]> cells = new HashMap<>();
        for (Map.Entry<BlockPos, BlockState> entry : schematic.getBlocks().entrySet()) {
            BlockPos pos = entry.getKey();
            long key = key(Math.floorDiv(pos.getX(), cell), Math.floorDiv(pos.getY(), cell), Math.floorDiv(pos.getZ(), cell));

            int[] prev = cells.get(key);
            if (prev == null || pos.getY() > prev[1])
                cells.put(key, new int[]{blockColor(entry.getValue(), pos), pos.getY()});
        }

        Set<Long> filled = cells.keySet();
        for (Map.Entry<Long, int[]> entry : cells.entrySet()) {
            long key = entry.getKey();
            int cx = unpackX(key), cy = unpackY(key), cz = unpackZ(key);

            int faces = 0;
            if (!filled.contains(key(cx + 1, cy, cz))) faces |= FACE_EAST;
            if (!filled.contains(key(cx - 1, cy, cz))) faces |= FACE_WEST;
            if (!filled.contains(key(cx, cy, cz + 1))) faces |= FACE_SOUTH;
            if (!filled.contains(key(cx, cy, cz - 1))) faces |= FACE_NORTH;

            boolean covered = filled.contains(key(cx, cy + 1, cz));
            if (!covered) faces |= FACE_UP;

            // Cells with no exposed face at all are kept on purpose - they draw nothing until the layer
            // slider cuts the build open on them, and then they are the surface you are looking at.

            // Contact shadow: how much of the level above hems this cell in. Flat roofs and open walls
            // stay bright, inner corners and the foot of a wall go darker - that is what reads as depth.
            int ao = 0;
            if (filled.contains(key(cx + 1, cy + 1, cz))) ao++;
            if (filled.contains(key(cx - 1, cy + 1, cz))) ao++;
            if (filled.contains(key(cx, cy + 1, cz + 1))) ao++;
            if (filled.contains(key(cx, cy + 1, cz - 1))) ao++;

            voxels.add(new Voxel(cx, cy, cz, entry.getValue()[0], faces, ao, covered));
        }

        order = new long[voxels.size()];
        for (int i = 0; i < order.length; i++) order[i] = i;
        orderDirty = true;
    }

    /**
     * The smallest merge factor that keeps the model under the draw budget. Merging is cubic, so one step
     * up already takes eight times fewer cubes - even a two million block city lands on cell 4 or 5.
     */
    private static int chooseCell(@NotNull Schematic schematic) {
        if (schematic.size() <= MAX_VOXELS) return 1;

        for (int c = 2; c < 32; c++) {
            Set<Long> seen = new HashSet<>();
            for (BlockPos pos : schematic.getBlocks().keySet()) {
                seen.add(key(Math.floorDiv(pos.getX(), c), Math.floorDiv(pos.getY(), c), Math.floorDiv(pos.getZ(), c)));
                if (seen.size() > MAX_VOXELS) break;
            }
            if (seen.size() <= MAX_VOXELS) return c;
        }
        return 32;
    }

    private static int ceilDiv(int value, int div) {
        return Math.max(1, (value + div - 1) / div);
    }

    // 21 bits per axis, signed - schematic coordinates never come close to that.
    private static long key(int x, int y, int z) {
        return ((long) (x & 0x1FFFFF) << 42) | ((long) (y & 0x1FFFFF) << 21) | (z & 0x1FFFFF);
    }

    private static int unpackX(long key) {
        return sign21((int) ((key >> 42) & 0x1FFFFF));
    }

    private static int unpackY(long key) {
        return sign21((int) ((key >> 21) & 0x1FFFFF));
    }

    private static int unpackZ(long key) {
        return sign21((int) (key & 0x1FFFFF));
    }

    private static int sign21(int value) {
        return (value & 0x100000) != 0 ? value | ~0x1FFFFF : value;
    }

    /**
     * Painter's order: back and bottom first. Only re-sorted when the camera actually moved, and sorted
     * as packed longs - a comparator over boxed indices is what made dragging a big model stutter, since
     * a rotation re-sorts every cube in it.
     */
    private void sortIfNeeded(double cosYaw, double sinYaw, double cosPitch, double sinPitch) {
        if (!orderDirty && yaw == sortedYaw && pitch == sortedPitch) return;

        for (int i = 0; i < order.length; i++) {
            int idx = (int) (order[i] & 0xFFFFFFFFL);
            float d = (float) depth(voxels.get(idx), cosYaw, sinYaw, cosPitch, sinPitch);
            order[i] = ((long) sortableBits(d) << 32) | (idx & 0xFFFFFFFFL);
        }
        Arrays.sort(order);

        sortedYaw = yaw;
        sortedPitch = pitch;
        orderDirty = false;
    }

    /** Floats mapped onto signed ints that compare the same way, so the packed key can just be sorted. */
    private static int sortableBits(float value) {
        int bits = Float.floatToIntBits(value);
        return bits ^ ((bits >> 31) & 0x7FFFFFFF);
    }

    /**
     * Distance along the view axis, i.e. the third row of the same yaw-then-pitch rotation the projection
     * uses. The horizontal part has to carry cosPitch: without it the order came apart as soon as the
     * camera was tilted, and blocks from the back of the build painted over the front of it.
     */
    private static double depth(@NotNull Voxel v, double cosYaw, double sinYaw, double cosPitch, double sinPitch) {
        return sinPitch * v.y() + (v.x() * sinYaw + v.z() * cosYaw) * cosPitch;
    }

    /**
     * One cube face. The winding is normalised to the orientation the GUI pipeline's own rect helper
     * emits, so a face stays visible no matter which way the camera turned it - a back-facing winding
     * would be culled away and punch holes in the model.
     */
    private static void face(net.minecraft.client.render.VertexConsumer vc, Matrix3x2f pose,
                             float ax, float ay, float bx, float by,
                             float cx, float cy, float dx, float dy, int argb) {
        float cross = (bx - ax) * (cy - by) - (by - ay) * (cx - bx);
        if (cross <= 0f) GuiRender.quad(vc, pose, ax, ay, bx, by, cx, cy, dx, dy, argb, argb, argb, argb);
        else GuiRender.quad(vc, pose, ax, ay, dx, dy, cx, cy, bx, by, argb, argb, argb, argb);
    }

    private static int shade(int rgb, float factor) {
        float f = Math.max(0.15f, Math.min(1f, factor));
        int r = (int) (((rgb >> 16) & 0xFF) * f);
        int g = (int) (((rgb >> 8) & 0xFF) * f);
        int b = (int) ((rgb & 0xFF) * f);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    private static int blockColor(@NotNull BlockState state, @NotNull BlockPos relative) {
        try {
            BlockPos pos = AutoBuild.origin == null ? relative : AutoBuild.origin.add(relative);
            return state.getMapColor(mc.world, pos).color;
        } catch (Exception e) {
            return 0x9A9A9A;
        }
    }
}
