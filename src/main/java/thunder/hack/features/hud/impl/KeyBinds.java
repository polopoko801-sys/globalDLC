package thunder.hack.features.hud.impl;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.util.Formatting;
import thunder.hack.core.Managers;
import thunder.hack.core.manager.client.ModuleManager;
import thunder.hack.features.hud.HudElement;
import thunder.hack.features.modules.Module;
import thunder.hack.features.modules.client.HudEditor;
import thunder.hack.gui.font.FontRenderer;
import thunder.hack.gui.font.FontRenderers;
import thunder.hack.setting.Setting;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 1:1 port of Rockstar Free 2.0's KeyBinds ({@code display.KeyBinds} + {@code HUD.renderKeyBinds}), with its own
 * fonts (mntsb 15/16/20 = Montserrat, i30 = icons2.otf), texts and drawing helpers ({@link RockstarRender}):
 * a 110.5 wide panel titled "Keybinds" (Rockstar: "Бинды") listing the ENABLED bound modules as "Name [KEY]" (LWJGL 2 key names), widest
 * name first, the rows sliding in / out with Translate.arrayListAnim.
 *
 * <p>Rockstar placed the panel at {@code sr.getScaledHeight() - KeyBinds X - 14 + 123 - 4, KeyBinds Y - 15}; here
 * that top-left corner is the HUD editor position, everything else is offset from it exactly as in the original.
 * Rockstar redrew the panel and title for every visible row (the text sat above them at z = 1); the last, largest
 * one is what showed, which is what is drawn here once. Its "KeyBinds Color", "Color Time" and "Background"
 * settings were never read by renderKeyBinds (rows are always colour -1), so they are left out.
 */
public class KeyBinds extends HudElement {
    public KeyBinds() {
        super("KeyBinds", 100, 100);
    }

    /** The three Rockstar modes, plus LiquidGlass (not Rockstar): the Styled layout on the client's glass, tinted with the GUI colour. */
    public enum Mode {LiquidGlass, Rockstar, RockstarNew, RockstarStyled}

    private final Setting<Mode> mode = new Setting<>("Mode", Mode.LiquidGlass);
    private final Setting<Integer> fonty = new Setting<>("Font Y", 1, 0, 10);
    private final Setting<String> glassTitle = new Setting<>("Title", "Keybinds", v -> mode.is(Mode.LiquidGlass));

    private static final String TITLE = "Keybinds";
    private static final float LIST_OFFSET2 = 8.0F;

    private final Map<Module, RockstarRender.Translate> translates = new HashMap<>();
    /** LiquidGlass: the slide-in / slide-out of each row. */
    private final thunder.hack.features.hud.RowAnimator<Module> slide = new thunder.hack.features.hud.RowAnimator<>(260f);
    /** Minecraft.frameTime: the last frame's length in ms. */
    private long lastFrameNanos = System.nanoTime();

    public void onRender2D(DrawContext context) {
        super.onRender2D(context);
        if (fullNullCheck()) return;

        long now = System.nanoTime();
        double frameTime = (now - lastFrameNanos) / 1_000_000D;
        lastFrameNanos = now;

        FontRenderer clientFont = FontRenderers.getModulesRenderer();
        FontRenderer mntsb = FontRenderers.mntsb;
        double width = mc.getWindow().getScaledWidth();
        // panel top-left = Rockstar's (sr.getScaledHeight() - x2 - 14 + 123 - 4, y2 - 15)
        float left = getPosX();
        float top = getPosY();

        List<Module> modules = new ArrayList<>(Managers.MODULE.modules);
        modules.sort(Comparator.comparing(m -> -clientFont.getStringWidth(m.getName())));

        if (mode.is(Mode.LiquidGlass)) {
            // every bound module is a candidate; turning it on slides its row in from the left, turning it off
            // slides it back out to the left
            slide.frame();
            List<GlassPanel.Row> glassRows = new ArrayList<>();
            for (Module feature : modules) {
                if (feature == ModuleManager.clickGui) continue;
                if (feature.getBind().getKey() == -1 || "None".equals(feature.getBind().getBind())) continue;
                float p = slide.update(feature, feature.isEnabled());
                if (p > 0f) glassRows.add(new GlassPanel.Row(null, feature.getName(), getShortKeyName(feature), p));
            }
            if (glassRows.isEmpty()) {
                setBounds(left, top, 0, 0);
                return;
            }
            float gw = GlassPanel.width(glassTitle.getValue(), glassRows, 90);
            GlassPanel.draw(context, left, top, gw, glassTitle.getValue(), thunder.hack.utility.render.TextureStorage.keyboardIcon, glassRows, HudEditor.getColor(0));
            setBounds(left, top, gw, GlassPanel.height(glassRows));
            return;
        }

        float yDist = 1;
        double lastTranslateY = 0;
        List<Module> rows = new ArrayList<>();
        List<Double> rowY = new ArrayList<>();
        for (Module feature : modules) {
            if (feature == ModuleManager.clickGui) continue;
            if (feature.getBind().getKey() == -1 || "None".equals(feature.getBind().getBind())) continue;
            RockstarRender.Translate translate = translates.computeIfAbsent(feature, k -> new RockstarRender.Translate());
            float length = clientFont.getStringWidth(feature.getName());
            float featureX = (float) (width - length);
            if (feature.isEnabled())
                translate.arrayListAnim(featureX, yDist, (float) (0.1f * frameTime) / 6, (float) (0.3f * frameTime) / 6, frameTime);
            else
                translate.arrayListAnim((float) width, yDist, (float) (0.1f * frameTime) / 6, (float) (0.3f * frameTime) / 6, frameTime);
            boolean visible = translate.x < width;
            if (!visible) continue;
            rows.add(feature);
            rowY.add((double) translate.y);
            lastTranslateY = translate.y;
            yDist += LIST_OFFSET2;
        }
        // the panel was drawn inside the per-row loop: with no visible row there is no panel at all
        if (rows.isEmpty()) {
            setBounds(left, top, 0, 0);
            return;
        }

        Color client = HudEditor.getColor(0);
        if (mode.is(Mode.LiquidGlass)) {
            List<GlassPanel.Row> glassRows = new ArrayList<>();
            for (Module feature : rows)
                glassRows.add(new GlassPanel.Row(null, feature.getName(), getShortKeyName(feature)));
            float gw = GlassPanel.width(glassTitle.getValue(), glassRows, 90);
            GlassPanel.draw(context, left, top, gw, glassTitle.getValue(), thunder.hack.utility.render.TextureStorage.keyboardIcon, glassRows, client);
            setBounds(left, top, gw, GlassPanel.height(glassRows.size()));
            return;
        }
        float panelH = (float) (lastTranslateY + 9 + 15);
        switch (mode.getValue()) {
            case RockstarNew ->
                    RockstarRender.drawHorizontalGradientOutlinedRoundedRect(context, left, top, 110.5f, panelH, 7, 1.5f, client.darker(), client);
            case Rockstar ->
                    RockstarRender.drawGradientRoundedRect(context, left, top, 110.5f, panelH, 7, client.darker(), client);
            case LiquidGlass -> {
                RockstarRender.drawLiquidGlass(context, left, top, 110.5f, panelH + 2, 5, client);
                RockstarRender.drawGradientRound(context, left + 2, top + 2, 12, 12, 4, client, client.brighter(), client.brighter(), client);
                FontRenderers.i30.drawString(context, "k", left + 2 + .5f, top + 2 + 2.5f, -1);
            }
            case RockstarStyled -> {
                Color l1 = client.brighter();
                Color l2 = client;
                RockstarRender.drawRound(context, left, top, 110.5f, panelH + 2, 5, new Color(40, 40, 40));
                RockstarRender.drawGradientRound(context, left + 2, top + 2, 12, 12, 4, l2, l1, l1, l2);
                FontRenderers.i30.drawString(context, "k", left + 2 + .5f, top + 2 + 2.5f, -1);
                RockstarRender.drawRect(context, left + 2 + .5f, top + 2 + 2.5f + 10.5f, left + 2 + .5f + 20, top + 2 + 2.5f + 5 + 10, new Color(40, 40, 40));
            }
        }

        if (mode.is(Mode.RockstarStyled) || mode.is(Mode.LiquidGlass))
            RockstarRender.drawStringWithShadow(context, FontRenderers.mntsb_20, TITLE, left + 3 + mntsb.getStringWidth(TITLE) / 2, top + 15 - 10 - 1, -1);
        else
            RockstarRender.drawStringWithShadow(context, FontRenderers.mntsb_16, TITLE, left + 5 + 20 + mntsb.getStringWidth(TITLE) / 2, top + 15 - 10, -1);

        float y = fonty.getValue();
        for (int i = 0; i < rows.size(); i++) {
            Module feature = rows.get(i);
            String moduleLabel = feature.getName();
            String key = lwjglKeyName(feature);
            // (sr.getScaledHeight() - 225 - w / 2 + 386) after translate(-x2, y2) = panel left + 56 - w / 2
            float x = left + 56 - mntsb.getStringWidth(moduleLabel + " [" + key + "]") / 2;
            float ty = (float) (top + 15 + rowY.get(i) + y - 1 + 1);
            if (mode.is(Mode.RockstarStyled))
                mntsb.drawString(context, Formatting.GRAY + moduleLabel + " [" + key + "]", x, ty + 1.5f, -1);
            else
                RockstarRender.drawStringWithShadow(context, mntsb, moduleLabel + Formatting.DARK_GRAY + " [" + key + "]", x, ty, -1);
        }

        setBounds(left, top, 110.5f, panelH + 2);
    }

    /** Keyboard.getKeyName (LWJGL 2): the names Rockstar printed, from this client's GLFW bind names. */
    private static String lwjglKeyName(Module feature) {
        String bind = feature.getBind().getBind();
        if (bind.startsWith("KP_")) return "NUMPAD" + bind.substring(3);
        return switch (bind) {
            case "LEFT_SHIFT" -> "LSHIFT";
            case "RIGHT_SHIFT" -> "RSHIFT";
            case "LEFT_CONTROL" -> "LCONTROL";
            case "RIGHT_CONTROL" -> "RCONTROL";
            case "LEFT_ALT" -> "LMENU";
            case "RIGHT_ALT" -> "RMENU";
            case "CAPS_LOCK" -> "CAPITAL";
            case "ENTER" -> "RETURN";
            case "BACKSPACE" -> "BACK";
            case "GRAVE_ACCENT", "`" -> "GRAVE";
            case "LEFT_BRACKET", "[" -> "LBRACKET";
            case "RIGHT_BRACKET", "]" -> "RBRACKET";
            case "PAGE_UP" -> "PRIOR";
            case "PAGE_DOWN" -> "NEXT";
            case "," -> "COMMA";
            case "." -> "PERIOD";
            case "/" -> "SLASH";
            case ";" -> "SEMICOLON";
            case "'" -> "APOSTROPHE";
            case "-" -> "MINUS";
            case "=" -> "EQUALS";
            case "\\" -> "BACKSLASH";
            default -> bind;
        };
    }

    /** Short key names for .bind list. */
    public static String getShortKeyName(Module feature) {
        String sbind = feature.getBind().getBind();
        return switch (sbind) {
            case "LEFT_CONTROL" -> "LCtrl";
            case "RIGHT_CONTROL" -> "RCtrl";
            case "LEFT_SHIFT" -> "LShift";
            case "RIGHT_SHIFT" -> "RShift";
            case "LEFT_ALT" -> "LAlt";
            case "RIGHT_ALT" -> "RAlt";
            default -> sbind.toUpperCase();
        };
    }
}
