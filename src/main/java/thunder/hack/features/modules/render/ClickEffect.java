package thunder.hack.features.modules.render;

import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.gui.DrawContext;
import thunder.hack.events.impl.EventAttack;
import thunder.hack.features.modules.Module;
import thunder.hack.features.modules.client.HudEditor;
import thunder.hack.setting.Setting;
import thunder.hack.setting.impl.ColorSetting;
import thunder.hack.utility.render.Render2DEngine;
import thunder.hack.utility.render.TextureStorage;

import java.awt.Color;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A soft glow ripple across the screen when you attack or click, centred on
 * the crosshair. Purely visual - it reads nothing and triggers nothing, it only draws on an attack event.
 */
public class ClickEffect extends Module {
    public ClickEffect() {
        super("ClickEffect", Category.RENDER);
    }

    public enum Trigger {Attack, Click, Both}

    private final Setting<Trigger> trigger = new Setting<>("Trigger", Trigger.Attack);
    private final Setting<Float> lifetime = new Setting<>("Lifetime", 0.45f, 0.1f, 1.5f);
    private final Setting<Float> maxRadius = new Setting<>("MaxRadius", 90f, 20f, 300f);
    private final Setting<Boolean> glass = new Setting<>("GlassBlur", true);
    private final Setting<Float> glassStrength = new Setting<>("GlassStrength", 1f, 0.2f, 2f, v -> glass.getValue());

    private record Ripple(long start, float cx, float cy) {
    }

    private final CopyOnWriteArrayList<Ripple> ripples = new CopyOnWriteArrayList<>();
    private boolean prevAttackDown;

    @EventHandler
    public void onAttack(EventAttack e) {
        if (e.isPre() && trigger.getValue() != Trigger.Click) spawn();
    }

    @Override
    public void onUpdate() {
        if (fullNullCheck()) return;
        boolean down = mc.options.attackKey.isPressed();
        if (down && !prevAttackDown && trigger.getValue() != Trigger.Attack) spawn();
        prevAttackDown = down;
    }

    private void spawn() {
        if (mc.getWindow() == null) return;
        ripples.add(new Ripple(System.currentTimeMillis(),
                mc.getWindow().getScaledWidth() / 2f, mc.getWindow().getScaledHeight() / 2f));
    }

    @Override
    public void onRender2D(DrawContext context) {
        if (mc.options.hudHidden) return;
        long now = System.currentTimeMillis();
        long life = Math.round(lifetime.getValue() * 1000f);

        for (Ripple r : ripples) {
            float t = (now - r.start()) / (float) life;
            if (t >= 1f) {
                ripples.remove(r);
                continue;
            }
            // easeOutCubic expand
            float ease = 1f - (1f - t) * (1f - t) * (1f - t);
            float radius = maxRadius.getValue() * ease;
            float d = radius * 2f;
            float x = r.cx() - radius, y = r.cy() - radius;

            // Strength envelope: rises fast, then relaxes to exactly 0 at the end of its life. At 0 the drop
            // blurs and magnifies nothing, so it melts into the screen instead of popping off.
            float env = (float) Math.sin(Math.PI * Math.pow(t, 0.55));
            float str = glassStrength.getValue();

            // A drop of glass: blurred, slightly magnifying (lens) disc with a light-catching rim. No white
            // fill on top - it stays a transparent glass ripple, not a white disc.
            if (radius > 3f) {
                if (glass.getValue() && thunder.hack.utility.render.ScreenCapture.available()) {
                    Render2DEngine.drawBackdropBlur(context, x, y, d, d, radius,
                            Math.max(1f, 12f * env * str), 1f - 0.11f * env * Math.min(str, 1.5f));
                    Render2DEngine.drawBackdropBoost(context, x, y, d, d, radius, 0.06f * env);
                }
                Render2DEngine.drawGlassRim(context, x, y, d, d, radius, Math.min(1.6f, radius / 4f), 0.85f * env);
            }
        }
    }

    @Override
    public void onDisable() {
        ripples.clear();
    }
}
