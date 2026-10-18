import javax.swing.*;
import javax.swing.Timer;
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.*;
import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.util.List;

/**
 * ThunderHack Injector - produkt dawany kupujacemu.
 *
 * Logowanie po UID (licencja generowana w panelu admina). Po zalogowaniu
 * otwiera sie GUI z kreconca gwiazda i paskiem ladowania na dole, a w LEWYM
 * DOLNYM ROGU widac aktualny build i range uzytkownika (przelaczane klawiszem P).
 */
public class ThunderInjector extends GlassFrame {

    static final String CLIENT_NAME = "thunderhack-1.7.jar";   // nazwa pliku klienta w mods

    private final CardLayout cards = new CardLayout();
    private final JPanel deck = new JPanel(cards);
    private final LoginPanel login;
    private final InjectScreen injectScreen;
    private Store.License current;
    volatile File overrideJar;   // opcjonalny .jar wybrany przez "Browse" (zamiast wbudowanego)

    public ThunderInjector() {
        super("GOMBAL Injector", "", 1000, 640);
        deck.setOpaque(false);
        login = new LoginPanel(this::onLogged);
        injectScreen = new InjectScreen();
        deck.add(login, "login");
        deck.add(injectScreen, "inject");
        cards.show(deck, "login");
        setBody(deck);
    }

    private void onLogged(Store.License lic) {
        current = lic;
        injectScreen.bind(lic);
        cards.show(deck, "inject");
        injectScreen.onShown();
    }

    /** Auto-logowanie UID-em podanym z linii polecen (plik .vbs z pulpitu). */
    public void autoLogin(String uid) {
        Store.License lic = Store.find(uid);
        if (lic != null && !lic.banned) {
            onLogged(lic);
        } else {
            login.field.setText(uid);
            login.fail(lic != null && lic.banned ? "Ten UID zostal zbanowany" : "Niepoprawny UID");
        }
    }

    // ====================== Ekran logowania (UID) ======================
    static class LoginPanel extends JPanel {
        final JTextField field = new JTextField();
        final JLabel error = new JLabel(" ");
        final java.util.function.Consumer<Store.License> onOk;
        int shake = 0; Timer shakeTimer;

        LoginPanel(java.util.function.Consumer<Store.License> onOk) {
            this.onOk = onOk;
            setOpaque(false);
            setLayout(new GridBagLayout());

            UI.RoundPanel card = new UI.RoundPanel(22, UI.PANEL);
            card.setLayout(new GridBagLayout());
            card.setBorder(BorderFactory.createEmptyBorder(32, 44, 32, 44));
            card.setPreferredSize(new Dimension(440, 340));

            GridBagConstraints g = new GridBagConstraints();
            g.gridx = 0; g.gridy = 0; g.fill = GridBagConstraints.HORIZONTAL; g.weightx = 1;

            JLabel title = UI.gradLabel("ThunderHack", 30);
            title.setHorizontalAlignment(SwingConstants.CENTER);
            card.add(title, g);

            g.gridy++;
            JLabel sub = new JLabel("RECODE  -  AUTORYZACJA LICENCJI", SwingConstants.CENTER);
            sub.setForeground(UI.TEXT_DIM);
            sub.setFont(new Font("Segoe UI", Font.BOLD, 11));
            card.add(sub, g);

            g.gridy++; g.insets = new Insets(24, 0, 0, 0);
            JLabel lbl = new JLabel("TWOJ UID");
            lbl.setForeground(UI.TEXT_DIM);
            lbl.setFont(new Font("Segoe UI", Font.BOLD, 11));
            card.add(lbl, g);

            g.gridy++; g.insets = new Insets(8, 0, 0, 0);
            styleField(field);
            field.addActionListener(e -> tryLogin());
            card.add(field, g);

            g.gridy++; g.insets = new Insets(6, 0, 0, 0);
            error.setForeground(UI.DANGER);
            error.setFont(new Font("Segoe UI", Font.PLAIN, 11));
            error.setHorizontalAlignment(SwingConstants.CENTER);
            card.add(error, g);

            g.gridy++; g.insets = new Insets(16, 0, 0, 0);
            UI.GlowButton unlock = new UI.GlowButton("ZALOGUJ", UI.ACCENT, UI.ACCENT2);
            unlock.addActionListener(e -> tryLogin());
            card.add(unlock, g);

            add(card);
            SwingUtilities.invokeLater(field::requestFocusInWindow);
        }

        void tryLogin() {
            String uid = field.getText().trim();
            if (uid.isEmpty()) { fail("Wpisz swoj UID"); return; }
            Store.License lic = Store.find(uid);
            if (lic == null) { fail("Niepoprawny lub nieaktywny UID"); return; }
            if (lic.banned) { fail("Ten UID zostal zbanowany"); return; }
            error.setForeground(UI.OK);
            error.setText("Zalogowano jako " + lic.rank);
            Timer t = new Timer(300, e -> onOk.accept(lic));
            t.setRepeats(false); t.start();
        }

        void fail(String msg) {
            error.setForeground(UI.DANGER);
            error.setText(msg);
            doShake();
        }

        void doShake() {
            if (shakeTimer != null && shakeTimer.isRunning()) return;
            final int[] n = {0};
            shakeTimer = new Timer(18, null);
            shakeTimer.addActionListener(e -> {
                n[0]++;
                shake = (int) (Math.sin(n[0] * 0.9) * (10 - n[0] * 0.5));
                if (n[0] > 18) { shake = 0; shakeTimer.stop(); }
                repaint();
            });
            shakeTimer.start();
        }

        public void paint(Graphics g) {
            g.translate(shake, 0);
            super.paint(g);
            g.translate(-shake, 0);
        }
    }

    static void styleField(JTextField f) {
        f.setForeground(UI.TEXT);
        f.setCaretColor(UI.ACCENT);
        f.setBackground(new Color(10, 14, 26));
        f.setHorizontalAlignment(JTextField.CENTER);
        f.setFont(new Font("Consolas", Font.BOLD, 18));
        f.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(40, 224, 212, 120), 1, true),
                BorderFactory.createEmptyBorder(10, 12, 10, 12)));
    }

    // ====================== Ekran injectora (styl "GOMBAL INJECTOR") ======================
    class InjectScreen extends JPanel {
        final JLabel status = new JLabel(" ");
        final JLabel detail = new JLabel(" ");
        final UI.ProgressBar bar = new UI.ProgressBar();
        final BigButton injectBtn = new BigButton("INJECT MOD", Icons.ROCKET);
        final ClientPicker picker = new ClientPicker();
        final StatusPill pill = new StatusPill();
        final Field gameDir = new Field(Icons.FOLDER, "");
        boolean running = false;

        InjectScreen() {
            setOpaque(false);
            setLayout(new BorderLayout());
            setBorder(BorderFactory.createEmptyBorder(4, 22, 16, 22));

            add(new BrandHeader(), BorderLayout.NORTH);

            JPanel body = new JPanel(new BorderLayout(16, 0));
            body.setOpaque(false);
            body.add(buildSidebar(), BorderLayout.WEST);
            body.add(buildContent(), BorderLayout.CENTER);
            add(body, BorderLayout.CENTER);

            picker.onSelect = t -> gameDir.setText(t.modsDir().toString());
            if (picker.selected != null) gameDir.setText(picker.selected.modsDir().toString());
        }

        // ---- sidebar: nawigacja + motyw + wersja ----
        private JComponent buildSidebar() {
            UI.RoundPanel side = new UI.RoundPanel(18, UI.PANEL);
            side.setLayout(new BoxLayout(side, BoxLayout.Y_AXIS));
            side.setBorder(BorderFactory.createEmptyBorder(16, 14, 16, 14));
            side.setPreferredSize(new Dimension(228, 10));
            side.setMaximumSize(new Dimension(228, Integer.MAX_VALUE));

            side.add(new NavItem(Icons.HOME, "Injector", true));

            side.add(Box.createVerticalGlue());

            side.add(new ThemeToggle());
            side.add(Box.createVerticalStrut(14));

            JLabel ver = new JLabel("Version 1.0.0");
            ver.setForeground(UI.TEXT_DIM);
            ver.setFont(new Font("Segoe UI", Font.BOLD, 12));
            ver.setAlignmentX(LEFT_ALIGNMENT);
            side.add(ver);
            JLabel by = new JLabel("<html>by <font color='#a855f7'>Gombal</font></html>");
            by.setForeground(UI.TEXT_DIM);
            by.setFont(new Font("Segoe UI", Font.BOLD, 12));
            by.setAlignmentX(LEFT_ALIGNMENT);
            side.add(by);
            return side;
        }

        // ---- content: sekcje jak na screenie ----
        private JComponent buildContent() {
            UI.RoundPanel card = new UI.RoundPanel(18, UI.CARD);
            card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
            card.setBorder(BorderFactory.createEmptyBorder(22, 26, 18, 26));

            card.add(section("Mod File (.jar)"));
            card.add(Box.createVerticalStrut(8));
            Field modFile = new Field(Icons.CUBE, CLIENT_NAME + "  (wbudowany)");
            card.add(row(modFile, new MiniButton("Browse", Icons.FOLDER, true, this::browseMod)));

            card.add(Box.createVerticalStrut(16));
            card.add(section("Launcher"));
            card.add(Box.createVerticalStrut(8));
            picker.setAlignmentX(LEFT_ALIGNMENT);
            card.add(picker);

            card.add(Box.createVerticalStrut(16));
            card.add(section("Game Directory"));
            card.add(Box.createVerticalStrut(8));
            card.add(row(gameDir, new MiniButton("Browse", Icons.FOLDER, false, this::browseDir)));

            card.add(Box.createVerticalStrut(16));
            card.add(buildStatusRow());

            card.add(Box.createVerticalStrut(14));
            injectBtn.setAlignmentX(LEFT_ALIGNMENT);
            injectBtn.addActionListener(e -> startInjection());
            card.add(injectBtn);

            // dyskretny pasek + tekst postepu (widoczny podczas injekcji)
            card.add(Box.createVerticalStrut(8));
            bar.setAlignmentX(LEFT_ALIGNMENT);
            bar.setMaximumSize(new Dimension(Integer.MAX_VALUE, 20));
            card.add(bar);
            status.setForeground(UI.TEXT_DIM);
            status.setFont(new Font("Segoe UI", Font.PLAIN, 11));
            status.setAlignmentX(LEFT_ALIGNMENT);
            card.add(status);

            card.add(Box.createVerticalGlue());
            card.add(new Footer());
            return card;
        }

        private JComponent buildStatusRow() {
            UI.RoundPanel row = new UI.RoundPanel(12, new Color(15, 13, 22, 220));
            row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
            row.setBorder(BorderFactory.createEmptyBorder(10, 16, 10, 16));
            row.setAlignmentX(0f);
            row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 44));
            JLabel s = new JLabel("Status");
            s.setForeground(UI.TEXT);
            s.setFont(new Font("Segoe UI", Font.BOLD, 14));
            row.add(s);
            row.add(Box.createHorizontalStrut(14));
            row.add(pill);
            row.add(Box.createHorizontalGlue());
            return row;
        }

        private JLabel section(String text) {
            JLabel l = new JLabel(text);
            l.setForeground(UI.TEXT);
            l.setFont(new Font("Segoe UI", Font.BOLD, 15));
            l.setAlignmentX(LEFT_ALIGNMENT);
            l.setMaximumSize(new Dimension(Integer.MAX_VALUE, 22));
            return l;
        }

        /** Wiersz: [pole rosnie][odstep][przycisk] */
        private JComponent row(JComponent field, JComponent button) {
            JPanel p = new JPanel(new BorderLayout(10, 0));
            p.setOpaque(false);
            p.setAlignmentX(LEFT_ALIGNMENT);
            p.setMaximumSize(new Dimension(Integer.MAX_VALUE, 50));
            p.add(field, BorderLayout.CENTER);
            p.add(button, BorderLayout.EAST);
            return p;
        }

        private void browseMod() {
            JFileChooser fc = new JFileChooser();
            fc.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("JAR", "jar"));
            if (fc.showOpenDialog(ThunderInjector.this) == JFileChooser.APPROVE_OPTION)
                overrideJar = fc.getSelectedFile();
        }
        private void browseDir() {
            JFileChooser fc = new JFileChooser();
            fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            if (fc.showOpenDialog(ThunderInjector.this) == JFileChooser.APPROVE_OPTION)
                gameDir.setText(fc.getSelectedFile().getAbsolutePath());
        }
        private void openLogs() {
            try {
                if (picker.selected != null) java.awt.Desktop.getDesktop().open(picker.selected.modsDir().toFile());
            } catch (Exception ignored) {}
        }

        void bind(Store.License lic) { }
        void onShown() { picker.playIntro(); }

        void startInjection() {
            if (running) return;
            running = true;
            injectBtn.setEnabled(false);
            pill.set("INJEKCJA...", UI.ACCENT);
            bar.reset();
            picker.playIntro();
            new InjectionWorker(this).execute();
        }
    }

    // ====================== Komponenty UI (styl GOMBAL) ======================

    /** Wektorowe ikony rysowane liniami (bez emoji/PNG). */
    static final class Icons {
        static final int HOME = 0, DOC = 1, GEAR = 2, INFO = 3, FOLDER = 4, CUBE = 5, ROCKET = 6, SHIELD = 7;
        static void draw(Graphics2D g2, int kind, int x, int y, int s, Color c) {
            g2.setColor(c);
            g2.setStroke(new BasicStroke(1.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            int cx = x + s / 2, cy = y + s / 2;
            switch (kind) {
                case HOME -> {
                    g2.drawLine(x + 2, cy, cx, y + 2);
                    g2.drawLine(cx, y + 2, x + s - 2, cy);
                    g2.draw(new RoundRectangle2D.Float(x + 4, cy, s - 8, s / 2f - 1, 3, 3));
                }
                case DOC -> {
                    g2.draw(new RoundRectangle2D.Float(x + 4, y + 2, s - 8, s - 4, 4, 4));
                    g2.drawLine(x + 8, y + 7, x + s - 8, y + 7);
                    g2.drawLine(x + 8, cy, x + s - 8, cy);
                    g2.drawLine(x + 8, y + s - 7, x + s - 10, y + s - 7);
                }
                case GEAR -> {
                    g2.draw(new Ellipse2D.Float(cx - 5, cy - 5, 10, 10));
                    for (int a = 0; a < 360; a += 45) {
                        double r = Math.toRadians(a);
                        g2.drawLine(cx + (int) (Math.cos(r) * 6), cy + (int) (Math.sin(r) * 6),
                                cx + (int) (Math.cos(r) * 8), cy + (int) (Math.sin(r) * 8));
                    }
                }
                case INFO -> {
                    g2.draw(new Ellipse2D.Float(x + 3, y + 3, s - 6, s - 6));
                    g2.drawLine(cx, cy - 1, cx, cy + 4);
                    g2.fill(new Ellipse2D.Float(cx - 1f, cy - 5f, 2.4f, 2.4f));
                }
                case FOLDER -> {
                    g2.draw(new RoundRectangle2D.Float(x + 3, y + 5, s - 6, s - 9, 3, 3));
                    g2.drawLine(x + 3, y + 5, x + 3 + (s - 6) / 3, y + 5);
                    g2.drawLine(x + 3 + (s - 6) / 3, y + 5, x + 3 + (s - 6) / 3 + 3, y + 8);
                }
                case CUBE -> {
                    int r = s / 3;
                    g2.drawLine(cx, cy - r, cx + r, cy - r / 2);
                    g2.drawLine(cx + r, cy - r / 2, cx + r, cy + r / 2);
                    g2.drawLine(cx + r, cy + r / 2, cx, cy + r);
                    g2.drawLine(cx, cy + r, cx - r, cy + r / 2);
                    g2.drawLine(cx - r, cy + r / 2, cx - r, cy - r / 2);
                    g2.drawLine(cx - r, cy - r / 2, cx, cy - r);
                    g2.drawLine(cx - r, cy - r / 2, cx, cy);
                    g2.drawLine(cx + r, cy - r / 2, cx, cy);
                    g2.drawLine(cx, cy, cx, cy + r);
                }
                case ROCKET -> {
                    g2.draw(new java.awt.geom.Arc2D.Float(cx - 5, y + 2, 10, s - 6, 0, 360, java.awt.geom.Arc2D.OPEN));
                    g2.fill(new Ellipse2D.Float(cx - 2f, cy - 4f, 4, 4));
                    g2.drawLine(cx - 5, cy + 3, cx - 8, cy + 8);
                    g2.drawLine(cx + 5, cy + 3, cx + 8, cy + 8);
                }
                case SHIELD -> {
                    Path2D p = new Path2D.Float();
                    p.moveTo(cx, y + 2); p.lineTo(x + s - 3, y + 5); p.lineTo(x + s - 5, cy + 3);
                    p.lineTo(cx, y + s - 2); p.lineTo(x + 5, cy + 3); p.lineTo(x + 3, y + 5); p.closePath();
                    g2.draw(p);
                }
            }
        }
    }

    /** Naglowek: kostka 3D + "GOMBAL" (fiolet) + "INJECTOR" (bialy) + podtytul. */
    static class BrandHeader extends JComponent {
        BrandHeader() { setPreferredSize(new Dimension(10, 74)); setOpaque(false); }
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            int cx = 26, cy = 30, r = 18;
            Color acc = UI.ACCENT, accL = UI.lighten(acc, 45), accD = acc.darker();
            // glow
            g2.setPaint(new RadialGradientPaint(new Point2D.Float(cx, cy), 34, new float[]{0f, 1f},
                    new Color[]{UI.alpha(acc, 120), UI.alpha(acc, 0)}));
            g2.fillRect(0, 0, 70, 64);
            // isometric cube
            Path2D top = poly(cx, cy - r, cx + r, cy - r / 2, cx, cy, cx - r, cy - r / 2);
            Path2D left = poly(cx - r, cy - r / 2, cx, cy, cx, cy + r, cx - r, cy + r / 2);
            Path2D right = poly(cx + r, cy - r / 2, cx, cy, cx, cy + r, cx + r, cy + r / 2);
            g2.setColor(accL); g2.fill(top);
            g2.setColor(acc);  g2.fill(left);
            g2.setColor(accD); g2.fill(right);
            g2.setColor(new Color(255, 255, 255, 60));
            g2.setStroke(new BasicStroke(1.2f)); g2.draw(top);

            int tx = 58, ty = 26;
            g2.setFont(new Font("Segoe UI", Font.BOLD, 26));
            FontMetrics fm = g2.getFontMetrics();
            g2.setPaint(new GradientPaint(tx, 0, acc, tx + 130, 0, accL));
            g2.drawString("GOMBAL", tx, ty);
            int gx = tx + fm.stringWidth("GOMBAL") + 8;
            g2.setColor(UI.TEXT);
            g2.drawString("INJECTOR", gx, ty);
            g2.setColor(UI.TEXT_DIM);
            g2.setFont(new Font("Segoe UI", Font.PLAIN, 12));
            g2.drawString("Inject mods into Modrinth or Dawn easily.", tx, ty + 18);
            g2.dispose();
        }
        static Path2D poly(int... xy) {
            Path2D p = new Path2D.Float();
            p.moveTo(xy[0], xy[1]);
            for (int i = 2; i < xy.length; i += 2) p.lineTo(xy[i], xy[i + 1]);
            p.closePath();
            return p;
        }
    }

    /** Pozycja nawigacji w sidebarze (ikona + tekst, podswietlenie gdy wybrana). */
    static class NavItem extends JComponent {
        final int icon; final String text; final boolean selected; float hov;
        NavItem(int icon, String text, boolean selected) {
            this.icon = icon; this.text = text; this.selected = selected;
            setAlignmentX(0f);
            setPreferredSize(new Dimension(200, 46));
            setMaximumSize(new Dimension(Integer.MAX_VALUE, 46));
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            addMouseListener(new MouseAdapter() {
                public void mouseEntered(MouseEvent e) { hov = 1; repaint(); }
                public void mouseExited(MouseEvent e) { hov = 0; repaint(); }
            });
        }
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            int w = getWidth(), h = getHeight();
            if (selected) {
                g2.setColor(UI.alpha(UI.ACCENT, 40));
                g2.fill(new RoundRectangle2D.Float(0, 0, w, h, 12, 12));
                g2.setColor(UI.alpha(UI.ACCENT, 150));
                g2.setStroke(new BasicStroke(1f));
                g2.draw(new RoundRectangle2D.Float(0.5f, 0.5f, w - 1, h - 1, 12, 12));
            } else if (hov > 0) {
                g2.setColor(new Color(255, 255, 255, 16));
                g2.fill(new RoundRectangle2D.Float(0, 0, w, h, 12, 12));
            }
            Color c = selected ? UI.ACCENT : UI.TEXT_DIM;
            Icons.draw(g2, icon, 14, h / 2 - 9, 18, c);
            g2.setColor(selected ? UI.TEXT : UI.TEXT_DIM);
            g2.setFont(new Font("Segoe UI", Font.BOLD, 14));
            g2.drawString(text, 44, h / 2 + 5);
            g2.dispose();
        }
    }

    /** Karta przelacznika motywu (dekoracyjna). */
    static class ThemeToggle extends JComponent {
        boolean on = UI.purpleTheme;
        ThemeToggle() {
            setAlignmentX(0f);
            setPreferredSize(new Dimension(200, 52));
            setMaximumSize(new Dimension(Integer.MAX_VALUE, 52));
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            addMouseListener(new MouseAdapter() {
                public void mousePressed(MouseEvent e) {
                    on = !on;
                    UI.setTheme(on);
                    Window win = SwingUtilities.getWindowAncestor(ThemeToggle.this);
                    if (win != null) win.repaint();
                }
            });
        }
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            int w = getWidth(), h = getHeight();
            g2.setColor(new Color(255, 255, 255, 12));
            g2.fill(new RoundRectangle2D.Float(0, 0, w, h, 12, 12));
            // moon
            g2.setColor(UI.TEXT_DIM);
            g2.fill(new Ellipse2D.Float(14, h / 2f - 7, 14, 14));
            g2.setColor(new Color(255, 255, 255, 12));
            g2.fill(new Ellipse2D.Float(18, h / 2f - 9, 14, 14));
            g2.setColor(UI.TEXT);
            g2.setFont(new Font("Segoe UI", Font.BOLD, 12));
            g2.drawString("Dark / Purple", 38, h / 2 - 1);
            g2.setColor(UI.TEXT_DIM);
            g2.drawString("Theme", 38, h / 2 + 13);
            // switch
            int sw = 34, sh = 18, sx = w - sw - 12, sy = h / 2 - sh / 2;
            g2.setColor(on ? UI.ACCENT : new Color(90, 90, 105));
            g2.fill(new RoundRectangle2D.Float(sx, sy, sw, sh, sh, sh));
            g2.setColor(Color.WHITE);
            g2.fill(new Ellipse2D.Float(on ? sx + sw - sh + 2 : sx + 2, sy + 2, sh - 4, sh - 4));
            g2.dispose();
        }
    }

    /** Zaokraglone ciemne pole z ikona i tekstem. */
    static class Field extends JComponent {
        final int icon; String text;
        Field(int icon, String text) {
            this.icon = icon; this.text = text;
            setAlignmentX(0f);
            setPreferredSize(new Dimension(280, 48));
            setMaximumSize(new Dimension(Integer.MAX_VALUE, 48));
        }
        void setText(String t) { text = t; repaint(); }
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            int w = getWidth(), h = getHeight();
            g2.setColor(new Color(13, 11, 20, 230));
            g2.fill(new RoundRectangle2D.Float(0, 0, w, h, 12, 12));
            g2.setColor(new Color(255, 255, 255, 22));
            g2.setStroke(new BasicStroke(1f));
            g2.draw(new RoundRectangle2D.Float(0.5f, 0.5f, w - 1, h - 1, 12, 12));
            Icons.draw(g2, icon, 12, h / 2 - 9, 18, UI.TEXT_DIM);
            g2.setColor(text != null && text.contains("Select") ? UI.TEXT_DIM : UI.TEXT);
            g2.setFont(new Font("Segoe UI", Font.PLAIN, 13));
            String t = text == null ? "" : text;
            FontMetrics fm = g2.getFontMetrics();
            while (fm.stringWidth(t) > w - 52 && t.length() > 4) t = t.substring(1);
            g2.drawString(t, 40, h / 2 + 5);
            g2.dispose();
        }
    }

    /** Maly przycisk z ikona (fioletowy lub ciemny). */
    static class MiniButton extends JButton {
        final int icon; final boolean filled; float hov;
        MiniButton(String text, int icon, boolean filled, Runnable action) {
            super(text); this.icon = icon; this.filled = filled;
            setContentAreaFilled(false); setFocusPainted(false); setBorderPainted(false);
            setPreferredSize(new Dimension(120, 48));
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            addActionListener(e -> action.run());
            new Timer(16, e -> { hov += ((getModel().isRollover() ? 1f : 0f) - hov) * 0.25f; repaint(); }).start();
        }
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            int w = getWidth(), h = getHeight();
            if (filled) {
                g2.setPaint(new GradientPaint(0, 0, UI.ACCENT, w, h, UI.ACCENT2));
                g2.fill(new RoundRectangle2D.Float(0, 0, w, h, 12, 12));
                g2.setColor(new Color(255, 255, 255, (int) (30 * hov)));
                g2.fill(new RoundRectangle2D.Float(0, 0, w, h, 12, 12));
            } else {
                g2.setColor(new Color(255, 255, 255, (int) (14 + 14 * hov)));
                g2.fill(new RoundRectangle2D.Float(0, 0, w, h, 12, 12));
                g2.setColor(new Color(255, 255, 255, 30));
                g2.draw(new RoundRectangle2D.Float(0.5f, 0.5f, w - 1, h - 1, 12, 12));
            }
            Color fg = filled ? Color.WHITE : UI.TEXT;
            g2.setFont(new Font("Segoe UI", Font.BOLD, 13));
            FontMetrics fm = g2.getFontMetrics();
            int tw = fm.stringWidth(getText());
            int total = 18 + 8 + tw, sx = (w - total) / 2;
            Icons.draw(g2, icon, sx, h / 2 - 9, 18, fg);
            g2.setColor(fg);
            g2.drawString(getText(), sx + 26, h / 2 + 5);
            g2.dispose();
        }
    }

    /** Wielki fioletowy przycisk INJECT MOD (z poswiata i ikona rakiety). */
    static class BigButton extends JButton {
        final int icon; float hov, pulse;
        BigButton(String text, int icon) {
            super(text); this.icon = icon;
            setContentAreaFilled(false); setFocusPainted(false); setBorderPainted(false);
            setPreferredSize(new Dimension(400, 56));
            setMaximumSize(new Dimension(Integer.MAX_VALUE, 56));
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            new Timer(16, e -> { hov += ((getModel().isRollover() ? 1f : 0f) - hov) * 0.2f; pulse += 0.06f; repaint(); }).start();
        }
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            int w = getWidth(), h = getHeight();
            boolean off = !isEnabled();
            if (!off) {
                float p = 0.5f + 0.5f * (float) Math.sin(pulse);
                int a = (int) (60 + 40 * p + 70 * hov);
                g2.setColor(UI.alpha(UI.ACCENT, Math.min(180, a)));
                g2.fill(new RoundRectangle2D.Float(-6, -6, w + 12, h + 12, 22, 22));
            }
            Color a = off ? new Color(60, 58, 72) : UI.ACCENT2;
            Color b = off ? new Color(52, 50, 64) : UI.lighten(UI.ACCENT, 40);
            g2.setPaint(new GradientPaint(0, 0, a, w, 0, b));
            g2.fill(new RoundRectangle2D.Float(0, 0, w, h, 14, 14));
            RoundRectangle2D rr = new RoundRectangle2D.Float(0, 0, w, h, 14, 14);
            Shape oc = g2.getClip(); g2.setClip(rr);
            g2.setPaint(new GradientPaint(0, 0, new Color(255, 255, 255, off ? 20 : 70), 0, h / 2f, new Color(255, 255, 255, 0)));
            g2.fillRect(0, 0, w, h / 2);
            g2.setClip(oc);
            Color fg = off ? UI.TEXT_DIM : Color.WHITE;
            g2.setFont(new Font("Segoe UI", Font.BOLD, 16));
            FontMetrics fm = g2.getFontMetrics();
            int tw = fm.stringWidth(getText());
            int total = 20 + 12 + tw, sx = (w - total) / 2;
            Icons.draw(g2, icon, sx, h / 2 - 10, 20, fg);
            g2.setColor(fg);
            g2.drawString(getText(), sx + 32, h / 2 + 6);
            g2.dispose();
        }
    }

    /** Przycisk z obrysem (OPEN LOGS). */
    static class OutlineButton extends JButton {
        final int icon; float hov;
        OutlineButton(String text, int icon, Runnable action) {
            super(text); this.icon = icon;
            setContentAreaFilled(false); setFocusPainted(false); setBorderPainted(false);
            setPreferredSize(new Dimension(400, 46));
            setMaximumSize(new Dimension(Integer.MAX_VALUE, 46));
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            addActionListener(e -> action.run());
            new Timer(16, e -> { hov += ((getModel().isRollover() ? 1f : 0f) - hov) * 0.25f; repaint(); }).start();
        }
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            int w = getWidth(), h = getHeight();
            g2.setColor(new Color(168, 85, 247, (int) (14 + 22 * hov)));
            g2.fill(new RoundRectangle2D.Float(0, 0, w, h, 12, 12));
            g2.setColor(new Color(168, 85, 247, (int) (120 + 80 * hov)));
            g2.setStroke(new BasicStroke(1.2f));
            g2.draw(new RoundRectangle2D.Float(0.6f, 0.6f, w - 1.2f, h - 1.2f, 12, 12));
            g2.setFont(new Font("Segoe UI", Font.BOLD, 13));
            FontMetrics fm = g2.getFontMetrics();
            int tw = fm.stringWidth(getText());
            int total = 18 + 10 + tw, sx = (w - total) / 2;
            Icons.draw(g2, icon, sx, h / 2 - 9, 18, UI.TEXT);
            g2.setColor(UI.TEXT);
            g2.drawString(getText(), sx + 28, h / 2 + 5);
            g2.dispose();
        }
    }

    /** Stopka: Safe/Secure/No malware + haslo po prawej. */
    static class Footer extends JComponent {
        Footer() { setAlignmentX(0f); setPreferredSize(new Dimension(10, 26)); setMaximumSize(new Dimension(Integer.MAX_VALUE, 26)); }
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            int h = getHeight(), w = getWidth();
            Icons.draw(g2, Icons.SHIELD, 0, h / 2 - 9, 18, UI.TEXT_DIM);
            g2.setColor(UI.TEXT_DIM);
            g2.setFont(new Font("Segoe UI", Font.BOLD, 12));
            g2.drawString("Safe  •  Secure  •  No malware", 24, h / 2 + 4);
            String right = "Made for Minecraft. Made for you.";
            int rw = g2.getFontMetrics().stringWidth(right);
            g2.drawString(right, w - rw, h / 2 + 4);
            g2.dispose();
        }
    }

    /** Mala pigulka statusu z pulsujaca kropka (READY / INJEKCJA / GOTOWE / BLAD). */
    static class StatusPill extends JComponent {
        String text = "Ready";
        Color col = UI.OK;
        float t = 0;
        StatusPill() {
            setPreferredSize(new Dimension(180, 26));
            new Timer(40, e -> { t += 0.09f; repaint(); }).start();
        }
        void set(String s, Color c) { text = s; col = c; repaint(); }
        public Dimension getMaximumSize() { return new Dimension(240, 26); }
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g2.setFont(new Font("Segoe UI", Font.BOLD, 11));
            int tw = g2.getFontMetrics().stringWidth(text);
            int h = 24, w = Math.min(getWidth(), 30 + tw + 12);
            g2.setColor(new Color(col.getRed(), col.getGreen(), col.getBlue(), 26));
            g2.fill(new RoundRectangle2D.Float(0, 1, w, h, h, h));
            g2.setStroke(new BasicStroke(1f));
            g2.setColor(new Color(col.getRed(), col.getGreen(), col.getBlue(), 95));
            g2.draw(new RoundRectangle2D.Float(0.5f, 1.5f, w - 1, h - 1, h, h));
            float p = 0.5f + 0.5f * (float) Math.sin(t);
            int cy = 1 + h / 2;
            g2.setColor(new Color(col.getRed(), col.getGreen(), col.getBlue(), (int) (70 + 90 * p)));
            g2.fill(new Ellipse2D.Float(14 - 4, cy - 4, 8, 8));
            g2.setColor(new Color(col.getRed(), col.getGreen(), col.getBlue(), (int) (30 + 40 * p)));
            g2.fill(new Ellipse2D.Float(14 - 7, cy - 7, 14, 14));
            g2.setColor(UI.TEXT);
            g2.drawString(text, 28, cy + 4);
            g2.dispose();
        }
    }

    // ====================== Worker injekcji ======================
    class InjectionWorker extends SwingWorker<Boolean, Object[]> {
        final InjectScreen ui;
        boolean ok = false; String finalMsg = "";
        InjectionWorker(InjectScreen ui) { this.ui = ui; }

        protected Boolean doInBackground() {
            try {
                publish(new Object[]{"Inicjalizacja injectora...", "", 6});
                Thread.sleep(420);

                ClientTarget target = ui.picker.selected;
                if (target == null) target = ClientTarget.VANILLA;
                Path modsDir = target.modsDir();
                publish(new Object[]{"Wybrany client: " + target.name, modsDir.toString(), 22});
                Files.createDirectories(modsDir);
                Path dest = modsDir.resolve(CLIENT_NAME);
                Thread.sleep(420);

                // 0) Plik wybrany recznie przez "Browse" ma pierwszenstwo
                if (overrideJar != null && overrideJar.isFile()) {
                    publish(new Object[]{"Injekcja wybranego pliku...", "-> " + dest, 64});
                    Files.copy(overrideJar.toPath(), dest, StandardCopyOption.REPLACE_EXISTING);
                    Thread.sleep(420);
                    publish(new Object[]{"Weryfikacja...", dest.toString(), 92});
                    Thread.sleep(300);
                    ok = Files.exists(dest) && Files.size(dest) > 0;
                    finalMsg = ok ? "Injekcja OK! Uruchom Minecraft (Fabric)." : "Injekcja nie powiodla sie.";
                    publish(new Object[]{ok ? "Gotowe!" : "Blad kopiowania", dest.toString(), 100});
                    return ok;
                }

                // 1) Klient WBUDOWANY w injector (zintegrowany) -> wypakuj z zasobow
                java.io.InputStream emb = ThunderInjector.class.getResourceAsStream("/" + CLIENT_NAME);
                if (emb == null) emb = ThunderInjector.class.getResourceAsStream("/client.jar");
                if (emb != null) {
                    publish(new Object[]{"Wypakowywanie wbudowanego klienta...", "-> " + dest, 64});
                    try (java.io.InputStream in = emb) {
                        Files.copy(in, dest, StandardCopyOption.REPLACE_EXISTING);
                    }
                    Thread.sleep(520);
                } else {
                    // 2) Fallback: klient z build/libs (lub gradle build)
                    publish(new Object[]{"Szukanie klienta w projekcie...", "build/libs/*.jar", 40});
                    File root = findProjectRoot();
                    File jar = root == null ? null : findClientJar(root);
                    Thread.sleep(450);
                    if (jar == null && root != null) {
                        publish(new Object[]{"Brak .jar - buduje klienta...", "gradlew build", 50});
                        if (buildClient(root)) jar = findClientJar(root);
                    }
                    if (jar == null) {
                        finalMsg = "Brak wbudowanego klienta i brak build/libs. Przebuduj injector (Build.bat).";
                        publish(new Object[]{"Blad: brak klienta", "-", 100});
                        return false;
                    }
                    publish(new Object[]{"Injekcja: " + jar.getName(), "-> " + dest, 72});
                    Files.copy(jar.toPath(), dest, StandardCopyOption.REPLACE_EXISTING);
                    Thread.sleep(520);
                }

                publish(new Object[]{"Weryfikacja...", dest.toString(), 92});
                Thread.sleep(380);
                ok = Files.exists(dest) && Files.size(dest) > 0;
                finalMsg = ok
                        ? "Injekcja OK! Uruchom Minecraft (Fabric) i W GRZE wcisnij P, zeby otworzyc ClickGUI."
                        : "Injekcja nie powiodla sie podczas kopiowania.";
                publish(new Object[]{ok ? "Gotowe!" : "Blad kopiowania", dest.toString(), 100});
                return ok;
            } catch (Exception ex) {
                finalMsg = "Blad: " + ex.getMessage();
                publish(new Object[]{"Wystapil blad", String.valueOf(ex.getMessage()), 100});
                return false;
            }
        }
        protected void process(List<Object[]> chunks) {
            Object[] last = chunks.get(chunks.size() - 1);
            ui.status.setText((String) last[0]);
            ui.detail.setText((String) last[1]);
            ui.bar.setTarget((Integer) last[2]);
        }
        protected void done() {
            ui.running = false;
            ui.injectBtn.setEnabled(true);
            ui.status.setForeground(ok ? UI.OK : UI.DANGER);
            ui.status.setText(finalMsg);
            ui.bar.setSuccess(ok);
            ui.pill.set(ok ? "GOTOWE" : "BLAD", ok ? UI.OK : UI.DANGER);
            ui.revalidate(); ui.repaint();
        }
    }

    // ====================== Logika injekcji ======================
    static File findProjectRoot() {
        File dir = Store.baseDir();
        for (int i = 0; i < 6 && dir != null; i++) {
            if (new File(dir, "gradle.properties").exists() && new File(dir, "build.gradle").exists())
                return dir;
            dir = dir.getParentFile();
        }
        return null;
    }
    static File findClientJar(File root) {
        File libs = new File(root, "build/libs");
        if (!libs.isDirectory()) return null;
        File[] files = libs.listFiles((d, n) ->
                n.endsWith(".jar") && !n.contains("-sources") && !n.contains("-dev"));
        if (files == null) return null;
        File best = null;
        for (File f : files) {
            if (!f.getName().toLowerCase().startsWith("thunderhack")) continue;
            if (best == null || f.lastModified() > best.lastModified()) best = f;
        }
        return best;
    }
    static boolean buildClient(File root) {
        try {
            boolean win = System.getProperty("os.name").toLowerCase().contains("win");
            ProcessBuilder pb = win
                    ? new ProcessBuilder("cmd", "/c", "gradlew.bat", "build", "-x", "test")
                    : new ProcessBuilder("./gradlew", "build", "-x", "test");
            pb.directory(root); pb.redirectErrorStream(true);
            Process p = pb.start();
            p.getInputStream().readAllBytes();
            return p.waitFor() == 0;
        } catch (IOException | InterruptedException e) { return false; }
    }
    static Path minecraftModsDir() {
        String appdata = System.getenv("APPDATA");
        Path base = (appdata != null && !appdata.isBlank())
                ? Paths.get(appdata, ".minecraft")
                : Paths.get(System.getProperty("user.home"), ".minecraft");
        return base.resolve("mods");
    }

    /** Clienci do wyboru w GUI (foldery modow dla 1.21.11). */
    enum ClientTarget {
        OGULNIEGA("ogulniega",   "1.21.11-sodium", ".ogulniega/profile/mods/1.21.11-sodium/preinstalled"),
        MODRINTH ("Modrinth",    "Fabric 1.21.11", "ModrinthApp/profiles/Fabric 1.21.11/mods"),
        DAWN     ("Dawn Client", "1.21.11",         ".dawn/profiles/feather-default--1-21-11/.minecraft/mods"),
        VANILLA  ("Vanilla",     ".minecraft",      ".minecraft/mods");

        final String name, sub, rel;
        ClientTarget(String name, String sub, String rel) { this.name = name; this.sub = sub; this.rel = rel; }

        static Path roaming() {
            String appdata = System.getenv("APPDATA");
            return (appdata != null && !appdata.isBlank())
                    ? Paths.get(appdata)
                    : Paths.get(System.getProperty("user.home"), "AppData", "Roaming");
        }
        Path modsDir() { return roaming().resolve(rel); }
        boolean installed() {
            Path m = modsDir();
            return Files.exists(m) || (m.getParent() != null && Files.exists(m.getParent()));
        }
        public String toString() { return name; }
    }

    /** Klikalny kafelek clienta (zamiast rozwijanej listy). */
    static class ClientCard extends JComponent {
        final ClientTarget target;
        boolean selected, hover;
        float selAnim, hovAnim, intro;   // wygladzane 0..1 do animacji

        ClientCard(ClientTarget t, Runnable onClick) {
            this.target = t;
            setPreferredSize(new Dimension(370, 76));
            setMaximumSize(new Dimension(600, 96));
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            addMouseListener(new MouseAdapter() {
                public void mouseEntered(MouseEvent e) { hover = true; }
                public void mouseExited(MouseEvent e) { hover = false; }
                public void mousePressed(MouseEvent e) { onClick.run(); }
            });
            new Timer(16, e -> {
                selAnim += ((selected ? 1f : 0f) - selAnim) * 0.22f;
                hovAnim += ((hover ? 1f : 0f) - hovAnim) * 0.22f;
                intro += (1f - intro) * 0.14f;
                repaint();
            }).start();
        }
        void setSelected(boolean s) { selected = s; }
        void resetIntro() { intro = 0f; }

        static Color mix(Color a, Color b, float t) {
            t = Math.max(0f, Math.min(1f, t));
            return new Color(
                    (int) (a.getRed() + (b.getRed() - a.getRed()) * t),
                    (int) (a.getGreen() + (b.getGreen() - a.getGreen()) * t),
                    (int) (a.getBlue() + (b.getBlue() - a.getBlue()) * t),
                    (int) (a.getAlpha() + (b.getAlpha() - a.getAlpha()) * t));
        }

        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            int w = getWidth(), h = getHeight();

            // wejscie: slide z prawej + fade (ease-out)
            float ez = 1f - (1f - intro) * (1f - intro);
            g2.translate((1f - ez) * 34f, 0);
            g2.setComposite(java.awt.AlphaComposite.getInstance(java.awt.AlphaComposite.SRC_OVER, Math.max(0.05f, ez)));

            float act = Math.max(selAnim, hovAnim * 0.6f);
            Color acc = UI.ACCENT;

            // poswiata gdy zaznaczony
            if (selAnim > 0.02f) {
                g2.setColor(new Color(acc.getRed(), acc.getGreen(), acc.getBlue(), (int) (42 * selAnim)));
                g2.fill(new RoundRectangle2D.Float(-3, -3, w + 6, h + 6, 20, 20));
            }

            g2.setColor(mix(new Color(15, 20, 37, 220), new Color(26, 40, 66, 248), act));
            g2.fill(new RoundRectangle2D.Float(0, 0, w, h, 16, 16));

            g2.setStroke(new BasicStroke(1f + selAnim * 1.1f));
            g2.setColor(mix(new Color(255, 255, 255, 26), new Color(acc.getRed(), acc.getGreen(), acc.getBlue(), 220), act));
            g2.draw(new RoundRectangle2D.Float(1f, 1f, w - 2f, h - 2f, 16, 16));

            // pasek akcentu po lewej (rosnie przy zaznaczeniu)
            g2.setColor(new Color(acc.getRed(), acc.getGreen(), acc.getBlue(), (int) (255 * selAnim)));
            g2.fill(new RoundRectangle2D.Float(0, 12, 4, Math.max(0, h - 24), 4, 4));

            // logo clienta: prawdziwy PNG (logos/<nazwa>.png) albo zapasowa ikona
            int isz = 38, ix = 18, iy = (h - isz) / 2;
            Image logo = logoFor(target);
            if (logo != null) {
                Shape oldClip = g2.getClip();
                g2.setClip(new RoundRectangle2D.Float(ix, iy, isz, isz, 11, 11));
                g2.drawImage(logo, ix, iy, isz, isz, null);
                g2.setClip(oldClip);
            } else {
                drawClientIcon(g2, target, ix, iy, isz);
            }

            g2.setColor(UI.TEXT);
            g2.setFont(new Font("Segoe UI", Font.BOLD, 17));
            g2.drawString(target.name, 66, h / 2 - 3);
            g2.setColor(UI.TEXT_DIM);
            g2.setFont(new Font("Segoe UI", Font.PLAIN, 12));
            g2.drawString(target.sub, 66, h / 2 + 14);

            // radio-kropka po prawej (skaluje sie)
            int dx = w - 30, dy = h / 2;
            g2.setStroke(new BasicStroke(1.7f));
            g2.setColor(mix(UI.TEXT_DIM, acc, selAnim));
            g2.draw(new Ellipse2D.Float(dx - 9, dy - 9, 18, 18));
            if (selAnim > 0.02f) {
                g2.setColor(acc);
                float r = 9f * selAnim;
                g2.fill(new Ellipse2D.Float(dx - r / 2, dy - r / 2, r, r));
            }
            g2.dispose();
        }

        // cache logo: probuje /logos/<nazwa>.png z jara, potem plik injector/logos/<nazwa>.png obok jara
        static final java.util.Map<ClientTarget, Image> LOGO_CACHE = new java.util.HashMap<>();
        static Image logoFor(ClientTarget t) {
            if (LOGO_CACHE.containsKey(t)) return LOGO_CACHE.get(t);
            Image img = loadLogo(t);
            LOGO_CACHE.put(t, img);
            return img;
        }
        static Image loadLogo(ClientTarget t) {
            String key = t.name().toLowerCase();
            try (java.io.InputStream in = ThunderInjector.class.getResourceAsStream("/logos/" + key + ".png")) {
                if (in != null) return javax.imageio.ImageIO.read(in);
            } catch (Exception ignored) {}
            try {
                File f = new File(Store.baseDir(), "logos/" + key + ".png");
                if (f.isFile()) return javax.imageio.ImageIO.read(f);
            } catch (Exception ignored) {}
            return null;
        }

        /** Zapasowe logo clienta w barwach marki (gdy brak PNG - rysowane wektorowo). */
        static void drawClientIcon(Graphics2D g2, ClientTarget t, int x, int y, int s) {
            int cx = x + s / 2, cy = y + s / 2;
            switch (t) {
                case MODRINTH -> {
                    g2.setColor(new Color(0x1B, 0xD9, 0x6A));                 // Modrinth green
                    g2.fill(new RoundRectangle2D.Float(x, y, s, s, 9, 9));
                    g2.setColor(new Color(6, 40, 22));
                    g2.setStroke(new BasicStroke(2.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                    int r = s / 4;
                    g2.draw(new java.awt.geom.Arc2D.Float(cx - r, cy - r, r * 2, r * 2, 35, 250, java.awt.geom.Arc2D.OPEN));
                    g2.fill(new Ellipse2D.Float(cx + r - 3, cy - 5, 5, 5));
                }
                case DAWN -> {
                    g2.setColor(new Color(18, 22, 40));
                    g2.fill(new RoundRectangle2D.Float(x, y, s, s, 9, 9));
                    int by = y + s - 8;                                       // linia horyzontu
                    g2.setPaint(new GradientPaint(x, by - 12, new Color(255, 205, 100), x, by, new Color(255, 120, 60)));
                    g2.fill(new java.awt.geom.Arc2D.Float(cx - 9, by - 9, 18, 18, 0, 180, java.awt.geom.Arc2D.PIE));
                    g2.setColor(new Color(255, 185, 90));
                    g2.setStroke(new BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                    for (int a = -60; a <= 60; a += 30) {
                        double rad = Math.toRadians(a - 90);
                        g2.drawLine(cx + (int) (Math.cos(rad) * 11), by + (int) (Math.sin(rad) * 11),
                                cx + (int) (Math.cos(rad) * 14), by + (int) (Math.sin(rad) * 14));
                    }
                }
                case OGULNIEGA -> {
                    g2.setPaint(new GradientPaint(x, y, UI.ACCENT2, x + s, y + s, new Color(0x9B, 0x59, 0xF6)));
                    g2.fill(new RoundRectangle2D.Float(x, y, s, s, 9, 9));
                    g2.setColor(Color.WHITE);
                    g2.fill(UI.StarSpinner.star(cx, cy, s * 0.28, s * 0.12, 5, 0));
                }
                default -> {                                                  // VANILLA - blok trawy
                    g2.setColor(new Color(0x8B, 0x5A, 0x2B));
                    g2.fill(new RoundRectangle2D.Float(x, y, s, s, 9, 9));
                    Shape clip = new RoundRectangle2D.Float(x, y, s, s, 9, 9);
                    g2.setClip(clip);
                    g2.setColor(new Color(0x5A, 0xB8, 0x3A));
                    g2.fillRect(x, y, s, s / 2);
                    g2.setClip(null);
                }
            }
        }
    }

    /** Lista kafelkow clientow z pojedynczym wyborem (zamiast JComboBox). */
    static class ClientPicker extends JPanel {
        final java.util.List<ClientCard> cards = new java.util.ArrayList<>();
        ClientTarget selected;
        java.util.function.Consumer<ClientTarget> onSelect;
        ClientPicker() {
            setOpaque(false);
            setAlignmentX(0f);
            java.util.List<ClientTarget> list = new java.util.ArrayList<>();
            for (ClientTarget t : ClientTarget.values()) if (t.installed()) list.add(t);
            if (list.isEmpty()) for (ClientTarget t : ClientTarget.values()) list.add(t);
            int rows = (list.size() + 1) / 2;
            setLayout(new GridLayout(rows, 2, 12, 12));
            setMaximumSize(new Dimension(Integer.MAX_VALUE, rows * 90));
            for (ClientTarget t : list) {
                ClientCard card = new ClientCard(t, () -> select(t));
                cards.add(card);
                add(card);
            }
            select(list.get(0));
        }
        void select(ClientTarget t) {
            selected = t;
            for (ClientCard c : cards) c.setSelected(c.target == t);
            if (onSelect != null) onSelect.accept(t);
        }
        void playIntro() { for (ClientCard c : cards) c.resetIntro(); }
    }

    public static void main(String[] args) {
        try { UIManager.setLookAndFeel(UIManager.getCrossPlatformLookAndFeelClassName()); }
        catch (Exception ignored) {}
        String presetUid = (args.length > 0 && !args[0].isBlank()) ? args[0].trim() : null;
        SwingUtilities.invokeLater(() -> {
            ThunderInjector app = new ThunderInjector();
            app.setVisible(true);
            if (presetUid != null) app.autoLogin(presetUid);
        });
    }
}
