package thunder.hack.features.modules.render;

import com.google.common.collect.Lists;
import net.minecraft.block.*;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Util;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import org.jetbrains.annotations.NotNull;
import thunder.hack.features.modules.Module;
import thunder.hack.setting.Setting;
import thunder.hack.setting.impl.BooleanSettingGroup;
import thunder.hack.setting.impl.ColorSetting;
import thunder.hack.setting.impl.ItemSelectSetting;
import thunder.hack.utility.Timer;
import thunder.hack.utility.player.PlayerUtility;
import thunder.hack.utility.render.core.CloudanModelRenderer;
import thunder.hack.utility.render.core.CloudanOutlineRenderer;
import thunder.hack.utility.render.core.CloudanRenderCore;
import thunder.hack.utility.render.Render2DEngine;
import thunder.hack.utility.render.Render3DEngine;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static thunder.hack.features.modules.client.ClientSettings.isRu;

public class BlockESP extends Module {
    public BlockESP() {
        super("BlockESP", Category.RENDER);
    }

    public final Setting<ItemSelectSetting> selectedBlocks = new Setting<>("SelectedBlocks", new ItemSelectSetting(new ArrayList<>(), ItemSelectSetting.Kind.Blocks));
    public static ArrayList<BlockVec> blocks = new ArrayList<>();
    private final Setting<Integer> range = new Setting<>("Range", 100, 1, 128);
    private final Setting<BooleanSettingGroup> limit = new Setting<>("Limit", new BooleanSettingGroup(true));
    private final Setting<Integer> limitCount = new Setting<>("LimitCount", 50, 1, 2048).addToGroup(limit);
    private final Setting<ColorSetting> color = new Setting<>("Color", new ColorSetting(0xFF00FFFF));
    private final Setting<Boolean> illegals = new Setting<>("Illegals", true);
    private final Setting<Boolean> tracers = new Setting<>("Tracers", false);
    /** Where a tracer starts. Crosshair by default - it used to be hardcoded to the bottom of the screen. */
    private final Setting<Anchor> tracerAnchor = new Setting<>("TracerAnchor", Anchor.Crosshair, v -> tracers.getValue());
    /**
     * Models draws each found block with its own Minecraft model and texture - a chest looks like a
     * chest, a furnace like a furnace - lifted to full brightness and visible through walls, instead of
     * flattening everything into one coloured cube. Boxes is the old look, kept for a weak machine.
     */
    private final Setting<Mode> mode = new Setting<>("Mode", Mode.Models);
    private final Setting<Integer> modelAlpha = new Setting<>("ModelOpacity", 190, 30, 255, v -> mode.getValue() == Mode.Models);
    private final Setting<Boolean> throughWalls = new Setting<>("ThroughWalls", true, v -> mode.getValue() == Mode.Models);
    private final Setting<Boolean> fill = new Setting<>("Fill", true, v -> mode.getValue() == Mode.Boxes);
    private final Setting<Boolean> outline = new Setting<>("Outline", true);

    private final ExecutorService searchThread = Executors.newSingleThreadExecutor();
    private final Timer searchTimer = new Timer();
    private long lastFrameTime;
    private boolean canContinue;

    @Override
    public void onEnable() {
        blocks.clear();
        lastFrameTime = System.currentTimeMillis();
        canContinue = true;
    }

    @Override
    public void onUpdate() {
        if (searchTimer.every(1000) && canContinue) {
            CompletableFuture.supplyAsync(this::scan, searchThread).thenAcceptAsync(this::sync, Util.getMainWorkerExecutor());
            canContinue = false;
        }
    }

    private ArrayList<BlockVec> scan() {
        ArrayList<BlockVec> blocks = new ArrayList<>();
        int startX = (int) Math.floor(mc.player.getX() - range.getValue());
        int endX = (int) Math.ceil(mc.player.getX() + range.getValue());
        int startY = mc.world.getBottomY() + 1;
        int endY = mc.world.getTopYInclusive();
        int startZ = (int) Math.floor(mc.player.getZ() - range.getValue());
        int endZ = (int) Math.ceil(mc.player.getZ() + range.getValue());

        for (int x = startX; x <= endX; x++) {
            for (int y = startY; y <= endY; y++) {
                for (int z = startZ; z <= endZ; z++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    BlockState bs = mc.world.getBlockState(pos);
                    if (shouldAdd(bs.getBlock(), pos)) {
                        blocks.add(new BlockVec(pos.getX(), pos.getY(), pos.getZ()));
                    }
                }
            }
        }
        return blocks;
    }

    private void sync(ArrayList<BlockVec> b) {
        blocks = b;
        canContinue = true;
    }

    public void onRender3D(MatrixStack stack) {
        // Boxes are drawn in onRender2D as real projected cubes (drawProjectedBox) - every face is
        // projected on its own, so a block reads as a 3D box, not a flat screen rectangle. Models are
        // world-space geometry and belong here, on the pass that carries the camera rotation once.
        if (fullNullCheck()) return;
        if (mc.getCurrentFps() < 8 && mc.player.age > 100) {
            disable(isRu() ? "Спасаем твой ПК :)" : "Saving ur pc :)");
            return;
        }

        if (mode.getValue() != Mode.Models || blocks.isEmpty()) return;

        int alpha = modelAlpha.getValue();
        boolean tw = throughWalls.getValue();
        // The budget follows the quality dial, so turning quality down genuinely draws fewer models
        // rather than drawing the same number slightly dimmer.
        int limitCount = CloudanRenderCore.budget(this.limitCount.getValue());
        int drawn = 0;

        for (BlockVec vec : Lists.newArrayList(blocks)) {
            if (limit.getValue().isEnabled() && drawn >= limitCount) break;

            BlockPos pos = BlockPos.ofFloored(vec.x, vec.y, vec.z);
            BlockState state = mc.world.getBlockState(pos);
            if (state.isAir()) continue;

            // The core does the frustum test, the distance test and the LOD tier in one call, so a chest
            // behind you costs nothing at all instead of a full model's worth of vertices.
            if (CloudanModelRenderer.submit(stack, state, pos, alpha, tw, range.getValue()).drawable())
                drawn++;
        }

        // The core flushes at the end of the frame; nothing to do here.
    }

    public enum Mode {
        Models, Boxes
    }

    public void onRender2D(DrawContext context) {
        if (fullNullCheck() || blocks.isEmpty()) return;

        boolean boxMode = mode.getValue() == Mode.Boxes;
        boolean wantFill = boxMode && fill.getValue();
        // In Models mode the outline is the accent on top of the real model, not the whole picture.
        if (!wantFill && !outline.getValue() && !tracers.getValue()) return;

        Color col = color.getValue().getColorObject();
        Color fillCol = Render2DEngine.injectAlpha(col, 50);
        int count = 0;

        for (BlockVec vec : Lists.newArrayList(blocks)) {
            if (count > limitCount.getValue() && limit.getValue().isEnabled())
                continue;

            if (vec.getDistance(mc.player.getEntityPos()) > range.getPow2Value()) {
                blocks.remove(vec);
                continue;
            }

            Box b = new Box(vec.x, vec.y, vec.z, vec.x + 1, vec.y + 1, vec.z + 1);
            // Through the core: frustum-culled, batched with everything else drawing this frame, counted.
            if (wantFill || outline.getValue())
                CloudanOutlineRenderer.box(b, wantFill ? fillCol : null, col, wantFill ? 1.5f : 1f, range.getValue());

            if (tracers.getValue())
                CloudanOutlineRenderer.tracer(mc.getWindow().getScaledWidth() / 2f, tracerOriginY(),
                        vec.getVector(), col, 1f);

            count++;
        }
        lastFrameTime = System.currentTimeMillis();
    }

    /** Screen Y a tracer leaves from. */
    private float tracerOriginY() {
        float h = mc.getWindow().getScaledHeight();
        return switch (tracerAnchor.getValue()) {
            case Top -> 0f;
            case Crosshair -> h / 2f;
            case Bottom -> h;
        };
    }

    private enum Anchor {
        Crosshair, Top, Bottom
    }

    private boolean shouldAdd(Block block, BlockPos pos) {
        if (block instanceof AirBlock) return false;
        if (selectedBlocks.getValue().contains(block)) return true;
        if (illegals.getValue()) return isIllegal(block, pos);
        return false;
    }

    private boolean isIllegal(Block block, BlockPos pos) {
        if (block instanceof CommandBlock || block instanceof BarrierBlock) return true;

        if (block == Blocks.BEDROCK) {
            if (!PlayerUtility.isInHell())
                return pos.getY() > 4;
            else
                return pos.getY() > 127 || (pos.getY() < 123 && pos.getY() > 4);
        }
        return false;
    }

    public record BlockVec(double x, double y, double z) {
        public double getDistance(@NotNull Vec3d v) {
            double dx = x - v.x;
            double dy = y - v.y;
            double dz = z - v.z;
            return dx * dx + dy * dy + dz * dz;
        }

        public Vec3d getVector() {
            return new Vec3d(x + 0.5f, y + 0.5f, z + 0.5f);
        }
    }
}