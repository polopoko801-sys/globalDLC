package thunder.hack.features.modules.render;

import net.minecraft.block.ShulkerBoxBlock;
import net.minecraft.block.entity.*;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.Entity;
import net.minecraft.entity.decoration.ItemFrameEntity;
import net.minecraft.entity.vehicle.ChestMinecartEntity;
import net.minecraft.item.BlockItem;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.chunk.WorldChunk;
import org.jetbrains.annotations.Nullable;
import thunder.hack.core.Managers;
import thunder.hack.features.modules.Module;
import thunder.hack.gui.font.FontRenderers;
import thunder.hack.setting.Setting;
import thunder.hack.setting.impl.ColorSetting;
import thunder.hack.utility.math.MathUtility;
import thunder.hack.utility.render.Render2DEngine;
import thunder.hack.utility.render.Render3DEngine;

import java.awt.*;
import java.util.ArrayList;
import java.util.List;

public class StorageEsp extends Module {
    public StorageEsp() {
        super("StorageEsp", Category.RENDER);
    }

    public final Setting<Boolean> outline = new Setting<>("Outline", true);
    public final Setting<Boolean> fill = new Setting<>("Fill", true);

    public final Setting<Boolean> chest = new Setting<>("Chest", true);
    public final Setting<Boolean> trappedChest = new Setting<>("Trapped Chest", true);
    public final Setting<Boolean> dispenser = new Setting<>("Dispenser", false);
    public final Setting<Boolean> shulker = new Setting<>("Shulker", true);
    public final Setting<Boolean> echest = new Setting<>("Ender Chest", true);
    public final Setting<Boolean> furnace = new Setting<>("Furnace", false);
    public final Setting<Boolean> hopper = new Setting<>("Hopper", false);
    public final Setting<Boolean> barrels = new Setting<>("Barrel", false);

    public final Setting<Boolean> cart = new Setting<>("Minecart", false);
    public final Setting<Boolean> frame = new Setting<>("ItemFrame", false);
    private final Setting<ColorSetting> chestColor = new Setting<>("ChestColor", new ColorSetting(0x8800FF00));
    private final Setting<ColorSetting> trappedChestColor = new Setting<>("TrappedChestColor", new ColorSetting(0x8800FF00));
    private final Setting<ColorSetting> shulkColor = new Setting<>("ShulkerColor", new ColorSetting(0x8800FF00));
    private final Setting<ColorSetting> echestColor = new Setting<>("EChestColor", new ColorSetting(0x8800FF00));
    private final Setting<ColorSetting> frameColor = new Setting<>("FrameColor", new ColorSetting(0x8800FF00));
    private final Setting<ColorSetting> shulkerframeColor = new Setting<>("ShulkFrameColor", new ColorSetting(0x8800FF00));
    private final Setting<ColorSetting> furnaceColor = new Setting<>("FurnaceColor", new ColorSetting(0x8800FF00));
    private final Setting<ColorSetting> hopperColor = new Setting<>("HopperColor", new ColorSetting(0x8800FF00));
    private final Setting<ColorSetting> dispenserColor = new Setting<>("DispenserColor", new ColorSetting(0x8800FF00));
    private final Setting<ColorSetting> barrelColor = new Setting<>("BarrelColor", new ColorSetting(0x8800FF00));
    private final Setting<ColorSetting> minecartColor = new Setting<>("MinecartColor", new ColorSetting(0x8800FF00));

    public final Setting<Boolean> label = new Setting<>("Label", true);
    public final Setting<Boolean> labelScale = new Setting<>("DistanceScale", true, v -> label.getValue());

    public void onRender3D(MatrixStack stack) {
        // 1.21.11: boxes are drawn in onRender2D as real projected cubes (drawProjectedBox), so a container
        // reads as a 3D box welded to its block instead of a flat screen rectangle.
    }

    private void renderBoxes(DrawContext context) {
        // collected into one batch - see Render3DEngine.drawProjectedBoxes for why one element per box is a
        // crash and not just a slowdown
        List<Render3DEngine.BoxSpec> boxes = new ArrayList<>();

        for (BlockEntity blockEntity : getBlockEntities()) {
            Color color = getColor(blockEntity);
            if (color == null) continue;

            Box box = (blockEntity instanceof ChestBlockEntity || blockEntity instanceof EnderChestBlockEntity)
                    ? new Box(blockEntity.getPos().getX() + 0.06, blockEntity.getPos().getY(), blockEntity.getPos().getZ() + 0.06,
                    blockEntity.getPos().getX() + 0.94, blockEntity.getPos().getY() - 0.125 + 1, blockEntity.getPos().getZ() + 0.94)
                    : new Box(blockEntity.getPos());

            boxes.add(new Render3DEngine.BoxSpec(box, color, Render2DEngine.injectAlpha(color, 255), 1.5f, fill.getValue(), outline.getValue()));
        }

        for (Entity ent : Managers.ASYNC.getAsyncEntities()) {
            if (ent instanceof ItemFrameEntity iframe && frame.getValue()) {
                Color frameColor1 = frameColor.getValue().getColorObject();
                if (iframe.getHeldItemStack().getItem() instanceof BlockItem bitem && bitem.getBlock() instanceof ShulkerBoxBlock)
                    frameColor1 = shulkerframeColor.getValue().getColorObject();
                boxes.add(new Render3DEngine.BoxSpec(iframe.getBoundingBox(), frameColor1, Render2DEngine.injectAlpha(frameColor1, 255), 1.5f, fill.getValue(), outline.getValue()));
            }

            if (ent instanceof ChestMinecartEntity mcart && cart.getValue()) {
                Color c = minecartColor.getValue().getColorObject();
                boxes.add(new Render3DEngine.BoxSpec(mcart.getBoundingBox(), c, Render2DEngine.injectAlpha(c, 255), 1.5f, fill.getValue(), outline.getValue()));
            }
        }

        Render3DEngine.drawProjectedBoxes(context, boxes);
    }

    // Distance-scaled container labels: project the box centre to screen and draw the container name,
    // shrinking it the further away (or the further the camera is) the container is. Text isn't part of
    // the 3D pass, so it lives in onRender2D.
    public void onRender2D(DrawContext context) {
        if (mc.options.hudHidden) return;
        renderBoxes(context);
    }

    private String labelFor(BlockEntity be) {
        if (be instanceof TrappedChestBlockEntity) return "Trapped Chest";
        if (be instanceof EnderChestBlockEntity) return "Ender Chest";
        if (be instanceof ChestBlockEntity) return "Chest";
        if (be instanceof BarrelBlockEntity) return "Barrel";
        if (be instanceof ShulkerBoxBlockEntity) return "Shulker";
        if (be instanceof AbstractFurnaceBlockEntity) return "Furnace";
        if (be instanceof DispenserBlockEntity) return "Dispenser";
        if (be instanceof HopperBlockEntity) return "Hopper";
        return "Storage";
    }

    @Nullable
    private Color getColor(BlockEntity bEnt) {
        Color color = null;

        if (bEnt instanceof TrappedChestBlockEntity && trappedChest.getValue())
            color = trappedChestColor.getValue().getColorObject();
        else if (bEnt instanceof ChestBlockEntity && chest.getValue() && bEnt.getType() != BlockEntityType.TRAPPED_CHEST)
            color = chestColor.getValue().getColorObject();
        else if (bEnt instanceof EnderChestBlockEntity && echest.getValue())
            color = echestColor.getValue().getColorObject();
        else if (bEnt instanceof BarrelBlockEntity && barrels.getValue())
            color = barrelColor.getValue().getColorObject();
        else if (bEnt instanceof ShulkerBoxBlockEntity && shulker.getValue())
            color = shulkColor.getValue().getColorObject();
        else if (bEnt instanceof AbstractFurnaceBlockEntity && furnace.getValue())
            color = furnaceColor.getValue().getColorObject();
        else if (bEnt instanceof DispenserBlockEntity && dispenser.getValue())
            color = dispenserColor.getValue().getColorObject();
        else if (bEnt instanceof HopperBlockEntity && hopper.getValue())
            color = hopperColor.getValue().getColorObject();

        return color;
    }

    public static List<BlockEntity> getBlockEntities() {
        List<BlockEntity> list = new ArrayList<>();
        for (WorldChunk chunk : getLoadedChunks())
            list.addAll(chunk.getBlockEntities().values());

        return list;
    }

    public static List<WorldChunk> getLoadedChunks() {
        List<WorldChunk> chunks = new ArrayList<>();
        int viewDist = mc.options.getViewDistance().getValue();
        for (int x = -viewDist; x <= viewDist; x++) {
            for (int z = -viewDist; z <= viewDist; z++) {
                WorldChunk chunk = mc.world.getChunkManager().getWorldChunk((int) mc.player.getX() / 16 + x, (int) mc.player.getZ() / 16 + z);

                if (chunk != null) chunks.add(chunk);
            }
        }
        return chunks;
    }
}
