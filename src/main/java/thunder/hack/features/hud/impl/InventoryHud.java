package thunder.hack.features.hud.impl;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.item.ItemStack;
import thunder.hack.features.hud.HudElement;
import thunder.hack.features.modules.client.HudEditor;
import thunder.hack.gui.font.FontRenderers;
import thunder.hack.setting.Setting;
import thunder.hack.utility.player.InventoryUtility;
import thunder.hack.utility.render.Render2DEngine;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;

/**
 * Twoje własny ekwipunek jako siatka slotów w HUD - dziewięć kolumn, w każdej ikona przedmiotu i licznik
 * stacks w rogu, dokładnie tak jak na vanillowym pasku. Można przeciągać w edytorze HUD jak każdy inny
 * element.
 *
 * <p>Domyślnie ( {@link Mode#Main} ) rysowane są dokładnie trzy rzędy kieszeni, tak jak na ekranie
 * minecraftowym - sloty 9-35; hotbar jest w minecraftowym ekranie osobno, więc w HUD też jest osobnym
 * trybem, a nie czwartym rzędem. Wiersz hotbara jako jedyny dostaje znacznik akcentu na aktualnie
 * wybranym slocie, więc widać od razu, co trzymasz w ręce.
 *
 * <p>Wygląd: panel ma miękk cień, mrożone ciało i 1px ramkę, a każdy slot nie jest płaskim kwadratem tylko
 * pionowym gradientem (jaśniejszy wierzch, ciemniejszy spód) - tak komórki wyglądają jak wciśnięte szkło.
 * Najechanie myszką rozjaśnia komórkę i dostawia pod nią nazwę przedmiotu, a zaklęte itemy dostają fioletowy
 * odcień.
 */
public class InventoryHud extends HudElement {
    private static final int COLUMNS = 9;
    private static final float ICON = 16f;
    private static final float PADDING = 3f;

    private static final Color FRAME = new Color(255, 255, 255, 26);
    private static final Color BODY = new Color(14, 15, 20, 152);
    private static final Color SHADOW = new Color(0, 0, 0, 140);

    public enum Mode {
        /** Tylko trzy rzędy kieszeni - tak jak w minecraftowym ekranie ekwipunku. */
        Main,
        /** Tylko hotbar - jeden wiersz. */
        Hotbar,
        /** Hotbar + trzy rzędy kieszeni. */
        HotbarMain,
        /** Zbroja i ręka dodatkowa nad hotbarem. */
        Full
    }

    private final Setting<Mode> mode = new Setting<>("Mode", Mode.Main);
    private final Setting<Float> slotSize = new Setting<>("SlotSize", 18f, 12f, 28f);
    private final Setting<Float> gap = new Setting<>("Gap", 1f, 0f, 4f);
    private final Setting<Boolean> emptySlots = new Setting<>("EmptySlots", true);
    private final Setting<Boolean> stackCount = new Setting<>("StackCount", true);
    private final Setting<Boolean> durability = new Setting<>("Durability", true);
    private final Setting<Boolean> selectedSlot = new Setting<>("SelectedSlot", true);
    private final Setting<Boolean> tooltips = new Setting<>("Tooltip", true);
    /** A title row over the slots, like the Potions / KeyBinds glass panels: name on the left, an icon on the right. */
    private final Setting<Boolean> showHeader = new Setting<>("Header", true);
    private final Setting<String> title = new Setting<>("Title", "Inventory", v -> showHeader.getValue());

    private static final float HEADER_H = 16f;
    private static final float HEADER_ICON = 9f;

    private float header() {
        return showHeader.getValue() ? HEADER_H : 0f;
    }

    public InventoryHud() {
        // domyslny rozmiar to 3 rzędy kieszeni x 9 kolumn, przy domyslnych SlotSize=18 i Gap=1
        super("InventoryHud", (int) (COLUMNS * 19f - 1f + PADDING * 2f), (int) (3 * 19f - 1f + PADDING * 2f), 0.015f, 0.02f);
        setEnabled(true); // wlaczone od razu; zapisany config i tak ma pierwszenstwo
    }

    @Override
    public void onRender2D(DrawContext context) {
        super.onRender2D(context);
        if (mc.player == null) return;

        float slot = slotSize.getValue();
        float step = slot + gap.getValue();
        List<ItemStack[]> rows = rows();

        float x = getPosX(), y = getPosY();
        float width = COLUMNS * step - gap.getValue() + PADDING * 2f;
        float height = rows.size() * step - gap.getValue() + PADDING * 2f + header();
        setBounds(x, y, width, height);

        float panelRadius = Math.min(HudEditor.hudRound.getValue(), 6f);
        float slotRadius = Math.min(HudEditor.hudRound.getValue(), slot / 2f);

        // panel: miękki cień -> mrożone szkło -> ramka -> ciało 1px w środku ramki
        Render2DEngine.drawBlurredShadow(context, x, y, width, height, 9, new Color(0, 0, 0, 130));
        Render2DEngine.drawRoundedBlur(context, x, y, width, height, panelRadius, new Color(255, 255, 255, 14));
        Render2DEngine.drawRound(context, x, y, width, height, panelRadius, FRAME);
        Render2DEngine.drawRound(context, x + 1f, y + 1f, width - 2f, height - 2f, Math.max(1f, panelRadius - 1f), BODY);

        if (showHeader.getValue()) {
            FontRenderers.sf_medium.drawString(context, title.getValue(), x + PADDING + 3f, y + 6f,
                    new Color(255, 255, 255, 235).getRGB());
            int s = Math.round(HEADER_ICON);
            context.drawTexture(net.minecraft.client.gl.RenderPipelines.GUI_TEXTURED, thunder.hack.utility.render.TextureStorage.backpackIcon,
                    Math.round(x + width - PADDING - 3f - HEADER_ICON), Math.round(y + (HEADER_H - HEADER_ICON) / 2f + 1f),
                    0, 0, s, s, s, s);
        }

        int hotbarRow = hotbarRow();
        int selected = selectedSlot.getValue() ? mc.player.getInventory().selectedSlot : -1;
        int hoverColumn = hoveredCell(step, COLUMNS, rows.size(), false);
        int hoverRow = hoveredCell(step, COLUMNS, rows.size(), true);

        for (int row = 0; row < rows.size(); row++) {
            ItemStack[] stacks = rows.get(row);

            for (int column = 0; column < COLUMNS; column++) {
                // a slot can come back null (e.g. the HUD editor preview before the inventory exists) - treat it as empty
                ItemStack stack = column < stacks.length && stacks[column] != null ? stacks[column] : ItemStack.EMPTY;
                float sx = x + PADDING + column * step;
                float sy = y + header() + PADDING + row * step;
                boolean isSelected = column == selected && row == hotbarRow;
                boolean isHovered = column == hoverColumn && row == hoverRow;

                if (emptySlots.getValue() || !stack.isEmpty() || isSelected || isHovered) {
                    // kolorowa obwódka pod komórką = akcentowa hairline przy najechaniu
                    if (isHovered)
                        Render2DEngine.drawRound(context, sx - 0.5f, sy - 0.5f, slot + 1f, slot + 1f, slotRadius + 0.5f,
                                Render2DEngine.injectAlpha(HudEditor.getColor(0), 150));

                    Color[] cell = cellColors(stack, isSelected, isHovered);
                    Render2DEngine.renderRoundedGradientRect(context, cell[0], cell[1], cell[0], cell[1], sx, sy, slot, slot, slotRadius);
                }

                if (stack.isEmpty()) continue;

                float offset = (slot - ICON) / 2f;
                context.drawItem(stack, Math.round(sx + offset), Math.round(sy + offset));

                if (stackCount.getValue() && stack.getCount() > 1)
                    drawStackCount(context, String.valueOf(stack.getCount()), sx, sy, slot);

                if (durability.getValue() && stack.isDamageable() && stack.isDamaged())
                    drawDurability(context, stack, sx, sy, slot, isSelected);

                if (isSelected)
                    Render2DEngine.drawRound(context, sx + 1f, sy + slot - 1.5f, slot - 2f, 1.5f, 0.75f, HudEditor.getColor(0));
            }
        }

        if (tooltips.getValue() && hoverRow >= 0 && hoverColumn >= 0) {
            ItemStack[] row = rows.get(hoverRow);
            ItemStack hovered = hoverColumn < row.length && row[hoverColumn] != null ? row[hoverColumn] : ItemStack.EMPTY;
            if (!hovered.isEmpty())
                drawTooltip(context, hovered, x, y + height + 3f);
        }
    }

    /** Kolory [góra, dół] komórki - pionowy gradient daje wrażenie wciśniętego szkła. */
    private Color[] cellColors(ItemStack stack, boolean isSelected, boolean isHovered) {
        if (isSelected) return new Color[]{new Color(255, 255, 255, 62), new Color(255, 255, 255, 26)};
        if (isHovered) return new Color[]{new Color(255, 255, 255, 50), new Color(255, 255, 255, 18)};
        if (stack.isEmpty()) return new Color[]{new Color(255, 255, 255, 20), new Color(255, 255, 255, 7)};
        if (isEnchanted(stack)) return new Color[]{new Color(206, 150, 255, 46), new Color(150, 90, 225, 16)};
        return new Color[]{new Color(255, 255, 255, 34), new Color(255, 255, 255, 12)};
    }

    private static boolean isEnchanted(ItemStack stack) {
        return !EnchantmentHelper.getEnchantments(stack).getEnchantments().isEmpty();
    }

    /** Kolumna albo wiersz komórki pod kursorem; -1 gdy mysz jest poza panelem. */
    private int hoveredCell(float step, int columns, int rowCount, boolean vertical) {
        if (!isHovering()) return -1;

        float origin = (vertical ? getPosY() + header() : getPosX()) + PADDING;
        float along = (vertical ? normaliseY() : normaliseX()) - origin;
        int cell = (int) Math.floor(along / step);
        int limit = vertical ? rowCount : columns;
        return cell >= 0 && cell < limit ? cell : -1;
    }

    /** Liczba stacksow doczepiona do prawego dolnego rogu slotu, z cieniem pod spodem. */
    private void drawStackCount(DrawContext context, String text, float sx, float sy, float slot) {
        float textWidth = FontRenderers.sf_bold_mini.getStringWidth(text);
        float textHeight = FontRenderers.sf_bold_mini.getStringHeight(text);
        // drawString przesuwa ramke glifu o 3px w gore, wiec przy dolnym wyrównaniu trzeba to odczytac
        float tx = sx + slot - 1f - textWidth;
        float ty = sy + slot + 2f - textHeight;
        FontRenderers.sf_bold_mini.drawString(context, text, tx + 1f, ty + 1f, SHADOW);
        FontRenderers.sf_bold_mini.drawString(context, text, tx, ty, Color.WHITE.getRGB());
    }

    /** Pasek zuzycia przedmiotu: zielony w pelni, czerwony przy zerze. */
    private void drawDurability(DrawContext context, ItemStack stack, float sx, float sy, float slot, boolean lifted) {
        int maxDamage = stack.getMaxDamage();
        if (maxDamage <= 0) return;

        float left = Math.max(0f, (maxDamage - stack.getDamage()) / (float) maxDamage);
        // na wybranym slocie u gory siedzi akcentowa kreska, więc pasek zuzycia idzie wyżej
        float barY = sy + slot - (lifted ? 4.5f : 2.5f);
        Render2DEngine.drawRound(context, sx + 1f, barY, slot - 2f, 1.5f, 0.75f, new Color(0, 0, 0, 90));
        Render2DEngine.drawRound(context, sx + 1f, barY, Math.max(1.5f, (slot - 2f) * left), 1.5f, 0.75f,
                new Color((int) (255 * (1 - left)), (int) (200 * left), 40));
    }

    /** Podpis przedmiotu pod panelem, przycięty do ekranu. */
    private void drawTooltip(DrawContext context, ItemStack stack, float x, float y) {
        String name = stack.getCount() > 1
                ? stack.getName().getString() + " x" + stack.getCount()
                : stack.getName().getString();

        float width = FontRenderers.sf_bold_mini.getStringWidth(name) + 12f;
        float height = 13f;
        float screenWidth = mc.getWindow().getScaledWidth();
        float screenHeight = mc.getWindow().getScaledHeight();
        float tx = Math.min(Math.max(x, 2f), Math.max(2f, screenWidth - width - 2f));
        float ty = Math.min(y, Math.max(2f, screenHeight - height - 2f));

        Render2DEngine.drawRoundedBlur(context, tx, ty, width, height, 3.5f, new Color(255, 255, 255, 14));
        Render2DEngine.drawRound(context, tx, ty, width, height, 3.5f, BODY);
        FontRenderers.sf_bold_mini.drawString(context, name, tx + 6f, ty + 3f, Color.WHITE.getRGB());
    }

    /** Sloty do narysowania, wierszami - kazdy wiersz ma 9 kolumn. */
    private List<ItemStack[]> rows() {
        List<ItemStack[]> rows = new ArrayList<>(4);

        if (mode.is(Mode.Full)) {
            // InventoryUtility zwraca armor w kolejnosci [buty, nogi, tors, helm]
            List<ItemStack> armor = InventoryUtility.getArmor(mc.player);
            ItemStack[] equipped = new ItemStack[COLUMNS];
            for (int i = 0; i < 4; i++)
                equipped[i] = armor.get(3 - i);
            equipped[4] = mc.player.getOffHandStack();
            rows.add(equipped);
        }

        if (!mode.is(Mode.Main))
            rows.add(row(0));
        if (!mode.is(Mode.Hotbar))
            for (int first = 9; first < 36; first += COLUMNS)
                rows.add(row(first));

        return rows;
    }

    private ItemStack[] row(int firstSlot) {
        ItemStack[] row = new ItemStack[COLUMNS];
        for (int column = 0; column < COLUMNS; column++)
            row[column] = mc.player.getInventory().main.get(firstSlot + column);
        return row;
    }

    /**
     * Numer wiersza hotbara w liscie {@link #rows()} - ten jedyny dostaje znacznik wybranego slotu.
     * W trybie {@link Mode#Main} hotbara nie ma wcale, wiec -1 i nic nie jest podswietlone.
     */
    private int hotbarRow() {
        if (mode.is(Mode.Main)) return -1;
        return mode.is(Mode.Full) ? 1 : 0;
    }
}