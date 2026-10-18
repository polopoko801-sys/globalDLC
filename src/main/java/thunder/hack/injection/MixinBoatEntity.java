package thunder.hack.injection;

import net.minecraft.entity.Entity;
import net.minecraft.entity.vehicle.BoatEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import thunder.hack.core.manager.client.ModuleManager;

@Mixin(BoatEntity.class)
public class MixinBoatEntity {

    @Unique
    private float lastYaw, lastHeadYaw;

    // 1.21.11: BoatEntity.updatePassengerPosition removed (boat refactored into AbstractBoatEntity;
    // passenger positioning moved). Disabled -> BoatFly passenger yaw-lock needs a new target.
    /*
    @Inject(method = "updatePassengerPosition", at = @At("HEAD"))
    protected void updatePassengerPositionHookPre(Entity passenger, Entity.PositionUpdater positionUpdater, CallbackInfo ci) {
        if(ModuleManager.boatFly.isEnabled()) {
            lastYaw = passenger.getYaw();
            lastHeadYaw = passenger.getHeadYaw();
        }
    }

    @Inject(method = "updatePassengerPosition", at = @At("RETURN"))
    protected void updatePassengerPositionHookPost(Entity passenger, Entity.PositionUpdater positionUpdater, CallbackInfo ci) {
        if(ModuleManager.boatFly.isEnabled()) {
            passenger.setYaw(lastYaw);
            passenger.setHeadYaw(lastHeadYaw);
        }
    }
    */
}
