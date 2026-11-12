package thunder.hack.injection;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.gui.render.GuiRenderer;
import net.minecraft.client.render.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import thunder.hack.utility.render.ScreenCapture;

/**
 * The GUI renderer's blur point, the one place mid-GUI where everything recorded before it is already on the
 * frame. A screen that asked for {@link ScreenCapture#deferToBlur()} gets its backdrop copied into the glass
 * snapshot here instead of vanilla's menu blur.
 */
@Mixin(GuiRenderer.class)
public class MixinGuiRendererBlur {
    @WrapOperation(method = "renderPreparedDraws", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/render/GameRenderer;renderBlur()V"))
    private void gombal$glassSnapshot(GameRenderer renderer, Operation<Void> original) {
        if (ScreenCapture.runDeferred()) return;
        original.call(renderer);
    }
}
