import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.AttributeSet;
import javax.swing.text.BadLocationException;
import javax.swing.text.PlainDocument;
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.RoundRectangle2D;
import java.security.SecureRandom;

/**
 * The "new UID" window: you type the number you want and pick the rank, and that is the whole licence.
 *
 * <p>The rank lives here rather than in a menu you have to find afterwards, because a UID without one
 * is a UID that shows a blank corner in the client. Being asked for both at once is one step; generating
 * an id and then remembering to go back and rank it is two, and the second one gets forgotten.
 *
 * <p>There is no avatar field: the client finds your Discord account by itself and uses that picture.
 *
 * <p>Styled like the rest of the app - undecorated, rounded, the same starfield backdrop - because it is
 * the one screen you actually interact with, and a stock {@code JOptionPane} in the middle of it would
 * look like a different program.
 *
 * <p>It shows the finished UID underneath the field as you type, so there is never a question about what
 * is going to be saved. Enter confirms, Escape cancels, and LOSUJ fills the field for you when you do not
 * care which number you get.
 */
public final class UidPrompt extends JDialog {

    private static final SecureRandom RANDOM = new SecureRandom();
    /** Eight digits split into two blocks - the CLOUDAN-XXXX-XXXX shape. */
    private static final int DIGITS = 8;

    /** Everything the window decides about one licence. */
    public record Result(String uid, String rank) {
    }

    private final JTextField field = new JTextField();
    private final JLabel preview = new JLabel();
    private final JLabel hint = new JLabel(" ");
    private final java.util.List<RankChip> chips = new java.util.ArrayList<>();

    private String rank = Store.RANKS[0];

    /** The finished licence, or null when the window was cancelled. */
    private Result result;

    private UidPrompt(Window owner, String uid, String startRank) {
        super(owner, "Nowy UID", ModalityType.APPLICATION_MODAL);

        if (startRank != null && !startRank.isBlank()) rank = startRank;

        setUndecorated(true);
        setSize(560, 560);
        setLocationRelativeTo(owner);
        setBackground(new Color(0, 0, 0, 0));

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

        // Editing an existing card starts from its own digits, so changing only the rank does not mean
        // typing the number back in from memory.
        if (uid != null) field.setText(uid.replaceAll("\\D", ""));

        bindKeys();
        refresh();
    }

    /**
     * Opens the window and blocks until it is answered.
     *
     * @return the licence the user settled on, or null when they cancelled
     */
    public static Result ask(Window owner) {
        return ask(owner, null, null);
    }

    /** As {@link #ask(Window)}, but with the fields pre-filled from a licence that already exists. */
    public static Result ask(Window owner, String uid, String rank) {
        UidPrompt prompt = new UidPrompt(owner, uid, rank);
        prompt.setVisible(true);
        return prompt.result;
    }

    /* ------------------------------------------------------------------ */
    /* UI                                                                  */
    /* ------------------------------------------------------------------ */

    private JComponent titleBar() {
        JPanel bar = new JPanel(new BorderLayout()) {
            @Override
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
        bar.setPreferredSize(new Dimension(10, 42));

        JPanel west = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 8));
        west.setOpaque(false);
        west.add(new UI.LogoStar(20));
        JLabel title = new JLabel("NOWY UID");
        title.setForeground(UI.TEXT);
        title.setFont(new Font("Segoe UI", Font.BOLD, 14));
        west.add(title);
        bar.add(west, BorderLayout.WEST);

        JPanel east = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 7));
        east.setOpaque(false);
        east.add(new UI.WinButton("close", true, this::cancel));
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
        for (JComponent c : new JComponent[]{bar, west, title}) {
            c.addMouseListener(drag);
            c.addMouseMotionListener(drag);
        }
        return bar;
    }

    private JComponent body() {
        JPanel body = new JPanel(new BorderLayout(0, 12));
        body.setOpaque(false);
        body.setBorder(BorderFactory.createEmptyBorder(10, 26, 20, 26));

        JPanel stack = new JPanel();
        stack.setOpaque(false);
        stack.setLayout(new BoxLayout(stack, BoxLayout.Y_AXIS));
        stack.add(headline());
        stack.add(Box.createRigidArea(new Dimension(0, 12)));
        stack.add(inputCard());
        stack.add(Box.createRigidArea(new Dimension(0, 12)));
        stack.add(identityCard());
        stack.add(Box.createVerticalGlue());

        body.add(stack, BorderLayout.CENTER);
        body.add(buttons(), BorderLayout.SOUTH);
        return body;
    }

    /** The same eyebrow-and-headline opening as the main window, so the two read as one product. */
    private JComponent headline() {
        JPanel head = new JPanel();
        head.setOpaque(false);
        head.setLayout(new BoxLayout(head, BoxLayout.Y_AXIS));
        head.setAlignmentX(Component.LEFT_ALIGNMENT);

        head.add(row(new UI.Eyebrow("nowa licencja")));
        head.add(Box.createRigidArea(new Dimension(0, 4)));
        head.add(row(new UI.Hero("Twoje", "UID.", 30)));
        head.add(Box.createRigidArea(new Dimension(0, 2)));

        JLabel sub = new JLabel("Numer i ranga - jedno i drugie jedzie razem z jarem.");
        sub.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        sub.setForeground(UI.TEXT_DIM);
        head.add(row(sub));
        return head;
    }

    private static JComponent row(JComponent component) {
        JPanel row = new JPanel(new BorderLayout());
        row.setOpaque(false);
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.add(component, BorderLayout.WEST);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, component.getPreferredSize().height));
        return row;
    }

    private JComponent inputCard() {
        UI.GlassPanel card = new UI.GlassPanel(20);
        card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
        card.setBorder(BorderFactory.createEmptyBorder(18, 24, 18, 24));
        card.setAlignmentX(Component.LEFT_ALIGNMENT);
        card.setMaximumSize(new Dimension(Integer.MAX_VALUE, 200));

        JLabel caption = new JLabel("WPISZ SWOJ NUMER");
        caption.setFont(new Font("Segoe UI", Font.BOLD, 11));
        caption.setForeground(UI.TEXT_DIM);
        caption.setAlignmentX(Component.LEFT_ALIGNMENT);
        card.add(caption);
        card.add(Box.createRigidArea(new Dimension(0, 10)));

        // Digits only, capped at eight, enforced by the document itself - a field that simply refuses the
        // wrong key is kinder than one that accepts it and complains afterwards.
        field.setDocument(new DigitsDocument(DIGITS));
        field.setFont(new Font("Consolas", Font.BOLD, 30));
        field.setForeground(UI.TEXT);
        field.setCaretColor(UI.ACCENT);
        field.setOpaque(false);
        field.setBorder(BorderFactory.createMatteBorder(0, 0, 2, 0, new Color(255, 255, 255, 40)));
        field.setAlignmentX(Component.LEFT_ALIGNMENT);
        field.setMaximumSize(new Dimension(Integer.MAX_VALUE, 48));
        field.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { refresh(); }
            public void removeUpdate(DocumentEvent e) { refresh(); }
            public void changedUpdate(DocumentEvent e) { refresh(); }
        });
        card.add(field);
        card.add(Box.createRigidArea(new Dimension(0, 16)));

        JLabel previewCaption = new JLabel("TWOJ UID");
        previewCaption.setFont(new Font("Segoe UI", Font.BOLD, 11));
        previewCaption.setForeground(UI.TEXT_DIM);
        previewCaption.setAlignmentX(Component.LEFT_ALIGNMENT);
        card.add(previewCaption);
        card.add(Box.createRigidArea(new Dimension(0, 6)));

        preview.setFont(new Font("Consolas", Font.BOLD, 22));
        preview.setForeground(UI.ACCENT);
        preview.setAlignmentX(Component.LEFT_ALIGNMENT);
        card.add(preview);

        hint.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        hint.setForeground(UI.TEXT_DIM);
        hint.setAlignmentX(Component.LEFT_ALIGNMENT);
        hint.setBorder(BorderFactory.createEmptyBorder(10, 0, 0, 0));
        card.add(hint);

        return card;
    }

    /**
     * The rank, and a word about the avatar.
     *
     * <p>There is no avatar to pick here: the client works out your Discord account from the Discord
     * app running beside it and uses that picture. Asking for a PNG was the old way and it meant a
     * corner that stayed empty whenever somebody skipped the step.
     */
    private JComponent identityCard() {
        UI.GlassPanel card = new UI.GlassPanel(20);
        card.setLayout(new BorderLayout(18, 0));
        card.setBorder(BorderFactory.createEmptyBorder(16, 24, 16, 24));
        card.setAlignmentX(Component.LEFT_ALIGNMENT);
        card.setMaximumSize(new Dimension(Integer.MAX_VALUE, 150));

        JPanel left = new JPanel();
        left.setOpaque(false);
        left.setLayout(new BoxLayout(left, BoxLayout.Y_AXIS));

        JLabel caption = new JLabel("RANGA");
        caption.setFont(new Font("Segoe UI", Font.BOLD, 11));
        caption.setForeground(UI.TEXT_DIM);
        caption.setAlignmentX(Component.LEFT_ALIGNMENT);
        left.add(caption);
        left.add(Box.createRigidArea(new Dimension(0, 10)));

        JPanel row = new JPanel(new GridLayout(1, Store.RANKS.length, 8, 0));
        row.setOpaque(false);
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 36));
        for (String r : Store.RANKS) {
            RankChip chip = new RankChip(r);
            chips.add(chip);
            row.add(chip);
        }
        left.add(row);
        left.add(Box.createRigidArea(new Dimension(0, 10)));

        JLabel note = new JLabel("<html>Ranga jedzie razem z UID - klient pokazuje ja w rogu menu.<br>"
                + "Awatar Discorda klient wykrywa sam, nic nie trzeba podawac.</html>");
        note.setFont(new Font("Segoe UI", Font.PLAIN, 11));
        note.setForeground(UI.TEXT_DIM);
        note.setAlignmentX(Component.LEFT_ALIGNMENT);
        left.add(note);

        card.add(left, BorderLayout.CENTER);
        return card;
    }

    private JComponent buttons() {
        JPanel row = new JPanel(new GridLayout(1, 3, 10, 0));
        row.setOpaque(false);
        row.setPreferredSize(new Dimension(0, 42));

        UI.GhostButton random = new UI.GhostButton("LOSUJ");
        random.addActionListener(e -> {
            StringBuilder sb = new StringBuilder(DIGITS);
            for (int i = 0; i < DIGITS; i++) sb.append(RANDOM.nextInt(10));
            field.setText(sb.toString());
            field.requestFocusInWindow();
        });

        UI.GhostButton cancel = new UI.GhostButton("ANULUJ");
        cancel.addActionListener(e -> cancel());

        UI.GlowButton save = new UI.GlowButton("ZAPISZ", UI.ACCENT, UI.ACCENT2);
        save.addActionListener(e -> confirm());

        row.add(random);
        row.add(cancel);
        row.add(save);
        return row;
    }

    private void bindKeys() {
        JRootPane root = getRootPane();
        root.registerKeyboardAction(e -> cancel(),
                KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);
        root.registerKeyboardAction(e -> confirm(),
                KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);

        addWindowListener(new WindowAdapter() {
            @Override
            public void windowOpened(WindowEvent e) {
                field.requestFocusInWindow();
            }
        });
    }

    /* ------------------------------------------------------------------ */
    /* Behaviour                                                           */
    /* ------------------------------------------------------------------ */

    private void setRank(String value) {
        rank = value;
        for (RankChip chip : chips) chip.repaint();
    }

    private void refresh() {
        String digits = field.getText();

        if (digits.isEmpty()) {
            preview.setText("CLOUDAN-????-????");
            preview.setForeground(UI.TEXT_DIM);
            hint.setText("Wpisz od 1 do " + DIGITS + " cyfr.");
            return;
        }

        preview.setText(format(digits));
        preview.setForeground(UI.ACCENT);
        // Short numbers are padded rather than rejected: "7" is a perfectly good thing to want as a UID.
        hint.setText(digits.length() < DIGITS
                ? "Krotszy numer zostanie uzupelniony zerami z przodu."
                : " ");
    }

    private void confirm() {
        String digits = field.getText();
        if (digits.isEmpty()) {
            hint.setText("Najpierw wpisz numer.");
            return;
        }

        result = new Result(format(digits), rank);
        dispose();
    }

    private void cancel() {
        result = null;
        dispose();
    }

    /** Pads to eight digits and splits them into the two blocks of the UID format. */
    static String format(String digits) {
        String padded = "0".repeat(Math.max(0, DIGITS - digits.length())) + digits;
        return "CLOUDAN-" + padded.substring(0, 4) + "-" + padded.substring(4, 8);
    }

    /* ------------------------------------------------------------------ */
    /* Pieces                                                              */
    /* ------------------------------------------------------------------ */

    /** One rank, painted in its own colour, filled in when it is the chosen one. */
    private final class RankChip extends JComponent {
        private final String value;
        private boolean hover;

        RankChip(String value) {
            this.value = value;
            setPreferredSize(new Dimension(90, 34));
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            addMouseListener(new MouseAdapter() {
                @Override
                public void mouseEntered(MouseEvent e) { hover = true; repaint(); }

                @Override
                public void mouseExited(MouseEvent e) { hover = false; repaint(); }

                @Override
                public void mousePressed(MouseEvent e) { setRank(value); }
            });
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

            int w = getWidth(), h = getHeight();
            boolean on = value.equals(rank);
            Color colour = UI.rankColor(value);

            RoundRectangle2D shape = new RoundRectangle2D.Float(0.5f, 0.5f, w - 1, h - 1, 10, 10);
            g2.setColor(on
                    ? new Color(colour.getRed(), colour.getGreen(), colour.getBlue(), 60)
                    : new Color(255, 255, 255, hover ? 16 : 8));
            g2.fill(shape);

            g2.setColor(on ? colour : new Color(255, 255, 255, hover ? 60 : 30));
            g2.setStroke(new BasicStroke(on ? 1.6f : 1f));
            g2.draw(shape);

            g2.setFont(new Font("Segoe UI", Font.BOLD, 11));
            g2.setColor(on ? colour : UI.TEXT_DIM);
            FontMetrics fm = g2.getFontMetrics();
            g2.drawString(value, (w - fm.stringWidth(value)) / 2, (h + fm.getAscent() - fm.getDescent()) / 2);
            g2.dispose();
        }
    }

    /** A document that only ever holds digits, and never more than {@code max} of them. */
    private static final class DigitsDocument extends PlainDocument {
        private final int max;

        DigitsDocument(int max) {
            this.max = max;
        }

        @Override
        public void insertString(int offset, String text, AttributeSet attrs) throws BadLocationException {
            if (text == null) return;

            StringBuilder kept = new StringBuilder(text.length());
            for (char c : text.toCharArray())
                if (Character.isDigit(c)) kept.append(c);

            int room = max - getLength();
            if (room <= 0 || kept.isEmpty()) return;
            if (kept.length() > room) kept.setLength(room);

            super.insertString(offset, kept.toString(), attrs);
        }
    }
}
