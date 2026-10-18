package thunder.hack.features.modules.render;

import thunder.hack.gui.anim.AnimData;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.item.ItemDisplayContext;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.LivingEntity;
import net.minecraft.item.CrossbowItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.HandSwingC2SPacket;
import net.minecraft.util.Arm;
import net.minecraft.util.Hand;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.RotationAxis;
import org.jetbrains.annotations.NotNull;
import thunder.hack.ThunderHack;
import thunder.hack.core.manager.client.ModuleManager;
import thunder.hack.events.impl.EventHeldItemRenderer;
import thunder.hack.events.impl.PacketEvent;
import thunder.hack.injection.accesors.IHeldItemRenderer;
import thunder.hack.features.modules.Module;
import thunder.hack.features.modules.combat.Aura;
import thunder.hack.setting.Setting;
import thunder.hack.setting.impl.SettingGroup;

public class Animations extends Module {
    public Animations() {
        super("Animations", Category.RENDER);
    }

    private final Setting<Boolean> onlyaura = new Setting<>("OnlyAura", false);
    public Setting<Boolean> oldAnimationsM = new Setting<>("DisableSwapMain", true);
    public Setting<Boolean> oldAnimationsOff = new Setting<>("DisableSwapOff", true);
    public static Setting<Boolean> slowAnimation = new Setting<>("SlowAnimation", true);
    public static Setting<Integer> slowAnimationVal = new Setting<>("SlowValue", 12, 1, 50);


    /* ---- Hand Animation: the first-person hand + item system (HandAnimator). Visual only. ---- */
    public enum AnimQuality { Low, Medium, High, Ultra }

    private final Setting<SettingGroup> handGroup = new Setting<>("HandAnimation", new SettingGroup(false, 0));
    public final Setting<Boolean> handEnabled = new Setting<>("HandEnabled", true).addToGroup(handGroup);
    public final Setting<Boolean> idleMotion = new Setting<>("IdleMotion", true).addToGroup(handGroup);
    public final Setting<Float> idleStrength = new Setting<>("IdleStrength", 1f, 0f, 3f).addToGroup(handGroup);
    public final Setting<Boolean> swingAnimation = new Setting<>("SwingAnimation", true).addToGroup(handGroup);
    public final Setting<Float> swingStrength = new Setting<>("SwingStrength", 1f, 0f, 3f).addToGroup(handGroup);
    public final Setting<Float> swingSpeed = new Setting<>("SwingSpeed", 1f, 0.3f, 3f).addToGroup(handGroup);
    public final Setting<Float> swingAngle = new Setting<>("SwingAngle", 45f, 0f, 120f).addToGroup(handGroup);
    public final Setting<Boolean> handSway = new Setting<>("HandSway", true).addToGroup(handGroup);
    public final Setting<Float> swayStrength = new Setting<>("SwayStrength", 1f, 0f, 3f).addToGroup(handGroup);
    public final Setting<Boolean> movementBob = new Setting<>("MovementBob", true).addToGroup(handGroup);
    public final Setting<Float> bobStrength = new Setting<>("BobStrength", 1f, 0f, 3f).addToGroup(handGroup);
    public final Setting<Float> horizontalOffset = new Setting<>("HorizontalOffset", 0f, -0.5f, 0.5f).addToGroup(handGroup);
    public final Setting<Float> verticalOffset = new Setting<>("VerticalOffset", 0f, -0.5f, 0.5f).addToGroup(handGroup);
    public final Setting<Float> itemRotation = new Setting<>("ItemRotation", 0f, -180f, 180f).addToGroup(handGroup);
    public final Setting<Float> itemScale = new Setting<>("ItemScale", 1f, 0.4f, 2f).addToGroup(handGroup);
    public final Setting<Float> itemX = new Setting<>("ItemX", 0f, -0.5f, 0.5f).addToGroup(handGroup);
    public final Setting<Float> itemY = new Setting<>("ItemY", 0f, -0.5f, 0.5f).addToGroup(handGroup);
    public final Setting<Float> itemZ = new Setting<>("ItemZ", 0f, -0.5f, 0.5f).addToGroup(handGroup);
    public final Setting<AnimQuality> animQuality = new Setting<>("AnimationQuality", AnimQuality.High).addToGroup(handGroup);

    private final HandAnimator handAnimator = new HandAnimator(this);

    float handX() { return horizontalOffset.getValue(); }
    float handY() { return verticalOffset.getValue(); }
    boolean mediumQuality() { return animQuality.getValue().ordinal() >= AnimQuality.Medium.ordinal(); }
    boolean highQuality() { return animQuality.getValue().ordinal() >= AnimQuality.High.ordinal(); }
    boolean ultraQuality() { return animQuality.getValue() == AnimQuality.Ultra; }

    /* ------------------------------------------------------------------ */
    /* Sword                                                               */
    /* ------------------------------------------------------------------ */

    /**
     * How a sword is carried and spun in the hand.
     *
     * <p>Applied through {@link EventHeldItemRenderer} rather than the module's own first-person
     * renderer: that renderer is driven by a mixin the 1.21.11 port had to disable, and this hook is the
     * one that is actually live. It also means the sword keeps every bit of vanilla's swing and equip
     * animation and only has this laid on top, instead of replacing the lot.
     */
    public enum SwordStyle {
        /** Turns end over end, towards you. */
        Forward,
        /** Rolls around the blade's own length. */
        Roll,
        /** Sweeps round in a circle in front of you. */
        Orbit,
        /** Two axes at unrelated speeds, so it never repeats the same pose twice in a row. */
        Figure8
    }

    private final Setting<SettingGroup> sword = new Setting<>("Sword", new SettingGroup(false, 0));
    private final Setting<Boolean> swordEnabled = new Setting<>("SwordAnimation", false).addToGroup(sword);
    /** Thrust: lay the sword flat, blade forward, and jab it towards the target on a hit (LayPitch/Yaw/Roll, ThrustDistance). */
    public final Setting<Boolean> thrustEnabled = new Setting<>("ThrustStab", false).addToGroup(sword);
    private final Setting<Float> layPitch = new Setting<>("LayPitch", -65f, -180f, 180f, v -> thrustEnabled.getValue()).addToGroup(sword);
    private final Setting<Float> layYaw = new Setting<>("LayYaw", 12f, -180f, 180f, v -> thrustEnabled.getValue()).addToGroup(sword);
    private final Setting<Float> layRoll = new Setting<>("LayRoll", -80f, -180f, 180f, v -> thrustEnabled.getValue()).addToGroup(sword);
    private final Setting<Float> thrust = new Setting<>("ThrustDistance", 0.35f, 0f, 1.2f, v -> thrustEnabled.getValue()).addToGroup(sword);
    private final Setting<SwordStyle> swordStyle = new Setting<>("SwordStyle", SwordStyle.Forward).addToGroup(sword);
    /** Degrees per second. */
    private final Setting<Float> swordSpeed = new Setting<>("SwordSpeed", 220f, 20f, 2000f).addToGroup(sword);
    /** Only spin while it is actually swinging, rather than the whole time it is held. */
    private final Setting<Boolean> onlyWhileSwinging = new Setting<>("OnlyWhenSwinging", false).addToGroup(sword);
    private final Setting<Float> swordScale = new Setting<>("SwordScale", 1f, 0.4f, 2f).addToGroup(sword);
    private final Setting<Float> swordX = new Setting<>("SwordX", 0f, -1f, 1f).addToGroup(sword);
    private final Setting<Float> swordY = new Setting<>("SwordY", 0f, -1f, 1f).addToGroup(sword);
    private final Setting<Float> swordZ = new Setting<>("SwordZ", 0f, -1f, 1f).addToGroup(sword);

    @meteordevelopment.orbit.EventHandler
    public void onHeldItem(EventHeldItemRenderer event) {
        // Hand Animation: the hand (and with it the item) - camera space, before vanilla places the arm
        if (handEnabled.getValue() && !fullNullCheck())
            handAnimator.handTransform(event.getStack(), event.getItem(), event.getHand() == net.minecraft.util.Hand.MAIN_HAND);

        // Thrust: the stab is a move of the whole hand towards the target, in camera space - done here, at
        // the start of the item draw. The laid-flat pose is applied later, in the hand (beforeItemDraw).
        if (thrustEnabled.getValue() && !fullNullCheck() && event.getHand() == net.minecraft.util.Hand.MAIN_HAND
                && event.getItem().isIn(net.minecraft.registry.tag.ItemTags.SWORDS)) {
            float swing = mc.player.getHandSwingProgress(mc.getRenderTickCounter().getTickProgress(true));
            float s = MathHelper.sin(MathHelper.sqrt(swing) * 3.1415927F);
            event.getStack().translate(0f, 0.04f * s, -thrust.getValue() * s);
        }

        if (!swordEnabled.getValue() || fullNullCheck()) return;
        if (event.getHand() != net.minecraft.util.Hand.MAIN_HAND) return;
        // SwordItem is gone on this version - weapons are data-driven now, so the tag is the only
        // thing that still knows what a sword is.
        if (!event.getItem().isIn(net.minecraft.registry.tag.ItemTags.SWORDS)) return;
        if (onlyWhileSwinging.getValue() && mc.player.handSwingProgress <= 0f) return;

        // Driven off the clock rather than off a tick counter, so the speed is in degrees per second
        // and looks the same at any frame rate.
        float angle = (System.currentTimeMillis() % 360000L) / 1000f * swordSpeed.getValue() % 360f;

        var stack = event.getStack();
        stack.translate(swordX.getValue(), swordY.getValue(), swordZ.getValue());

        // Rotated about the middle of the item, not its corner - around the corner it orbits the hand
        // instead of turning on the spot.
        stack.translate(0.5f, 0.5f, 0.5f);

        switch (swordStyle.getValue()) {
            case Forward -> stack.multiply(RotationAxis.POSITIVE_X.rotationDegrees(angle));
            case Roll -> stack.multiply(RotationAxis.POSITIVE_Z.rotationDegrees(angle));
            case Orbit -> stack.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(angle));
            case Figure8 -> {
                stack.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(angle));
                stack.multiply(RotationAxis.POSITIVE_X.rotationDegrees(angle * 0.62f));
            }
        }

        stack.scale(swordScale.getValue(), swordScale.getValue(), swordScale.getValue());
        stack.translate(-0.5f, -0.5f, -0.5f);
    }

    public boolean shouldChangeAnimationDuration() {
        return isEnabled() && (!onlyaura.getValue() || ModuleManager.aura.isEnabled() && Aura.target != null);
    }

    /** Acts as a button: tick it on in the ClickGUI and the live editor window opens. */
    public final Setting<Boolean> openEditor = new Setting<>("OpenEditor", false);

    @Override
    public void onUpdate() {
        if (openEditor.getValue()) {
            openEditor.setValue(false);
            mc.execute(() -> mc.setScreen(new thunder.hack.gui.anim.AnimationScreen()));
        }
    }

    /**
     * Opens the editor the INSTANT the button is clicked. onUpdate() only runs on the game tick, which is paused in
     * singleplayer while a screen (the ClickGUI) is open - so the tick-based path never fired and "OpenEditor nie
     * dziala". EventSetting is posted synchronously from setValue, so it works even while paused.
     */
    @meteordevelopment.orbit.EventHandler
    public void onSettingChange(thunder.hack.events.impl.EventSetting e) {
        if (e.getSetting() == openEditor && openEditor.getValue()) {
            openEditor.setValueSilent(false);
            mc.execute(() -> mc.setScreen(new thunder.hack.gui.anim.AnimationScreen()));
        }
    }

    /** Applies the configured hand/sword animation to the held item - this is what makes the settings do something. */
    /** Play the active editor preset (/anim) when a sword is held and swinging: Start -> End by its easing curve. */
    public final Setting<Boolean> editorAnim = new Setting<>("EditorAnimation", true).addToGroup(sword);

    private long animStart = -1;   // when the current swing animation began (self-timed, not swingProgress)
    private float prevSwing;

    public void beforeItemDraw(net.minecraft.util.Hand hand, ItemStack item, float swingProgress, MatrixStack matrices) {
        boolean editor = editorAnim.getValue() && hand == net.minecraft.util.Hand.MAIN_HAND
                && item.isIn(net.minecraft.registry.tag.ItemTags.SWORDS);

        // handAnimator fights the editor pose (two systems moving the same item = the shake), so skip it when the
        // editor animation owns the item.
        if (handEnabled.getValue() && !editor) handAnimator.itemTransform(matrices, item);

        if (editor) {
            AnimData.Preset ap = AnimData.current();

            // SELF-TIMED playback. swingProgress steps/jitters, which made the item shake; instead we only use its
            // RISING EDGE to trigger, then run a perfectly smooth clock. CRITICAL: only start a NEW animation when
            // one is NOT already playing (animStart < 0) - otherwise continuous attacking re-triggers it every
            // frame and the restart IS the shake.
            if (swingProgress > 0.08f && prevSwing <= 0.08f && animStart < 0) animStart = System.currentTimeMillis();
            prevSwing = swingProgress;

            float phase = 0f;
            if (animStart > 0) {
                float dur = Math.max(140f, 520f / Math.max(0.3f, ap.swingSpeed));
                float t = (System.currentTimeMillis() - animStart) / dur;
                if (t >= 1f) animStart = -1;
                else phase = ap.returnBack ? MathHelper.sin(t * 3.1415927F) : t;
            }
            float e = AnimData.ease(phase);
            AnimData.Pose s = ap.start, en = ap.end;
            matrices.translate(AnimData.lerp(s.tx, en.tx, e), AnimData.lerp(s.ty, en.ty, e), AnimData.lerp(s.tz, en.tz, e));
            matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(AnimData.lerp(s.ry, en.ry, e)));
            matrices.multiply(RotationAxis.POSITIVE_X.rotationDegrees(AnimData.lerp(s.rx, en.rx, e)));
            matrices.multiply(RotationAxis.POSITIVE_Z.rotationDegrees(AnimData.lerp(s.rz, en.rz, e)));
            float sc = AnimData.lerp(s.scale, en.scale, e);
            matrices.scale(sc, sc, sc);
            return; // the editor pose owns the item - no thrust on top
        }

        if (!thrustEnabled.getValue() || hand != net.minecraft.util.Hand.MAIN_HAND) return;
        if (!item.isIn(net.minecraft.registry.tag.ItemTags.SWORDS)) return;
        float s = MathHelper.sin(MathHelper.sqrt(swingProgress) * 3.1415927F);
        matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(layYaw.getValue()));
        matrices.multiply(RotationAxis.POSITIVE_X.rotationDegrees(layPitch.getValue() - 10f * s));
        matrices.multiply(RotationAxis.POSITIVE_Z.rotationDegrees(layRoll.getValue()));
    }

}
