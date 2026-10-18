package thunder.hack.injection;

import net.minecraft.client.gui.screen.ChatScreen;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.util.math.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import thunder.hack.core.manager.client.ModuleManager;
import static thunder.hack.ThunderHack.mc;


@Mixin(KeyBinding.class)
public abstract class MixinKeyBinding {
    @Shadow public abstract boolean equals(KeyBinding other);

    @Shadow public abstract boolean isPressed();

    @Shadow private InputUtil.Key boundKey;

    @Inject(method = "isPressed",at = @At("HEAD"),cancellable = true)
    private void pressHook(CallbackInfoReturnable<Boolean> cir){
        // InventoryMove: keep movement keys live while a (non-chat) GUI is open
        if (ModuleManager.inventoryMove != null && ModuleManager.inventoryMove.isEnabled()
                && mc.player != null && mc.currentScreen != null && !(mc.currentScreen instanceof ChatScreen)
                && (this.equals(mc.options.forwardKey) || this.equals(mc.options.backKey)
                    || this.equals(mc.options.leftKey) || this.equals(mc.options.rightKey)
                    || this.equals(mc.options.jumpKey)
                    || (this.equals(mc.options.sneakKey) && ModuleManager.inventoryMove.sneak.getValue()))) {
            cir.setReturnValue(InputUtil.isKeyPressed(mc.getWindow(), boundKey.getCode()));
            return;
        }
        if(     this.equals(mc.options.sneakKey)
                && mc.player != null
                && mc.world != null
                && ModuleManager.safeWalk.isEnabled()
                && mc.player.isOnGround() && mc.world.getBlockState(new BlockPos((int) Math.floor(mc.player.getEntityPos().getX()), (int) Math.floor(mc.player.getEntityPos().getY()) - 1, (int) Math.floor(mc.player.getEntityPos().getZ()))).isAir()
                && !ModuleManager.scaffold.isEnabled()){
            cir.setReturnValue(true);
        }
    }
}
