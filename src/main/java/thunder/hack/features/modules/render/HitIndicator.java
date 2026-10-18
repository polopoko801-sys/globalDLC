package thunder.hack.features.modules.render;

import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.jetbrains.annotations.NotNull;
import thunder.hack.core.target.TargetState;
import thunder.hack.events.impl.EventAttack;
import thunder.hack.features.modules.Module;
import thunder.hack.features.modules.client.HudEditor;
import thunder.hack.setting.Setting;
import thunder.hack.setting.impl.ColorSetting;
import thunder.hack.utility.render.Render2DEngine;
import thunder.hack.utility.render.TextureStorage;
import thunder.hack.utility.render.core.CloudanAnimationEngine;
import thunder.hack.utility.render.core.CloudanCullingSystem;
import thunder.hack.utility.render.core.CloudanGlowRenderer;
import thunder.hack.utility.render.core.CloudanParticleRenderer;
import thunder.hack.utility.render.core.CloudanRenderCore;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;

/**
 * Shows the hits you land: where they went, and in which direction.
 *
 * <p><b>It only ever reacts.</b> Every effect here starts from {@link EventAttack} with
 * {@code isPre() == false} - a swing that has already been sent, by your hand. There is no call to
 * {@code attackEntity} in this file, no timer that could fire one, and no path from any of its settings to
 * an outgoing packet. Turning it up makes the feedback louder, never the attacks more frequent.
 *
 * <p>Because the effect is built inside the event handler, it cannot drift out of sync with the hit: the
 * position is sampled at the instant of the swing rather than re-derived a frame later, which is what stops
 * the wave appearing where the target has since moved to.
 *
 * <p><b>The direction indicator</b> is measured against the <i>camera</i> yaw, not the player's, so it keeps
 * pointing at the right place in third person and while the camera is detached. Straight up on the ring is
 * in front of you, right is to your right, bottom is behind.
 *
 * <p>Everything is drawn through CloudanRenderCore - glow for the wave, the pooled particle system for the
 * sparks, the culling system for range. No separate renderer.
 */
public class HitIndicator extends Module {
    public HitIndicator() {
        super("HitIndicator", Category.RENDER);
    }

    /** The world wave at the point of impact. */
    private final Setting<Boolean> hitWave = new Setting<>("HitWave", true);
    private final Setting<Float> waveSize = new Setting<>("WaveSize", 1f, 0.3f, 3f, v -> hitWave.getValue());
    /** A hot core on the first frames, under the wave. */
    private final Setting<Boolean> waveFlash = new Setting<>("WaveFlash", true, v -> hitWave.getValue());

    /** The arc around the crosshair that says which way the hit was. */
    private final Setting<Boolean> direction = new Setting<>("Direction", true);
    private final Setting<Float> directionRadius = new Setting<>("DirectionRadius", 26f, 8f, 80f,
            v -> direction.getValue());
    /** How wide the arc segment is, in degrees. */
    private final Setting<Float> directionSpan = new Setting<>("DirectionSpan", 34f, 6f, 120f,
            v -> direction.getValue());
    private final Setting<Float> directionThickness = new Setting<>("DirectionThickness", 2.5f, 1f, 8f,
            v -> direction.getValue());

    private final Setting<Boolean> particles = new Setting<>("Particles", true);
    private final Setting<Integer> particleCount = new Setting<>("ParticleCount", 10, 2, 40,
            v -> particles.getValue());

    /**
     * Makes a critical hit look like one.
     *
     * <p>The crit condition is sampled in the pre-attack phase, because by the time the swing has been sent
     * your fall distance has already been cleared and the answer would always be no.
     */
    private final Setting<Boolean> critEmphasis = new Setting<>("CritEmphasis", true);

    private final Setting<Mode> colorMode = new Setting<>("ColorMode", Mode.Sync);
    private final Setting<ColorSetting> color = new Setting<>("Color", new ColorSetting(0xFFFFFFFF),
            v -> colorMode.getValue() == Mode.Custom);
    private final Setting<ColorSetting> critColor = new Setting<>("CritColor", new ColorSetting(0xFFFFC14D),
            v -> critEmphasis.getValue());
    private final Setting<Integer> opacity = new Setting<>("Opacity", 220, 40, 255);

    /** How long one hit stays on screen, in milliseconds. */
    private final Setting<Integer> fade = new Setting<>("Fade", 420, 100, 2000);
    private final Setting<Float> animationSpeed = new Setting<>("AnimationSpeed", 1f, 0.2f, 4f);
    private final Setting<Integer> renderDistance = new Setting<>("RenderDistance", 48, 8, 128);
    private final Setting<Quality> quality = new Setting<>("Quality", Quality.Auto);
    /** How many hits may be on screen at once. A spam-clicker would otherwise stack dozens. */
    private final Setting<Integer> maxHits = new Setting<>("MaxHits", 6, 1, 20);

    public enum Mode {
        Custom, Sync
    }

    public enum Quality {
        Auto, Low, Medium, High
    }

    /**
     * One landed hit.
     *
     * @param at   where the victim was at the moment of the swing, sampled then rather than looked up later
     * @param yaw  the world yaw from the player to that point, so the arc can be re-measured against
     *             whatever the camera is doing now instead of being baked to one frame's view
     * @param crit whether the swing was a critical one
     */
    private record Hit(Vec3d at, float yaw, boolean crit, long stamp) {
        float age(int fadeMs) {
            return Math.clamp((System.currentTimeMillis() - stamp) / (float) fadeMs, 0f, 1f);
        }

        boolean done(int fadeMs) {
            return System.currentTimeMillis() - stamp > fadeMs;
        }
    }

    private final List<Hit> hits = new ArrayList<>();
    /** The crit condition as it was before the swing went out. */
    private boolean critPending;

    @Override
    public void onEnable() {
        hits.clear();
        critPending = false;
    }

    @Override
    public void onDisable() {
        hits.clear();
        // The pool is shared, so this module's particles are returned rather than left to expire on a
        // module that is no longer running.
        CloudanParticleRenderer.clear("HitIndicator");
    }

    @Override
    public void onLogout() {
        hits.clear();
        CloudanParticleRenderer.clear("HitIndicator");
    }

    @Override
    public void onUnload() {
        hits.clear();
    }

    /* ------------------------------------------------------------------ */
    /* The one input: a swing that actually happened                       */
    /* ------------------------------------------------------------------ */

    @EventHandler
    @SuppressWarnings("unused")
    public void onAttack(@NotNull EventAttack event) {
        if (fullNullCheck()) return;

        Entity victim = event.getEntity();
        if (victim == null) return;

        if (event.isPre()) {
            // Sampled here and used below. The vanilla client-side condition: falling, not on the ground,
            // not in water, not climbing, not riding, with the cooldown back. It is an approximation of
            // what the server will decide, and it only changes how loud the effect is.
            critPending = critEmphasis.getValue()
                    && mc.player.fallDistance > 0f
                    && !mc.player.isOnGround()
                    && !mc.player.isTouchingWater()
                    && !mc.player.isClimbing()
                    && !mc.player.hasVehicle()
                    && mc.player.getAttackCooldownProgress(0.5f) > 0.9f;
            return;
        }

        Vec3d at = TargetState.centreOf(victim);
        Vec3d from = mc.player.getEyePos();
        Vec3d to = at.subtract(from);

        // World yaw of the hit, stored rather than a screen position: the camera will have turned by the
        // time this is drawn, and an arc baked to one frame's screen angle would point at nothing.
        float yaw = (float) Math.toDegrees(Math.atan2(to.z, to.x)) - 90f;

        // Taken into a local before the pending flag is cleared - reading it back after the reset is how
        // the sparks would end up never being the crit colour.
        boolean crit = critPending;
        critPending = false;

        hits.add(new Hit(at, yaw, crit, System.currentTimeMillis()));

        // Oldest out first, so a fast clicker keeps the newest hits rather than the stalest ones.
        while (hits.size() > maxHits.getValue()) hits.remove(0);

        if (particles.getValue() && !reduce()) spawnSparks(at, crit);
    }

    /**
     * A short burst at the point of impact.
     *
     * <p>Through the pooled particle system with this module named as the owner, so its own cap applies and
     * a spam-click cannot starve every other visual of particles.
     */
    private void spawnSparks(Vec3d at, boolean crit) {
        Color tint = crit ? critColor.getValue().getColorObject() : baseColor();
        int count = Math.round(particleCount.getValue() * CloudanRenderCore.qualityScale());

        for (int i = 0; i < Math.max(1, count); i++) {
            var spawn = CloudanParticleRenderer.spawn("HitIndicator", maxHits.getValue() * particleCount.getValue());
            if (spawn == null) return;

            double angle = Math.random() * Math.PI * 2;
            double speed = 0.04 + Math.random() * 0.06;

            spawn.at(at)
                    .velocity(Math.cos(angle) * speed, 0.02 + Math.random() * 0.05, Math.sin(angle) * speed)
                    .physics(0.004, 0.86)
                    .size(0.09f + (float) Math.random() * 0.05f, 0.14f)
                    .life(Math.round(fade.getValue() * 0.9f))
                    .color(Render2DEngine.injectAlpha(tint, opacity.getValue()))
                    .texture(TextureStorage.fxSpark)
                    .throughWalls(false);
        }
    }

    /* ------------------------------------------------------------------ */
    /* World pass: the wave                                                */
    /* ------------------------------------------------------------------ */

    @Override
    public void onRender3D(MatrixStack matrices) {
        if (fullNullCheck()) return;

        hits.removeIf(hit -> hit.done(fade.getValue()));
        if (!hitWave.getValue()) return;

        for (Hit hit : hits) {
            if (!CloudanCullingSystem.inRange(hit.at(), renderDistance.getValue())) continue;

            // Eased out, so the wave slows as it widens instead of travelling at a constant speed, which is
            // what an impact actually looks like.
            float t = CloudanAnimationEngine.smoothStep(scaled(hit));
            float strength = (1f - t) * (hit.crit() ? 1f : 0.82f);
            if (strength <= 0.02f) continue;

            Color tint = hit.crit() ? critColor.getValue().getColorObject() : baseColor();
            double radius = (0.5 + t * 1.5) * waveSize.getValue() * (hit.crit() ? 1.25 : 1.0);

            CloudanGlowRenderer.shockwave(hit.at(), radius, CloudanAnimationEngine.spin(4f),
                    tint, strength * 0.85f, false);

            if (waveFlash.getValue() && !reduce() && t < 0.45f)
                CloudanGlowRenderer.flash(hit.at(), 0.45 * waveSize.getValue(), 0, tint,
                        (1f - t / 0.45f) * 0.6f, false);
        }
    }

    /* ------------------------------------------------------------------ */
    /* Screen pass: the direction arc                                      */
    /* ------------------------------------------------------------------ */

    @Override
    public void onRender2D(DrawContext context) {
        if (fullNullCheck() || !direction.getValue()) return;
        // F1 hides the interface, and an arc round the crosshair is interface.
        if (mc.options.hudHidden) return;
        if (hits.isEmpty()) return;

        float cx = mc.getWindow().getScaledWidth() / 2f;
        float cy = mc.getWindow().getScaledHeight() / 2f;
        float radius = directionRadius.getValue();

        for (Hit hit : hits) {
            float t = scaled(hit);
            float strength = 1f - t;
            if (strength <= 0.02f) continue;

            // Re-measured against the camera every frame: 0 is straight ahead, +90 is to your right, 180 is
            // behind. Camera yaw rather than player yaw, so F5 and a detached camera stay honest.
            float relative = MathHelper.wrapDegrees(hit.yaw() - CloudanRenderCore.cameraYaw());

            float span = directionSpan.getValue();
            Color tint = Render2DEngine.injectAlpha(
                    hit.crit() ? critColor.getValue().getColorObject() : baseColor(),
                    Math.round(strength * opacity.getValue()));
            // Transparent at both ends so the segment reads as a soft marker rather than a cut-out tile.
            Color edge = Render2DEngine.injectAlpha(tint, 0);

            // The arc pushes outwards slightly as it fades, which gives the hit a direction of travel.
            float r = radius + t * 5f;

            Render2DEngine.drawArc(context, cx - r, cy - r, r * 2f, r * 2f, r,
                    directionThickness.getValue(),
                    relative - span / 2f, relative, edge, tint);
            Render2DEngine.drawArc(context, cx - r, cy - r, r * 2f, r * 2f, r,
                    directionThickness.getValue(),
                    relative, relative + span / 2f, tint, edge);
        }
    }

    /* ------------------------------------------------------------------ */
    /* Helpers                                                             */
    /* ------------------------------------------------------------------ */

    /** Age 0..1, with the animation speed dial folded in. */
    private float scaled(Hit hit) {
        return Math.clamp(hit.age(fade.getValue()) * animationSpeed.getValue(), 0f, 1f);
    }

    private Color baseColor() {
        return colorMode.getValue() == Mode.Sync ? HudEditor.getColor(0) : color.getValue().getColorObject();
    }

    private boolean reduce() {
        return switch (quality.getValue()) {
            case Auto -> CloudanRenderCore.reduceEffects();
            case Low -> true;
            case Medium -> CloudanRenderCore.quality() == thunder.hack.features.modules.client.ClientSettings.Quality.Low;
            case High -> false;
        };
    }
}
