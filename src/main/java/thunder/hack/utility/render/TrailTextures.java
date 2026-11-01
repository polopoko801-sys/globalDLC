package thunder.hack.utility.render;

import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.util.Identifier;
import thunder.hack.utility.ThunderUtility;

import java.awt.*;
import java.awt.geom.Ellipse2D;
import java.awt.geom.GeneralPath;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.util.EnumMap;
import java.util.Map;

/**
 * Sprite textures for the Trails "Images" mode, drawn in code at first use - no image files shipped, nothing
 * downloaded. Every sprite is WHITE on transparent with a soft glow, so the trail can tint it to any colour
 * through the vertex colour (and an untinted one stays a clean white shape).
 */
public final class TrailTextures {
    private TrailTextures() {
    }

    public enum Kind {Stars, Hearts, Sparkles, Flames, Snowflakes, Bubbles, Petals, Notes}

    private static final int SIZE = 128;
    private static final Map<Kind, Identifier> CACHE = new EnumMap<>(Kind.class);

    /** The texture for a kind, generated and registered on first use (render thread). */
    public static Identifier get(Kind kind) {
        return CACHE.computeIfAbsent(kind, k -> {
            BufferedImage img = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = img.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            glow(g, k == Kind.Flames ? 0.55f : 0.42f);
            g.setColor(Color.WHITE);
            switch (k) {
                case Stars -> g.fill(star(5, 44, 19));
                case Sparkles -> g.fill(star(4, 50, 11));
                case Hearts -> g.fill(heart());
                case Flames -> flame(g);
                case Snowflakes -> snowflake(g);
                case Bubbles -> bubble(g);
                case Petals -> petal(g);
                case Notes -> note(g);
            }
            g.dispose();
            return register("trail_" + k.name().toLowerCase(), img);
        });
    }

    /* ---------------------------------------------------------------- shapes */

    /** Soft round halo behind the shape, so it reads as light rather than a flat sticker. */
    private static void glow(Graphics2D g, float strength) {
        float c = SIZE / 2f;
        g.setPaint(new RadialGradientPaint(c, c, SIZE * 0.5f, new float[]{0f, 0.45f, 1f},
                new Color[]{new Color(1f, 1f, 1f, strength), new Color(1f, 1f, 1f, strength * 0.25f), new Color(1f, 1f, 1f, 0f)}));
        g.fillRect(0, 0, SIZE, SIZE);
    }

    private static Shape star(int points, double outer, double inner) {
        Path2D p = new Path2D.Double();
        double c = SIZE / 2.0;
        for (int i = 0; i < points * 2; i++) {
            double r = i % 2 == 0 ? outer : inner;
            double a = -Math.PI / 2 + Math.PI * i / points;
            double x = c + Math.cos(a) * r, y = c + Math.sin(a) * r;
            if (i == 0) p.moveTo(x, y);
            else p.lineTo(x, y);
        }
        p.closePath();
        return p;
    }

    private static Shape heart() {
        GeneralPath p = new GeneralPath();
        double c = SIZE / 2.0;
        p.moveTo(c, 100);
        p.curveTo(c - 52, 70, c - 50, 22, c - 22, 26);
        p.curveTo(c - 8, 28, c, 40, c, 48);
        p.curveTo(c, 40, c + 8, 28, c + 22, 26);
        p.curveTo(c + 50, 22, c + 52, 70, c, 100);
        p.closePath();
        return p;
    }

    private static void flame(Graphics2D g) {
        GeneralPath p = new GeneralPath();
        double c = SIZE / 2.0;
        p.moveTo(c, 14);
        p.curveTo(c + 18, 40, c + 38, 62, c + 30, 88);
        p.curveTo(c + 24, 108, c - 24, 108, c - 30, 88);
        p.curveTo(c - 38, 62, c - 18, 40, c, 14);
        p.closePath();
        // brighter core towards the bottom, like a real flame
        g.setPaint(new GradientPaint(0, 20, new Color(1f, 1f, 1f, 0.55f), 0, 104, Color.WHITE));
        g.fill(p);
        g.setColor(new Color(1f, 1f, 1f, 0.9f));
        g.fill(new Ellipse2D.Double(c - 12, 70, 24, 28));
    }

    private static void snowflake(Graphics2D g) {
        double c = SIZE / 2.0;
        g.setStroke(new BasicStroke(6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        for (int i = 0; i < 6; i++) {
            double a = Math.PI * i / 3;
            double ex = c + Math.cos(a) * 46, ey = c + Math.sin(a) * 46;
            g.draw(new java.awt.geom.Line2D.Double(c, c, ex, ey));
            for (double t : new double[]{0.45, 0.72}) {
                double bx = c + Math.cos(a) * 46 * t, by = c + Math.sin(a) * 46 * t;
                double len = 14 * (1.1 - t);
                for (int s = -1; s <= 1; s += 2) {
                    double ba = a + s * Math.PI / 4;
                    g.draw(new java.awt.geom.Line2D.Double(bx, by, bx + Math.cos(ba) * len, by + Math.sin(ba) * len));
                }
            }
        }
    }

    private static void bubble(Graphics2D g) {
        double c = SIZE / 2.0;
        g.setColor(new Color(1f, 1f, 1f, 0.22f));
        g.fill(new Ellipse2D.Double(c - 40, c - 40, 80, 80));
        g.setColor(Color.WHITE);
        g.setStroke(new BasicStroke(5f));
        g.draw(new Ellipse2D.Double(c - 40, c - 40, 80, 80));
        g.fill(new Ellipse2D.Double(c - 24, c - 26, 16, 12)); // highlight
    }

    private static void petal(Graphics2D g) {
        double c = SIZE / 2.0;
        GeneralPath p = new GeneralPath();
        p.moveTo(c, 18);
        p.curveTo(c + 40, 40, c + 34, 92, c, 110);
        p.curveTo(c - 34, 92, c - 40, 40, c, 18);
        p.closePath();
        g.fill(p);
        g.setColor(new Color(1f, 1f, 1f, 0.45f));
        g.setStroke(new BasicStroke(2.5f));
        g.draw(new java.awt.geom.Line2D.Double(c, 26, c, 102));
    }

    private static void note(Graphics2D g) {
        g.fill(new Ellipse2D.Double(30, 78, 34, 26));
        g.fill(new Rectangle(58, 26, 7, 66));
        GeneralPath flag = new GeneralPath();
        flag.moveTo(62, 26);
        flag.curveTo(80, 34, 96, 40, 92, 66);
        flag.curveTo(88, 52, 78, 46, 62, 44);
        flag.closePath();
        g.fill(flag);
    }

    /* ---------------------------------------------------------------- upload */

    private static Identifier register(String name, BufferedImage bi) {
        NativeImage image = new NativeImage(NativeImage.Format.RGBA, bi.getWidth(), bi.getHeight(), false);
        for (int y = 0; y < bi.getHeight(); y++)
            for (int x = 0; x < bi.getWidth(); x++)
                image.setColorArgb(x, y, bi.getRGB(x, y));
        return ThunderUtility.registerDynamicTexture(name, new NativeImageBackedTexture(() -> "gombal", image));
    }
}
