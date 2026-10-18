package thunder.hack.injection.accesors;

import net.minecraft.registry.entry.RegistryEntry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

// 1.21.11: SimpleRegistry.add() no longer binds a Reference's value immediately; binding is deferred
// to freeze() (valueToEntry.forEach(setValue)). Since we register sounds after bootstrap and can't
// call the full freeze() again (tag re-validation throws), we bind each new Reference's value directly.
@Mixin(RegistryEntry.Reference.class)
public interface IRegistryEntryReference<T> {
    @Accessor("value")
    void thSetValue(T value);
}
