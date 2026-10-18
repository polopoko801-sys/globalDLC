package thunder.hack.utility.render.core;

import net.minecraft.util.Identifier;
import net.minecraft.util.math.Vec3d;
import org.jetbrains.annotations.NotNull;
import thunder.hack.utility.render.Render2DEngine;
import thunder.hack.utility.render.TextureStorage;

import java.awt.Color;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * The client's particles, pooled.
 *
 * <p>A hit effect that allocates forty objects and throws them away 300 ms later, ten times a second in
 * a fight, is four hundred short-lived objects a second from one module. That is not a leak, but it is
 * exactly the shape of garbage that turns into a visible hitch when the collector catches up. So
 * particles are taken from a free list, reset in place, and handed back when they die - the pool reaches
 * its working size in the first fight and never allocates again.
 *
 * <p>Caps are hard, both globally and per owner: an effect that goes wrong can waste its own budget but
 * cannot take the frame down with it.
 */
public final class CloudanParticleRenderer {
    private CloudanParticleRenderer() {
    }

    /** Ceiling for the whole client at Ultra; every tier below scales it down. */
    private static final int MAX_PARTICLES = 2000;

    private static final List<Particle> ALIVE = new ArrayList<>();
    private static final Deque<Particle> POOL = new ArrayDeque<>();

    /** One particle. Mutable and reused - see the class note. */
    public static final class Particle {
        double x, y, z;
        double vx, vy, vz;
        double gravity, drag;
        float size, sizeDecay;
        long bornAt, lifetimeMs;
        int argb;
        Identifier texture;
        boolean throughWalls;
        /** What spawned it, so one effect's runaway cannot eat everyone else's budget. */
        String owner;

        void reset() {
            x = y = z = vx = vy = vz = 0;
            gravity = drag = 0;
            size = 0.2f;
            sizeDecay = 1f;
            bornAt = System.currentTimeMillis();
            lifetimeMs = 500;
            argb = 0xFFFFFFFF;
            texture = TextureStorage.fxSpark;
            throughWalls = false;
            owner = "";
        }

        float age() {
            return (System.currentTimeMillis() - bornAt) / (float) lifetimeMs;
        }

        boolean dead() {
            return age() >= 1f;
        }
    }

    /** Fluent spawn: fill in what matters, leave the rest at its default. */
    public static final class Spawn {
        private final Particle p;

        private Spawn(Particle p) {
            this.p = p;
        }

        public Spawn at(@NotNull Vec3d pos) {
            p.x = pos.x;
            p.y = pos.y;
            p.z = pos.z;
            return this;
        }

        public Spawn velocity(double vx, double vy, double vz) {
            p.vx = vx;
            p.vy = vy;
            p.vz = vz;
            return this;
        }

        public Spawn physics(double gravity, double drag) {
            p.gravity = gravity;
            p.drag = drag;
            return this;
        }

        public Spawn size(float size, float decayPerSecond) {
            p.size = size;
            p.sizeDecay = decayPerSecond;
            return this;
        }

        public Spawn life(long ms) {
            p.lifetimeMs = Math.max(1, ms);
            return this;
        }

        public Spawn color(@NotNull Color color) {
            p.argb = color.getRGB();
            return this;
        }

        public Spawn texture(@NotNull Identifier texture) {
            p.texture = texture;
            return this;
        }

        public Spawn throughWalls(boolean through) {
            p.throughWalls = through;
            return this;
        }
    }

    /**
     * Takes a particle from the pool for {@code owner}, or returns null when a cap says no.
     *
     * <p>Null rather than an exception on purpose: a caller in a loop should just stop spawning, not have
     * to guard every call.
     */
    public static Spawn spawn(@NotNull String owner, int ownerCap) {
        if (CloudanRenderCore.reduceEffects()) return null;

        int globalCap = CloudanRenderCore.budget(MAX_PARTICLES);
        if (ALIVE.size() >= globalCap) return null;
        if (countOf(owner) >= CloudanRenderCore.budget(ownerCap)) return null;

        Particle p = POOL.isEmpty() ? new Particle() : POOL.pop();
        p.reset();
        p.owner = owner;
        ALIVE.add(p);
        return new Spawn(p);
    }

    private static int countOf(String owner) {
        int n = 0;
        for (Particle p : ALIVE) if (p.owner.equals(owner)) n++;
        return n;
    }

    /**
     * Advances every particle. Driven off the client tick, not the frame, so the motion is the same at
     * 60 fps and at 300.
     */
    public static void tick() {
        if (ALIVE.isEmpty()) return;

        for (int i = ALIVE.size() - 1; i >= 0; i--) {
            Particle p = ALIVE.get(i);

            if (p.dead()) {
                ALIVE.remove(i);
                POOL.push(p);
                continue;
            }

            p.vy -= p.gravity;
            double keep = 1.0 - p.drag;
            p.vx *= keep;
            p.vy *= keep;
            p.vz *= keep;

            p.x += p.vx;
            p.y += p.vy;
            p.z += p.vz;
        }
    }

    /** Draws everything alive. Called by the core as the frame closes. */
    static void render() {
        if (ALIVE.isEmpty()) return;

        for (Particle p : ALIVE) {
            Vec3d pos = new Vec3d(p.x, p.y, p.z);
            if (!CloudanCullingSystem.isVisible(pos, p.size)) continue;

            float fade = 1f - p.age();
            float size = p.size * (1f - (1f - p.sizeDecay) * p.age());
            if (size <= 0.001f || fade <= 0.01f) continue;

            Color base = new Color(p.argb, true);
            CloudanBatchRenderer.billboard(p.texture, pos, size, 0,
                    Render2DEngine.injectAlpha(base, Math.round(base.getAlpha() * fade)), p.throughWalls);
        }
    }

    public static int alive() {
        return ALIVE.size();
    }

    public static int pooled() {
        return POOL.size();
    }

    /** Drops every live particle back into the pool. World change, module disable, SelfDestruct. */
    public static void clear() {
        POOL.addAll(ALIVE);
        ALIVE.clear();
    }

    /** Drops just one effect's particles, for a module turning itself off. */
    public static void clear(@NotNull String owner) {
        for (int i = ALIVE.size() - 1; i >= 0; i--) {
            if (!ALIVE.get(i).owner.equals(owner)) continue;
            POOL.push(ALIVE.remove(i));
        }
    }
}
