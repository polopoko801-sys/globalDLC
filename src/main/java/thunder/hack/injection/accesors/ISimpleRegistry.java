package thunder.hack.injection.accesors;

import net.minecraft.registry.SimpleRegistry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

// 1.21.11: built-in registries (incl. SOUND_EVENT) are frozen before ThunderHack's 'main'
// entrypoint runs, so Registry.register(...) throws "Registry is already frozen". ThunderHack
// bundles no Fabric API, so we briefly flip the frozen flag around our late sound registration.
@Mixin(SimpleRegistry.class)
public interface ISimpleRegistry {
    @Accessor("frozen")
    boolean isFrozen();

    @Accessor("frozen")
    void setFrozen(boolean frozen);
}
