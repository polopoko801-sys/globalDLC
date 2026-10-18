import javax.imageio.ImageIO; import java.awt.image.BufferedImage; import java.awt.*; import java.io.File;
/** Turns a glow-on-black picture into colour + alpha: brightness becomes alpha, colour is un-premultiplied. */
public class TrailFromPhoto {
  public static void main(String[] a) throws Exception {
    BufferedImage src = ImageIO.read(new File(a[0]));
    int w = 1024, h = (int) Math.round(src.getHeight() * 1024.0 / src.getWidth());
    BufferedImage scaled = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
    Graphics2D g = scaled.createGraphics(); g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
    g.drawImage(src, 0, 0, w, h, null); g.dispose();
    BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
    for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
      int c = scaled.getRGB(x, y); int r = (c >> 16) & 255, gg = (c >> 8) & 255, b = c & 255;
      int m = Math.max(r, Math.max(gg, b));
      double al = Math.max(0, (m - 6) / 249.0); al = Math.pow(al, 0.8);
      int A = (int) Math.round(255 * Math.min(1, al));
      if (A < 2) { out.setRGB(x, y, 0); continue; }
      double k = 255.0 / Math.max(1, m);
      int R = (int) Math.min(255, r * k), G = (int) Math.min(255, gg * k), B = (int) Math.min(255, b * k);
      out.setRGB(x, y, (A << 24) | (R << 16) | (G << 8) | B);
    }
    ImageIO.write(out, "png", new File(a[1]));
    System.out.println(w + "x" + h);
  }
}
