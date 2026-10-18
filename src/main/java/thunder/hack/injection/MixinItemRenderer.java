package thunder.hack.injection;

import net.minecraft.item.ItemDisplayContext;
import net.minecraft.client.render.item.ItemRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import thunder.hack.core.manager.client.ModuleManager;
import thunder.hack.features.modules.render.ItemShader;

/**
 * ItemShader's colour step, at the one place item quads become vertices.
 *
 * <p>During a silhouette pass ({@link ItemShader#isSolidPass()}) every quad tint is replaced with the flat glow
 * colour, so the offset copies drawn by {@code MixinHeldItemRenderer} come out as a solid glowing shape - the
 * outline. On the normal pass the item's own tints are nudged towards the glow (the inner fill). Both passes
 * are lit full-bright so the item glows instead of being shaded by the world.
 */
@Mixin(ItemRenderer.class)
public abstract class MixinItemRenderer {

    @Inject(method = "renderItem", at = @At("HEAD"))
    private static void thItemShader(ItemDisplayContext ctx, net.minecraft.client.util.math.MatrixStack matrices,
                                     net.minecraft.client.render.VertexConsumerProvider vc, int light, int overlay,
                                     int[] tints, java.util.List<net.minecraft.client.render.model.BakedQuad> quads,
                                     net.minecraft.client.render.RenderLayer layer,
                                     net.minecraft.client.render.item.ItemRenderState.Glint glint, CallbackInfo ci) {
        ItemShader shader = ModuleManager.itemShader;
        if (shader == null || tints == null) return;
        if (shader.isSolidPass()) {
            int solid = shader.solidArgb();
            for (int i = 0; i < tints.length; i++) tints[i] = solid;
        } else if (shader.appliesTo(ctx) && shader.fillGlowEnabled()) {
            for (int i = 0; i < tints.length; i++) tints[i] = shader.fill(tints[i]);
        }
    }
}
