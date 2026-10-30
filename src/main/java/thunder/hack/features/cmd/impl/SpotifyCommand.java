package thunder.hack.features.cmd.impl;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.command.CommandSource;
import org.jetbrains.annotations.NotNull;
import thunder.hack.features.cmd.Command;
import thunder.hack.gui.spotify.SpotifyScreen;
import thunder.hack.utility.spotify.SpotifyArt;
import thunder.hack.utility.spotify.SpotifyAuth;
import thunder.hack.utility.spotify.SpotifyLibrary;
import thunder.hack.utility.spotify.SpotifyPlayer;

import static com.mojang.brigadier.Command.SINGLE_SUCCESS;

/**
 * The text side of the Spotify panel: logging in, and everything that needs a value typed.
 *
 * <p>The client id has to be entered somewhere, and a chat command is the one input that always works -
 * before any login exists, with no screen open, and with a value you can paste. Everything else here is a
 * convenience for the same things the panel does with a click.
 */
public class SpotifyCommand extends Command {
    public SpotifyCommand() {
        super("spotify", "sp");
    }

    @Override
    public void executeBuild(@NotNull LiteralArgumentBuilder<CommandSource> builder) {
        builder.executes(context -> {
            SpotifyScreen.open(SpotifyScreen.Tab.NowPlaying);
            return SINGLE_SUCCESS;
        });

        builder.then(literal("login").executes(context -> {
            if (SpotifyAuth.clientId().isEmpty()) {
                // chat links are not clickable here, so do the clicking for them: open the dashboard and put the
                // redirect URI on the clipboard, ready to paste
                try {
                    net.minecraft.util.Util.getOperatingSystem().open(java.net.URI.create("https://developer.spotify.com/dashboard"));
                } catch (Throwable ignored) {
                }
                try {
                    mc.keyboard.setClipboard(SpotifyAuth.REDIRECT);
                } catch (Throwable ignored) {
                }
                sendMessage("Otworzylem developer.spotify.com - zaloz tam darmowa apke (Create app).");
                sendMessage("Redirect URI jest juz w schowku - wklej go (Ctrl+V) w polu Redirect URIs.");
                sendMessage("Potem skopiuj Client ID i wpisz: @spotify clientid <id>, a na koniec znowu @spotify login");
                return SINGLE_SUCCESS;
            }

            sendMessage("Otwieram przegladarke - zaloguj sie na stronie Spotify.");
            SpotifyAuth.login(status -> sendMessage("§7spotify: " + status));
            return SINGLE_SUCCESS;
        }));

        builder.then(literal("logout").executes(context -> {
            SpotifyAuth.logout();
            SpotifyArt.forget();
            sendMessage("Wylogowano, token usuniety. Apke mozesz tez odebrac w Spotify -> Apps.");
            return SINGLE_SUCCESS;
        }));

        builder.then(literal("clientid")
                .then(arg("id", StringArgumentType.word()).executes(context -> {
                    String id = StringArgumentType.getString(context, "id");
                    SpotifyAuth.setClientId(id);
                    sendMessage("Client id zapisany. Teraz: .spotify login");
                    return SINGLE_SUCCESS;
                }))
                .executes(context -> {
                    // Printed rather than hidden: the client id is public by design - it travels in the
                    // authorisation URL - so there is nothing to protect by refusing to show it.
                    sendMessage(SpotifyAuth.clientId().isEmpty()
                            ? "Brak client id. Uzycie: .spotify clientid <id>"
                            : "Client id: " + SpotifyAuth.clientId());
                    sendMessage("Redirect URI do wklejenia w apce: §7" + SpotifyAuth.REDIRECT);
                    return SINGLE_SUCCESS;
                }));

        builder.then(literal("status").executes(context -> {
            sendMessage("Login: " + SpotifyAuth.stage()
                    + (SpotifyAuth.message().isEmpty() ? "" : " (" + SpotifyAuth.message() + ")"));

            if (!SpotifyAuth.authorised()) return SINGLE_SUCCESS;

            SpotifyPlayer.Snapshot snapshot = SpotifyPlayer.state();
            sendMessage("Konto: " + (SpotifyLibrary.meName().isEmpty() ? SpotifyLibrary.meId() : SpotifyLibrary.meName()));

            if (!snapshot.present()) {
                sendMessage("Nic nie gra.");
                return SINGLE_SUCCESS;
            }

            sendMessage(snapshot.title() + " - " + snapshot.artist());
            sendMessage("§7" + snapshot.album() + "  |  "
                    + SpotifyPlayer.stamp(snapshot.progressMs()) + " / " + SpotifyPlayer.stamp(snapshot.durationMs())
                    + "  |  " + (snapshot.playing() ? "plays" : "paused"));
            sendMessage("§7id " + snapshot.trackId() + "  |  "
                    + (snapshot.deviceName().isEmpty() ? "brak urzadzenia" : snapshot.deviceName()));
            return SINGLE_SUCCESS;
        }));

        builder.then(literal("search").then(arg("query", StringArgumentType.greedyString()).executes(context -> {
            if (!authorised()) return SINGLE_SUCCESS;

            String query = StringArgumentType.getString(context, "query");
            SpotifyLibrary.search(query, java.util.List.of(
                    thunder.hack.utility.spotify.SpotifyModels.Kind.Track,
                    thunder.hack.utility.spotify.SpotifyModels.Kind.Album,
                    thunder.hack.utility.spotify.SpotifyModels.Kind.Artist,
                    thunder.hack.utility.spotify.SpotifyModels.Kind.Playlist,
                    thunder.hack.utility.spotify.SpotifyModels.Kind.Show,
                    thunder.hack.utility.spotify.SpotifyModels.Kind.Episode));
            SpotifyScreen.open(SpotifyScreen.Tab.Search);
            return SINGLE_SUCCESS;
        })));

        /* The transport, for anybody who would rather bind a macro than click. */
        builder.then(literal("play").executes(context -> run(SpotifyPlayer::resume)));
        builder.then(literal("pause").executes(context -> run(SpotifyPlayer::pause)));
        builder.then(literal("toggle").executes(context -> run(SpotifyPlayer::toggle)));
        builder.then(literal("next").executes(context -> run(SpotifyPlayer::next)));
        builder.then(literal("prev").executes(context -> run(SpotifyPlayer::previous)));
        builder.then(literal("save").executes(context -> run(SpotifyLibrary::toggleSaved)));

        builder.then(literal("volume").then(arg("percent", com.mojang.brigadier.arguments.IntegerArgumentType.integer(0, 100))
                .executes(context -> run(() -> SpotifyPlayer.volume(
                        com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(context, "percent"))))));

        builder.then(literal("devices").executes(context -> {
            if (!authorised()) return SINGLE_SUCCESS;
            SpotifyScreen.open(SpotifyScreen.Tab.Devices);
            return SINGLE_SUCCESS;
        }));

        builder.then(literal("playlists").executes(context -> {
            if (!authorised()) return SINGLE_SUCCESS;
            SpotifyScreen.open(SpotifyScreen.Tab.Playlists);
            return SINGLE_SUCCESS;
        }));

        builder.then(literal("queue").executes(context -> {
            if (!authorised()) return SINGLE_SUCCESS;
            SpotifyScreen.open(SpotifyScreen.Tab.Queue);
            return SINGLE_SUCCESS;
        }));

        builder.then(literal("clearart").executes(context -> {
            SpotifyArt.forget();
            sendMessage("Cache okladek wyczyszczony (pliki zostaja w GlobalDLC/misc/spotify-art).");
            return SINGLE_SUCCESS;
        }));
    }

    private int run(Runnable action) {
        if (!authorised()) return SINGLE_SUCCESS;
        action.run();
        return SINGLE_SUCCESS;
    }

    private boolean authorised() {
        if (SpotifyAuth.authorised()) return true;
        sendMessage("Nie jestes zalogowany: .spotify login");
        return false;
    }
}
