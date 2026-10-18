package thunder.hack.features.modules.combat;

import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Blocks;
import net.minecraft.item.Items;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import thunder.hack.events.impl.PlayerUpdateEvent;
import thunder.hack.features.modules.Module;
import thunder.hack.setting.Setting;
import thunder.hack.utility.Timer;
import thunder.hack.utility.player.InventoryUtility;
import thunder.hack.utility.player.SearchInvResult;

/**
 * Rail, then TNT minecart, as fast as the server will take them.
 *
 * <p>A TNT minecart only detonates where there is track to put it on, so the two go together: the rail
 * goes down first and the cart on top of it in the same breath. Splitting them across ticks is what
 * gives somebody time to walk off the spot.
 *
 * <p>Triggered by firing an arrow, because that is a button you already have on your hand and it costs
 * you nothing to press. The alternative - running on its own whenever a target is near - is an aura,
 * and an aura that spends TNT is not something to leave switched on.
 *
 * <p>It places at the TARGET's feet, not yours. Placing under yourself is a way to kill yourself.
 */
public final class AutoCart extends Module {
    public AutoCart() {
        super("AutoCart", Category.COMBAT);
    }

    private final Setting<Float> range = new Setting<>("Range", 4.5f, 1f, 6f);
    /** How long after the shot the module keeps placing, in milliseconds. */
    private final Setting<Integer> window = new Setting<>("Window", 600, 100, 3000);
    /** Milliseconds between one rail-and-cart pair and the next. 0 is as fast as the ticks allow. */
    private final Setting<Integer> delay = new Setting<>("Delay", 0, 0, 500);
    /** How many pairs one shot is worth. */
    private final Setting<Integer> amount = new Setting<>("Amount", 3, 1, 16);
    /** Place under the nearest player rather than under yourself. */
    private final Setting<Boolean> atTarget = new Setting<>("AtTarget", true);
    private final Setting<Boolean> swing = new Setting<>("Swing", true);

    private final Timer timer = new Timer();
    /** When the current shot's window closes, and how many pairs it has left. */
    private long until;
    private int left;

    @Override
    public void onEnable() {
        until = 0;
        left = 0;
    }

    /**
     * Arming the module. Any arrow you fire opens the window.
     *
     * <p>Watched on the tick rather than hooked into the bow, so a crossbow, a bow and a loaded
     * crossbow all count without three separate cases.
     */
    private boolean wasUsing;

    @EventHandler
    public void onUpdate(PlayerUpdateEvent event) {
        if (fullNullCheck()) return;

        boolean using = mc.player.isUsingItem();
        boolean released = wasUsing && !using;
        wasUsing = using;

        if (released && isBow(mc.player.getMainHandStack().getItem())) {
            until = System.currentTimeMillis() + window.getValue();
            left = amount.getValue();
        }

        if (left <= 0 || System.currentTimeMillis() > until) return;
        if (!timer.passedMs(delay.getValue())) return;

        if (place()) {
            left--;
            timer.reset();
        }
    }

    private static boolean isBow(net.minecraft.item.Item item) {
        return item == Items.BOW || item == Items.CROSSBOW;
    }

    /**
     * One rail and one cart, in that order, in the same tick.
     *
     * <p>Returns false without spending anything when either half is missing - half of this is no use,
     * and a rail left on the floor is a rail somebody else can stand on.
     */
    private boolean place() {
        BlockPos pos = target();
        if (pos == null) return false;

        SearchInvResult rail = InventoryUtility.findItemInHotBar(Items.RAIL, Items.POWERED_RAIL,
                Items.DETECTOR_RAIL, Items.ACTIVATOR_RAIL);
        SearchInvResult cart = InventoryUtility.findItemInHotBar(Items.TNT_MINECART);
        if (!rail.found() || !cart.found()) return false;

        int previous = mc.player.getInventory().selectedSlot;

        // The rail needs a face to be placed against, so it is clicked onto the top of the block below.
        BlockPos support = pos.down();
        if (mc.world.getBlockState(support).isAir()) return false;

        if (!mc.world.getBlockState(pos).isReplaceable()) return false;

        rail.switchTo();
        mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND,
                new BlockHitResult(Vec3d.ofCenter(support).add(0, 0.5, 0), Direction.UP, support, false));
        if (swing.getValue()) mc.player.swingHand(Hand.MAIN_HAND);

        // The cart goes onto the rail that now exists, clicked on the rail's own block.
        cart.switchTo();
        mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND,
                new BlockHitResult(Vec3d.ofCenter(pos), Direction.UP, pos, false));
        if (swing.getValue()) mc.player.swingHand(Hand.MAIN_HAND);

        InventoryUtility.switchTo(previous);
        return true;
    }

    /** The block the pair goes in: the nearest player's feet, or your own when there is nobody. */
    private BlockPos target() {
        if (!atTarget.getValue()) return mc.player.getBlockPos();

        net.minecraft.entity.player.PlayerEntity best = null;
        double bestDistance = Double.MAX_VALUE;

        for (net.minecraft.entity.player.PlayerEntity player : mc.world.getPlayers()) {
            if (player == mc.player || !player.isAlive()) continue;
            if (thunder.hack.core.Managers.FRIEND.isFriend(player)) continue;

            double distance = mc.player.squaredDistanceTo(player);
            if (distance > range.getPow2Value() || distance >= bestDistance) continue;

            bestDistance = distance;
            best = player;
        }

        return best == null ? null : best.getBlockPos();
    }

    @Override
    public String getDisplayInfo() {
        return left > 0 ? String.valueOf(left) : null;
    }
}
