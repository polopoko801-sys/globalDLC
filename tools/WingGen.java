import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.io.File;

/** Crystal shard wing: translucent faceted shards fanning from the root, bright glowing edges. White + alpha. */
public class WingGen {
    static final int W = 1024, H = 512;
    static final double RX = 0.03, RY = 0.50; // root, normalized

    // tip u, v, width at the shoulder, shoulder position along the shard
    static final double[][] SHARDS = {
            {0.52, 0.03, 0.21, 0.46},
            {0.93, 0.20, 0.17, 0.52},
            {0.78, 0.53, 0.15, 0.48},
            {0.94, 0.82, 0.16, 0.52},
            {0.48, 0.97, 0.19, 0.46},
    };

    public static void main(String[] a) throws Exception {
        BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);

        double rx = RX * W, ry = RY * H;

        // 1) translucent bodies: denser at the root, thinning towards the tips
        for (double[] s : SHARDS) {
            Path2D p = shard(s);
            double tx = s[0] * W, ty = s[1] * H;
            g.setPaint(new GradientPaint((float) rx, (float) ry, new Color(255, 255, 255, 135),
                    (float) tx, (float) ty, new Color(255, 255, 255, 60)));
            g.fill(p);
        }

        // 2) a soft core glow where the shards meet the back
        for (int i = 8; i >= 1; i--) {
            float r = i * 13f;
            g.setColor(new Color(255, 255, 255, 16));
            g.fill(new java.awt.geom.Ellipse2D.Double(rx - r * 0.6, ry - r, r * 1.6, r * 2));
        }

        // 3) facets: a ridge line down the middle of each shard
        g.setStroke(new BasicStroke(2.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        for (double[] s : SHARDS) {
            g.setColor(new Color(255, 255, 255, 120));
            g.draw(new java.awt.geom.Line2D.Double(rx, ry, s[0] * W, s[1] * H));
        }

        // 4) edges: wide faint glow, then a crisp bright line
        float[][] passes = {{14f, 22}, {8f, 45}, {4f, 110}, {2.2f, 255}};
        for (float[] pass : passes) {
            g.setStroke(new BasicStroke(pass[0], BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.setColor(new Color(255, 255, 255, (int) pass[1]));
            for (double[] s : SHARDS) g.draw(shard(s));
        }

        // 5) a few sparkles along the shards
        java.util.Random r = new java.util.Random(4);
        for (double[] s : SHARDS) {
            for (int k = 0; k < 2; k++) {
                double t = 0.35 + r.nextDouble() * 0.55;
                double x = rx + (s[0] * W - rx) * t, y = ry + (s[1] * H - ry) * t;
                sparkle(g, x, y, 7 + r.nextDouble() * 9);
            }
        }

        g.dispose();
        ImageIO.write(img, "png", new File(a[0]));
    }

    static Path2D shard(double[] s) {
        double rx = RX * W, ry = RY * H, tx = s[0] * W, ty = s[1] * H;
        double dx = tx - rx, dy = ty - ry, len = Math.hypot(dx, dy);
        double nx = -dy / len, ny = dx / len;
        double half = s[2] * H;
        double sx = rx + dx * s[3], sy = ry + dy * s[3];
        Path2D p = new Path2D.Double();
        p.moveTo(rx, ry);
        p.lineTo(sx + nx * half, sy + ny * half);
        p.lineTo(tx, ty);
        p.lineTo(sx - nx * half * 0.7, sy - ny * half * 0.7); // uneven sides read as cut crystal
        p.closePath();
        return p;
    }

    static void sparkle(Graphics2D g, double x, double y, double size) {
        for (int i = 3; i >= 1; i--) {
            double s = size * i / 3.0;
            g.setColor(new Color(255, 255, 255, i == 1 ? 255 : 60));
            Path2D p = new Path2D.Double();
            p.moveTo(x, y - s);
            p.lineTo(x + s * 0.22, y);
            p.lineTo(x, y + s);
            p.lineTo(x - s * 0.22, y);
            p.closePath();
            g.fill(p);
            Path2D q = new Path2D.Double();
            q.moveTo(x - s, y);
            q.lineTo(x, y + s * 0.22);
            q.lineTo(x + s, y);
            q.lineTo(x, y - s * 0.22);
            q.closePath();
            g.fill(q);
        }
    }
}
