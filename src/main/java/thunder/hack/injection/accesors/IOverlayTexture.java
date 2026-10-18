package thunder.hack.injection.accesors;

import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.texture.NativeImageBackedTexture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(OverlayTexture.class)
public interface IOverlayTexture {
    // The 16x16 hurt/white-flash overlay; rows 0-7 are the red hurt tint HitColor recolours.
    @Accessor("texture")
    NativeImageBackedTexture getTexture();
}
