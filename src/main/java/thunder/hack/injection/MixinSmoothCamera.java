package thunder.hack.injection;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.Camera;
import net.minecraft.entity.Entity;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;
import thunder.hack.features.modules.render.SmoothCamera;

/** BKClient's CameraMixin smooth-camera hook (MIT, bkubiakdev): the camera's position goes through SmoothCamera. */
@Mixin(Camera.class)
public class MixinSmoothCamera {
    @ModifyArgs(method = "update", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/render/Camera;setPos(DDD)V"))
    private void gombal$smoothCamera(Args args, World area, Entity focusedEntity, boolean thirdPerson, boolean inverseView, float tickDelta) {
        SmoothCamera smooth = SmoothCamera.getInstance();
        if (smooth == null || !smooth.isEnabled()) return;
        if (thunder.hack.core.manager.client.ModuleManager.freeCam != null && thunder.hack.core.manager.client.ModuleManager.freeCam.isEnabled()) return;
        double[] position = smooth.apply(args.<Double>get(0), args.<Double>get(1), args.<Double>get(2), thirdPerson,
                MinecraftClient.getInstance().world);
        args.set(0, position[0]);
        args.set(1, position[1]);
        args.set(2, position[2]);
    }
}
