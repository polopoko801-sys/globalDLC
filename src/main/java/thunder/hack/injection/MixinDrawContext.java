package thunder.hack.injection;

import net.minecraft.client.gui.DrawContext;
import org.spongepowered.asm.mixin.Mixin;

// 1.21.11: DrawContext.matrices is now org.joml.Matrix3x2fStack (was MatrixStack), so the old
// @Shadow MatrixStack matrices no longer binds. Its only user (drawTextHook) was already disabled
// during the render port, so the mixin is now an empty shell.
@Mixin(DrawContext.class)
public class MixinDrawContext {
}
