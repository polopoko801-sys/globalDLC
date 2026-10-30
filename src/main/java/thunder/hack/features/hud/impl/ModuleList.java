package thunder.hack.features.hud.impl;

import net.minecraft.client.gui.DrawContext;
import thunder.hack.core.Managers;
import thunder.hack.features.hud.HudElement;
import thunder.hack.features.hud.HudListStyle;
import thunder.hack.features.modules.Module;
import thunder.hack.setting.Setting;
import thunder.hack.setting.impl.ColorSetting;

import java.awt.Color;
import java.util.Comparator;
import java.util.List;

/**
 * The enabled modules, longest first.
 *
 * <p>Rows come from {@link HudListStyle}, which is the same code KeyBinds draws with - one place to change
 * how a list on the HUD looks, and the two stacks can never drift apart.
 */
public class ModuleList extends HudElement {
    /** The Potions-style glass panel, each module sliding in from the left and out to the left. */
    private final Setting<Boolean> liquidGlass = new Setting<>("LiquidGlass", true);
    private final Setting<String> glassTitle = new Setting<>("Title", "Modules", v -> liquidGlass.getValue());
    /** Off: just the module name ("Kilaura"), without its mode or info on the right ("Kilaura  Packet"). */
    private final Setting<Boolean> showInfo = new Setting<>("ShowInfo", false, v -> liquidGlass.getValue());
    private final Setting<HudListStyle.Mode> mode = new Setting<>("Mode", HudListStyle.Mode.Glass, v -> !liquidGlass.getValue());
    private final Setting<Boolean> small = new Setting<>("SmallFont", false);
    /** Frosted backdrop behind every row. Independent of the global HudStyle, and colours stay on top. */
    private final Setting<Boolean> blur = new Setting<>("Blur", false);
    private final Setting<Boolean> glow = new Setting<>("Glow", false, v -> mode.getValue() != HudListStyle.Mode.Glass);
    private final Setting<Integer> glowStrength = new Setting<>("GlowStrength", 30, 1, 50, v -> glow.getValue() && mode.getValue() != HudListStyle.Mode.Glass);
    private final Setting<Boolean> hrender = new Setting<>("HideRender", true);
    private final Setting<Boolean> hhud = new Setting<>("HideHud", true);
    private final Setting<ColorSetting> color3 = new Setting<>("RectColor", new ColorSetting(new Color(12, 12, 15, 208)));
    private final Setting<ColorSetting> color4 = new Setting<>("SideRectColor", new ColorSetting(-16777216), v -> mode.getValue() == HudListStyle.Mode.ColorRect);
    private final Setting<ColorSetting> nameColor = new Setting<>("NameColor", new ColorSetting(-1));

    public ModuleList() {
        super("ArrayList", 50, 30);
    }

    public void onRender2D(DrawContext context) {
        super.onRender2D(context);

        boolean reverse = getPosX() > (mc.getWindow().getScaledWidth() / 2f);

        if (liquidGlass.getValue()) {
            drawGlass(context, reverse);
            return;
        }
        HudListStyle.Style style = new HudListStyle.Style(mode.getValue(), small.getValue(), reverse,
                glow.getValue(), glowStrength.getValue(),
                color3.getValue().getColorObject(), color4.getValue().getColorObject(), blur.getValue());

        List<Module> list;
        try {
            list = Managers.MODULE.getEnabledModules().stream()
                    .filter(this::shouldRender)
                    .sorted(Comparator.comparingDouble(module -> -HudListStyle.rowWidth(style, module.getDisplayName(), trailOf(module))))
                    .toList();
        } catch (IllegalArgumentException ex) {
            return;
        }

        float rowH = HudListStyle.rowHeight(style);
        int offset = 0;
        float maxWidth = 0;

        for (Module module : list) {
            String trail = trailOf(module);
            float w = HudListStyle.rowWidth(style, module.getDisplayName(), trail);
            float x = reverse ? getPosX() - w : getPosX();

            HudListStyle.drawRow(context, style, x, getPosY() + offset, w,
                    module.getDisplayName(), trail, HudListStyle.TrailAlign.Inline, offset,
                    nameColor.getValue().getColorObject());

            if (w > maxWidth) maxWidth = w;
            offset += (int) rowH;
        }

        setBounds(getPosX(), getPosY(), (int) maxWidth * (reverse ? -1 : 1), offset);
    }

    private final thunder.hack.features.hud.RowAnimator<Module> slide = new thunder.hack.features.hud.RowAnimator<>(260f);

    /** LiquidGlass: name on the left, its info on the right, longest first, each row animated in and out. */
    private void drawGlass(DrawContext context, boolean reverse) {
        slide.frame();
        var font = GlassPanel.font();
        List<Module> all = new java.util.ArrayList<>(Managers.MODULE.modules);
        all.sort(Comparator.comparingDouble(m -> -font.getStringWidth(m.getDisplayName() + (!showInfo.getValue() || m.getDisplayInfo() == null ? "" : m.getDisplayInfo()))));

        List<GlassPanel.Row> rows = new java.util.ArrayList<>();
        for (Module m : all) {
            float p = slide.update(m, m.isEnabled() && shouldRender(m));
            if (p <= 0f) continue;
            String info = showInfo.getValue() ? m.getDisplayInfo() : null;
            rows.add(new GlassPanel.Row(null, m.getDisplayName(), info == null ? "" : info, p));
        }
        if (rows.isEmpty()) {
            setBounds(getPosX(), getPosY(), 0, 0);
            return;
        }
        float w = GlassPanel.width(glassTitle.getValue(), rows, 90);
        float x = reverse ? getPosX() - w : getPosX();
        GlassPanel.draw(context, x, getPosY(), w, glassTitle.getValue(), null, rows, thunder.hack.features.modules.client.HudEditor.getColor(0));
        setBounds(getPosX(), getPosY(), w * (reverse ? -1 : 1), GlassPanel.height(rows));
    }

    /** The " [info]" half of a row, or null when the module has nothing to add. */
    private static String trailOf(Module module) {
        String info = module.getDisplayInfo();
        return info == null ? null : " " + info;
    }

    private boolean shouldRender(Module m) {
        return m.isDrawn()
                && (!hrender.getValue() || m.getCategory() != Category.RENDER)
                && (!hhud.getValue() || m.getCategory() != Category.HUD);
    }
}
