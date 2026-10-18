package thunder.hack.utility.math;

import java.util.Random;

/**
 * Compact classic 1-D/2-D Perlin noise. Ported idea from the Rockstar aura's humaniser: instead of snapping dead
 * straight at the target, the aim gets a smooth, drifting offset so the rotation reads like a hand on a mouse.
 *
 * <p>Deterministic per instance (seeded on construction); make a new instance to reshuffle the drift.
 */
public final class PerlinNoise {
    private final int[] p = new int[512];

    public PerlinNoise() {
        this(new Random().nextLong());
    }

    public PerlinNoise(long seed) {
        int[] perm = new int[256];
        for (int i = 0; i < 256; i++) perm[i] = i;
        Random r = new Random(seed);
        for (int i = 255; i > 0; i--) {
            int j = r.nextInt(i + 1);
            int t = perm[i];
            perm[i] = perm[j];
            perm[j] = t;
        }
        for (int i = 0; i < 512; i++) p[i] = perm[i & 255];
    }

    private static double fade(double t) {
        return t * t * t * (t * (t * 6 - 15) + 10);
    }

    private static double lerp(double t, double a, double b) {
        return a + t * (b - a);
    }

    private static double grad(int hash, double x, double y) {
        int h = hash & 7;
        double u = h < 4 ? x : y;
        double v = h < 4 ? y : x;
        return ((h & 1) == 0 ? u : -u) + ((h & 2) == 0 ? v : -v);
    }

    /** 1-D noise in roughly [-1, 1]. */
    public double noise(double x) {
        return noise(x, 0.0);
    }

    /** 2-D noise in roughly [-1, 1]. */
    public double noise(double x, double y) {
        int xi = (int) Math.floor(x) & 255;
        int yi = (int) Math.floor(y) & 255;
        double xf = x - Math.floor(x);
        double yf = y - Math.floor(y);
        double u = fade(xf);
        double v = fade(yf);
        int aa = p[p[xi] + yi];
        int ab = p[p[xi] + yi + 1];
        int ba = p[p[xi + 1] + yi];
        int bb = p[p[xi + 1] + yi + 1];
        double x1 = lerp(u, grad(aa, xf, yf), grad(ba, xf - 1, yf));
        double x2 = lerp(u, grad(ab, xf, yf - 1), grad(bb, xf - 1, yf - 1));
        return lerp(v, x1, x2);
    }
}
