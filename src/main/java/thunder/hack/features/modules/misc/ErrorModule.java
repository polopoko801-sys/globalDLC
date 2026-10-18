package thunder.hack.features.modules.misc;

import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.ButtonBlock;
import net.minecraft.block.LeverBlock;
import net.minecraft.fluid.Fluids;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.HandSwingC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInteractBlockC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import thunder.hack.events.impl.PacketEvent;
import thunder.hack.features.modules.Module;
import thunder.hack.setting.Setting;
import thunder.hack.utility.Timer;
import thunder.hack.utility.player.InteractionUtility;
import thunder.hack.utility.player.InventoryUtility;
import thunder.hack.utility.player.SearchInvResult;

/**
 * Error - the lever/button trick, done for real.
 *
 * <p>Right-click a lever or a button (or the block one is stuck to) and this runs the sequence by hand:
 * water goes on the spot, the water is scooped straight back up, and a cobweb goes where the button was.
 * Fast enough that it reads as one action.
 *
 * <p>Every step is a real interaction with real items out of your inventory - a water bucket, an empty
 * bucket and cobwebs. Nothing is faked client side: if you have no bucket, nothing happens and it says so.
 */
public class ErrorModule extends Module {
    public ErrorModule() {
        super("Error", Category.MISC);
    }

    /**
     * Milliseconds between the steps. Zero by default: the whole point is that it looks like one click.
     *
     * <p>At zero the three steps still land on three separate ticks, which is as fast as the server
     * will accept them - packing them into one tick is a use-item rate no legitimate client produces.
     */
    private final Setting<Integer> delay = new Setting<>("Delay", 0, 0, 1000);
    private final Setting<Boolean> pickupWater = new Setting<>("PickupWater", true);
    private final Setting<Boolean> placeWeb = new Setting<>("Web", true);
    private final Setting<Boolean> returnSlot = new Setting<>("ReturnSlot", true);
    private final Setting<InteractionUtility.Rotate> rotate = new Setting<>("Rotate", InteractionUtility.Rotate.Default);
    private final Setting<InteractionUtility.Interact> interact = new Setting<>("Interact", InteractionUtility.Interact.Vanilla);
    private final Setting<InteractionUtility.PlaceMode> placeMode = new Setting<>("PlaceMode", InteractionUtility.PlaceMode.Packet);
    private final Setting<Float> range = new Setting<>("Range", 4.5f, 1f, 6f);
    private final Setting<Integer> cooldown = new Setting<>("Cooldown", 400, 0, 3000);
    /** Levers and buttons; AnyBlock lets it run on whatever you are looking at. */
    private final Setting<Boolean> anyBlock = new Setting<>("AnyBlock", false);
    private final Setting<Boolean> chatInfo = new Setting<>("ChatInfo", false);

    private enum Stage {
        Idle,
        PlaceWater,
        PickUpWater,
        PlaceWeb
    }

    private Stage stage = Stage.Idle;
    private BlockPos target;
    private final Timer stepTimer = new Timer();
    private final Timer cooldownTimer = new Timer();
    /** Set while the module is sending its own interactions, so it never triggers on itself. */
    private boolean selfInteracting;

    @Override
    public void onEnable() {
        reset();
    }

    @Override
    public void onDisable() {
        reset();
    }

    private void reset() {
        stage = Stage.Idle;
        target = null;
        bucketSlot = -1;
        selfInteracting = false;
    }

    /* ------------------------------------------------------------------ */
    /* Trigger                                                             */
    /* ------------------------------------------------------------------ */

    @EventHandler
    public void onPacketSend(PacketEvent.Send event) {
        if (isOff() || fullNullCheck() || selfInteracting) return;
        if (stage != Stage.Idle) return;
        if (!(event.getPacket() instanceof PlayerInteractBlockC2SPacket packet)) return;
        if (!cooldownTimer.passedMs(cooldown.getValue())) return;

        BlockPos found = resolveTarget(packet.getBlockHitResult().getBlockPos());
        if (found == null) return;
        if (mc.player.getEyePos().squaredDistanceTo(found.toCenterPos()) > range.getPow2Value()) return;

        cooldownTimer.reset();
        target = found;
        stage = Stage.PlaceWater;
        stepTimer.reset();
    }

    /**
     * Where the water and the web go. Clicking the lever itself is the obvious case; clicking the block it
     * hangs on is the one you actually do in a fight, so the neighbours are checked too.
     */
    private @Nullable BlockPos resolveTarget(@NotNull BlockPos clicked) {
        if (isTrigger(mc.world.getBlockState(clicked))) return clicked;
        if (anyBlock.getValue()) return clicked;

        for (Direction direction : Direction.values()) {
            BlockPos side = clicked.offset(direction);
            if (isTrigger(mc.world.getBlockState(side))) return side;
        }

        return null;
    }

    private boolean isTrigger(@NotNull BlockState state) {
        return state.getBlock() instanceof LeverBlock || state.getBlock() instanceof ButtonBlock;
    }

    /* ------------------------------------------------------------------ */
    /* Sequence                                                            */
    /* ------------------------------------------------------------------ */

    @Override
    public void onUpdate() {
        if (stage == Stage.Idle || target == null) return;

        if (fullNullCheck()) {
            reset();
            return;
        }

        if (!stepTimer.passedMs(delay.getValue())) return;
        if (mc.player.getEyePos().squaredDistanceTo(target.toCenterPos()) > range.getPow2Value()) {
            info(Formatting.YELLOW + "Za daleko - przerywam.");
            reset();
            return;
        }

        switch (stage) {
            case PlaceWater -> placeWater();
            case PickUpWater -> pickUpWater();
            case PlaceWeb -> placeWeb();
            default -> reset();
        }

        stepTimer.reset();
    }

    /**
     * The slot the water bucket came out of.
     *
     * <p>Scooping goes back to THIS slot rather than hunting for some other empty bucket: pouring a
     * bucket out leaves an empty one in the same place, which is the bucket you are supposed to pick
     * the water back up with. Searching the hotbar again found a second bucket when you had one and
     * gave up when you did not, leaving the water on the ground.
     */
    private int bucketSlot = -1;

    private void placeWater() {
        SearchInvResult bucket = InventoryUtility.findItemInHotBar(Items.WATER_BUCKET);
        if (!bucket.found()) {
            info(Formatting.RED + "Brak wiadra z woda w hotbarze.");
            reset();
            return;
        }

        bucketSlot = bucket.slot();

        // A water bucket is used ON a face, exactly like placing a block - the water lands in the target.
        run(() -> InteractionUtility.placeBlock(target, rotate.getValue(), interact.getValue(),
                placeMode.getValue(), bucket, returnSlot.getValue(), true));

        stage = pickupWater.getValue() ? Stage.PickUpWater : (placeWeb.getValue() ? Stage.PlaceWeb : Stage.Idle);
        if (stage == Stage.Idle) reset();
    }

    private void pickUpWater() {
        // The same slot first - it is holding the bucket we just emptied.
        SearchInvResult bucket = bucketSlot >= 0 && mc.player.getInventory().getStack(bucketSlot).isOf(Items.BUCKET)
                ? new SearchInvResult(bucketSlot, true, mc.player.getInventory().getStack(bucketSlot))
                : InventoryUtility.findItemInHotBar(Items.BUCKET);
        if (!bucket.found()) {
            info(Formatting.YELLOW + "Brak pustego wiadra - woda zostaje.");
            stage = placeWeb.getValue() ? Stage.PlaceWeb : Stage.Idle;
            if (stage == Stage.Idle) reset();
            return;
        }

        // Scooping is a use on the water block itself, not on a neighbour's face, so it cannot go through
        // placeBlock - that one always clicks an adjacent face to put something INTO the position.
        run(() -> useOn(target, bucket));

        stage = placeWeb.getValue() ? Stage.PlaceWeb : Stage.Idle;
        if (stage == Stage.Idle) reset();
    }

    private void placeWeb() {
        // The button and the water both have to be gone before a web can stand there. The world has not
        // necessarily caught up yet, so one more tick costs nothing and saves a wasted right-click.
        BlockState current = mc.world.getBlockState(target);
        if (!current.isReplaceable() || current.getFluidState().getFluid() == Fluids.WATER) return;

        SearchInvResult web = InventoryUtility.findBlockInHotBar(Blocks.COBWEB);
        if (!web.found()) {
            info(Formatting.RED + "Brak pajeczyny w hotbarze.");
            reset();
            return;
        }

        run(() -> InteractionUtility.placeBlock(target, rotate.getValue(), interact.getValue(),
                placeMode.getValue(), web, returnSlot.getValue(), true));

        info(Formatting.GREEN + "Error " + Formatting.RESET + "zrobiony na " + target.toShortString() + ".");
        reset();
    }

    /* ------------------------------------------------------------------ */
    /* Helpers                                                             */
    /* ------------------------------------------------------------------ */

    /**
     * Uses the held item ON a block position - the right-click you do to scoop a water source back into
     * an empty bucket.
     */
    private void useOn(@NotNull BlockPos pos, @NotNull SearchInvResult result) {
        int previous = mc.player.getInventory().selectedSlot;
        result.switchTo();

        BlockHitResult hit = new BlockHitResult(pos.toCenterPos(), Direction.UP, pos, false);
        float[] angle = InteractionUtility.calculateAngle(hit.getPos());

        switch (rotate.getValue()) {
            case None -> {
            }
            case Default -> mc.player.networkHandler.sendPacket(new PlayerMoveC2SPacket.LookAndOnGround(
                    angle[0], angle[1], mc.player.isOnGround(), mc.player.horizontalCollision));
            case Grim -> mc.player.networkHandler.sendPacket(new PlayerMoveC2SPacket.Full(
                    mc.player.getX(), mc.player.getY(), mc.player.getZ(), angle[0], angle[1],
                    mc.player.isOnGround(), mc.player.horizontalCollision));
        }

        if (placeMode.getValue() == InteractionUtility.PlaceMode.Normal)
            mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, hit);
        else
            InteractionUtility.sendSequencedPacket(id -> new PlayerInteractBlockC2SPacket(Hand.MAIN_HAND, hit, id));

        mc.player.networkHandler.sendPacket(new HandSwingC2SPacket(Hand.MAIN_HAND));

        if (returnSlot.getValue()) InventoryUtility.switchTo(previous);
    }

    /**
     * Runs one of our own interactions with the self-trigger guard up. Without it the very first water
     * bucket would look like a fresh right-click and restart the sequence forever.
     */
    private void run(@NotNull Runnable action) {
        selfInteracting = true;
        try {
            action.run();
        } finally {
            selfInteracting = false;
        }
    }

    private void info(String message) {
        if (chatInfo.getValue()) sendMessage(message);
    }

    @Override
    public String getDisplayInfo() {
        return stage == Stage.Idle ? null : stage.name();
    }
}
