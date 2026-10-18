package thunder.hack.features.modules.render;

/**
 * A white wave that runs out across the blocks when you jump.
 *
 * <p>It is {@link HitBlock} with the trigger set to Jump: the same breadth-first walk over the real
 * exposed surfaces of the world, measured from your feet instead of from something you hit. Everything
 * that makes that wave work - running along the tops of blocks, climbing a step, bending round a wall,
 * doing the walk once and only arithmetic per frame afterwards - is shared rather than written twice.
 *
 * <p>It exists as its own entry in the list because that is where it gets looked for. Buried as one
 * value of a Trigger setting on a module called HitBlock, nobody finds it.
 *
 * <p>Purely a renderer. It places nothing, breaks nothing and sends nothing.
 */
public class BlockJump extends HitBlock {
    public BlockJump() {
        super("BlockJump", Trigger.Jump);
    }
}
