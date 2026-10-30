package thunder.hack.features.modules.client;

import thunder.hack.features.modules.Module;
import thunder.hack.setting.Setting;

/**
 * The client's own interface sounds - the ClickGUI opening and closing, the settings panel sliding, groups
 * expanding, the Dynamic Island chime. On = they play; Volume sets how loud, separately from SoundFX (which is
 * about game feedback: hits, kills, module toggles).
 */
public final class ClientSound extends Module {
    public ClientSound() {
        super("ClientSound", Category.CLIENT);
        setEnabled(true); // on from the start; a saved config still wins
    }

    /** Quiet by default: these play on every click, so they should sit under the game, not over it. */
    public final Setting<Integer> volume = new Setting<>("Volume", 35, 0, 100);
    public final Setting<Boolean> gui = new Setting<>("GUI", true);
    public final Setting<Boolean> island = new Setting<>("DynamicIsland", true);
}
