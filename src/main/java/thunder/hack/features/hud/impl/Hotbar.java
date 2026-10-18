package thunder.hack.features.hud.impl;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import thunder.hack.gui.font.FontRenderers;
import thunder.hack.features.hud.HudElement;
import thunder.hack.gui.windows.WindowsScreen;
import thunder.hack.features.modules.client.HudEditor;
import thunder.hack.setting.Setting;
import thunder.hack.utility.render.Render2DEngine;

import java.awt.*;

public class Hotbar extends HudElement {
    @Override
    protected boolean draggable() {
        return false;
    }

    public Hotbar() {
        super("Hotbar", 0, 0);
    }

    public static final Setting<Mode> lmode = new Setting<>("LeftHandMode", Mode.Merged);

    private float animSel = -1f; // smoothed selected-slot index for the sliding highlight

    public enum Mode {
        Merged, Separately
    }

    public void onRender2D(DrawContext context) {
        if (mc.currentScreen instanceof WindowsScreen)
            return;

        PlayerEntity playerEntity = mc.player;
        if (playerEntity == null) return;

        int i = mc.getWindow().getScaledWidth() / 2;
        float y = mc.getWindow().getScaledHeight() - 25;
        boolean off = !playerEntity.getOffHandStack().isEmpty();

        // custom frosted-glass hotbar
        float px, pw;
        if (off && lmode.getValue() == Mode.Merged) {
            px = i - 111;
            pw = 201;
        } else {
            px = i - 90;
            pw = 180;
        }

        Render2DEngine.drawRoundedBlur(context, px, y, pw, 20, 5f, new Color(255, 255, 255, 16));
        Render2DEngine.drawRound(context, px, y, pw, 20, 5f, new Color(16, 16, 20, 125));

        // offhand glass cell
        if (off && lmode.getValue() == Mode.Merged) {
            Render2DEngine.drawRound(context, i - 111 + 2, y + 2, 16, 16, 3.5f, new Color(255, 255, 255, 22));
        } else if (off) {
            Render2DEngine.drawRoundedBlur(context, i - 112.5f, y, 20, 20, 5f, new Color(255, 255, 255, 16));
            Render2DEngine.drawRound(context, i - 112.5f, y, 20, 20, 5f, new Color(16, 16, 20, 125));
            Render2DEngine.drawRound(context, i - 112.5f + 2, y + 2, 16, 16, 3.5f, new Color(255, 255, 255, 22));
        }

        // individual glass slot cells
        int selected = playerEntity.getInventory().selectedSlot;
        for (int m = 0; m < 9; m++) {
            float sx = i - 89 + m * 20f;
            Render2DEngine.drawRound(context, sx, y + 2, 18, 16, 3.5f, new Color(255, 255, 255, 20));
        }

        // smooth-sliding selected-slot highlight (eases toward the current slot each frame)
        if (animSel < 0f) animSel = selected;
        animSel += (selected - animSel) * 0.35f;
        float hx = i - 89 + animSel * 20f;
        Color accent = HudEditor.getColor(0);
        Render2DEngine.drawRound(context, hx, y + 2, 18, 16, 3.5f, new Color(255, 255, 255, 48));
        Render2DEngine.drawRound(context, hx, y + 16.5f, 18, 1.4f, 0.6f, accent);
    }

    // Bake only items
    public static void renderHotBarItems(float tickDelta, DrawContext context) {
        if (mc.currentScreen instanceof WindowsScreen)
            return;

        PlayerEntity playerEntity = mc.player;
        if (playerEntity != null) {

            DrawContext matrices = context;
            int i = mc.getWindow().getScaledWidth() / 2;
            int o = mc.getWindow().getScaledHeight() - 16 - 3;

            if (mc.player.getOffHandStack().isEmpty()) {
            } else if (lmode.getValue() == Mode.Merged) {
                renderHotbarItem(context, i - 109, o - 5, playerEntity.getOffHandStack());
            } else {
                renderHotbarItem(context, i - 111, o - 5, playerEntity.getOffHandStack());
            }

            for (int m = 0; m < 9; ++m) {
                int n = i - 90 + m * 20 + 2;
                if (m == mc.player.getInventory().selectedSlot)
                    renderHotbarItem(context, n, o - 7, playerEntity.getInventory().main.get(m));
                else renderHotbarItem(context, n, o - 5, playerEntity.getInventory().main.get(m));
            }
        }
    }

    private static void renderHotbarItem(DrawContext context, int i, int j, ItemStack itemStack) {
        if (!itemStack.isEmpty()) {
            context.getMatrices().pushMatrix();
            context.getMatrices().translate((float)((float) (i + 8)), (float)( (float) (j + 12)));
            context.getMatrices().scale((float)(0.9f), (float)( 0.9f));
            context.getMatrices().translate((float)((float) (-(i + 8))), (float)( (float) (-(j + 12))));
            context.drawItem(itemStack, i, j);
            context.drawItem(itemStack, i, j);
            context.getMatrices().popMatrix();

            // vanilla stack overlay = stack count (e.g. how many blocks) + durability bar + cooldown,
            // which the custom hotbar previously dropped by only drawing the icon
            context.drawStackOverlay(mc.textRenderer, itemStack, i, j);

            // extra: remaining-durability number for damageable tools/weapons/armor, green -> red
            if (itemStack.isDamageable() && itemStack.isDamaged()) {
                int rem = itemStack.getMaxDamage() - itemStack.getDamage();
                float frac = Math.max(0f, Math.min(1f, (float) rem / itemStack.getMaxDamage()));
                int col = new Color((int) (255 * (1 - frac)), (int) (200 * frac), 40).getRGB();
                String s = String.valueOf(rem);
                FontRenderers.sf_bold_mini.drawString(context, s, i + 16 - FontRenderers.sf_bold_mini.getStringWidth(s), j - 3, col);
            }
        }
    }

    public static void renderXpBar(int x, DrawContext matrices) {
        net.minecraft.util.profiler.Profilers.get().push("expBar");
        int k;
        int l;
        net.minecraft.util.profiler.Profilers.get().pop();

        if (mc.player.experienceLevel > 0) {
            net.minecraft.util.profiler.Profilers.get().push("expLevel");
            String string = "" + mc.player.experienceLevel;
            k = (int) ((mc.getWindow().getScaledWidth() - FontRenderers.sf_bold_mini.getStringWidth(string)) / 2);
            l = mc.getWindow().getScaledHeight() - 31 - 4;
            FontRenderers.sf_bold_mini.drawString(matrices, string, k, l, 8453920);
            net.minecraft.util.profiler.Profilers.get().pop();
        }
    }
}
