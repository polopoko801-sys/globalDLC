import javax.swing.*;
import javax.swing.Timer;
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.awt.image.ConvolveOp;
import java.awt.image.Kernel;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** Wspolne komponenty i styl dla obu aplikacji (Injector + Admin). */
public final class UI {

    // theme-dependent (mutated by setTheme); read at paint time so a toggle re-themes live
    public static Color BG_TOP    = new Color(16, 13, 22);
    public static Color BG_BOTTOM = new Color(8, 7, 12);
    public static Color PANEL     = new Color(19, 17, 26, 235);
    public static Color CARD      = new Color(24, 21, 33, 235);
    public static Color ACCENT    = new Color(168, 85, 247);
    public static Color ACCENT2   = new Color(124, 58, 237);
    // fixed
    public static final Color TEXT      = new Color(236, 236, 243);
    public static final Color TEXT_DIM  = new Color(140, 140, 158);
    public static final Color DANGER    = new Color(255, 86, 110);
    public static final Color OK        = new Color(74, 222, 128);
    public static final Color GOLD      = new Color(255, 198, 90);
    public static final Color ORANGE    = new Color(255, 150, 70);
    public static final Color BORDER    = new Color(255, 255, 255, 30);
    /** Neutralny (szary) przycisk drugorzędny — dla akcji, które nie mają być akcentowane kolorem. */
    public static final Color CARD_BTN  = new Color(52, 48, 66);

    public static boolean purpleTheme = true;

    /** Switches between the Purple theme and a neutral Dark theme. */
    public static void setTheme(boolean purple) {
        purpleTheme = purple;
        if (purple) {
            ACCENT = new Color(168, 85, 247);  ACCENT2 = new Color(124, 58, 237);
            BG_TOP = new Color(16, 13, 22);     BG_BOTTOM = new Color(8, 7, 12);
            PANEL  = new Color(19, 17, 26, 235); CARD = new Color(24, 21, 33, 235);
        } else {
            ACCENT = new Color(96, 121, 168);   ACCENT2 = new Color(64, 84, 122);
            BG_TOP = new Color(14, 15, 19);      BG_BOTTOM = new Color(7, 8, 11);
            PANEL  = new Color(20, 21, 27, 235); CARD = new Color(25, 26, 33, 235);
        }
    }

    static Color lighten(Color c, int amt) {
        return new Color(Math.min(255, c.getRed() + amt), Math.min(255, c.getGreen() + amt),
                Math.min(255, c.getBlue() + amt), c.getAlpha());
    }

    static Color alpha(Color c, int a) {
        return new Color(c.getRed(), c.getGreen(), c.getBlue(), a);
    }

    public static final Font  H1   = new Font("Segoe UI", Font.BOLD, 28);
    public static final Font  BOLD = new Font("Segoe UI", Font.BOLD, 13);
    public static final Font  MONO = new Font("Consolas", Font.PLAIN, 12);

    /** Kolor przypisany randze. */
    public static Color rankColor(String rank) {
        if (rank == null) return TEXT_DIM;
        switch (rank.toUpperCase()) {
            case "DEV":    return ACCENT2;
            case "TESTER": return ORANGE;
            case "OWNER":  return GOLD;
            case "SELLER": return OK;
            case "CUSTOMER":
            default:       return ACCENT;
        }
    }

    private UI() {}

    // ===================== Tlo: gradient + pole gwiazd =====================
    public static class BackgroundPanel extends JPanel {
        final List<Star> stars = new ArrayList<>();
        final Random rnd = new Random(7);
        float t = 0;
        public BackgroundPanel() {
            setOpaque(false);
            for (int i = 0; i < 55; i++) stars.add(new Star(rnd));
            new Timer(33, e -> { t += 0.016f; repaint(); }).start();
        }
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g.create();
            paintBackdrop(g2, getWidth(), getHeight());
            g2.dispose();
        }

        /**
         * Paints the backdrop and nothing else - no children.
         *
         * <p>Split out of {@link #paintComponent} so {@link GlassPanel} can render the thing it is
         * supposed to be frosting. Going through {@code paint()} would draw the children too, and one of
         * those children is the glass panel itself: straight into infinite recursion.
         */
        public void paintBackdrop(Graphics2D g2, int w, int h) {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setPaint(new GradientPaint(0, 0, BG_TOP, 0, h, BG_BOTTOM));
            g2.fillRect(0, 0, w, h);
            // subtle drifting purple glow for depth (kept faint to match the flat-dark look)
            float dx = (float) Math.sin(t * 0.22f) * 34f, dy = (float) Math.cos(t * 0.18f) * 24f;
            radial(g2, w, h, w * 0.20f + dx, h * 0.16f + dy, 460, new Color(168, 85, 247, 30));
            radial(g2, w, h, w * 0.86f - dx, h * 0.86f - dy, 480, new Color(124, 58, 237, 26));
            for (Star s : stars) s.draw(g2, w, h, t);
            // vignette: gently darken the edges so the panel pops
            g2.setPaint(new RadialGradientPaint(new Point2D.Float(w / 2f, h / 2f), Math.max(w, h) * 0.72f,
                    new float[]{0f, 1f}, new Color[]{new Color(0, 0, 0, 0), new Color(0, 0, 0, 125)}));
            g2.fillRect(0, 0, w, h);
        }
        void radial(Graphics2D g2, int w, int h, float cx, float cy, float r, Color c) {
            g2.setPaint(new RadialGradientPaint(new Point2D.Float(cx, cy), r,
                    new float[]{0f, 1f},
                    new Color[]{c, new Color(c.getRed(), c.getGreen(), c.getBlue(), 0)}));
            g2.fillRect(0, 0, w, h);
        }
    }

    /**
     * Frosted glass, the way iOS does it: what is behind the panel is blurred, a thin white film is laid
     * over it, and a hairline edge plus a sheen along the top give it thickness.
     *
     * <p>Swing has no backdrop filter, so the blur is real work: the panel asks the window's
     * {@link BackgroundPanel} to paint its own slice of the backdrop into a small offscreen image, blurs
     * that, and stretches it back. Rendering at a sixth of the size is what makes it cheap AND most of
     * what makes it look blurred - the convolutions on top only smooth out what is left.
     *
     * <p>The backdrop drifts slowly, so the blurred copy is cached and only rebuilt a few times a second.
     * Re-blurring at 30 fps would cost a lot and look identical.
     */
    public static class GlassPanel extends JPanel {
        /** How far the backdrop is shrunk before blurring. Higher = softer and cheaper. */
        private static final int DOWNSCALE = 6;
        private static final long REBUILD_MS = 120;

        private static final ConvolveOp BLUR = gaussian();

        private final int arc;
        /** How milky the glass is, 0-255. iOS sits around a tenth of full white. */
        private final int film;

        private BufferedImage cache;
        private long builtAt;

        public GlassPanel(int arc) {
            this(arc, 26);
        }

        public GlassPanel(int arc, int film) {
            this.arc = arc;
            this.film = film;
            setOpaque(false);
        }

        @Override
        protected void paintComponent(Graphics g) {
            int w = getWidth(), h = getHeight();
            if (w <= 0 || h <= 0) return;

            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);

            RoundRectangle2D round = new RoundRectangle2D.Float(0, 0, w, h, arc, arc);
            Shape oldClip = g2.getClip();
            g2.clip(round);

            BufferedImage blurred = backdrop(w, h);
            if (blurred != null) g2.drawImage(blurred, 0, 0, w, h, null);
            else {
                // No backdrop to sample (panel not in one of our windows) - fall back to a flat card so
                // the layout still reads correctly instead of showing a hole.
                g2.setColor(CARD);
                g2.fill(round);
            }

            g2.setColor(new Color(255, 255, 255, film));
            g2.fill(round);

            int sheen = Math.min(h, 90);
            g2.setPaint(new GradientPaint(0, 0, new Color(255, 255, 255, 30), 0, sheen, new Color(255, 255, 255, 0)));
            g2.fillRect(0, 0, w, sheen);

            g2.setClip(oldClip);

            // Two edges: a bright hairline just inside, and a darker one on the outside. That pair is what
            // makes a flat fill read as a pane of glass with thickness rather than a rectangle.
            g2.setStroke(new BasicStroke(1f));
            g2.setColor(new Color(255, 255, 255, 46));
            g2.draw(new RoundRectangle2D.Float(0.5f, 0.5f, w - 1, h - 1, arc, arc));
            g2.setColor(new Color(0, 0, 0, 60));
            g2.draw(new RoundRectangle2D.Float(1.5f, 1.5f, w - 3, h - 3, arc - 2, arc - 2));

            g2.dispose();
            super.paintComponent(g);
        }

        private BufferedImage backdrop(int w, int h) {
            BackgroundPanel bg = findBackground();
            if (bg == null || bg.getWidth() <= 0) return null;

            int sw = Math.max(1, w / DOWNSCALE), sh = Math.max(1, h / DOWNSCALE);
            long now = System.currentTimeMillis();
            if (cache != null && cache.getWidth() == sw && cache.getHeight() == sh && now - builtAt < REBUILD_MS)
                return cache;

            BufferedImage img = new BufferedImage(sw, sh, BufferedImage.TYPE_INT_RGB);
            Graphics2D ig = img.createGraphics();
            ig.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            ig.scale(1.0 / DOWNSCALE, 1.0 / DOWNSCALE);

            Point origin = SwingUtilities.convertPoint(this, 0, 0, bg);
            ig.translate(-origin.x, -origin.y);
            bg.paintBackdrop(ig, bg.getWidth(), bg.getHeight());
            ig.dispose();

            // Two light passes rather than one heavy kernel: cheaper, and repeated box-ish blurs converge
            // on a gaussian anyway.
            img = BLUR.filter(img, null);
            img = BLUR.filter(img, null);

            cache = img;
            builtAt = now;
            return cache;
        }

        private BackgroundPanel findBackground() {
            for (Container c = getParent(); c != null; c = c.getParent())
                if (c instanceof BackgroundPanel bg) return bg;
            return null;
        }

        private static ConvolveOp gaussian() {
            float[] k = {
                    1 / 16f, 2 / 16f, 1 / 16f,
                    2 / 16f, 4 / 16f, 2 / 16f,
                    1 / 16f, 2 / 16f, 1 / 16f
            };
            return new ConvolveOp(new Kernel(3, 3, k), ConvolveOp.EDGE_NO_OP, null);
        }
    }
    static class Star {
        float x, y, size, phase, speed;
        Star(Random r) {
            x = r.nextFloat(); y = r.nextFloat();
            size = 0.6f + r.nextFloat() * 1.8f;
            phase = r.nextFloat() * 6.28f;
            speed = 0.5f + r.nextFloat() * 2f;
        }
        void draw(Graphics2D g2, int w, int h, float t) {
            float a = (float) (0.30 + 0.30 * Math.sin(phase + t * speed));
            int alpha = Math.max(0, Math.min(255, (int) (a * 90)));
            g2.setColor(new Color(200, 180, 240, alpha));
            g2.fill(new Ellipse2D.Float(x * w, y * h, size, size));
        }
    }

    // ===================== Panele i przyciski =====================
    public static class RoundPanel extends JPanel {
        final int arc; final Color fill;
        public RoundPanel(int arc, Color fill) { this.arc = arc; this.fill = fill; setOpaque(false); }
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int w = getWidth(), h = getHeight();
            RoundRectangle2D round = new RoundRectangle2D.Float(0, 0, w, h, arc, arc);
            // vertical gradient fill (top a touch lighter) for depth
            g2.setPaint(new GradientPaint(0, 0, lighten(fill, 12), 0, h, fill));
            g2.fill(round);
            // glass sheen across the top
            Shape old = g2.getClip();
            g2.setClip(round);
            int sheen = Math.min(h, 80);
            g2.setPaint(new GradientPaint(0, 0, new Color(255, 255, 255, 24), 0, sheen, new Color(255, 255, 255, 0)));
            g2.fillRect(0, 0, w, sheen);
            g2.setClip(old);
            // crisp border
            g2.setStroke(new BasicStroke(1f));
            g2.setColor(BORDER);
            g2.draw(new RoundRectangle2D.Float(0.5f, 0.5f, w - 1, h - 1, arc, arc));
            g2.dispose();
            super.paintComponent(g);
        }
    }

    public static class GlowButton extends JButton {
        final Color c1, c2; float hover = 0, pulse = 0;
        public GlowButton(String text, Color c1, Color c2) {
            super(text); this.c1 = c1; this.c2 = c2;
            setContentAreaFilled(false); setFocusPainted(false); setBorderPainted(false);
            setForeground(new Color(8, 12, 20));
            setFont(new Font("Segoe UI", Font.BOLD, 15));
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setPreferredSize(new Dimension(240, 46));
            setMaximumSize(new Dimension(420, 46));
            new Timer(16, e -> {
                float tgt = getModel().isRollover() ? 1f : 0f;
                hover += (tgt - hover) * 0.2f;
                pulse += 0.06f;
                repaint();
            }).start();
        }
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int w = getWidth(), h = getHeight();
            boolean off = !isEnabled();
            // idle pulsujaca poswiata + mocniejsza przy hover
            if (!off) {
                float p = 0.5f + 0.5f * (float) Math.sin(pulse);
                int glowA = Math.min(130, (int) (24 * p + 80 * hover));
                float ext = 6f + 3f * p + 4f * hover;
                g2.setColor(new Color(c1.getRed(), c1.getGreen(), c1.getBlue(), glowA));
                g2.fill(new RoundRectangle2D.Float(-ext, -ext, w + ext * 2, h + ext * 2, h + ext * 2, h + ext * 2));
            }
            Color a = off ? new Color(60, 66, 84) : c1;
            Color b = off ? new Color(50, 54, 72) : c2;
            g2.setPaint(new GradientPaint(0, 0, a, w, h, b));
            g2.fill(new RoundRectangle2D.Float(0, 0, w, h, h, h));
            // glossy sheen over the top half
            RoundRectangle2D pill = new RoundRectangle2D.Float(0, 0, w, h, h, h);
            Shape oldClip = g2.getClip();
            g2.setClip(pill);
            g2.setPaint(new GradientPaint(0, 0, new Color(255, 255, 255, off ? 24 : 78), 0, h / 2f, new Color(255, 255, 255, 0)));
            g2.fillRect(0, 0, w, h / 2);
            g2.setClip(oldClip);
            if (getModel().isPressed()) {
                g2.setColor(new Color(0, 0, 0, 40));
                g2.fill(new RoundRectangle2D.Float(0, 0, w, h, h, h));
            }
            g2.setColor(off ? TEXT_DIM : getForeground());
            g2.setFont(getFont());
            FontMetrics fm = g2.getFontMetrics();
            g2.drawString(getText(), (w - fm.stringWidth(getText())) / 2,
                    (h + fm.getAscent() - fm.getDescent()) / 2);
            g2.dispose();
        }
    }

    /**
     * Zwykły, czysty przycisk: umiarkowanie zaokrąglony prostokąt, spokojny gradient, cienka ramka,
     * delikatne rozjaśnienie przy najechaniu i wciśnięcie w dół. Bez pulsującej poświaty i pigułkowego
     * kształtu — wygląda jak normalny przycisk aplikacji.
     */
    public static class FlatButton extends JButton {
        final Color base;
        float hover = 0, press = 0;

        public FlatButton(String text, Color base) {
            super(text);
            this.base = base;
            setContentAreaFilled(false);
            setFocusPainted(false);
            setBorderPainted(false);
            setForeground(Color.WHITE);
            setFont(new Font("Segoe UI", Font.BOLD, 13));
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setPreferredSize(new Dimension(150, 38));
            new Timer(16, e -> {
                float ht = getModel().isRollover() ? 1f : 0f;
                float pt = getModel().isPressed() ? 1f : 0f;
                boolean ch = Math.abs(ht - hover) > 0.001f || Math.abs(pt - press) > 0.001f;
                hover += (ht - hover) * 0.25f;
                press += (pt - press) * 0.35f;
                if (ch) repaint();
            }).start();
        }

        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            int w = getWidth(), h = getHeight();
            int arc = 10;
            boolean off = !isEnabled();

            int lift = (int) (14 * hover - 16 * press);
            Color top = off ? new Color(58, 60, 74) : lighten(base, 10 + lift);
            Color bot = off ? new Color(48, 50, 62) : darken2(base, 8 - lift);

            RoundRectangle2D r = new RoundRectangle2D.Float(0.5f, 0.5f, w - 1, h - 1, arc, arc);
            g2.setPaint(new GradientPaint(0, 0, top, 0, h, bot));
            g2.fill(r);

            // cienka ramka: jasniejsza na gorze, ciemniejsza na dole - daje delikatna wypuklosc
            g2.setColor(alpha(Color.WHITE, off ? 20 : (int) (34 + 26 * hover)));
            g2.draw(new RoundRectangle2D.Float(0.5f, 0.5f, w - 1, h - 1, arc, arc));

            g2.setColor(off ? TEXT_DIM : getForeground());
            g2.setFont(getFont());
            FontMetrics fm = g2.getFontMetrics();
            int ty = (h + fm.getAscent() - fm.getDescent()) / 2 + (int) (press * 1);
            g2.drawString(getText(), (w - fm.stringWidth(getText())) / 2, ty);
            g2.dispose();
        }
    }

    static Color darken2(Color c, int amt) {
        return new Color(Math.max(0, c.getRed() - amt), Math.max(0, c.getGreen() - amt),
                Math.max(0, c.getBlue() - amt), c.getAlpha());
    }

    /** Przycisk okna: rysuje ikone "min" (kreska) lub "close" (X) wektorowo - bez emoji. */
    public static class WinButton extends JButton {
        final boolean danger; final String kind;
        public WinButton(String kind, boolean danger, Runnable action) {
            this.kind = kind; this.danger = danger;
            setContentAreaFilled(false); setFocusPainted(false); setBorderPainted(false);
            setForeground(TEXT_DIM);
            setPreferredSize(new Dimension(30, 26));
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            addActionListener(e -> action.run());
        }
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            if (getModel().isRollover()) {
                g2.setColor(danger ? DANGER : new Color(255, 255, 255, 30));
                g2.fill(new RoundRectangle2D.Float(0, 0, getWidth(), getHeight(), 8, 8));
            }
            g2.setColor(getModel().isRollover() ? Color.WHITE : getForeground());
            int cx = getWidth() / 2, cy = getHeight() / 2;
            if ("close".equals(kind)) {
                g2.setStroke(new BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g2.drawLine(cx - 5, cy - 5, cx + 5, cy + 5);
                g2.drawLine(cx - 5, cy + 5, cx + 5, cy - 5);
            } else { // min
                g2.setStroke(new BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g2.drawLine(cx - 5, cy + 3, cx + 5, cy + 3);
            }
            g2.dispose();
        }
    }

    /** Statyczna gwiazdka-logo (gradient) zamiast emoji. */
    public static class LogoStar extends JComponent {
        final int size;
        public LogoStar(int size) {
            this.size = size;
            setOpaque(false);
            setPreferredSize(new Dimension(size, size));
            setMaximumSize(new Dimension(Integer.MAX_VALUE, size));
        }
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            double cx = getWidth() / 2.0, cy = getHeight() / 2.0, r = size * 0.46;
            Shape s = StarSpinner.star(cx, cy, r, r * 0.4, 5, 0);
            g2.setColor(new Color(40, 224, 212, 70));
            g2.setStroke(new BasicStroke(7f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g2.draw(s);
            g2.setPaint(new GradientPaint((float) (cx - r), (float) (cy - r), ACCENT,
                    (float) (cx + r), (float) (cy + r), ACCENT2));
            g2.fill(s);
            g2.setColor(new Color(255, 255, 255, 190));
            g2.setStroke(new BasicStroke(1.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g2.draw(s);
            g2.dispose();
        }
    }

    public static JLabel gradLabel(String text, int size) {
        JLabel l = new JLabel(text) {
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                g2.setFont(getFont());
                FontMetrics fm = g2.getFontMetrics();
                int tx = getHorizontalAlignment() == SwingConstants.CENTER
                        ? (getWidth() - fm.stringWidth(getText())) / 2 : 0;
                g2.setPaint(new GradientPaint(0, 0, ACCENT, Math.max(1, getWidth()), 0, ACCENT2));
                g2.drawString(getText(), tx, fm.getAscent());
                g2.dispose();
            }
        };
        l.setFont(new Font("Segoe UI", Font.BOLD, size));
        l.setForeground(ACCENT);
        return l;
    }

    // ===================== Kreconca gwiazda =====================
    public static class StarSpinner extends JComponent {
        double angle = 0, speed = 0.03, targetSpeed = 0.03;
        Color color = ACCENT; Timer t;
        public StarSpinner() {
            setPreferredSize(new Dimension(150, 150));
            setMaximumSize(new Dimension(150, 150));
        }
        public void start() {
            if (t != null) return;
            t = new Timer(16, e -> { speed += (targetSpeed - speed) * 0.06; angle += speed; repaint(); });
            t.start();
        }
        public void boost()     { targetSpeed = 0.28; color = ACCENT; }
        public void normal()    { targetSpeed = 0.03; color = ACCENT; }
        public void celebrate() { targetSpeed = 0.12; color = OK; }
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            double cx = getWidth() / 2.0, cy = getHeight() / 2.0;
            float r = 64;
            g2.setPaint(new RadialGradientPaint(new Point2D.Double(cx, cy), r, new float[]{0f, 1f},
                    new Color[]{new Color(color.getRed(), color.getGreen(), color.getBlue(), 90),
                            new Color(color.getRed(), color.getGreen(), color.getBlue(), 0)}));
            g2.fill(new Ellipse2D.Double(cx - r, cy - r, r * 2, r * 2));
            g2.setStroke(new BasicStroke(2f));
            g2.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(), 60));
            g2.draw(new Ellipse2D.Double(cx - 56, cy - 56, 112, 112));
            Shape s1 = star(cx, cy, 46, 19, 5, angle);
            g2.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(), 55));
            g2.setStroke(new BasicStroke(8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g2.draw(s1);
            Shape s2 = star(cx, cy, 42, 17, 5, angle);
            g2.setPaint(new GradientPaint((float) cx - 42, (float) cy - 42, ACCENT,
                    (float) cx + 42, (float) cy + 42, ACCENT2));
            g2.fill(s2);
            g2.setColor(new Color(255, 255, 255, 180));
            g2.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g2.draw(s2);
            g2.setColor(new Color(255, 255, 255, 230));
            g2.fill(star(cx, cy, 16, 6, 5, -angle * 1.6));
            g2.dispose();
        }
        static Shape star(double cx, double cy, double rO, double rI, int pts, double rot) {
            Path2D p = new Path2D.Double();
            double a = rot - Math.PI / 2, step = Math.PI / pts;
            for (int i = 0; i < pts * 2; i++) {
                double rr = (i % 2 == 0) ? rO : rI;
                double x = cx + Math.cos(a) * rr, y = cy + Math.sin(a) * rr;
                if (i == 0) p.moveTo(x, y); else p.lineTo(x, y);
                a += step;
            }
            p.closePath();
            return p;
        }
    }

    // ===================== Pasek ladowania =====================
    public static class ProgressBar extends JComponent {
        double value = 0, target = 0;
        boolean success = false, hasResult = false;
        float shimmer = 0;
        public ProgressBar() {
            setPreferredSize(new Dimension(10, 34));
            setMaximumSize(new Dimension(Integer.MAX_VALUE, 34));
            new Timer(16, e -> {
                value += (target - value) * 0.12;
                if (Math.abs(target - value) < 0.4) value = target;   // domknij do pelnej wartosci
                shimmer += 0.04f;
                repaint();
            }).start();
        }
        public void setTarget(int pct) { target = Math.max(0, Math.min(100, pct)); }
        public void setSuccess(boolean ok) { success = ok; hasResult = true; }
        public void reset() { value = 0; target = 0; hasResult = false; success = false; }
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int w = getWidth(), h = 12;
            int y = getHeight() - h;                 // pasek przy samym dole komponentu
            g2.setColor(new Color(255, 255, 255, 18));
            g2.fill(new RoundRectangle2D.Float(0, y, w, h, h, h));
            int fw = (int) Math.round(w * value / 100.0);
            if (fw > 0) {
                Color c = hasResult && !success ? DANGER : (hasResult && success ? OK : ACCENT);
                Color b = hasResult && !success ? new Color(255, 140, 120) : ACCENT2;
                g2.setPaint(new GradientPaint(0, 0, c, Math.max(fw, 1), 0, b));
                Shape clip = new RoundRectangle2D.Float(0, y, Math.max(fw, h), h, h, h);
                g2.fill(clip);
                if (!hasResult) {
                    g2.setClip(clip);
                    float sx = (shimmer % 1f) * (fw + 120) - 60;
                    g2.setPaint(new GradientPaint(sx, 0, new Color(255, 255, 255, 0),
                            sx + 60, 0, new Color(255, 255, 255, 90)));
                    g2.fillRect(0, y, fw, h);
                    g2.setClip(null);
                }
            }
            // procent NAD paskiem, w granicach komponentu (nie przycina sie)
            g2.setColor(TEXT);
            g2.setFont(new Font("Consolas", Font.BOLD, 12));
            String pct = Math.round(value) + "%";
            g2.drawString(pct, w - g2.getFontMetrics().stringWidth(pct), y - 6);
            g2.dispose();
        }
    }

    /* ==================================================================== */
    /* Landing-page pieces                                                   */
    /*                                                                       */
    /* The parts a modern product page is built out of: an eyebrow over a    */
    /* two-tone headline, a row of stat chips under it, a segmented switch   */
    /* and a search field. They are here rather than in one app because      */
    /* every window we have wants the same five shapes.                      */
    /* ==================================================================== */

    /** The same font with letters spread apart - what makes a small caps line read as a label. */
    public static Font tracked(Font font, float amount) {
        java.util.Map<java.awt.font.TextAttribute, Object> attrs = new java.util.HashMap<>();
        attrs.put(java.awt.font.TextAttribute.TRACKING, amount);
        return font.deriveFont(attrs);
    }

    /**
     * The small accented line above a headline: a star, then letterspaced caps.
     *
     * <p>It exists to give the title something to sit under. A headline alone floats; a headline with a
     * label over it reads as the top of a page.
     */
    public static class Eyebrow extends JComponent {
        private static final Font FONT = tracked(new Font("Segoe UI", Font.BOLD, 11), 0.22f);

        private final String text;

        public Eyebrow(String text) {
            this.text = text.toUpperCase();
        }

        /**
         * As wide as the text needs.
         *
         * <p>Measured rather than guessed: a component that reports a fixed width gets exactly that much
         * room from a layout that honours preferred sizes, and the rest of the line is simply cut off.
         */
        @Override
        public Dimension getPreferredSize() {
            return new Dimension(20 + getFontMetrics(FONT).stringWidth(text) + 4, 20);
        }

        @Override
        public Dimension getMaximumSize() {
            return getPreferredSize();
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

            int h = getHeight();
            float cy = h / 2f;

            g2.setPaint(new GradientPaint(0, 0, ACCENT, 16, h, ACCENT2));
            g2.setStroke(new BasicStroke(1.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g2.fill(StarSpinner.star(7, cy, 6, 2.6, 5, 0));

            g2.setFont(FONT);
            g2.setColor(ACCENT);
            FontMetrics fm = g2.getFontMetrics();
            g2.drawString(text, 20, cy + (fm.getAscent() - fm.getDescent()) / 2f);
            g2.dispose();
        }
    }

    /**
     * The headline: one word plain, the next in the accent gradient.
     *
     * <p>Two tones rather than one because a single flat colour at this size is a heading, and two is a
     * wordmark. The gradient half is the one people remember.
     */
    public static class Hero extends JComponent {
        private final String plain, accented;
        private final int size;

        public Hero(String plain, String accented, int size) {
            this.plain = plain;
            this.accented = accented;
            this.size = size;
        }

        private Font font() {
            return tracked(new Font("Segoe UI", Font.BOLD, size), -0.02f);
        }

        /** Measured, not guessed - see {@link Eyebrow#getPreferredSize()}. */
        @Override
        public Dimension getPreferredSize() {
            return new Dimension(getFontMetrics(font()).stringWidth(plain + " " + accented) + 6,
                    (int) (size * 1.35f));
        }

        @Override
        public Dimension getMaximumSize() {
            return getPreferredSize();
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

            Font font = font();
            g2.setFont(font);
            FontMetrics fm = g2.getFontMetrics();
            float baseline = (getHeight() + fm.getAscent() - fm.getDescent()) / 2f;
            float x = 0;

            // A soft dark halo under the words: at this weight the letters would otherwise sit flat on
            // the starfield instead of in front of it.
            g2.setColor(new Color(0, 0, 0, 120));
            g2.drawString(plain, x + 1.5f, baseline + 1.5f);
            g2.setColor(TEXT);
            g2.drawString(plain, x, baseline);
            x += fm.stringWidth(plain + " ");

            float width = fm.stringWidth(accented);
            g2.setColor(new Color(0, 0, 0, 120));
            g2.drawString(accented, x + 1.5f, baseline + 1.5f);
            g2.setPaint(new GradientPaint(x, 0, lighten(ACCENT, 40), x + width, 0, ACCENT2));
            g2.drawString(accented, x, baseline);
            g2.dispose();
        }
    }

    /**
     * One of the little bordered chips under a headline: a coloured dot, a bold value, a dim label.
     *
     * <p>Given an action it becomes clickable and lights up on hover; without one it is just a readout.
     */
    public static class StatPill extends JComponent {
        private final Color dot;
        private String value, label;
        private final Runnable action;
        private boolean hover;

        public StatPill(Color dot, String value, String label, Runnable action) {
            this.dot = dot;
            this.value = value;
            this.label = label;
            this.action = action;
            setPreferredSize(new Dimension(10, 34));
            setMaximumSize(new Dimension(Integer.MAX_VALUE, 34));

            if (action == null) return;
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            addMouseListener(new MouseAdapter() {
                @Override
                public void mouseEntered(MouseEvent e) { hover = true; repaint(); }

                @Override
                public void mouseExited(MouseEvent e) { hover = false; repaint(); }

                @Override
                public void mousePressed(MouseEvent e) { action.run(); }
            });
        }

        public void set(String value, String label) {
            this.value = value;
            this.label = label;
            revalidate();
            repaint();
        }

        /** Wide enough for its own text - the row that holds these lays them out side by side. */
        @Override
        public Dimension getPreferredSize() {
            FontMetrics bold = getFontMetrics(new Font("Segoe UI", Font.BOLD, 12));
            FontMetrics plain = getFontMetrics(new Font("Segoe UI", Font.PLAIN, 12));
            return new Dimension(34 + bold.stringWidth(value) + 5 + plain.stringWidth(label) + 16, 34);
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

            int w = getWidth(), h = getHeight();
            RoundRectangle2D shape = new RoundRectangle2D.Float(0.5f, 0.5f, w - 1, h - 1, h, h);

            g2.setColor(new Color(255, 255, 255, hover ? 16 : 8));
            g2.fill(shape);
            g2.setColor(new Color(255, 255, 255, hover ? 55 : 28));
            g2.draw(shape);

            float cy = h / 2f;
            g2.setColor(new Color(dot.getRed(), dot.getGreen(), dot.getBlue(), 60));
            g2.fill(new Ellipse2D.Float(13, cy - 6, 12, 12));
            g2.setColor(dot);
            g2.fill(new Ellipse2D.Float(16, cy - 3, 6, 6));

            float x = 34;
            g2.setFont(new Font("Segoe UI", Font.BOLD, 12));
            FontMetrics fm = g2.getFontMetrics();
            g2.setColor(TEXT);
            g2.drawString(value, x, cy + (fm.getAscent() - fm.getDescent()) / 2f);
            x += fm.stringWidth(value) + 5;

            g2.setFont(new Font("Segoe UI", Font.PLAIN, 12));
            g2.setColor(TEXT_DIM);
            g2.drawString(label, x, cy + (fm.getAscent() - fm.getDescent()) / 2f);
            g2.dispose();
        }
    }

    /** A row of stat pills that wraps instead of squashing when the window is narrow. */
    public static JPanel pillRow(JComponent... pills) {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        row.setOpaque(false);
        for (JComponent pill : pills) row.add(pill);
        return row;
    }

    /**
     * A segmented switch: one of N, the chosen one filled and outlined in the accent.
     *
     * <p>Used where a dropdown would also work and would be worse - with four options there is nothing
     * to hide behind a click.
     */
    public static class SegTabs extends JComponent {
        private final String[] labels;
        private final java.util.function.IntConsumer onSelect;
        private int selected, hovered = -1;

        public SegTabs(String[] labels, java.util.function.IntConsumer onSelect) {
            this.labels = labels;
            this.onSelect = onSelect;
            setPreferredSize(new Dimension(10, 34));
            setMaximumSize(new Dimension(Integer.MAX_VALUE, 34));
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

            addMouseListener(new MouseAdapter() {
                @Override
                public void mouseExited(MouseEvent e) { hovered = -1; repaint(); }

                @Override
                public void mousePressed(MouseEvent e) {
                    int index = indexAt(e.getX());
                    if (index < 0 || index == selected) return;
                    selected = index;
                    repaint();
                    onSelect.accept(index);
                }
            });
            addMouseMotionListener(new MouseAdapter() {
                @Override
                public void mouseMoved(MouseEvent e) {
                    int index = indexAt(e.getX());
                    if (index == hovered) return;
                    hovered = index;
                    repaint();
                }
            });
        }

        public int selected() {
            return selected;
        }

        /** Moves to a tab without calling back - for when the app itself decided to change tabs. */
        public void set(int index) {
            selected = Math.max(0, Math.min(labels.length - 1, index));
            repaint();
        }

        private float segment() {
            return (getWidth() - 8f) / labels.length;
        }

        private int indexAt(int x) {
            int index = (int) ((x - 4) / segment());
            return index < 0 || index >= labels.length ? -1 : index;
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

            int w = getWidth(), h = getHeight();
            g2.setColor(new Color(255, 255, 255, 8));
            g2.fill(new RoundRectangle2D.Float(0.5f, 0.5f, w - 1, h - 1, 11, 11));
            g2.setColor(new Color(255, 255, 255, 22));
            g2.draw(new RoundRectangle2D.Float(0.5f, 0.5f, w - 1, h - 1, 11, 11));

            float seg = segment();
            g2.setFont(new Font("Segoe UI", Font.BOLD, 11));
            FontMetrics fm = g2.getFontMetrics();

            for (int i = 0; i < labels.length; i++) {
                float x = 4 + i * seg;
                boolean on = i == selected;

                if (on) {
                    RoundRectangle2D chip = new RoundRectangle2D.Float(x, 4, seg, h - 8, 8, 8);
                    g2.setColor(new Color(ACCENT.getRed(), ACCENT.getGreen(), ACCENT.getBlue(), 45));
                    g2.fill(chip);
                    g2.setColor(alpha(ACCENT, 170));
                    g2.draw(chip);
                } else if (i == hovered) {
                    g2.setColor(new Color(255, 255, 255, 12));
                    g2.fill(new RoundRectangle2D.Float(x, 4, seg, h - 8, 8, 8));
                }

                g2.setColor(on ? TEXT : TEXT_DIM);
                g2.drawString(labels[i], x + (seg - fm.stringWidth(labels[i])) / 2f,
                        h / 2f + (fm.getAscent() - fm.getDescent()) / 2f);
            }
            g2.dispose();
        }
    }

    /** A rounded text field with a magnifier in front of it and a placeholder when it is empty. */
    public static class SearchField extends JTextField {
        private final String placeholder;

        public SearchField(String placeholder) {
            this.placeholder = placeholder;
            setOpaque(false);
            setForeground(TEXT);
            setCaretColor(ACCENT);
            setFont(new Font("Segoe UI", Font.PLAIN, 13));
            setBorder(BorderFactory.createEmptyBorder(0, 36, 0, 14));
            setPreferredSize(new Dimension(10, 36));
            setMaximumSize(new Dimension(Integer.MAX_VALUE, 36));
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

            int w = getWidth(), h = getHeight();
            boolean focused = isFocusOwner();

            RoundRectangle2D shape = new RoundRectangle2D.Float(0.5f, 0.5f, w - 1, h - 1, 10, 10);
            g2.setColor(new Color(255, 255, 255, 10));
            g2.fill(shape);
            g2.setColor(focused ? alpha(ACCENT, 150) : new Color(255, 255, 255, 26));
            g2.draw(shape);

            float cy = h / 2f;
            g2.setColor(focused ? ACCENT : TEXT_DIM);
            g2.setStroke(new BasicStroke(1.5f));
            g2.draw(new Ellipse2D.Float(14, cy - 6, 9, 9));
            g2.draw(new Line2D.Float(22.5f, cy + 3f, 26f, cy + 6.5f));

            if (getText().isEmpty()) {
                g2.setFont(getFont());
                g2.setColor(TEXT_DIM);
                FontMetrics fm = g2.getFontMetrics();
                g2.drawString(placeholder, 36, cy + (fm.getAscent() - fm.getDescent()) / 2f);
            }
            g2.dispose();

            super.paintComponent(g);
        }
    }

    /**
     * The quiet button: no fill of its own, a hairline border, text that brightens on hover.
     *
     * <p>Sits next to a {@link GlowButton} without competing with it, which is the whole job - a screen
     * where every button is filled has no primary action.
     */
    public static class GhostButton extends JButton {
        private float hover;

        public GhostButton(String text) {
            super(text);
            setContentAreaFilled(false);
            setFocusPainted(false);
            setBorderPainted(false);
            setForeground(TEXT);
            setFont(new Font("Segoe UI", Font.BOLD, 13));
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setPreferredSize(new Dimension(150, 40));
            new Timer(16, e -> {
                float target = getModel().isRollover() ? 1f : 0f;
                if (Math.abs(target - hover) < 0.001f) return;
                hover += (target - hover) * 0.25f;
                repaint();
            }).start();
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

            int w = getWidth(), h = getHeight();
            boolean off = !isEnabled();
            RoundRectangle2D shape = new RoundRectangle2D.Float(0.5f, 0.5f, w - 1, h - 1, 10, 10);

            g2.setColor(new Color(255, 255, 255, (int) (7 + 13 * hover)));
            g2.fill(shape);
            g2.setColor(new Color(255, 255, 255, off ? 14 : (int) (26 + 34 * hover)));
            g2.draw(shape);

            g2.setFont(getFont());
            g2.setColor(off ? TEXT_DIM : (hover > 0.5f ? Color.WHITE : TEXT));
            FontMetrics fm = g2.getFontMetrics();
            g2.drawString(getText(), (w - fm.stringWidth(getText())) / 2f,
                    (h + fm.getAscent() - fm.getDescent()) / 2f);
            g2.dispose();
        }
    }
}
