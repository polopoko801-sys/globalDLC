package thunder.hack.features.hud.impl;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.entity.LivingEntity;
import net.minecraft.util.hit.EntityHitResult;
import thunder.hack.features.hud.HudElement;
import thunder.hack.features.modules.combat.Aura;
import thunder.hack.features.modules.combat.AutoCrystal;
import thunder.hack.gui.font.FontRenderers;
import thunder.hack.setting.Setting;
import thunder.hack.utility.math.MathUtility;
import thunder.hack.utility.render.Render2DEngine;

import java.awt.Color;

/**
 * Small glass HP read-out for the entity you're looking at (or your current combat target): a frosted
 * pill with a colored health bar (green -> red) and the exact HP number.
 */
public class HealthIndicator extends HudElement {
    private final Setting<Boolean> absorption = new Setting<>("Absorption", true);
    private final Setting<Boolean> percent = new Setting<>("Percent", false);

    public HealthIndicator() {
        super("HealthIndicator", 66, 13);
    }

    private LivingEntity resolveTarget() {
        if (AutoCrystal.target != null && !AutoCrystal.target.isDead()) return AutoCrystal.target;
        if (Aura.target instanceof LivingEntity le && !le.isDead()) return le;
        if (mc.crosshairTarget instanceof EntityHitResult ehr && ehr.getEntity() instanceof LivingEntity le && !le.isDead())
            return le;
        return null;
    }

    public void onRender2D(DrawContext context) {
        super.onRender2D(context);

        LivingEntity target = resolveTarget();
        float w = 66f, h = 13f;
        setBounds(getPosX(), getPosY(), w, h);
        if (target == null) return;

        float hp = target.getHealth() + (absorption.getValue() ? target.getAbsorptionAmount() : 0f);
        float max = Math.max(1f, target.getMaxHealth());
        float frac = MathUtility.clamp(hp / max, 0f, 1f);

        Color barColor = new Color((int) (255 * (1 - frac)), (int) (200 * frac), 45);

        float x = getPosX(), y = getPosY();
        // glass pill
        Render2DEngine.drawRoundedBlur(context, x, y, w, h, 3.5f, new Color(255, 255, 255, 18));
        Render2DEngine.drawRound(context, x, y, w, h, 3.5f, new Color(18, 18, 22, 130));
        // health bar
        Render2DEngine.drawRound(context, x + 3, y + h - 4.5f, (w - 6), 2.5f, 1f, new Color(0, 0, 0, 120));
        Render2DEngine.drawRound(context, x + 3, y + h - 4.5f, Math.max(1.5f, (w - 6) * frac), 2.5f, 1f, barColor);

        String txt = percent.getValue()
                ? Math.round(frac * 100f) + "%"
                : (Math.round(hp * 10f) / 10f) + " HP";
        FontRenderers.sf_bold_mini.drawString(context, txt, x + 4f, y + 2.5f, -1);
    }
}
