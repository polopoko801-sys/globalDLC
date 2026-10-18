package thunder.hack.features.modules.base;

import meteordevelopment.orbit.EventHandler;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import thunder.hack.events.impl.EventPostSync;
import thunder.hack.events.impl.EventSync;
import thunder.hack.setting.Setting;
import thunder.hack.utility.Timer;
import thunder.hack.utility.player.InteractionUtility;
import thunder.hack.utility.world.HoleUtility;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public abstract class TrapModule extends PlaceModule {
    protected final Setting<PlaceTiming> placeTiming = new Setting<>("Place Timing", PlaceTiming.Default);
    protected final Setting<Integer> blocksPerTick = new Setting<>("Block/Tick", 8, 1, 12, v -> placeTiming.getValue() == PlaceTiming.Default);
    protected final Setting<Integer> placeDelay = new Setting<>("Delay/Place", 3, 0, 10);
    protected final Setting<TrapMode> trapMode = new Setting<>("Trap Mode", TrapMode.Full);
    protected final Setting<Priority> priority = new Setting<>("Priority", Priority.Trap);
    protected final Setting<Boolean> autoDisable = new Setting<>("Auto Disable", false);
    protected final Setting<Boolean> debug = new Setting<>("Debug", false);

    private int delay;
    protected PlayerEntity target;
    private final ArrayList<BlockPos> sequentialBlocks = new ArrayList<>();
    private final Timer debugTimer = new Timer();
    /** The last reason printed, so the same one is never printed twice in a row. */
    private String lastIdleReason;

    public TrapModule(@NotNull String name, @NotNull Category category) {
        super(name, category);
    }

    protected abstract boolean needNewTarget();

    protected abstract @Nullable PlayerEntity getTarget();

    @Override
    public void onDisable() {
        target = null;
        delay = 0;
        sequentialBlocks.clear();
        lastIdleReason = null;
    }

    @EventHandler
    @SuppressWarnings("unused")
    private void onSync(EventSync event) {
        if (needNewTarget()) {
            target = getTarget();
            return;
        }

        if (placeTiming.getValue() == PlaceTiming.Vanilla && !rotate.is(InteractionUtility.Rotate.None)) {
            BlockPos targetBlock = getBlockToPlace();
            if (targetBlock != null && mc.player != null) {
                BlockHitResult result = InteractionUtility.getPlaceResult(targetBlock, interact.getValue(), false);
                if (result != null) {
                    float[] angle = InteractionUtility.calculateAngle(result.getPos());
                    mc.player.setYaw(angle[0]);
                    mc.player.setPitch(angle[1]);
                }
            }
        }
    }

    @EventHandler
    @SuppressWarnings("unused")
    private void onPostSync(EventPostSync event) {
        if (delay > 0) {
            delay--;
            return;
        }

        InteractionUtility.Rotate rotateMod = placeTiming.is(PlaceTiming.Vanilla) && !rotate.is(InteractionUtility.Rotate.None) ? InteractionUtility.Rotate.None : rotate.getValue();

        if (placeTiming.getValue() == PlaceTiming.Default) {
            List<BlockPos> queue = getPlaceQueue();
            final List<BlockPos> firstQueue = queue;
            // Spots that refused to place this tick. The old code did `else break`, so a single bad spot
            // (entity standing in it, no reachable face) stalled the whole tick; skipping keeps the rest
            // of the budget usable. Remembering them also guarantees the loop below terminates.
            final Set<BlockPos> refused = new HashSet<>();
            int placed = 0;

            while (placed < blocksPerTick.getValue()) {
                BlockPos targetBlock = null;
                for (BlockPos pos : queue)
                    if (!refused.contains(pos)) {
                        targetBlock = pos;
                        break;
                    }
                if (targetBlock == null)
                    break;

                if (placeBlock(targetBlock, rotateMod)) {
                    placed++;
                    delay = placeDelay.getValue();
                    inactivityTimer.reset();
                    // A placed block becomes support for its neighbours, so new spots open up mid-tick —
                    // re-query instead of working off the snapshot, otherwise the trap only grows one
                    // ring per tick.
                    queue = getPlaceQueue();
                } else {
                    refused.add(targetBlock);
                }
            }

            if (placed == 0)
                reportIdle(firstQueue);
            if (autoDisable.getValue() && target != null && firstQueue.isEmpty())
                disable();
        } else if (placeTiming.getValue() == PlaceTiming.Vanilla) {
            BlockPos targetBlock = getBlockToPlace();

            if (targetBlock != null) {
                if (placeBlock(targetBlock, rotateMod)) {
                    sequentialBlocks.add(targetBlock);
                    delay = placeDelay.getValue();
                    inactivityTimer.reset();
                } else {
                    reportIdle(List.of(targetBlock));
                }
            } else {
                reportIdle(List.of());
                if (autoDisable.getValue() && target != null)
                    disable();
            }
        }
    }

    /**
     * Says where the trap is bailing out, so it never fails silently - but only ONCE per reason.
     *
     * <p>It used to print every second for as long as the condition held, which with a module that
     * spends most of its time with nothing to do means a line of chat every second, forever. The useful
     * information is that the reason CHANGED; repeating it is noise that buries everything else in
     * chat. So the last reason is remembered and an identical one is dropped.
     */
    private void reportIdle(@NotNull List<BlockPos> queue) {
        if (!debug.getValue() || !debugTimer.passedMs(1000)) return;
        debugTimer.reset();

        String reason;
        if (target == null)
            reason = "§cNo target §7- nobody within Range " + range.getValue() + " passing Require/MovingPlayers";
        else if (!getBlockResult().found())
            reason = "§cNo block §7- the blocks ticked under 'Blocks' must be in your HOTBAR, not the inventory";
        else if (shouldPause())
            reason = "§cPaused §7- eating / mining / another module called pause()";
        else if (queue.isEmpty())
            reason = "§cNothing placeable §7- " + getBlocks(target).size() + " trap spots, all out of Range "
                    + range.getValue() + " or rejected by Interact=" + interact.getValue();
        else
            reason = "§c" + queue.size() + " spots but every place was refused §7- Interact=" + interact.getValue()
                    + ", Rotate=" + rotate.getValue() + ", PlaceMode=" + placeMode.getValue();

        if (reason.equals(lastIdleReason)) return;
        lastIdleReason = reason;
        sendMessage(reason);
    }

    protected @Nullable BlockPos getBlockToPlace() {
        final List<BlockPos> queue = getPlaceQueue();
        return queue.isEmpty() ? null : queue.getFirst();
    }

    /**
     * Every position still worth placing, best first.
     * <p>
     * {@link #getBlocks} emits the trap in tiers (hole floor, surround, head level, ceiling) and that
     * emission order IS the priority, so {@link Priority#Trap} keeps it — the old code sorted purely by
     * distance and threw all of it away. Positions repeat across tiers, hence the de-duplication.
     */
    protected @NotNull List<BlockPos> getPlaceQueue() {
        if (target == null || mc.player == null) return new ArrayList<>();

        final List<BlockPos> candidates = new ArrayList<>();
        for (BlockPos pos : getBlocks(target)) {
            if (candidates.contains(pos)) continue;
            if (pos.getSquaredDistance(mc.player.getEntityPos()) >= range.getPow2Value()) continue;
            if (!InteractionUtility.canPlaceBlock(pos, interact.getValue(), true)) continue;
            candidates.add(pos);
        }

        switch (priority.getValue()) {
            case Closest ->
                    candidates.sort(Comparator.comparingDouble(pos -> mc.player.squaredDistanceTo(pos.toCenterPos())));
            case Farthest ->
                    candidates.sort(Comparator.comparingDouble((BlockPos pos) -> mc.player.squaredDistanceTo(pos.toCenterPos())).reversed());
            case Trap -> sortTrapOrder(candidates);
        }

        return candidates;
    }

    /**
     * Keeps the tier order from {@link #getBlocks} — tiers are whole Y levels, ranked by when the level was
     * first emitted — and, inside a tier, seals the side the target is about to escape through first.
     */
    private void sortTrapOrder(@NotNull List<BlockPos> candidates) {
        final Map<Integer, Integer> tierOrder = new HashMap<>();
        for (BlockPos pos : candidates)
            tierOrder.putIfAbsent(pos.getY(), tierOrder.size());

        final Vec3d escape = getEscapeDirection();
        candidates.sort(Comparator
                .comparingInt((BlockPos pos) -> tierOrder.get(pos.getY()))
                .thenComparing(Comparator.comparingDouble((BlockPos pos) -> escapeScore(pos, escape)).reversed()));
    }

    /** Where the target is heading: its actual movement if it is moving, otherwise where it is looking. */
    private @NotNull Vec3d getEscapeDirection() {
        if (target == null) return Vec3d.ZERO;

        final Vec3d velocity = target.getVelocity();
        if (velocity.horizontalLengthSquared() > 1.0E-4)
            return new Vec3d(velocity.x, 0, velocity.z).normalize();

        final double yaw = Math.toRadians(target.getYaw());
        return new Vec3d(-Math.sin(yaw), 0, Math.cos(yaw));
    }

    /** 1 when the position sits straight in the target's escape path, -1 when it is directly behind it. */
    private double escapeScore(@NotNull BlockPos pos, @NotNull Vec3d escape) {
        if (target == null || escape.lengthSquared() < 1.0E-6) return 0;

        final Vec3d relative = pos.toCenterPos().subtract(target.getEntityPos()).multiply(1, 0, 1);
        if (relative.lengthSquared() < 1.0E-6) return 0;

        return relative.normalize().dotProduct(escape);
    }

    protected List<BlockPos> getBlocks(@NotNull PlayerEntity player) {
        final Vec3d playerPos = player.getEntityPos();
        final List<BlockPos> offsets = new ArrayList<>();
        final List<BlockPos> holePoses = HoleUtility.getHolePoses(playerPos);
        final List<BlockPos> surroundPoses = HoleUtility.getSurroundPoses(playerPos);

        if (mc.player == null || mc.world == null) return offsets;

        switch (trapMode.getValue()) {
            case Full -> {
                offsets.addAll(holePoses.stream()
                        .map(BlockPos::down)
                        .toList());

                if (interact.getValue() != InteractionUtility.Interact.AirPlace)
                    offsets.addAll(addHelpOffsets(surroundPoses));

                offsets.addAll(surroundPoses);
                offsets.addAll(surroundPoses.stream()
                        .map(BlockPos::up)
                        .toList());

                if (interact.getValue() != InteractionUtility.Interact.AirPlace) {
                    surroundPoses.stream()
                            .map(pos -> pos.up(2))
                            .filter(pos -> pos.getSquaredDistance(mc.player.getEntityPos()) < range.getPow2Value())
                            .max(Comparator.comparing(pos -> mc.player.squaredDistanceTo(pos.toCenterPos())))
                            .ifPresent(pos -> {
                                offsets.add(pos);
                                offsets.add(pos.down());
                            });
                }

                offsets.addAll(holePoses.stream()
                        .map(pos -> pos.up(2))
                        .toList());
            }
            case Legs -> {
                offsets.addAll(holePoses.stream()
                        .map(BlockPos::down)
                        .toList());
                if (interact.getValue() != InteractionUtility.Interact.AirPlace) {
                    surroundPoses.stream()
                            .filter(pos -> pos.getSquaredDistance(mc.player.getEntityPos()) < range.getPow2Value())
                            .max(Comparator.comparing(pos -> player.squaredDistanceTo(pos.toCenterPos())))
                            .ifPresent(pos -> {
                                offsets.add(pos);
                                offsets.add(pos.up());
                                offsets.add(pos.up(2));
                            });
                    offsets.addAll(addHelpOffsets(surroundPoses));
                }
                offsets.addAll(surroundPoses);
                offsets.addAll(holePoses.stream()
                        .map(pos -> pos.up(2))
                        .toList());
            }
            case Head -> {
                offsets.addAll(holePoses.stream()
                        .map(BlockPos::down)
                        .toList());
                if (interact.getValue() != InteractionUtility.Interact.AirPlace) {
                    surroundPoses.stream()
                            .map(BlockPos::down)
                            .filter(pos -> pos.getSquaredDistance(mc.player.getEntityPos()) < range.getPow2Value())
                            .max(Comparator.comparing(pos -> player.squaredDistanceTo(pos.toCenterPos())))
                            .ifPresent(pos -> {
                                offsets.add(pos);
                                offsets.add(pos.up());
                                offsets.add(pos.up(3));
                            });
                }
                offsets.addAll(surroundPoses.stream()
                        .map(BlockPos::up)
                        .toList());
                offsets.addAll(holePoses.stream()
                        .map(pos -> pos.up(2))
                        .toList());
            }
        }

        return offsets;
    }

    private @NotNull List<BlockPos> addHelpOffsets(@NotNull List<BlockPos> surroundPoses) {
        final List<BlockPos> helpOffsets = new ArrayList<>();

        if (mc.world == null || mc.player == null)
            return helpOffsets;

        surroundPoses.stream()
                .map(BlockPos::down)
                .filter(pos -> pos.getSquaredDistance(mc.player.getEntityPos()) < range.getPow2Value())
                .filter(pos -> {
                    for (Direction dir : Direction.values()) {
                        if (!mc.world.getBlockState(pos.add(dir.getVector().up())).isReplaceable()) {
                            return false;
                        }
                    }
                    return true;
                })
                .forEach(helpOffsets::add);

        return helpOffsets;
    }

    protected enum TrapMode {
        Full,
        Legs,
        Head
    }

    protected enum PlaceTiming {
        Default,
        Vanilla
    }

    protected enum Priority {
        /** Tier order from {@link #getBlocks}, escape side first. */
        Trap,
        Closest,
        /** The pre-existing behaviour: always the block farthest from you. */
        Farthest
    }
}
