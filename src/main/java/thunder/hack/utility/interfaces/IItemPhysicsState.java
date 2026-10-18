package thunder.hack.utility.interfaces;

/** Extra data ItemPhysics needs on {@code ItemEntityRenderState} (filled by MixinItemEntityRenderer). */
public interface IItemPhysicsState {
    boolean gombal$onGround();

    void gombal$setOnGround(boolean onGround);
}
