package thunder.hack.features.cmd.impl;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.command.CommandSource;
import thunder.hack.features.cmd.Command;
import thunder.hack.gui.anim.AnimationScreen;

import static com.mojang.brigadier.Command.SINGLE_SUCCESS;

/** /anim - opens the live animation editor (preview + sliders). */
public class AnimCommand extends Command {
    public AnimCommand() {
        super("anim", "animations");
    }

    @Override
    public void executeBuild(LiteralArgumentBuilder<CommandSource> builder) {
        builder.executes(context -> {
            // Deferred: the chat screen closes right after the command runs; that close would wipe our window.
            mc.execute(() -> mc.setScreen(new AnimationScreen()));
            return SINGLE_SUCCESS;
        });
    }
}
