package thunder.hack.features.modules.combat;

import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.world.RaycastContext;
import thunder.hack.core.Managers;
import thunder.hack.events.impl.EventTick;
import thunder.hack.features.modules.Module;
import thunder.hack.features.modules.client.HudEditor;
import thunder.hack.setting.Setting;
import thunder.hack.setting.impl.ColorSetting;
import thunder.hack.setting.impl.SettingGroup;
import thunder.hack.utility.Timer;
import thunder.hack.utility.player.InteractionUtility;
import thunder.hack.utility.player.InventoryUtility;
import thunder.hack.utility.player.PlayerUtility;
import thunder.hack.utility.render.Render2DEngine;
import thunder.hack.utility.render.Render3DEngine;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static thunder.hack.utility.player.InteractionUtility.squaredDistanceFromEyes;

public final class AutoWeb extends Module {
    private final Setting<Float> range = new Setting<>("Range", 5f, 0f, 7f);
    private final Setting<Float> placeWallRange = new Setting<>("WallRange", 5f, 0f, 7f);
    private final Setting<PlaceTiming> placeTiming = new Setting<>("PlaceTiming", PlaceTiming.Default);
    private final Setting<Integer> blocksPerTick = new Setting<>("Block/Tick", 8, 1, 12, v -> placeTiming.getValue() == PlaceTiming.Default);
    private final Setting<Integer> placeDelay = new Setting<>("Delay/Place", 3, 0, 10);
    private final Setting<InteractionUtility.Interact> interact = new Setting<>("Interact", InteractionUtility.Interact.Strict);
    private final Setting<InteractionUtility.PlaceMode> placeMode = new Setting<>("PlaceMode", InteractionUtility.PlaceMode.Normal);
    private final Setting<InteractionUtility.Rotate> rotate = new Setting<>("Rotate", InteractionUtility.Rotate.None);
    /**
     * Hold off while you are doing something that a swapped hotbar slot would ruin.
     *
     * <p>Placing a web means switching to the web slot and switching back. Do that mid-bite and the
     * eat is cancelled; do it mid-swing and the block you were breaking resets its progress. Every
     * other place module in the client has this pair; this one never did.
     */
    private final Setting<SettingGroup> pause = new Setting<>("Pause", new SettingGroup(false, 0));
    private final Setting<Boolean> eatPause = new Setting<>("On Eat", true).addToGroup(pause);
    private final Setting<Boolean> breakPause = new Setting<>("On Break", true).addToGroup(pause);

    private final Setting<SettingGroup> selection = new Setting<>("Selection", new SettingGroup(false, 0));
    private final Setting<Boolean> head = new Setting<>("Head", true).addToGroup(selection);
    private final Setting<Boolean> leggs = new Setting<>("Leggs", true).addToGroup(selection);
    private final Setting<Boolean> surround = new Setting<>("Surround", true).addToGroup(selection);
    private final Setting<Boolean> upperSurround = new Setting<>("UpperSurround", false).addToGroup(selection);
    /** Ticks of target movement to lead by — a running target outruns a web placed on his current block. */
    private final Setting<Float> predict = new Setting<>("Predict", 1.5f, 0f, 5f).addToGroup(selection);
    /** Never place into your own hitbox, so you do not web yourself while trapping him. */
    private final Setting<Boolean> selfSafe = new Setting<>("SelfSafe", true).addToGroup(selection);
    /** Stop feeding webs into someone who is already stuck in one. */
    private final Setting<Boolean> skipWebbed = new Setting<>("SkipWebbed", true).addToGroup(selection);
    private final Setting<SettingGroup> renderCategory = new Setting<>("Render", new SettingGroup(false, 0));
    private final Setting<RenderMode> renderMode = new Setting<>("Render Mode", RenderMode.Fade).addToGroup(renderCategory);
    private final Setting<ColorSetting> renderFillColor = new Setting<>("Render Fill Color", new ColorSetting(HudEditor.getColor(0))).addToGroup(renderCategory);
    private final Setting<ColorSetting> renderLineColor = new Setting<>("Render Line Color", new ColorSetting(HudEditor.getColor(0))).addToGroup(renderCategory);
    private final Setting<Integer> renderLineWidth = new Setting<>("Render Line Width", 2, 1, 5).addToGroup(renderCategory);
    private final Setting<Integer> effectDurationMs = new Setting<>("Effect Duration (MS)", 500, 0, 10000).addToGroup(renderCategory);

    private final ArrayList<BlockPos> sequentialBlocks = new ArrayList<>();
    public static Timer inactivityTimer = new Timer();

    private final Map<BlockPos, Long> renderPoses = new ConcurrentHashMap<>();

    private int delay = 0;

    public AutoWeb() {
        super("AutoWeb", Category.COMBAT);
    }

    /**
     * 1.21.11: drawn as projected boxes from the 2D pass. The world-space 3D queue double-rotates on this
     * version, which made the web preview drift off its block whenever the camera moved.
     */
    public void onRender2D(DrawContext context) {
        renderPoses.forEach((pos, time) -> {
            long passed = System.currentTimeMillis() - time;
            if (passed > effectDurationMs.getValue()) {
                renderPoses.remove(pos);
                return;
            }

            int alpha = (int) (100f * (1f - (passed / 500f)));
            if (alpha <= 0) return;
            Color fill = Render2DEngine.injectAlpha(renderFillColor.getValue().getColorObject(), Math.min(255, alpha));

            Box box;
            Color line;
            switch (renderMode.getValue()) {
                case Decrease -> {
                    float scale = 1 - passed / 500f;
                    Box base = new Box(pos.getX(), pos.getY(), pos.getZ(), pos.getX(), pos.getY(), pos.getZ());
                    box = base.shrink(scale, scale, scale).offset(0.5 + scale * 0.5, 0.5 + scale * 0.5, 0.5 + scale * 0.5);
                    line = renderLineColor.getValue().getColorObject();
                }
                default -> {
                    box = new Box(pos);
                    line = Render2DEngine.injectAlpha(renderLineColor.getValue().getColorObject(), Math.min(255, alpha));
                }
            }

            Render3DEngine.drawProjectedBox(context, box, fill, fill, line, renderLineWidth.getValue(), true, true);
        });
    }


    @Override
    public void onEnable() {
        sequentialBlocks.clear();
        renderPoses.clear();
    }

    @EventHandler
    public void onTick(EventTick e) {
        if (shouldPause()) return;

        BlockPos targetBlock1 = getSequentialPos();
        if (targetBlock1 == null) return;

        if (delay > 0) {
            delay--;
            return;
        }

        InventoryUtility.saveSlot();
        if (placeTiming.getValue() == PlaceTiming.Default) {
            int placed = 0;
            while (placed < blocksPerTick.getValue()) {
                BlockPos targetBlock = getSequentialPos();
                if (targetBlock == null)
                    break;

                if (InteractionUtility.placeBlock(targetBlock, rotate.getValue(), interact.getValue(), placeMode.getValue(), getSlot(), false, true)) {
                    placed++;
                    renderPoses.put(targetBlock, System.currentTimeMillis());
                    delay = placeDelay.getValue();
                    inactivityTimer.reset();
                } else break;   // getSequentialPos returns the same spot again, so retrying would spin
            }
        } else if (placeTiming.getValue() == PlaceTiming.Vanilla) {
            BlockPos targetBlock = getSequentialPos();
            if (targetBlock == null) return;

            if (InteractionUtility.placeBlock(targetBlock, rotate.getValue(), interact.getValue(), placeMode.getValue(), getSlot(), false, true)) {
                sequentialBlocks.add(targetBlock);
                renderPoses.put(targetBlock, System.currentTimeMillis());
                delay = placeDelay.getValue();
                inactivityTimer.reset();
            }
        }
        InventoryUtility.returnSlot();
    }

    /** True while eating or mining, if the matching toggle is on. */
    private boolean shouldPause() {
        return (eatPause.getValue() && PlayerUtility.isEating())
                || (breakPause.getValue() && PlayerUtility.isMining());
    }

    private BlockPos getSequentialPos() {
        PlayerEntity target = Managers.COMBAT.getNearestTarget(range.getValue());
        if (target != null) {

            // Lead the target: webs dropped on the block he is standing on right now land behind someone
            // who is sprinting. Velocity is taken from the movement he actually made last tick.
            net.minecraft.util.math.Vec3d motion = target.getEntityPos().subtract(
                    target.lastX, target.lastY, target.lastZ);
            net.minecraft.util.math.Vec3d aimAt = target.getEntityPos().add(motion.multiply(predict.getValue()));
            BlockPos targetBp = BlockPos.ofFloored(aimAt);

            // Already stuck? Then stop burning webs on him - but only once EVERY body slot that is
            // switched on is actually webbed. The old check bailed on feet OR head, so the moment the
            // head landed first the whole module gave up and the legs were never filled in.
            if (skipWebbed.getValue() && (leggs.getValue() || head.getValue())) {
                BlockPos feet = BlockPos.ofFloored(target.getEntityPos());
                boolean legsDone = !leggs.getValue() || mc.world.getBlockState(feet).isOf(Blocks.COBWEB);
                boolean headDone = !head.getValue() || mc.world.getBlockState(feet.up()).isOf(Blocks.COBWEB);
                if (legsDone && headDone) return null;
            }

            ArrayList<BlockPos> positions = new ArrayList<>();
            if (leggs.getValue())
                positions.add(targetBp);

            if (head.getValue())
                positions.add(targetBp.up());

            // Sides ordered by where he is heading: the escape route gets sealed first instead of whatever
            // happens to be east.
            List<Direction> sides = new ArrayList<>(List.of(Direction.EAST, Direction.WEST, Direction.SOUTH, Direction.NORTH));
            if (motion.horizontalLengthSquared() > 1.0E-5)
                sides.sort((a, b) -> Double.compare(
                        motion.x * b.getOffsetX() + motion.z * b.getOffsetZ(),
                        motion.x * a.getOffsetX() + motion.z * a.getOffsetZ()));

            if (surround.getValue())
                for (Direction d : sides)
                    positions.add(targetBp.offset(d));

            if (upperSurround.getValue())
                for (Direction d : sides)
                    positions.add(targetBp.offset(d).up());

            for (BlockPos bp : positions) {
                // do not brick yourself into the trap you are building
                if (selfSafe.getValue() && new Box(bp).intersects(mc.player.getBoundingBox())) continue;

                BlockHitResult wallCheck = mc.world.raycast(new RaycastContext(InteractionUtility.getEyesPos(mc.player), bp.toCenterPos().offset(Direction.UP, 0.5f), RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, mc.player));
                if (wallCheck != null && wallCheck.getType() == HitResult.Type.BLOCK && wallCheck.getBlockPos() != bp)
                    if (squaredDistanceFromEyes(bp.toCenterPos()) > placeWallRange.getPow2Value()) continue;
                if (InteractionUtility.canPlaceBlock(bp, interact.getValue(), true) && mc.world.getBlockState(bp).isReplaceable()) {
                    return bp;
                }
            }
        }

        return null;
    }


    private int getSlot() {
        List<Block> canUseBlocks = new ArrayList<>();
        canUseBlocks.add(Blocks.COBWEB);
        int slot = -1;
        final ItemStack mainhandStack = mc.player.getMainHandStack();
        if (mainhandStack != ItemStack.EMPTY && mainhandStack.getItem() instanceof BlockItem) {
            final Block blockFromMainhandItem = ((BlockItem) mainhandStack.getItem()).getBlock();
            if (canUseBlocks.contains(blockFromMainhandItem)) {
                slot = mc.player.getInventory().selectedSlot;
            }
        }
        if (slot == -1) {
            for (int i = 0; i < 9; i++) {
                final ItemStack stack = mc.player.getInventory().getStack(i);
                if (stack != ItemStack.EMPTY && stack.getItem() instanceof BlockItem) {
                    final Block blockFromItem = ((BlockItem) stack.getItem()).getBlock();
                    if (canUseBlocks.contains(blockFromItem)) {
                        slot = i;
                        break;
                    }
                }
            }
        }
        return slot;
    }

    private enum PlaceTiming {
        Default, Vanilla
    }

    private enum RenderMode {
        Fade, Decrease
    }
}