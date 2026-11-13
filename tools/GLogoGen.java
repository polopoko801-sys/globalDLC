import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.io.File;

/**
 * The client's "G" mark: a faceted, sharp-cut white G (constant 18-unit strokes, diagonal tips, an arrow-head
 * crossbar), with the lower-right facets a shade darker so it reads as a cut solid rather than flat.
 *
 * <p>Usage: {@code java tools/GLogoGen.java <out.png> <size>} - transparent background.
 */
public class GLogoGen {
    // 0..100 design units
    private static final double[][] G = {
            {30, 8}, {84, 8}, {72, 26}, {38, 26}, {26, 38}, {26, 62}, {38, 74}, {62, 74}, {74, 62},
            {58, 62}, {49, 53}, {58, 44}, {92, 44}, {92, 70}, {70, 92}, {30, 92}, {8, 70}, {8, 30}
    };

    // darker facets: the lower-right part of the bowl and the underside of the crossbar
    private static final double[][] SHADE_BOTTOM = {{8, 70}, {26, 62}, {38, 74}, {62, 74}, {74, 62}, {92, 70}, {70, 92}, {30, 92}};
    private static final double[][] SHADE_BAR = {{49, 53}, {58, 62}, {74, 62}, {92, 70}, {92, 53}};

    public static void main(String[] args) throws Exception {
        String out = args.length > 0 ? args[0] : "logo.png";
        int size = args.length > 1 ? Integer.parseInt(args[1]) : 512;
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        double s = size / 100.0;

        Shape shape = path(G, s);
        g.setColor(Color.WHITE);
        g.fill(shape);

        // facets, clipped to the letter
        g.setClip(shape);
        g.setColor(new Color(214, 216, 222));
        g.fill(path(SHADE_BOTTOM, s));
        g.setColor(new Color(196, 199, 207));
        g.fill(path(SHADE_BAR, s));
        g.setClip(null);

        g.dispose();
        ImageIO.write(img, "png", new File(out));
    }

    private static Shape path(double[][] pts, double s) {
        Path2D.Double p = new Path2D.Double();
        p.moveTo(pts[0][0] * s, pts[0][1] * s);
        for (int i = 1; i < pts.length; i++) p.lineTo(pts[i][0] * s, pts[i][1] * s);
        p.closePath();
        return p;
    }
}
