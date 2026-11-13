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

    public enum Style {LiquidGlass, Glass}

    private final Setting<Trigger> trigger = new Setting<>("Trigger", Trigger.Attack);
    private final Setting<Style> style = new Setting<>("Style", Style.LiquidGlass);
    /** The wave runs out to the far corners of the screen instead of stopping at MaxRadius. */
    private final Setting<Boolean> fullScreen = new Setting<>("FullScreen", true);
    private final Setting<Float> lifetime = new Setting<>("Lifetime", 0.7f, 0.1f, 1.5f);
    private final Setting<Float> maxRadius = new Setting<>("MaxRadius", 90f, 20f, 300f, v -> !fullScreen.getValue());
    private final Setting<Float> band = new Setting<>("WaveWidth", 28f, 6f, 64f, v -> style.getValue() == Style.LiquidGlass);
    private final Setting<Float> refraction = new Setting<>("Refraction", 0.6f, 0.1f, 1f, v -> style.getValue() == Style.LiquidGlass);
    private final Setting<Boolean> glass = new Setting<>("GlassBlur", true, v -> style.getValue() == Style.Glass);
    private final Setting<Float> glassStrength = new Setting<>("GlassStrength", 1f, 0.2f, 2f, v -> glass.getValue() && style.getValue() == Style.Glass);

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
            float sw = mc.getWindow().getScaledWidth(), sh = mc.getWindow().getScaledHeight();
            // full screen: far enough that the whole band has left the farthest corner by the end
            float reach = fullScreen.getValue()
                    ? (float) Math.hypot(Math.max(r.cx(), sw - r.cx()), Math.max(r.cy(), sh - r.cy())) + band.getValue()
                    : maxRadius.getValue();
            float radius = reach * ease;
            float d = radius * 2f;
            float x = r.cx() - radius, y = r.cy() - radius;

            // Strength envelope: rises fast, then relaxes to exactly 0 at the end of its life. At 0 the drop
            // blurs and magnifies nothing, so it melts into the screen instead of popping off.
            float env = (float) Math.sin(Math.PI * Math.pow(t, 0.55));
            float str = glassStrength.getValue();

            if (style.getValue() == Style.LiquidGlass) {
                // the band grows in quickly, then the ring thins out as it travels
                float w = band.getValue() * Math.min(1f, radius / Math.max(1f, band.getValue()));
                if (!thunder.hack.utility.render.BkClickWave.ring(context, r.cx(), r.cy(), radius, w,
                        refraction.getValue() * (1f - 0.5f * t), env))
                    Render2DEngine.drawGlassRim(context, r.cx() - radius, r.cy() - radius, radius * 2f, radius * 2f, radius,
                            Math.min(1.6f, radius / 4f), 0.85f * env);
                continue;
            }

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
