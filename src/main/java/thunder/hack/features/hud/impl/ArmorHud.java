package thunder.hack.features.hud.impl;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.ItemStack;
import thunder.hack.features.hud.HudElement;
import thunder.hack.gui.font.FontRenderer;
import thunder.hack.gui.font.FontRenderers;

/**
 * 1:1 port of Rockstar's {@code ru.rockstar.client.features.impl.visuals.ArmorHUD}: the worn armor sits right of
 * the crosshair column, above the hotbar, each piece with its durability bar and its remaining durability below it.
 * Unlike Rockstar (fixed above the hotbar) it sits at its HUD editor position, so it can be dragged; neverlose500_14 -> sf_bold_mini.
 */
public class ArmorHud extends HudElement {
    public ArmorHud() {
        super("ArmorHud", 40, 90);
    }

    // mc.player.inventory.armorInventory order: feet, legs, chest, head
    private static final EquipmentSlot[] ARMOR = {EquipmentSlot.FEET, EquipmentSlot.LEGS, EquipmentSlot.CHEST, EquipmentSlot.HEAD};

    public void onRender2D(DrawContext context) {
        super.onRender2D(context);
        if (fullNullCheck()) return;

        // Rockstar: x = width/2 - 90 + (9 - iteration) * 20 + 2, y above the hotbar; here the same 4 slots sit
        // from the HUD editor position (head left, boots right), so it can be dragged
        int i = (int) getPosX() - 12;
        int iteration = 0;
        int y = (int) getPosY();
        int shown = 0;
        FontRenderer font = FontRenderers.sf_bold_mini;
        for (EquipmentSlot slot : ARMOR) {
            ItemStack is = mc.player.getEquippedStack(slot);
            ++iteration;
            if (is.isEmpty()) continue;
            shown++;
            int x = i - 90 + (9 - iteration) * 20 + 2;
            context.drawItem(is, x, y);
            context.drawStackOverlay(mc.textRenderer, is, x, y, "");
            String s = is.getCount() > 1 ? is.getCount() + "" : "";
            drawStringWithShadow(context, font, s, x + 19 - 2 - font.getStringWidth(s), y + 20, 0xFFFFFFFF);
            int green = Math.abs(is.getMaxDamage() - is.getDamage());
            drawStringWithShadow(context, font, green + "", x + 8 - font.getStringWidth(green + "") / 2, y - -18, -1);
        }

        setBounds(getPosX(), y, shown == 0 ? 0 : 80, 26);
    }

    private static void drawStringWithShadow(DrawContext context, FontRenderer font, String s, float x, float y, int color) {
        if (s.isEmpty()) return;
        font.drawString(context, s, x + 0.5f, y + 0.5f, 0xFF3F3F3F);
        font.drawString(context, s, x, y, color);
    }
}
