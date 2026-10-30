package thunder.hack.features.hud.impl;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.text.Text;
import thunder.hack.features.hud.HudElement;
import thunder.hack.features.modules.client.HudEditor;
import thunder.hack.gui.font.FontRenderer;
import thunder.hack.gui.font.FontRenderers;
import thunder.hack.setting.Setting;
import thunder.hack.utility.render.Render2DEngine;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 1:1 port of Rockstar Free 2.0's PotionIndicator ({@code display.PotionIndicator} + {@code PotionComponent}): a 100
 * wide panel titled "Potions" (Rockstar: "Зелья") with one row per effect - name (+ II / III / IV) in grey, time left on the right (grey,
 * orange under 20 s, red-orange under 10 s) - or "Nothing here)" (Rockstar: "Ниче нету)") when there are none. Fonts mntsb_15 / mntsb_20 and the
 * icons2 "n" icon, like Rockstar. The panel's top-left is the HUD editor position (PotionComponent's x / y).
 */
public class PotionHud extends HudElement {
    public PotionHud() {
        super("Potions", 100, 150);
    }

    /** The three Rockstar modes, plus LiquidGlass (not Rockstar): the Styled layout on the client's glass, tinted with the GUI colour. */
    public enum Mode {LiquidGlass, Rockstar, RockstarStyled, RockstarNew}

    private final Setting<Mode> mode = new Setting<>("Mode", Mode.LiquidGlass);
    private final Setting<String> glassTitle = new Setting<>("Title", "Effects", v -> mode.is(Mode.LiquidGlass));

    private static final String TITLE = "Potions";
    private static final String EMPTY = "Nothing here)";

    /** Rockstar's {@code height}: set after drawing, so the panel follows one frame behind. */
    private int height;

    public void onRender2D(DrawContext context) {
        super.onRender2D(context);
        if (fullNullCheck() || mc.getDebugHud().shouldShowDebugHud()) return;

        int x2 = (int) getPosX();
        int y2 = (int) getPosY();
        FontRenderer mntsb15 = FontRenderers.mntsb;
        Color client = HudEditor.getColor(0);

        List<StatusEffectInstance> potions = new ArrayList<>(mc.player.getStatusEffects());
        // sorted by the vanilla-font width of Potion.getName(), which in 1.12 is the untranslated key
        potions.sort(Comparator.comparingDouble(effect -> mc.textRenderer.getWidth(effect.getEffectType().value().getTranslationKey())));

        if (mode.is(Mode.LiquidGlass)) {
            drawGlass(context, potions, client);
            return;
        }

        switch (mode.getValue()) {
            // (PotionIndicator's own render pass - its y offsets differ by a pixel from PotionComponent's editor pass)
            case RockstarNew -> {
                RockstarRender.drawHorizontalGradientOutlinedRoundedRectWithGlow(context, x2, y2, 100, 15 + height, 5, 1, 15, client, client.darker());
                FontRenderers.mntsb_20.drawCenteredString(context, TITLE, x2 + 50, y2 + 3, -1);
            }
            case Rockstar -> {
                RockstarRender.drawGradientRound(context, x2, y2, 100, 15 + height, 5, client, client, client.darker(), client.darker());
                FontRenderers.mntsb_20.drawCenteredString(context, TITLE, x2 + 50, y2 + 3, -1);
            }
            case LiquidGlass -> {
                RockstarRender.drawLiquidGlass(context, x2, y2, 100, 15 + height - 0.5f, 5, client);
                RockstarRender.drawGradientRound(context, x2 - 3 + 4 + 1, y2 - 16.0f + 4 + 14, 12, 12, 4, client, client.brighter(), client.brighter(), client);
                FontRenderers.i30.drawString(context, "n", (float) (x2 + 1.5) + 2.2f, y2 - 13.0f + 3.5f + 14, -1);
                RockstarRender.drawStringWithShadow(context, FontRenderers.mntsb_20, TITLE, x2 + 17, y2 + 4, -1);
            }
            case RockstarStyled -> {
                Color l1 = client.brighter();
                Color l2 = client;
                RockstarRender.drawRound(context, x2, y2, 100, 15 + height - 0.5f, 5, new Color(40, 40, 40));
                RockstarRender.drawGradientRound(context, x2 - 3 + 4 + 1, y2 - 16.0f + 4 + 14, 12, 12, 4, l2, l1, l1, l2);
                FontRenderers.i30.drawString(context, "n", (float) (x2 + 1.5) + 2.2f, y2 - 13.0f + 3.5f + 14, -1);
                RockstarRender.drawRect(context, x2 + 1, y2 - 12 + 13 + 14, x2 + 2.5f + 15, y2 - 12 + 12 + 5 + 14, new Color(40, 40, 40));
                FontRenderers.mntsb_20.drawString(context, TITLE, x2 + 17, y2 + 4, -1);
            }
        }

        if (potions.isEmpty()) {
            if (mode.is(Mode.RockstarStyled) || mode.is(Mode.LiquidGlass))
                mntsb15.drawString(context, EMPTY, x2 + 4, y2 + height + 6.5f, Color.GRAY.getRGB());
            else
                mntsb15.drawString(context, EMPTY, x2 + 5, y2 + height + 6, Color.GRAY.getRGB());
            height = 12;
        } else {
            for (int i = 0; i < potions.size(); i++) {
                StatusEffectInstance potion = potions.get(i);
                String level = potion.getEffectType().value().getName().getString();
                if (potion.getAmplifier() == 1) {
                    level = level + " " + Text.translatable("enchantment.level.2").getString();
                } else if (potion.getAmplifier() == 2) {
                    level = level + " " + Text.translatable("enchantment.level.3").getString();
                } else if (potion.getAmplifier() == 3) {
                    level = level + " " + Text.translatable("enchantment.level.4").getString();
                }
                int getPotionColor = -1;
                if (potion.getDuration() < 200) {
                    getPotionColor = new Color(255, 103, 32).getRGB();
                } else if (potion.getDuration() < 400) {
                    getPotionColor = new Color(231, 143, 32).getRGB();
                } else if (potion.getDuration() > 400) {
                    getPotionColor = Color.GRAY.getRGB();
                }
                String durationString = getDuration(potion);
                mntsb15.drawString(context, level, x2 + 5, y2 + ((i + 1) * 12) + 6, Color.GRAY.getRGB());
                mntsb15.drawString(context, durationString, x2 + 95 - mntsb15.getStringWidth(durationString), y2 + ((i + 1) * 12) + 6, getPotionColor);
                height = potions.size() * 12;
            }
        }

        setBounds(x2, y2, 100, 15 + height);
    }

    /** LiquidGlass: icon, "Name 2", "mm:ss" per effect on the glass panel; hidden when there are none. */
    private void drawGlass(DrawContext context, List<StatusEffectInstance> potions, Color tint) {
        // Effects slide in from the left one after another and slide back out to the left when they end. An
        // ended effect is gone from the player, so its last row is remembered to animate it out.
        slide.frame();
        java.util.Set<Object> active = new java.util.HashSet<>();
        for (StatusEffectInstance p : potions) {
            net.minecraft.util.Identifier id = net.minecraft.registry.Registries.STATUS_EFFECT.getId(p.getEffectType().value());
            net.minecraft.util.Identifier icon = id == null ? null : net.minecraft.util.Identifier.of(id.getNamespace(), "textures/mob_effect/" + id.getPath() + ".png");
            String name = p.getEffectType().value().getName().getString() + (p.getAmplifier() > 0 ? " " + (p.getAmplifier() + 1) : "");
            String time;
            if (p.isInfinite()) time = "**:**";
            else {
                int s = p.getDuration() / 20;
                time = String.format("%02d:%02d", s / 60, s % 60);
            }
            Object key = p.getEffectType().value();
            active.add(key);
            lastRows.put(key, new GlassPanel.Row(icon, name, time));
        }

        List<GlassPanel.Row> rows = new ArrayList<>();
        var it = lastRows.entrySet().iterator();
        while (it.hasNext()) {
            var e = it.next();
            float anim = slide.update(e.getKey(), active.contains(e.getKey()));
            if (anim <= 0f && !active.contains(e.getKey())) {
                it.remove();
                continue;
            }
            GlassPanel.Row r = e.getValue();
            rows.add(new GlassPanel.Row(r.icon(), r.left(), r.right(), anim));
        }
        if (rows.isEmpty()) {
            setBounds(getPosX(), getPosY(), 0, 0);
            return;
        }
        float w = GlassPanel.width(glassTitle.getValue(), rows, 100);
        GlassPanel.draw(context, getPosX(), getPosY(), w, glassTitle.getValue(), thunder.hack.utility.render.TextureStorage.potionIcon, rows, tint);
        setBounds(getPosX(), getPosY(), w, GlassPanel.height(rows));
    }

    private final thunder.hack.features.hud.RowAnimator<Object> slide = new thunder.hack.features.hud.RowAnimator<>(260f);
    /** Each effect's last row, in first-seen order, so an ended one can still slide out. */
    private final java.util.Map<Object, GlassPanel.Row> lastRows = new java.util.LinkedHashMap<>();

    /** Potion.getDurationString: m:ss, "**:**" for an endless effect. Also used by NameTags. */
    public static String getDuration(StatusEffectInstance pe) {
        if (pe.isInfinite()) return "**:**";

        int ticks = pe.getDuration();
        return (ticks / 1200) + ":" + String.format("%02d", (ticks % 1200) / 20);
    }
}
