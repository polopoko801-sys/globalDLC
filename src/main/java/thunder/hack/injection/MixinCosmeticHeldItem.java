package thunder.hack.injection;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.render.command.OrderedRenderCommandQueue;
import net.minecraft.client.render.item.HeldItemRenderer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Hand;
import org.spongepowered.asm.mixin.Mixin;
import thunder.hack.features.cosmetics.render.MeleeCosmeticRenderer;

/**
 * First-person melee cosmetics (ported from BKClient, MIT). The wrappers sit outside every other
 * renderFirstPersonItem injection (Animations, ViewModel), so when a weapon skin takes over the hand none of
 * them run, and when it does not they run exactly as before.
 */
@Mixin(value = HeldItemRenderer.class, priority = 1200)
public abstract class MixinCosmeticHeldItem {
    @WrapMethod(method = "renderItem(FLnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/command/OrderedRenderCommandQueue;Lnet/minecraft/client/network/ClientPlayerEntity;I)V")
    private void gombal$cosmeticFrame(float delta, MatrixStack matrices, OrderedRenderCommandQueue queue,
                                      ClientPlayerEntity player, int light, Operation<Void> original) {
        MeleeCosmeticRenderer.beginVisualFrame();
        MeleeCosmeticRenderer.beginFrame();
        try { original.call(delta, matrices, queue, player, light); }
        finally { MeleeCosmeticRenderer.endFrame(player, queue, light); }
    }

    @WrapMethod(method = "renderFirstPersonItem")
    private void gombal$firstPersonCosmetic(AbstractClientPlayerEntity player, float tickDelta, float pitch, Hand hand,
                                            float swing, ItemStack stack, float equip, MatrixStack matrices,
                                            OrderedRenderCommandQueue queue, int light, Operation<Void> original) {
        if (!MeleeCosmeticRenderer.firstPerson(player, hand, stack, swing, equip, matrices, queue, light))
            original.call(player, tickDelta, pitch, hand, swing, stack, equip, matrices, queue, light);
    }
}
