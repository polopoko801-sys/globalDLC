package thunder.hack.features.modules.render;

import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.RotationAxis;

import static thunder.hack.features.modules.Module.mc;

/**
 * The first-person hand and held-item animation - purely visual, it never touches input, timing or the game.
 *
 * <p>Two stages, one hierarchy. {@link #handTransform} runs at the start of the item draw, in camera space,
 * before vanilla places the arm: everything applied there moves the hand AND what it holds (idle, sway, bob,
 * the arc of the swing). {@link #itemTransform} runs right before the item itself is drawn, with the matrix
 * already in the hand: rotations there turn the item about its grip (the swing's wrist roll, the item's own
 * offset, scale and rotation). So the sword follows the hand instead of moving on its own.
 *
 * <p>The swing runs on its own visual clock, started when a swing begins - so Swing Speed changes how the
 * animation plays, never how fast you attack.
 */
public final class HandAnimator {
    private final Animations s;

    HandAnimator(Animations settings) {
        this.s = settings;
    }

    /* ------------------------------------------------------------------ */
    /* Profiles                                                            */
    /* ------------------------------------------------------------------ */

    /** How a kind of item carries itself: swing size, swing tempo, how much it bobs, how vertical its swing is. */
    record Profile(float swing, float tempo, float bob, float vertical) {
    }

    private static final Profile SWORD = new Profile(1.25f, 1.15f, 1.0f, 0.15f);
    private static final Profile TOOL = new Profile(1.0f, 0.8f, 1.15f, 0.3f);
    private static final Profile FOOD = new Profile(0.7f, 1.0f, 0.9f, 1.0f);
    private static final Profile BLOCK = new Profile(0.6f, 1.0f, 0.6f, 0.4f);
    private static final Profile EMPTY = new Profile(0.6f, 1.1f, 0.8f, 0.25f);
    private static final Profile OTHER = new Profile(0.85f, 1.0f, 0.9f, 0.3f);

    private Profile profileFor(ItemStack stack) {
        if (!s.highQuality()) return OTHER;
        if (stack == null || stack.isEmpty()) return EMPTY;
        if (stack.isIn(ItemTags.SWORDS)) return SWORD;
        if (stack.isIn(ItemTags.AXES) || stack.isIn(ItemTags.PICKAXES) || stack.isIn(ItemTags.SHOVELS) || stack.isIn(ItemTags.HOES))
            return TOOL;
        if (stack.contains(DataComponentTypes.FOOD)) return FOOD;
        if (stack.getItem() instanceof BlockItem) return BLOCK;
        return OTHER;
    }

    /* ------------------------------------------------------------------ */
    /* State                                                               */
    /* ------------------------------------------------------------------ */

    private long lastNanos;
    private float lastYaw = Float.NaN, lastPitch = Float.NaN;
    private float swayX, swayY;             // eased hand lag behind the camera
    private float bobPhase, bobAmount;      // walking cycle and how much of it is showing
    private float airY;                     // eased vertical offset from jumping / falling
    private long swingStart = -1;           // visual swing clock
    private float lastVanillaSwing;

    private float dt() {
        long now = System.nanoTime();
        float d = lastNanos == 0 ? 0.016f : Math.min(0.1f, (now - lastNanos) / 1e9f);
        lastNanos = now;
        return d;
    }

    private static float follow(float current, float target, float dt, float halfLifeSec) {
        return current + (target - current) * (1f - (float) Math.exp(-dt * 0.693f / Math.max(0.001f, halfLifeSec)));
    }

    /* ------------------------------------------------------------------ */
    /* Hand (camera space)                                                 */
    /* ------------------------------------------------------------------ */

    void handTransform(MatrixStack m, ItemStack stack, boolean mainHand) {
        if (mc.player == null) return;
        float dt = mainHand ? dt() : 0.016f; // the off hand shares the main hand's clock
        Profile p = profileFor(stack);
        float time = (System.currentTimeMillis() % 1_000_000L) / 1000f;

        float x = 0, y = 0, z = 0, pitch = 0, yaw = 0, roll = 0;

        // 1. idle: breathing - a slow rise and fall and a hint of roll, barely there
        if (s.idleMotion.getValue()) {
            float k = s.idleStrength.getValue();
            y += (float) Math.sin(time * 1.6) * 0.006f * k;
            x += (float) Math.sin(time * 0.8) * 0.003f * k;
            roll += (float) Math.sin(time * 0.9) * 0.6f * k;
        }

        // 2. sway: the hand lags behind a turning camera, then catches up
        if (s.handSway.getValue() && s.mediumQuality()) {
            float yawNow = mc.player.getYaw(), pitchNow = mc.player.getPitch();
            if (mainHand) {
                if (Float.isNaN(lastYaw)) { lastYaw = yawNow; lastPitch = pitchNow; }
                float dYaw = MathHelper.wrapDegrees(yawNow - lastYaw), dPitch = pitchNow - lastPitch;
                lastYaw = yawNow;
                lastPitch = pitchNow;
                // turn speed (degrees per second) becomes a target lag, eased in and back out
                float targetX = MathHelper.clamp(-dYaw / Math.max(dt, 1e-3f) * 0.00012f, -0.06f, 0.06f);
                float targetY = MathHelper.clamp(dPitch / Math.max(dt, 1e-3f) * 0.0001f, -0.05f, 0.05f);
                float half = s.ultraQuality() ? 0.09f : 0.07f;
                swayX = follow(swayX, targetX, dt, half);
                swayY = follow(swayY, targetY, dt, half);
            }
            float k = s.swayStrength.getValue();
            x += swayX * k;
            y += swayY * k;
            yaw += swayX * 40f * k;
            roll += swayX * 25f * k;
        }

        // 3. bob: walking cycle, side to side with a small roll; jumping and falling push the hand the other way
        if (s.movementBob.getValue() && s.mediumQuality()) {
            double speed = Math.hypot(mc.player.getVelocity().x, mc.player.getVelocity().z);
            boolean ground = mc.player.isOnGround();
            if (mainHand) {
                bobPhase += (float) (speed * 24.0 * dt * (ground ? 1 : 0.3));
                bobAmount = follow(bobAmount, ground ? (float) Math.min(1.0, speed / 0.22) : 0f, dt, 0.12f);
                float vy = (float) mc.player.getVelocity().y;
                airY = follow(airY, MathHelper.clamp(-vy * 0.12f, -0.06f, 0.06f), dt, 0.1f);
            }
            float k = s.bobStrength.getValue() * p.bob();
            y += (float) -Math.abs(Math.sin(bobPhase)) * 0.018f * bobAmount * k;
            x += (float) Math.sin(bobPhase) * 0.012f * bobAmount * k;
            roll += (float) Math.sin(bobPhase) * 1.2f * bobAmount * k;
            y += airY * k;
        }

        // 4. the swing's arc on the hand: pull back, drive forward, settle
        Swing sw = swing(p);
        if (s.swingAnimation.getValue() && sw.active) {
            float k = s.swingStrength.getValue() * p.swing();
            z += sw.reach * 0.10f * k;               // back (+) then forward (-)
            x += -sw.arc * 0.05f * k;                // the arc sweeps across
            y += sw.arc * 0.03f * k * (0.4f + p.vertical());
            yaw += -sw.arc * 8f * k;
        }

        m.translate(x + s.handX(), y + s.handY(), z);
        if (yaw != 0) m.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(yaw));
        if (pitch != 0) m.multiply(RotationAxis.POSITIVE_X.rotationDegrees(pitch));
        if (roll != 0) m.multiply(RotationAxis.POSITIVE_Z.rotationDegrees(roll));
    }

    /* ------------------------------------------------------------------ */
    /* Item (hand space)                                                   */
    /* ------------------------------------------------------------------ */

    void itemTransform(MatrixStack m, ItemStack stack) {
        Profile p = profileFor(stack);
        m.translate(s.itemX.getValue(), s.itemY.getValue(), s.itemZ.getValue());

        Swing sw = swing(p);
        if (s.swingAnimation.getValue() && sw.active) {
            float k = s.swingStrength.getValue() * p.swing();
            float angle = s.swingAngle.getValue();
            // the wrist: the blade cocks back, whips through the arc, overshoots a touch and settles
            m.multiply(RotationAxis.POSITIVE_X.rotationDegrees(-sw.arc * angle * 0.55f * k * (0.5f + p.vertical())));
            m.multiply(RotationAxis.POSITIVE_Z.rotationDegrees(sw.arc * angle * 0.35f * k * (1f - p.vertical() * 0.6f)));
            m.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(-sw.arc * angle * 0.2f * k));
        }

        float rot = s.itemRotation.getValue();
        if (rot != 0) m.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(rot));
        float scale = s.itemScale.getValue();
        if (scale != 1f) m.scale(scale, scale, scale);
    }

    /* ------------------------------------------------------------------ */
    /* Swing curve                                                         */
    /* ------------------------------------------------------------------ */

    /** reach: + is pulled back, - is driven forward; arc: 0 rest, 1 the far end of the swing. */
    private record Swing(boolean active, float reach, float arc) {
    }

    private Swing swing(Profile p) {
        if (mc.player == null) return new Swing(false, 0, 0);

        // A new vanilla swing starts the visual clock (its progress jumps back towards 0).
        float vanilla = mc.player.getHandSwingProgress(mc.getRenderTickCounter().getTickProgress(true));
        long now = System.currentTimeMillis();
        if (mc.player.handSwinging && vanilla < lastVanillaSwing - 0.2f || (swingStart < 0 && mc.player.handSwinging))
            swingStart = now;
        lastVanillaSwing = vanilla;
        if (swingStart < 0) return new Swing(false, 0, 0);

        float duration = 340f / Math.max(0.1f, s.swingSpeed.getValue() * p.tempo());
        float t = (now - swingStart) / duration;
        if (t >= 1f) {
            swingStart = -1;
            return new Swing(false, 0, 0);
        }

        if (!s.highQuality()) {
            // Low/Medium: one smooth sine, no phases
            float v = (float) Math.sin(t * Math.PI);
            return new Swing(true, -v * 0.6f, v);
        }

        float reach, arc;
        if (t < 0.35f) {                       // START: pull back, cock the wrist
            float k = easeOutCubic(t / 0.35f);
            reach = 0.5f * k;
            arc = -0.25f * k;
        } else if (t < 0.70f) {                // SWING: fast through the middle
            float k = easeInOutCubic((t - 0.35f) / 0.35f);
            reach = MathHelper.lerp(k, 0.5f, -0.8f);
            arc = MathHelper.lerp(k, -0.25f, 1f);
        } else {                               // END: return with a small overshoot
            float k = (t - 0.70f) / 0.30f;
            float back = s.ultraQuality() ? easeOutBack(k) : smoothstep(k);
            reach = MathHelper.lerp(back, -0.8f, 0f);
            arc = MathHelper.lerp(back, 1f, 0f);
        }
        return new Swing(true, reach, arc);
    }

    private static float easeOutCubic(float x) {
        float a = 1 - x;
        return 1 - a * a * a;
    }

    private static float easeInOutCubic(float x) {
        return x < 0.5f ? 4 * x * x * x : 1 - (float) Math.pow(-2 * x + 2, 3) / 2;
    }

    private static float smoothstep(float x) {
        return x * x * (3 - 2 * x);
    }

    /** Ease out with a slight overshoot past the target before settling. */
    private static float easeOutBack(float x) {
        float c1 = 1.25f, c3 = c1 + 1;
        float a = x - 1;
        return 1 + c3 * a * a * a + c1 * a * a;
    }
}
