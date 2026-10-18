import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;

/**
 * Turns a rough extracted matte into a usable sprite.
 *
 * <p>Four passes, in the order that matters: throw away isolated specks, trim to what is left, push the
 * contrast so the soft screenshot edges become edges again, then resample to the size the renderer wants.
 * Trimming before resampling is the important bit - otherwise the sprite is mostly empty margin and the
 * wing ends up drawn at a fraction of the size the settings say.
 */
public class Finish {
    public static void main(String[] args) throws Exception {
        BufferedImage src = ImageIO.read(new File(args[0]));
        File out = new File(args[1]);
        int targetW = Integer.parseInt(args[2]);
        int targetH = Integer.parseInt(args[3]);
        boolean mirror = args.length > 4 && Boolean.parseBoolean(args[4]);

        int w = src.getWidth(), h = src.getHeight();
        int[][] a = new int[h][w];
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++) a[y][x] = src.getRGB(x, y) >>> 24;

        // 1. Despeckle: a pixel with almost no lit neighbours is compression noise, not wing.
        int[][] clean = new int[h][w];
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++) {
                int sum = 0, n = 0;
                for (int dy = -2; dy <= 2; dy++)
                    for (int dx = -2; dx <= 2; dx++) {
                        int nx = x + dx, ny = y + dy;
                        if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue;
                        sum += a[ny][nx];
                        n++;
                    }
                int neighbourhood = sum / n;
                clean[y][x] = neighbourhood < 26 ? 0 : a[y][x];
            }

        // 2. Trim to content.
        int minX = w, minY = h, maxX = -1, maxY = -1;
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++) {
                if (clean[y][x] < 20) continue;
                minX = Math.min(minX, x);
                maxX = Math.max(maxX, x);
                minY = Math.min(minY, y);
                maxY = Math.max(maxY, y);
            }
        if (maxX < 0) throw new IllegalStateException("nothing left after despeckle");
        System.out.printf("content %d,%d -> %d,%d%n", minX, minY, maxX, maxY);

        int cw = maxX - minX + 1, ch = maxY - minY + 1;
        BufferedImage trimmed = new BufferedImage(cw, ch, BufferedImage.TYPE_INT_ARGB);

        for (int y = 0; y < ch; y++)
            for (int x = 0; x < cw; x++) {
                // 3. Contrast: the source is a blurry screenshot, so everything is mid-grey. An s-curve
                // pushes the faint haze down and the panel bodies up, which puts the edges back.
                double v = clean[minY + y][minX + x] / 255.0;
                v = Math.max(0, (v - 0.18) / 0.72);
                v = v * v * (3 - 2 * v);
                int alpha = (int) Math.round(Math.min(1, v) * 255);
                trimmed.setRGB(mirror ? cw - 1 - x : x, y, (alpha << 24) | 0x00FFFFFF);
            }

        // 4. Resample.
        BufferedImage scaled = new BufferedImage(targetW, targetH, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = scaled.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(trimmed, 0, 0, targetW, targetH, null);
        g.dispose();

        ImageIO.write(scaled, "png", out);
        System.out.println("wrote " + out.getName() + " " + targetW + "x" + targetH);
    }
}
