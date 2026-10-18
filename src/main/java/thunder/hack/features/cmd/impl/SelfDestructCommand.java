package thunder.hack.features.cmd.impl;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.command.CommandSource;
import org.jetbrains.annotations.NotNull;
import thunder.hack.core.Managers;
import thunder.hack.core.manager.client.ConfigManager;
import thunder.hack.features.cmd.Command;
import thunder.hack.features.modules.Module;

import java.io.File;
import java.util.ArrayList;

import static com.mojang.brigadier.Command.SINGLE_SUCCESS;

/**
 * Panic / self-destruct: instantly disables every enabled module. With the {@code confirm}
 * sub-command it also wipes all configs & client data (the Cloudan folder) — a full
 * "leave no trace" nuke. The wipe is gated behind {@code confirm} so it can't fire by accident.
 */
public class SelfDestructCommand extends Command {
    public SelfDestructCommand() {
        super("selfdestruct", "sd", "panic");
    }

    @Override
    public void executeBuild(@NotNull LiteralArgumentBuilder<CommandSource> builder) {
        builder.executes(context -> {
            int n = disableAll();
            sendMessage("Self-destruct: disabled " + n + " module(s). Type .selfdestruct confirm to ALSO wipe all configs & data.");
            return SINGLE_SUCCESS;
        });

        builder.then(literal("confirm").executes(context -> {
            int n = disableAll();
            boolean wiped = deleteRecursively(ConfigManager.MAIN_FOLDER);
            sendMessage("Self-destruct complete — disabled " + n + " module(s)"
                    + (wiped ? " and wiped all configs & data." : ", but the data wipe failed (files in use?)."));
            return SINGLE_SUCCESS;
        }));
    }

    private int disableAll() {
        int count = 0;
        for (Module m : new ArrayList<>(Managers.MODULE.getEnabledModules())) {
            if (m.isEnabled()) {
                m.disable();
                count++;
            }
        }
        return count;
    }

    private boolean deleteRecursively(File f) {
        if (f == null || !f.exists()) return true;
        boolean ok = true;
        File[] children = f.listFiles();
        if (children != null)
            for (File c : children)
                ok &= deleteRecursively(c);
        return ok & f.delete();
    }
}
