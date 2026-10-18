import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;

/** Composites an RGBA sprite over black, scaled up, so the matte can actually be judged. */
public class Preview {
    public static void main(String[] args) throws Exception {
        BufferedImage src = ImageIO.read(new File(args[0]));
        int scale = args.length > 2 ? Integer.parseInt(args[2]) : 2;
        int w = src.getWidth() * scale, h = src.getHeight() * scale;

        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++) {
                int p = src.getRGB(x / scale, y / scale);
                int a = (p >>> 24);
                int v = a;
                out.setRGB(x, y, (v << 16) | (v << 8) | v);
            }
        ImageIO.write(out, "png", new File(args[1]));
    }
}
