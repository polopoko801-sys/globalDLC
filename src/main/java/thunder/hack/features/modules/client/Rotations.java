package thunder.hack.features.modules.client;

import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import thunder.hack.core.Managers;
import thunder.hack.core.manager.client.ModuleManager;
import thunder.hack.events.impl.EventFixVelocity;
import thunder.hack.events.impl.EventKeyboardInput;
import thunder.hack.events.impl.EventPlayerJump;
import thunder.hack.events.impl.EventPlayerTravel;
import thunder.hack.features.modules.Module;
import thunder.hack.features.modules.combat.Aura;
import thunder.hack.setting.Setting;

public class Rotations extends Module {
    public Rotations() {
        super("Rotations", Category.CLIENT);
    }

    private final Setting<MoveFix> moveFix = new Setting<>("MoveFix", MoveFix.Off);
    public final Setting<Boolean> clientLook = new Setting<>("ClientLook", false);

    public enum MoveFix {
        Off, Focused, Free
    }

    public float fixRotation;
    /**
     * Set by Kilaura while it has a target: Focused = W walks towards where the aura aims (at the target),
     * Free = W walks where the camera looks. Overrides the MoveFix setting; null = use the setting.
     */
    public MoveFix forced;

    private MoveFix mode() {
        return forced != null && !Float.isNaN(fixRotation) ? forced : moveFix.getValue();
    }
    private float lastYaw, lastPitch;

    public void onJump(EventPlayerJump e) {
        if (Float.isNaN(fixRotation) || mode() == MoveFix.Off || mc.player.isRiding())
            return;

        if (e.isPre()) {
            lastYaw = mc.player.getYaw();
            mc.player.setYaw(fixRotation);
        } else mc.player.setYaw(lastYaw);
    }

    public void onPlayerMove(EventFixVelocity event) {
        if (mode() == MoveFix.Free) {
            if (Float.isNaN(fixRotation) || mc.player.isRiding())
                return;
            event.setVelocity(fix(fixRotation, event.getMovementInput(), event.getSpeed()));
        }
    }

    public void modifyVelocity(EventPlayerTravel e) {
        if (ModuleManager.aura.isEnabled() && ModuleManager.aura.target != null && ModuleManager.aura.rotationMode.not(Aura.Mode.None)
                && ModuleManager.aura.elytraTarget.getValue() && Managers.PLAYER.ticksElytraFlying > 5) {
            if (e.isPre()) {
                lastYaw = mc.player.getYaw();
                lastPitch = mc.player.getPitch();

                mc.player.setYaw(fixRotation);
                mc.player.setPitch(ModuleManager.aura.rotationPitch);
            } else {
                mc.player.setYaw(lastYaw);
                mc.player.setPitch(lastPitch);
            }
            return;
        }

        if (mode() == MoveFix.Focused && !Float.isNaN(fixRotation) && !mc.player.isRiding()) {
            if (e.isPre()) {
                lastYaw = mc.player.getYaw();
                mc.player.setYaw(fixRotation);
            } else {
                mc.player.setYaw(lastYaw);
            }
        }
    }

    public void onKeyInput(EventKeyboardInput e) {
        if (mode() == MoveFix.Free) {
            if (Float.isNaN(fixRotation) || mc.player.isRiding())
                return;

            float mF = mc.player.input.movementVector.y;
            float mS = mc.player.input.movementVector.x;
            float delta = (mc.player.getYaw() - fixRotation) * MathHelper.RADIANS_PER_DEGREE;
            float cos = MathHelper.cos(delta);
            float sin = MathHelper.sin(delta);
            mc.player.input.movementVector = new net.minecraft.util.math.Vec2f((float)(Math.round(mS * cos - mF * sin)), mc.player.input.movementVector.y);
            mc.player.input.movementVector = new net.minecraft.util.math.Vec2f(mc.player.input.movementVector.x, (float)(Math.round(mF * cos + mS * sin)));
        }
    }

    private Vec3d fix(float yaw, Vec3d movementInput, float speed) {
        double d = movementInput.lengthSquared();
        if (d < 1.0E-7)
            return Vec3d.ZERO;
        Vec3d vec3d = (d > 1.0 ? movementInput.normalize() : movementInput).multiply(speed);
        float f = MathHelper.sin(yaw * MathHelper.RADIANS_PER_DEGREE);
        float g = MathHelper.cos(yaw * MathHelper.RADIANS_PER_DEGREE);
        return new Vec3d(vec3d.x * (double) g - vec3d.z * (double) f, vec3d.y, vec3d.z * (double) g + vec3d.x * (double) f);
    }

    @Override
    public boolean isToggleable() {
        return false;
    }
}
