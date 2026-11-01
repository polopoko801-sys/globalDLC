package thunder.hack.features.hud.impl;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import thunder.hack.features.hud.HudElement;
import thunder.hack.features.modules.client.HudEditor;
import thunder.hack.gui.font.FontRenderers;
import thunder.hack.gui.windows.WindowsScreen;
import thunder.hack.setting.Setting;
import thunder.hack.utility.render.Render2DEngine;
import thunder.hack.utility.render.animation.AnimationUtility;

import java.awt.*;

/**
 * Frosted-glass hotbar.
 *
 * <p>The selection is an accent-lit pill that glides between slots and stretches towards where it is going; the
 * held item rises and grows a little; a slim XP line runs along the top; nearly broken tools glow red; small slot
 * numbers sit in the corners. Every animation runs on real frame time, so it moves the same at any FPS.
 */
public class Hotbar extends HudElement {
    @Override
    protected boolean draggable() {
        return false;
    }

    public Hotbar() {
        super("Hotbar", 0, 0);
    }

    public static final Setting<Mode> lmode = new Setting<>("LeftHandMode", Mode.Merged);
    public static final Setting<Boolean> slotNumbers = new Setting<>("SlotNumbers", true);
    public static final Setting<Boolean> xpLine = new Setting<>("XpLine", true);

    public enum Mode {
        Merged, Separately
    }

    /** Smoothed selected index (the gliding pill), and per-slot "is selected" 0..1 for the item lift. */
    private static float animSel = -1f;
    private static final float[] lift = new float[9];
    private static float xpShown = -1f;

    private static final float SLOT = 20f;

    public void onRender2D(DrawContext context) {
        if (mc.currentScreen instanceof WindowsScreen) return;
        PlayerEntity player = mc.player;
        if (player == null) return;

        int cx = mc.getWindow().getScaledWidth() / 2;
        float y = mc.getWindow().getScaledHeight() - 25;
        boolean off = !player.getOffHandStack().isEmpty();
        boolean merged = off && lmode.getValue() == Mode.Merged;
        Color accent = HudEditor.getColor(0);

        float px = merged ? cx - 111 : cx - 90, pw = merged ? 201 : 180;

        // panel: blur + dark glass + a faint top highlight
        Render2DEngine.drawRoundedBlur(context, px, y, pw, 20, 6f, new Color(255, 255, 255, 16));
        Render2DEngine.drawRound(context, px, y, pw, 20, 6f, new Color(14, 14, 19, 135));
        Render2DEngine.drawRound(context, px + 3, y + 0.5f, pw - 6, 1f, 0.5f, new Color(255, 255, 255, 22));

        // offhand cell
        if (merged) {
            slotCell(context, cx - 109, y + 2, player.getOffHandStack(), false);
        } else if (off) {
            Render2DEngine.drawRoundedBlur(context, cx - 112.5f, y, 20, 20, 6f, new Color(255, 255, 255, 16));
            Render2DEngine.drawRound(context, cx - 112.5f, y, 20, 20, 6f, new Color(14, 14, 19, 135));
            slotCell(context, cx - 110.5f, y + 2, player.getOffHandStack(), false);
        }

        // slots
        int selected = player.getInventory().getSelectedSlot();
        for (int m = 0; m < 9; m++) {
            float sx = cx - 89 + m * SLOT;
            slotCell(context, sx, y + 2, player.getInventory().getStack(m), true);
            if (slotNumbers.getValue())
                FontRenderers.sf_medium_mini.drawString(context, String.valueOf(m + 1), sx + 1.8f, y + 3.2f,
                        new Color(255, 255, 255, m == selected ? 150 : 55).getRGB());
        }

        // the selection pill: glides, and stretches towards the slot it is heading for while it travels
        if (animSel < 0f) animSel = selected;
        animSel = AnimationUtility.fast(animSel, selected, 16f);
        float gap = selected - animSel;
        float stretch = Math.min(10f, Math.abs(gap) * 6f);
        float hx = cx - 89 + animSel * SLOT - (gap < 0 ? stretch : 0);
        float hw = 18 + stretch;
        Render2DEngine.drawBlurredShadow(context, hx, y + 2, hw, 16, 10, Render2DEngine.injectAlpha(accent, 70));
        Render2DEngine.drawRound(context, hx, y + 2, hw, 16, 4f, Render2DEngine.injectAlpha(accent, 46));
        Render2DEngine.drawRound(context, hx + 0.5f, y + 2.5f, hw - 1f, 15f, 3.6f, new Color(255, 255, 255, 26));
        Render2DEngine.drawRound(context, hx + 4f, y + 16.3f, hw - 8f, 1.4f, 0.7f, accent);

        // XP: a slim accent line on the panel's top edge, filling smoothly
        if (xpLine.getValue()) {
            float target = Math.clamp(player.experienceProgress, 0f, 1f);
            if (xpShown < 0f || target < xpShown - 0.5f) xpShown = target;   // level up: start over, no rewind
            xpShown = AnimationUtility.fast(xpShown, target, 8f);
            float lw = (pw - 12f) * xpShown;
            if (lw > 0.5f) {
                Color xp = new Color(128, 255, 32);
                Render2DEngine.drawBlurredShadow(context, px + 6, y - 2.2f, lw, 1.6f, 4, Render2DEngine.injectAlpha(xp, 90));
                Render2DEngine.drawRound(context, px + 6, y - 2f, lw, 1.4f, 0.7f, xp);
            }
        }
    }

    /** One glass slot; a bit brighter with an item in it, glowing red when the item is about to break. */
    private static void slotCell(DrawContext context, float sx, float sy, ItemStack stack, boolean wide) {
        float w = wide ? 18 : 16;
        Color fill = stack.isEmpty() ? new Color(255, 255, 255, 12) : new Color(255, 255, 255, 22);
        if (!stack.isEmpty() && stack.isDamageable() && stack.getMaxDamage() > 0) {
            float left = (stack.getMaxDamage() - stack.getDamage()) / (float) stack.getMaxDamage();
            if (left < 0.12f) {
                float pulse = 0.6f + 0.4f * (float) Math.sin(System.currentTimeMillis() / 180.0);
                Render2DEngine.drawBlurredShadow(context, sx, sy, w, 16, 6, new Color(255, 60, 60, (int) (110 * pulse)));
                fill = new Color(255, 70, 70, (int) (50 * pulse));
            }
        }
        Render2DEngine.drawRound(context, sx, sy, w, 16, 3.5f, fill);
    }

    // Bake only items
    public static void renderHotBarItems(float tickDelta, DrawContext context) {
        if (mc.currentScreen instanceof WindowsScreen) return;
        PlayerEntity player = mc.player;
        if (player == null) return;

        int i = mc.getWindow().getScaledWidth() / 2;
        int o = mc.getWindow().getScaledHeight() - 16 - 3;

        if (!player.getOffHandStack().isEmpty()) {
            if (lmode.getValue() == Mode.Merged) renderHotbarItem(context, i - 109, o - 5, 0f, player.getOffHandStack());
            else renderHotbarItem(context, i - 111, o - 5, 0f, player.getOffHandStack());
        }

        int selected = player.getInventory().getSelectedSlot();
        for (int m = 0; m < 9; ++m) {
            lift[m] = AnimationUtility.fast(lift[m], m == selected ? 1f : 0f, 14f);
            int n = i - 90 + m * 20 + 2;
            renderHotbarItem(context, n, o - 5, lift[m], player.getInventory().getStack(m));
        }
    }

    /** @param up 0..1 how "picked" the slot is: the item rises up to 2 px and grows from 0.9 to 1.0 */
    private static void renderHotbarItem(DrawContext context, int i, int j, float up, ItemStack itemStack) {
        if (itemStack.isEmpty()) return;
        float scale = 0.9f + 0.1f * up;
        var m = context.getMatrices();
        m.pushMatrix();
        m.translate(0f, -2f * up);
        m.translate(i + 8f, j + 12f);
        m.scale(scale, scale);
        m.translate(-(i + 8f), -(j + 12f));
        context.drawItem(itemStack, i, j);
        m.popMatrix();

        m.pushMatrix();
        m.translate(0f, -2f * up);
        // vanilla stack overlay = stack count + durability bar + cooldown
        context.drawStackOverlay(mc.textRenderer, itemStack, i, j);
        m.popMatrix();

        // remaining-durability number for damageable items, green -> red
        if (itemStack.isDamageable() && itemStack.isDamaged()) {
            int rem = itemStack.getMaxDamage() - itemStack.getDamage();
            float frac = Math.max(0f, Math.min(1f, (float) rem / itemStack.getMaxDamage()));
            int col = new Color((int) (255 * (1 - frac)), (int) (200 * frac), 40).getRGB();
            String s = String.valueOf(rem);
            FontRenderers.sf_bold_mini.drawString(context, s, i + 16 - FontRenderers.sf_bold_mini.getStringWidth(s), j - 3 - 2f * up, col);
        }
    }

    public static void renderXpBar(int x, DrawContext matrices) {
        if (mc.player.experienceLevel > 0) {
            String string = "" + mc.player.experienceLevel;
            float k = (mc.getWindow().getScaledWidth() - FontRenderers.sf_bold_mini.getStringWidth(string)) / 2f;
            int l = mc.getWindow().getScaledHeight() - 31 - 4;
            // a soft dark halo so the level stays readable over any background
            FontRenderers.sf_bold_mini.drawString(matrices, string, k + 0.6f, l + 0.6f, new Color(0, 0, 0, 140).getRGB());
            FontRenderers.sf_bold_mini.drawString(matrices, string, k, l, 8453920);
        }
    }
}
