package thunder.hack.injection;

import net.minecraft.client.render.WeatherRendering;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkSectionPos;
import net.minecraft.world.World;
import net.minecraft.world.biome.Biome;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;
import thunder.hack.core.manager.client.ModuleManager;

@Mixin(WeatherRendering.class)
public class MixinWeatherRendering {
    /** Weather module: rain or snow everywhere, whatever the biome (client rendering only). */
    @Inject(method = "getPrecipitationAt", at = @At("HEAD"), cancellable = true)
    private void gombal$forcePrecipitation(World world, BlockPos pos, CallbackInfoReturnable<Biome.Precipitation> cir) {
        if (ModuleManager.weather == null || !ModuleManager.weather.isEnabled()) return;
        if (!world.getChunkManager().isChunkLoaded(ChunkSectionPos.getSectionCoord(pos.getX()), ChunkSectionPos.getSectionCoord(pos.getZ())))
            return;
        cir.setReturnValue(ModuleManager.weather.precipitation());
    }

    /** Weather module: the game's own rain / snow curtains are not drawn - Weather draws its own drops. */
    @Inject(method = "renderPrecipitation", at = @At("HEAD"), cancellable = true)
    private void gombal$hideVanillaPrecipitation(net.minecraft.client.render.VertexConsumerProvider vertexConsumers, net.minecraft.util.math.Vec3d pos,
                                                 net.minecraft.client.render.state.WeatherRenderState state, org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
        if (ModuleManager.weather != null && ModuleManager.weather.isEnabled() && ModuleManager.weather.customDrops()) ci.cancel();
    }

    /** ...and neither are its pixel splash particles (the rain sound stays). */
    @org.spongepowered.asm.mixin.injection.Redirect(method = "addParticlesAndSound", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/world/ClientWorld;addParticleClient(Lnet/minecraft/particle/ParticleEffect;DDDDDD)V"))
    private void gombal$hideVanillaSplash(net.minecraft.client.world.ClientWorld world, net.minecraft.particle.ParticleEffect effect,
                                         double x, double y, double z, double vx, double vy, double vz) {
        if (ModuleManager.weather != null && ModuleManager.weather.isEnabled() && ModuleManager.weather.customDrops()) return;
        world.addParticleClient(effect, x, y, z, vx, vy, vz);
    }

    private static final net.minecraft.util.Identifier RAIN_HD = net.minecraft.util.Identifier.of("gombal", "textures/environment/rain_hd.png");
    private static final net.minecraft.util.Identifier SNOW_HD = net.minecraft.util.Identifier.of("gombal", "textures/environment/snow_hd.png");

    /** Weather module: thin soft rain streaks / round snowflakes instead of the game's pixel ones. */
    @org.spongepowered.asm.mixin.injection.ModifyArg(method = "renderPrecipitation", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/render/RenderLayers;weather(Lnet/minecraft/util/Identifier;Z)Lnet/minecraft/client/render/RenderLayer;", ordinal = 0), index = 0)
    private net.minecraft.util.Identifier gombal$rainTexture(net.minecraft.util.Identifier id) {
        return ModuleManager.weather != null && ModuleManager.weather.isEnabled() && ModuleManager.weather.hdTextures() ? RAIN_HD : id;
    }

    @org.spongepowered.asm.mixin.injection.ModifyArg(method = "renderPrecipitation", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/render/RenderLayers;weather(Lnet/minecraft/util/Identifier;Z)Lnet/minecraft/client/render/RenderLayer;", ordinal = 1), index = 0)
    private net.minecraft.util.Identifier gombal$snowTexture(net.minecraft.util.Identifier id) {
        return ModuleManager.weather != null && ModuleManager.weather.isEnabled() && ModuleManager.weather.hdTextures() ? SNOW_HD : id;
    }

    /**
     * Wind: every vertex of the rain / snow columns is pushed sideways in proportion to its height above the
     * camera, which leans the whole curtain into the wind.
     */
    @ModifyArgs(method = "renderPieces", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/render/VertexConsumer;vertex(FFF)Lnet/minecraft/client/render/VertexConsumer;"))
    private void gombal$wind(Args args) {
        if (ModuleManager.weather == null || !ModuleManager.weather.isEnabled()) return;
        float[] lean = ModuleManager.weather.lean();
        if (lean == null) return;
        float y = args.get(1);
        args.set(0, (float) args.get(0) + lean[0] * y);
        args.set(2, (float) args.get(2) + lean[1] * y);
    }
}
