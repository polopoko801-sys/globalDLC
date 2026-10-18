package thunder.hack.injection;

import net.minecraft.client.render.entity.state.ItemEntityRenderState;
import org.spongepowered.asm.mixin.Mixin;
import thunder.hack.utility.interfaces.IItemPhysicsState;
import org.spongepowered.asm.mixin.Unique;

@Mixin(ItemEntityRenderState.class)
public class MixinItemEntityRenderState implements IItemPhysicsState {
    @Unique
    private boolean gombal$onGround;

    @Override
    public boolean gombal$onGround() {
        return gombal$onGround;
    }

    @Override
    public void gombal$setOnGround(boolean onGround) {
        gombal$onGround = onGround;
    }
}
