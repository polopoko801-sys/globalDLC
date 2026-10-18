import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.awt.*;
import java.io.File;

/**
 * Turns the glossy "G" screenshot into the client logo set:
 *  - args[0]: source screenshot (G on a dark backdrop)
 *  - args[1]: output mini_logo.png  (128x128, transparent, used by WaterMark)
 *  - args[2]: output mod icon       (same art, size taken from the existing icon.png; pass "-" to skip)
 *
 * The backdrop is near-black but not black, so pixels below a brightness floor are transparent; the rest
 * is un-premultiplied (brightness -> alpha) so the glossy highlights keep their colour.
 */
public class LogoFromPhoto {
  public static void main(String[] a) throws Exception {
    BufferedImage src = ImageIO.read(new File(a[0]));
    int w = src.getWidth(), h = src.getHeight();

    // 1. bounding box of everything brighter than the backdrop floor
    int minX = w, minY = h, maxX = -1, maxY = -1;
    for (int y = 0; y < h; y++)
      for (int x = 0; x < w; x++) {
        int c = src.getRGB(x, y);
        int m = Math.max((c >> 16) & 255, Math.max((c >> 8) & 255, c & 255));
        if (m > 55) { // backdrop sits in the 0..40 range, the glyph starts well above
          if (x < minX) minX = x;
          if (y < minY) minY = y;
          if (x > maxX) maxX = x;
          if (y > maxY) maxY = y;
        }
      }
    if (maxX < 0) throw new IllegalStateException("nothing bright found");
    System.out.println("crop " + minX + "," + minY + " .. " + maxX + "," + maxY);

    int cw = maxX - minX + 1, ch = maxY - minY + 1;
    int side = Math.max(cw, ch) + 4; // small breathing room
    int ox = minX - (side - cw) / 2, oy = minY - (side - ch) / 2;

    // 2. crop into a transparent canvas floor alpha=0, then scale to 128
    BufferedImage sq = new BufferedImage(side, side, BufferedImage.TYPE_INT_ARGB);
    for (int y = 0; y < side; y++)
      for (int x = 0; x < side; x++) {
        int sx = x + ox, sy = y + oy;
        if (sx < 0 || sy < 0 || sx >= w || sy >= h) continue;
        int c = src.getRGB(sx, sy);
        int r = (c >> 16) & 255, g = (c >> 8) & 255, b = c & 255;
        int m = Math.max(r, Math.max(g, b));
        if (m <= 55) continue; // backdrop -> transparent
        double al = Math.pow(Math.min(1, (m - 55) / 200.0), 0.75);
        int A = (int) Math.round(255 * al);
        if (A < 8) continue;
        double k = 255.0 / Math.max(1, m);
        sq.setRGB(x, y, (A << 24) | (Math.min(255, (int) (r * k)) << 16) | (Math.min(255, (int) (g * k)) << 8) | Math.min(255, (int) (b * k)));
      }

    BufferedImage out128 = scale(sq, 128);
    ImageIO.write(out128, "png", new File(a[1]));
    System.out.println("wrote " + a[1] + " (128x128)");

    if (a.length > 2 && !a[2].equals("-")) {
      BufferedImage old = ImageIO.read(new File(a[2]));
      int size = old.getWidth() == old.getHeight() ? old.getWidth() : Math.max(old.getWidth(), old.getHeight());
      ImageIO.write(scale(sq, size), "png", new File(a[2]));
      System.out.println("wrote " + a[2] + " (" + size + "x" + size + ")");
    }
  }

  static BufferedImage scale(BufferedImage in, int size) {
    BufferedImage out = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
    Graphics2D g = out.createGraphics();
    g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
    g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
    g.drawImage(in, 0, 0, size, size, null);
    g.dispose();
    return out;
  }
}
