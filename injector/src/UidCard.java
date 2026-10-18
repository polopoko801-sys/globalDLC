import javax.swing.*;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.awt.event.*;
import java.awt.geom.RoundRectangle2D;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * The card window inside an exported UID jar.
 *
 * <p>Whoever you hand the jar to double-clicks it and this is what they see: their UID, big, with a copy
 * button. Nothing else - no network, no files written, no check-in anywhere. The UID is baked into the jar
 * as a resource at export time, so the card works on a machine that has never seen the app.
 *
 * <p>The status is baked in the same way. A ban applied after the jar was handed out cannot reach it - the
 * card has no way to ask anybody - so re-export the jar when you want the new status to travel with it.
 */
public final class UidCard extends JDialog {

    private static final String RESOURCE = "/uid.txt";

    private final String uid;
    private final boolean banned;

    private UidCard(String uid, boolean banned) {
        super((Frame) null, "Gombal UID", false);
        this.uid = uid;
        this.banned = banned;

        setUndecorated(true);
        setSize(520, 300);
        setLocationRelativeTo(null);
        setBackground(new Color(0, 0, 0, 0));
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);

        UI.BackgroundPanel root = new UI.BackgroundPanel();
        root.setLayout(new BorderLayout());
        setContentPane(root);
        root.add(titleBar(), BorderLayout.NORTH);
        root.add(body(), BorderLayout.CENTER);

        addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                setShape(new RoundRectangle2D.Double(0, 0, getWidth(), getHeight(), 26, 26));
            }
        });

        getRootPane().registerKeyboardAction(e -> close(),
                KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);
    }

    private JComponent titleBar() {
        JPanel bar = new JPanel(new BorderLayout());
        bar.setOpaque(false);
        bar.setPreferredSize(new Dimension(10, 42));

        JPanel west = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 8));
        west.setOpaque(false);
        west.add(new UI.LogoStar(20));
        JLabel brand = new JLabel("GOMBAL");
        brand.setForeground(UI.TEXT);
        brand.setFont(new Font("Segoe UI", Font.BOLD, 14));
        west.add(brand);
        bar.add(west, BorderLayout.WEST);

        JPanel east = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 7));
        east.setOpaque(false);
        east.add(new UI.WinButton("close", true, this::close));
        bar.add(east, BorderLayout.EAST);

        MouseAdapter drag = new MouseAdapter() {
            Point start, winLoc;

            @Override
            public void mousePressed(MouseEvent e) {
                start = e.getLocationOnScreen();
                winLoc = getLocation();
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                Point now = e.getLocationOnScreen();
                setLocation(winLoc.x + now.x - start.x, winLoc.y + now.y - start.y);
            }
        };
        for (JComponent c : new JComponent[]{bar, west, brand}) {
            c.addMouseListener(drag);
            c.addMouseMotionListener(drag);
        }
        return bar;
    }

    private JComponent body() {
        JPanel body = new JPanel(new BorderLayout(0, 14));
        body.setOpaque(false);
        body.setBorder(BorderFactory.createEmptyBorder(14, 26, 22, 26));

        UI.GlassPanel card = new UI.GlassPanel(20);
        card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
        card.setBorder(BorderFactory.createEmptyBorder(24, 26, 24, 26));

        JLabel caption = new JLabel("TWOJ UID");
        caption.setFont(new Font("Segoe UI", Font.BOLD, 11));
        caption.setForeground(UI.TEXT_DIM);
        caption.setAlignmentX(Component.LEFT_ALIGNMENT);
        card.add(caption);
        card.add(Box.createRigidArea(new Dimension(0, 10)));

        JLabel value = new JLabel(uid);
        value.setFont(new Font("Consolas", Font.BOLD, 30));
        value.setForeground(banned ? UI.DANGER : UI.TEXT);
        value.setAlignmentX(Component.LEFT_ALIGNMENT);
        card.add(value);
        card.add(Box.createRigidArea(new Dimension(0, 14)));

        JLabel status = new JLabel(banned ? "ZABANOWANY" : "AKTYWNY");
        status.setFont(new Font("Segoe UI", Font.BOLD, 13));
        status.setForeground(banned ? UI.DANGER : UI.OK);
        status.setAlignmentX(Component.LEFT_ALIGNMENT);
        card.add(status);

        body.add(card, BorderLayout.CENTER);

        UI.GlowButton copy = new UI.GlowButton("KOPIUJ UID", UI.ACCENT, UI.ACCENT2);
        copy.addActionListener(e ->
                Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(uid), null));
        copy.setPreferredSize(new Dimension(0, 42));
        body.add(copy, BorderLayout.SOUTH);

        return body;
    }

    private void close() {
        dispose();
        System.exit(0);
    }

    public static void main(String[] args) {
        String uid = "CLOUDAN-????-????";
        boolean banned = false;

        try (InputStream in = UidCard.class.getResourceAsStream(RESOURCE)) {
            if (in != null) {
                String[] lines = new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\\R");
                if (lines.length > 0 && !lines[0].isBlank()) uid = lines[0].trim();
                banned = lines.length > 1 && lines[1].trim().equalsIgnoreCase("banned");
            }
        } catch (Exception ignored) {
            // A card with no resource still opens and says so, rather than failing to start at all.
        }

        final String shown = uid;
        final boolean isBanned = banned;
        SwingUtilities.invokeLater(() -> new UidCard(shown, isBanned).setVisible(true));
    }
}
