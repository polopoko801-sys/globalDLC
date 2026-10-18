import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;

/**
 * Generator tekstur wody dla FloorWeather (nie jest częścią moda - to jednorazowy skrypt, a PNG leżą w
 * src/main/resources/assets/gombal/textures/particles/).
 *
 * <p><b>Dlaczego tekstura nosi KOLOR.</b> Poprzednia wersja była biała z samą alfą, bo kolor miał nadawać
 * moduł przez {@code TintedTextures}. Efekt był taki, że na tafli nie było żadnego rysunku: wszystkie
 * szczegóły żyły w przezroczystości, a przezroczystość rozłożona równo po kafelku to jednolita mgiełka.
 * Stąd „pięć kratek" - płaska, jednobarwna powierzchnia zamiast wody. Prawdziwa woda to głównie
 * <em>odbicia</em>: jasne grzbiety, ciemne doliny, drobne iskry. To musi być w obrazie, bo tint mnoży cały
 * kafelek przez jeden kolor i nie potrafi dodać kontrastu, którego w nim nie ma.
 *
 * <p>Cztery tekstury, każda nosi własny kolor i własną alfę:
 * <ul>
 *   <li>{@code fx_water.png} - tafla: kolor od głębokiego granatu w dolinach do bladego błękitu na
 *       grzbietach, plus drobna zmarszczka i jasna siatka kaustyki;</li>
 *   <li>{@code fx_water_sheen.png} - odbicie nieba: duże, miękkie płaty bladego błękitu. To warstwa,
 *       która sprawia, że daleka woda jest jasna - patrząc po powierzchni widzisz niebo, nie wodę;</li>
 *   <li>{@code fx_water_caustic.png} - światło na DNIE: cienka, jasna siatka komórek, rysowana pod taflą,
 *       żeby dno wyglądało na oświetlone przez wodę;</li>
 *   <li>{@code fx_water_glint.png} - iskry: gęste, drobne punkty na grzbietach fal.</li>
 * </ul>
 *
 * <p>Wszystko jest CYKLICZNE (każda fala robi całkowitą liczbę okresów na kaflu, szum ma kratę zawijaną
 * modulo), więc tafla kafelkuje się bez szwu. Ziarno jest stałe, więc każdy build daje identyczny plik.
 *
 * <p>Uruchomienie: {@code java tools/GenWaterTexture.java <katalog wyjściowy>}
 */
public class GenWaterTexture {
    private static final int SIZE = 256;

    public static void main(String[] args) throws Exception {
        File dir = new File(args.length > 0 ? args[0] : ".");

        int[] body = body(), glint = glint(), sheen = sheen(), caustic = caustic();
        write(new File(dir, "fx_water.png"), body);
        write(new File(dir, "fx_water_sheen.png"), sheen);
        write(new File(dir, "fx_water_caustic.png"), caustic);
        write(new File(dir, "fx_water_glint.png"), glint);

        System.out.printf("fx_water.png         %dx%d  alpha %d..%d  mean %d  luma %d..%d%n",
                SIZE, SIZE, minAlpha(body), maxAlpha(body), meanAlpha(body), minLuma(body), maxLuma(body));
        System.out.printf("fx_water_sheen.png   %dx%d  alpha %d..%d  mean %d%n",
                SIZE, SIZE, minAlpha(sheen), maxAlpha(sheen), meanAlpha(sheen));
        System.out.printf("fx_water_caustic.png %dx%d  alpha %d..%d  mean %d%n",
                SIZE, SIZE, minAlpha(caustic), maxAlpha(caustic), meanAlpha(caustic));
        System.out.printf("fx_water_glint.png   %dx%d  alpha %d..%d  mean %d%n",
                SIZE, SIZE, minAlpha(glint), maxAlpha(glint), meanAlpha(glint));
    }

    /* ------------------------------------------------------------------ */
    /* The two sheets                                                      */
    /* ------------------------------------------------------------------ */

    /**
     * The surface itself, in colour.
     *
     * <p>The height field is a sum of plane waves: a slow swell that gives the big shape, chop that breaks
     * it up, and a fine ripple on top so the texture still has something to show at arm's length. The
     * shading is a fake normal - the slope of that height field dotted with a fixed light direction - which
     * is what makes crests read as crests instead of as noise: a wave is bright on the side facing the sky
     * and dark on the side facing away, and that is the whole trick.
     */
    private static int[] body() {
        int[] px = new int[SIZE * SIZE];
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                double u = x / (double) SIZE, v = y / (double) SIZE;

                double h = swellAndChop(u, v);
                // Slopes come from the WAVES only, sampled a few texels apart. Differentiating the fine
                // ripple as well turned the whole sheet into television static: the noise changes every
                // texel, so its slope swamped the waves and no crest could be read at all.
                double step = 4.0 / SIZE;
                double hx = (swellAndChop(u + step, v) - swellAndChop(u - step, v)) / (2 * step);
                double hz = (swellAndChop(u, v + step) - swellAndChop(u, v - step)) / (2 * step);
                // grain: the capillary ripple, a few percent either way, never enough to hide the waves
                double grain = fbm(u, v, 32, 3, 0x51A7E5L) - 0.5;

                // light from above and slightly to the left; flat water is a mirror, so the slope counts
                // much more than the height
                // Gentle on purpose. Full-strength slopes drove the ramp into both extremes and the sheet
                // came out as scratchy denim; water is mostly one quiet tone with the light carried by the
                // sparkle sheet, so the body only has to suggest the shape of the waves.
                double lit = 0.54 + h * 0.20 - (hx * 0.70 + hz * 0.45) * 0.030 + grain * 0.07;
                // the caustic web: thin bright lines where two wave trains cross
                double caustic = caustics(u, v);

                double t = clamp01(lit + caustic * 0.12);
                int rgb = ramp(t, caustic);
                int alpha = (int) Math.round((0.52 + 0.40 * t) * 255);
                px[y * SIZE + x] = (clampByte(alpha) << 24) | rgb;
            }
        }
        return px;
    }

    /**
     * Sun glitter.
     *
     * <p>Small and dense, not long streaks. The old sheet had a handful of long diagonals: at eight blocks
     * per repeat those turned into huge bright wedges lying across the floor, which is exactly what the
     * surface was being blamed for. Real glitter is thousands of tiny facets, so this is a threshold on fine
     * noise, gated by the crests so the sparks sit on the waves and not in the troughs.
     */
    private static int[] glint() {
        int[] alpha = new int[SIZE * SIZE];

        // Thousands of tiny facets, placed on a fixed seed. A threshold on noise was tried first and gave a
        // mean alpha of five - the sheet was effectively blank - because value noise rarely reaches high
        // values. Individual sparks are also what glitter really is: points, not texture.
        java.util.Random rnd = new java.util.Random(0x51A7E5L);
        for (int i = 0; i < 1500; i++) {
            double cx = rnd.nextDouble() * SIZE, cy = rnd.nextDouble() * SIZE;
            // Small: a spark seen from a metre away must not become a soft blob the size of a hand.
            double radius = 0.55 + rnd.nextDouble() * 1.05;
            double strength = 110 + rnd.nextInt(146);
            int ox = (int) Math.round(cx), oy = (int) Math.round(cy);
            int reach = (int) Math.ceil(radius + 1.4);

            for (int dy = -reach; dy <= reach; dy++) {
                for (int dx = -reach; dx <= reach; dx++) {
                    double d = Math.hypot(dx, dy);
                    if (d > radius + 1.4) continue;
                    double fall = Math.max(0, 1 - d / (radius + 1.4));
                    int add = (int) Math.round(strength * fall * fall);
                    if (add <= 0) continue;
                    // modulo, so a spark sitting on the edge continues on the opposite side
                    int x = Math.floorMod(ox + dx, SIZE), y = Math.floorMod(oy + dy, SIZE);
                    alpha[y * SIZE + x] = Math.min(255, alpha[y * SIZE + x] + add);
                }
            }
        }

        int[] px = new int[SIZE * SIZE];
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                double u = x / (double) SIZE, v = y / (double) SIZE;
                // a faint breath of sheen under the sparks, strongest on the crests, so the reflection layer
                // is not just dots on an empty sheet
                double crest = clamp01(0.55 + swellAndChop(u, v) * 0.8);
                double sheen = (fbm(u, v, 16, 2, 0x7A11C3L) - 0.45) * 70 * crest;
                double a = Math.min(255, alpha[y * SIZE + x] + Math.max(0, sheen));
                // warm specular, the colour of sunlight on water - not the blue-white of a sky highlight
                px[y * SIZE + x] = (clampByte(a) << 24) | 0xFFF3DC;
            }
        }
        return px;
    }

    /**
     * The sky, lying on the water.
     *
     * <p>Big and soft on purpose. This sheet is drawn at about two and a half times the body's tile and is
     * sampled far from the camera, so it cannot carry detail even if it had any: what it has to carry is
     * the shape of a reflected sky - wide bright patches with soft edges - and the fresnel term in the
     * module does the rest by fading it in towards the horizon.
     */
    private static int[] sheen() {
        int[] px = new int[SIZE * SIZE];
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                double u = x / (double) SIZE, v = y / (double) SIZE;

                double clouds = fbm(u, v, 4, 4, 0x1B3C77L);
                double bands = 0.06 * Math.sin(2 * Math.PI * (2 * u + 1 * v));
                double t = clamp01((clouds + bands - 0.40) / 0.30);
                double soft = t * t * (3 - 2 * t);                 // wide shoulders, no hard rim anywhere

                // What is being reflected is the land around the water, so the blobs are not one colour:
                // a second field picks brown (banks and trunks), olive (reeds and grass) or a muted grey
                // (rock, sky behind cloud) per patch. Blurred by construction - no hard edges anywhere.
                double pick = fbm(u + 0.61, v + 0.29, 5, 3, 0x5A17C4L);
                double r, g, b;
                if (pick < 0.40) {            // brown banks
                    r = 74 + 46 * soft; g = 56 + 38 * soft; b = 38 + 28 * soft;
                } else if (pick < 0.72) {     // olive reeds
                    r = 84 + 52 * soft; g = 86 + 50 * soft; b = 46 + 30 * soft;
                } else {                      // muted grey rock / cloud
                    r = 116 + 56 * soft; g = 118 + 56 * soft; b = 116 + 52 * soft;
                }

                // some patches are glossy and some are matte: the reflection layer must not be a uniform
                // sheen. Widened towards the top end, because the blurred reflections are what carries the
                // reference look - the water itself is almost featureless underneath them.
                double gloss = 0.45 + 0.90 * fbm(u + 0.13, v + 0.77, 6, 2, 0x0BADF00DL);
                int alpha = clampByte(soft * 190 * clamp01(gloss));
                px[y * SIZE + x] = (alpha << 24) | (clampByte(r) << 16) | (clampByte(g) << 8) | clampByte(b);
            }
        }
        return px;
    }

    /**
     * Sunlight on the bottom.
     *
     * <p>The web of light a moving surface focuses onto whatever is under it. Two wave trains multiplied and
     * raised to a high power leave thin bright lines where they cross and nothing in between, which is what
     * makes it read as light instead of as a second texture; drawn below the sheet, the pattern belongs to
     * the floor rather than to the water.
     */
    private static int[] caustic() {
        int[] px = new int[SIZE * SIZE];
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                double u = x / (double) SIZE, v = y / (double) SIZE;

                // warped the same way the surface is, so the cell walls wander instead of ruling straight
                double wu = u + (fbm(u, v, 4, 2, 0x165667B1L) - 0.5) * 0.22;
                double wv = v + (fbm(u + 0.19, v - 0.23, 4, 2, 0xD3A2646CL) - 0.5) * 0.22;
                double web = Math.pow(Math.abs(Math.sin(2 * Math.PI * (5 * wu + 2 * wv)) * Math.sin(2 * Math.PI * (2 * wu - 6 * wv) + 1.1)), 7);
                double web2 = Math.pow(Math.abs(Math.sin(2 * Math.PI * (3 * wu - 4 * wv) + 0.6) * Math.sin(2 * Math.PI * (7 * wu + 3 * wv))), 9);
                double cells = clamp01((web + web2 * 0.7) * 1.5);
                double breath = 0.35 + 0.65 * fbm(u, v, 8, 2, 0x4C9A2FL);     // some cells brighter than others

                int alpha = clampByte(cells * breath * 215);
                // sunlight through muddy water: warm, never cyan
                int r = clampByte(206 + 49 * cells), g = clampByte(186 + 40 * cells), b = clampByte(140 + 30 * cells);
                px[y * SIZE + x] = (alpha << 24) | (r << 16) | (g << 8) | b;
            }
        }
        return px;
    }

    /* ------------------------------------------------------------------ */
    /* Colour                                                              */
    /* ------------------------------------------------------------------ */

    /** Deep trough -> mid water -> lit crest -> foam. Stops are interpolated by {@code t}. */
    private static int ramp(double t, double caustic) {
        // 0.00 deep, 0.45 body, 0.78 crest, 1.00 foam
        // Muddy brown, not blue. This is the palette of the reference picture: the water is almost black
        // in the troughs, brown-olive in the body and only the crests lift towards a dull warm grey. Blue
        // is deliberately absent - it is the single strongest tell of "painted Minecraft water".
        double[][] stops = {
                {0.00, 16, 14, 11},
                {0.50, 36, 31, 23},
                {0.85, 58, 53, 40},
                {1.00, 96, 92, 72}
        };
        double[] c = stops[0];
        for (int i = 0; i < stops.length - 1; i++) {
            double[] a = stops[i], b = stops[i + 1];
            if (t >= a[0] && t <= b[0]) {
                double k = (t - a[0]) / (b[0] - a[0]);
                c = new double[]{t, a[1] + (b[1] - a[1]) * k, a[2] + (b[2] - a[2]) * k, a[3] + (b[3] - a[3]) * k};
                break;
            }
            if (t > b[0]) c = b;
        }
        // the light it focuses is warm sunlight, not white
        int r = clampByte(c[1] + caustic * 62);
        int g = clampByte(c[2] + caustic * 52);
        int b = clampByte(c[3] + caustic * 34);
        return (r << 16) | (g << 8) | b;
    }

    /* ------------------------------------------------------------------ */
    /* Fields                                                              */
    /* ------------------------------------------------------------------ */

    /**
     * Slow swell: few, wide waves. Integer cycle counts keep the tile seamless.
     *
     * <p>The pairs are deliberately UNRELATED - 3:1 next to 7:2 next to 11:-5 - because waves on the same
     * lattice line up and the sum turns into a corduroy of parallel ridges. That was visible on screen: the
     * first version read as woven fabric rather than water.
     */
    private static final int[][] SWELL = {{3, 1}, {7, 2}, {2, -5}, {11, -3}};
    /**
     * Mid waves: the band that actually carries the look.
     *
     * <p>At one texture per four blocks, ten to thirty cycles per sheet is chop of a few centimetres - the
     * scale you see on real water a metre away. The first version put most of its energy in the swell
     * instead, and near the camera that painted half-metre dark and light patches: it looked like brush
     * strokes, not water.
     */
    private static final int[][] MID = {{9, 4}, {-13, 7}, {17, -6}, {11, 15}, {-19, 8}, {23, 13}, {-7, 21}, {15, -19}};
    /** Chop: the fine capillary ripple on top of everything. */
    private static final int[][] CHOP = {{29, 11}, {-37, 17}, {43, -13}, {31, 23}, {-47, 9}, {53, 37}, {-59, 21}, {41, -43}};

    /**
     * The large-scale surface: swell and chop, no noise. This is what the shading follows.
     *
     * <p>Two things stop it looking manufactured. First, the sampling point is WARPED by low-frequency
     * noise before the waves are evaluated, so the ridges bend and braid instead of running dead straight.
     * Since the warp is periodic on the tile and every wave count is a whole number, the result is still
     * seamless - warping costs nothing in tileability. Second, the chop's amplitude is itself modulated by
     * noise, so crests come in patches the way wind-driven water does, rather than at one even strength
     * across the whole sheet.
     */
    private static double swellAndChop(double u, double v) {
        double warpU = (fbm(u, v, 3, 3, 0x9E3779B9L) - 0.5) * 0.16;
        double warpV = (fbm(u + 0.37, v + 0.11, 3, 3, 0x85EBCA6BL) - 0.5) * 0.16;
        double wu = u + warpU, wv = v + warpV;

        double swell = waves(SWELL, wu, wv, 1.3);
        double patchiness = 0.55 + 0.90 * fbm(u * 0.5, v * 0.5, 2, 2, 0x27D4EB2FL);
        double mid = waves(MID, wu, wv, 0.35) * patchiness;
        double chop = waves(CHOP, wu, wv, 0.8);
        return clamp(swell * 0.26 + mid * 0.52 + chop * 0.30, -1.2, 1.2);
    }

    /** Sum of waves along unrelated directions. Amplitudes fall off with index, like a real spectrum. */
    private static double waves(int[][] set, double u, double v, double offset) {
        double sum = 0, norm = 0;
        for (int i = 0; i < set.length; i++) {
            double amp = Math.pow(0.82, i);
            double phase = offset + i * 1.9 + 0.4 * Math.sin(i * 2.3);
            sum += amp * Math.sin(2 * Math.PI * (set[i][0] * u + set[i][1] * v) + phase);
            norm += amp;
        }
        return sum / norm;
    }

    /**
     * Where two wave trains run into each other - the thin bright web on the bottom of a pool.
     *
     * <p>Also warped, and for the same reason: an unwarped product of two sines is a perfect grid, and a
     * perfect grid of bright lines over a full-screen quad reads as a screen door.
     */
    private static double caustics(double u, double v) {
        double wu = u + (fbm(u, v, 4, 2, 0x165667B1L) - 0.5) * 0.22;
        double wv = v + (fbm(u + 0.19, v - 0.23, 4, 2, 0xD3A2646CL) - 0.5) * 0.22;
        double a = Math.sin(2 * Math.PI * (6 * wu + 3 * wv));
        double b = Math.sin(2 * Math.PI * (3 * wu - 7 * wv) + 1.1);
        double web = Math.pow(Math.abs(a * b), 6);
        return clamp01(web * 1.4);
    }

    /* ------------------------------------------------------------------ */
    /* Seamless value noise                                                */
    /* ------------------------------------------------------------------ */

    /** Repeated fractal value noise over the tile - wraps exactly, because every octave is sampled modulo its own lattice. */
    private static double fbm(double u, double v, int baseLattice, int octaves, long seed) {
        double sum = 0, amp = 1, norm = 0;
        int lattice = baseLattice;
        for (int o = 0; o < octaves; o++) {
            sum += amp * noise(u * lattice, v * lattice, lattice, seed + o * 7919L);
            norm += amp;
            amp *= 0.5;
            lattice *= 2;
        }
        return sum / norm;
    }

    /** Value noise on an integer lattice, interpolated smoothly; only the lattice nodes carry randomness. */
    private static double noise(double x, double y, int lattice, long seed) {
        int x0 = (int) Math.floor(x), y0 = (int) Math.floor(y);
        double fx = smooth(x - x0), fy = smooth(y - y0);
        int xa = Math.floorMod(x0, lattice), ya = Math.floorMod(y0, lattice);
        int xb = Math.floorMod(x0 + 1, lattice), yb = Math.floorMod(y0 + 1, lattice);

        double v00 = node(xa, ya, seed), v10 = node(xb, ya, seed);
        double v01 = node(xa, yb, seed), v11 = node(xb, yb, seed);
        return (v00 * (1 - fx) + v10 * fx) * (1 - fy) + (v01 * (1 - fx) + v11 * fx) * fy;
    }

    private static double node(int x, int y, long seed) {
        long h = x * 374761393L + y * 668265263L + seed * 2654435761L;
        h ^= h >>> 13;
        h *= 1274126177L;
        h ^= h >>> 16;
        return (h & 0xFFFFFF) / (double) 0xFFFFFF;
    }

    private static double smooth(double t) {
        return t * t * (3 - 2 * t);
    }

    /* ------------------------------------------------------------------ */
    /* Plumbing                                                            */
    /* ------------------------------------------------------------------ */

    private static double clamp01(double v) {
        return Math.max(0, Math.min(1, v));
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static int clampByte(double v) {
        return (int) Math.round(Math.max(0, Math.min(255, v)));
    }

    private static void write(File file, int[] px) throws Exception {
        BufferedImage img = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_ARGB);
        img.setRGB(0, 0, SIZE, SIZE, px, 0, SIZE);
        if (file.getParentFile() != null) file.getParentFile().mkdirs();
        ImageIO.write(img, "PNG", file);
    }

    private static int meanAlpha(int[] px) {
        long s = 0;
        for (int p : px) s += p >>> 24;
        return (int) (s / px.length);
    }

    private static int maxAlpha(int[] px) {
        int m = 0;
        for (int p : px) m = Math.max(m, p >>> 24);
        return m;
    }

    private static int minAlpha(int[] px) {
        int m = 255;
        for (int p : px) m = Math.min(m, p >>> 24);
        return m;
    }

    private static int luma(int p) {
        int r = (p >> 16) & 0xFF, g = (p >> 8) & 0xFF, b = p & 0xFF;
        return (r * 299 + g * 587 + b * 114) / 1000;
    }

    private static int maxLuma(int[] px) {
        int m = 0;
        for (int p : px) m = Math.max(m, luma(p));
        return m;
    }

    private static int minLuma(int[] px) {
        int m = 255;
        for (int p : px) m = Math.min(m, luma(p));
        return m;
    }
}
