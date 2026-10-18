package thunder.hack.features.modules.render;

import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Vec3d;
import thunder.hack.features.modules.Module;
import thunder.hack.setting.Setting;
import thunder.hack.setting.impl.ColorSetting;
import thunder.hack.utility.render.Render2DEngine;
import thunder.hack.utility.render.Render3DEngine;
import thunder.hack.utility.render.TextureStorage;
import thunder.hack.utility.render.core.CloudanCullingSystem;
import thunder.hack.utility.render.core.CloudanGlowRenderer;
import thunder.hack.utility.render.core.CloudanRenderCore;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Sky lanterns released around you, rising into the night.
 *
 * <p>They used to orbit the player on a ring, which is the thing that made them read as "balls circling
 * somebody" rather than as lanterns: a sky lantern's whole character is that it is going UP and AWAY,
 * unevenly, at its own speed, and is not attached to anything. So nothing here is attached to anything.
 * Each lantern is released once, somewhere in a wide area around you, and from then on it only rises -
 * it is not carried, pulled or formed up, and it retires when it gets high enough or far enough away.
 *
 * <p>They are released over a wide radius and at a spread of starting heights, so at any moment some are
 * near the ground and some are already far overhead. Releasing them all at the same height is what makes
 * a group of rising things look like one rising object.
 *
 * <p>Each one drifts sideways on its own slow wind and sways a little as it goes, because a paper bag
 * with a flame in it is never quite steady.
 *
 * <p>Cosmetic only: nothing here touches movement, collision or the world.
 */
public class Lanterns extends Module {
    public Lanterns() {
        super("Lanterns", Category.RENDER);
    }

    private final Setting<Style> style = new Setting<>("Style", Style.Classic);
    /** Sync follows the client accent, so turning the client green turns these green too. */
    private final Setting<Boolean> syncColor = new Setting<>("SyncColor", true);
    private final Setting<ColorSetting> color = new Setting<>("Color", new ColorSetting(0xFFFFB366), v -> !syncColor.getValue());
    /** How many are in the air at once. */
    private final Setting<Integer> count = new Setting<>("Count", 40, 1, 120);
    /** How far out they are released, in blocks. */
    private final Setting<Float> radius = new Setting<>("Radius", 26f, 4f, 80f);
    /** How high one climbs before it retires, measured from where it was released. */
    private final Setting<Float> ceiling = new Setting<>("Ceiling", 45f, 8f, 160f);
    /** Blocks per second, before the per-lantern variation. */
    private final Setting<Float> riseSpeed = new Setting<>("RiseSpeed", 1.1f, 0.1f, 6f);
    /** How hard the wind pushes them sideways as they climb. */
    private final Setting<Float> drift = new Setting<>("Drift", 0.35f, 0f, 3f);
    /** How much each one rocks on its way up. */
    private final Setting<Float> sway = new Setting<>("Sway", 0.4f, 0f, 2f);
    private final Setting<Float> size = new Setting<>("Size", 1.3f, 0.2f, 4f);
    /** Height a lantern is released at, relative to your feet. */
    private final Setting<Float> releaseHeight = new Setting<>("ReleaseHeight", 1f, -4f, 20f);
    private final Setting<Integer> opacity = new Setting<>("Opacity", 235, 40, 255);
    private final Setting<Boolean> glow = new Setting<>("Glow", true);
    private final Setting<Float> glowRadius = new Setting<>("GlowRadius", 1.2f, 0.2f, 4f, v -> glow.getValue());
    private final Setting<Boolean> throughWalls = new Setting<>("ThroughWalls", false);
    private final Setting<Integer> renderDistance = new Setting<>("RenderDistance", 96, 8, 160);

    public enum Style {
        /** Warm paper shell with the frame showing through and a flame at the mouth. */
        Classic,
        /** Taller, narrower, no frame - a wisp in a paper shell. */
        Soul,
        /** Paper shell in the client accent. */
        Cloudan,
        /** No shell - just the light. */
        Energy
    }

    /**
     * One lantern in the air.
     *
     * <p>It owns its position and its own rates, and nothing outside it ever moves it. That is the whole
     * difference between this and the ring it replaced.
     */
    private static final class Lantern {
        Vec3d pos;
        double startY;
        double rise;
        double windX, windZ;
        double swayPhase, swaySpeed;
        double scale;
        /** Which way this lantern faces. Fixed at release - a lantern does not spin to face you. */
        float heading;
        /** Fades in on release and out at the ceiling, so none of them pop. */
        double climbed;
    }

    private final List<Lantern> lanterns = new ArrayList<>();
    private long lastFrame;

    @Override
    public void onEnable() {
        lanterns.clear();
        lastFrame = 0;
    }

    @Override
    public void onDisable() {
        lanterns.clear();
    }

    @Override
    public void onRender3D(MatrixStack matrices) {
        if (fullNullCheck()) return;

        long now = System.currentTimeMillis();
        // Real elapsed time, so they climb at the same speed at 30 fps and at 300.
        float dt = lastFrame == 0 ? 0f : Math.min(0.1f, (now - lastFrame) / 1000f);
        lastFrame = now;

        Vec3d player = CloudanRenderCore.interpolated(mc.player);

        int want = Math.max(1, Math.round(count.getValue() * Math.min(1f, CloudanRenderCore.qualityScale())));
        while (lanterns.size() < want) lanterns.add(release(player));
        while (lanterns.size() > want) lanterns.removeLast();

        Identifier texture = textureFor(style.getValue());
        Color base = colorFor(style.getValue());
        boolean tw = throughWalls.getValue();
        float cameraYaw = CloudanRenderCore.cameraYaw();

        for (Iterator<Lantern> it = lanterns.iterator(); it.hasNext(); ) {
            Lantern lantern = it.next();

            advance(lantern, dt);

            // Retired once it has climbed out of sight or you have walked away from it. Releasing a
            // replacement near you is what keeps the sky full as you travel.
            if (lantern.climbed >= ceiling.getValue()
                    || player.squaredDistanceTo(lantern.pos) > Math.pow(radius.getValue() * 2.5, 2)) {
                it.remove();
                continue;
            }

            if (!CloudanCullingSystem.inRange(lantern.pos, renderDistance.getValue())) continue;

            double width = size.getValue() * lantern.scale;
            double height = width * 1.25;
            if (!CloudanCullingSystem.isVisible(lantern.pos, height)) continue;

            // In at the bottom, out at the top, full for the whole middle of the climb.
            double t = lantern.climbed / ceiling.getValue();
            float fade = (float) Math.min(1, Math.min(t / 0.06, (1 - t) / 0.22));
            if (fade <= 0.01f) continue;

            Color tint = Render2DEngine.injectAlpha(base, Math.round(opacity.getValue() * fade));

            // Real geometry, not a billboard: a lantern you can walk around has a front and a back, and
            // the swarm does not pivot with you as you move.
            float lean = (float) (Math.sin(lantern.swayPhase) * sway.getValue() * 9f);

            Render3DEngine.drawLantern3D(texture, lantern.pos, width, height,
                    lantern.heading, lean, tint, tw);

            if (glow.getValue())
                CloudanGlowRenderer.halo(lantern.pos.add(0, height * 0.18, 0),
                        glowRadius.getValue() * lantern.scale, tint,
                        style.getValue() == Style.Energy ? 0.85f : 0.45f * fade, tw);
        }
    }

    /** Moves one lantern. Nothing in here looks at the player. */
    private void advance(Lantern lantern, float dt) {
        if (dt <= 0) return;

        double up = lantern.rise * riseSpeed.getValue() * dt;
        lantern.climbed += up;
        lantern.swayPhase += lantern.swaySpeed * dt;

        // The sideways wander is the sway, not a separate wobble - so the lantern leans the way it is
        // actually being pushed instead of leaning one way and sliding the other.
        double lean = Math.sin(lantern.swayPhase) * sway.getValue() * 0.25 * dt;

        lantern.pos = lantern.pos.add(
                (lantern.windX * drift.getValue() + lean) * dt,
                up,
                (lantern.windZ * drift.getValue() + lean) * dt);
    }

    /** Puts a new lantern in the air somewhere around the player. */
    private Lantern release(Vec3d player) {
        var random = mc.world.random;

        Lantern lantern = new Lantern();

        // Area-uniform, so they are not all bunched at the centre.
        double angle = random.nextDouble() * Math.PI * 2;
        double r = radius.getValue() * Math.sqrt(random.nextDouble());

        // Spread over the WHOLE climb rather than all starting at the ground: on the first frame the sky
        // is already full instead of filling up over the next minute, and no two are ever in step.
        double head = random.nextDouble() * ceiling.getValue();

        lantern.climbed = head;
        lantern.pos = new Vec3d(
                player.x + Math.cos(angle) * r,
                player.y + releaseHeight.getValue() + head,
                player.z + Math.sin(angle) * r);
        lantern.startY = lantern.pos.y - head;

        lantern.rise = 0.6 + random.nextDouble() * 0.8;
        // One shared wind direction with a little scatter, so the whole sky drifts together.
        double wind = random.nextDouble() * Math.PI * 2;
        lantern.windX = Math.cos(wind) * (0.3 + random.nextDouble() * 0.7);
        lantern.windZ = Math.sin(wind) * (0.3 + random.nextDouble() * 0.7);

        lantern.swayPhase = random.nextDouble() * Math.PI * 2;
        lantern.swaySpeed = 0.6 + random.nextDouble() * 0.9;
        lantern.scale = 0.55 + random.nextDouble() * 0.9;
        lantern.heading = random.nextFloat() * 360f;

        return lantern;
    }

    private Color colorFor(Style style) {
        if (syncColor.getValue()) return thunder.hack.features.modules.client.HudEditor.getColor(0);

        return switch (style) {
            case Soul -> new Color(90, 210, 235);
            default -> color.getValue().getColorObject();
        };
    }

    private static Identifier textureFor(Style style) {
        return switch (style) {
            case Soul -> TextureStorage.lanternSoul;
            case Energy -> TextureStorage.lanternOrb;
            default -> TextureStorage.lanternPaper;
        };
    }

    @Override
    public String getDisplayInfo() {
        return lanterns.isEmpty() ? null : lanterns.size() + "";
    }
}
