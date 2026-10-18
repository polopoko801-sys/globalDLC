import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;

/**
 * Pulls a wing out of the reference screenshot.
 *
 * <p>The wing is a pale translucent shape laid over grass, so there is no colour that is purely wing -
 * every pixel is a mix of the two. What separates them is that the wing washes the grass out: it lifts
 * the brightness and drops the saturation. So the matte is built from how far each pixel has moved away
 * from the surrounding grass rather than from its own colour.
 */
public class Extract {
    public static void main(String[] args) throws Exception {
        BufferedImage src = ImageIO.read(new File(args[0]));
        File out = new File(args[1]);

        int x0 = Integer.parseInt(args[2]), y0 = Integer.parseInt(args[3]);
        int x1 = Integer.parseInt(args[4]), y1 = Integer.parseInt(args[5]);

        int w = x1 - x0, h = y1 - y0;

        // Sample the background from the outer border of the crop, which should be grass only.
        double bgLum = 0, bgSat = 0;
        int n = 0;
        for (int x = x0; x < x1; x++) {
            for (int y : new int[]{y0, y1 - 1}) {
                double[] ls = lumSat(src.getRGB(x, y));
                bgLum += ls[0];
                bgSat += ls[1];
                n++;
            }
        }
        bgLum /= n;
        bgSat /= n;
        System.out.printf("background lum %.1f sat %.1f%n", bgLum, bgSat);

        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        int opaque = 0;

        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++) {
                double[] ls = lumSat(src.getRGB(x0 + x, y0 + y));

                // Brighter than the grass AND less colourful than it: both must hold, because a sunlit
                // patch of grass is brighter too, and a shadow is less saturated too.
                double brighter = Math.max(0, ls[0] - bgLum) / 90.0;
                double washed = Math.max(0, bgSat - ls[1]) / 45.0;

                double a = Math.min(1.0, Math.pow(Math.min(1, brighter) * Math.min(1, washed), 0.6));
                if (a > 0.08) opaque++;

                int alpha = (int) Math.round(a * 255);
                img.setRGB(x, y, (alpha << 24) | 0x00FFFFFF);
            }

        System.out.printf("crop %dx%d, %d px above threshold (%.1f%%)%n",
                w, h, opaque, opaque * 100.0 / (w * h));
        ImageIO.write(img, "png", out);
    }

    static double[] lumSat(int p) {
        int r = (p >> 16) & 255, g = (p >> 8) & 255, b = p & 255;
        int max = Math.max(r, Math.max(g, b)), min = Math.min(r, Math.min(g, b));
        return new double[]{
                r * 0.299 + g * 0.587 + b * 0.114,
                max == 0 ? 0 : (max - min) * 255.0 / max
        };
    }
}
