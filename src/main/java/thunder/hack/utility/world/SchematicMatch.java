package thunder.hack.utility.world;

import net.minecraft.block.BlockState;
import net.minecraft.state.property.Properties;
import net.minecraft.state.property.Property;
import org.jetbrains.annotations.NotNull;

import java.util.Set;

/**
 * Decides whether the block that is actually in the world is the block the schematic asked for.
 *
 * <p>"Same block id" is not enough - a schematic that wants stairs facing north is not satisfied by
 * stairs facing south, and a door is three properties deep before it is the right door. Comparing the
 * <em>whole</em> state is not right either: a lot of properties are not something a player places, they
 * are something the world works out on its own the moment the block lands. Fences and walls decide their
 * connections from their neighbours, stairs pick their {@code shape} from theirs, redstone recomputes
 * {@code power} and {@code lit}, and a block dropped in water comes back {@code waterlogged} whether the
 * schematic said so or not. Demanding those match turns a finished build into one that can never finish.
 *
 * <p>So this compares the block plus the properties that describe how a player <em>placed</em> it, and
 * ignores the ones the world owns.
 */
public final class SchematicMatch {
    private SchematicMatch() {
    }

    /**
     * Properties the world derives by itself. A mismatch on any of these is not the printer's fault and
     * must not keep a block on the todo list forever.
     */
    private static final Set<Property<?>> WORLD_OWNED = Set.of(
            Properties.WATERLOGGED,
            Properties.POWERED,
            Properties.POWER,
            Properties.LIT,
            Properties.STAIR_SHAPE,
            Properties.RAIL_SHAPE,
            Properties.STRAIGHT_RAIL_SHAPE,
            Properties.NORTH,
            Properties.SOUTH,
            Properties.EAST,
            Properties.WEST,
            Properties.UP,
            Properties.DOWN,
            Properties.NORTH_WALL_SHAPE,
            Properties.SOUTH_WALL_SHAPE,
            Properties.EAST_WALL_SHAPE,
            Properties.WEST_WALL_SHAPE,
            Properties.DISTANCE_0_7,
            Properties.DISTANCE_1_7,
            Properties.PERSISTENT,
            Properties.SNOWY,
            Properties.AGE_1,
            Properties.AGE_2,
            Properties.AGE_3,
            Properties.AGE_5,
            Properties.AGE_7,
            Properties.AGE_15,
            Properties.AGE_25,
            Properties.MOISTURE,
            Properties.TRIGGERED,
            Properties.EXTENDED,
            Properties.SHORT,
            Properties.IN_WALL,
            Properties.OPEN,
            Properties.SIGNAL_FIRE,
            Properties.BLOOM,
            Properties.CAN_SUMMON
    );

    /**
     * True when {@code world} satisfies {@code required}: the same block, and every
     * placement-defining property the two have in common agrees.
     */
    public static boolean matches(@NotNull BlockState required, @NotNull BlockState world) {
        if (required.getBlock() != world.getBlock()) return false;

        for (Property<?> property : required.getProperties()) {
            if (WORLD_OWNED.contains(property)) continue;
            if (!world.contains(property)) continue;
            if (!required.get(property).equals(world.get(property))) return false;
        }

        return true;
    }

    /**
     * True when the world block is the right <em>block</em> but the wrong state - i.e. the build is
     * INCORRECT rather than MISSING, and the printer would have to break it to fix it.
     */
    public static boolean wrongStateOnly(@NotNull BlockState required, @NotNull BlockState world) {
        return required.getBlock() == world.getBlock() && !matches(required, world);
    }
}
