package thunder.hack.injection;

import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.model.Model;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.command.ModelCommandRenderer;
import net.minecraft.client.render.command.OrderedRenderCommandQueue;
import net.minecraft.client.render.entity.LivingEntityRenderer;
import net.minecraft.client.render.entity.feature.FeatureRenderer;
import net.minecraft.client.render.entity.state.EntityRenderState;
import net.minecraft.client.render.entity.state.LivingEntityRenderState;
import net.minecraft.client.render.entity.state.PlayerEntityRenderState;
import net.minecraft.client.render.state.CameraRenderState;
import net.minecraft.client.texture.Sprite;
import net.minecraft.client.util.math.MatrixStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import thunder.hack.features.cosmetics.render.CharacterCosmeticRenderer;

/**
 * Character cosmetics replace the local player's body model. Ported from BKClient (MIT): on 1.21.4 BK wrapped
 * {@code EntityModel.render}; on 1.21.11 the body is a {@code submitModel} command, so that call is wrapped.
 */
@Mixin(LivingEntityRenderer.class)
public abstract class MixinCharacterCosmetic {
    @WrapOperation(method = "render(Lnet/minecraft/client/render/entity/state/LivingEntityRenderState;Lnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/command/OrderedRenderCommandQueue;Lnet/minecraft/client/render/state/CameraRenderState;)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/render/command/OrderedRenderCommandQueue;submitModel(Lnet/minecraft/client/model/Model;Ljava/lang/Object;Lnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/RenderLayer;IIILnet/minecraft/client/texture/Sprite;ILnet/minecraft/client/render/command/ModelCommandRenderer$CrumblingOverlayCommand;)V"))
    private void gombal$character(OrderedRenderCommandQueue queue, Model<?> model, Object modelState, MatrixStack matrices,
                                  RenderLayer layer, int light, int overlay, int color, Sprite sprite, int outline,
                                  ModelCommandRenderer.CrumblingOverlayCommand crumbling, Operation<Void> original,
                                  LivingEntityRenderState state, MatrixStack enclosing, OrderedRenderCommandQueue enclosingQueue,
                                  CameraRenderState camera) {
        var entry = CharacterCosmeticRenderer.selected(state);
        if (entry == null) original.call(queue, model, modelState, matrices, layer, light, overlay, color, sprite, outline, crumbling);
        else CharacterCosmeticRenderer.render(entry, (PlayerEntityRenderState) state, matrices, queue, light, overlay, color);
    }

    @WrapWithCondition(method = "render(Lnet/minecraft/client/render/entity/state/LivingEntityRenderState;Lnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/command/OrderedRenderCommandQueue;Lnet/minecraft/client/render/state/CameraRenderState;)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/render/entity/feature/FeatureRenderer;render(Lnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/command/OrderedRenderCommandQueue;ILnet/minecraft/client/render/entity/state/EntityRenderState;FF)V"))
    private boolean gombal$characterFeatures(FeatureRenderer<?, ?> feature, MatrixStack matrices,
                                             OrderedRenderCommandQueue queue, int light, EntityRenderState state, float yaw, float pitch) {
        // Human armor, capes, hands and attachments do not share these characters' skeletons.
        // Selections stay saved and return as soon as the player chooses their regular skin.
        return CharacterCosmeticRenderer.selected(state) == null;
    }
}
