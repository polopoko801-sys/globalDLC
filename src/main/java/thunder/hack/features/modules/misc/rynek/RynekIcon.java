package thunder.hack.features.modules.misc.rynek;

import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.CustomModelDataComponent;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtSizeTracker;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryOps;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import static thunder.hack.features.modules.Module.mc;

/**
 * Enough of an item to draw it the way the server drew it.
 *
 * <p>On this server the interesting items are vanilla ids wearing a resource pack: "Dynamit" is a
 * {@code minecraft:candle} carrying an {@code item_model} (or {@code custom_model_data}) component
 * that points the renderer somewhere else entirely. Rebuilding an icon from the registry id alone —
 * which is all an offer used to store — therefore drew a candle, and every custom item in the offer
 * list, the editor and the HUD looked like whatever base item the server happened to reuse.
 *
 * <p>So the model components are captured alongside the id, and the display name with them, so the
 * tooltip reads right too. Plain fields and lists on purpose: this is written to {@code buywatch.json}
 * by Gson, which has no idea what a {@code CustomModelDataComponent} is.
 */
public final class RynekIcon {
    private String itemId = "";
    /** {@code DataComponentTypes.ITEM_MODEL} — the modern way a pack re-skins one item. */
    private String itemModel;
    /** {@code DataComponentTypes.CUSTOM_MODEL_DATA}, split into the four lists it is made of. */
    private List<Float> modelFloats;
    private List<Boolean> modelFlags;
    private List<String> modelStrings;
    private List<Integer> modelColors;
    /** Display name, stripped of colour codes. */
    private String name;
    /**
     * The whole listing, encoded with {@code ItemStack.CODEC} as SNBT — every component the server sent,
     * not just the two we thought mattered.
     * <p>
     * This is the field that actually makes the player's resource pack apply. A pack can key a re-skin
     * off anything: {@code item_model}, {@code custom_model_data}, the display name, a dyed colour, a
     * trim. Rebuilding an approximation of the item means guessing which of those the pack looked at,
     * and getting it wrong shows the base item. Keeping the item verbatim means the renderer — and so
     * the pack — sees exactly what {@code /ah} showed.
     */
    private String nbt;

    /**
     * The item itself: the live stack while the session that captured it is still running, otherwise
     * whatever {@link #decode()} could rebuild from {@link #nbt} after a restart.
     * <p>
     * Transient so Gson neither writes nor reads it. It also serves as the cache — {@link #toStack()}
     * runs once per row per FRAME, and decoding costs an SNBT parse plus a codec parse.
     */
    private transient ItemStack cached;

    public RynekIcon() {
    }

    public static RynekIcon from(ItemStack stack) {
        RynekIcon icon = new RynekIcon();
        if (stack == null || stack.isEmpty()) return icon;
        // The live item itself, kept as-is for the rest of this session. This is the whole trick, and it
        // is what the reference mod does (its PurchaseEntry holds a raw ItemStack and draws it): a stack
        // that came off the market renders through exactly the same path the market's own slot did, so
        // the player's resource pack re-skins it identically. Anything rebuilt is an approximation.
        // Copied because the caller's slot stack is emptied client-side the moment we click it.
        icon.cached = stack.copy();
        try {
            icon.itemId = Registries.ITEM.getId(stack.getItem()).toString();
            icon.name = Formatting.strip(stack.getName().getString());
            icon.nbt = encode(stack);

            Identifier model = stack.get(DataComponentTypes.ITEM_MODEL);
            if (model != null) icon.itemModel = model.toString();

            CustomModelDataComponent cmd = stack.get(DataComponentTypes.CUSTOM_MODEL_DATA);
            if (cmd != null) {
                icon.modelFloats = new ArrayList<>(cmd.floats());
                icon.modelFlags = new ArrayList<>(cmd.flags());
                icon.modelStrings = new ArrayList<>(cmd.strings());
                icon.modelColors = new ArrayList<>(cmd.colors());
            }
        } catch (Exception ignored) {
        }
        return icon;
    }

    /** The registry-aware ops the stack codec needs; null before the world exists. */
    private static RegistryOps<NbtElement> ops() {
        RegistryWrapper.WrapperLookup lookup = null;
        if (mc.world != null) lookup = mc.world.getRegistryManager();
        else if (mc.player != null) lookup = mc.player.getRegistryManager();
        return lookup == null ? null : RegistryOps.of(NbtOps.INSTANCE, lookup);
    }

    /**
     * BINARY nbt, base64'd — not SNBT. Stringified NBT has to survive a text round-trip through Gson and
     * back through the parser, and any component whose value does not re-read to the same type comes back
     * as a different item or not at all. The binary form is what the game itself writes to disk.
     */
    private static String encode(ItemStack stack) {
        try {
            RegistryOps<NbtElement> ops = ops();
            if (ops == null) return null;
            NbtElement element = ItemStack.CODEC.encodeStart(ops, stack).result().orElse(null);
            if (!(element instanceof NbtCompound compound)) return null;
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream out = new DataOutputStream(bytes)) {
                NbtIo.writeCompound(compound, out);
            }
            return Base64.getEncoder().encodeToString(bytes.toByteArray());
        } catch (Exception e) {
            return null;
        }
    }

    /** The listing exactly as it came off the market, or empty when it was never captured / cannot be read. */
    private ItemStack decode() {
        if (nbt == null || nbt.isBlank()) return ItemStack.EMPTY;
        try {
            RegistryOps<NbtElement> ops = ops();
            if (ops == null) return ItemStack.EMPTY;
            byte[] raw = Base64.getDecoder().decode(nbt);
            NbtCompound compound;
            try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(raw))) {
                compound = NbtIo.readCompound(in, NbtSizeTracker.ofUnlimitedBytes());
            }
            return ItemStack.CODEC.parse(ops, compound).result().orElse(ItemStack.EMPTY);
        } catch (Exception e) {
            return ItemStack.EMPTY;
        }
    }

    /**
     * The item to draw. The verbatim capture first — that is the one the resource pack re-skins the same
     * way it does in {@code /ah} — falling back to a rebuild from the model components for offers
     * captured before the full encoding existed, and to a bare stack from the id after that.
     */
    public ItemStack toStack() {
        if (cached != null && !cached.isEmpty()) return cached;
        ItemStack exact = decode();
        if (!exact.isEmpty()) {
            cached = exact;
            return exact;
        }
        ItemStack built = buildFromComponents();
        // Only final once there is no verbatim capture to wait for: a decode can also fail simply
        // because the world is not up yet, and caching the approximation then would freeze it in.
        if (!built.isEmpty() && (nbt == null || nbt.isBlank())) cached = built;
        return built;
    }

    /** Older offers, captured before the whole stack was stored: rebuild what we did keep. */
    private ItemStack buildFromComponents() {
        try {
            if (itemId == null || itemId.isBlank()) return ItemStack.EMPTY;
            Identifier id = Identifier.tryParse(itemId);
            if (id == null) return ItemStack.EMPTY;
            Item item = Registries.ITEM.get(id);
            if (item == null || item == Items.AIR) return ItemStack.EMPTY;

            ItemStack stack = new ItemStack(item);
            if (itemModel != null && !itemModel.isBlank()) {
                Identifier model = Identifier.tryParse(itemModel);
                if (model != null) stack.set(DataComponentTypes.ITEM_MODEL, model);
            }
            if (hasCustomModelData())
                stack.set(DataComponentTypes.CUSTOM_MODEL_DATA, new CustomModelDataComponent(
                        list(modelFloats), list(modelFlags), list(modelStrings), list(modelColors)));
            if (name != null && !name.isBlank())
                stack.set(DataComponentTypes.CUSTOM_NAME, Text.literal(name));
            return stack;
        } catch (Exception e) {
            return ItemStack.EMPTY;
        }
    }

    private boolean hasCustomModelData() {
        return notEmpty(modelFloats) || notEmpty(modelFlags) || notEmpty(modelStrings) || notEmpty(modelColors);
    }

    private static boolean notEmpty(List<?> l) {
        return l != null && !l.isEmpty();
    }

    private static <T> List<T> list(List<T> l) {
        return l == null ? List.of() : l;
    }

    public String getItemId() {
        return itemId == null ? "" : itemId;
    }

    public boolean isEmpty() {
        return itemId == null || itemId.isBlank();
    }
}
