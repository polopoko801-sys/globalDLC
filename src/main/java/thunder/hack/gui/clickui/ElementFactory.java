package thunder.hack.gui.clickui;

import thunder.hack.features.modules.Module;
import thunder.hack.gui.clickui.impl.*;
import thunder.hack.setting.Setting;
import thunder.hack.setting.impl.*;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns a module's settings into the ClickGUI's setting widgets. Lives on its own so the standalone
 * windows (AutoBuild, ...) can show the very same controls the ClickGUI panel shows instead of
 * hand-rolling a second, poorer settings renderer.
 */
public final class ElementFactory {
    private ElementFactory() {
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    public static List<AbstractElement> build(Module module) {
        List<AbstractElement> elements = new ArrayList<>();
        for (Setting setting : module.getSettings()) {
            if (setting.getValue() instanceof Boolean && !setting.getName().equals("Enabled") && !setting.getName().equals("Drawn"))
                elements.add(new BooleanElement(setting));
            else if (setting.getValue() instanceof ColorSetting)
                elements.add(new ColorPickerElement(setting));
            else if (setting.getValue() instanceof BooleanSettingGroup)
                elements.add(new BooleanParentElement(setting));
            else if (setting.isNumberSetting() && setting.hasRestriction())
                elements.add(new SliderElement(setting));
            else if (setting.getValue() instanceof ItemSelectSetting)
                elements.add(new ItemSelectElement(setting));
            else if (setting.getValue() instanceof SettingGroup)
                elements.add(new ParentElement(setting));
            else if (setting.isEnumSetting() && !(setting.getValue() instanceof PositionSetting))
                elements.add(new ModeElement(setting));
            else if (setting.getValue() instanceof Bind && !setting.getName().equals("Keybind"))
                elements.add(new BindElement(setting));
            else if ((setting.getValue() instanceof String || setting.getValue() instanceof Character) && !setting.getName().equalsIgnoreCase("displayName"))
                elements.add(new StringElement(setting));
        }
        elements.forEach(AbstractElement::init);
        return elements;
    }

    /** Row height the ClickGUI panel gives an element - color pickers and sliders are taller. */
    public static float height(AbstractElement el) {
        if (el instanceof ColorPickerElement picker) return picker.getHeight();
        if (el instanceof SliderElement) return 18;
        if (el instanceof ModeElement combo)
            return 13 + combo.listHeight();
        return 13;
    }

    /** Applies the per-type height an element expects before it is rendered. */
    public static void applyHeight(AbstractElement el) {
        el.setHeight(13);
        if (el instanceof ColorPickerElement picker) el.setHeight(picker.getHeight());
        else if (el instanceof SliderElement) el.setHeight(18);
        else if (el instanceof ModeElement combo) {
            combo.setWHeight(13);
            el.setHeight(13 + combo.listHeight());
        }
    }
}
