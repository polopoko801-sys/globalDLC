package thunder.hack.gui.windows.impl;

import com.google.common.collect.Lists;
import net.minecraft.block.Block;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.resource.language.I18n;
import net.minecraft.client.util.InputUtil;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
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
import thunder.hack.setting.impl.ItemSelectSetting;
import thunder.hack.utility.render.Render2DEngine;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Objects;

import static thunder.hack.features.modules.Module.mc;
import static thunder.hack.features.modules.client.ClientSettings.isRu;

/**
 * The block / item picker.
 *
 * <p>It lists exactly ONE registry, chosen by the setting's {@link ItemSelectSetting.Kind}. It used to
 * walk both, and since nearly every block is also an item that meant nearly everything appeared twice -
 * searching "diamond_ore" returned two rows that looked identical and behaved differently, because only
 * one of them matched the id the module later looked up. Asking the setting which kind it wants removes
 * the duplicate at the source instead of trying to spot and merge it afterwards.
 *
 * <p>Rows are drawn as rounded plates that light up under the cursor, and a selected row is tinted with
 * the client accent, so what is in the list is readable at a glance rather than only from a small icon
 * at the right-hand end.
 */
public class ItemSelectWindow extends WindowBase {
    /** Row pitch, in pixels. */
    private static final int ROW = 20;

    private final Setting<ItemSelectSetting> itemSetting;
    private final ArrayList<ItemPlate> itemPlates = new ArrayList<>();
    private final ArrayList<ItemPlate> allItems = new ArrayList<>();

    private boolean allTab = true, listening = false;
    private String search = "";

    public ItemSelectWindow(Setting<ItemSelectSetting> itemSetting) {
        this(mc.getWindow().getScaledWidth() / 2f - 110, mc.getWindow().getScaledHeight() / 2f - 150, 220, 300, itemSetting);
    }

    public ItemSelectWindow(float x, float y, float width, float height, Setting<ItemSelectSetting> itemSetting) {
        super(x, y, width, height,
                (itemSetting.getValue().getKind() == ItemSelectSetting.Kind.Items ? "Items / " : "Blocks / ")
                        + Formatting.GRAY + itemSetting.getModule().getName(), null, null);
        this.itemSetting = itemSetting;
        refreshItemPlates();
        refreshAllItems();
    }

    private ItemSelectSetting.Kind kind() {
        return itemSetting.getValue().getKind();
    }

    private boolean wantsBlocks() {
        return kind() != ItemSelectSetting.Kind.Items;
    }

    private boolean wantsItems() {
        return kind() != ItemSelectSetting.Kind.Blocks;
    }

    /* ------------------------------------------------------------------ */
    /* Render                                                              */
    /* ------------------------------------------------------------------ */

    @Override
    public void render(DrawContext context, int mouseX, int mouseY) {
        super.render(context, mouseX, mouseY);

        Color accent = HudEditor.getColor(0);
        ArrayList<ItemPlate> shown = allTab ? allItems : itemPlates;

        drawSearchBox(context, mouseX, mouseY, accent);
        drawTabs(context, mouseX, mouseY, accent);

        if (shown.isEmpty()) {
            String message = allTab
                    ? (isRu() ? "Ничего не найдено" : "Nothing found")
                    : (isRu() ? "Тут пока пусто" : "It's empty here yet");
            FontRenderers.sf_medium.drawCenteredString(context, message,
                    getX() + getWidth() / 2f, getY() + getHeight() / 2f, new Color(0x8A8A8A).getRGB());
        }

        Render2DEngine.addWindow(context, getX(), getY() + 32, getX() + getWidth(), getY() + getHeight() - 1, 1f);

        for (ItemPlate plate : shown) {
            float rowY = plate.offset() + getY() + 34 + getScrollOffset();
            if (rowY > getY() + getHeight() || rowY + ROW < getY() + 32) continue;

            boolean selected = itemSetting.getValue().getItemsById().contains(plate.id());
            boolean hovered = Render2DEngine.isHovered(mouseX, mouseY, getX() + 4, rowY, getWidth() - 8, ROW - 2);

            // The plate. Selected rows carry the accent so the list reads at a glance; hovered rows only
            // lift slightly, because a hover that looks like a selection is worse than no hover at all.
            if (selected) {
                Render2DEngine.drawRound(context, getX() + 4, rowY, getWidth() - 8, ROW - 2, 3f,
                        Render2DEngine.injectAlpha(accent, hovered ? 110 : 82));
                Render2DEngine.drawGlassRim(context, getX() + 4, rowY, getWidth() - 8, ROW - 2, 3f, 0.7f, 0.55f);
            } else {
                WindowBase.field(context, getX() + 4, rowY, getWidth() - 8, ROW - 2, hovered);
            }

            context.getMatrices().pushMatrix();
            context.getMatrices().translate((float) (getX() + 8), rowY + 1);
            context.drawItem(plate.icon().getDefaultStack(), 0, 0);
            context.getMatrices().popMatrix();

            FontRenderers.sf_medium.drawString(context, plate.name(),
                    getX() + 30, rowY + 7, new Color(selected ? 0xFFFFFF : 0xC8C8C8).getRGB());

            // The toggle at the right-hand end: + to add, x to take back out.
            boolean toggleHover = Render2DEngine.isHovered(mouseX, mouseY, getX() + getWidth() - 22, rowY + 3, 12, 12);
            if (selected)
                WindowBase.cross(context, getX() + getWidth() - 16f, rowY + 9f, 8f, toggleHover);
            else
                WindowBase.chip(context, getX() + getWidth() - 22, rowY + 3, 12, 12, toggleHover);

            if (!selected)
                FontRenderers.categories.drawString(context, "+", getX() + getWidth() - 18.5f, rowY + 7, -1);
        }

        setMaxElementsHeight(shown.size() * ROW);
        Render2DEngine.popWindow();
    }

    private void drawSearchBox(DrawContext context, int mouseX, int mouseY, Color accent) {
        float x = getX() + getWidth() - 96, y = getY() + 3, w = 76, h = 11;
        boolean hovered = Render2DEngine.isHovered(mouseX, mouseY, x, y, w, h);

        if (listening) {
            Render2DEngine.drawRound(context, x, y, w, h, 3f, Render2DEngine.injectAlpha(accent, 95));
            Render2DEngine.drawGlassRim(context, x, y, w, h, 3f, 0.7f, 0.6f);
        } else {
            WindowBase.field(context, x, y, w, h, hovered);
        }

        String text = listening
                ? search + ((System.currentTimeMillis() / 500) % 2 == 0 ? "_" : "")
                : search.isEmpty() ? (isRu() ? "Поиск" : "Search") : search;

        FontRenderers.sf_medium_mini.drawString(context, text, x + 4, y + 4,
                new Color(listening || !search.isEmpty() ? 0xFFFFFF : 0x9A9A9A).getRGB());
    }

    private void drawTabs(DrawContext context, int mouseX, int mouseY, Color accent) {
        String left = wantsItems() && !wantsBlocks() ? "All items" : "All blocks";
        String right = "Selected " + itemSetting.getValue().getItemsById().size();

        drawTab(context, mouseX, mouseY, accent, getX() + 6, left, allTab);
        drawTab(context, mouseX, mouseY, accent, getX() + 6 + 66, right, !allTab);
    }

    private void drawTab(DrawContext context, int mouseX, int mouseY, Color accent, float x, String label, boolean active) {
        float y = getY() + 18, w = 62, h = 12;
        boolean hovered = Render2DEngine.isHovered(mouseX, mouseY, x, y, w, h);

        if (active) {
            Render2DEngine.drawRound(context, x, y, w, h, 3f, Render2DEngine.injectAlpha(accent, 130));
            Render2DEngine.drawGlassRim(context, x, y, w, h, 3f, 0.7f, 0.55f);
        } else {
            WindowBase.field(context, x, y, w, h, hovered);
        }

        FontRenderers.sf_medium_mini.drawCenteredString(context, label, x + w / 2f, y + 4.5f,
                new Color(active ? 0xFFFFFF : 0xB0B0B0).getRGB());
    }

    /* ------------------------------------------------------------------ */
    /* Input                                                               */
    /* ------------------------------------------------------------------ */

    @Override
    public void mouseClicked(double mouseX, double mouseY, int button) {
        if (Render2DEngine.isHovered(mouseX, mouseY, getX() + 6, getY() + 18, 62, 12)) {
            allTab = true;
            resetScroll();
            Managers.SOUND.playBoolean();
        }

        if (Render2DEngine.isHovered(mouseX, mouseY, getX() + 72, getY() + 18, 62, 12)) {
            allTab = false;
            resetScroll();
            Managers.SOUND.playBoolean();
        }

        if (Render2DEngine.isHovered(mouseX, mouseY, getX() + getWidth() - 96, getY() + 3, 76, 11)) {
            listening = true;
            Managers.SOUND.playBoolean();
        }

        if (Render2DEngine.isHovered(mouseX, mouseY, getX() + getWidth() - 15, getY() + 3, 10, 10))
            mc.setScreen(ClickGUI.getClickGui());

        for (ItemPlate plate : Lists.newArrayList(allTab ? allItems : itemPlates)) {
            float rowY = plate.offset() + getY() + 34 + getScrollOffset();
            if (rowY > getY() + getHeight() || rowY + ROW < getY() + 32) continue;

            // The whole row toggles, not just the little square at the end - a 12-pixel target in a
            // list this long is a lot of precision to ask for.
            if (!Render2DEngine.isHovered(mouseX, mouseY, getX() + 4, rowY, getWidth() - 8, ROW - 2)) continue;

            if (itemSetting.getValue().getItemsById().contains(plate.id()))
                itemSetting.getValue().getItemsById().remove(plate.id());
            else
                itemSetting.getValue().getItemsById().add(plate.id());

            refreshItemPlates();
            Managers.SOUND.playScroll();
            return;
        }
    }

    @Override
    public void keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_F && (InputUtil.isKeyPressed(mc.getWindow(), GLFW.GLFW_KEY_LEFT_CONTROL) || InputUtil.isKeyPressed(mc.getWindow(), GLFW.GLFW_KEY_RIGHT_CONTROL))) {
            listening = !listening;
            return;
        }

        if (!listening) return;

        switch (keyCode) {
            case GLFW.GLFW_KEY_ESCAPE -> {
                listening = false;
                search = "";
                refreshAllItems();
            }
            case GLFW.GLFW_KEY_ENTER -> listening = false;
            case GLFW.GLFW_KEY_BACKSPACE -> {
                search = SliderElement.removeLastChar(search);
                refreshAllItems();
            }
            case GLFW.GLFW_KEY_SPACE -> {
                search = search + " ";
                refreshAllItems();
            }
        }
    }

    @Override
    public void charTyped(char key, int keyCode) {
        if (StringHelper.isValidChar(key) && listening) {
            search = search + key;
            refreshAllItems();
        }
    }

    /* ------------------------------------------------------------------ */
    /* Lists                                                               */
    /* ------------------------------------------------------------------ */

    /** The id a module stores and looks up: the translation key with its namespace prefix stripped. */
    private static String idOf(String translationKey) {
        return translationKey.replace("block.minecraft.", "").replace("item.minecraft.", "");
    }

    private void refreshItemPlates() {
        itemPlates.clear();
        int index = 0;

        if (wantsBlocks())
            for (Block block : Registries.BLOCK) {
                String id = idOf(block.getTranslationKey());
                if (!itemSetting.getValue().getItemsById().contains(id)) continue;
                itemPlates.add(plate(index++, iconFor(block), id, block.getTranslationKey()));
            }

        if (wantsItems())
            for (Item item : Registries.ITEM) {
                String id = idOf(item.getTranslationKey());
                if (!itemSetting.getValue().getItemsById().contains(id)) continue;
                // A Both-kind setting would otherwise list a block and its item under the same id.
                if (wantsBlocks() && itemPlates.stream().anyMatch(p -> Objects.equals(p.id(), id))) continue;
                itemPlates.add(plate(index++, item, id, item.getTranslationKey()));
            }
    }

    private void refreshAllItems() {
        allItems.clear();
        resetScroll();
        int index = 0;

        if (wantsBlocks())
            for (Block block : Registries.BLOCK) {
                if (!matches(block.getTranslationKey())) continue;
                allItems.add(plate(index++, iconFor(block), idOf(block.getTranslationKey()), block.getTranslationKey()));
            }

        if (wantsItems())
            for (Item item : Registries.ITEM) {
                if (!matches(item.getTranslationKey())) continue;
                String id = idOf(item.getTranslationKey());
                if (wantsBlocks() && allItems.stream().anyMatch(p -> Objects.equals(p.id(), id))) continue;
                allItems.add(plate(index++, item, id, item.getTranslationKey()));
            }
    }

    private boolean matches(String translationKey) {
        if (search.isEmpty()) return true;
        String needle = search.toLowerCase().replace(' ', '_');
        return translationKey.toLowerCase().contains(needle)
                || I18n.translate(translationKey).toLowerCase().contains(search.toLowerCase());
    }

    /** Air-backed blocks (fire, water, piston heads) have no item to draw, so fall back to something. */
    private static Item iconFor(Block block) {
        Item item = block.asItem();
        return item == Items.AIR ? Items.BARRIER : item;
    }

    private static ItemPlate plate(int index, Item icon, String id, String translationKey) {
        return new ItemPlate(index * ROW, icon, id, I18n.translate(translationKey));
    }

    private record ItemPlate(float offset, Item icon, String id, String name) {
    }
}
