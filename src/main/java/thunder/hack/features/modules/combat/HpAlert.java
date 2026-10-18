package thunder.hack.features.modules.combat;

import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import thunder.hack.events.impl.PlayerUpdateEvent;
import thunder.hack.features.modules.Module;
import thunder.hack.gui.font.FontRenderers;
import thunder.hack.setting.Setting;
import thunder.hack.utility.render.Render2DEngine;

import java.awt.Color;

/**
 * Warns you when your health drops below a threshold — plays a sound and flashes a warning in the middle
 * of the screen, once per time you cross the threshold (re-arms when you heal back above it).
 */
public final class HpAlert extends Module {
    private final Setting<Float> threshold = new Setting<>("Health", 8f, 1f, 36f);
    private final Setting<Boolean> withAbsorption = new Setting<>("Absorption", true);
    private final Setting<Boolean> playSound = new Setting<>("Sound", true);
    private final Setting<Boolean> onScreen = new Setting<>("OnScreen", true);
    private final Setting<Integer> duration = new Setting<>("Seconds", 3, 1, 10);

    private boolean alerted;
    private long shownAt;
    private float shownHp;

    public HpAlert() {
        super("HpAlert", Category.COMBAT);
    }

    @Override
    public void onEnable() {
        alerted = false;
        shownAt = 0L;
    }

    @EventHandler
    public void onUpdate(PlayerUpdateEvent e) {
        if (fullNullCheck()) return;
        float hp = mc.player.getHealth() + (withAbsorption.getValue() ? mc.player.getAbsorptionAmount() : 0f);
        if (hp <= threshold.getValue()) {
            if (!alerted) {
                alerted = true;
                shownHp = hp;
                shownAt = System.currentTimeMillis();
                if (playSound.getValue() && mc.world != null)
                    mc.world.playSound(mc.player, mc.player.getBlockPos(), SoundEvents.BLOCK_NOTE_BLOCK_PLING.value(), SoundCategory.PLAYERS, 1.5f, 1.3f);
            }
        } else {
            alerted = false;
        }
    }

    @Override
    public void onRender2D(DrawContext context) {
        if (!onScreen.getValue() || shownAt == 0L) return;

        long visibleFor = duration.getValue() * 1000L;
        long age = System.currentTimeMillis() - shownAt;
        if (age > visibleFor) {
            shownAt = 0L;
            return;
        }

        // fade out over the last 500ms, pulse the whole time
        float fade = age > visibleFor - 500 ? (visibleFor - age) / 500f : 1f;
        float pulse = 0.75f + 0.25f * (float) Math.sin(age / 120.0);
        int alpha = (int) (255 * fade * pulse);

        String text = "NISKIE HP  " + String.format("%.1f", shownHp);
        float cx = mc.getWindow().getScaledWidth() / 2f;
        float cy = mc.getWindow().getScaledHeight() / 2f - 40f;
        float textW = FontRenderers.monsterrat.getStringWidth(text);
        float boxW = textW + 28f, boxH = 24f;

        Render2DEngine.drawBlurredShadow(context, cx - boxW / 2f, cy - boxH / 2f, boxW, boxH, 14, new Color(200, 40, 40, (int) (90 * fade)));
        Render2DEngine.drawRound(context, cx - boxW / 2f, cy - boxH / 2f, boxW, boxH, 7f, new Color(24, 12, 16, (int) (215 * fade)));
        Render2DEngine.drawRound(context, cx - boxW / 2f, cy - boxH / 2f + 5f, 3f, boxH - 10f, 1.5f, new Color(235, 70, 70, alpha));
        FontRenderers.monsterrat.drawCenteredString(context, text, cx + 4f, cy - 4f, new Color(255, 90, 90, alpha).getRGB());
    }
}
