package thunder.hack.features.modules.render;

import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import thunder.hack.features.modules.Module;
import thunder.hack.features.modules.client.HudEditor;
import thunder.hack.setting.Setting;
import thunder.hack.setting.impl.ColorSetting;
import thunder.hack.utility.render.Render2DEngine;
import thunder.hack.utility.render.TextureStorage;
import thunder.hack.utility.render.animation.Easing;
import thunder.hack.utility.render.core.CloudanAnimationEngine;
import thunder.hack.utility.render.core.CloudanBatchRenderer;
import thunder.hack.utility.render.core.CloudanCullingSystem;
import thunder.hack.utility.render.core.CloudanGlowRenderer;
import thunder.hack.utility.render.core.CloudanRenderCore;

import java.awt.Color;

/**
 * A sigil on the ground under the player that reacts to what they are doing.
 *
 * <p>Standing still it breathes. Sprinting it turns faster. Jumping it throws one expanding ring and
 * lets it fade. Those three states are the whole feature - a symbol that just spins at a constant rate
 * is wallpaper, and stops being noticed within a minute.
 *
 * <p>It follows the player rather than being pinned to them: the centre eases towards your feet, so
 * walking drags it and stopping lets it catch up. Being exactly under you at all times is what makes
 * this kind of effect look stuck to the camera instead of laid on the floor.
 */
public class GroundSigil extends Module {
    public GroundSigil() {
        super("GroundSigil", Category.RENDER);
    }

    /**
     * Blocks lights up the real surfaces you are standing on; Sigil is the painted symbol on the floor.
     *
     * <p>Blocks is the default because a flat sprite lies across everything underneath it - it cuts
     * through a step, floats over a hole and sinks into a slab, because it is one square and the ground
     * is not. Lighting the actual block faces follows whatever is really there.
     */
    private final Setting<Style> style = new Setting<>("Style", Style.Blocks);
    /** How far the lit patch reaches, in blocks. */
    private final Setting<Integer> blockRadius = new Setting<>("BlockRadius", 5, 1, 16, v -> style.getValue() != Style.Sigil);
    /**
     * How sharply the light dies away from you. Higher fades sooner, so only the blocks at your feet
     * stay bright.
     */
    private final Setting<Float> falloff = new Setting<>("Falloff", 1.6f, 0.4f, 4f, v -> style.getValue() != Style.Sigil);
    /** Draws a bright edge round each lit face, so the individual blocks stay readable. */
    private final Setting<Boolean> blockOutline = new Setting<>("BlockOutline", true, v -> style.getValue() != Style.Sigil);
    private final Setting<Float> radius = new Setting<>("Radius", 1.7f, 0.5f, 6f, v -> style.getValue() != Style.Blocks);
    private final Setting<Mode> colorMode = new Setting<>("ColorMode", Mode.Sync);
    private final Setting<ColorSetting> color = new Setting<>("Color", new ColorSetting(0xFF9B5CFF), v -> colorMode.getValue() == Mode.Custom);
    private final Setting<Integer> opacity = new Setting<>("Opacity", 150, 20, 255);
    private final Setting<Boolean> glow = new Setting<>("Glow", true);
    private final Setting<Boolean> innerRing = new Setting<>("InnerRing", true);
    private final Setting<Boolean> jumpBurst = new Setting<>("JumpBurst", true);
    /** Seconds for one turn while standing. Sprinting multiplies this. */
    private final Setting<Float> idleSeconds = new Setting<>("IdleSeconds", 22f, 3f, 90f);
    private final Setting<Float> sprintMultiplier = new Setting<>("SprintSpeedup", 3.2f, 1f, 8f);
    private final Setting<Float> followSpeed = new Setting<>("FollowSpeed", 0.22f, 0.02f, 1f);
    private final Setting<Float> animationSpeed = new Setting<>("AnimationSpeed", 1f, 0.1f, 4f);
    private final Setting<Boolean> throughWalls = new Setting<>("ThroughWalls", false);
    private final Setting<Integer> renderDistance = new Setting<>("RenderDistance", 48, 8, 128);

    private enum Mode {
        Custom, Sync
    }

    public enum Style {
        /** Light up the real block faces under and around you. */
        Blocks,
        /** The painted symbol lying on the floor. */
        Sigil,
        Both
    }

    /** Where the sigil currently is, eased towards the player. */
    private Vec3d centre;
    /** Accumulated rotation. Kept separately so a speed change never makes the sigil jump. */
    private double angle;
    private long lastFrame;

    /** One expanding ring thrown by a jump. */
    private long burstAt;
    private boolean wasOnGround = true;

    private static final long BURST_MS = 600;

    @Override
    public void onEnable() {
        centre = null;
        angle = 0;
        lastFrame = 0;
        burstAt = 0;
    }

    @Override
    public void onRender3D(MatrixStack matrices) {
        if (fullNullCheck()) return;

        Vec3d feet = CloudanRenderCore.interpolated(mc.player).add(0, 0.02, 0);
        if (!CloudanCullingSystem.inRange(feet, renderDistance.getValue())) return;

        if (centre == null) centre = feet;
        else centre = centre.add(feet.subtract(centre).multiply(followSpeed.getValue()));

        if (!CloudanCullingSystem.isVisible(centre, radius.getValue() * 2)) return;

        long now = System.currentTimeMillis();
        float deltaMs = lastFrame == 0 ? 16f : Math.min(100f, now - lastFrame);
        lastFrame = now;

        // Leaving the ground is the jump; landing is not, so only the rising edge fires.
        boolean onGround = mc.player.isOnGround();
        if (jumpBurst.getValue() && wasOnGround && !onGround) burstAt = now;
        wasOnGround = onGround;

        // The rotation is ACCUMULATED, not computed from the clock, so changing speed mid-spin carries on
        // from where it was instead of snapping to wherever the new rate says it should be.
        boolean sprinting = mc.player.isSprinting();
        double perSecond = (Math.PI * 2 / Math.max(0.5f, idleSeconds.getValue()))
                * animationSpeed.getValue()
                * (sprinting ? sprintMultiplier.getValue() : 1f);
        angle += perSecond * deltaMs / 1000.0;

        Color base = colorMode.getValue() == Mode.Sync ? HudEditor.getColor(0) : color.getValue().getColorObject();
        Color tint = Render2DEngine.injectAlpha(base, opacity.getValue());

        float breathe = sprinting ? 1f : CloudanAnimationEngine.pulse(5.5f, 0.045f);
        double r = radius.getValue() * breathe;

        if (style.getValue() != Style.Sigil) lightBlocks(feet, base, breathe);

        if (style.getValue() != Style.Blocks) {
            CloudanBatchRenderer.groundSprite(TextureStorage.fxSigil, centre, r, angle, tint, throughWalls.getValue());

            if (innerRing.getValue())
                CloudanGlowRenderer.ring(centre, r * 0.55, -angle * 1.8,
                        Render2DEngine.injectAlpha(base, Math.round(opacity.getValue() * 0.7f)),
                        0.8f, throughWalls.getValue());
        }

        if (glow.getValue())
            CloudanGlowRenderer.halo(centre, r * 0.85, tint, 0.20f, throughWalls.getValue());

        drawBurst(now, base);
    }

    /**
     * Lights the real surface around the player, one block face at a time.
     *
     * <p>For every column in range it walks DOWN from the player's feet to find the first solid block,
     * so the light lands on the step, the slab or the floor of the hole - whatever is actually there.
     * A flat sprite cannot do that: it is one square laid at one height, and the ground rarely is.
     *
     * <p>Brightness falls off with distance from the player, so the patch has no edge to it - it simply
     * runs out. A hard circular boundary is the thing that would give it away as a drawn shape.
     */
    private void lightBlocks(Vec3d feet, Color base, float breathe) {
        int reach = blockRadius.getValue();
        double maxDistance = reach * breathe;
        BlockPos origin = BlockPos.ofFloored(feet);

        for (int dx = -reach; dx <= reach; dx++)
            for (int dz = -reach; dz <= reach; dz++) {
                double distance = Math.sqrt(dx * dx + dz * dz);
                if (distance > maxDistance) continue;

                BlockPos surface = surfaceUnder(origin.add(dx, 0, dz));
                if (surface == null) continue;

                // Measured to the block's own centre rather than to the column, so a block one step down
                // is correctly dimmer than one at your feet.
                double d = Math.sqrt(surface.toCenterPos().squaredDistanceTo(feet.x, surface.getY() + 0.5, feet.z));
                float strength = (float) Math.pow(Math.max(0, 1 - d / Math.max(0.5, maxDistance)), falloff.getValue());
                if (strength <= 0.02f) continue;

                Box box = new Box(surface);
                if (!CloudanCullingSystem.isVisible(box)) continue;

                int alpha = Math.round(opacity.getValue() * strength);
                if (alpha <= 2) continue;

                Color fill = Render2DEngine.injectAlpha(base, alpha);
                Color edge = Render2DEngine.injectAlpha(base, Math.min(255, alpha * 2));

                CloudanBatchRenderer.side(box, Direction.UP, fill, blockOutline.getValue(), edge, 1f);
            }
    }

    /**
     * The first solid block at or below this column, within a step or two of the player's level.
     *
     * <p>Searching a short way up as well as down is what lets the light climb onto a block you are
     * standing next to instead of disappearing under it.
     */
    private BlockPos surfaceUnder(BlockPos column) {
        for (int dy = 1; dy >= -3; dy--) {
            BlockPos pos = column.add(0, dy, 0);
            if (!mc.world.getBlockState(pos).isSolidBlock(mc.world, pos)) continue;
            // Only a face with open air above it is a surface you can see.
            if (mc.world.getBlockState(pos.up()).isSolidBlock(mc.world, pos.up())) continue;
            return pos;
        }
        return null;
    }

    /** The jump ring: expands outwards and fades, on an ease-out so it leaves fast and settles slow. */
    private void drawBurst(long now, Color base) {
        if (burstAt == 0) return;

        float t = (now - burstAt) / (float) BURST_MS;
        if (t >= 1f) {
            burstAt = 0;
            return;
        }

        float eased = Easing.CubicOut.applyClamped(t);
        double r = radius.getValue() * (0.8 + eased * 1.9);
        float strength = (1f - t) * 0.9f;

        CloudanGlowRenderer.shockwave(centre, r, angle, Render2DEngine.injectAlpha(base, opacity.getValue()),
                strength, throughWalls.getValue());
    }

    @Override
    public String getDisplayInfo() {
        return null;
    }
}
