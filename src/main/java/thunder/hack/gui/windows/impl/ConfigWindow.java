package thunder.hack.gui.windows.impl;

import com.google.common.collect.Lists;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.util.InputUtil;
import net.minecraft.util.StringHelper;
import org.lwjgl.glfw.GLFW;
import thunder.hack.core.Managers;
import thunder.hack.features.modules.client.HudEditor;
import thunder.hack.gui.clickui.ClickGUI;
import thunder.hack.gui.clickui.impl.SliderElement;
import thunder.hack.gui.font.FontRenderers;
import thunder.hack.gui.windows.WindowBase;
import thunder.hack.setting.Setting;
import thunder.hack.setting.impl.PositionSetting;
import thunder.hack.utility.render.Render2DEngine;
import thunder.hack.utility.render.TextureStorage;

import java.awt.*;
import java.util.ArrayList;
import java.util.Objects;

import static thunder.hack.features.modules.Module.mc;
import static thunder.hack.features.modules.client.ClientSettings.isRu;

public class ConfigWindow extends WindowBase {
    private static ConfigWindow instance;

    private ArrayList<ConfigPlate> configPlates = new ArrayList<>();
    private int listeningId = -1;
    private String search = "Search", addName = "Name";

    public ConfigWindow(float x, float y, float width, float height, Setting<PositionSetting> position) {
        super(x, y, width, height, "Config", position, TextureStorage.configIcon);
        refresh();
    }

    public static ConfigWindow get(float x, float y, Setting<PositionSetting> position) {
        if (instance == null)
            instance = new ConfigWindow(x, y, 200, 180, position);
        instance.refresh();
        return instance;
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY) {
        super.render(context, mouseX, mouseY);

        int textColor = new Color(0xBDBDBD).getRGB();

        boolean hover1 = Render2DEngine.isHovered(mouseX, mouseY, getX() + getWidth() - 90, getY() + 3, 70, 10);

        WindowBase.field(context, getX() + getWidth() - 90, getY() + 3, 70, 10, hover1);
        FontRenderers.sf_medium_mini.drawString(context, search, getX() + getWidth() - 86, getY() + 7, new Color(0xD5D5D5).getRGB());

        if (configPlates.isEmpty())
            WindowBase.emptyState(context, getX() + getWidth() / 2f, getY() + getHeight() / 2f + 10,
                    isRu() ? "Тут пока пусто" : "Brak configow", isRu() ? "Впиши имя сверху" : "Wpisz nazwe u gory i kliknij +");
        String blink = (System.currentTimeMillis() / 240) % 2 == 0 ? "" : "   <<<<";
        String blink2 = (System.currentTimeMillis() / 240) % 2 == 0 ? "" : "l";

        {
            // Name
            boolean hover2 = Render2DEngine.isHovered(mouseX, mouseY, getX() + 11, getY() + 19, getWidth() - 28, 11);
            WindowBase.field(context, getX() + 11, getY() + 19, getWidth() - 28, 11, hover2);
            FontRenderers.sf_medium.drawString(context, addName + (listeningId == -3 ? blink2 : "")
                    , getX() + 13, getY() + 23, new Color(0xBDBDBD).getRGB());

            // Add
            boolean hover5 = Render2DEngine.isHovered(mouseX, mouseY, getX() + getWidth() - 15, getY() + 19, 11, 11);
            WindowBase.chip(context, getX() + getWidth() - 15, getY() + 19, 11, 11, hover5);
            FontRenderers.categories.drawString(context, "+", getX() + getWidth() - 12, getY() + 23, -1);
        }

        WindowBase.divider(context, getX() + 4, getY() + 33f, getWidth() + 2);

        Render2DEngine.addWindow(context, getX(), getY() + 38, getX() + getWidth() + 10, getY() + getHeight() - 1, 1f);
        for (ConfigPlate configPlate : configPlates) {
            float rowY = configPlate.offset + getY() + 36 + getScrollOffset();
            if (rowY > getY() + getHeight() || rowY + WindowBase.CARD_H < getY() + 36)
                continue;

            boolean active = Managers.CONFIG.currentConfig != null
                    && Objects.equals(configPlate.name() + ".gl", Managers.CONFIG.currentConfig.getName());
            float cardX = getX() + 6, cardW = getWidth() - 7;
            boolean hovered = Render2DEngine.isHovered(mouseX, mouseY, cardX, rowY, cardW, WindowBase.CARD_H);
            WindowBase.card(context, cardX, rowY, cardW, WindowBase.CARD_H, hovered, active);

            String initial = configPlate.name().isEmpty() ? "?" : configPlate.name().substring(0, 1).toUpperCase();
            WindowBase.letterBadge(context, cardX + 6, rowY + 4, 14, initial, active);

            float tx = cardX + 26;
            FontRenderers.sf_medium.drawString(context, configPlate.name(), tx, rowY + 5f, active ? -1 : textColor);
            FontRenderers.sf_medium_mini.drawString(context, configPlate.date() + "  .gl", tx, rowY + 14f, new Color(130, 134, 150).getRGB());
            if (active) {
                Color acc = HudEditor.getColor(0);
                float bw = FontRenderers.sf_medium_mini.getStringWidth("AKTYWNY") + 6;
                float bx = tx + FontRenderers.sf_medium.getStringWidth(configPlate.name()) + 4;
                if (bx + bw < getX() + getWidth() - 48) {
                    Render2DEngine.drawRound(context, bx, rowY + 4f, bw, 8, 4, Render2DEngine.injectAlpha(acc, 90));
                    FontRenderers.sf_medium_mini.drawString(context, "AKTYWNY", bx + 3, rowY + 6.8f, -1);
                }
            }

            // Load
            float loadX = getX() + getWidth() - 46, btnY = rowY + 5;
            boolean hover3 = Render2DEngine.isHovered(mouseX, mouseY, loadX, btnY, 26, 12);
            WindowBase.chip(context, loadX, btnY, 26, 12, hover3);
            FontRenderers.sf_medium_mini.drawCenteredString(context, "Load", loadX + 13, btnY + 4f, -1);

            // Delete
            float delX = getX() + getWidth() - 17;
            boolean hover5 = Render2DEngine.isHovered(mouseX, mouseY, delX, btnY, 12, 12);
            WindowBase.cross(context, delX + 6f, btnY + 6f, 8f, hover5);
        }        setMaxElementsHeight(configPlates.size() * WindowBase.CARD_STEP);
        Render2DEngine.popWindow();
    }

    @Override
    public void mouseClicked(double mouseX, double mouseY, int button) {
        if (Render2DEngine.isHovered(mouseX, mouseY, getX() + getWidth() - 90, getY() + 3, 70, 10)) {
            listeningId = -2;
            search = "";
            return;
        }

        if (Render2DEngine.isHovered(mouseX, mouseY, getX() + getWidth() - 15, getY() + 3, 10, 10)) {
            mc.setScreen(ClickGUI.getClickGui());
            return;
        }

        boolean hoveringName = Render2DEngine.isHovered(mouseX, mouseY, getX() + 11, getY() + 19, getWidth() - 28, 11);
        boolean hoveringAdd = Render2DEngine.isHovered(mouseX, mouseY, getX() + getWidth() - 15, getY() + 19, 11, 11);

        if (hoveringName) {
            if (listeningId != -3)
                addName = "";
            listeningId = -3;
            return;
        }

        if (hoveringAdd) {
            saveTyped();
            return;
        }

        ArrayList<ConfigPlate> copy = Lists.newArrayList(configPlates);
        for (ConfigPlate configPlate : copy) {
            float rowY = configPlate.offset + getY() + 36 + getScrollOffset();
            if (rowY > getY() + getHeight() || rowY + WindowBase.CARD_H < getY() + 36)
                continue;

            boolean hoveringRemove = Render2DEngine.isHovered(mouseX, mouseY, getX() + getWidth() - 17, rowY + 5, 12, 12);
            boolean hoverLoad = Render2DEngine.isHovered(mouseX, mouseY, getX() + getWidth() - 46, rowY + 5, 26, 12);

            if (hoverLoad) {
                Managers.CONFIG.load(configPlate.name());
                return;
            }

            if (hoveringRemove) {
                Managers.CONFIG.delete(configPlate.name());
                refresh();
                return;
            }
        }

        // nothing of ours was hit -> let the base window handle dragging / resizing / the scrollbar
        super.mouseClicked(mouseX, mouseY, button);
    }

    private void saveTyped() {
        if (addName.isEmpty() || addName.equals("Name"))
            return;
        Managers.CONFIG.save(addName);
        addName = "Name";
        listeningId = -1;
        refresh();
    }

    @Override
    protected boolean isTyping() {
        return listeningId != -1;
    }

    @Override
    public void keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_F && (InputUtil.isKeyPressed(mc.getWindow(), GLFW.GLFW_KEY_LEFT_CONTROL) || InputUtil.isKeyPressed(mc.getWindow(), GLFW.GLFW_KEY_RIGHT_CONTROL))) {
            listeningId = -2;
            return;
        }

        if (listeningId != -1) {
            switch (keyCode) {
                case GLFW.GLFW_KEY_ENTER -> {
                    if (listeningId == -3)
                        saveTyped();
                    else if (listeningId != -2)
                        listeningId = -1;
                }

                case GLFW.GLFW_KEY_ESCAPE -> {
                    if (listeningId == -2)
                        search = "Search";
                    listeningId = -1;
                    refresh();
                }

                case GLFW.GLFW_KEY_BACKSPACE -> {
                    if (listeningId == -2) {
                        search = SliderElement.removeLastChar(search);
                        refresh();
                        if (Objects.equals(search, "")) {
                            listeningId = -1;
                            search = "Search";
                        }
                        return;
                    }

                    if (listeningId == -3)
                        addName = SliderElement.removeLastChar(addName);
                }
            }
        }
    }

    @Override
    public void charTyped(char key, int keyCode) {
        if (StringHelper.isValidChar(key) && listeningId != -1) {
            if (listeningId == -2)
                search = search + key;

            if (listeningId == -3) {
                addName = addName + key;
            }

            refresh();
        }
    }

    private void refresh() {
        resetScroll();
        configPlates.clear();
        int id1 = 0;
        for (String s : Managers.CONFIG.getConfigList())
            if (search.equals("Search") || search.isEmpty() || s.contains(search)) {
                configPlates.add(new ConfigPlate(id1, id1 * WindowBase.CARD_STEP + 6, s, thunder.hack.core.manager.client.ConfigManager.getConfigDate(s)));
                id1++;
            }
    }

    private record ConfigPlate(int id, float offset, String name, String date) {
    }
}
