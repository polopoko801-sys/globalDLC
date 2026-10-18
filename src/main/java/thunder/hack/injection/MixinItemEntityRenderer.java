package thunder.hack.injection;

import net.minecraft.client.render.command.OrderedRenderCommandQueue;
import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.render.entity.ItemEntityRenderer;
import net.minecraft.client.render.entity.state.ItemEntityRenderState;
import net.minecraft.client.render.state.CameraRenderState;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.ItemEntity;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.RotationAxis;
import net.minecraft.util.math.random.Random;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import thunder.hack.utility.interfaces.IItemPhysicsState;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import thunder.hack.core.manager.client.ModuleManager;

@Mixin(ItemEntityRenderer.class)
public abstract class MixinItemEntityRenderer extends EntityRenderer<ItemEntity, ItemEntityRenderState> {
    @Shadow
    @Final
    private Random random;

    protected MixinItemEntityRenderer(EntityRendererFactory.Context ctx) {
        super(ctx);
    }

    @Inject(method = "updateRenderState(Lnet/minecraft/entity/ItemEntity;Lnet/minecraft/client/render/entity/state/ItemEntityRenderState;F)V", at = @At("TAIL"))
    private void gombal$physicsState(ItemEntity entity, ItemEntityRenderState state, float tickDelta, CallbackInfo ci) {
        ((IItemPhysicsState) state).gombal$setOnGround(entity.isOnGround());
    }

    /**
     * ItemPhysics: items stop bobbing and spinning; flat items lie on the ground, and anything in the air
     * tumbles at "Physics Speed". Same draw call as vanilla, only the transform differs.
     */
    @Inject(method = "render(Lnet/minecraft/client/render/entity/state/ItemEntityRenderState;Lnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/command/OrderedRenderCommandQueue;Lnet/minecraft/client/render/state/CameraRenderState;)V", at = @At("HEAD"), cancellable = true)
    private void gombal$itemPhysics(ItemEntityRenderState state, MatrixStack matrices, OrderedRenderCommandQueue queue, CameraRenderState camera, CallbackInfo ci) {
        if (ModuleManager.itemPhysics == null || !ModuleManager.itemPhysics.isEnabled() || state.itemRenderState.isEmpty()) return;
        ci.cancel();

        Box box = state.itemRenderState.getModelBoundingBox();
        boolean flat = box.getLengthZ() <= 0.0625F;
        boolean onGround = ((IItemPhysicsState) state).gombal$onGround();
        float centerY = (float) (box.minY + box.maxY) / 2f;
        float spin = state.age * ModuleManager.itemPhysics.physicsSpeed.getValue() * 20f;

        matrices.push();
        if (onGround && flat) {
            // Lying flat: +90 deg about X turns the sprite's depth into height, so lift it by its max Z.
            matrices.translate(0.0F, (float) box.maxZ + 0.01F, 0.0F);
            matrices.multiply(RotationAxis.POSITIVE_Y.rotation(state.uniqueOffset));
            matrices.multiply(RotationAxis.POSITIVE_X.rotationDegrees(90f));
            matrices.translate(0.0F, -centerY, 0.0F);
        } else if (onGround) {
            // Block models stand on their bottom face, no bob, no spin.
            matrices.translate(0.0F, (float) -box.minY, 0.0F);
            matrices.multiply(RotationAxis.POSITIVE_Y.rotation(state.uniqueOffset));
        } else {
            matrices.translate(0.0F, (float) (box.maxY - box.minY) / 2f + 0.0625F, 0.0F);
            matrices.multiply(RotationAxis.POSITIVE_Y.rotation(state.uniqueOffset));
            matrices.multiply(RotationAxis.POSITIVE_X.rotationDegrees(spin));
            matrices.translate(0.0F, -centerY, 0.0F);
        }
        ItemEntityRenderer.render(matrices, queue, state.light, state, random, box);
        matrices.pop();
        super.render(state, matrices, queue, camera);
    }
}
