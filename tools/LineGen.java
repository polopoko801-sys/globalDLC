import javax.imageio.ImageIO; import java.awt.image.BufferedImage; import java.io.File;
/** fx_line.png: a crisp line across V - solid core, one soft texel each side. */
public class LineGen { public static void main(String[] a) throws Exception {
  int W = 8, H = 16; BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_ARGB);
  for (int y = 0; y < H; y++) { double d = Math.abs(y - 7.5) / 8.0; int al = d < 0.70 ? 255 : (int) Math.max(0, 255 * (1 - (d - 0.70) / 0.30));
    for (int x = 0; x < W; x++) img.setRGB(x, y, (al << 24) | 0xFFFFFF); }
  ImageIO.write(img, "png", new File(a[0])); } }
