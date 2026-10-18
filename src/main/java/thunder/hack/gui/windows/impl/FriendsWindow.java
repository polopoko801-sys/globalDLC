package thunder.hack.gui.windows.impl;

import com.google.common.collect.Lists;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.util.InputUtil;
import net.minecraft.util.Formatting;
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

public class FriendsWindow extends WindowBase {
    private static FriendsWindow instance;
    private ArrayList<FriendPlate> friendPlates = new ArrayList<>();
    private int listeningId = -1;
    private String search = "Search", addName = "Name";

    public FriendsWindow(float x, float y, float width, float height, Setting<PositionSetting> position) {
        super(x, y, width, height, "Friends", position, TextureStorage.playerIcon);
        refresh();
    }

    public static FriendsWindow get(float x, float y, Setting<PositionSetting> position) {
        if (instance == null)
            instance = new FriendsWindow(x, y, 200, 180, position);
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

        if (friendPlates.isEmpty())
            WindowBase.emptyState(context, getX() + getWidth() / 2f, getY() + getHeight() / 2f + 10,
                    isRu() ? "Тут пока пусто" : "Brak znajomych", isRu() ? "Впиши ник сверху" : "Wpisz nick u gory i kliknij +");
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

        java.util.Set<String> tab = new java.util.HashSet<>();
        if (mc.player != null && mc.player.networkHandler != null)
            for (var e : mc.player.networkHandler.getPlayerList()) tab.add(e.getProfile().name());

        for (FriendPlate friendPlate : friendPlates) {
            float rowY = friendPlate.offset + getY() + 36 + getScrollOffset();
            if (rowY > getY() + getHeight() || rowY + WindowBase.CARD_H < getY() + 36)
                continue;

            boolean inTab = tab.contains(friendPlate.name());
            boolean online = inTab || Managers.TELEMETRY.getOnlinePlayers().contains(friendPlate.name());
            float cardX = getX() + 6, cardW = getWidth() - 7;
            boolean hovered = Render2DEngine.isHovered(mouseX, mouseY, cardX, rowY, cardW, WindowBase.CARD_H);
            WindowBase.card(context, cardX, rowY, cardW, WindowBase.CARD_H, hovered, online);

            // avatar: real skin face when the server sent it, default skin for that nick otherwise
            float av = 14f, avX = cardX + 6, avY = rowY + 4;
            net.minecraft.util.Identifier skin = null;
            if (inTab && mc.getNetworkHandler() != null) {
                var entry = mc.getNetworkHandler().getPlayerListEntry(friendPlate.name());
                if (entry != null) skin = entry.getSkinTextures().body().texturePath();
            }
            if (skin == null) skin = thunder.hack.utility.render.HeadTextures.of(friendPlate.name(), null);
            if (skin != null) {
                thunder.hack.utility.render.GuiRender.preferNearest(skin);
                Render2DEngine.drawRound(context, avX - 1, avY - 1, av + 2, av + 2, 3, new Color(0, 0, 0, 90));
                thunder.hack.utility.render.HeadTextures.draw(context, skin, avX, avY, av);
            } else {
                WindowBase.letterBadge(context, avX, avY, av, friendPlate.name().substring(0, 1).toUpperCase(), online);
            }
            // status dot on the avatar's corner
            float dotX = avX + av - 3.5f, dotY = avY + av - 3.5f;
            if (online)
                Render2DEngine.drawBlurredShadow(context, dotX, dotY, 5, 5, 4, new Color(80, 230, 120, 140));
            Render2DEngine.drawRound(context, dotX - 0.8f, dotY - 0.8f, 6.6f, 6.6f, 3.3f, new Color(14, 15, 22));
            Render2DEngine.drawRound(context, dotX, dotY, 5, 5, 2.5f, online ? new Color(80, 230, 120) : new Color(120, 124, 140));

            FontRenderers.sf_medium.drawString(context, friendPlate.name(), avX + av + 6, rowY + 5f, online ? -1 : textColor);
            FontRenderers.sf_medium_mini.drawString(context, online ? (inTab ? "Online - na serwerze" : "Online") : "Offline",
                    avX + av + 6, rowY + 14f, (online ? new Color(110, 225, 140) : new Color(130, 134, 150)).getRGB());

            // Delete
            float delX = getX() + getWidth() - 17, delY = rowY + 5;
            boolean hover5 = Render2DEngine.isHovered(mouseX, mouseY, delX, delY, 12, 12);
            WindowBase.cross(context, delX + 6f, delY + 6f, 8f, hover5);
        }        setMaxElementsHeight(friendPlates.size() * WindowBase.CARD_STEP);
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
            addTyped();
            return;
        }

        ArrayList<FriendPlate> copy = Lists.newArrayList(friendPlates);
        for (FriendPlate friendPlate : copy) {
            float rowY = friendPlate.offset + getY() + 36 + getScrollOffset();
            if (rowY > getY() + getHeight() || rowY + WindowBase.CARD_H < getY() + 36)
                continue;

            boolean hoveringRemove = Render2DEngine.isHovered(mouseX, mouseY, getX() + getWidth() - 17, rowY + 5, 12, 12);

            if (hoveringRemove) {
                Managers.FRIEND.removeFriend(friendPlate.name());
                refresh();
                return;
            }
        }

        // nothing of ours was hit -> let the base window handle dragging / resizing / the scrollbar
        super.mouseClicked(mouseX, mouseY, button);
    }

    private void addTyped() {
        if (addName.isEmpty() || addName.equals("Name"))
            return;
        Managers.FRIEND.addFriend(addName);
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
                        addTyped();
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
        friendPlates.clear();
        int id1 = 0;
        for (String f : Managers.FRIEND.getFriends())
            if (search.equals("Search") || search.isEmpty() || f.contains(search)) {
                friendPlates.add(new FriendPlate(id1, id1 * WindowBase.CARD_STEP + 6, f));
                id1++;
            }
    }

    private record FriendPlate(int id, float offset, String name) {
    }
}
