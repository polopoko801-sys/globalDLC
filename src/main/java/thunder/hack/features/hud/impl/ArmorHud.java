package thunder.hack.features.hud.impl;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.ItemStack;
import thunder.hack.features.hud.HudElement;
import thunder.hack.features.modules.client.HudEditor;
import thunder.hack.setting.Setting;
import thunder.hack.utility.render.Render2DEngine;

import java.awt.Color;

/**
 * The worn armour on a small pane of liquid glass: helmet to boots, each piece a scaled-down icon with a thin
 * durability line under it that slides from green through amber to red as the piece wears. No numbers unless
 * asked for - the line says at a glance how much is left.
 *
 * <p>The glass is the same as Potions' - real backdrop blur, a veil in the GUI colour and a light-catching rim -
 * without the rectangular sheen and the boxy drop shadow that panel carries, which showed as a square behind it.
 */
public class ArmorHud extends HudElement {
    public ArmorHud() {
        super("ArmorHud", 20, 70);
    }

    private enum Layout {Vertical, Horizontal}

    private final Setting<Layout> layout = new Setting<>("Layout", Layout.Vertical);
    /** The glass pane behind the icons. */
    private final Setting<Boolean> background = new Setting<>("Background", true);
    /** The remaining durability as a percentage under each piece. */
    private final Setting<Boolean> numbers = new Setting<>("Numbers", false);

    /** Top to bottom (or left to right), the way it is read. */
    private static final EquipmentSlot[] ORDER = {
            EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET
    };

    private static final float ICON = 12f;      // items drawn at 3/4 size
    private static final float CELL = 17f;      // one piece along the slab
    private static final float SIDE = 18f;      // the slab's thickness
    private static final float PAD = 3f;
    private static final float BAR_W = 11f, BAR_H = 1.5f;
    private static final float RADIUS = 6f;

    @Override
    public void onRender2D(DrawContext context) {
        super.onRender2D(context);
        if (fullNullCheck() || mc.getDebugHud().shouldShowDebugHud()) return;

        long now = System.currentTimeMillis();
        float dt = lastFrame == 0L ? 16f : Math.min(100f, now - lastFrame);
        lastFrame = now;

        // each slot slides in and out instead of popping, and the pane grows and shrinks with it
        ItemStack[] stacks = new ItemStack[ORDER.length];
        float slots = 0f;
        for (int i = 0; i < ORDER.length; i++) {
            ItemStack stack = mc.player.getEquippedStack(ORDER[i]);
            if (!stack.isEmpty()) lastStack[i] = stack.copy();
            stacks[i] = lastStack[i];
            // a spring: a piece put on swells a touch past full size and settles
            appear[i] = Math.max(0f, appearSpring[i].update(stack.isEmpty() ? 0f : 1f, dt));
            if (stacks[i] != null) shownLeft[i] = thunder.hack.utility.render.core.CloudanAnimationEngine.approach(
                    shownLeft[i] < 0f ? fraction(stacks[i]) : shownLeft[i], fraction(stacks[i]), 90f, dt);
            slots += ease(Math.min(1f, appear[i]));
        }
        if (slots < 0.02f) {
            setBounds(getPosX(), getPosY(), 0, 0);
            return;
        }

        boolean vertical = layout.is(Layout.Vertical);
        float extra = numbers.getValue() ? 5f : 0f;
        float step = CELL + (vertical ? extra : 0f);
        float along = slots * step + PAD * 2f;
        float across = SIDE + (vertical ? 0f : extra);
        float x = getPosX(), y = getPosY();
        float w = vertical ? across : along, h = vertical ? along : across;

        if (background.getValue()) glass(context, x, y, w, Math.max(h, RADIUS * 2f));

        float at = PAD;
        for (int i = 0; i < ORDER.length; i++) {
            float e = ease(Math.min(1f, appear[i]));
            if (e <= 0.01f || stacks[i] == null) continue;
            float cx = vertical ? x + w / 2f : x + at + step / 2f;
            float top = vertical ? y + at : y + PAD - 0.5f;
            drawPiece(context, stacks[i], shownLeft[i], cx, top, e, appear[i], now);
            at += step * e;
        }

        setBounds(x, y, w, h);
    }

    private long lastFrame;
    /** Per slot: how far it has slid in (0..1), the last piece worn there, and its eased durability. */
    private final float[] appear = new float[ORDER.length];
    private final thunder.hack.utility.render.core.Spring[] appearSpring = {
            new thunder.hack.utility.render.core.Spring(260f, 17f, 0f), new thunder.hack.utility.render.core.Spring(260f, 17f, 0f),
            new thunder.hack.utility.render.core.Spring(260f, 17f, 0f), new thunder.hack.utility.render.core.Spring(260f, 17f, 0f)};
    private final ItemStack[] lastStack = new ItemStack[ORDER.length];
    private final float[] shownLeft = {-1f, -1f, -1f, -1f};

    private static float ease(float t) {
        return t * t * (3f - 2f * t);
    }

    /**
     * One piece: the icon centred on {@code cx}, growing in with {@code e}, its durability line under it and the
     * percentage under that. A piece about to break pulses a red glow around its line.
     */
    private void drawPiece(DrawContext context, ItemStack stack, float left, float cx, float top, float e, float pop, long now) {
        var matrices = context.getMatrices();
        matrices.pushMatrix();
        float s = ICON / 16f * (0.6f + 0.4f * pop);
        matrices.translate(cx - 8f * s, top + 1f + (ICON - 16f * s) / 2f);
        matrices.scale(s, s);
        context.drawItem(stack, 0, 0);
        matrices.popMatrix();
        if (e < 0.98f) return;

        float bx = cx - BAR_W / 2f, by = top + ICON + 2.5f;
        if (left < 0.2f) {
            float pulse = 0.5f + 0.5f * (float) Math.sin(now / 160.0);
            Render2DEngine.drawRound(context, bx - 1.5f, by - 1.5f, BAR_W + 3f, BAR_H + 3f, (BAR_H + 3f) / 2f,
                    new Color(232, 80, 70, Math.round(40 + 70 * pulse)));
        }
        Render2DEngine.drawRound(context, bx, by, BAR_W, BAR_H, BAR_H / 2f, new Color(0, 0, 0, 120));
        if (left > 0f)
            Render2DEngine.drawRound(context, bx, by, Math.max(BAR_H, BAR_W * left), BAR_H, BAR_H / 2f, barColor(left));

        if (numbers.getValue()) {
            String text = Math.round(left * 100) + "%";
            var font = thunder.hack.gui.font.FontRenderers.sf_bold_micro;
            font.drawString(context, text, cx - font.getStringWidth(text) / 2f, by + 3f, new Color(255, 255, 255, 210).getRGB());
        }
    }

    /**
     * Potions' liquid glass without its two boxy parts: blurred backdrop lifted a touch, an even veil in the GUI
     * colour, a faint wash and milkiness, and the light-catching rim. Without a backdrop to blur (an unsupported
     * backend) a plain translucent pane stands in.
     */
    private static void glass(DrawContext context, float x, float y, float w, float h) {
        float blur = HudEditor.blurStrength.getValue();
        if (Render2DEngine.drawBackdropBlur(context, x, y, w, h, RADIUS, blur / 2.5f, 1f, 1f)) {
            Render2DEngine.drawBackdropBoost(context, x, y, w, h, RADIUS, 0.07f);
            Color gui = HudEditor.getColor(0);
            int tintA = (int) (255 * Math.clamp(HudEditor.glassTint.getValue(), 0f, 0.8f));
            Color veil = Render2DEngine.injectAlpha(Render2DEngine.interpolateColorC(gui, new Color(18, 18, 24), 0.55f), tintA);
            Render2DEngine.renderRoundedQuad(context, veil, x, y, x + w, y + h, RADIUS, 6);
            Render2DEngine.renderRoundedQuad(context, Render2DEngine.injectAlpha(gui, 22), x, y, x + w, y + h, RADIUS, 6);
            Render2DEngine.renderRoundedQuad(context, new Color(255, 255, 255, 14), x, y, x + w, y + h, RADIUS, 6);
        } else {
            Render2DEngine.drawRound(context, x, y, w, h, RADIUS, new Color(20, 20, 26, 190));
        }
        Render2DEngine.drawGlassRim(context, x, y, w, h, RADIUS, 1f, 1f);
    }

    /** How much of the piece is left, 0..1. Unbreakable pieces are always full. */
    private static float fraction(ItemStack stack) {
        int max = stack.getMaxDamage();
        if (max <= 0) return 1f;
        return Math.clamp((max - stack.getDamage()) / (float) max, 0f, 1f);
    }

    private static final Color GOOD = new Color(92, 214, 110), MID = new Color(240, 196, 70), LOW = new Color(232, 80, 70);

    /** Slides from green through amber to red as the piece wears - no sudden jumps between three colours. */
    private static Color barColor(float left) {
        Color c = left > 0.5f
                ? Render2DEngine.interpolateColorC(MID, GOOD, (left - 0.5f) * 2f)
                : Render2DEngine.interpolateColorC(LOW, MID, left * 2f);
        return Render2DEngine.injectAlpha(c, 240);
    }
}
