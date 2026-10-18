package thunder.hack.utility.world;

import net.minecraft.block.BlockState;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtHelper;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtSizeTracker;
import net.minecraft.registry.Registries;
import net.minecraft.util.BlockMirror;
import net.minecraft.util.BlockRotation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3i;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import thunder.hack.core.manager.client.ConfigManager;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static thunder.hack.features.modules.Module.mc;

/**
 * A litematica-style schematic: every non-air block of a scanned cuboid, stored relative to the region's
 * min corner. Saved as compressed NBT in GlobalDLC/schematics/name.thsc with a block state palette,
 * so states (stair facing, slab halves, ...) survive a save/load round trip.
 */
public class Schematic {
    private String name;
    private final Map<BlockPos, BlockState> blocks = new LinkedHashMap<>();

    public Schematic(String name) {
        this.name = name;
    }

    /**
     * Scans the whole cuboid between the two corners (interior included) and keeps everything that is not
     * air, shifted so the region min corner becomes (0,0,0).
     */
    public static @NotNull Schematic capture(String name, @NotNull BlockPos a, @NotNull BlockPos b) {
        Schematic schematic = new Schematic(name);
        if (mc.world == null) return schematic;

        BlockPos min = new BlockPos(Math.min(a.getX(), b.getX()), Math.min(a.getY(), b.getY()), Math.min(a.getZ(), b.getZ()));
        BlockPos max = new BlockPos(Math.max(a.getX(), b.getX()), Math.max(a.getY(), b.getY()), Math.max(a.getZ(), b.getZ()));

        for (BlockPos pos : BlockPos.iterate(min, max)) {
            BlockState state = mc.world.getBlockState(pos);
            if (state.isAir()) continue;
            // No obtainable item means nothing we could ever place (fluids, fire, technical blocks).
            if (state.getBlock().asItem() == Items.AIR) continue;

            schematic.blocks.put(pos.subtract(min), state);
        }

        schematic.sort();
        return schematic;
    }

    /** Bottom to top, then outwards from the corner - the order a player would build in. */
    private void sort() {
        List<Map.Entry<BlockPos, BlockState>> entries = new ArrayList<>(blocks.entrySet());
        entries.sort(Comparator.<Map.Entry<BlockPos, BlockState>>comparingInt(e -> e.getKey().getY())
                .thenComparingInt(e -> e.getKey().getX() * e.getKey().getX() + e.getKey().getZ() * e.getKey().getZ()));

        blocks.clear();
        for (Map.Entry<BlockPos, BlockState> e : entries) blocks.put(e.getKey(), e.getValue());
    }

    /**
     * Shifts everything so the lowest corner is (0,0,0) and re-sorts. Imported files can start anywhere,
     * and the printer assumes relative coordinates start at the origin.
     */
    public void normalize() {
        if (blocks.isEmpty()) return;

        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        for (BlockPos pos : blocks.keySet()) {
            minX = Math.min(minX, pos.getX());
            minY = Math.min(minY, pos.getY());
            minZ = Math.min(minZ, pos.getZ());
        }

        if (minX != 0 || minY != 0 || minZ != 0) {
            Map<BlockPos, BlockState> shifted = new LinkedHashMap<>();
            for (Map.Entry<BlockPos, BlockState> e : blocks.entrySet())
                shifted.put(e.getKey().add(-minX, -minY, -minZ), e.getValue());

            blocks.clear();
            blocks.putAll(shifted);
        }

        sort();
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Map<BlockPos, BlockState> getBlocks() {
        return blocks;
    }

    public int size() {
        return blocks.size();
    }

    public @NotNull Vec3i getDimensions() {
        int mx = 0, my = 0, mz = 0;
        for (BlockPos pos : blocks.keySet()) {
            mx = Math.max(mx, pos.getX());
            my = Math.max(my, pos.getY());
            mz = Math.max(mz, pos.getZ());
        }
        return new Vec3i(mx + 1, my + 1, mz + 1);
    }

    /** How many of each item the build needs, biggest amount first. */
    public @NotNull List<Map.Entry<Item, Integer>> getMaterials() {
        Map<Item, Integer> materials = new LinkedHashMap<>();
        for (BlockState state : blocks.values()) {
            Item item = state.getBlock().asItem();
            if (item == Items.AIR) continue;
            materials.merge(item, 1, Integer::sum);
        }

        List<Map.Entry<Item, Integer>> sorted = new ArrayList<>(materials.entrySet());
        sorted.sort(Map.Entry.<Item, Integer>comparingByValue().reversed());
        return sorted;
    }

    /** Rotates the whole schematic (and every block state in it) around the Y axis. */
    public void rotate(BlockRotation rotation) {
        if (rotation == BlockRotation.NONE) return;

        Vec3i dim = getDimensions();
        Map<BlockPos, BlockState> rotated = new LinkedHashMap<>();

        for (Map.Entry<BlockPos, BlockState> e : blocks.entrySet()) {
            BlockPos p = e.getKey();
            BlockPos np = switch (rotation) {
                case CLOCKWISE_90 -> new BlockPos(dim.getZ() - 1 - p.getZ(), p.getY(), p.getX());
                case CLOCKWISE_180 -> new BlockPos(dim.getX() - 1 - p.getX(), p.getY(), dim.getZ() - 1 - p.getZ());
                case COUNTERCLOCKWISE_90 -> new BlockPos(p.getZ(), p.getY(), dim.getX() - 1 - p.getX());
                default -> p;
            };
            rotated.put(np, e.getValue().rotate(rotation));
        }

        blocks.clear();
        blocks.putAll(rotated);
        sort();
    }

    /** Mirrors the schematic along Z (LEFT_RIGHT) or X (FRONT_BACK). */
    public void mirror(BlockMirror mirrorMode) {
        if (mirrorMode == BlockMirror.NONE) return;

        Vec3i dim = getDimensions();
        Map<BlockPos, BlockState> mirrored = new LinkedHashMap<>();

        for (Map.Entry<BlockPos, BlockState> e : blocks.entrySet()) {
            BlockPos p = e.getKey();
            BlockPos np = mirrorMode == BlockMirror.LEFT_RIGHT
                    ? new BlockPos(p.getX(), p.getY(), dim.getZ() - 1 - p.getZ())
                    : new BlockPos(dim.getX() - 1 - p.getX(), p.getY(), p.getZ());
            mirrored.put(np, e.getValue().mirror(mirrorMode));
        }

        blocks.clear();
        blocks.putAll(mirrored);
        sort();
    }

    public @NotNull Schematic copy() {
        Schematic copy = new Schematic(name);
        copy.blocks.putAll(blocks);
        return copy;
    }

    public static @NotNull File getFolder() {
        File folder = ConfigManager.SCHEMATICS_FOLDER;
        if (!folder.exists()) folder.mkdirs();
        return folder;
    }

    public static @NotNull File getFile(String name) {
        return new File(getFolder(), name + ".thsc");
    }

    /**
     * Everything in the schematics folder we can open: our own saves by bare name, foreign files
     * (.litematic / .nbt / .schem dropped in from the internet) by full file name.
     */
    public static @NotNull List<String> getSavedNames() {
        List<String> names = new ArrayList<>();
        File[] files = getFolder().listFiles();
        if (files == null) return names;

        for (File file : files) {
            if (!file.isFile()) continue;

            if (file.getName().endsWith(".thsc"))
                names.add(file.getName().substring(0, file.getName().length() - 5));
            else if (SchematicImporter.isSupported(file.getName()))
                names.add(file.getName());
        }

        names.sort(String::compareToIgnoreCase);
        return names;
    }

    /** Loads by bare name (our format) or by file name (anything the importer understands). */
    public static @Nullable Schematic loadAny(@NotNull String name) {
        File own = getFile(name);
        if (own.exists()) return load(name);

        File direct = new File(getFolder(), name);
        if (direct.isFile()) return SchematicImporter.importFile(direct);

        for (String ext : SchematicImporter.SUPPORTED) {
            File file = new File(getFolder(), name + ext);
            if (file.isFile()) return SchematicImporter.importFile(file);
        }

        return null;
    }

    /** Deletes by bare name or file name, mirroring {@link #loadAny(String)}. */
    public static boolean deleteAny(@NotNull String name) {
        File own = getFile(name);
        if (own.exists()) return own.delete();

        File direct = new File(getFolder(), name);
        if (direct.isFile()) return direct.delete();

        for (String ext : SchematicImporter.SUPPORTED) {
            File file = new File(getFolder(), name + ext);
            if (file.isFile()) return file.delete();
        }

        return false;
    }

    public boolean save() {
        try {
            NbtCompound root = new NbtCompound();
            root.putString("Name", name);

            Vec3i dim = getDimensions();
            root.putInt("SizeX", dim.getX());
            root.putInt("SizeY", dim.getY());
            root.putInt("SizeZ", dim.getZ());

            // Palette + flat int stream (x, y, z, paletteIndex) keeps even big builds small on disk.
            List<BlockState> palette = new ArrayList<>();
            Map<BlockState, Integer> paletteIds = new LinkedHashMap<>();
            int[] data = new int[blocks.size() * 4];
            int i = 0;

            for (Map.Entry<BlockPos, BlockState> e : blocks.entrySet()) {
                Integer id = paletteIds.get(e.getValue());
                if (id == null) {
                    id = palette.size();
                    palette.add(e.getValue());
                    paletteIds.put(e.getValue(), id);
                }
                data[i++] = e.getKey().getX();
                data[i++] = e.getKey().getY();
                data[i++] = e.getKey().getZ();
                data[i++] = id;
            }

            NbtList paletteNbt = new NbtList();
            for (BlockState state : palette) paletteNbt.add(NbtHelper.fromBlockState(state));

            root.put("Palette", paletteNbt);
            root.putIntArray("Blocks", data);

            NbtIo.writeCompressed(root, getFile(name).toPath());
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public static @Nullable Schematic load(String name) {
        File file = getFile(name);
        if (!file.exists()) return null;

        try {
            NbtCompound root = NbtIo.readCompressed(file.toPath(), NbtSizeTracker.ofUnlimitedBytes());
            Schematic schematic = new Schematic(root.getString("Name", name));

            List<BlockState> palette = new ArrayList<>();
            NbtList paletteNbt = root.getListOrEmpty("Palette");
            for (int i = 0; i < paletteNbt.size(); i++)
                palette.add(NbtHelper.toBlockState(Registries.BLOCK, paletteNbt.getCompoundOrEmpty(i)));

            int[] data = root.getIntArray("Blocks").orElse(new int[0]);
            for (int i = 0; i + 3 < data.length; i += 4) {
                int id = data[i + 3];
                if (id < 0 || id >= palette.size()) continue;
                schematic.blocks.put(new BlockPos(data[i], data[i + 1], data[i + 2]), palette.get(id));
            }

            schematic.sort();
            return schematic;
        } catch (Exception e) {
            return null;
        }
    }

    public static boolean delete(String name) {
        File file = getFile(name);
        return file.exists() && file.delete();
    }
}
