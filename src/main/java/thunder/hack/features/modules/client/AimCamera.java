package thunder.hack.features.modules.client;

import net.minecraft.entity.Entity;
import thunder.hack.core.manager.client.ModuleManager;
import thunder.hack.core.rotation.AimController;
import thunder.hack.core.rotation.CloudanCameraController;
import thunder.hack.core.rotation.PlayerRotationController;
import thunder.hack.core.rotation.RotationState;
import thunder.hack.features.modules.Module;
import thunder.hack.setting.Setting;

/**
 * Where aim, body and camera are told apart.
 *
 * <p>The client used to have one rotation that the aura, the camera and the model all wrote to, which is
 * why changing target could swing your screen and why a body animation could change which block a click
 * landed on. This splits them into four and gives each an owner:
 *
 * <pre>
 *   AimController            -> aimYaw / aimPitch      (what an attack points at)
 *   CloudanCameraController  -> cameraYaw / cameraPitch (what YOU are looking at)
 *   PlayerRotationController -> bodyYaw / bodyPitch     (which way the model faces)
 *   RotationState            -> targetYaw / targetPitch (raw angle to the target - an input, not a rotation)
 * </pre>
 *
 * <p>The rule that falls out of it: <b>a target change never moves the camera</b> unless you turned
 * Camera Follow on yourself. Default is off.
 *
 * <p>This module is the settings surface and the per-frame driver. It holds no logic of its own - the
 * controllers do the work, so nothing here has to be duplicated by the next thing that wants to aim.
 */
public class AimCamera extends Module {
    public AimCamera() {
        super("AimCamera", Category.CLIENT);
    }

    /* ---- aim ---- */
    private final Setting<AimController.AimMode> aimMode = new Setting<>("AimMode", AimController.AimMode.Normal);
    private final Setting<AimController.TargetPriority> priority = new Setting<>("TargetPriority", AimController.TargetPriority.Distance);
    private final Setting<Float> strength = new Setting<>("Strength", 0.35f, 0.05f, 1f, v -> aimMode.getValue() == AimController.AimMode.AimAssist);
    private final Setting<Integer> smoothing = new Setting<>("Smoothing", 140, 10, 600, v -> aimMode.getValue() == AimController.AimMode.AimAssist);
    private final Setting<Float> maxAngle = new Setting<>("MaxAngle", 35f, 1f, 180f, v -> aimMode.getValue() == AimController.AimMode.AimAssist);
    private final Setting<Integer> prediction = new Setting<>("Prediction", 0, 0, 10);

    /* ---- camera ---- */
    /**
     * OFF by default, and that default is the feature. With it off nothing in the client may turn your
     * view - not a new target, not a lock, not an aura.
     */
    private final Setting<Boolean> cameraFollow = new Setting<>("CameraFollowTarget", false);
    private final Setting<Float> horizontalSpeed = new Setting<>("HorizontalSpeed", 140f, 10f, 720f, v -> cameraFollow.getValue());
    private final Setting<Float> verticalSpeed = new Setting<>("VerticalSpeed", 90f, 10f, 720f, v -> cameraFollow.getValue());
    private final Setting<Integer> cameraSmoothing = new Setting<>("CameraSmoothing", 120, 10, 600, v -> cameraFollow.getValue());
    private final Setting<Float> deadzone = new Setting<>("Deadzone", 2.5f, 0f, 30f, v -> cameraFollow.getValue());
    private final Setting<Float> returnSpeed = new Setting<>("ReturnSpeed", 0f, 0f, 360f, v -> cameraFollow.getValue());

    /* ---- body ---- */
    private final Setting<PlayerRotationController.SpinMode> spinMode = new Setting<>("BodyMode", PlayerRotationController.SpinMode.Off);
    private final Setting<Float> spinSpeed = new Setting<>("SpinSpeed", 180f, 20f, 36000f, v -> spinMode.getValue() == PlayerRotationController.SpinMode.Spin);

    private final Setting<Boolean> debug = new Setting<>("Debug", false);

    @Override
    public boolean isToggleable() {
        // Always on: the split is the client's rotation architecture, not an effect you switch off. The
        // defaults (Normal aim, no camera follow, no spin) are exactly vanilla behaviour anyway.
        return false;
    }

    @Override
    public void onEnable() {
        RotationState.reset();
        AimController.reset();
        CloudanCameraController.reset();
        PlayerRotationController.reset();
    }

    /**
     * Driven straight from {@link thunder.hack.core.manager.player.PlayerManager}, not from the event bus.
     *
     * <p>The module is not toggleable - the split IS the client's rotation architecture, and its defaults
     * are plain vanilla behaviour - so it never gets "enabled" and would never receive a module tick. The
     * same reason Rotations is called directly.
     */
    public void tick() {
        push();

        // The aura picked somebody this tick; the aim layer decides whether to honour that (Normal) or
        // keep what it locked onto (TargetLock), and produces the attack angles either way.
        Entity candidate = ModuleManager.kilaura.isEnabled() ? ModuleManager.kilaura.getTarget() : null;
        AimController.update(candidate);
    }

    /** Per-frame work: the camera and the body both animate, so neither belongs on the tick. */
    public void frame() {
        if (fullNullCheck()) return;

        push();
        CloudanCameraController.update(AimController.locked() != null
                ? AimController.locked()
                : (ModuleManager.kilaura.isEnabled() ? ModuleManager.kilaura.getTarget() : null));

        // The driver is the only thing that advances the spin clock; the render hook just applies the
        // offset it produced. Based on the BODY yaw, because that is what the offset sits on top of.
        if (PlayerRotationController.isActive())
            PlayerRotationController.update(mc.player.bodyYaw);
    }

    /** Whether THIS module wants the body spinning - so another module turning it off does not fight it. */
    public boolean wantsSpin() {
        return spinMode.getValue() == PlayerRotationController.SpinMode.Spin;
    }

    /** Copies the settings into the controllers. Cheap, and keeps them free of any Setting dependency. */
    private void push() {
        AimController.mode = aimMode.getValue();
        AimController.priority = priority.getValue();
        AimController.strength = strength.getValue();
        AimController.smoothing = smoothing.getValue();
        AimController.maxAngle = maxAngle.getValue();
        AimController.prediction = prediction.getValue();

        CloudanCameraController.followEnabled = cameraFollow.getValue();
        CloudanCameraController.horizontalSpeed = horizontalSpeed.getValue();
        CloudanCameraController.verticalSpeed = verticalSpeed.getValue();
        CloudanCameraController.smoothing = cameraSmoothing.getValue();
        CloudanCameraController.deadzone = deadzone.getValue();
        CloudanCameraController.returnSpeed = returnSpeed.getValue();

        // The single authority on whether the body spins, decided fresh every tick (Kilaura no longer has
        // its own Spinning toggle - it is a 1:1 Rockstar KillAura now).
        if (spinMode.getValue() != PlayerRotationController.SpinMode.Off) {
            PlayerRotationController.mode = spinMode.getValue();
            PlayerRotationController.spinSpeed = spinSpeed.getValue();
        } else {
            PlayerRotationController.mode = PlayerRotationController.SpinMode.Off;
        }
    }

    @Override
    public String getDisplayInfo() {
        if (!debug.getValue()) return null;

        return String.format("cam %.0f | aim %.0f | body %.0f",
                RotationState.cameraYaw, RotationState.aimYaw, RotationState.bodyYaw);
    }
}
