package thunder.hack.features.modules.render;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import thunder.hack.features.modules.Module;
import thunder.hack.features.modules.client.HudEditor;
import thunder.hack.setting.Setting;
import thunder.hack.setting.impl.BooleanSettingGroup;
import thunder.hack.setting.impl.ColorSetting;
import thunder.hack.setting.impl.SettingGroup;
import thunder.hack.utility.math.MathUtility;
import thunder.hack.utility.render.Render2DEngine;
import thunder.hack.utility.render.Render3DEngine;
import thunder.hack.utility.render.TextureStorage;

import java.awt.*;
import java.util.ArrayList;

// Фабос, чекнул сурс - сдох отец, спастишь - сдохнет мать
public class Particles extends Module {
    public Particles() {
        super("Particles", Category.RENDER);
    }

    private final Setting<BooleanSettingGroup> FireFlies = new Setting<>("FireFlies", new BooleanSettingGroup(true));
    private final Setting<Integer> ffcount = new Setting<>("FFCount", 30, 20, 200).addToGroup(FireFlies);
    private final Setting<Float> ffsize = new Setting<>("FFSize", 1f, 0.1f, 2.0f).addToGroup(FireFlies);
    private final Setting<Mode> mode = new Setting<>("Mode", Mode.SnowFlake);
    /**
     * Which shapes All is allowed to pick from.
     *
     * <p>All on its own means "every shape"; these are what turn it into "these five". Untick one and
     * it stops appearing, so a mixture can be exactly the set you want rather than all or nothing.
     */
    private final Setting<SettingGroup> types = new Setting<>("Types", new SettingGroup(false, 0), v -> mode.getValue() == Mode.All);
    private final Setting<Boolean> useSnow = new Setting<>("SnowFlake", true).addToGroup(types);
    private final Setting<Boolean> useStars = new Setting<>("Stars", true).addToGroup(types);
    private final Setting<Boolean> useHearts = new Setting<>("Hearts", true).addToGroup(types);
    private final Setting<Boolean> useDollars = new Setting<>("Dollars", true).addToGroup(types);
    private final Setting<Boolean> useBloom = new Setting<>("Bloom", true).addToGroup(types);
    private final Setting<Boolean> useSquares = new Setting<>("Squares", true).addToGroup(types);
    /** Off by default: a cloud of solid cubes is a very different look, and nobody asked the mixture for it. */
    private final Setting<Boolean> useCubes = new Setting<>("Cubes", false).addToGroup(types);
    private final Setting<Boolean> useMarker = new Setting<>("Marker", false).addToGroup(types);
    /** How long a Marker comet's tail is, in ticks of its own travel. */
    /** Tail length in ticks of travel - short by default, a few head-lengths, like a falling spark. */
    private final Setting<Float> tail = new Setting<>("Tail", 1.5f, 0.5f, 12f, v -> mode.getValue() == Mode.Marker || mode.getValue() == Mode.All);
    /** The comets' own tint - pale mint by default, like falling stars, independent of ColorMode. */
    private final Setting<ColorSetting> markerColor = new Setting<>("MarkerColor", new ColorSetting(new Color(200, 255, 215).getRGB()),
            v -> mode.getValue() == Mode.Marker || mode.getValue() == Mode.All);
    private final Setting<Integer> count = new Setting<>("Count", 100, 20, 800);
    private final Setting<Float> size = new Setting<>("Size", 1f, 0.1f, 6.0f);
    // spread radius in blocks: how wide the cloud scatters around you (default ~20-block-wide cloud)
    private final Setting<Float> radius = new Setting<>("Radius", 10f, 2f, 50f);
    private final Setting<ColorMode> lmode = new Setting<>("ColorMode", ColorMode.Sync);
    private final Setting<ColorSetting> color = new Setting<>("Color", new ColorSetting(3649978), v -> lmode.getValue() == ColorMode.Custom);
    private final Setting<Physics> physics = new Setting<>("Physics", Physics.Fly, v -> mode.getValue() != Mode.Off);
    /** On = particles stop on a block; off = they sink through it for {@link #passDepth} blocks. */
    private final Setting<Boolean> stopOnGround = new Setting<>("DropOnBlock", true);
    private final Setting<Float> passDepth = new Setting<>("PassDepth", 4f, 0f, 40f, v -> !stopOnGround.getValue());

    private final ArrayList<ParticleBase> fireFlies = new ArrayList<>();
    private final ArrayList<ParticleBase> particles = new ArrayList<>();

    @Override
    public void onUpdate() {
        fireFlies.removeIf(ParticleBase::tick);
        particles.removeIf(ParticleBase::tick);

        for (int i = fireFlies.size(); i < ffcount.getValue(); i++) {
            if (FireFlies.getValue().isEnabled()) {
                float[] p = domePoint(radius.getValue());
                double sx = mc.player.getX() + p[0], sy = mc.player.getY() + p[1], sz = mc.player.getZ() + p[2];
                if (!isAir(sx, sy, sz)) continue; // never spawn inside terrain / underground
                fireFlies.add(new FireFly((float) sx, (float) sy, (float) sz,
                        MathUtility.random(-0.2f, 0.2f),
                        MathUtility.random(-0.1f, 0.1f),
                        MathUtility.random(-0.2f, 0.2f)));
            }
        }

        for (int j = particles.size(); j < count.getValue(); j++) {
            boolean drop = physics.getValue() == Physics.Drop;
            if (mode.getValue() != Mode.Off) {
                // In All, the shape is chosen per particle and kept for life, so the cloud is a real
                // mixture instead of every particle changing together whenever the setting moves.
                Mode kind = mode.getValue() == Mode.All ? pickEnabled() : mode.getValue();
                if (kind == null) break;   // every shape switched off: nothing to spawn

                // Each shape gets its own shell of air to live in. Spawning every shape in the same
                // volume is what makes a mixed cloud look like one cloud of noise; separating them by
                // distance is what makes it read as several different things around you.
                float shell = mode.getValue() == Mode.All ? shellFor(kind) : 1f;

                float[] p = domePoint(radius.getValue() * shell);
                double sx = mc.player.getX() + p[0], sy = mc.player.getY() + p[1], sz = mc.player.getZ() + p[2];
                if (!isAir(sx, sy, sz)) continue; // never spawn inside terrain / underground
                if (kind == Mode.Marker) {
                    // a comet: one steady slanted fall, the same heading for its whole life
                    double heading = MathUtility.random(0f, (float) (Math.PI * 2.0));
                    float drift = MathUtility.random(0.04f, 0.09f);
                    particles.add(new ParticleBase((float) sx, (float) sy + radius.getValue() * 0.5f, (float) sz,
                            (float) (Math.cos(heading) * drift), MathUtility.random(-0.22f, -0.12f),
                            (float) (Math.sin(heading) * drift), kind));
                    continue;
                }
                particles.add(new ParticleBase((float) sx, (float) sy, (float) sz,
                        drop ? 0 : MathUtility.random(-0.4f, 0.4f),
                        drop ? MathUtility.random(-0.2f, -0.05f) : MathUtility.random(-0.1f, 0.1f),
                        drop ? 0 : MathUtility.random(-0.4f, 0.4f),
                        kind));
            }
        }
    }

    /**
     * One of the ticked shapes, at random, or null if none are ticked.
     *
     * <p>Built fresh each time rather than cached, because the set is a live setting and a cache would
     * keep spawning a shape for a second or two after it was turned off.
     */
    private Mode pickEnabled() {
        java.util.List<Mode> enabled = new java.util.ArrayList<>(Mode.REAL.length);
        if (useSnow.getValue()) enabled.add(Mode.SnowFlake);
        if (useStars.getValue()) enabled.add(Mode.Stars);
        if (useHearts.getValue()) enabled.add(Mode.Hearts);
        if (useDollars.getValue()) enabled.add(Mode.Dollars);
        if (useBloom.getValue()) enabled.add(Mode.Bloom);
        if (useSquares.getValue()) enabled.add(Mode.Squares);
        if (useCubes.getValue()) enabled.add(Mode.Cube);
        if (useMarker.getValue()) enabled.add(Mode.Marker);

        if (enabled.isEmpty()) return null;
        return enabled.get((int) MathUtility.random(0, enabled.size() - 0.001f));
    }

    /** Fraction of the spread radius a given shape spawns within, so the shapes sit at different depths. */
    private static float shellFor(Mode kind) {
        return switch (kind) {
            case SnowFlake -> 1.00f;
            case Stars -> 0.82f;
            case Squares -> 0.66f;
            case Cube -> 0.74f;
            case Marker -> 0.90f;
            case Bloom -> 0.52f;
            case Hearts -> 0.40f;
            default -> 0.30f;
        };
    }

    /**
     * "Sync" colour. It used to read HudEditor's accent, which is why the particles stayed purple after a
     * palette click — the palette deliberately does not touch HUD colours. When a swatch is active the
     * particles follow it; with no swatch picked they fall back to the HUD accent as before.
     */
    private static Color syncColor(int count) {
        Color palette = thunder.hack.utility.ColorPalette.getActive();
        return palette != null ? palette : HudEditor.getColor(count);
    }

    private boolean isAir(double x, double y, double z) {
        return mc.world != null && mc.world.getBlockState(BlockPos.ofFloored(x, y, z)).isAir();
    }

    // 1.21.11: the old Tessellator/BufferBuilder + Render2DEngine.endBuilding path was a no-op stub, so
    // nothing ever drew. Reimplemented as billboarded 2D sprites: project each particle world position to
    // screen space, size it inversely with camera distance, and draw the sprite via the 2D DrawContext.
    public void onRender2D(DrawContext context) {
        if (mc.player == null || mc.world == null) return;

        if (FireFlies.getValue().isEnabled())
            for (ParticleBase p : fireFlies)
                p.render2D(context);

        if (mode.getValue() == Mode.Off) return;

        // Sprites first, cubes after.
        //
        // The two use different pipelines - the sprites are opaque PNGs on black that only look right
        // under ADDITIVE blending, and the cubes are plain projected geometry. Interleaving them turned
        // every sprite drawn after a cube into a black square, which is exactly what a white-on-black
        // sprite looks like the moment additive blending is not the thing in effect. Grouping the two
        // passes keeps each pipeline bound for a whole run instead of being swapped per particle.
        for (ParticleBase p : particles)
            if (p.kind != Mode.Cube) p.render2D(context);

        for (ParticleBase p : particles)
            if (p.kind == Mode.Cube) p.render2D(context);
    }

    /**
     * A random offset spread evenly in every horizontal direction around the player, from the player's
     * level UP only (an upper hemisphere / dome), so particles surround you on all sides but never spawn
     * below you. Uniform density inside the volume, not clustered toward the centre.
     */
    private float[] domePoint(float radius) {
        double r = radius * Math.cbrt(MathUtility.random(0f, 1f));
        double theta = MathUtility.random(0f, (float) (Math.PI * 2.0)); // any horizontal direction
        double cosPhi = MathUtility.random(0f, 1f);                      // upper hemisphere -> y >= 0
        double sinPhi = Math.sqrt(1.0 - cosPhi * cosPhi);
        return new float[]{
                (float) (r * sinPhi * Math.cos(theta)),
                (float) (r * cosPhi),
                (float) (r * sinPhi * Math.sin(theta))
        };
    }

    private void drawParticle(DrawContext context, Identifier tex, float cx, float cy, float scale, Color col) {
        // additive: the sprites are opaque PNGs on black, so anything else draws a black square
        Render2DEngine.renderGradientTexture(context, tex, cx - scale / 2f, cy - scale / 2f, scale, scale,
                0, 0, 128, 128, 128, 128, col, col, col, col, true);
    }

    public class FireFly extends ParticleBase {
        public FireFly(float posX, float posY, float posZ, float motionX, float motionY, float motionZ) {
            super(posX, posY, posZ, motionX, motionY, motionZ);
        }

        @Override
        public boolean tick() {
            if (mc.player.squaredDistanceTo(posX, posY, posZ) > 100) age -= 4;
            else if (!mc.world.getBlockState(new BlockPos((int) posX, (int) posY, (int) posZ)).isAir()) age -= 8;
            else age--;

            if (age < 0)
                return true;

            prevposX = posX;
            prevposY = posY;
            prevposZ = posZ;

            posX += motionX;
            posY += motionY;
            posZ += motionZ;

            motionX *= 0.99f;
            motionY *= 0.99f;
            motionZ *= 0.99f;

            return false;
        }

        @Override
        public void render2D(DrawContext context) {
            Vec3d world = Render3DEngine.interpolatePosAbsolute(prevposX, prevposY, prevposZ, posX, posY, posZ);
            Vec3d screen = Render3DEngine.worldSpaceToScreenSpace(world);
            if (!(screen.z > 0 && screen.z < 1)) return;

            double dist = mc.getEntityRenderDispatcher().camera.getCameraPos().distanceTo(world);
            float pixel = (float) (ffsize.getValue() * 14.0 / Math.max(1.0, dist));

            Color base = lmode.getValue() == ColorMode.Sync ? syncColor(age * 10) : color.getValue().getColorObject();
            Color col = Render2DEngine.injectAlpha(base, (int) (255 * ((float) age / (float) maxAge)));
            drawParticle(context, TextureStorage.firefly, (float) screen.x, (float) screen.y, pixel, col);
        }
    }

    public class ParticleBase {

        protected float prevposX, prevposY, prevposZ, posX, posY, posZ, motionX, motionY, motionZ;
        protected int age, maxAge;
        /** The shape this particle was born with. Fixed for life, which is what makes All a mixture. */
        protected Mode kind = Mode.SnowFlake;
        /** Y at which this particle first entered terrain, NaN while it is in open air. */
        protected float sunkFrom = Float.NaN;

        public ParticleBase(float posX, float posY, float posZ, float motionX, float motionY, float motionZ, Mode kind) {
            this(posX, posY, posZ, motionX, motionY, motionZ);
            this.kind = kind;
        }

        public ParticleBase(float posX, float posY, float posZ, float motionX, float motionY, float motionZ) {
            this.posX = posX;
            this.posY = posY;
            this.posZ = posZ;
            prevposX = posX;
            prevposY = posY;
            prevposZ = posZ;
            this.motionX = motionX;
            this.motionY = motionY;
            this.motionZ = motionZ;
            age = (int) MathUtility.random(100, 300);
            maxAge = age;
        }

        public boolean tick() {
            // die quickly once they drift past ~2x the spawn radius, so the cloud keeps hugging the player
            // as you move instead of trailing behind — scales with the Radius setting
            float killDist = radius.getValue() * 2.2f;
            if (mc.player.squaredDistanceTo(posX, posY, posZ) > killDist * killDist) age -= 30;
            else age--;

            if (age < 0)
                return true;

            prevposX = posX;
            prevposY = posY;
            prevposZ = posZ;

            if (kind == Mode.Marker) {
                // a comet keeps its speed and burns out on the first block it meets - resting on the ground
                // would collapse the tail into a dot
                posX += motionX;
                posY += motionY;
                posZ += motionZ;
                return !isAir(posX, posY, posZ);
            }

            if (stopOnGround.getValue()) {
                // block collision so particles rest on the ground instead of sinking through it
                if (isAir(posX + motionX, posY, posZ)) posX += motionX; else motionX = 0;
                if (isAir(posX, posY, posZ + motionZ)) posZ += motionZ; else motionZ = 0;
                if (isAir(posX, posY + motionY, posZ)) posY += motionY; else motionY = 0;
                sunkFrom = Float.NaN;
            } else {
                // no collision: they fall straight through terrain and only die once they are PassDepth
                // blocks deep inside it (0 = they vanish the moment they touch a block)
                posX += motionX;
                posY += motionY;
                posZ += motionZ;

                if (isAir(posX, posY, posZ)) {
                    sunkFrom = Float.NaN;
                } else {
                    if (Float.isNaN(sunkFrom)) sunkFrom = posY;
                    if (sunkFrom - posY >= passDepth.getValue()) return true;
                }
            }

            motionX *= 0.9f;
            if (physics.getValue() == Physics.Fly)
                motionY *= 0.9f;
            motionZ *= 0.9f;

            motionY -= 0.001f;

            return false;
        }

        public void render2D(DrawContext context) {
            Vec3d world = Render3DEngine.interpolatePosAbsolute(prevposX, prevposY, prevposZ, posX, posY, posZ);

            Color base = lmode.getValue() == ColorMode.Sync ? syncColor(age * 2) : color.getValue().getColorObject();
            Color col = Render2DEngine.injectAlpha(base, (int) (255 * ((float) age / (float) maxAge)));

            // Cube is the only shape that is geometry rather than a picture: a box in world space,
            // projected face by face, so it turns with the camera and has depth. Everything else stays a
            // flat sprite - a cube made of a snowflake texture would be six snowflakes in a box.
            if (kind == Mode.Cube) {
                double half = size.getValue() * 0.06;
                Box cube = new Box(world.x - half, world.y - half, world.z - half,
                        world.x + half, world.y + half, world.z + half);

                Render3DEngine.drawProjectedBox(context, cube,
                        Render2DEngine.injectAlpha(col, col.getAlpha() / 4),
                        Render2DEngine.injectAlpha(col, col.getAlpha() / 6),
                        col, 1f, true, true);
                return;
            }

            if (kind == Mode.Marker) {
                renderComet(context, world, new Vec3d(motionX, motionY, motionZ),
                        Render2DEngine.injectAlpha(markerColor.getValue().getColorObject(), col.getAlpha()));
                return;
            }

            Vec3d screen = Render3DEngine.worldSpaceToScreenSpace(world);
            if (!(screen.z > 0 && screen.z < 1)) return;

            double dist = mc.getEntityRenderDispatcher().camera.getCameraPos().distanceTo(world);
            float pixel = (float) (size.getValue() * 16.0 / Math.max(1.0, dist));

            drawParticle(context, textureFor(kind), (float) screen.x, (float) screen.y, pixel, col);
        }
    }

    /**
     * A Marker comet: glow sprites laid along the path it just travelled, shrinking and fading towards the
     * tail, with a small white-hot core on the head. Projected point by point, so the tail follows the
     * real direction of flight from any camera angle.
     */
    private void renderComet(DrawContext context, Vec3d head, Vec3d motion, Color col) {
        Vec3d cam = mc.getEntityRenderDispatcher().camera.getCameraPos();
        Vec3d end = head.subtract(motion.multiply(tail.getValue()));

        Vec3d h = Render3DEngine.worldSpaceToScreenSpace(head);
        if (!(h.z > 0 && h.z < 1)) return;
        Vec3d t = Render3DEngine.worldSpaceToScreenSpace(end);
        boolean tailOnScreen = t.z > 0 && t.z < 1;

        double dist = cam.distanceTo(head);
        // a clearly visible teardrop head at any distance (it used to be ~1px a few blocks out)
        float width = Math.max(2.5f, (float) (size.getValue() * 28.0 / Math.max(1.0, dist)));
        int a = col.getAlpha();

        // the streak: one tapered sprite (fx_streak - broad and bright at the top, a hair at the bottom) turned to
        // lie along the path on screen, head at the comet, tail trailing back the way it came
        if (tailOnScreen) {
            float dx = (float) (t.x - h.x), dy = (float) (t.y - h.y);
            float length = (float) Math.sqrt(dx * dx + dy * dy);
            if (length > 1f) {
                var m = context.getMatrices();
                m.pushMatrix();
                m.translate((float) h.x, (float) h.y);
                // the sprite's +y runs head -> tail, so turn +y onto the screen direction of the tail
                m.rotate((float) (Math.atan2(dy, dx) - Math.PI / 2));
                // bloom under the tail: a wider, fainter copy, so the streak glows instead of being a flat line
                Color bloom = Render2DEngine.injectAlpha(col, Math.round(a * 0.4f));
                Render2DEngine.renderGradientTexture(context, TextureStorage.fxStreak,
                        -width * 1.3f, -width * 0.5f, width * 2.6f, length + width * 0.5f,
                        0, 0, 64, 256, 64, 256, bloom, bloom, bloom, bloom, true);
                Color streak = Render2DEngine.injectAlpha(col, a);
                Render2DEngine.renderGradientTexture(context, TextureStorage.fxStreak,
                        -width / 2f, -width * 0.2f, width, length + width * 0.2f,
                        0, 0, 64, 256, 64, 256, streak, streak, streak, streak, true);
                m.popMatrix();
            }
        }

        // Glow: ghostGlow is a hot centre with a long soft falloff - the right texture for light, where firefly's
        // halo is short. A wide faint halo, a tighter brighter one, then the nearly white core.
        float hx = (float) h.x, hy = (float) h.y;
        drawGlow(context, hx, hy, width * 5f, Render2DEngine.injectAlpha(col, Math.round(a * 0.55f)));
        drawGlow(context, hx, hy, width * 2.4f, Render2DEngine.injectAlpha(col, a));
        Color core = new Color(240, 255, 245, a);
        drawParticle(context, TextureStorage.firefly, hx, hy, width * 0.75f, core);
        drawParticle(context, TextureStorage.firefly, hx, hy, width * 0.45f, core);
    }

    /** An additive ghostGlow sprite centred on (cx, cy) - soft light with a long falloff. */
    private void drawGlow(DrawContext context, float cx, float cy, float size, Color col) {
        Render2DEngine.renderGradientTexture(context, TextureStorage.ghostGlow, cx - size / 2f, cy - size / 2f, size, size,
                0, 0, 128, 128, 128, 128, col, col, col, col, true);
    }

    private static Identifier textureFor(Mode mode) {
        return switch (mode) {
            case Stars -> TextureStorage.star;
            case Hearts -> TextureStorage.heart;
            case Dollars -> TextureStorage.dollar;
            case Bloom -> TextureStorage.firefly;
            case SnowFlake -> TextureStorage.snowflake;
            case Squares -> TextureStorage.squareOutline;
            // Cube is geometry, never a sprite - it never reaches the texture path.
            case Cube -> TextureStorage.squareOutline;
            // Marker is drawn as a comet by renderComet, never as a single sprite.
            case Marker -> TextureStorage.firefly;
            // All is resolved per particle at spawn, so it never reaches here; Off draws nothing.
            case Off, All -> TextureStorage.default_circle;
        };
    }

    public enum ColorMode {
        Custom, Sync
    }

    public enum Mode {
        Off, SnowFlake, Stars, Hearts, Dollars, Bloom, Squares,
        /** A real projected cube in the world, not a picture of a square. */
        Cube,
        /** Glowing comets falling at a slant: a bright head and a tail that fades along the path. */
        Marker,
        /**
         * Every shape at once.
         *
         * <p>Each particle picks its own shape when it spawns and keeps it for life, so the cloud is a
         * genuine mixture rather than everything switching together. Each shape also gets its own
         * spawn shell, so they sit at different distances from you instead of piling into the same
         * band of air.
         */
        All;

        /** The shapes All is allowed to pick from - everything real, so neither Off nor All itself. */
        static final Mode[] REAL = {SnowFlake, Stars, Hearts, Dollars, Bloom, Squares};
    }

    public enum Physics {
        Drop, Fly
    }
}
