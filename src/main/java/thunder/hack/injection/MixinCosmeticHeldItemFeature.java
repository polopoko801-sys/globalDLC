package thunder.hack.injection;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.command.OrderedRenderCommandQueue;
import net.minecraft.client.render.entity.feature.FeatureRenderer;
import net.minecraft.client.render.entity.feature.HeldItemFeatureRenderer;
import net.minecraft.client.render.entity.model.ModelWithArms;
import net.minecraft.client.render.entity.state.ArmedEntityRenderState;
import net.minecraft.client.render.entity.state.PlayerEntityRenderState;
import net.minecraft.client.render.item.ItemRenderState;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.item.ItemDisplayContext;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Arm;
import net.minecraft.util.math.RotationAxis;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import thunder.hack.features.cosmetics.render.MeleeCosmeticRenderer;

/** Third-person melee cosmetic in the local player's hand (ported from BKClient, MIT). */
@Mixin(HeldItemFeatureRenderer.class)
public abstract class MixinCosmeticHeldItemFeature {
    @SuppressWarnings({"unchecked", "rawtypes"})
    @Inject(method = "renderItem", at = @At("HEAD"), cancellable = true)
    private void gombal$heldCosmetic(ArmedEntityRenderState state, ItemRenderState item, ItemStack stack, Arm arm,
                                     MatrixStack matrices, OrderedRenderCommandQueue queue, int light, CallbackInfo ci) {
        var client = MinecraftClient.getInstance();
        if (client.player == null || !(state instanceof PlayerEntityRenderState playerState)
                || playerState.id != client.player.getId()) return;
        var held = arm == client.player.getMainArm() ? client.player.getMainHandStack() : client.player.getOffHandStack();
        if (!MeleeCosmeticRenderer.eligible(held)) return;
        matrices.push();
        try {
            ((ModelWithArms) ((FeatureRenderer<?, ?>) (Object) this).getContextModel()).setArmAngle(state, arm, matrices);
            matrices.multiply(RotationAxis.POSITIVE_X.rotationDegrees(-90));
            matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(180));
            matrices.translate(arm == Arm.LEFT ? -1f / 16 : 1f / 16, .125f, -.625f);
            if (MeleeCosmeticRenderer.thirdPerson(client.player, held,
                    arm == Arm.LEFT ? ItemDisplayContext.THIRD_PERSON_LEFT_HAND : ItemDisplayContext.THIRD_PERSON_RIGHT_HAND,
                    matrices, queue, light)) ci.cancel();
        } finally { matrices.pop(); }
    }
}
