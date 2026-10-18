package thunder.hack.features.modules.render;

import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.Entity;
import net.minecraft.entity.projectile.ArrowEntity;
import net.minecraft.entity.projectile.PersistentProjectileEntity;
import net.minecraft.entity.projectile.SpectralArrowEntity;
import net.minecraft.entity.projectile.TridentEntity;
import net.minecraft.entity.projectile.thrown.EggEntity;
import net.minecraft.entity.projectile.thrown.EnderPearlEntity;
import net.minecraft.entity.projectile.thrown.ExperienceBottleEntity;
import net.minecraft.entity.projectile.thrown.PotionEntity;
import net.minecraft.entity.projectile.thrown.SnowballEntity;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Vec3d;
import thunder.hack.features.modules.Module;
import thunder.hack.features.modules.client.HudEditor;
import thunder.hack.setting.Setting;
import thunder.hack.setting.impl.ColorSetting;
import thunder.hack.utility.render.Render2DEngine;
import thunder.hack.utility.render.TextureStorage;
import thunder.hack.utility.render.core.CloudanCullingSystem;
import thunder.hack.utility.render.core.CloudanParticleRenderer;
import thunder.hack.utility.render.core.CloudanRenderCore;

import java.awt.Color;
import java.util.HashMap;
import java.util.Map;

/**
 * Stars trailing behind thrown things.
 *
 * <p>Pearls, potions and arrows all move fast enough that one particle per tick leaves visible gaps
 * between them. So the trail is laid down along the segment the projectile covered since the last tick
 * rather than at the point it happens to be at now - several stars spaced along where it actually went,
 * which is what makes the trail continuous instead of a dotted line.
 *
 * <p>Every star comes out of the shared pool and is capped per module, so a firework spam or twenty
 * arrows in the air cannot turn into thousands of live objects.
 *
 * <p>Purely visual: the projectiles themselves are untouched.
 */
public class ProjectileTrails extends Module {
    /**
     * Driven by Trajectories' Particles toggle, so it is not a second entry in the Render list saying
     * roughly what Trajectories already says. It stays registered because it still needs ticks and
     * render callbacks; it is only kept out of the menus.
     */
    @Override
    public boolean isHidden() {
        return true;
    }

    public ProjectileTrails() {
        super("ProjectileTrails", Category.RENDER);
    }

    private final Setting<Boolean> pearls = new Setting<>("Pearls", true);
    private final Setting<Boolean> potions = new Setting<>("Potions", true);
    private final Setting<Boolean> arrows = new Setting<>("Arrows", true);
    private final Setting<Boolean> other = new Setting<>("Snowballs/Eggs", true);
    private final Setting<Boolean> ownOnly = new Setting<>("OwnOnly", false);

    private final Setting<Shape> shape = new Setting<>("Shape", Shape.Star);
    /** Stars laid along each tick of flight. Higher = a denser, more continuous trail. */
    private final Setting<Integer> density = new Setting<>("Density", 3, 1, 10);
    private final Setting<Float> size = new Setting<>("Size", 0.42f, 0.05f, 1.5f);
    private final Setting<Integer> lifetime = new Setting<>("Lifetime", 900, 150, 4000);
    private final Setting<Float> spread = new Setting<>("Spread", 0.10f, 0f, 0.6f);
    private final Setting<Float> drift = new Setting<>("Drift", 0.004f, 0f, 0.05f);

    private final Setting<ColorMode> colorMode = new Setting<>("ColorMode", ColorMode.PerType);
    private final Setting<ColorSetting> color = new Setting<>("Color", new ColorSetting(0xFFFFE9A0), v -> colorMode.getValue() == ColorMode.Custom);
    private final Setting<Integer> opacity = new Setting<>("Opacity", 220, 20, 255);
    private final Setting<Boolean> throughWalls = new Setting<>("ThroughWalls", false);
    private final Setting<Integer> renderDistance = new Setting<>("RenderDistance", 64, 8, 128);

    public enum Shape {
        /** Five-pointed star. */
        Star,
        /** Four-armed sparkle - reads better when the stars are small. */
        Sparkle,
        /** A plain soft dot. */
        Dot
    }

    public enum ColorMode {
        /** Pearls purple, potions by their own colour, arrows white. */
        PerType,
        Sync,
        Custom
    }

    /** Where each projectile was last tick, so the trail can be laid along the gap it crossed. */
    private final Map<Integer, Vec3d> previous = new HashMap<>();

    @Override
    public void onDisable() {
        previous.clear();
        CloudanParticleRenderer.clear("projectile");
    }

    @Override
    public void onLogout() {
        previous.clear();
    }

    @Override
    public void onUpdate() {
        if (fullNullCheck()) return;

        for (Entity entity : mc.world.getEntities()) {
            if (!tracked(entity)) continue;
            if (ownOnly.getValue() && !isOurs(entity)) continue;
            if (!CloudanCullingSystem.inRange(entity.getEntityPos(), renderDistance.getValue())) {
                previous.remove(entity.getId());
                continue;
            }

            Vec3d now = entity.getEntityPos();
            Vec3d last = previous.put(entity.getId(), now);

            // First sighting: nothing to draw a line along yet.
            if (last == null) continue;

            layTrail(entity, last, now);
        }

        // Anything that landed or despawned stops being tracked, so the map cannot grow forever.
        previous.keySet().removeIf(id -> mc.world.getEntityById(id) == null);
    }

    /**
     * Drops stars along the segment the projectile covered since the last tick.
     *
     * <p>A pearl moves about two blocks per tick. One particle at its current position leaves a trail of
     * dots two blocks apart; spacing them along the segment is what closes those gaps.
     */
    private void layTrail(Entity entity, Vec3d from, Vec3d to) {
        int count = CloudanRenderCore.budget(density.getValue());
        Color tint = Render2DEngine.injectAlpha(colorFor(entity), opacity.getValue());
        Identifier texture = textureFor();

        for (int i = 0; i < count; i++) {
            var spawn = CloudanParticleRenderer.spawn("projectile", 400);
            if (spawn == null) return;

            double t = (i + 0.5) / count;
            Vec3d at = from.lerp(to, t);

            // A little scatter so the trail has width instead of being a perfectly straight wire.
            double sx = (mc.world.random.nextDouble() - 0.5) * spread.getValue();
            double sy = (mc.world.random.nextDouble() - 0.5) * spread.getValue();
            double sz = (mc.world.random.nextDouble() - 0.5) * spread.getValue();

            spawn.at(at.add(sx, sy, sz))
                    // Drifting up slowly rather than falling - stars should hang where they were left.
                    .velocity(0, drift.getValue(), 0)
                    .physics(0, 0.03)
                    .size(size.getValue(), 0.15f)
                    .life(lifetime.getValue())
                    .color(tint)
                    .texture(texture)
                    .throughWalls(throughWalls.getValue());
        }
    }

    private boolean tracked(Entity entity) {
        if (entity instanceof EnderPearlEntity) return pearls.getValue();
        if (entity instanceof PotionEntity) return potions.getValue();
        if (entity instanceof PersistentProjectileEntity) return arrows.getValue();
        if (entity instanceof SnowballEntity || entity instanceof EggEntity
                || entity instanceof ExperienceBottleEntity) return other.getValue();
        return false;
    }

    private boolean isOurs(Entity entity) {
        return entity instanceof net.minecraft.entity.projectile.ProjectileEntity projectile
                && projectile.getOwner() == mc.player;
    }

    private Color colorFor(Entity entity) {
        return switch (colorMode.getValue()) {
            case Sync -> HudEditor.getColor(0);
            case Custom -> color.getValue().getColorObject();
            case PerType -> {
                if (entity instanceof EnderPearlEntity) yield new Color(170, 110, 255);
                if (entity instanceof PotionEntity) yield new Color(255, 130, 220);
                if (entity instanceof SpectralArrowEntity) yield new Color(255, 235, 150);
                if (entity instanceof TridentEntity) yield new Color(120, 220, 255);
                if (entity instanceof ArrowEntity) yield new Color(235, 235, 245);
                if (entity instanceof SnowballEntity) yield new Color(210, 240, 255);
                yield new Color(255, 225, 160);
            }
        };
    }

    private Identifier textureFor() {
        return switch (shape.getValue()) {
            case Star -> TextureStorage.fxStar;
            case Sparkle -> TextureStorage.fxSparkle;
            case Dot -> TextureStorage.fxSpark;
        };
    }

    /** Nothing to draw here - the pool renders the stars as part of the frame. */
    @Override
    public void onRender3D(MatrixStack matrices) {
    }

    @Override
    public String getDisplayInfo() {
        return previous.isEmpty() ? null : previous.size() + "";
    }
}
