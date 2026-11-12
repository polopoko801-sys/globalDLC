package thunder.hack.injection.accesors;

import net.minecraft.client.gui.widget.TextFieldWidget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The vanilla chat field's selection, drawn by the custom chat. */
@Mixin(TextFieldWidget.class)
public interface ITextFieldSelection {
    @Accessor("selectionStart")
    int gombal$selectionStart();

    @Accessor("selectionEnd")
    int gombal$selectionEnd();
}
