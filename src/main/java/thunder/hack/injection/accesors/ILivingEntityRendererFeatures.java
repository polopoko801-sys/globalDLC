package thunder.hack.injection.accesors;

import net.minecraft.client.render.entity.LivingEntityRenderer;
import net.minecraft.client.render.entity.feature.FeatureRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Lets the cosmetics mixin append its feature to the player renderer (BKClient's LivingEntityRendererAccessor). */
@Mixin(LivingEntityRenderer.class)
public interface ILivingEntityRendererFeatures {
    @Invoker("addFeature")
    boolean gombal$addFeature(FeatureRenderer<?, ?> feature);
}
