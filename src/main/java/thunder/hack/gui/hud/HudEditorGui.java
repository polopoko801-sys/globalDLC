package thunder.hack.gui.hud;

import com.google.common.collect.Lists;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;
import thunder.hack.ThunderHack;
import thunder.hack.core.Managers;
import thunder.hack.features.hud.HudElement;
import thunder.hack.features.modules.Module;
import thunder.hack.gui.clickui.AbstractCategory;
import thunder.hack.gui.clickui.AbstractElement;
import thunder.hack.gui.clickui.ClickGUI;
import thunder.hack.gui.clickui.impl.*;
import thunder.hack.gui.font.FontRenderers;
import thunder.hack.setting.Setting;
import thunder.hack.setting.impl.*;
import thunder.hack.utility.render.Render2DEngine;
import thunder.hack.utility.render.animation.AnimationUtility;

import java.awt.Color;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static thunder.hack.features.modules.Module.mc;

public class HudEditorGui extends Screen {
    public static HudElement currentlyDragging;
    private final List<AbstractCategory> windows;
    private static HudEditorGui instance = new HudEditorGui();

    private double dWheel;

    // ---- palette (glassy, matches ClickGUI) ----
    private static final Color PANEL    = new Color(18, 19, 26, 224);
    private static final Color ROW      = new Color(30, 31, 41, 226);
    private static final Color ROW_HOV  = new Color(39, 41, 53, 236);
    private static final Color ACCENT   = new Color(139, 100, 246);
    private static final Color GREEN    = new Color(120, 220, 130);
    private static final Color TEXT     = new Color(233, 234, 240);
    private static final Color DIM      = new Color(140, 143, 158);
    private static final Color DIVIDER  = new Color(255, 255, 255, 18);

    // ---- right-click context menu (list of HUD elements) ----
    private boolean menuOpen;
    private float menuX, menuY, menuW = 116f;
    private final List<float[]> menuRows = new ArrayList<>(); // {x,y,w,h,index}
    private List<Module> hudModules = new ArrayList<>();

    // ---- slide-out settings panel (reuses ClickGUI's element widgets) ----
    private Module settingsModule;
    private final Map<Module, List<AbstractElement>> elementCache = new HashMap<>();
    private float spX, spY, spW = 150f, spH, spCloseX, spCloseY;
    private float spToggleX, spToggleY, spToggleW, spToggleH;
    private float scrollSettings, scrollSettingsTarget;

    public HudEditorGui() {
        super(Text.of("HudEditorGui"));
        windows = Lists.newArrayList();
        this.setInstance();
    }

    @Override
    protected void init() {
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        ClickGUI.anyHovered = false;
        thunder.hack.utility.render.ScreenCapture.captureFrame();

        // 1.21.11: while a Screen is open the game does NOT call InGameHud.render, so render the
        // enabled HUD elements here so they're visible & draggable in the editor.
        hudModules = Managers.MODULE.getModulesByCategory(Module.Category.HUD);
        for (Module m : hudModules)
            if (m.isEnabled())
                m.onRender2D(context);

        dWheel = 0;

        FontRenderers.monsterrat.drawCenteredString(context,
                "Drag to move  •  right-click empty space for the element list  •  right-click an element for its settings",
                mc.getWindow().getScaledWidth() / 2f, 6, new Color(210, 212, 220).getRGB());

        if (menuOpen)
            renderMenu(context, mouseX, mouseY);

        if (settingsModule != null)
            renderSettings(context, mouseX, mouseY, delta);
    }

    private void renderMenu(DrawContext context, int mouseX, int mouseY) {
        menuRows.clear();
        float rowH = 15f;
        float headerH = 16f;
        float h = headerH + hudModules.size() * rowH + 6f;

        // keep on-screen
        float x = Math.min(menuX, mc.getWindow().getScaledWidth() - menuW - 4f);
        float y = Math.min(menuY, mc.getWindow().getScaledHeight() - h - 4f);

        Render2DEngine.drawBlurredShadow(context, x, y, menuW, h, 10, new Color(0, 0, 0, 130));
        thunder.hack.gui.windows.WindowBase.glassPanel(context, x, y, menuW, h, 5f, mouseX, mouseY);
        FontRenderers.sf_medium.drawString(context, "HUD Elements", x + 8f, y + 5f, TEXT.getRGB());
        Render2DEngine.drawRect(context, x + 6f, y + headerH - 1f, menuW - 12f, 1f, DIVIDER);

        float ry = y + headerH;
        for (int i = 0; i < hudModules.size(); i++) {
            Module m = hudModules.get(i);
            boolean hov = Render2DEngine.isHovered(mouseX, mouseY, x + 4f, ry, menuW - 8f, rowH);
            if (hov)
                Render2DEngine.drawRound(context, x + 4f, ry, menuW - 8f, rowH, 3f, ROW_HOV);
            // on/off dot
            Render2DEngine.drawRound(context, x + 8f, ry + 5f, 5f, 5f, 1.5f, m.isEnabled() ? ACCENT : DIM);
            FontRenderers.sf_medium_mini.drawString(context, m.getName(), x + 18f, ry + 4f,
                    (m.isEnabled() ? TEXT : DIM).getRGB());
            menuRows.add(new float[]{x + 4f, ry, menuW - 8f, rowH, i});
            ry += rowH;
        }
    }

    private void renderSettings(DrawContext context, int mouseX, int mouseY, float delta) {
        List<AbstractElement> els = getElements(settingsModule);
        float contentTotal = 0f;
        for (AbstractElement el : els) if (el.isVisible()) contentTotal += elementHeight(el);
        spH = Math.max(40f, Math.min(mc.getWindow().getScaledHeight() - 20f, 22f + contentTotal + 6f));

        // clamp on-screen (spX/spY were set where the user clicked)
        spX = Math.min(spX, mc.getWindow().getScaledWidth() - spW - 4f);
        spY = Math.min(spY, mc.getWindow().getScaledHeight() - spH - 4f);
        if (spX < 4f) spX = 4f;
        if (spY < 4f) spY = 4f;

        if (Render2DEngine.isHovered(mouseX, mouseY, spX, spY, spW, spH))
            ClickGUI.anyHovered = true;

        Render2DEngine.drawBlurredShadow(context, spX, spY, spW, spH, 12, new Color(0, 0, 0, 130));
        thunder.hack.gui.windows.WindowBase.glassPanel(context, spX, spY, spW, spH, 6f, mouseX, mouseY);

        String title = settingsModule.getName();
        FontRenderers.monsterrat.drawString(context, title, spX + 9f, spY + 7f, TEXT.getRGB());

        // on/off toggle for the element itself (so you can disable it right here)
        spToggleW = 24f; spToggleH = 11f;
        spToggleX = spX + spW - 20f - spToggleW;
        spToggleY = spY + 6f;
        boolean on = settingsModule.isEnabled();
        Render2DEngine.drawRound(context, spToggleX, spToggleY, spToggleW, spToggleH, 5.5f, on ? ACCENT : new Color(58, 60, 72));
        Render2DEngine.drawRound(context, on ? spToggleX + spToggleW - 10f : spToggleX + 1f, spToggleY + 1f, 9f, 9f, 4.5f, TEXT);

        spCloseX = spX + spW - 14f;
        spCloseY = spY + 6f;
        boolean closeHov = Render2DEngine.isHovered(mouseX, mouseY, spCloseX - 3f, spCloseY - 3f, 12f, 12f);
        FontRenderers.sf_medium.drawString(context, "x", spCloseX, spCloseY, (closeHov ? TEXT : DIM).getRGB());
        Render2DEngine.drawRect(context, spX + 8f, spY + 19f, spW - 16f, 1f, DIVIDER);

        float ex = spX + 8f;
        float ew = spW - 16f;
        float top = spY + 23f;
        float viewH = spH - 25f;
        float maxScroll = Math.max(0f, contentTotal - viewH);
        scrollSettingsTarget = Math.max(-maxScroll, Math.min(0f, scrollSettingsTarget));
        scrollSettings = AnimationUtility.fast(scrollSettings, scrollSettingsTarget, 12f);

        Render2DEngine.addWindow(context, spX, top, spX + spW, spY + spH - 4f, 1.0);
        float offY = scrollSettings;
        for (AbstractElement el : els) {
            if (!el.isVisible()) continue;
            el.setOffsetY(offY);
            el.setX(ex);
            el.setY(top);
            el.setWidth(ew);
            el.setHeight(13);
            if (el instanceof ColorPickerElement picker) el.setHeight(picker.getHeight());
            else if (el instanceof SliderElement) el.setHeight(18);
            if (el instanceof ModeElement combo) {
                combo.setWHeight(13);
                el.setHeight(combo.isOpen() ? 13 + (combo.getSetting().getModes().length * 12) : 13);
            }
            float elY = top + offY;
            if (elY + el.getHeight() >= top && elY <= spY + spH - 4f)
                el.render(context, mouseX, mouseY, delta);
            offY += el.getHeight();
        }
        Render2DEngine.popWindow();

        if (contentTotal <= 0.5f)
            FontRenderers.sf_medium_mini.drawString(context, "No configurable settings.", spX + 9f, top + 4f, DIM.getRGB());
    }

    private float elementHeight(AbstractElement el) {
        if (el instanceof ColorPickerElement picker) return picker.getHeight();
        if (el instanceof SliderElement) return 18;
        if (el instanceof ModeElement combo)
            return combo.isOpen() ? 13 + (combo.getSetting().getModes().length * 12) : 13;
        return 13;
    }

    private List<AbstractElement> getElements(Module module) {
        return elementCache.computeIfAbsent(module, this::buildElements);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private List<AbstractElement> buildElements(Module module) {
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

    private void openSettings(Module m, float x, float y) {
        settingsModule = m;
        scrollSettings = scrollSettingsTarget = 0f;
        spX = x;
        spY = y;
    }

    private void closeSettings() {
        settingsModule = null;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (settingsModule != null && Render2DEngine.isHovered(mouseX, mouseY, spX, spY, spW, spH)) {
            scrollSettingsTarget += (float) (verticalAmount * 16D);
            return true;
        }
        dWheel = (int) (verticalAmount * 5D);
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    @Override
    public boolean mouseClicked(net.minecraft.client.gui.Click click, boolean doubled) {
        double mouseX = click.x();
        double mouseY = click.y();
        int button = click.button();

        // ---- settings panel first (topmost) ----
        if (settingsModule != null) {
            if (Render2DEngine.isHovered(mouseX, mouseY, spCloseX - 4f, spCloseY - 4f, 14f, 14f)) {
                closeSettings();
                return true;
            }
            if (Render2DEngine.isHovered(mouseX, mouseY, spToggleX, spToggleY, spToggleW, spToggleH)) {
                settingsModule.toggle();
                return true;
            }
            if (Render2DEngine.isHovered(mouseX, mouseY, spX, spY, spW, spH)) {
                for (AbstractElement el : getElements(settingsModule))
                    if (el.isVisible())
                        el.mouseClicked((int) mouseX, (int) mouseY, button);
                return true;
            }
        }

        // ---- context menu ----
        if (menuOpen) {
            for (float[] r : menuRows) {
                if (Render2DEngine.isHovered(mouseX, mouseY, r[0], r[1], r[2], r[3])) {
                    Module m = hudModules.get((int) r[4]);
                    if (button == 0) {           // left-click: toggle element
                        m.toggle();
                    } else if (button == 1) {    // right-click: open its settings
                        openSettings(m, (float) mouseX + 6f, (float) mouseY);
                        menuOpen = false;
                    }
                    return true;
                }
            }
            // clicked off the menu -> close it (fall through to normal handling)
            menuOpen = false;
        }

        // ---- right-click an on-screen enabled HUD element -> its settings ----
        if (button == 1) {
            for (Module m : hudModules) {
                if (m.isEnabled() && m instanceof HudElement he && he.isHovering()) {
                    openSettings(m, (float) mouseX + 6f, (float) mouseY);
                    return true;
                }
            }
            // right-click on empty space -> open the element list menu at the cursor
            menuOpen = true;
            menuX = (float) mouseX;
            menuY = (float) mouseY;
            return true;
        }

        // ---- default: forward to (legacy) windows for dragging ----
        windows.forEach(w -> {
            w.mouseClicked((int) mouseX, (int) mouseY, button);
            windows.forEach(w1 -> {
                if (w.dragging && w != w1)
                    w1.dragging = false;
            });
        });
        return false;
    }

    @Override
    public boolean mouseReleased(net.minecraft.client.gui.Click click) {
        double mouseX = click.x();
        double mouseY = click.y();
        int button = click.button();
        if (settingsModule != null)
            for (AbstractElement el : getElements(settingsModule))
                el.mouseReleased((int) mouseX, (int) mouseY, button);
        windows.forEach(w -> w.mouseReleased((int) mouseX, (int) mouseY, button));
        return false;
    }

    @Override
    public boolean charTyped(net.minecraft.client.input.CharInput input) {
        if (settingsModule != null)
            for (AbstractElement el : getElements(settingsModule))
                el.charTyped((char) input.codepoint(), input.modifiers());
        return true;
    }

    @Override
    public boolean keyPressed(net.minecraft.client.input.KeyInput input) {
        int keyCode = input.key();
        if (settingsModule != null)
            for (AbstractElement el : getElements(settingsModule))
                el.keyTyped(keyCode);

        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            if (settingsModule != null) {
                closeSettings();
                return true;
            }
            if (menuOpen) {
                menuOpen = false;
                return true;
            }
            this.close();
            return true;
        }
        return false;
    }

    @Override
    public void removed() {
        for (List<AbstractElement> els : elementCache.values())
            els.forEach(AbstractElement::onClose);
        ThunderHack.EVENT_BUS.unsubscribe(this);
    }

    public void hudClicked(Module module) {
        for (AbstractCategory window : windows)
            window.hudClicked(module);
    }

    public static HudEditorGui getInstance() {
        if (instance == null)
            instance = new HudEditorGui();
        return instance;
    }

    public static HudEditorGui getHudGui() {
        return HudEditorGui.getInstance();
    }

    private void setInstance() {
        instance = this;
    }
}
