package thunder.hack.features.modules.combat;

import meteordevelopment.orbit.EventHandler;
import thunder.hack.core.Managers;
import thunder.hack.events.impl.EventSetting;
import thunder.hack.features.modules.Module;
import thunder.hack.gui.notification.Notification;
import thunder.hack.setting.Setting;

import java.util.List;

/**
 * ConfigManager — one-click anticheat presets. Pick an anticheat and it configures your combat
 * placement/aura modules (Aura, AutoTrap, AutoWeb) with values tuned for that anticheat.
 * <p>
 * Applying happens either by enabling the module (enable = apply) or, while it is already enabled,
 * by toggling the {@code Apply} button. Values are sensible starting points — tune per server.
 */
public final class ConfigManager extends Module {
    private final Setting<AntiCheat> anticheat = new Setting<>("AntiCheat", AntiCheat.Grim);
    private final Setting<Boolean> apply = new Setting<>("Apply", false);

    private final Setting<Boolean> configureAura = new Setting<>("Aura", true);
    private final Setting<Boolean> configureAutoTrap = new Setting<>("AutoTrap", true);
    private final Setting<Boolean> configureAutoWeb = new Setting<>("AutoWeb", true);
    private final Setting<Boolean> enableModules = new Setting<>("EnableModules", false);

    public ConfigManager() {
        super("ConfigManager", Category.COMBAT);
    }

    @Override
    public void onEnable() {
        // enable == apply the currently selected anticheat preset
        applyPreset();
    }

    @EventHandler
    public void onSetting(EventSetting e) {
        // Apply acts as a re-apply button while the module is enabled.
        if (e.getSetting() == apply && apply.getValue()) {
            applyPreset();
            apply.setValueSilent(false);
        }
    }

    private void applyPreset() {
        // Aura is gone (Kilaura took over), so the "Aura" preset switch now tunes Kilaura instead
        Module aura = configureAura.getValue() ? Managers.MODULE.get("Kilaura") : null;
        Module trap = configureAutoTrap.getValue() ? Managers.MODULE.get("AutoTrap") : null;
        Module web = configureAutoWeb.getValue() ? Managers.MODULE.get("AutoWeb") : null;

        switch (anticheat.getValue()) {
            case Grim -> {
                auraCfg(aura, 3.0f, true, true);
                placement(trap, web, "Grim", "Strict", "Normal", "Vanilla");
            }
            case Vulcan -> {
                auraCfg(aura, 3.0f, true, false);
                placement(trap, web, "Default", "Strict", "Normal", "Vanilla");
            }
            case Intave -> {
                auraCfg(aura, 3.0f, false, false);
                placement(trap, web, "Default", "Legit", "Normal", "Vanilla");
            }
            case Matrix -> {
                auraCfg(aura, 3.1f, false, false);
                placement(trap, web, "Default", "Strict", "Packet", "Default");
            }
            case Polar -> {
                auraCfg(aura, 3.0f, true, true);
                placement(trap, web, "Grim", "Strict", "Normal", "Vanilla");
            }
            case Verus -> {
                auraCfg(aura, 3.0f, false, false);
                placement(trap, web, "Default", "Strict", "Packet", "Default");
            }
            case Watchdog -> {
                auraCfg(aura, 3.0f, false, false);
                placement(trap, web, "Default", "Legit", "Normal", "Vanilla");
            }
            case Vanilla -> {
                auraCfg(aura, 3.0f, false, false);
                placement(trap, web, "Default", "Vanilla", "Packet", "Default");
            }
        }

        if (enableModules.getValue()) {
            enableIfPresent(aura);
            enableIfPresent(trap);
            enableIfPresent(web);
        }

        Managers.NOTIFICATION.publicity("ConfigManager", "Applied " + anticheat.getValue().name() + " preset", 2, Notification.Type.ENABLED);
    }

    private void auraCfg(Module aura, float range, boolean tpsSync, boolean grimRayTrace) {
        // Kilaura (Rockstar port) reach setting is "AttackRange"; TPSSync/GrimRayTrace have no counterpart and are skipped
        set(aura, "AttackRange", range);
        set(aura, "Smart Range", false);
        set(aura, "TPSSync", tpsSync);
        set(aura, "GrimRayTrace", grimRayTrace);
    }

    // AutoTrap (PlaceModule) uses "Place Mode"/"Place Timing"; AutoWeb uses "PlaceMode"/"PlaceTiming".
    private void placement(Module trap, Module web, String rotate, String interact, String mode, String timing) {
        set(trap, "Rotate", rotate);
        set(trap, "Interact", interact);
        set(trap, "Place Mode", mode);
        set(trap, "Place Timing", timing);

        set(web, "Rotate", rotate);
        set(web, "Interact", interact);
        set(web, "PlaceMode", mode);
        set(web, "PlaceTiming", timing);
    }

    private void enableIfPresent(Module m) {
        if (m != null && !m.isEnabled())
            m.enable();
    }

    /**
     * Sets a setting on {@code module} by its display name, without needing access to the (often
     * package-private) setting/enum types: enum values are matched by their name via the mode list,
     * numbers are boxed to the setting's own numeric type, booleans are set directly.
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private void set(Module module, String name, Object value) {
        if (module == null) return;
        List<Setting<?>> settings = module.getSettings();
        for (Setting s : settings) {
            if (!s.getName().equalsIgnoreCase(name)) continue;
            try {
                if (value instanceof String enumName && s.isEnumSetting()) {
                    String[] modes = s.getModes();
                    for (int i = 0; i < modes.length; i++) {
                        if (modes[i].equalsIgnoreCase(enumName)) {
                            s.setEnumByNumber(i);
                            break;
                        }
                    }
                } else if (value instanceof Boolean b) {
                    s.setValue(b);
                } else if (value instanceof Number n) {
                    if (s.isFloat()) s.setValue(n.floatValue());
                    else if (s.isInteger()) s.setValue(n.intValue());
                    else s.setValue(n.doubleValue());
                }
            } catch (Exception ignored) {
            }
            return;
        }
    }

    public enum AntiCheat {
        Grim, Vulcan, Intave, Matrix, Polar, Verus, Watchdog, Vanilla
    }
}
