package thunder.hack.features.hud.impl;

import net.minecraft.client.gui.DrawContext;
import thunder.hack.features.modules.Module;
import thunder.hack.setting.Setting;
import thunder.hack.setting.impl.ColorSetting;
import thunder.hack.utility.player.MovementUtility;
import thunder.hack.utility.render.Render2DEngine;

import java.awt.Color;

/**
 * 1:1 port of Rockstar Free 2.0's {@code visuals.Crosshair}: four bars round the centre (T-Mode drops the top one,
 * Dynamic widens the gap while moving), or Circle - an arc of 360 x attack cooldown, radius 3, width 3.
 * The vanilla crosshair is hidden while this is on (MixinInGameHud).
 */
public class Crosshair extends Module {
    public Crosshair() {
        super("Crosshair", Category.HUD);
    }

    private final Setting<Boolean> circle = new Setting<>("Circle", false);
    private final Setting<Boolean> tmode = new Setting<>("T-Mode", false);
    private final Setting<Boolean> dynamic = new Setting<>("Dynamic", false);
    private final Setting<Float> dynamicGap = new Setting<>("Dynamic Gap", 3f, 1f, 20f, v -> dynamic.getValue());
    private final Setting<Float> gap = new Setting<>("Gap", 2f, 0f, 10f);
    public final Setting<ColorSetting> colorGlobal = new Setting<>("Crosshair Color", new ColorSetting(new Color(0xFFFFFF).getRGB()));
    private final Setting<Float> width = new Setting<>("Width", 1f, 0f, 8f);
    private final Setting<Float> length = new Setting<>("Length", 3f, 0.5f, 30f);

    @Override
    public void onRender2D(DrawContext context) {
        if (fullNullCheck()) return;
        int crosshairColor = colorGlobal.getValue().getColor() | 0xFF000000;
        float screenWidth = mc.getWindow().getScaledWidth();
        float screenHeight = mc.getWindow().getScaledHeight();
        float width = screenWidth / 2;
        float height = screenHeight / 2;
        boolean dyn = dynamic.getValue();
        float dyngap = dynamicGap.getValue();
        float wid = this.width.getValue();
        float len = length.getValue();
        boolean isMoving = dyn && MovementUtility.isMoving();
        float gaps = isMoving ? dyngap : gap.getValue();
        if (circle.getValue()) {
            final float cooldawn = mc.player.getAttackCooldownProgress(0);
            final double hpWidth2 = 360.0 * cooldawn;
            Color c = new Color(crosshairColor, true);
            // DrawHelper.drawCircle(middleX, middleY, 0, hpWidth2, 3, 3, false, color): a LINE_STRIP of radius 3 with
            // glLineWidth(3) - 3 framebuffer pixels - starting at three o'clock (angle 0) and running clockwise
            float line = (float) (3.0 / mc.getWindow().getScaleFactor());
            float outer = 3 + line / 2f;
            Render2DEngine.drawArc(context, width - outer, height - outer, outer * 2, outer * 2, outer, line, 90, 90 + (float) hpWidth2, c, c);
        } else {
            drawRect(context, width - gaps - len, height - (wid / 2), width - gaps, height + (wid / 2), crosshairColor);
            drawRect(context, width + gaps, height - (wid / 2), width + gaps + len, height + (wid / 2), crosshairColor);
            if (!tmode.getValue()) {
                drawRect(context, width - (wid / 2), height - gaps - len, width + (wid / 2), height - gaps, crosshairColor);
            }
            drawRect(context, width - (wid / 2), height + gaps, width + (wid / 2), height + gaps + len, crosshairColor);
        }
    }

    /** DrawHelper.drawRect(left, top, right, bottom, color). */
    private static void drawRect(DrawContext context, float left, float top, float right, float bottom, int color) {
        Render2DEngine.drawRect(context, left, top, right - left, bottom - top, new Color(color, true));
    }

    /** Screen centre - other HUD elements (Totem / Gapples counters) anchor to the crosshair. */
    public float getAnimatedPosX() {
        return mc.getWindow().getScaledWidth() / 2f;
    }

    public float getAnimatedPosY() {
        return mc.getWindow().getScaledHeight() / 2f;
    }
}
