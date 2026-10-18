package thunder.hack.features.modules.render;

import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import org.jetbrains.annotations.NotNull;
import thunder.hack.events.impl.EventAttack;
import thunder.hack.features.modules.Module;
import thunder.hack.setting.Setting;
import thunder.hack.setting.impl.ColorSetting;
import thunder.hack.utility.Timer;
import thunder.hack.utility.render.Render2DEngine;
import thunder.hack.utility.render.core.CloudanBatchRenderer;
import thunder.hack.utility.render.core.CloudanCullingSystem;
import thunder.hack.utility.render.core.CloudanRenderCore;

import java.awt.Color;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/**
 * Hit Block - a white pulse that runs out across the real surfaces of the world from where you landed a
 * hit, then fades.
 *
 * <p>Purely a renderer. It places nothing, breaks nothing and sends nothing; every "surface" it lights
 * up is a face of a block that is already there, drawn over for a fraction of a second.
 *
 * <p>The geometry is the point. Each lit patch is an actual exposed block face, found by walking the
 * surface outwards from the impact, so the wave climbs a step, turns a corner and runs along the top of
 * a slab because that is where the faces are - rather than being one flat rectangle laid over the world
 * and ignoring it.
 *
 * <h2>Why the propagation runs once</h2>
 * Finding the faces is a breadth-first walk over up to fifteen blocks of neighbourhood. Doing that every
 * frame would be hundreds of block lookups per frame per hit. So it runs ONCE when the hit lands, stores
 * each face with the distance it sits at, and every frame after that is only arithmetic: which band of
 * distances is currently lit, and how bright.
 */
public class HitBlock extends Module {
    public HitBlock() {
        this("HitBlock", Trigger.Attack);
    }

    /**
     * Shared by {@link BlockJump}, which is the same wave measured from your feet instead of from a
     * target. Duplicating the breadth-first surface walk into a second module to get a second name in
     * the list would be two copies of the only hard part of this file.
     */
    protected HitBlock(String name, Trigger defaultTrigger) {
        super(name, Category.RENDER);
        trigger.setValue(defaultTrigger);
    }

    /**
     * What sets the wave off. Jump is the same effect measured from your feet instead of from a target -
     * the propagation, the caps and the band are all shared, so this is a trigger rather than a second
     * module doing the same breadth-first walk twice.
     */
    protected final Setting<Trigger> trigger = new Setting<>("Trigger", Trigger.Attack);
    /**
     * Blocks draws each lit spot as a whole translucent cube with bright edges - the wave then reads as
     * a ring of blocks lighting up and going out. Faces draws only the exposed surface, which is subtler
     * but much harder to see at a distance.
     */
    private final Setting<RenderMode> renderMode = new Setting<>("RenderMode", RenderMode.Blocks);
    private final Setting<ColorSetting> color = new Setting<>("Color", new ColorSetting(0xFFFFFFFF));
    /** How far the wave reaches, in blocks. The spec's fifteen is the default and the sane ceiling. */
    private final Setting<Integer> range = new Setting<>("Range", 15, 3, 24);
    private final Setting<Integer> duration = new Setting<>("Duration", 650, 150, 2500);
    /** How wide the lit band is, in blocks. Narrow reads as a pulse, wide as a flood. */
    private final Setting<Float> width = new Setting<>("Width", 2.2f, 0.5f, 8f);
    private final Setting<Integer> opacity = new Setting<>("Opacity", 150, 20, 255);
    private final Setting<Boolean> glow = new Setting<>("Glow", true);
    private final Setting<Boolean> outline = new Setting<>("Outline", false);
    /** Multiplier on how fast the band travels outwards. 1 = it reaches Range exactly at Duration. */
    private final Setting<Float> speed = new Setting<>("Speed", 1f, 0.25f, 3f);
    private final Setting<Quality> quality = new Setting<>("Quality", Quality.High);
    private final Setting<Integer> cooldown = new Setting<>("Cooldown", 120, 0, 2000);

    public enum RenderMode {
        Blocks,
        Faces
    }

    public enum Trigger {
        /** When you land a hit. */
        Attack,
        /** When you leave the ground. */
        Jump,
        Both
    }

    public enum Quality {
        /** Fewer faces, no glow pass. */
        Low,
        Medium,
        High
    }

    /** How many faces one wave may ever hold, before quality scaling. */
    private static final int MAX_FACES = 1400;
    /** Two waves at once is plenty; a third only muddies the picture and costs another BFS. */
    private static final int MAX_WAVES = 3;

    /** One lit patch: a real block face and how far along the surface it sits from the impact. */
    private record Face(BlockPos pos, Direction dir, float distance) {
    }

    /** One pulse: the faces it may light, and when it started. */
    private static final class Wave {
        final List<Face> faces;
        final long start;
        final float maxDistance;

        Wave(List<Face> faces, float maxDistance) {
            this.faces = faces;
            this.start = System.currentTimeMillis();
            this.maxDistance = maxDistance;
        }
    }

    private final List<Wave> waves = new ArrayList<>();
    private final Timer cooldownTimer = new Timer();

    @Override
    public void onDisable() {
        waves.clear();
    }

    /* ------------------------------------------------------------------ */
    /* Trigger                                                             */
    /* ------------------------------------------------------------------ */

    @EventHandler
    public void onAttack(EventAttack event) {
        if (isOff() || fullNullCheck()) return;
        if (trigger.getValue() == Trigger.Jump) return;
        if (!cooldownTimer.passedMs(cooldown.getValue())) return;
        if (waves.size() >= MAX_WAVES) return;

        // The POST event is the one that carries the target. The PRE one is posted from
        // MinecraftClient.doAttack with a NULL entity - it only says "the attack key was pressed", not
        // what was hit - so listening to PRE meant the null check below rejected every single hit and
        // the wave never fired on an attack at all.
        if (event.isPre()) return;

        Entity target = event.getEntity();
        if (target == null) return;

        cooldownTimer.reset();

        // The contact point, not the target's feet: a wave that starts inside the body and spreads from
        // there is what makes it read as an impact rather than a marker under somebody.
        Vec3d impact = target.getBoundingBox().getCenter();
        List<Face> faces = propagate(impact);
        if (!faces.isEmpty()) waves.add(new Wave(faces, range.getValue()));
    }

    private boolean wasOnGround = true;

    /**
     * The jump trigger. Only the rising edge counts - leaving the ground is the jump, landing is not.
     */
    @Override
    public void onUpdate() {
        if (fullNullCheck() || trigger.getValue() == Trigger.Attack) return;

        boolean onGround = mc.player.isOnGround();
        boolean jumped = wasOnGround && !onGround;
        wasOnGround = onGround;

        if (!jumped || !cooldownTimer.passedMs(cooldown.getValue()) || waves.size() >= MAX_WAVES) return;
        cooldownTimer.reset();

        // From the feet, so the wave runs out along the ground you just left.
        Vec3d impact = new Vec3d(mc.player.getX(), mc.player.getBoundingBox().minY, mc.player.getZ());
        List<Face> faces = propagate(impact);
        if (!faces.isEmpty()) waves.add(new Wave(faces, range.getValue()));
    }

    /**
     * Walks the exposed surfaces outwards from the impact and records every face it can light.
     *
     * <p>Breadth-first over solid blocks that have at least one open face, so the distance a face ends up
     * with is the distance ALONG THE SURFACE rather than through the air - which is why the wave bends
     * round a wall instead of appearing on the far side of it.
     *
     * <p>Runs once per hit. Everything after this is arithmetic.
     */
    private @NotNull List<Face> propagate(@NotNull Vec3d impact) {
        BlockPos origin = BlockPos.ofFloored(impact);
        int reach = range.getValue();
        int faceCap = CloudanRenderCore.budget(switch (quality.getValue()) {
            case Low -> MAX_FACES / 4;
            case Medium -> MAX_FACES / 2;
            case High -> MAX_FACES;
        });

        List<Face> found = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();

        // Seed from the solid blocks immediately around the impact - the hit usually happens in mid air.
        for (BlockPos seed : BlockPos.iterate(origin.add(-2, -2, -2), origin.add(2, 2, 2))) {
            if (!isSolid(seed)) continue;
            BlockPos immutable = seed.toImmutable();
            if (seen.add(immutable.asLong())) queue.add(immutable);
        }

        while (!queue.isEmpty() && found.size() < faceCap) {
            BlockPos pos = queue.poll();

            float distance = (float) Math.sqrt(pos.toCenterPos().squaredDistanceTo(impact));
            if (distance > reach) continue;

            for (Direction dir : Direction.values()) {
                // Only faces with air (or anything non-solid) in front of them are visible surfaces.
                if (isSolid(pos.offset(dir))) continue;
                found.add(new Face(pos, dir, distance));
                if (found.size() >= faceCap) break;
            }

            for (Direction dir : Direction.values()) {
                BlockPos next = pos.offset(dir);
                if (!isSolid(next)) continue;
                if (next.toCenterPos().squaredDistanceTo(impact) > reach * reach) continue;
                BlockPos immutable = next.toImmutable();
                if (seen.add(immutable.asLong())) queue.add(immutable);
            }
        }

        return found;
    }

    private boolean isSolid(BlockPos pos) {
        if (mc.world == null) return false;
        return mc.world.getBlockState(pos).isSolidBlock(mc.world, pos);
    }

    /* ------------------------------------------------------------------ */
    /* Render                                                              */
    /* ------------------------------------------------------------------ */

    @Override
    public void onRender2D(DrawContext context) {
        if (fullNullCheck() || mc.options.hudHidden || waves.isEmpty()) return;

        Color base = color.getValue().getColorObject();
        int maxAlpha = opacity.getValue();
        float band = width.getValue();
        long life = duration.getValue();

        for (Iterator<Wave> it = waves.iterator(); it.hasNext(); ) {
            Wave wave = it.next();

            float t = (System.currentTimeMillis() - wave.start) / (float) life;
            if (t >= 1f) {
                it.remove();
                continue;
            }

            // Where the crest is right now, and how much the whole wave has faded overall.
            float crest = t * wave.maxDistance * speed.getValue();
            float overall = 1f - t * t;

            // In Blocks mode a block with four exposed faces would otherwise be submitted four times,
            // stacking its translucency until it looks solid while its neighbours stay faint.
            Set<Long> drawn = new HashSet<>();

            for (Face face : wave.faces) {
                float offset = Math.abs(face.distance() - crest);
                if (offset > band) continue;

                // Brightest at the crest, nothing at the edge of the band - that gradient IS the pulse.
                float strength = (1f - offset / band) * overall;
                if (strength <= 0.02f) continue;

                Box box = new Box(face.pos());
                if (!CloudanCullingSystem.isVisible(box)) continue;

                int alpha = Math.round(maxAlpha * strength);
                if (alpha <= 2) continue;

                Color fill = Render2DEngine.injectAlpha(base, alpha);
                Color edge = Render2DEngine.injectAlpha(base, Math.min(255, alpha * 2));

                if (renderMode.getValue() == RenderMode.Blocks) {
                    // One cube per lit spot, drawn once however many of its faces are exposed - the
                    // crest is a band of blocks rather than a band of surfaces.
                    if (!drawn.add(face.pos().asLong())) continue;
                    CloudanBatchRenderer.box(box, fill, edge, 1.2f, true, true);
                } else {
                    // Lifted a hair off the surface it is lighting. A face drawn exactly on the block is
                    // one flat translucent patch lying on a lit texture, which is why Faces looked like
                    // it did nothing: it WAS drawing, it just had nothing to stand out against.
                    Box lifted = box.offset(
                            face.dir().getOffsetX() * 0.01,
                            face.dir().getOffsetY() * 0.01,
                            face.dir().getOffsetZ() * 0.01);

                    // Always outlined. A single face needs its border to read at all, where a whole cube
                    // gets its shape from six of them overlapping.
                    CloudanBatchRenderer.side(lifted, face.dir(), fill, true, edge, outline.getValue() ? 2f : 1f);

                    // The glow pass is a second, fainter copy of the crest only - it thickens the leading
                    // edge without washing the whole band out.
                    if (glow.getValue() && quality.getValue() == Quality.High && offset < band * 0.35f)
                        CloudanBatchRenderer.side(lifted, face.dir(),
                                Render2DEngine.injectAlpha(base, Math.round(alpha * 0.5f)), false, fill, 1f);
                }
            }
        }
    }

    @Override
    public String getDisplayInfo() {
        return waves.isEmpty() ? null : waves.size() + "";
    }
}
