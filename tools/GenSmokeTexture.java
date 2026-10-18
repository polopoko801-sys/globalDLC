import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;

/**
 * Generator tekstury dymu dla BlockOverlay (nie jest częścią moda - images jest jednorazowym skryptem,
 * a PNG lezy w src/main/resources/assets/gombal/textures/particles/fx_smoke.png).
 *
 * <p>Siatka jest CYKLICZNA, wiec szw jest niewidoczny na sasiednich sciankach bloku i tekstura(tile) da sie
 * krecic bez widocznych przerw. Kolor bialy, modul nadaje wlasny tint.
 */
public class GenSmokeTexture {
    private static final int SIZE = 256;
    private static final int LATTICE = 8;      // 8x8 wezlow szumu na kaflu tekstury
    private static final double SCALE = 2.6;   // ile "chmur" na kaflu

    public static void main(String[] args) throws Exception {
        double[][] lattice = new double[LATTICE][LATTICE];
        java.util.Random rnd = new java.util.Random(0xC0FFEE); // deterministycznie, zeby kazdy build dawal to samo
        for (int y = 0; y < LATTICE; y++)
            for (int x = 0; x < LATTICE; x++)
                lattice[y][x] = rnd.nextDouble();

        BufferedImage img = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_ARGB);
        for (int py = 0; py < SIZE; py++) {
            for (int px = 0; px < SIZE; px++) {
                double n = periodicFbm(px / (double) SIZE * SCALE, py / (double) SIZE * SCALE, lattice);
                // dwa przebiegi wina: gora chmura i gesty core
                double cloud = clamp(n * 1.35 - 0.18);
                double core = clamp((n - 0.62) * 2.4);
                double value = clamp(cloud * 0.75 + core * 0.45);
                int alpha = (int) Math.round(Math.pow(value, 1.25) * 255);
                img.setRGB(px, py, (alpha << 24) | 0xFFFFFF);
            }
        }

        File out = new File(args.length > 0 ? args[0] : "fx_smoke.png");
        File parent = out.getParentFile();
        if (parent != null) parent.mkdirs();
        ImageIO.write(img, "PNG", out);
        System.out.println("zapisano " + out.getAbsolutePath() + " (" + SIZE + "x" + SIZE + ")");
    }

    /** Value noise na cyklicznej siatce - fract(sin) byloby tu za ziarniste, smoothstep daje miekkie chmury. */
    private static double periodicFbm(double u, double v, double[][] lattice) {
        double sum = 0, amp = 1, norm = 0;
        double freq = 1;
        for (int octave = 0; octave < 4; octave++) {
            sum += amp * latticeSample(u * freq, v * freq, lattice);
            norm += amp;
            amp *= 0.5;
            freq *= 2;
        }
        return sum / norm;
    }

    private static double latticeSample(double u, double v, double[][] lattice) {
        int x0 = (int) Math.floor(u), y0 = (int) Math.floor(v);
        double fx = smoothstep(u - x0), fy = smoothstep(v - y0);
        int n = lattice.length;
        double a = lattice[Math.floorMod(y0, n)][Math.floorMod(x0, n)];
        double b = lattice[Math.floorMod(y0, n)][Math.floorMod(x0 + 1, n)];
        double c = lattice[Math.floorMod(y0 + 1, n)][Math.floorMod(x0, n)];
        double d = lattice[Math.floorMod(y0 + 1, n)][Math.floorMod(x0 + 1, n)];
        return lerp(lerp(a, b, fx), lerp(c, d, fx), fy);
    }

    private static double smoothstep(double t) {
        t = clamp(t);
        return t * t * (3 - 2 * t);
    }

    private static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }

    private static double clamp(double v) {
        return Math.max(0, Math.min(1, v));
    }
}