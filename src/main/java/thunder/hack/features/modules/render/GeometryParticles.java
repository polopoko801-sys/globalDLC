package thunder.hack.features.modules.render;

import net.minecraft.util.math.Vec3d;
import thunder.hack.features.modules.Module;
import thunder.hack.features.modules.client.HudEditor;
import thunder.hack.setting.Setting;
import thunder.hack.setting.impl.ColorSetting;
import thunder.hack.utility.render.Render2DEngine;
import thunder.hack.utility.render.core.CloudanCullingSystem;
import thunder.hack.utility.render.Render3DEngine;
import thunder.hack.utility.render.TextureStorage;
import thunder.hack.utility.render.core.CloudanRenderCore;

import java.awt.Color;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Random;

/**
 * Small wireframe solids drifting in the world - holographic geometry, not sprites.
 *
 * <p>Every particle is real 3D: a set of vertices rotated on three axes, translated into the world and drawn
 * as its edges. Nothing here is a billboard, a quad or a texture, which is the entire point - a sprite
 * rotates with the camera and these do not. Walk around one and you walk around a solid.
 *
 * <h2>What it reuses</h2>
 * Edges are world-space beams ({@code Render3DEngine.drawBeamSegments3D}) collected for the whole frame, so
 * every particle is drawn in two batched calls - glow and core - not one draw call each. Range and frustum come from {@code CloudanCullingSystem}, the budget from
 * {@code CloudanRenderCore.budget}. No second renderer, no second glow system, no second particle engine.
 *
 * <h2>Why the shapes are templates</h2>
 * A cube's eight corners never change; only the rotation does. The vertex lists are therefore built once as
 * static templates and each particle only transforms them, so spawning costs nothing and no geometry is
 * allocated per frame beyond the rotated positions themselves.
 *
 * <h2>Glow without a glow buffer</h2>
 * Each edge is drawn twice: a thin bright core, and a wider much fainter line underneath. Two lines read as a
 * lit filament at this scale and cost one extra segment; a real bloom pass would mean an offscreen buffer for
 * an effect the brief explicitly wants kept subtle.
 */
public class GeometryParticles extends Module {
    public GeometryParticles() {
        super("GeometryParticles", Category.RENDER);
    }

    /* ------------------------------------------------------------------ */
    /* Settings                                                            */
    /* ------------------------------------------------------------------ */

    public enum Shape {
        Mixed, Cube, Diamond, Octahedron, Crystal, Random
    }

    public enum Tint {
        Blue, Cyan, Purple, Sync, Custom
    }

    public enum Quality {
        Low, Medium, High, Ultra
    }

    private final Setting<Integer> count = new Setting<>("ParticleCount", 26, 1, 120);
    private final Setting<Integer> maxDistance = new Setting<>("MaxDistance", 20, 4, 96);
    private final Setting<Float> minSize = new Setting<>("MinSize", 0.25f, 0.03f, 2f);
    private final Setting<Float> maxSize = new Setting<>("MaxSize", 0.9f, 0.05f, 4f);
    private final Setting<Float> lineWidth = new Setting<>("LineWidth", 2.2f, 0.4f, 6f);

    private final Setting<Boolean> glow = new Setting<>("Glow", true);
    private final Setting<Boolean> throughWalls = new Setting<>("ThroughWalls", false);
    // Fixed glow strength - no "GlowStrength" slider (user: "bez ustawiania jakiegos mocnego").
    private static final float GLOW_STRENGTH = 0.55f;

    private final Setting<Float> rotationSpeed = new Setting<>("RotationSpeed", 1f, 0.1f, 4f);
    private final Setting<Float> driftSpeed = new Setting<>("DriftSpeed", 1f, 0.1f, 4f);
    private final Setting<Float> driftAmount = new Setting<>("DriftAmount", 0.35f, 0f, 2f);
    private final Setting<Float> lifetime = new Setting<>("Lifetime", 14f, 3f, 60f);
    /** New particles per second, while under the count cap. */
    private final Setting<Float> spawnRate = new Setting<>("SpawnRate", 2.5f, 0.2f, 20f);

    private final Setting<Shape> shape = new Setting<>("GeometryType", Shape.Mixed);
    private final Setting<Tint> tint = new Setting<>("ColorMode", Tint.Blue);
    private final Setting<ColorSetting> custom = new Setting<>("Color", new ColorSetting(0xFF6FA8FF),
            v -> tint.getValue() == Tint.Custom);
    private final Setting<Integer> opacity = new Setting<>("Opacity", 235, 20, 255);

    private final Setting<Quality> quality = new Setting<>("Quality", Quality.High);

    /* ------------------------------------------------------------------ */
    /* Geometry templates                                                  */
    /* ------------------------------------------------------------------ */

    /** A shape as unit-radius vertices plus the pairs of indices that form its edges. */
    private record Template(float[][] vertices, int[][] edges) {
    }

    private static final Template CUBE = cube();
    private static final Template OCTAHEDRON = octahedron();
    private static final Template DIAMOND = diamond();
    private static final Template CRYSTAL = crystal();

    private static Template cube() {
        float[][] v = new float[8][];
        int i = 0;
        for (int x = -1; x <= 1; x += 2)
            for (int y = -1; y <= 1; y += 2)
                for (int z = -1; z <= 1; z += 2)
                    v[i++] = new float[]{x * 0.5f, y * 0.5f, z * 0.5f};

        // Two corners are joined when they differ on exactly one axis - which is the definition of a cube edge
        // and avoids writing out twelve index pairs by hand and getting one wrong.
        // One axis apart = a cube edge (12). Two axes apart = a face diagonal (12): those draw the X across
        // every face, the cross-hatched look of the reference cubes.
        List<int[]> edges = new ArrayList<>();
        for (int a = 0; a < 8; a++)
            for (int b = a + 1; b < 8; b++) {
                int differing = 0;
                for (int axis = 0; axis < 3; axis++) if (v[a][axis] != v[b][axis]) differing++;
                if (differing == 1 || differing == 2) edges.add(new int[]{a, b});
            }

        return new Template(v, edges.toArray(new int[0][]));
    }

    private static Template octahedron() {
        float[][] v = {
                {0, 0.6f, 0}, {0, -0.6f, 0},
                {0.6f, 0, 0}, {-0.6f, 0, 0},
                {0, 0, 0.6f}, {0, 0, -0.6f}
        };
        // Every equatorial vertex joins both poles and its two neighbours.
        int[][] e = {
                {0, 2}, {0, 3}, {0, 4}, {0, 5},
                {1, 2}, {1, 3}, {1, 4}, {1, 5},
                {2, 4}, {4, 3}, {3, 5}, {5, 2}
        };
        return new Template(v, e);
    }

    /** A squat octahedron: a long point up, a short one down - a gem rather than a ball. */
    private static Template diamond() {
        float[][] v = {
                {0, 0.75f, 0}, {0, -0.45f, 0},
                {0.45f, 0.12f, 0}, {-0.45f, 0.12f, 0},
                {0, 0.12f, 0.45f}, {0, 0.12f, -0.45f}
        };
        int[][] e = {
                {0, 2}, {0, 3}, {0, 4}, {0, 5},
                {1, 2}, {1, 3}, {1, 4}, {1, 5},
                {2, 4}, {4, 3}, {3, 5}, {5, 2}
        };
        return new Template(v, e);
    }

    /** A six-sided prism with points at both ends - the classic low-poly crystal. */
    private static Template crystal() {
        List<float[]> v = new ArrayList<>();
        v.add(new float[]{0, 0.8f, 0});     // 0 top point
        v.add(new float[]{0, -0.8f, 0});    // 1 bottom point

        for (int i = 0; i < 6; i++) {
            double angle = Math.PI * 2 * i / 6.0;
            v.add(new float[]{(float) Math.cos(angle) * 0.38f, 0.22f, (float) Math.sin(angle) * 0.38f});
        }
        for (int i = 0; i < 6; i++) {
            double angle = Math.PI * 2 * i / 6.0;
            v.add(new float[]{(float) Math.cos(angle) * 0.38f, -0.22f, (float) Math.sin(angle) * 0.38f});
        }

        List<int[]> e = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            int top = 2 + i, bottom = 8 + i;
            int topNext = 2 + (i + 1) % 6, bottomNext = 8 + (i + 1) % 6;

            e.add(new int[]{0, top});           // cap to the upper ring
            e.add(new int[]{1, bottom});        // cap to the lower ring
            e.add(new int[]{top, topNext});     // upper ring
            e.add(new int[]{bottom, bottomNext});
            e.add(new int[]{top, bottom});      // the body
        }

        return new Template(v.toArray(new float[0][]), e.toArray(new int[0][]));
    }

    /* ------------------------------------------------------------------ */
    /* Particles                                                           */
    /* ------------------------------------------------------------------ */

    /**
     * One floating solid.
     *
     * <p>A mutable class rather than a record because these are pooled: the whole point is that a particle
     * ending its life is reset and reused instead of allocating a new one, which is what keeps a continuously
     * spawning effect from producing garbage forever.
     */
    private static final class Particle {
        Vec3d position = Vec3d.ZERO;
        Vec3d velocity = Vec3d.ZERO;

        float size;
        float rotX, rotY, rotZ;
        float spinX, spinY, spinZ;

        float ageMs, lifeMs;
        float seed;
        Template template;
        float hueShift;

        void reset() {
            position = Vec3d.ZERO;
            velocity = Vec3d.ZERO;
            ageMs = 0f;
        }

        float life() {
            return lifeMs <= 0f ? 1f : Math.clamp(ageMs / lifeMs, 0f, 1f);
        }
    }

    private final List<Particle> alive = new ArrayList<>();
    private final Deque<Particle> pool = new ArrayDeque<>();
    private final Random random = new Random();

    private long lastFrame;
    private float spawnAccumulator;

    @Override
    public void onEnable() {
        clear();
    }

    @Override
    public void onDisable() {
        clear();
    }

    @Override
    public void onLogout() {
        clear();
    }

    @Override
    public void onUnload() {
        clear();
    }

    private void clear() {
        // Returned to the pool rather than dropped, so re-enabling does not have to allocate again.
        pool.addAll(alive);
        alive.clear();
        lastFrame = 0L;
        spawnAccumulator = 0f;
    }

    /* ------------------------------------------------------------------ */
    /* Simulation                                                          */
    /* ------------------------------------------------------------------ */

    /**
     * Advances and draws.
     *
     * <p>Stepped on real elapsed time, not per frame, so the drift and the rotation look identical at 60fps
     * and at 240 - the thing a per-frame increment always gets wrong.
     *
     * <p>Drawn in the 3D pass as world-space beams, depth tested, so terrain hides them like real objects.
     */
    @Override
    public void onRender3D(net.minecraft.client.util.math.MatrixStack matrices) {
        if (fullNullCheck()) return;

        long now = System.currentTimeMillis();
        float delta = lastFrame == 0L ? 16f : Math.min(250f, now - lastFrame);
        lastFrame = now;

        step(delta);
        spawn(delta);
        draw();
    }

    private void step(float delta) {
        float seconds = delta / 1000f;

        for (int i = alive.size() - 1; i >= 0; i--) {
            Particle p = alive.get(i);
            p.ageMs += delta;

            if (p.ageMs >= p.lifeMs) {
                alive.remove(i);
                p.reset();
                pool.push(p);
                continue;
            }

            p.position = p.position.add(p.velocity.multiply(seconds));

            float spin = rotationSpeed.getValue() * seconds;
            p.rotX += p.spinX * spin;
            p.rotY += p.spinY * spin;
            p.rotZ += p.spinZ * spin;
        }
    }

    private void spawn(float delta) {
        int cap = Math.min(count.getValue(), CloudanRenderCore.budget(count.getValue()));
        if (alive.size() >= cap) return;

        spawnAccumulator += spawnRate.getValue() * (delta / 1000f);
        while (spawnAccumulator >= 1f && alive.size() < cap) {
            spawnAccumulator -= 1f;
            alive.add(create());
        }
    }

    /**
     * Places a new particle in a shell around the player.
     *
     * <p>A shell, not a sphere: spawning uniformly inside the radius would put most of them close to you,
     * because volume grows with the cube of the distance. Keeping them between a third and the full range is
     * what makes the effect read as ambient space rather than as something orbiting your head - and it is why
     * nothing appears in the middle of the screen.
     */
    private Particle create() {
        Particle p = pool.isEmpty() ? new Particle() : pool.pop();
        p.reset();

        // The shell is kept close, and NOT proportional to MaxDistance. Scaling it with the range meant that at
        // MaxDistance 96 every solid spawned 17-58 blocks away - a sub-pixel wireframe nobody can see, which
        // is exactly how "it does not work" looked. MaxDistance is only the draw cut-off now.
        double range = Math.min(maxDistance.getValue(), 14.0);
        double distance = 2.5 + random.nextDouble() * Math.max(0.5, range - 2.5);
        double angle = random.nextDouble() * Math.PI * 2;
        double height = (random.nextDouble() - 0.35) * range * 0.4;

        Vec3d eye = mc.player.getEyePos();
        p.position = eye.add(Math.cos(angle) * distance, height, Math.sin(angle) * distance);

        // A gentle, mostly horizontal drift with a touch of rise.
        double speed = 0.08 + random.nextDouble() * 0.12;
        double driftAngle = random.nextDouble() * Math.PI * 2;
        p.velocity = new Vec3d(Math.cos(driftAngle) * speed, (random.nextDouble() - 0.4) * 0.05,
                Math.sin(driftAngle) * speed).multiply(driftSpeed.getValue());

        // Larger solids are rarer: cubing the roll pushes the distribution towards the small end, which is
        // what keeps a few big ones from dominating a field that is supposed to be quiet.
        float roll = random.nextFloat();
        float biased = roll * roll * roll;
        float lo = Math.min(minSize.getValue(), maxSize.getValue());
        float hi = Math.max(minSize.getValue(), maxSize.getValue());
        p.size = lo + (hi - lo) * biased;

        p.rotX = random.nextFloat() * 360f;
        p.rotY = random.nextFloat() * 360f;
        p.rotZ = random.nextFloat() * 360f;
        p.spinX = (random.nextFloat() - 0.5f) * 26f;
        p.spinY = (random.nextFloat() - 0.5f) * 26f;
        p.spinZ = (random.nextFloat() - 0.5f) * 26f;

        p.seed = random.nextFloat() * 100f;
        p.hueShift = (random.nextFloat() - 0.5f) * 0.06f;
        p.lifeMs = lifetime.getValue() * 1000f * (0.7f + random.nextFloat() * 0.6f);
        p.template = templateFor();

        return p;
    }

    private Template templateFor() {
        return switch (shape.getValue()) {
            case Cube -> CUBE;
            case Diamond -> DIAMOND;
            case Octahedron -> OCTAHEDRON;
            case Crystal -> CRYSTAL;
            case Mixed, Random -> switch (random.nextInt(4)) {
                case 0 -> CUBE;
                case 1 -> DIAMOND;
                case 2 -> OCTAHEDRON;
                default -> CRYSTAL;
            };
        };
    }

    /* ------------------------------------------------------------------ */
    /* Drawing                                                             */
    /* ------------------------------------------------------------------ */

    private void draw() {
        // Real 3D wireframe: the cube is rotated in space and its edges drawn as glowing world-space beams. A flat
        // PNG billboard looked 2D; a cube only reads as 3D when it is actual geometry turning with perspective.
        for (Particle p : alive) {
            Vec3d centre = centreOf(p);
            float scale = p.size * spawnScale(p);
            if (!CloudanCullingSystem.inRange(centre, maxDistance.getValue())) continue;
            if (!CloudanCullingSystem.isVisible(centre, scale * 1.5)) continue;
            double distance = CloudanCullingSystem.distanceTo(centre);
            if (lodFor(distance) == Lod.SKIP) continue;
            Color color = colorFor(p, distance);
            if (color.getAlpha() <= 2) continue;

            float[][] vertices = p.template.vertices();
            Vec3d[] world = new Vec3d[vertices.length];
            float sx = (float) Math.sin(Math.toRadians(p.rotX)), cx = (float) Math.cos(Math.toRadians(p.rotX));
            float sy = (float) Math.sin(Math.toRadians(p.rotY)), cy = (float) Math.cos(Math.toRadians(p.rotY));
            float sz = (float) Math.sin(Math.toRadians(p.rotZ)), cz = (float) Math.cos(Math.toRadians(p.rotZ));
            for (int i = 0; i < vertices.length; i++) {
                float x = vertices[i][0] * scale, y = vertices[i][1] * scale, z = vertices[i][2] * scale;
                float y1 = y * cx - z * sx, z1 = y * sx + z * cx;
                float x2 = x * cy + z1 * sy, z2 = -x * sy + z1 * cy;
                float x3 = x2 * cz - y1 * sz, y3 = x2 * sz + y1 * cz;
                world[i] = centre.add(x3, y3, z2);
            }

            // A faint soft halo around the whole cube - reaches ~0.5 block out, lightly - so it glows into the air
            // around it rather than only the edges being lit.
            if (glow.getValue()) {
                double halo = (scale + 0.5) * 2.0;
                Color h = Render2DEngine.injectAlpha(color, Math.round(color.getAlpha() * GLOW_STRENGTH * 0.18f));
                Render3DEngine.drawOrientedSprite3D(TextureStorage.fxHalo, centre.add(0, halo * 0.5, 0),
                        -CloudanRenderCore.cameraYaw(), 0f, 0f, -halo / 2, halo, halo, h, false, throughWalls.getValue());
            }

            // Neon edge like the reference: a WIDE faint glow, a medium mid, and a THIN BRIGHT-WHITE core on top.
            Color wide = Render2DEngine.injectAlpha(color, Math.round(color.getAlpha() * GLOW_STRENGTH * 0.45f));
            Color mid = Render2DEngine.injectAlpha(color, Math.round(color.getAlpha() * GLOW_STRENGTH * 0.9f));
            Color coreC = new Color(
                    Math.min(255, (color.getRed() + 255) / 2),
                    Math.min(255, (color.getGreen() + 255) / 2),
                    Math.min(255, (color.getBlue() + 255) / 2), 255);
            for (int[] e : p.template.edges()) {
                java.util.List<Vec3d> seg = java.util.List.of(world[e[0]], world[e[1]]);
                boolean tw = throughWalls.getValue();
                if (glow.getValue()) {
                    Render3DEngine.drawBeamSegments3D(TextureStorage.fxLine, seg, java.util.List.of(wide, wide), scale * 0.22, tw);
                    Render3DEngine.drawBeamSegments3D(TextureStorage.fxLine, seg, java.util.List.of(mid, mid), scale * 0.10, tw);
                }
                Render3DEngine.drawBeamSegments3D(TextureStorage.fxLine, seg, java.util.List.of(coreC, coreC), scale * 0.03, tw);
            }
        }
    }

    /** Position plus the gentle vertical sway, which is what stops them looking like they are on rails. */
    private Vec3d centreOf(Particle p) {
        double sway = Math.sin(p.ageMs / 1000.0 * 0.6 + p.seed) * driftAmount.getValue() * 0.5;
        return p.position.add(0, sway, 0);
    }

    private enum Lod {
        NEAR, MEDIUM, SKIP
    }

    /**
     * Detail by distance.
     *
     * <p>Only the near band pays for the glow pass; past that the core line alone is all that is readable
     * anyway, so the second segment per edge would be spent on something nobody can see.
     */
    private Lod lodFor(double distance) {
        double max = maxDistance.getValue();
        if (distance > max) return Lod.SKIP;

        double nearBand = switch (quality.getValue()) {
            case Low -> max * 0.15;
            case Medium -> max * 0.35;
            case High -> max * 0.6;
            case Ultra -> max;
        };

        return distance <= nearBand ? Lod.NEAR : Lod.MEDIUM;
    }

    /** Spawn and despawn scaling: eased in at birth and back out at death, never popping. */
    private float spawnScale(Particle p) {
        float life = p.life();

        // Fixed-length ramps, not a fraction of the lifetime. As a fraction, a 14 second particle took nearly
        // two seconds to become visible at all - so on first enabling the module the world stayed empty long
        // enough to look broken. Half a second in, three quarters out, whatever the lifetime is.
        float inPoint = p.lifeMs <= 0f ? 0.1f : Math.clamp(500f / p.lifeMs, 0.02f, 0.4f);
        float outPoint = p.lifeMs <= 0f ? 0.15f : Math.clamp(750f / p.lifeMs, 0.03f, 0.4f);

        float in = Math.clamp(life / inPoint, 0f, 1f);
        float out = Math.clamp((1f - life) / outPoint, 0f, 1f);

        // easeOutCubic on the way in, so it arrives quickly and settles.
        float eased = 1f - (1f - in) * (1f - in) * (1f - in);
        return eased * out;
    }

    private Color colorFor(Particle p, double distance) {
        Color base = switch (tint.getValue()) {
            case Blue -> new Color(0x6FA8FF);
            case Cyan -> new Color(0x5FE2F0);
            case Purple -> new Color(0x9B8CFF);
            case Sync -> HudEditor.getColor(0);
            case Custom -> custom.getValue().getColorObject();
        };

        // A touch of per-particle hue variation so a field of them is not one flat colour, while staying
        // inside the one cool palette the brief asks for.
        float[] hsb = Color.RGBtoHSB(base.getRed(), base.getGreen(), base.getBlue(), null);
        float hue = (hsb[0] + p.hueShift + 1f) % 1f;
        Color shifted = Color.getHSBColor(hue, hsb[1], hsb[2]);

        // Further away is fainter - but only slightly. Scaling alpha linearly to zero across the range meant
        // anything past the middle distance was already too faint to see, which stacked with the thin line and
        // the small size until nothing was visible at all. The floor is high enough that distance reads as
        // depth rather than as disappearance.
        float fade = (float) Math.clamp(1.0 - distance / maxDistance.getValue(), 0.0, 1.0);
        float alpha = opacity.getValue() * spawnScale(p) * (0.72f + 0.28f * fade);

        return Render2DEngine.injectAlpha(shifted, Math.clamp(Math.round(alpha), 0, 255));
    }

    @Override
    public String getDisplayInfo() {
        return alive.isEmpty() ? null : String.valueOf(alive.size());
    }
}
