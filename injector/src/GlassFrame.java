import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.util.List;

/** Bazowe okno bez dekoracji: zaokraglone rogi, tlo z gwiazdami, pasek tytulu. */
public abstract class GlassFrame extends JFrame {

    protected final UI.BackgroundPanel rootPanel = new UI.BackgroundPanel();

    protected GlassFrame(String windowTitle, String brand, int w, int h) {
        setUndecorated(true);
        setTitle(windowTitle);
        setSize(w, h);
        setMinimumSize(new Dimension(w, h));
        setLocationRelativeTo(null);
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setBackground(new Color(0, 0, 0, 0));
        setIconImages(starIcons());

        rootPanel.setLayout(new BorderLayout());
        setContentPane(rootPanel);
        rootPanel.add(buildTitleBar(brand), BorderLayout.NORTH);

        addComponentListener(new ComponentAdapter() {
            public void componentResized(ComponentEvent e) {
                setShape(new RoundRectangle2D.Double(0, 0, getWidth(), getHeight(), 28, 28));
            }
        });
    }

    protected void setBody(JComponent body) { rootPanel.add(body, BorderLayout.CENTER); }

    /** Ikona aplikacji (pasek zadań / Alt+Tab): fioletowa gwiazda na przezroczystym tle, kilka rozmiarów. */
    static List<Image> starIcons() {
        java.util.ArrayList<Image> imgs = new java.util.ArrayList<>();
        for (int size : new int[]{16, 24, 32, 48, 64, 128, 256}) {
            BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g2 = img.createGraphics();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            double cx = size / 2.0, cy = size / 2.0, rO = size * 0.44, rI = rO * 0.42;
            Shape star = UI.StarSpinner.star(cx, cy, rO, rI, 5, 0);
            // miekka poswiata
            g2.setColor(new Color(168, 85, 247, 70));
            g2.setStroke(new BasicStroke(Math.max(1f, size * 0.12f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g2.draw(star);
            // wypelnienie gradientem
            g2.setPaint(new GradientPaint((float) (cx - rO), (float) (cy - rO), UI.ACCENT,
                    (float) (cx + rO), (float) (cy + rO), UI.ACCENT2));
            g2.fill(star);
            // jasny obrys
            g2.setColor(new Color(255, 255, 255, 200));
            g2.setStroke(new BasicStroke(Math.max(1f, size * 0.03f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g2.draw(star);
            g2.dispose();
            imgs.add(img);
        }
        return imgs;
    }

    private JComponent buildTitleBar(String brand) {
        JPanel bar = new JPanel(new BorderLayout()) {
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setColor(new Color(255, 255, 255, 8));
                g2.fillRect(0, 0, getWidth(), getHeight());
                g2.setColor(new Color(255, 255, 255, 14));
                g2.fillRect(0, getHeight() - 1, getWidth(), 1);
                g2.dispose();
            }
        };
        bar.setOpaque(false);
        bar.setPreferredSize(new Dimension(10, 44));

        JPanel west = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 9));
        west.setOpaque(false);
        JLabel b = new JLabel(brand == null ? "" : brand);
        if (brand != null && !brand.isBlank()) {   // blank brand => header lives in the body
            west.add(new UI.LogoStar(22));
            b.setForeground(UI.TEXT);
            b.setFont(new Font("Segoe UI", Font.BOLD, 14));
            west.add(b);
        }
        bar.add(west, BorderLayout.WEST);

        JPanel btns = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 8));
        btns.setOpaque(false);
        btns.add(new UI.WinButton("min", false, () -> setState(Frame.ICONIFIED)));
        btns.add(new UI.WinButton("close", true, () -> System.exit(0)));
        bar.add(btns, BorderLayout.EAST);

        MouseAdapter drag = new MouseAdapter() {
            Point start, winLoc;
            public void mousePressed(MouseEvent e) { start = e.getLocationOnScreen(); winLoc = getLocation(); }
            public void mouseDragged(MouseEvent e) {
                Point now = e.getLocationOnScreen();
                setLocation(winLoc.x + now.x - start.x, winLoc.y + now.y - start.y);
            }
        };
        for (JComponent c : new JComponent[]{bar, west, b}) {
            c.addMouseListener(drag); c.addMouseMotionListener(drag);
        }
        return bar;
    }
}
