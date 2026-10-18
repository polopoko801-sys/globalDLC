package thunder.hack.features.modules.render;

import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.item.CrossbowItem;
import net.minecraft.item.FilledMapItem;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Arm;
import net.minecraft.util.Hand;
import net.minecraft.util.math.MathHelper;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import thunder.hack.core.manager.client.ModuleManager;
import thunder.hack.features.modules.Module;
import thunder.hack.features.modules.combat.Kilaura;
import thunder.hack.setting.Setting;

/**
 * 1:1 port of Rockstar Free 2.0 {@code SwingAnimations} together with the transforms it drives inside its patched
 * {@code ItemRenderer} (renderItemInFirstPerson / transformFirstPerson / transformFirstPersonItem / translate).
 *
 * <p>Rockstar replaced the whole main-hand transform while the aura had a target (or LMB was held with Aura Only
 * off). On 1.21.11 the same effect: the hand matrix is saved at the HEAD of renderFirstPersonItem, and right
 * before the item is drawn it is put back and the Rockstar transforms are applied instead of vanilla's.
 * GlStateManager.rotate(angle, x, y, z) normalises its axis (and a zero axis is a no-op), so {@link #rot} does too.
 */
public class SwingAnimations extends Module {
    public SwingAnimations() {
        super("SwingAnimations", Category.RENDER);
    }

    public enum SwordAnim {Spin, Swank, Sigma, Jello, Fap, Big, Astolfo, Neutral, Custom}

    public enum Item360Mode {Horizontal, Vertical, Zoom}

    public enum Item360Hand {All, Left, Right}

    public enum Item360Trigger {OnClick, Always}

    /**
     * The animation's pose is held the whole time the module is on (the sword lies in the Jello/Sigma/... position
     * right away) and a swing animates from there. Off = the Rockstar gating below, vanilla hand in between.
     */
    public final Setting<Boolean> idlePose = new Setting<>("Idle Pose", true);
    public final Setting<Boolean> auraOnly = new Setting<>("Aura Only", true, v -> !idlePose.getValue());
    /** The animation plays during a swing (a click, or an aura hit) and the hand is vanilla in between. */
    public final Setting<Boolean> onlyOnSwing = new Setting<>("Only On Swing", true, v -> !idlePose.getValue());
    public final Setting<SwordAnim> swordAnim = new Setting<>("Sword Animation", SwordAnim.Jello);
    public final Setting<Float> fapSmooth = new Setting<>("Fap Smooth", 5f, 0.5f, 10f, v -> swordAnim.is(SwordAnim.Fap));
    public final Setting<Float> x = new Setting<>("X", 0f, -1f, 1f, v -> swordAnim.is(SwordAnim.Custom));
    public final Setting<Float> y = new Setting<>("Y", 0f, -1f, 1f, v -> swordAnim.is(SwordAnim.Custom));
    public final Setting<Float> z = new Setting<>("Z", -1f, -1f, 1f, v -> swordAnim.is(SwordAnim.Custom));
    public final Setting<Float> rotate1 = new Setting<>("Rotate 1", 0f, -360f, 360f, v -> swordAnim.is(SwordAnim.Custom));
    public final Setting<Float> rotate2 = new Setting<>("Rotate 2", 0f, -360f, 360f, v -> swordAnim.is(SwordAnim.Custom));
    public final Setting<Float> rotate3 = new Setting<>("Rotate 3", 0f, -360f, 360f, v -> swordAnim.is(SwordAnim.Custom));
    public final Setting<Float> angle = new Setting<>("Angle", 50f, 0f, 360f, v -> swordAnim.is(SwordAnim.Custom));
    public final Setting<Float> swingX = new Setting<>("Swing X", 0f, -1f, 1f, v -> swordAnim.is(SwordAnim.Custom));
    public final Setting<Float> swingY = new Setting<>("Swing Y", 0f, -1f, 1f, v -> swordAnim.is(SwordAnim.Custom));
    public final Setting<Float> swingZ = new Setting<>("Swing Z", -1f, -1f, 1f, v -> swordAnim.is(SwordAnim.Custom));
    public final Setting<Float> swingRotate1 = new Setting<>("Swing Rotate 1", 0f, -360f, 360f, v -> swordAnim.is(SwordAnim.Custom));
    public final Setting<Float> swingRotate2 = new Setting<>("Swing Rotate 2", 0f, -360f, 360f, v -> swordAnim.is(SwordAnim.Custom));
    public final Setting<Float> swingRotate3 = new Setting<>("Swing Rotate 3", 0f, -360f, 360f, v -> swordAnim.is(SwordAnim.Custom));
    public final Setting<Float> swingAngle = new Setting<>("Swing angle", 50f, 0f, 360f, v -> swordAnim.is(SwordAnim.Custom));
    public final Setting<Float> scale = new Setting<>("Scale", 1f, 0.1f, 5f, v -> swordAnim.is(SwordAnim.Custom));
    public final Setting<Float> smooth = new Setting<>("Swing Smooth", 8f, 1f, 20f, v -> !swordAnim.is(SwordAnim.Neutral));
    public final Setting<Float> spinSpeed = new Setting<>("Spin Speed", 4f, 1f, 10f, v -> swordAnim.is(SwordAnim.Astolfo) || swordAnim.is(SwordAnim.Spin));
    public final Setting<Boolean> smallItem = new Setting<>("Mini Item", false, v -> !swordAnim.is(SwordAnim.Custom));
    public final Setting<Float> smallItemSize = new Setting<>("Mini Item Size", 0.7f, 0.1f, 0.9f, v -> !swordAnim.is(SwordAnim.Custom) && smallItem.getValue());
    public final Setting<Boolean> item360 = new Setting<>("Item360", false);
    public final Setting<Item360Mode> item360Mode = new Setting<>("Item360 Mode", Item360Mode.Horizontal, v -> item360.getValue());
    public final Setting<Item360Hand> item360Hand = new Setting<>("Item360 Hand", Item360Hand.All, v -> item360.getValue());
    public final Setting<Item360Trigger> item360Trigger = new Setting<>("Item360 Trigger", Item360Trigger.OnClick, v -> item360.getValue());
    public final Setting<Float> item360Speed = new Setting<>("Item360 Speed", 5f, 1f, 15f, v -> item360.getValue());

    /** OnClick: when the current single turn started (ms), -1 = not spinning; lastSwing detects a new swing. */
    private long spinStart = -1L;
    private float lastSwing;

    private float spin;
    private final Matrix4f savedPos = new Matrix4f();
    private final Matrix3f savedNormal = new Matrix3f();

    @Override
    public String getDisplayInfo() {
        return swordAnim.getValue().name();
    }

    /** HEAD of renderFirstPersonItem: the hand matrix before vanilla's equip/swing transforms. */
    public void saveHandMatrix(MatrixStack matrices) {
        savedPos.set(matrices.peek().getPositionMatrix());
        savedNormal.set(matrices.peek().getNormalMatrix());
    }

    /** Right before the item model is drawn (vanilla transforms already applied). */
    public void beforeItemDraw(Hand hand, ItemStack item, float tickDelta, float equipProgress, MatrixStack matrices) {
        if (!isEnabled() || mc.player == null || item.isEmpty() || item.getItem() instanceof FilledMapItem
                || item.getItem() instanceof CrossbowItem) return;
        // Rockstar only touches the branch where the item is NOT being used (eating/blocking/drawing keep vanilla)
        if (mc.player.isUsingItem() && mc.player.getActiveHand() == hand) return;

        Arm side = hand == Hand.MAIN_HAND ? mc.player.getMainArm() : mc.player.getMainArm().getOpposite();
        if (hand == Hand.MAIN_HAND) updateSpinTrigger(tickDelta);
        boolean auraActive = ModuleManager.kilaura.isEnabled() && Kilaura.target != null;
        boolean active = (mc.options.attackKey.isPressed() && !auraOnly.getValue()) || auraActive;
        if (onlyOnSwing.getValue()) {
            boolean swinging = mc.player.handSwinging || mc.player.getHandSwingProgress(tickDelta) > 0f;
            // with Aura Only on, a manual click still counts - the point is "only while hitting", not "only aura"
            active = swinging && (auraActive || !auraOnly.getValue() || mc.options.attackKey.isPressed() || mc.player.handSwinging);
        }
        if (idlePose.getValue()) active = true;

        if (active && side == mc.player.getMainArm()) {
            // replace vanilla's main-hand transform with the Rockstar one
            matrices.peek().getPositionMatrix().set(savedPos);
            matrices.peek().getNormalMatrix().set(savedNormal);
            customTransform(matrices, equipProgress, mc.player.getHandSwingProgress(tickDelta));
        } else if (item360.getValue()) {
            item360(matrices, side, hand);
        }
    }

    private void customTransform(MatrixStack m, float equipProgress, float swingprogress) {
        float f11 = 0.2f * MathHelper.sin(MathHelper.sqrt(swingprogress) * 6.2831855f);
        float smoothV = swingprogress * 0.8f - swingprogress * swingprogress * 0.8f;

        switch (swordAnim.getValue()) {
            case Spin -> {
                transformFirstPersonItem(m, 0f, 0f);
                translate(m);
                rot(m, spin * spinSpeed.getValue(), spin, 0f, spin);
                ++spin;
            }
            case Fap -> {
                m.translate(0.96f, -0.02f, -0.71999997f);
                m.translate(0f, -0f, 0f);
                rot(m, 45f, 0f, 1f, 0f);
                float var3 = MathHelper.sin(0f);
                float var4 = MathHelper.sin(MathHelper.sqrt(0f) * 3.1415927f);
                rot(m, var3 * -20f, 0f, 1f, 0f);
                rot(m, var4 * -20f, 0f, 0f, 1f);
                rot(m, var4 * -80f, 1f, 0f, 0f);
                m.translate(-0.5f, 0.2f, 0f);
                rot(m, 30f, 0f, 1f, 0f);
                rot(m, -80f, 1f, 0f, 0f);
                rot(m, 60f, 0f, 1f, 0f);
                // Rockstar drove this dip with the wall clock (a 255 ms triangle wave), so one ~300 ms swing could
                // dip twice. Same depth (0..254), but one dip and back per swing, tied to the swing itself.
                int alpha = Math.round(254f * (1f - Math.abs(2f * swingprogress - 1f)));
                float f13 = (f11 > 0.5) ? (1f - f11) : f11;
                m.translate(0.3f, -0f, 0.4f);
                rot(m, 0f, 0f, 0f, 1f);
                m.translate(0f, 0.5f, 0f);
                rot(m, 90f, 1f, 0f, -1f);
                m.translate(0.6f, 0.5f, 0f);
                rot(m, -90f, 1f, 0f, -1f);
                rot(m, -10f, 1f, 0f, -1f);
                rot(m, -f13 * 10f, 10f, 10f, -9f);
                rot(m, 10f, -1f, 0f, 0f);
                m.translate(0.0, 0.0, -0.5);
                rot(m, mc.player.handSwinging ? (-alpha / fapSmooth.getValue()) : 1f, 1f, -0f, 1f);
                m.translate(0.0, 0.0, 0.5);
            }
            case Jello -> {
                m.translate(0.56f, -0.52f, -0.71999997f);
                m.translate(0f, equipProgress * -0.15f, 0f);
                rot(m, 45f, 0f, 1f, 0f);
                rot(m, smoothV * -90f, 0f, 1f, 0f);
                m.scale(0.37f, 0.37f, 0.37f);
                m.translate(-0.5f, 1f, 0f);
                rot(m, 30f, 0f, 1f, 0f);
                rot(m, -80f, 1f, 0f, 0f);
                rot(m, 60f, 0f, 1f, 0f);
            }
            case Sigma -> {
                float sigma = MathHelper.sin(MathHelper.sqrt(swingprogress) * 3.1415927f);
                m.translate(0.56f, -0.42f, -0.71999997f);
                m.translate(0f, equipProgress * 0.5f * -0.6f, 0f);
                rot(m, 45f, 0f, 1f, 0f);
                float h1 = MathHelper.sin(0f);
                float h2 = MathHelper.sin(MathHelper.sqrt(0f) * 3.1415927f);
                rot(m, h1 * -20f, 0f, 1f, 0f);
                rot(m, h2 * -20f, 0f, 0f, 1f);
                rot(m, h2 * -80f, 1f, 0f, 0f);
                m.scale(0.4f, 0.4f, 0.4f);
                rot(m, -sigma * 55f / 2f, -8f, -0f, 9f);
                rot(m, -sigma * 45f, 1f, sigma / 2f, -0f);
                m.translate(-0.5f, 0.2f, 0f);
                rot(m, 30f, 0f, 1f, 0f);
                rot(m, -80f, 1f, 0f, 0f);
                rot(m, 60f, 0f, 1f, 0f);
                m.translate(1.2, 0.3, 0.5);
                m.translate(-1f, mc.player.isSneaking() ? -0.1f : -0.2f, 0.2f);
                m.scale(1.2f, 1.2f, 1.2f);
            }
            case Swank -> {
                m.translate(0.56f, -0.52f, -0.9999997f);
                m.translate(0f, 0f, 0f);
                transformFirstPersonItem(m, equipProgress / 2f, swingprogress);
                m.translate(-0.7f, 0.2f, 0f);
                float f12 = MathHelper.sin(MathHelper.sqrt(swingprogress) * 3.1415927f);
                rot(m, f12 * 30f, -f12, -0f, 9f);
                rot(m, f12 * 40f, 1f, -f12, -0f);
            }
            case Astolfo -> {
                rot(m, (float) (System.currentTimeMillis() / 16L * (int) (float) spinSpeed.getValue() % 360L), 0f, 0f, -0.1f);
                transformFirstPersonItem(m, 0f, 0f);
                translate(m);
            }
            case Big -> {
                m.translate(0.56f, -0.52f, -0.71999997f);
                m.translate(0f, 0f, 0f);
                rot(m, 45f, 0f, 1f, 0f);
                float sin1 = MathHelper.sin(swingprogress * swingprogress * 3.1415927f);
                float sin2 = MathHelper.sin(MathHelper.sqrt(swingprogress) * 3.1415927f);
                rot(m, sin1 * -20f, 0f, 1f, 0f);
                rot(m, sin2 * -20f, 0f, 0f, 1f);
                rot(m, sin2 * -40f, 1f, 0f, 0f);
                m.scale(0.8f, 0.8f, 0.8f);
                m.translate(-0.5f, 0.2f, 0f);
                rot(m, 30f, 0f, 1f, 0f);
                rot(m, -80f, 1f, 0f, 0f);
                rot(m, 60f, 0f, 1f, 0f);
            }
            case Neutral -> {
                transformFirstPersonItem(m, 0f, 0f);
                translate(m);
            }
            case Custom -> {
                transformFirstPersonItem(m, 0f, 0f);
                float s = scale.getValue();
                if (mc.player.handSwinging) {
                    m.translate(swingX.getValue(), swingY.getValue(), swingZ.getValue());
                    rot(m, swingAngle.getValue(), swingRotate1.getValue(), swingRotate2.getValue(), swingRotate3.getValue());
                } else {
                    m.translate(x.getValue(), y.getValue(), z.getValue());
                    rot(m, angle.getValue(), rotate1.getValue(), rotate2.getValue(), rotate3.getValue());
                }
                m.scale(s, s, s);
            }
        }

        if (smallItem.getValue()) {
            float s = smallItemSize.getValue();
            m.scale(s, s, s);
        }
    }

    /**
     * transformFirstPerson's Item360 branch: vanilla ends the swing offset with rotate(i * -45, Y); Rockstar
     * replaced that last rotation with the spin, so it is undone here first. A hand filtered out by Item360 Hand
     * returned early in Rockstar - before the -45 - and that is kept.
     */
    private void item360(MatrixStack m, Arm side, Hand hand) {
        float a;
        if (item360Trigger.is(Item360Trigger.OnClick)) {
            // one full turn per click, only on the hand that swung; idle = vanilla, untouched
            if (spinStart < 0 || hand != mc.player.preferredHand) return;
            float duration = 2000f / item360Speed.getValue();
            float p = (System.currentTimeMillis() - spinStart) / duration;
            if (p >= 1f) {
                spinStart = -1L;
                return;
            }
            a = (1f - (float) Math.pow(1f - p, 3)) * 360f;
        } else {
            a = (float) (System.currentTimeMillis() / (int) (float) item360Speed.getValue() % 360L);
        }
        int i = side == Arm.RIGHT ? 1 : -1;
        rot(m, i * 45f, 0f, 1f, 0f);
        Item360Hand h = item360Hand.getValue();
        if ((h == Item360Hand.Left && side != Arm.LEFT) || (h == Item360Hand.Right && side != Arm.RIGHT)) return;
        rot(m, a, 0f, item360Mode.is(Item360Mode.Horizontal) ? 1f : 0f, item360Mode.is(Item360Mode.Vertical) ? a : 0f);
        m.translate(0f, 0.02f, 0f);
    }

    /** A new swing (click) = swing progress appears or restarts; starts a turn unless one is still running. */
    private void updateSpinTrigger(float tickDelta) {
        float sp = mc.player.getHandSwingProgress(tickDelta);
        boolean newSwing = sp > 0f && (lastSwing <= 0f || sp < lastSwing - 0.05f);
        if (newSwing && spinStart < 0) spinStart = System.currentTimeMillis();
        lastSwing = sp;
    }

    private static void transformFirstPersonItem(MatrixStack m, float equipProgress, float swingProgress) {
        m.translate(0.56f, -0.44f, -0.71999997f);
        m.translate(0f, equipProgress * -0.6f, 0f);
        rot(m, 45f, 0f, 1f, 0f);
        float f = MathHelper.sin(swingProgress * swingProgress * 3.1415927f);
        float f2 = MathHelper.sin(MathHelper.sqrt(swingProgress) * 3.1415927f);
        rot(m, f * -20f, 0f, 0f, 0f);
        rot(m, f2 * -20f, 0f, 0f, 1f);
        rot(m, f2 * -80f, 0.01f, 0f, 0f);
        m.translate(0.4f, 0.2f, 0.2f);
    }

    private static void translate(MatrixStack m) {
        m.translate(-0.5f, 0.08f, 0f);
        rot(m, 20f, 0f, 1f, 0f);
        rot(m, -80f, 1f, 0f, 0f);
        rot(m, 20f, 0f, 1f, 0f);
    }

    /** GlStateManager.rotate: degrees about a (normalised) axis; a zero axis does nothing. */
    private static void rot(MatrixStack m, float degrees, float ax, float ay, float az) {
        float len = (float) Math.sqrt(ax * ax + ay * ay + az * az);
        if (len < 1.0E-4f || degrees == 0f) return;
        m.multiply(new Quaternionf().rotationAxis((float) Math.toRadians(degrees), ax / len, ay / len, az / len));
    }
}
