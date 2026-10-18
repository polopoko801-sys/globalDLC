package thunder.hack.injection;

import thunder.hack.core.manager.client.ModuleManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.render.command.OrderedRenderCommandQueue;
import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.client.render.entity.state.ArmorStandEntityRenderState;
import net.minecraft.client.render.entity.state.EntityRenderState;
import net.minecraft.client.render.entity.state.PlayerEntityRenderState;
import net.minecraft.client.render.state.CameraRenderState;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.Entity;

@Mixin(EntityRenderer.class)
public abstract class MixinEntityRenderer<T extends Entity> {
    // 1.21.11: renderLabelIfPresent now takes a render-state object instead of the entity/Text.
    // Identify entity type via the render-state subclass to keep the armor-stand / nametag hiding.
    @Inject(method = "renderLabelIfPresent", at = @At("HEAD"), cancellable = true)
    private void renderLabelIfPresent(EntityRenderState state, MatrixStack matrices, OrderedRenderCommandQueue queue, CameraRenderState cameraState, CallbackInfo info) {
        if(state instanceof ArmorStandEntityRenderState && ModuleManager.noRender.isEnabled() && ModuleManager.noRender.noArmorStands.getValue())
            info.cancel();

        // Only the label floating in the world. Chat, tab, the scoreboard and inventory names go through
        // entirely different code and stay exactly as they were.
        if (state instanceof PlayerEntityRenderState && ModuleManager.nameTags.isEnabled()
                && ModuleManager.nameTags.hideVanillaNames.getValue())
            info.cancel();
    }

    /**
     * Shaders ESP, 1.21.11 edition. Vanilla ends updateRenderState with
     * {@code state.outlineColor = mc.hasOutline(entity) ? fullAlpha(teamColour) : 0}, and everything
     * downstream - the entity-outline framebuffer, the post effect, drawing it through walls - keys off that
     * one field. So the ESP is just a matter of writing our own colour into it; no custom shader chain, which
     * is what the old ShaderManager needed and what 1.21.6+ removed.
     */
    @Inject(method = "updateRenderState", at = @At("RETURN"))
    private void thShadersOutline(T entity, EntityRenderState state, float tickDelta, CallbackInfo info) {
        if (!ModuleManager.shaders.isEnabled()) return;

        int color = ModuleManager.shaders.outlineColorFor(entity);
        if (color != 0) state.outlineColor = color;
    }
}