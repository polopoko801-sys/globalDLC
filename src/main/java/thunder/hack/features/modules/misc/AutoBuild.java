package thunder.hack.features.modules.misc;

import net.minecraft.block.BlockState;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import thunder.hack.ThunderHack;
import thunder.hack.core.Managers;
import thunder.hack.features.modules.Module;
import thunder.hack.setting.Setting;
import thunder.hack.setting.impl.ColorSetting;
import thunder.hack.utility.Timer;
import thunder.hack.utility.player.InteractionUtility;
import thunder.hack.utility.player.InventoryUtility;
import thunder.hack.utility.player.SearchInvResult;
import thunder.hack.utility.render.BlockModelRender;
import thunder.hack.utility.render.Render2DEngine;
import thunder.hack.utility.render.Render3DEngine;
import thunder.hack.utility.world.BuildQueue;
import thunder.hack.utility.world.Schematic;
import thunder.hack.utility.world.SchematicMatch;

import java.awt.*;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import static thunder.hack.features.modules.client.ClientSettings.isRu;

/**
 * Litematica-style printer. The selection, the schematic and the paste position are driven from the
 * /build command; this module owns the state, renders the ghost, walks to the next block and places it.
 */
public class AutoBuild extends Module {
    public AutoBuild() {
        super("AutoBuild", Category.MISC);
    }

    private final Setting<InteractionUtility.PlaceMode> placeMode = new Setting<>("PlaceMode", InteractionUtility.PlaceMode.Packet);
    private final Setting<InteractionUtility.Rotate> rotate = new Setting<>("Rotate", InteractionUtility.Rotate.Default);
    private final Setting<InteractionUtility.Interact> interact = new Setting<>("Interact", InteractionUtility.Interact.Vanilla);
    /**
     * BottomUp finishes the lowest unbuilt layer of the whole schematic before touching anything above
     * it - the way a person builds, and the only order where a wall has something under it when it goes
     * up. Nearest just takes whatever is closest, which is faster but leaves floating fragments behind.
     */
    private final Setting<Order> order = new Setting<>("Order", Order.BottomUp);
    /** How many layers the printer may work on at once. 1 = strictly one layer at a time. */
    private final Setting<Integer> layerWindow = new Setting<>("LayerWindow", 1, 1, 16, v -> order.getValue() == Order.BottomUp);
    private final Setting<Integer> blocksPerTick = new Setting<>("BlocksPerTick", 2, 1, 8);
    private final Setting<Integer> delay = new Setting<>("Delay", 50, 0, 1000);
    private final Setting<Float> range = new Setting<>("Range", 4.5f, 1f, 6f);
    private final Setting<Boolean> returnSlot = new Setting<>("ReturnSlot", true);
    private final Setting<Boolean> ignoreEntities = new Setting<>("IgnoreEntities", false);
    private final Setting<Boolean> notifyMissing = new Setting<>("NotifyMissing", true);
    private final Setting<Boolean> disableOnFinish = new Setting<>("DisableOnFinish", true);

    // ---- creative ----
    private final Setting<Boolean> creativeGrab = new Setting<>("CreativeGrab", true);
    // One item is all creative ever needs - placing does not consume it - and a stack of one keeps the
    // hotbar readable while the printer walks through a 20 block palette.
    private final Setting<Integer> creativeGrabCount = new Setting<>("CreativeGrabCount", 1, 1, 64, v -> creativeGrab.getValue());
    private final Setting<Boolean> creativeKeepTools = new Setting<>("CreativeKeepTools", true, v -> creativeGrab.getValue());

    // ---- movement ----
    /**
     * Walking the build itself. Everything else here is useless while the player is the one who has to
     * shuffle along a 150k block schematic: the printer only reaches 4.5 blocks, so somebody has to move.
     */
    private final Setting<MoveMode> autoMove = new Setting<>("AutoMove", MoveMode.Auto);
    private final Setting<Float> moveSpeed = new Setting<>("MoveSpeed", 0.32f, 0.05f, 1f, v -> autoMove.getValue() != MoveMode.Off);
    /** How high above the target block to hover while flying, so the model is in reach and in view. */
    private final Setting<Float> flyHeight = new Setting<>("FlyHeight", 1.6f, 0f, 4f, v -> autoMove.getValue() != MoveMode.Off);
    /** Seconds of no progress on one block before writing it off as unreachable. 0 = never give up. */
    private final Setting<Integer> skipStuck = new Setting<>("SkipStuck", 8, 0, 60);

    // ---- preview ----
    /**
     * Models is the real thing: every missing block drawn with its own Minecraft model and texture, so
     * the preview reads as an almost-finished build with the gaps standing out - dirt still looks like
     * dirt and a chest still looks like a chest. Boxes is the cheap fallback for a weak machine.
     */
    private final Setting<GhostMode> ghostMode = new Setting<>("Preview", GhostMode.Models);
    private final Setting<Integer> ghostAlpha = new Setting<>("Opacity", 170, 20, 255, v -> ghostMode.getValue() != GhostMode.Off);
    /** Slightly under a full block, so a preview block never z-fights with the real block next to it. */
    private final Setting<Float> ghostScale = new Setting<>("ModelScale", 0.98f, 0.3f, 1f, v -> modelPreview());
    /**
     * How far the accent colour is mixed into the model. A touch of it says "not built yet"; turning it
     * up paints the whole build purple, which is exactly what the preview is supposed to avoid.
     */
    private final Setting<Float> ghostTint = new Setting<>("AccentTint", 0.14f, 0f, 0.6f, v -> modelPreview());
    private final Setting<Boolean> throughWalls = new Setting<>("ThroughWalls", true, v -> ghostMode.getValue() != GhostMode.Off);
    private final Setting<Integer> ghostRange = new Setting<>("PreviewRange", 64, 8, 512, v -> ghostMode.getValue() != GhostMode.Off);
    /**
     * Safety cap on preview blocks per frame, nearest first. Deliberately high enough that PreviewRange
     * is what you actually see: when the count is the binding limit the picture visibly re-picks itself
     * as you walk, which reads as the build sliding around instead of standing still.
     */
    private final Setting<Integer> ghostLimit = new Setting<>("PreviewLimit", 12000, 100, 60000, v -> ghostMode.getValue() != GhostMode.Off);
    /** Skip blocks walled in on all six sides - nothing can ever see them. */
    private final Setting<Boolean> surfaceOnly = new Setting<>("SurfaceOnly", true, v -> ghostMode.getValue() != GhostMode.Off);
    private final Setting<Boolean> fill = new Setting<>("Fill", true, v -> ghostMode.getValue() == GhostMode.Boxes);
    private final Setting<Boolean> outline = new Setting<>("Outline", true, v -> ghostMode.getValue() != GhostMode.Off);
    /** Blocks that are built but built WRONG - right block, wrong facing/half. Marked, not hidden. */
    private final Setting<Boolean> showIncorrect = new Setting<>("ShowIncorrect", true, v -> ghostMode.getValue() != GhostMode.Off);
    private final Setting<Boolean> showOrigin = new Setting<>("ShowOrigin", true);
    private final Setting<Boolean> showBoundingBox = new Setting<>("ShowBoundingBox", true);
    private final Setting<ColorSetting> noMaterialColor = new Setting<>("NoMaterialColor", new ColorSetting(0x55FF3030), v -> ghostMode.getValue() != GhostMode.Off);
    private final Setting<ColorSetting> incorrectColor = new Setting<>("IncorrectColor", new ColorSetting(0x66FFAA00), v -> showIncorrect.getValue());
    private final Setting<ColorSetting> accentColor = new Setting<>("AccentColor", new ColorSetting(0xFF9B5CFF));
    private final Setting<ColorSetting> selectionColor = new Setting<>("SelectionColor", new ColorSetting(0x3300FF80));

    // ---- state shared with BuildCommand / BuildScreen ----
    public static BlockPos pos1, pos2;
    public static Schematic schematic;
    public static BlockPos origin;
    public static boolean building;

    /** The todo list, bucketed in space so a huge schematic can be queried around the player. */
    private static final BuildQueue queue = new BuildQueue();
    private static Schematic queuedFor;

    /** Cells further than this are only ever looked at to work out where to walk next. */
    private static final double SCAN_LIMIT = 1024;
    /** Ceiling on how many live blocks one tick may inspect, so a giant schematic cannot stall the game. */
    private static final int SCAN_BUDGET = 8192;

    private final Timer timer = new Timer();
    private final Timer missingTimer = new Timer();

    /** Hotbar slots this module filled from the creative inventory, oldest first - it recycles its own. */
    private final LinkedHashSet<Integer> grabbedSlots = new LinkedHashSet<>();
    /** The last creative grab had nowhere to put the block; the stall report explains what to do. */
    private boolean noGrabSlot;
    /**
     * Blocks that could not be done right now and the reason why. They are NOT marked done - they come
     * back round after {@link #DEFER_RETRY_MS} so a schematic never quietly loses blocks because the
     * chunk was not loaded yet or the support had not been built underneath.
     */
    private static final Map<BlockPos, Deferred> deferred = new HashMap<>();
    /** Built, but built wrong: right block, wrong facing/half. Shown as a warning, not retried. */
    private static final Map<BlockPos, BlockState> incorrect = new LinkedHashMap<>();
    private static final long DEFER_RETRY_MS = 15_000;
    /** After this many parked attempts a block is written off rather than retried forever. */
    private static final int MAX_RETRIES = 5;
    /** How long a freshly placed block keeps its BUILDING / COMPLETED flourish, in ms. */
    private static final long TRANSITION_MS = 450;
    /** Past this many blocks the bucketing goes off-thread; below it the scheduling costs more. */
    private static final int ASYNC_THRESHOLD = 20_000;

    /** True while a large schematic is being bucketed on a worker. */
    private static volatile boolean preparing;
    /** Placing is held, but nothing is forgotten. See {@link #isPaused()}. */
    private static boolean paused;

    /** Blocks that ran out of retries, and what beat them. */
    private static final Map<BlockPos, StallReason> failed = new LinkedHashMap<>();
    /** Blocks placed in the last {@link #TRANSITION_MS} - drawn mid-transition rather than popping. */
    private static final Map<BlockPos, Long> transitioning = new LinkedHashMap<>();

    /** Where the printer is currently heading, for the HUD line and to stop movement when it arrives. */
    private BlockPos moveTarget;
    /** When it started heading there - a target it never reaches gets written off, see trackProgress. */
    private long targetSince;
    private int skipped;
    private final Timer skipTimer = new Timer();

    @Override
    public void onEnable() {
        grabbedSlots.clear();
        skipped = 0;
        moveTarget = null;
        targetSince = System.currentTimeMillis();
        rebuildQueue();
    }

    @Override
    public void onDisable() {
        building = false;
        moveTarget = null;
        grabbedSlots.clear();
    }

    @Override
    public String getDisplayInfo() {
        if (schematic == null) return null;
        return (queue.total() - queue.remaining()) + "/" + queue.total();
    }

    /* ------------------------------------------------------------------ */
    /* Failure handling                                                    */
    /* ------------------------------------------------------------------ */

    /** Why a block is not done yet - kept so the stall report can say something useful. */
    public enum StallReason {
        NoMaterial("brak materialu"),
        NoSupport("nie ma o co oprzec"),
        Unreachable("nie do zasiegu"),
        ChunkUnavailable("chunk niezaladowany"),
        WrongState("zly stan bloku - trzeba zbic recznie");

        public final String label;

        StallReason(String label) {
            this.label = label;
        }
    }

    private record Deferred(StallReason reason, long until, int attempts) {
    }

    /**
     * Parks a block for a while with a reason instead of hammering it or losing it.
     *
     * <p>The attempt count is what turns "try again later" into something that can end. Without a
     * ceiling a genuinely impossible block - buried in bedrock, inside somebody's base, a state the
     * server keeps rewriting - comes back every fifteen seconds forever, and the build never reports
     * itself finished because one block is still pending.
     */
    private static void defer(BlockPos rel, StallReason reason) {
        Deferred previous = deferred.get(rel);
        int attempts = previous == null ? 1 : previous.attempts() + 1;

        if (attempts > MAX_RETRIES) {
            // Written off, with the reason kept so the report can say what beat it.
            failed.put(rel, reason);
            deferred.remove(rel);
            queue.markDone(rel);
            return;
        }

        // Each retry waits longer than the last: a block that failed once may just need a moment, a
        // block that has failed five times is not going to be fixed by asking faster.
        long wait = DEFER_RETRY_MS * attempts;
        deferred.put(rel, new Deferred(reason, System.currentTimeMillis() + wait, attempts));
    }

    private static boolean isDeferred(BlockPos rel) {
        Deferred d = deferred.get(rel);
        if (d == null) return false;
        if (System.currentTimeMillis() >= d.until()) {
            // Left in the map so the attempt count survives; the timer being up is what lets it retry.
            deferred.put(rel, new Deferred(d.reason(), 0, d.attempts()));
            return false;
        }
        return true;
    }

    /**
     * True while a block is placed-but-unconfirmed. BUILDING, in the spec's words: the packet is out, the
     * world has not said yes yet, and the preview shows a transition rather than a hard pop.
     */
    private static boolean isTransitioning(BlockPos rel) {
        Long at = transitioning.get(rel);
        if (at == null) return false;
        if (System.currentTimeMillis() - at > TRANSITION_MS) {
            transitioning.remove(rel);
            return false;
        }
        return true;
    }

    /**
     * Whether whatever this block needs underneath it is there yet.
     *
     * <p>Only asks about the block below, and only when the schematic itself puts something there. A full
     * dependency graph would be the "right" answer and the wrong trade: the real failure mode is a wall
     * being attempted before its foundation, and one lookup catches that. Anything subtler gets caught by
     * the placement simply failing and the block being parked with a reason.
     */
    private static boolean supportReady(@NotNull BlockPos rel) {
        if (schematic == null || origin == null) return true;

        BlockPos belowRel = rel.down();
        BlockState wanted = schematic.getBlocks().get(belowRel);
        // The schematic wants nothing there: whatever the world has is as good as it is going to get.
        if (wanted == null || wanted.isAir()) return true;

        BlockState world = mc.world.getBlockState(origin.add(belowRel));
        return !world.isReplaceable();
    }

    /** Blocks that ran out of retries. The build is finished with them either way. */
    public static int getFailedCount() {
        return failed.size();
    }

    public static @NotNull Map<StallReason, Integer> failedByReason() {
        Map<StallReason, Integer> out = new LinkedHashMap<>();
        for (StallReason reason : failed.values()) out.merge(reason, 1, Integer::sum);
        return out;
    }

    /** Blocks currently parked, by reason - what the GUI and the stall report read. */
    public static @NotNull Map<StallReason, Integer> pendingByReason() {
        Map<StallReason, Integer> out = new LinkedHashMap<>();
        for (Deferred d : deferred.values()) out.merge(d.reason(), 1, Integer::sum);
        return out;
    }

    public static int getIncorrectCount() {
        return incorrect.size();
    }

    /* ------------------------------------------------------------------ */
    /* Materials                                                           */
    /* ------------------------------------------------------------------ */

    /** One line of the material list: what the schematic needs, what is in the bags, what is short. */
    public record Material(@NotNull Item item, int required, int available, int placed) {
        public int missing() {
            return Math.max(0, required - placed - available);
        }
    }

    /**
     * The whole material picture, largest requirement first. Required comes from the schematic, placed
     * from what has actually been verified in the world, available from the inventory right now.
     */
    public static @NotNull List<Material> materials() {
        if (schematic == null) return List.of();

        Map<Item, Integer> required = new LinkedHashMap<>();
        for (Map.Entry<Item, Integer> e : schematic.getMaterials()) required.merge(e.getKey(), e.getValue(), Integer::sum);

        Map<Item, Integer> placed = queue.doneByItem();

        List<Material> out = new ArrayList<>(required.size());
        for (Map.Entry<Item, Integer> e : required.entrySet())
            out.add(new Material(e.getKey(), e.getValue(), InventoryUtility.getItemCount(e.getKey()), placed.getOrDefault(e.getKey(), 0)));

        out.sort(Comparator.comparingInt(Material::required).reversed());
        return out;
    }

    /** (Re)builds the todo list from the loaded schematic. Called on /build place, /build go and enable. */
    public static void rebuildQueue() {
        paused = false;
        deferred.clear();
        incorrect.clear();
        failed.clear();
        transitioning.clear();

        if (schematic == null || origin == null) {
            queue.clear();
            queuedFor = null;
            return;
        }

        // Re-bucketing a 150k block schematic is not free, so a plain "place it somewhere else" keeps the
        // buckets and only forgets the progress.
        if (schematic == queuedFor) {
            queue.resetProgress();
            return;
        }

        // Small schematics are bucketed inline - handing a house to another thread costs more in
        // scheduling than it saves, and the preview is up a frame sooner.
        if (schematic.size() <= ASYNC_THRESHOLD) {
            queue.load(schematic);
            queuedFor = schematic;
            return;
        }

        loadAsync(schematic);
    }

    /**
     * Buckets a large schematic off the main thread.
     *
     * <p>Bucketing walks every block and works out which ones are visible from outside; at a hundred
     * thousand blocks that is a visible freeze on the render thread, and it happens at the worst moment -
     * right as you place the schematic and are looking at it.
     *
     * <p>The result is swapped in on the main thread. The staging queue is filled in isolation and only
     * becomes the live one once it is complete, so nothing ever reads a half-bucketed queue; and a second
     * load started while the first is running simply wins, because the stale one checks before swapping.
     */
    private static void loadAsync(@NotNull Schematic target) {
        preparing = true;
        BuildQueue staging = new BuildQueue();

        Managers.ASYNC.run(() -> {
            try {
                staging.load(target);
            } catch (Throwable t) {
                ThunderHack.LOGGER.error("[Cloudan] schematic bucketing failed: {}", t.toString());
                preparing = false;
                return;
            }

            mc.execute(() -> {
                // Somebody loaded something else while we were working: that one is the truth now.
                if (schematic != target) {
                    preparing = false;
                    return;
                }

                queue.adopt(staging);
                queuedFor = target;
                preparing = false;
            });
        });
    }

    /**
     * Paused keeps everything - the queue, the progress, the preview - and simply stops placing. Stop
     * throws the run away; those are different things and the GUI now offers both.
     */
    public static boolean isPaused() {
        return paused;
    }

    public static void setPaused(boolean value) {
        paused = value;
    }

    /** True while a big schematic is still being bucketed. The GUI says so rather than looking frozen. */
    public static boolean isPreparing() {
        return preparing;
    }

    public static int getRemaining() {
        return queue.remaining();
    }

    public static int getTotal() {
        return queue.total();
    }

    public static @Nullable BlockPos getMin() {
        if (pos1 == null || pos2 == null) return null;
        return new BlockPos(Math.min(pos1.getX(), pos2.getX()), Math.min(pos1.getY(), pos2.getY()), Math.min(pos1.getZ(), pos2.getZ()));
    }

    public static @Nullable BlockPos getMax() {
        if (pos1 == null || pos2 == null) return null;
        return new BlockPos(Math.max(pos1.getX(), pos2.getX()), Math.max(pos1.getY(), pos2.getY()), Math.max(pos1.getZ(), pos2.getZ()));
    }

    @Override
    public void onUpdate() {
        if (fullNullCheck() || !building || schematic == null || origin == null) return;
        // Nothing to work from until the buckets exist, and nothing to do while held.
        if (preparing || paused) return;
        if (queue.total() == 0) return;

        // The scan runs every tick even when the placing is on a delay: it is what feeds the walker and
        // the creative grab, and both of those have to keep working between two placements.
        Scan scan = scan();

        if (queue.remaining() == 0) {
            building = false;
            moveTarget = null;
            sendMessage(isRu() ? "Постройка завершена!" : "Budowa skonczona!");
            if (disableOnFinish.getValue()) disable();
            return;
        }

        // Creative has no material problem, only a hotbar problem: pull what is missing in reach, and when
        // nothing is in reach yet, stock up for the block being walked towards.
        Item grabTarget = !scan.missing.isEmpty() ? scan.missing.keySet().iterator().next() : scan.nearestItem;
        boolean grabbed = creativeGrab.getValue() && grabTarget != null
                && !InventoryUtility.findItemInHotBar(grabTarget).found() && grabCreative(grabTarget);

        // Walk/fly to the next block that needs placing. Stops on its own once it is in reach.
        trackProgress(scan);
        autoMove(scan.nearestWorld);

        if (!timer.passedMs(delay.getValue())) return;

        if (scan.candidates.isEmpty()) {
            if (!grabbed && notifyMissing.getValue() && missingTimer.passedMs(8000)) {
                missingTimer.reset();
                reportStall(scan);
            }
            return;
        }

        scan.candidates.sort(order.getValue() == Order.Nearest
                ? Comparator.comparingDouble(c -> c.distance)
                : Comparator.<Candidate>comparingInt(c -> c.world.getY()).thenComparingDouble(c -> c.distance));

        int placed = 0;
        for (Candidate candidate : scan.candidates) {
            if (placed >= blocksPerTick.getValue()) break;

            if (InteractionUtility.placeBlock(candidate.world, rotate.getValue(), interact.getValue(), placeMode.getValue(),
                    candidate.result.slot(), returnSlot.getValue(), ignoreEntities.getValue())) {
                // BUILDING, not COMPLETED: the packet went out, which is not the same as the block being
                // there. The scan verifies it against the world next tick and only then marks it done.
                transitioning.put(candidate.relative, System.currentTimeMillis());
                deferred.remove(candidate.relative);
                placed++;
            }
        }

        if (placed > 0) {
            timer.reset();
            targetSince = System.currentTimeMillis(); // real progress: the stuck clock starts over
        }
    }

    /** What one tick's look around the player found. */
    private static final class Scan {
        final List<Candidate> candidates = new ArrayList<>();
        final Map<Item, Integer> missing = new LinkedHashMap<>();
        BlockPos nearestWorld;
        BlockPos nearestRel;
        Item nearestItem;
        double nearestSq = Double.MAX_VALUE;
        int inRange;
        /** Layer being worked on, or -1 when the printer is not building bottom up. */
        int activeLayer = -1;
    }

    /**
     * Looks at the build around the player, nearest cell first: what can be placed right now, what is
     * missing from the hotbar, and where the closest unbuilt block is.
     *
     * <p>Nearest-first is the whole point. The flat list this replaced always started at block zero and
     * gave up after 32768 of them, so on a big schematic everything past the beginning was invisible to
     * the printer no matter where the player stood.
     */
    private @NotNull Scan scan() {
        Scan scan = new Scan();

        Vec3d eyes = InteractionUtility.getEyesPos(mc.player);
        double r = range.getValue();
        double rSq = r * r;
        int budget = SCAN_BUDGET;

        // Bottom up: everything above the layers currently being worked on is off limits, for placing AND
        // for walking. Without the walker being held to the same layers it would fly off to the nearest
        // block anywhere in the build and the "lowest first" ordering would mean nothing.
        int topLayer = order.getValue() == Order.BottomUp
                ? queue.activeLayer() + layerWindow.getValue() - 1
                : Integer.MAX_VALUE;
        scan.activeLayer = order.getValue() == Order.BottomUp ? queue.activeLayer() : -1;

        for (BuildQueue.Cell cell : queue.cellsByDistance(eyes, origin, SCAN_LIMIT)) {
            // Cells out of reach only matter until a walking target has been found.
            if (scan.nearestWorld != null && cell.distance() - 14 > r) break;
            if (budget <= 0) break;
            // A whole cell above the working layers is 4096 blocks nobody has to look at.
            if (BuildQueue.cellMinY(cell.key()) > topLayer) continue;

            for (BuildQueue.Block block : queue.cell(cell.key())) {
                if (queue.isDone(block.rel())) continue;
                if (block.rel().getY() > topLayer) continue;
                if (--budget <= 0) break;

                if (isDeferred(block.rel())) continue;
                // Just placed and not yet confirmed by the world - asking again would only duplicate the
                // packet while the server is still answering the first one.
                if (isTransitioning(block.rel())) continue;

                BlockPos wp = origin.add(block.rel());
                BlockState current = mc.world.getBlockState(wp);

                // CORRECT: the block is there AND in the state the schematic asked for. Anything looser
                // would call a north-facing staircase done when the world holds a south-facing one.
                if (SchematicMatch.matches(block.state(), current)) {
                    // COMPLETED - verified against the world, not assumed from a packet. If it was one of
                    // ours the transition stamp is refreshed so the preview fades rather than blinking out.
                    if (transitioning.containsKey(block.rel())) transitioning.put(block.rel(), System.currentTimeMillis());
                    queue.markDone(block.rel());
                    incorrect.remove(block.rel());
                    deferred.remove(block.rel());
                    continue;
                }

                Item item = block.state().getBlock().asItem();
                if (item == Items.AIR) {
                    queue.markDone(block.rel());
                    continue;
                }

                // INCORRECT: right block, wrong state. The printer cannot fix it by placing - it would
                // have to break first, which this module deliberately does not do - so it is recorded,
                // shown as a warning and skipped rather than retried forever.
                if (SchematicMatch.wrongStateOnly(block.state(), current)) {
                    incorrect.put(block.rel(), block.state());
                    defer(block.rel(), StallReason.WrongState);
                    continue;
                }
                incorrect.remove(block.rel());

                // Something else is in the way: not placeable, but still worth walking to and showing.
                boolean replaceable = current.isReplaceable();

                double dist = eyes.squaredDistanceTo(wp.toCenterPos());
                if (replaceable && dist < scan.nearestSq) {
                    scan.nearestSq = dist;
                    scan.nearestWorld = wp;
                    scan.nearestRel = block.rel();
                    scan.nearestItem = item;
                }

                if (!replaceable || dist > rSq) continue;
                scan.inRange++;

                SearchInvResult result = InventoryUtility.findItemInHotBar(item);
                if (!result.found()) {
                    scan.missing.merge(item, 1, Integer::sum);
                    continue;
                }

                // Dependencies: a block the schematic wants something underneath is not worth trying until
                // that something exists. Skipping it now is what stops the printer wasting its whole tick
                // budget on blocks that physically cannot stand yet.
                if (!supportReady(block.rel())) {
                    defer(block.rel(), StallReason.NoSupport);
                    continue;
                }

                if (!InteractionUtility.canPlaceBlock(wp, interact.getValue(), ignoreEntities.getValue())) continue;

                scan.candidates.add(new Candidate(wp, block.rel(), result, dist));
            }
        }

        return scan;
    }

    /**
     * Keeps the walker honest. A block can be genuinely unreachable - buried in bedrock, inside another
     * build, on the wrong side of a wall the printer will not break - and without this the whole thing
     * parks in front of it forever, which is exactly how an "it builds by itself" feature stops building
     * by itself. After SkipStuck seconds of no progress on the same target it is written off and the
     * printer moves on to the next block.
     */
    private void trackProgress(@NotNull Scan scan) {
        BlockPos target = scan.nearestWorld;

        if (target == null || !target.equals(moveTarget)) {
            moveTarget = target;
            targetSince = System.currentTimeMillis();
            return;
        }

        if (skipStuck.getValue() <= 0) return;
        if (System.currentTimeMillis() - targetSince < skipStuck.getValue() * 1000L) return;

        if (scan.nearestRel != null) {
            // Parked, not written off: it comes back round in DEFER_RETRY_MS, by which time the support
            // under it may exist, the chunk may have loaded or the material may be in the bag.
            defer(scan.nearestRel, mc.world.getChunkManager().getWorldChunk(
                    (origin.getX() + scan.nearestRel.getX()) >> 4,
                    (origin.getZ() + scan.nearestRel.getZ()) >> 4) == null
                    ? StallReason.ChunkUnavailable : StallReason.Unreachable);
            skipped++;
            if (notifyMissing.getValue() && skipTimer.passedMs(10000)) {
                skipTimer.reset();
                sendMessage(Formatting.YELLOW + "Odkladam bloki nie do postawienia na pozniej (" + skipped + ").");
            }
        }

        moveTarget = null;
        targetSince = System.currentTimeMillis();
    }

    /* ------------------------------------------------------------------ */
    /* Movement                                                            */
    /* ------------------------------------------------------------------ */

    /**
     * Takes the player to the next block that needs placing. Flying is a straight line at the build; on
     * foot it is a straight line plus a hop when something is in the way - enough for open ground and for
     * walking along a build, not a replacement for a real pathfinder in a cave.
     *
     * <p>Any movement key the player touches hands control straight back, so it never fights you.
     */
    private void autoMove(@Nullable BlockPos target) {
        if (autoMove.getValue() == MoveMode.Off || target == null || playerIsSteering()) return;

        Vec3d goal = target.toCenterPos();
        Vec3d me = mc.player.getEntityPos();
        double stop = Math.max(1.2, range.getValue() - 1.2);

        boolean fly = autoMove.getValue() == MoveMode.Fly
                || (autoMove.getValue() == MoveMode.Auto && mc.player.getAbilities().allowFlying);

        double dx = goal.x - me.x;
        double dz = goal.z - me.z;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        double dy = goal.y + (fly ? flyHeight.getValue() : 0) - me.y;

        // Close enough to place: stand still, and stop nudging the flight so it does not drift off.
        if (horizontal <= stop && Math.abs(dy) <= stop) {
            if (fly && mc.player.getAbilities().flying) mc.player.setVelocity(0, 0, 0);
            return;
        }

        double speed = moveSpeed.getValue();
        double vx = horizontal < 1e-4 ? 0 : dx / horizontal * speed;
        double vz = horizontal < 1e-4 ? 0 : dz / horizontal * speed;

        if (fly) {
            mc.player.getAbilities().flying = true;
            mc.player.setVelocity(vx, MathHelper.clamp(dy * 0.35, -speed, speed), vz);
            return;
        }

        mc.player.setVelocity(vx, mc.player.getVelocity().y, vz);
        if (mc.player.horizontalCollision && mc.player.isOnGround()) mc.player.jump();
    }

    private boolean playerIsSteering() {
        return mc.options.forwardKey.isPressed() || mc.options.backKey.isPressed()
                || mc.options.leftKey.isPressed() || mc.options.rightKey.isPressed()
                || mc.options.jumpKey.isPressed() || mc.options.sneakKey.isPressed();
    }

    /* ------------------------------------------------------------------ */
    /* Creative + diagnostics                                              */
    /* ------------------------------------------------------------------ */

    /**
     * Says why nothing is being placed. A printer that quietly does nothing is impossible to tell apart
     * from a broken one, and every one of these has a different fix.
     */
    private void reportStall(@NotNull Scan scan) {
        if (!scan.missing.isEmpty()) {
            StringBuilder sb = new StringBuilder("Brak blokow w hotbarze: ");
            for (Map.Entry<Item, Integer> e : scan.missing.entrySet())
                sb.append(Formatting.RED).append(e.getValue()).append("x ").append(e.getKey().getName().getString()).append(Formatting.RESET).append(", ");
            sendMessage(sb.substring(0, Math.max(0, sb.length() - 2)));

            if (creativeGrab.getValue() && noGrabSlot)
                sendMessage(Formatting.YELLOW + "Hotbar zapchany - zwolnij slot albo wylacz CreativeKeepTools.");
            else if (!creativeGrab.getValue() && isCreative())
                sendMessage(Formatting.YELLOW + "Jestes w creative - wlacz CreativeGrab, sam wezmie bloki.");
            return;
        }

        if (scan.inRange == 0 && scan.nearestWorld != null) {
            String hint = autoMove.getValue() == MoveMode.Off ? " Wlacz AutoMove albo podejdz." : " Ide tam.";
            sendMessage(Formatting.YELLOW + "Najblizszy blok " + Formatting.RESET
                    + String.format("%.1f", Math.sqrt(scan.nearestSq)) + Formatting.YELLOW + " kratki stad." + hint);
            return;
        }

        if (scan.inRange > 0)
            sendMessage(Formatting.YELLOW + "Nie ma o co oprzec blokow - sprobuj Interact: Strict/Ignore albo dostaw podloze.");
    }

    private boolean isCreative() {
        return mc.interactionManager != null && mc.interactionManager.getCurrentGameMode().isCreative();
    }

    /**
     * Fetches a missing block out of the creative inventory into the hotbar - no inventory screen needed,
     * the same packet the creative menu sends when you drag an item onto the bar.
     *
     * <p>Only one item is taken: creative never consumes what it places, so a full stack buys nothing.
     */
    private boolean grabCreative(Item item) {
        if (!isCreative()) return false;
        if (item == Items.AIR || InventoryUtility.findItemInHotBar(item).found()) return false;

        int slot = pickGrabSlot();
        noGrabSlot = slot < 0;
        if (slot < 0) return false;

        int count = Math.min(creativeGrabCount.getValue(), item.getMaxCount());
        mc.interactionManager.clickCreativeStack(new ItemStack(item, count), 36 + slot);

        // Remember it as ours and move it to the back of the queue, so the next grab recycles the slot
        // that has been sitting around the longest instead of fighting over one.
        grabbedSlots.remove(slot);
        grabbedSlots.add(slot);
        return true;
    }

    /**
     * Where a fetched block goes: an empty slot first, then the oldest slot the printer filled itself.
     * Overwriting whatever the player is carrying is the last resort, and off entirely with KeepTools.
     */
    private int pickGrabSlot() {
        for (int i = 0; i < 9; i++)
            if (mc.player.getInventory().getStack(i).isEmpty()) return i;

        for (int slot : grabbedSlots)
            if (slot >= 0 && slot < 9) return slot;

        if (creativeKeepTools.getValue()) {
            // A hotbar packed with blocks still has room to work with: a block is free to get back in
            // creative, a pickaxe with the enchantments you picked is not.
            for (int i = 0; i < 9; i++)
                if (mc.player.getInventory().getStack(i).getItem() instanceof BlockItem) return i;
            return -1;
        }

        return mc.player.getInventory().getSelectedSlot();
    }

    /* ------------------------------------------------------------------ */
    /* Render                                                              */
    /* ------------------------------------------------------------------ */

    /**
     * The real preview: every missing block drawn with its own Minecraft model and texture, in world
     * space. A missing dirt block looks like dirt, a missing staircase faces the way the schematic says,
     * a missing chest is a chest - the build reads as almost finished with the gaps standing out, which
     * is the whole point of a preview. The Cloudan accent is a light mix on top, never a repaint.
     *
     * <p>This rides the 3D pass, which carries the camera rotation exactly once (see MixinGameRenderer),
     * so the models stand on their own grid cells instead of sliding around as the camera turns.
     */
    @Override
    public void onRender3D(MatrixStack matrices) {
        if (fullNullCheck() || mc.options.hudHidden) return;
        if (!modelPreview()) return;
        if (schematic == null || origin == null || queue.remaining() == 0) return;

        Color accent = accentColor.getValue().getColorObject();
        float mix = ghostTint.getValue();
        // The tint is multiplicative, so a full-strength accent would darken every model towards purple.
        // Mixing the accent towards white first keeps dirt looking like dirt with only a hint of colour.
        float tr = 1f - mix + mix * (accent.getRed() / 255f);
        float tg = 1f - mix + mix * (accent.getGreen() / 255f);
        float tb = 1f - mix + mix * (accent.getBlue() / 255f);

        int alpha = ghostAlpha.getValue();
        float scale = ghostScale.getValue();
        boolean tw = throughWalls.getValue();
        int limit = thunder.hack.features.modules.client.ClientSettings.budget(ghostLimit.getValue());
        int drawn = 0;

        Vec3d cam = mc.getEntityRenderDispatcher().camera.getCameraPos();

        outer:
        for (BuildQueue.Cell cell : queue.cellsByDistance(cam, origin, ghostRange.getValue())) {
            for (BuildQueue.Block block : queue.cell(cell.key())) {
                if (drawn >= limit) break outer;
                if (queue.isDone(block.rel())) continue;                       // COMPLETED - nothing to draw
                if (surfaceOnly.getValue() && !block.surface()) continue;

                BlockPos wp = origin.add(block.rel());
                BlockState current = mc.world.getBlockState(wp);
                boolean wrongState = SchematicMatch.wrongStateOnly(block.state(), current);

                // INCORRECT is a real block already standing there; drawing a second model inside it
                // would just z-fight. The warning box in the 2D pass is what marks those.
                if (wrongState) continue;
                if (!current.isReplaceable()) continue;                        // something else in the way

                // BUILDING: placed, not yet confirmed. Brightened and pulsing for a moment, so you can
                // see which block the printer is actually working on rather than guessing.
                int blockAlpha = alpha;
                float pulse = 1f;
                Long since = transitioning.get(block.rel());
                if (since != null) {
                    float t = (System.currentTimeMillis() - since) / (float) TRANSITION_MS;
                    if (t < 1f) {
                        pulse = 1f + (1f - t) * 0.5f;
                        blockAlpha = Math.min(255, Math.round(alpha * (1f + (1f - t) * 0.6f)));
                    }
                }

                BlockModelRender.render(matrices, block.state(), wp, blockAlpha, scale,
                        Math.min(1f, tr * pulse), Math.min(1f, tg * pulse), Math.min(1f, tb * pulse), tw);
                drawn++;
            }
        }

        renderCompleted(matrices, alpha, scale, tw);
        BlockModelRender.flush();
    }

    /**
     * COMPLETED: a block that has just been verified keeps its preview for a moment and fades it out,
     * instead of the model vanishing the instant the world confirms it.
     *
     * <p>The queue has already forgotten these - that is what "done" means - so they are drawn from the
     * transition map on their own rather than from the todo list.
     */
    private void renderCompleted(MatrixStack matrices, int alpha, float scale, boolean tw) {
        if (transitioning.isEmpty() || origin == null || schematic == null) return;

        long now = System.currentTimeMillis();
        transitioning.entrySet().removeIf(e -> now - e.getValue() > TRANSITION_MS);

        for (Map.Entry<BlockPos, Long> entry : transitioning.entrySet()) {
            if (!queue.isDone(entry.getKey())) continue;   // still BUILDING, drawn by the main pass

            BlockState state = schematic.getBlocks().get(entry.getKey());
            if (state == null || state.isAir()) continue;

            float fade = 1f - (now - entry.getValue()) / (float) TRANSITION_MS;
            if (fade <= 0.02f) continue;

            BlockModelRender.render(matrices, state, origin.add(entry.getKey()),
                    Math.round(alpha * fade), scale, 1f, 1f, 1f, tw);
        }
    }

    @Override
    public void onRender2D(DrawContext context) {
        if (fullNullCheck() || mc.options.hudHidden) return;

        renderSelection(context);
        if (ghostMode.getValue() == GhostMode.Boxes) renderBoxGhost(context);
        else if (modelPreview() && outline.getValue()) renderModelOutlines(context);
        if (showIncorrect.getValue()) renderIncorrect(context);
    }

    private void renderSelection(DrawContext context) {
        Color color = selectionColor.getValue().getColorObject();

        if (showBoundingBox.getValue() && pos1 != null && pos2 != null) {
            BlockPos min = getMin(), max = getMax();
            Box box = new Box(min.getX(), min.getY(), min.getZ(), max.getX() + 1d, max.getY() + 1d, max.getZ() + 1d);
            Render3DEngine.drawProjectedBox(context, box, color, color, Render2DEngine.injectAlpha(color, 255), 2f, false, true);
        } else if (showBoundingBox.getValue() && pos1 != null) {
            Render3DEngine.drawProjectedBox(context, new Box(pos1), color, color, Render2DEngine.injectAlpha(color, 255), 2f, true, true);
        }

        if (showOrigin.getValue() && origin != null) {
            Color accent = accentColor.getValue().getColorObject();
            Render3DEngine.drawProjectedBox(context, new Box(origin), Render2DEngine.injectAlpha(accent, 40), Render2DEngine.injectAlpha(accent, 40),
                    Render2DEngine.injectAlpha(accent, 255), 2f, true, true);
        }
    }

    /**
     * A thin accent outline around the missing blocks, on top of the real models. It is what makes a gap
     * findable in a wall of matching texture without painting the model itself.
     */
    private void renderModelOutlines(DrawContext context) {
        if (schematic == null || origin == null || queue.remaining() == 0) return;

        Color accent = Render2DEngine.injectAlpha(accentColor.getValue().getColorObject(), 120);
        Vec3d cam = mc.getEntityRenderDispatcher().camera.getCameraPos();
        List<Render3DEngine.BoxSpec> boxes = new ArrayList<>();
        // Outlines are per-block screen work, so they stay near the player even when the models do not.
        int limit = thunder.hack.features.modules.client.ClientSettings.budget(Math.min(ghostLimit.getValue(), 2000));

        outer:
        for (BuildQueue.Cell cell : queue.cellsByDistance(cam, origin, Math.min(ghostRange.getValue(), 48))) {
            for (BuildQueue.Block block : queue.cell(cell.key())) {
                if (boxes.size() >= limit) break outer;
                if (queue.isDone(block.rel())) continue;
                if (!block.surface()) continue;

                BlockPos wp = origin.add(block.rel());
                if (!mc.world.getBlockState(wp).isReplaceable()) continue;

                boxes.add(new Render3DEngine.BoxSpec(new Box(wp), accent, accent, 1f, false, true));
            }
        }

        Render3DEngine.drawProjectedBoxes(context, boxes);
    }

    /** Right block, wrong state: marked with a warning box so it can be fixed by hand. */
    private void renderIncorrect(DrawContext context) {
        if (origin == null || incorrect.isEmpty()) return;

        Color color = incorrectColor.getValue().getColorObject();
        Color line = Render2DEngine.injectAlpha(color, 255);
        Vec3d cam = mc.getEntityRenderDispatcher().camera.getCameraPos();
        double rangeSq = ghostRange.getValue() * (double) ghostRange.getValue();
        List<Render3DEngine.BoxSpec> boxes = new ArrayList<>();

        for (BlockPos rel : new ArrayList<>(incorrect.keySet())) {
            BlockPos wp = origin.add(rel);
            if (cam.squaredDistanceTo(wp.toCenterPos()) > rangeSq) continue;
            boxes.add(new Render3DEngine.BoxSpec(new Box(wp), color, line, 1.5f, true, true));
            if (boxes.size() >= 512) break;
        }

        Render3DEngine.drawProjectedBoxes(context, boxes);
    }

    /**
     * The cheap fallback ghost: projected 3D cubes in each block's map colour, all of them submitted as ONE
     * batch. One GUI element per box would stack one GuiRenderState layer per box and blow the stack -
     * see {@link Render3DEngine#drawProjectedBoxes}.
     */
    private void renderBoxGhost(DrawContext context) {
        if (schematic == null || origin == null || queue.remaining() == 0) return;

        Color noMatCol = noMaterialColor.getValue().getColorObject();
        Vec3d cam = mc.getEntityRenderDispatcher().camera.getCameraPos();
        Map<Item, Boolean> haveMaterial = new LinkedHashMap<>();
        int limit = ghostLimit.getValue();
        int alpha = ghostAlpha.getValue();
        List<Render3DEngine.BoxSpec> ghosts = new ArrayList<>();

        outer:
        for (BuildQueue.Cell cell : queue.cellsByDistance(cam, origin, ghostRange.getValue())) {
            for (BuildQueue.Block block : queue.cell(cell.key())) {
                if (ghosts.size() >= limit) break outer;
                if (queue.isDone(block.rel())) continue;
                if (surfaceOnly.getValue() && !block.surface()) continue;

                BlockPos wp = origin.add(block.rel());
                if (!mc.world.getBlockState(wp).isReplaceable()) continue;

                Item item = block.state().getBlock().asItem();
                boolean hasMaterial = item != Items.AIR
                        && haveMaterial.computeIfAbsent(item, i -> InventoryUtility.getItemCount(i) > 0);

                Color color = hasMaterial ? blockColor(block.state(), wp, alpha) : noMatCol;
                Box box = new Box(wp.getX() + 0.15, wp.getY() + 0.15, wp.getZ() + 0.15,
                        wp.getX() + 0.85, wp.getY() + 0.85, wp.getZ() + 0.85);

                ghosts.add(new Render3DEngine.BoxSpec(box, color, Render2DEngine.injectAlpha(lighten(color, 0.45f), 255),
                        1.5f, fill.getValue(), outline.getValue()));
            }
        }

        Render3DEngine.drawProjectedBoxes(context, ghosts);
    }

    /** Mixes a colour towards white by {@code amount} (0..1), keeping its alpha. */
    private static @NotNull Color lighten(@NotNull Color c, float amount) {
        return new Color(
                (int) (c.getRed() + (255 - c.getRed()) * amount),
                (int) (c.getGreen() + (255 - c.getGreen()) * amount),
                (int) (c.getBlue() + (255 - c.getBlue()) * amount),
                c.getAlpha());
    }

    private @NotNull Color blockColor(@NotNull BlockState state, @NotNull BlockPos pos, int alpha) {
        try {
            int rgb = state.getMapColor(mc.world, pos).color;
            // map colours are the darkened top-down palette - stone/deepslate come out as a grey smudge you
            // cannot read against the world, so lift every ghost towards white before drawing it
            Color raw = new Color((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF, alpha);
            return lighten(raw, 0.35f);
        } catch (Exception e) {
            return noMaterialColor.getValue().getColorObject();
        }
    }
    /** Textured is the legacy name for Models; both mean "real block models". */
    private boolean modelPreview() {
        return ghostMode.getValue() == GhostMode.Models || ghostMode.getValue() == GhostMode.Textured;
    }


    public enum GhostMode {
        /** Real Minecraft block models and textures. What the preview is supposed to look like. */
        Models,
        /** kept so old configs still load; the same thing as Models. */
        Textured,
        /** Flat coloured cubes. The cheap fallback. */
        Boxes,
        Off
    }

    public enum MoveMode {
        Off,
        Walk,
        Fly,
        Auto
    }

    private record Candidate(@NotNull BlockPos world, @NotNull BlockPos relative, @NotNull SearchInvResult result, double distance) {
    }

    public enum Order {
        BottomUp,
        Nearest
    }
}
