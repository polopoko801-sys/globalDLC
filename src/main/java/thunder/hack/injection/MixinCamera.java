package thunder.hack.injection;

import net.minecraft.entity.Entity;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import thunder.hack.core.manager.client.ModuleManager;
import net.minecraft.client.render.Camera;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;

@Mixin(Camera.class)
public abstract class MixinCamera {
    @Shadow
    protected abstract float clipToSpace(float desiredCameraDistance);

    @Shadow
    private boolean thirdPerson;

    @Shadow
    private float yaw;

    @Shadow
    private float pitch;

    @Shadow
    protected abstract void setRotation(float yaw, float pitch);

    @ModifyArgs(method = "update", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/render/Camera;moveBy(FFF)V", ordinal = 0))
    private void modifyCameraDistance(Args args) {
        // PersonViewer: turn the third-person camera by Viewer Yaw / Pitch before it backs away from the
        // player, then re-clip along the new direction.
        boolean viewer = ModuleManager.personViewer != null && ModuleManager.personViewer.isEnabled() && !ModuleManager.freeCam.isEnabled();
        if (viewer)
            setRotation(yaw + ModuleManager.personViewer.viewerYaw.getValue(), pitch + ModuleManager.personViewer.viewerPitch.getValue());

        if (ModuleManager.noCameraClip.isEnabled()) {
            args.set(0, -clipToSpace(ModuleManager.noCameraClip.getDistance()));
        } else if (viewer) {
            args.set(0, -clipToSpace(ModuleManager.personViewer.fovModifier.getValue()));
        }
    }

    @Inject(method = "clipToSpace", at = @At("HEAD"), cancellable = true)
    private void onClipToSpace(float f, CallbackInfoReturnable<Float> cir) {
        if (ModuleManager.noCameraClip.isEnabled()) {
            cir.setReturnValue(ModuleManager.noCameraClip.getDistance());
        }
    }

    @Shadow
    protected abstract void setPos(double x, double y, double z);

    @Inject(method = "update", at = @At("TAIL"))
    private void updateHook(World area, Entity focusedEntity, boolean thirdPerson, boolean inverseView, float tickDelta, CallbackInfo ci) {
        if (ModuleManager.freeCam.isEnabled()) {
            this.thirdPerson = true;
            return;
        }
        // KillEffect Ninja: the camera leaves your head and orbits the cut for the length of the scene
        Camera self = (Camera) (Object) this;
        double[] pose = thunder.hack.features.modules.render.KillEffect.cinematicPose(self.getCameraPos(), this.yaw, this.pitch);
        if (pose != null) {
            setPos(pose[0], pose[1], pose[2]);
            setRotation((float) pose[3], (float) pose[4]);
            this.thirdPerson = true;
        }
    }

    @ModifyArgs(method = "update", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/render/Camera;setRotation(FF)V"))
    private void setRotationHook(Args args) {
        if(ModuleManager.freeCam.isEnabled())
            args.setAll(ModuleManager.freeCam.getFakeYaw(), ModuleManager.freeCam.getFakePitch());
    }

    @ModifyArgs(method = "update", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/render/Camera;setPos(DDD)V"))
    private void setPosHook(Args args) {
        if(ModuleManager.freeCam.isEnabled())
            args.setAll(ModuleManager.freeCam.getFakeX(), ModuleManager.freeCam.getFakeY(), ModuleManager.freeCam.getFakeZ());
    }
}