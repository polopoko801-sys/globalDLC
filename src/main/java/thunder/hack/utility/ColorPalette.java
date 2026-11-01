package thunder.hack.utility;

import thunder.hack.core.Managers;
import thunder.hack.features.modules.Module;
import thunder.hack.setting.Setting;
import thunder.hack.setting.impl.ColorSetting;

import java.awt.*;
import java.util.List;
import java.util.Set;

/**
 * One-click recolour of everything the client draws IN THE WORLD: tracers, arrows, the AutoWeb/AutoTrap
 * placement render, ESP, particles. The picked colour also becomes the ClickGUI accent, so the selected
 * category and the enabled modules light up in it instead of the default purple.
 *
 * <p>What it deliberately never touches: every HUD element (ArrayList, WaterMark, KeyBinds, TargetHud,
 * notifications) and {@code HudEditor}'s own colours — the on-screen overlay keeps its own palette — plus
 * the modules in {@link #EXCLUDED}. Per-setting alpha is preserved, so only the hue changes.
 */
public final class ColorPalette {
    public static final List<Color> SWATCHES = List.of(
            new Color(235, 64, 64),    // red
            new Color(244, 128, 47),   // orange
            new Color(232, 182, 60),   // gold
            new Color(255, 228, 40),   // yellow - a real, bright yellow (the old one read as gold)
            new Color(170, 230, 60),   // lime
            new Color(112, 214, 108),  // green
            new Color(72, 214, 186),   // mint
            new Color(60, 220, 240),   // cyan
            new Color(70, 178, 245),   // sky
            new Color(78, 110, 240),   // blue
            new Color(168, 85, 247),   // purple
            new Color(238, 100, 190),  // pink
            new Color(255, 120, 150),  // rose
            new Color(236, 238, 245)   // white
    );

    public static final List<String> NAMES = List.of(
            "Red", "Orange", "Gold", "Yellow", "Lime", "Green", "Mint", "Cyan", "Sky", "Blue", "Purple", "Pink", "Rose", "White");

    /** Modules whose colours a palette click must not touch (on top of every HUD element). */
    public static final Set<String> EXCLUDED = Set.of("Skeleton", "HudEditor");

    private ColorPalette() {
    }

    /**
     * The colour the client is painted with, or null when the defaults are in use. Stored in the ClickGui
     * module so it goes through the normal config save/load and survives a restart.
     */
    public static Color getActive() {
        // Asked for during ModuleManager's own static init: a module constructor takes the accent as a
        // colour setting's default, and at that moment clickGui is still null because the field it lives
        // in has not been assigned yet. Answering "no palette" is correct there - the default is the
        // right value - and it has to not throw, because an exception in a static initialiser takes the
        // whole class down and the client never starts.
        try {
            var clickGui = thunder.hack.core.manager.client.ModuleManager.clickGui;
            if (clickGui == null) return null;

            int argb = clickGui.paletteColor.getValue();
            return argb == 0 ? null : new Color(argb, true);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void setActive(Color color) {
        thunder.hack.core.manager.client.ModuleManager.clickGui.paletteColor
                .setValue(color == null ? 0 : color.getRGB());
    }

    /** Writes {@code color} into the colour settings of every world-render module. */
    public static void apply(Color color) {
        setActive(color);
        forEachTarget(cs -> {
            int alpha = (cs.getColor() >> 24) & 0xFF;
            cs.setRainbow(false);
            cs.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(), alpha).getRGB());
        });
    }

    /**
     * Restores the client's factory colours. Unlike {@link #apply} this is deliberately NOT scoped: it also
     * repaints the HUD elements and HudEditor, so a palette click made before HUD elements were excluded
     * (or any manual colour edit) can be undone in one go.
     */
    public static void reset() {
        setActive(null);
        for (Module module : Managers.MODULE.modules)
            for (Setting<?> setting : module.getSettings())
                if (setting.getValue() instanceof ColorSetting cs)
                    cs.setDefault();
    }

    private static void forEachTarget(java.util.function.Consumer<ColorSetting> action) {
        for (Module module : Managers.MODULE.modules) {
            // HUD elements keep their own look — the user does not want the overlay following the palette
            if (module.getCategory() == Module.Category.HUD) continue;
            if (EXCLUDED.contains(module.getName())) continue;
            for (Setting<?> setting : module.getSettings())
                if (setting.getValue() instanceof ColorSetting cs)
                    action.accept(cs);
        }
    }
}
