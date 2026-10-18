package thunder.hack.features.modules.render;

import net.minecraft.block.Blocks;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.entity.Entity;
import net.minecraft.entity.projectile.ArrowEntity;
import net.minecraft.item.*;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import thunder.hack.features.modules.Module;
import thunder.hack.features.modules.client.HudEditor;
import thunder.hack.setting.Setting;
import thunder.hack.setting.impl.ColorSetting;
import thunder.hack.utility.render.Render2DEngine;
import thunder.hack.utility.render.Render3DEngine;

import java.awt.Color;

public class Trajectories extends Module {
    public Trajectories() {
        super("Trajectories", Category.RENDER);
    }

    private final Setting<Mode> mode = new Setting<>("ColorMode", Mode.Sync);
    private final Setting<ColorSetting> color = new Setting<>("Color", new ColorSetting(0x2250b4b4), v -> mode.getValue() == Mode.Custom);
    private final Setting<Landing> landing = new Setting<>("Landing", Landing.Circle);
    private final Setting<Float> landRadius = new Setting<>("LandRadius", 0.35f, 0.1f, 1.5f, v -> landing.getValue() == Landing.Circle);
    private final Setting<Mode> lmode = new Setting<>("LandedColorMode", Mode.Sync);
    /**
     * The landing marker's colour.
     *
     * <p>Opaque by default. The old default carried an alpha of 0x22 - thirteen percent - so the marker
     * washed out to grey over whatever was behind it no matter which hue was chosen, which is what made
     * it look like the colour setting did nothing.
     */
    private final Setting<ColorSetting> lcolor = new Setting<>("LandedColor", new ColorSetting(0xFF50B4B4), v -> lmode.getValue() == Mode.Custom);
    /**
     * The star trail left behind pearls, potions and arrows once they are actually in flight.
     *
     * <p>The trail is drawn by {@link ProjectileTrails}, which stays a module because it needs ticks and
     * render callbacks of its own, but is hidden from the menus and switched on and off from here - the
     * two belong to the same idea, and two separate entries for "show me where the thrown thing goes"
     * is one entry too many.
     */
    private final Setting<Boolean> particles = new Setting<>("Particles", true);

    @Override
    public void onEnable() {
        syncParticles();
    }

    @Override
    public void onDisable() {
        thunder.hack.core.manager.client.ModuleManager.projectileTrails.setEnabled(false);
    }

    @Override
    public void onUpdate() {
        syncParticles();

        // Driven DIRECTLY rather than left to the event bus.
        //
        // <p>The bus only delivers to modules it considers enabled, and a module that something else
        // switches on and off is exactly the case that falls through the gap - the same way AimCamera
        // did. Calling it here means the trails run whenever Trajectories says they should, with no
        // dependence on whether the bus agreed that the hidden module was live.
        if (particles.getValue()) thunder.hack.core.manager.client.ModuleManager.projectileTrails.onUpdate();
    }

    @Override
    public void onRender3D(net.minecraft.client.util.math.MatrixStack matrices) {
        if (particles.getValue())
            thunder.hack.core.manager.client.ModuleManager.projectileTrails.onRender3D(matrices);
    }

    private void syncParticles() {
        var trails = thunder.hack.core.manager.client.ModuleManager.projectileTrails;
        if (trails.isEnabled() != particles.getValue()) trails.setEnabled(particles.getValue());
    }

    private boolean isThrowable(Item item) {
        return item instanceof EnderPearlItem || item == net.minecraft.item.Items.TRIDENT || item instanceof ExperienceBottleItem || item instanceof SnowballItem || item instanceof EggItem || item instanceof SplashPotionItem || item instanceof LingeringPotionItem;
    }

    private float getDistance(Item item) {
        return item instanceof BowItem ? 1.0f : 0.4f;
    }

    // Launch speeds straight out of the vanilla item classes, so the predicted arc matches the real one:
    // splash/lingering potion 0.5, XP bottle 0.7, trident 2.5, thrown items (pearl/egg/snowball) 1.5.
    // The bow/crossbow multiplier in calcTrajectory is applied on top of the 1.5 fallback.
    private float getThrowVelocity(Item item) {
        if (item instanceof SplashPotionItem || item instanceof LingeringPotionItem) return 0.5f;
        if (item instanceof ExperienceBottleItem) return 0.7f;
        if (item == net.minecraft.item.Items.TRIDENT) return 2.5f;
        return 1.5f;
    }

    /** Per-tick gravity of the entity this item spawns (vanilla {@code getGravity}). */
    private double getGravity(Item item) {
        if (item instanceof BowItem || item instanceof CrossbowItem || item == net.minecraft.item.Items.TRIDENT)
            return 0.05;
        if (item instanceof SplashPotionItem || item instanceof LingeringPotionItem)
            return 0.05;
        if (item instanceof ExperienceBottleItem)
            return 0.07;
        return 0.03;
    }

    private int getThrowPitch(Item item) {
        if (item instanceof SplashPotionItem || item instanceof LingeringPotionItem || item instanceof ExperienceBottleItem)
            return 20;
        return 0;
    }

    public void onRender2D(DrawContext context) {
        if (mc.options.hudHidden) return;
        if (mc.player == null || mc.world == null || !mc.options.getPerspective().isFirstPerson())
            return;
        Hand hand;

        ItemStack mainHand = mc.player.getMainHandStack();
        ItemStack offHand = mc.player.getOffHandStack();

        if (mainHand.getItem() instanceof BowItem || mainHand.getItem() instanceof CrossbowItem || isThrowable(mainHand.getItem())) {
            hand = Hand.MAIN_HAND;
        } else if (offHand.getItem() instanceof BowItem || offHand.getItem() instanceof CrossbowItem || isThrowable(offHand.getItem())) {
            hand = Hand.OFF_HAND;
        } else return;

        if ((offHand.getItem() instanceof CrossbowItem && EnchantmentHelper.getLevel(mc.world.getRegistryManager().getOrThrow(Enchantments.MULTISHOT.getRegistryRef()).getOrThrow(Enchantments.MULTISHOT), offHand) != 0) ||
                (mainHand.getItem() instanceof CrossbowItem && EnchantmentHelper.getLevel(mc.world.getRegistryManager().getOrThrow(Enchantments.MULTISHOT.getRegistryRef()).getOrThrow(Enchantments.MULTISHOT), mainHand) != 0)) {

            calcTrajectory(context, hand == Hand.OFF_HAND ? offHand.getItem() : mainHand.getItem(), mc.player.getYaw() - 10);
            calcTrajectory(context, hand == Hand.OFF_HAND ? offHand.getItem() : mainHand.getItem(), mc.player.getYaw());
            calcTrajectory(context, hand == Hand.OFF_HAND ? offHand.getItem() : mainHand.getItem(), mc.player.getYaw() + 10);

        } else calcTrajectory(context, hand == Hand.OFF_HAND ? offHand.getItem() : mainHand.getItem(), mc.player.getYaw());
    }

    private void calcTrajectory(DrawContext context, Item item, float yaw) {
        double x = Render2DEngine.interpolate(mc.player.lastX, mc.player.getX(), Render3DEngine.getTickDelta());
        double y = Render2DEngine.interpolate(mc.player.lastY, mc.player.getY(), Render3DEngine.getTickDelta());
        double z = Render2DEngine.interpolate(mc.player.lastZ, mc.player.getZ(), Render3DEngine.getTickDelta());

        y = y + mc.player.getEyeHeight(mc.player.getPose()) - 0.1000000014901161;

        if (item == mc.player.getMainHandStack().getItem()) {
            x = x - MathHelper.cos(yaw / 180.0f * 3.1415927f) * 0.16f;
            z = z - MathHelper.sin(yaw / 180.0f * 3.1415927f) * 0.16f;
        } else {
            x = x + MathHelper.cos(yaw / 180.0f * 3.1415927f) * 0.16f;
            z = z + MathHelper.sin(yaw / 180.0f * 3.1415927f) * 0.16f;
        }

        final float maxDist = getDistance(item);
        double motionX = -MathHelper.sin(yaw / 180.0f * 3.1415927f) * MathHelper.cos(mc.player.getPitch() / 180.0f * 3.1415927f) * maxDist;
        double motionY = -MathHelper.sin((mc.player.getPitch() - getThrowPitch(item)) / 180.0f * 3.141593f) * maxDist;
        double motionZ = MathHelper.cos(yaw / 180.0f * 3.1415927f) * MathHelper.cos(mc.player.getPitch() / 180.0f * 3.1415927f) * maxDist;

        float power = mc.player.getItemUseTime() / 20.0f;
        power = (power * power + power * 2.0f) / 3.0f;

        if (power > 1.0f || power == 0) {
            power = 1.0f;
        }

        final float distance = MathHelper.sqrt((float) (motionX * motionX + motionY * motionY + motionZ * motionZ));
        motionX /= distance;
        motionY /= distance;
        motionZ /= distance;

        // fully drawn bow = 3.0, crossbow bolt = 3.15 (both are the vanilla numbers once multiplied by the
        // 1.5 fallback from getThrowVelocity)
        final float pow = (item instanceof BowItem ? (power * 2.0f) : item instanceof CrossbowItem ? (2.1f) : 1.0f) * getThrowVelocity(item);

        motionX *= pow;
        motionY *= pow;
        motionZ *= pow;

        // Vanilla adds the shooter's own velocity to the projectile (Y only while airborne). The old code
        // added Y but dropped X/Z, so the prediction drifted off whenever you threw while moving.
        motionX += mc.player.getVelocity().getX();
        motionZ += mc.player.getVelocity().getZ();
        if (!mc.player.isOnGround())
            motionY += mc.player.getVelocity().getY();

        Vec3d lastPos;
        for (int i = 0; i < 300; i++) {
            lastPos = new Vec3d(x, y, z);
            x += motionX;
            y += motionY;
            z += motionZ;
            // ofFloored, not an (int) cast: truncation rounds toward zero, so on negative coordinates the
            // water check was sampling the wrong block
            if (mc.world.getBlockState(BlockPos.ofFloored(x, y, z)).getBlock() == Blocks.WATER) {
                motionX *= 0.8;
                motionY *= 0.8;
                motionZ *= 0.8;
            } else {
                motionX *= 0.99;
                motionY *= 0.99;
                motionZ *= 0.99;
            }

            motionY -= getGravity(item);


            Vec3d pos = new Vec3d(x, y, z);

            for (Entity ent : mc.world.getEntities()) {
                if (ent instanceof ArrowEntity || ent.equals(mc.player)) continue;
                if (ent.getBoundingBox().intersects(new Box(x - 0.3, y - 0.3, z - 0.3, x + 0.3, y + 0.3, z + 0.3))) {
                    Color lc = lmode.getValue() == Mode.Sync ? HudEditor.getColor(i * 10) : lcolor.getValue().getColorObject();
                    Render3DEngine.drawProjectedBox(context, ent.getBoundingBox(), Render2DEngine.injectAlpha(lc, 90), Render2DEngine.injectAlpha(lc, 90), Render2DEngine.injectAlpha(lc, 255), 2f, true, true);
                    break;
                }
            }

            BlockHitResult bhr = mc.world.raycast(new RaycastContext(lastPos, pos, RaycastContext.ShapeType.OUTLINE, RaycastContext.FluidHandling.NONE, mc.player));
            if (bhr != null && bhr.getType() == HitResult.Type.BLOCK) {
                Color lc = lmode.getValue() == Mode.Sync ? HudEditor.getColor(i * 10) : lcolor.getValue().getColorObject();

                // draw the last stretch up to the actual impact point, so the marker is not left floating
                // a partial tick short of the surface
                Render3DEngine.drawLine2D(context, lastPos, bhr.getPos(), mode.getValue() == Mode.Sync ? HudEditor.getColor(i) : color.getValue().getColorObject());

                if (landing.getValue() == Landing.Circle) {
                    // lift it off the surface a hair, otherwise it z-fights the block face
                    Vec3d lifted = bhr.getPos().add(Vec3d.of(bhr.getSide().getVector()).multiply(0.02));
                    Render3DEngine.drawCircle2D(context, lifted, bhr.getSide(), landRadius.getValue(), Render2DEngine.injectAlpha(lc, 255), 28);

                    // A pearl gets a much bigger ring and the flight time written on it: with a pearl the
                    // question is never "which block" but "is it worth it", and both halves of that are
                    // how far you are going and how long you are in the air for.
                    if (item instanceof net.minecraft.item.EnderPearlItem) {
                        Render3DEngine.drawCircle2D(context, lifted, bhr.getSide(), landRadius.getValue() * 3.2f,
                                Render2DEngine.injectAlpha(lc, 190), 40);
                        Render3DEngine.drawCircle2D(context, lifted, bhr.getSide(), landRadius.getValue() * 2.1f,
                                Render2DEngine.injectAlpha(lc, 120), 36);

                        // i counts simulation steps, and a step is a tick.
                        Render3DEngine.drawTextIn3D(String.format("%.1fs", i / 20f), lifted.add(0, 0.55, 0),
                                0, 0, 0, Render2DEngine.injectAlpha(lc, 255));

                        // A 2D info panel floating above the landing spot: the pearl icon, how far it lands from
                        // you, and how long it is in the air - the whole "is it worth it" at a glance.
                        Vec3d sp = Render3DEngine.worldSpaceToScreenSpace(lifted.add(0, 1.0, 0));
                        if (sp.z > 0 && sp.z < 1) {
                            double dist = mc.player.getEyePos().distanceTo(bhr.getPos());
                            String dStr = String.format("%.0fm", dist);
                            String tStr = String.format("%.1fs", i / 20f);
                            float pw = 58, ph = 24;
                            float px = (float) sp.x - pw / 2f, py = (float) sp.y - ph / 2f;
                            Render2DEngine.drawRound(context, px, py, pw, ph, 5f, new Color(14, 14, 20, 225));
                            Render2DEngine.drawRound(context, px, py, pw, 2f, 1f, Render2DEngine.injectAlpha(lc, 255));
                            context.drawItem(new net.minecraft.item.ItemStack(net.minecraft.item.Items.ENDER_PEARL), (int) (px + 4), (int) (py + 4));
                            thunder.hack.gui.font.FontRenderers.sf_bold_mini.drawString(context, dStr, px + 24, py + 4, 0xFFFFFFFF);
                            thunder.hack.gui.font.FontRenderers.sf_bold_mini.drawString(context, tStr, px + 24, py + 13, Render2DEngine.injectAlpha(lc, 255).getRGB());
                        }
                    }
                } else {
                    Render3DEngine.drawProjectedBox(context, new Box(bhr.getBlockPos()), Render2DEngine.injectAlpha(lc, 90), Render2DEngine.injectAlpha(lc, 90), Render2DEngine.injectAlpha(lc, 255), 2f, true, true);
                }
                break;
            }

            if (y <= -65) break;
            if (motionX == 0 && motionY == 0 && motionZ == 0) continue;

            Render3DEngine.drawLine2D(context, lastPos, pos, mode.getValue() == Mode.Sync ? HudEditor.getColor(i) : color.getValue().getColorObject());
        }
    }

    private enum Mode {
        Custom,
        Sync
    }

    private enum Landing {
        /** Ring on the surface the projectile lands on. */
        Circle,
        /** The pre-existing behaviour: highlight the whole block that gets hit. */
        Box
    }
}
