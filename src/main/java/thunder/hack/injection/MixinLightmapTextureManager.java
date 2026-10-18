package thunder.hack.injection;

import net.minecraft.entity.LivingEntity;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.dimension.DimensionType;
import thunder.hack.core.manager.client.ModuleManager;
import thunder.hack.features.modules.render.Fullbright;
import net.minecraft.client.render.LightmapTextureManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.joml.Vector3f;
import org.joml.Vector3fc;
import java.awt.Color;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LightmapTextureManager.class)
public class MixinLightmapTextureManager {

    // 1.21.11: getDarknessFactor(F)F -> getDarkness(LivingEntity, float, float)F
    @Inject(method = "getDarkness", at = @At("HEAD"), cancellable = true)
    private void getDarknessFactor(LivingEntity entity, float darknessScale, float tickDelta, CallbackInfoReturnable<Float> info) {
        if (ModuleManager.noRender.isEnabled() && ModuleManager.noRender.darkness.getValue()) info.setReturnValue(0.0f);
    }
    @Inject(method = "getBrightness", at = @At("HEAD"), cancellable = true)
    private static void getBrightnessHook(DimensionType type, int lightLevel, CallbackInfoReturnable<Float> cir) {
        if (ModuleManager.fullbright.isEnabled()) {
            float f = (float)lightLevel / 15.0F;
            float g = f / (4.0F - 3.0F * f);
            cir.setReturnValue(Math.max(MathHelper.lerp(type.ambientLight(), g, 1.0F), Fullbright.minBright.getValue()));
        }
    }

    // WorldColor: multiply the sky-light colour (putVec3 #0) and the ambient colour (#1) by the chosen tint.
    @ModifyArg(method = "update", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/buffers/Std140Builder;putVec3(Lorg/joml/Vector3fc;)Lcom/mojang/blaze3d/buffers/Std140Builder;", ordinal = 0))
    private Vector3fc gombal$skyLightColor(Vector3fc v) {
        return gombal$tint(v);
    }

    @ModifyArg(method = "update", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/buffers/Std140Builder;putVec3(Lorg/joml/Vector3fc;)Lcom/mojang/blaze3d/buffers/Std140Builder;", ordinal = 1))
    private Vector3fc gombal$ambientColor(Vector3fc v) {
        return gombal$tint(v);
    }

    private static Vector3fc gombal$tint(Vector3fc v) {
        if (ModuleManager.worldColor == null || !ModuleManager.worldColor.isEnabled()) return v;
        Color c = ModuleManager.worldColor.getColor();
        return new Vector3f(v.x() * c.getRed() / 255f, v.y() * c.getGreen() / 255f, v.z() * c.getBlue() / 255f);
    }

    // NightMode: LightmapInfo floats go AmbientLight, Sky, Block, NightVision, Darkness, DarkenWorld, Brightness.
    @ModifyArg(method = "update", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/buffers/Std140Builder;putFloat(F)Lcom/mojang/blaze3d/buffers/Std140Builder;", ordinal = 1))
    private float gombal$skyFactor(float f) {
        return gombal$nightMode() ? f * (1f - ModuleManager.nightMode.darkModifier.getValue()) : f;
    }

    @ModifyArg(method = "update", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/buffers/Std140Builder;putFloat(F)Lcom/mojang/blaze3d/buffers/Std140Builder;", ordinal = 2))
    private float gombal$blockFactor(float f) {
        return gombal$nightMode() ? f * (1f - ModuleManager.nightMode.darkModifier.getValue()) : f;
    }

    @ModifyArg(method = "update", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/buffers/Std140Builder;putFloat(F)Lcom/mojang/blaze3d/buffers/Std140Builder;", ordinal = 6))
    private float gombal$brightness(float f) {
        return gombal$nightMode() ? 0f : f;
    }

    private static boolean gombal$nightMode() {
        return ModuleManager.nightMode != null && ModuleManager.nightMode.isEnabled();
    }
}