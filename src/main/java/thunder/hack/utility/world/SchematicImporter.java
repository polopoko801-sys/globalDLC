package thunder.hack.utility.world;

import net.minecraft.block.BlockState;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtHelper;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtSizeTracker;
import net.minecraft.registry.Registries;
import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads foreign schematic files so builds downloaded off the internet just work: Litematica's .litematic
 * and vanilla structure-block .nbt, both converted into our own {@link Schematic}.
 */
public final class SchematicImporter {
    private SchematicImporter() {
    }

    public static final String[] SUPPORTED = {".thsc", ".litematic", ".nbt", ".schem"};

    public static boolean isSupported(@NotNull String fileName) {
        for (String ext : SUPPORTED)
            if (fileName.toLowerCase().endsWith(ext)) return true;
        return false;
    }

    /** Litematica and vanilla structures are gzipped NBT, but plenty of files in the wild are not. */
    private static @NotNull NbtCompound readNbt(@NotNull File file) throws Exception {
        try {
            return NbtIo.readCompressed(file.toPath(), NbtSizeTracker.ofUnlimitedBytes());
        } catch (Exception e) {
            return NbtIo.read(file.toPath());
        }
    }

    public static @Nullable Schematic importFile(@NotNull File file) {
        String name = file.getName();
        String lower = name.toLowerCase();
        String baseName = name.contains(".") ? name.substring(0, name.lastIndexOf('.')) : name;

        try {
            if (lower.endsWith(".thsc")) return Schematic.load(baseName);

            NbtCompound root = readNbt(file);

            if (lower.endsWith(".litematic") || root.getCompound("Regions").isPresent())
                return fromLitematic(baseName, root);

            if (lower.endsWith(".nbt") || root.getList("palette").isPresent())
                return fromStructure(baseName, root);

            if (lower.endsWith(".schem") || root.getCompound("Schematic").isPresent() || root.getCompound("Palette").isPresent())
                return fromSponge(baseName, root);

            return null;
        } catch (Exception e) {
            return null;
        }
    }

    /** Litematica .litematic - one or more regions, each with its own palette and packed block states. */
    private static @NotNull Schematic fromLitematic(String name, @NotNull NbtCompound root) {
        Schematic schematic = new Schematic(root.getCompoundOrEmpty("Metadata").getString("Name", name));
        NbtCompound regions = root.getCompoundOrEmpty("Regions");

        // Everything is stored relative to the lowest corner of all regions combined.
        int gx = Integer.MAX_VALUE, gy = Integer.MAX_VALUE, gz = Integer.MAX_VALUE;
        for (String key : regions.getKeys()) {
            NbtCompound region = regions.getCompoundOrEmpty(key);
            BlockPos min = regionMin(region);
            gx = Math.min(gx, min.getX());
            gy = Math.min(gy, min.getY());
            gz = Math.min(gz, min.getZ());
        }
        if (gx == Integer.MAX_VALUE) return schematic;

        for (String key : regions.getKeys()) {
            NbtCompound region = regions.getCompoundOrEmpty(key);

            NbtCompound size = region.getCompoundOrEmpty("Size");
            int rawX = size.getInt("x", 0), rawY = size.getInt("y", 0), rawZ = size.getInt("z", 0);
            int sx = Math.abs(rawX), sy = Math.abs(rawY), sz = Math.abs(rawZ);
            if (sx == 0 || sy == 0 || sz == 0) continue;

            // A negative size means the region was selected backwards: its local axis runs towards -X/-Y/-Z
            // from Position. Laying those blocks out as if it ran forwards mirrors the whole region.
            NbtCompound posNbt = region.getCompoundOrEmpty("Position");
            int px = posNbt.getInt("x", 0), py = posNbt.getInt("y", 0), pz = posNbt.getInt("z", 0);

            List<BlockState> palette = new ArrayList<>();
            NbtList paletteNbt = region.getListOrEmpty("BlockStatePalette");
            for (int i = 0; i < paletteNbt.size(); i++)
                palette.add(NbtHelper.toBlockState(Registries.BLOCK, paletteNbt.getCompoundOrEmpty(i)));
            if (palette.isEmpty()) continue;

            long[] data = region.getLongArray("BlockStates").orElse(new long[0]);
            if (data.length == 0) continue;

            int bits = Math.max(2, 32 - Integer.numberOfLeadingZeros(palette.size() - 1));
            long mask = (1L << bits) - 1L;

            for (int y = 0; y < sy; y++) {
                for (int z = 0; z < sz; z++) {
                    for (int x = 0; x < sx; x++) {
                        long index = (long) y * sx * sz + (long) z * sx + x;
                        int id = (int) getPacked(data, index, bits, mask);
                        if (id < 0 || id >= palette.size()) continue;

                        BlockState state = palette.get(id);
                        if (state.isAir() || state.getBlock().asItem() == Items.AIR) continue;

                        int wx = rawX < 0 ? px - x : px + x;
                        int wy = rawY < 0 ? py - y : py + y;
                        int wz = rawZ < 0 ? pz - z : pz + z;

                        schematic.getBlocks().put(new BlockPos(wx - gx, wy - gy, wz - gz), state);
                    }
                }
            }
        }

        schematic.normalize();
        return schematic;
    }

    private static @NotNull BlockPos regionMin(@NotNull NbtCompound region) {
        NbtCompound pos = region.getCompoundOrEmpty("Position");
        NbtCompound size = region.getCompoundOrEmpty("Size");

        int px = pos.getInt("x", 0), py = pos.getInt("y", 0), pz = pos.getInt("z", 0);
        int sx = size.getInt("x", 0), sy = size.getInt("y", 0), sz = size.getInt("z", 0);

        // A negative size means the region grows towards -X/-Y/-Z from Position.
        return new BlockPos(sx < 0 ? px + sx + 1 : px, sy < 0 ? py + sy + 1 : py, sz < 0 ? pz + sz + 1 : pz);
    }

    /**
     * Litematica packs entries across long boundaries (its own LitematicaBitArray), unlike the vanilla
     * 1.16+ chunk format which never straddles - reading it the vanilla way gives garbage.
     */
    private static long getPacked(long @NotNull [] data, long index, int bits, long mask) {
        long startOffset = index * bits;
        int startIndex = (int) (startOffset >> 6);
        int endIndex = (int) (((index + 1) * bits - 1) >> 6);
        int startBit = (int) (startOffset & 0x3F);

        if (startIndex < 0 || endIndex >= data.length) return -1;

        if (startIndex == endIndex)
            return (data[startIndex] >>> startBit) & mask;

        int endOffset = 64 - startBit;
        return ((data[startIndex] >>> startBit) | (data[endIndex] << endOffset)) & mask;
    }

    /** Vanilla structure block .nbt - palette + a list of {pos, state} entries. */
    private static @NotNull Schematic fromStructure(String name, @NotNull NbtCompound root) {
        Schematic schematic = new Schematic(name);

        List<BlockState> palette = new ArrayList<>();
        NbtList paletteNbt = root.getListOrEmpty("palette");
        for (int i = 0; i < paletteNbt.size(); i++)
            palette.add(NbtHelper.toBlockState(Registries.BLOCK, paletteNbt.getCompoundOrEmpty(i)));
        if (palette.isEmpty()) return schematic;

        NbtList blocks = root.getListOrEmpty("blocks");
        for (int i = 0; i < blocks.size(); i++) {
            NbtCompound entry = blocks.getCompoundOrEmpty(i);
            NbtList pos = entry.getListOrEmpty("pos");
            if (pos.size() < 3) continue;

            int id = entry.getInt("state", -1);
            if (id < 0 || id >= palette.size()) continue;

            BlockState state = palette.get(id);
            if (state.isAir() || state.getBlock().asItem() == Items.AIR) continue;

            schematic.getBlocks().put(new BlockPos(pos.getInt(0, 0), pos.getInt(1, 0), pos.getInt(2, 0)), state);
        }

        schematic.normalize();
        return schematic;
    }

    /** WorldEdit sponge .schem (v2 flat, v3 nested under "Schematic"/"Blocks") - varint block data. */
    private static @NotNull Schematic fromSponge(String name, @NotNull NbtCompound root) {
        NbtCompound schem = root.getCompound("Schematic").orElse(root);
        NbtCompound blockContainer = schem.getCompound("Blocks").orElse(schem);

        Schematic schematic = new Schematic(name);

        int width = schem.getInt("Width", 0);
        int height = schem.getInt("Height", 0);
        int length = schem.getInt("Length", 0);
        if (width <= 0 || height <= 0 || length <= 0) return schematic;

        NbtCompound paletteNbt = blockContainer.getCompound("Palette").orElse(schem.getCompoundOrEmpty("Palette"));
        byte[] data = blockContainer.getByteArray("Data").orElse(schem.getByteArray("BlockData").orElse(new byte[0]));
        if (paletteNbt.isEmpty() || data.length == 0) return schematic;

        // The palette maps "minecraft:oak_stairs[facing=north]" -> id, so it has to be indexed by id.
        BlockState[] palette = new BlockState[paletteNbt.getKeys().size() + 1];
        int maxId = -1;
        for (String key : paletteNbt.getKeys()) {
            int id = paletteNbt.getInt(key, -1);
            if (id < 0) continue;
            if (id >= palette.length) palette = java.util.Arrays.copyOf(palette, id + 1);
            palette[id] = parseState(key);
            maxId = Math.max(maxId, id);
        }
        if (maxId < 0) return schematic;

        int index = 0, cursor = 0;
        while (cursor < data.length) {
            int value = 0, shift = 0, b;
            do {
                if (cursor >= data.length) return finish(schematic);
                b = data[cursor++] & 0xFF;
                value |= (b & 0x7F) << shift;
                shift += 7;
            } while ((b & 0x80) != 0);

            if (value >= 0 && value < palette.length && palette[value] != null) {
                BlockState state = palette[value];
                if (!state.isAir() && state.getBlock().asItem() != Items.AIR) {
                    int x = index % width;
                    int z = (index / width) % length;
                    int y = index / (width * length);
                    if (y < height) schematic.getBlocks().put(new BlockPos(x, y, z), state);
                }
            }

            index++;
        }

        return finish(schematic);
    }

    private static @NotNull Schematic finish(@NotNull Schematic schematic) {
        schematic.normalize();
        return schematic;
    }

    private static @NotNull BlockState parseState(@NotNull String key) {
        NbtCompound compound = new NbtCompound();
        int bracket = key.indexOf('[');

        if (bracket < 0) {
            compound.putString("Name", key);
        } else {
            compound.putString("Name", key.substring(0, bracket));
            NbtCompound props = new NbtCompound();
            String body = key.substring(bracket + 1, key.endsWith("]") ? key.length() - 1 : key.length());
            for (String pair : body.split(",")) {
                int eq = pair.indexOf('=');
                if (eq > 0) props.putString(pair.substring(0, eq).trim(), pair.substring(eq + 1).trim());
            }
            compound.put("Properties", props);
        }

        return NbtHelper.toBlockState(Registries.BLOCK, compound);
    }

    /**
     * Downloads a schematic straight into the schematics folder. Runs on the caller's thread - callers do
     * this off the render thread.
     */
    public static @NotNull String download(@NotNull String url, @Nullable String fileNameOverride) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) URI.create(url).toURL().openConnection();
        connection.setRequestProperty("User-Agent", "Cloudan");
        connection.setConnectTimeout(10000);
        connection.setReadTimeout(30000);
        connection.setInstanceFollowRedirects(true);

        String fileName = fileNameOverride;
        if (fileName == null || fileName.isEmpty()) {
            String path = URI.create(url).getPath();
            fileName = path.contains("/") ? path.substring(path.lastIndexOf('/') + 1) : path;
            if (fileName.isEmpty()) fileName = "downloaded";
        }
        if (!isSupported(fileName)) fileName = fileName + ".litematic";

        File target = new File(Schematic.getFolder(), fileName);
        try (InputStream in = connection.getInputStream()) {
            Files.copy(in, target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } finally {
            connection.disconnect();
        }

        return fileName;
    }
}
