package thunder.hack.features.modules.misc;

import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.fluid.FluidState;
import net.minecraft.fluid.Fluids;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.HandSwingC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInteractBlockC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.network.packet.s2c.play.BlockUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.ChunkDeltaUpdateS2CPacket;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import org.jetbrains.annotations.NotNull;
import thunder.hack.events.impl.PacketEvent;
import thunder.hack.features.modules.Module;
import thunder.hack.setting.Setting;
import thunder.hack.utility.Timer;
import thunder.hack.utility.player.InteractionUtility;
import thunder.hack.utility.player.InventoryUtility;
import thunder.hack.utility.player.SearchInvResult;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Scoops up water somebody else just placed.
 *
 * <p>Water that somebody pours on you - to wash you off an edge, to break your fall, to stop an
 * anchor - is only a problem while it is there. With an empty bucket in the hotbar this takes it back
 * out of the world within a tick or two of it appearing.
 *
 * <p><b>Only water that arrived while you were watching.</b> The trigger is the block update packet
 * that puts it there, not a scan of what is around you - an ocean does not send you block updates, so
 * standing in one does nothing at all, while a bucket poured at your feet does. That is the whole
 * difference between this and a module that empties the sea one source at a time.
 */
public class WaterGrabber extends Module {
    public WaterGrabber() {
        super("WaterGrabber", Category.MISC);
    }

    private final Setting<Float> range = new Setting<>("Range", 4.5f, 1f, 6f);
    /** Milliseconds between two scoops. A bucket is a use-item, and use-items have a sane rate. */
    private final Setting<Integer> delay = new Setting<>("Delay", 120, 0, 2000);
    /**
     * How long a spotted source stays worth taking.
     *
     * <p>Past this it is not "the water somebody just poured" any more - it has had time to spread,
     * and chasing spread water is how this would end up draining a lake.
     */
    private final Setting<Integer> memoryMs = new Setting<>("Memory", 4000, 500, 15000);
    private final Setting<Boolean> onlySources = new Setting<>("SourcesOnly", true);
    private final Setting<Boolean> returnSlot = new Setting<>("ReturnSlot", true);
    private final Setting<InteractionUtility.Rotate> rotate = new Setting<>("Rotate", InteractionUtility.Rotate.Default);
    private final Setting<InteractionUtility.PlaceMode> placeMode = new Setting<>("PlaceMode", InteractionUtility.PlaceMode.Packet);

    /** Positions that turned into water, and when. Ordered, so the oldest is dealt with first. */
    private final Map<BlockPos, Long> spotted = new LinkedHashMap<>();
    private final Timer scoopTimer = new Timer();

    @Override
    public void onEnable() {
        spotted.clear();
    }

    @Override
    public void onDisable() {
        spotted.clear();
    }

    @Override
    public void onLogout() {
        spotted.clear();
    }

    @Override
    public String getDisplayInfo() {
        return String.valueOf(spotted.size());
    }

    /* ------------------------------------------------------------------ */
    /* Spotting                                                            */
    /* ------------------------------------------------------------------ */

    /**
     * Both shapes of block update carry the water: a single one for a bucket poured by hand, and the
     * chunk delta for everything the server batches together.
     */
    @EventHandler
    public void onPacketReceive(PacketEvent.Receive event) {
        if (isOff() || fullNullCheck()) return;

        if (event.getPacket() instanceof BlockUpdateS2CPacket packet) {
            remember(packet.getPos(), packet.getState());
            return;
        }

        if (event.getPacket() instanceof ChunkDeltaUpdateS2CPacket packet)
            packet.visitUpdates(this::remember);
    }

    private void remember(@NotNull BlockPos pos, @NotNull BlockState state) {
        if (!isWater(state)) return;
        if (mc.player.getEyePos().squaredDistanceTo(pos.toCenterPos()) > range.getPow2Value()) return;

        // toImmutable: the delta visitor hands out a mutable position it reuses for every update, and
        // keeping that one would leave the whole map pointing at whatever it last looked at.
        spotted.put(pos.toImmutable(), System.currentTimeMillis());
    }

    private boolean isWater(@NotNull BlockState state) {
        FluidState fluid = state.getFluidState();
        if (fluid.getFluid() != Fluids.WATER && fluid.getFluid() != Fluids.FLOWING_WATER) return false;
        return !onlySources.getValue() || fluid.isStill();
    }

    /* ------------------------------------------------------------------ */
    /* Scooping                                                            */
    /* ------------------------------------------------------------------ */

    @Override
    public void onUpdate() {
        if (fullNullCheck()) return;

        long now = System.currentTimeMillis();
        spotted.values().removeIf(at -> now - at > memoryMs.getValue());
        if (spotted.isEmpty() || !scoopTimer.passedMs(delay.getValue())) return;

        SearchInvResult bucket = InventoryUtility.findItemInHotBar(Items.BUCKET);
        if (!bucket.found()) return;

        BlockPos target = next();
        if (target == null) return;

        spotted.remove(target);
        scoopTimer.reset();
        scoop(target, bucket);
    }

    /** The oldest position that is still water and still in reach; everything else is dropped. */
    private BlockPos next() {
        var iterator = spotted.keySet().iterator();
        while (iterator.hasNext()) {
            BlockPos pos = iterator.next();

            if (mc.player.getEyePos().squaredDistanceTo(pos.toCenterPos()) > range.getPow2Value()) {
                iterator.remove();
                continue;
            }

            BlockState state = mc.world.getBlockState(pos);
            // Somebody else may have taken it, or it may have been built over.
            if (!isWater(state) && !state.isOf(Blocks.WATER)) {
                iterator.remove();
                continue;
            }

            return pos;
        }
        return null;
    }

    /**
     * The right-click that fills the bucket.
     *
     * <p>Aimed at the water block itself rather than at a neighbour's face: a bucket is used ON the
     * fluid, which is the opposite of how a block is placed INTO a position.
     */
    private void scoop(@NotNull BlockPos pos, @NotNull SearchInvResult bucket) {
        int previous = mc.player.getInventory().selectedSlot;
        bucket.switchTo();

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
}
