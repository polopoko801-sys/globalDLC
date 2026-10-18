package thunder.hack.injection;

import net.minecraft.client.render.Camera;
import net.minecraft.client.render.fog.FogRenderer;
import net.minecraft.client.world.ClientWorld;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;
import thunder.hack.core.manager.client.ModuleManager;

import java.awt.Color;

@Mixin(FogRenderer.class)
public class MixinFogRenderer {
    // FogColor: the returned colour feeds both the fog uniform and the sky clear colour.
    @Inject(method = "getFogColor", at = @At("RETURN"), cancellable = true)
    private void gombal$fogColor(Camera camera, float tickProgress, ClientWorld world, int viewDistance, float skyDarkness, CallbackInfoReturnable<Vector4f> cir) {
        if (ModuleManager.fogColor == null || !ModuleManager.fogColor.isEnabled()) return;
        Color c = ModuleManager.fogColor.getColor();
        cir.setReturnValue(new Vector4f(c.getRed() / 255f, c.getGreen() / 255f, c.getBlue() / 255f, cir.getReturnValue().w));
    }

    // FogColor "Fog Distance": args are (buffer, offset, colour, envStart, envEnd, renderStart, renderEnd, skyEnd, cloudEnd).
    @ModifyArgs(method = "applyFog(Lnet/minecraft/client/render/Camera;ILnet/minecraft/client/render/RenderTickCounter;FLnet/minecraft/client/world/ClientWorld;)Lorg/joml/Vector4f;",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/render/fog/FogRenderer;applyFog(Ljava/nio/ByteBuffer;ILorg/joml/Vector4f;FFFFFF)V"))
    private void gombal$fogDistance(Args args) {
        if (ModuleManager.fogColor == null || !ModuleManager.fogColor.isEnabled()) return;
        float d = ModuleManager.fogColor.distance.getValue();
        for (int i = 3; i <= 6; i++) args.set(i, (float) args.get(i) * d);
    }
}
