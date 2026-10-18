import javax.swing.*;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.io.File;
import java.io.InputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.function.Consumer;

/**
 * The AKTUALIZACJE page: write what was added, press WYDAJ AKTUALIZACJE, and every client connected to this
 * app downloads the new build the next time it starts and shows the changelog. Also shows the address players
 * is stamped into every jar it hands out (players set nothing up) and whether clients are talking to the app.
 */
public final class UpdatePage extends JPanel {
    private final Consumer<String> say;
    private final JTextArea notes = new JTextArea();
    /** Who gets it: empty = everybody, or UIDs separated by commas (1, 5 or CLOUDAN-0000-0005). */
    private final JTextField target = new JTextField();
    private final JLabel current = new JLabel(" ");
    private final JLabel server = new JLabel(" ");
    private final JLabel source = new JLabel(" ");

    /** Every UID as {uid, rank, banned 0/1} - the table "Dla kogo" is picked from. */
    private final java.util.function.Supplier<java.util.List<String[]>> users;
    private final DefaultListModel<String[]> people = new DefaultListModel<>();
    private final JList<String[]> peopleList = new JList<>(people);
    /** Picked UIDs (normalised), kept in sync with the text field. */
    private final java.util.LinkedHashSet<String> picked = new java.util.LinkedHashSet<>();

    public UpdatePage(Consumer<String> say, java.util.function.Supplier<java.util.List<String[]>> users) {
        this.say = say;
        this.users = users;
        setOpaque(false);
        setLayout(new BorderLayout(14, 0));

        UI.GlassPanel left = new UI.GlassPanel(20, 18);
        left.setLayout(new BorderLayout(0, 10));
        left.setBorder(BorderFactory.createEmptyBorder(14, 14, 14, 14));
        JPanel top = new JPanel(new BorderLayout(0, 6));
        top.setOpaque(false);
        JPanel head = new JPanel(new BorderLayout(8, 0));
        head.setOpaque(false);
        head.add(caption("DLA KOGO  ·  kliknij graczy w tabelce (nikt zaznaczony = wszyscy)"), BorderLayout.CENTER);
        UI.GhostButton everyone = new UI.GhostButton("Wszyscy");
        everyone.addActionListener(e -> {
            picked.clear();
            syncField();
        });
        head.add(everyone, BorderLayout.EAST);
        top.add(head, BorderLayout.NORTH);
        target.setFont(new Font("Consolas", Font.PLAIN, 13));
        target.setForeground(UI.TEXT);
        target.setCaretColor(UI.TEXT);
        target.setBackground(new Color(20, 18, 28));
        target.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(UI.BORDER), BorderFactory.createEmptyBorder(6, 8, 6, 8)));
        // typing in the field still works; the table follows it
        target.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            public void insertUpdate(javax.swing.event.DocumentEvent e) { fromField(); }
            public void removeUpdate(javax.swing.event.DocumentEvent e) { fromField(); }
            public void changedUpdate(javax.swing.event.DocumentEvent e) { fromField(); }
        });

        peopleList.setOpaque(false);
        peopleList.setBackground(new Color(20, 18, 28));
        peopleList.setCellRenderer(new PersonRenderer());
        peopleList.setFixedCellHeight(30);
        peopleList.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mousePressed(java.awt.event.MouseEvent e) {
                int i = peopleList.locationToIndex(e.getPoint());
                if (i < 0 || i >= people.size()) return;
                String n = LinkServer.norm(people.get(i)[0]);
                if (!picked.remove(n)) picked.add(n);
                syncField();
                peopleList.repaint();
            }
        });
        JScrollPane table = new JScrollPane(peopleList);
        table.setBorder(BorderFactory.createLineBorder(UI.BORDER));
        table.getViewport().setBackground(new Color(20, 18, 28));
        table.setPreferredSize(new Dimension(10, 190));
        StatusPage.styleScroll(table);

        JPanel mid = new JPanel(new BorderLayout(0, 6));
        mid.setOpaque(false);
        mid.add(target, BorderLayout.NORTH);
        mid.add(table, BorderLayout.CENTER);
        mid.add(caption("WIADOMOŚĆ  ·  co dodano - zobaczą to gracze przy starcie gry i w menu"), BorderLayout.SOUTH);
        top.add(mid, BorderLayout.CENTER);
        left.add(top, BorderLayout.NORTH);
        notes.setFont(new Font("Segoe UI", Font.PLAIN, 13));
        notes.setForeground(UI.TEXT);
        notes.setCaretColor(UI.TEXT);
        notes.setBackground(new Color(20, 18, 28));
        notes.setLineWrap(true);
        notes.setWrapStyleWord(true);
        notes.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        notes.setText("- \n- \n- ");
        JScrollPane sp = new JScrollPane(notes);
        sp.setBorder(BorderFactory.createLineBorder(UI.BORDER));
        StatusPage.styleScroll(sp);
        left.add(sp, BorderLayout.CENTER);
        UI.GlowButton publish = new UI.GlowButton("WYDAJ AKTUALIZACJE", UI.ACCENT, UI.ACCENT2);
        publish.setPreferredSize(new Dimension(10, 44));
        publish.addActionListener(e -> publish());
        left.add(publish, BorderLayout.SOUTH);
        add(left, BorderLayout.CENTER);

        UI.GlassPanel right = new UI.GlassPanel(20, 18);
        right.setLayout(new BoxLayout(right, BoxLayout.Y_AXIS));
        right.setBorder(BorderFactory.createEmptyBorder(14, 14, 14, 14));
        right.setPreferredSize(new Dimension(330, 10));
        right.add(caption("WYDANIE"));
        right.add(Box.createRigidArea(new Dimension(0, 6)));
        style(current, true);
        right.add(current);
        style(source, false);
        right.add(source);
        right.add(Box.createRigidArea(new Dimension(0, 16)));
        right.add(caption("SERWER APLIKACJI"));
        right.add(Box.createRigidArea(new Dimension(0, 6)));
        style(server, false);
        right.add(server);
        right.add(Box.createRigidArea(new Dimension(0, 10)));
        UI.GhostButton copy = new UI.GhostButton("Zmień adres wbudowany w moda");
        copy.setAlignmentX(Component.LEFT_ALIGNMENT);
        copy.addActionListener(e -> {
            String a = JOptionPane.showInputDialog(this, "Adres, z którym łączą się gry graczy (wbudowywany w każdy wydany jar):", address());
            if (a != null && !a.isBlank()) {
                LinkServer.setPublicAddress(a.trim());
                say.accept("Adres zmieniony - trafi do następnego wydania i eksportu karty.");
                refresh();
            }
        });
        right.add(copy);
        right.add(Box.createRigidArea(new Dimension(0, 10)));
        JLabel help = new JLabel("<html><body style='width:280px'>Ten adres jest wbudowany w każdy wydany jar i kartę UID - gracze nic nie ustawiają. "
                + "Gra łączy się przy starcie i co 20 s (aktualizacje, bany, avatar). Aplikacja musi być włączona, "
                + "a port " + LinkServer.port() + " przekierowany na routerze. Bany i wydania robisz też w grze: moduł GlobalPanel.</body></html>");
        style(help, false);
        right.add(help);
        add(right, BorderLayout.EAST);

        new javax.swing.Timer(2000, e -> refresh()).start();
        refresh();
    }

    private boolean syncing;

    /** Writes the picked UIDs into the field (short form, e.g. "1, 5"). */
    private void syncField() {
        syncing = true;
        target.setText(String.join(", ", picked));
        syncing = false;
    }

    /** Reads the field back into the picks when it is typed in. */
    private void fromField() {
        if (syncing) return;
        picked.clear();
        for (String s : target.getText().split("[,;\\s]+")) if (!s.isBlank()) picked.add(LinkServer.norm(s));
        peopleList.repaint();
    }

    private void reloadPeople() {
        java.util.List<String[]> now = users.get();
        boolean same = now.size() == people.size();
        for (int i = 0; same && i < now.size(); i++)
            same = java.util.Arrays.equals(now.get(i), people.get(i));
        if (same) return;
        people.clear();
        for (String[] u : now) people.addElement(u);
    }

    /** One row of the "Dla kogo" table: tick, avatar, UID, rank chip, Discord name. */
    private final class PersonRenderer extends JComponent implements ListCellRenderer<String[]> {
        private String[] row;
        private boolean hover;

        @Override
        public Component getListCellRendererComponent(JList<? extends String[]> l, String[] value, int index, boolean sel, boolean focus) {
            row = value;
            hover = sel;
            return this;
        }

        @Override
        protected void paintComponent(Graphics g) {
            if (row == null) return;
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            int w = getWidth(), h = getHeight();
            boolean on = picked.contains(LinkServer.norm(row[0]));
            boolean banned = "1".equals(row[2]);
            if (on) {
                g2.setColor(new Color(UI.ACCENT.getRed(), UI.ACCENT.getGreen(), UI.ACCENT.getBlue(), 55));
                g2.fill(new java.awt.geom.RoundRectangle2D.Float(2, 2, w - 4, h - 4, 8, 8));
            }
            // tick box
            int x = 10;
            g2.setColor(on ? UI.ACCENT : new Color(255, 255, 255, 60));
            java.awt.geom.RoundRectangle2D box = new java.awt.geom.RoundRectangle2D.Float(x, h / 2f - 7, 14, 14, 4, 4);
            if (on) g2.fill(box); else g2.draw(box);
            if (on) {
                g2.setColor(Color.WHITE);
                g2.setStroke(new BasicStroke(2f));
                g2.drawLine(x + 3, h / 2, x + 6, h / 2 + 3);
                g2.drawLine(x + 6, h / 2 + 3, x + 11, h / 2 - 3);
            }
            x += 24;
            // avatar
            int d = h - 8;
            java.awt.geom.Ellipse2D circle = new java.awt.geom.Ellipse2D.Float(x, 4, d, d);
            Image face = null;
            try {
                java.nio.file.Path p = LinkServer.avatarOf(row[0]);
                if (p != null) face = javax.imageio.ImageIO.read(p.toFile());
            } catch (Exception ignored) {
            }
            if (face != null) {
                Shape old = g2.getClip();
                g2.clip(circle);
                g2.drawImage(face, x, 4, d, d, null);
                g2.setClip(old);
            } else {
                g2.setColor(new Color(255, 255, 255, 25));
                g2.fill(circle);
            }
            x += d + 10;
            // UID
            String id = LinkServer.norm(row[0]);
            g2.setFont(new Font("Consolas", Font.BOLD, 14));
            g2.setColor(banned ? UI.DANGER : UI.TEXT);
            FontMetrics fm = g2.getFontMetrics();
            g2.drawString("UID " + id, x, (h + fm.getAscent() - fm.getDescent()) / 2);
            x += Math.max(70, fm.stringWidth("UID " + id) + 12);
            // rank chip
            String rank = banned ? "BAN" : row[1];
            Color c = banned ? UI.DANGER : UI.rankColor(rank);
            g2.setFont(new Font("Segoe UI", Font.BOLD, 10));
            FontMetrics rm = g2.getFontMetrics();
            int cw = rm.stringWidth(rank) + 16;
            java.awt.geom.RoundRectangle2D chip = new java.awt.geom.RoundRectangle2D.Float(x, (h - 18) / 2f, cw, 18, 9, 9);
            g2.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), 55));
            g2.fill(chip);
            g2.setColor(c);
            g2.draw(chip);
            g2.drawString(rank, x + 8, (h + rm.getAscent() - rm.getDescent()) / 2);
            x += cw + 12;
            // Discord name
            String name = LinkServer.discordNameOf(row[0]);
            if (name != null && !name.isBlank()) {
                g2.setFont(new Font("Segoe UI", Font.PLAIN, 12));
                g2.setColor(UI.TEXT_DIM);
                FontMetrics nm = g2.getFontMetrics();
                g2.drawString(name, x, (h + nm.getAscent() - nm.getDescent()) / 2);
            }
            g2.dispose();
        }
    }

    private void publish() {
        String text = notes.getText().trim();
        if (text.isEmpty() || text.replace("-", "").isBlank()) {
            say.accept("Napisz najpierw, co dodałeś.");
            return;
        }
        try {
            Path jar = newestBuild();
            if (jar == null) {
                say.accept("Nie znalazłem jara klienta (zbuduj moda).");
                return;
            }
            java.util.List<String> who = new java.util.ArrayList<>();
            for (String s : target.getText().split("[,;\\s]+")) if (!s.isBlank()) who.add(s.trim());
            int n = LinkServer.publish(jar, text, who);
            say.accept("Wydano aktualizację #" + n + " dla: " + LinkServer.targetsText() + " - dostaną ją przy następnym starcie gry.");
            refresh();
        } catch (Exception ex) {
            say.accept("Nie udało się wydać: " + ex.getMessage());
        }
    }

    /** The freshest client jar: ../build/libs (newest, not -sources), else the client carried inside this app. */
    static Path newestBuild() throws Exception {
        File libs = Paths.get("..", "build", "libs").toFile();
        File best = null;
        File[] files = libs.listFiles((d, n) -> n.endsWith(".jar") && !n.contains("sources")
                && (n.startsWith("GombalBeta") || n.startsWith("thunderhack")));
        if (files != null)
            for (File f : files)
                if (best == null || f.lastModified() > best.lastModified()) best = f;
        if (best != null) return best.toPath();
        try (InputStream in = UpdatePage.class.getResourceAsStream("/client.jar")) {
            if (in == null) return null;
            Path tmp = Files.createTempFile("globaldlc-client", ".jar");
            Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
            return tmp;
        }
    }

    private void refresh() {
        reloadPeople();
        int rel = LinkServer.release();
        current.setText(rel == 0 ? "Brak wydania - wydaj pierwsze" : "<html>Aktualne wydanie: #" + rel + "<br><span style='font-size:10px'>dla: " + LinkServer.targetsText() + "</span></html>");
        try {
            Path jar = newestBuild();
            source.setText(jar == null ? "Build: brak" : "Build do wydania: " + jar.getFileName() + "  ("
                    + new SimpleDateFormat("dd.MM HH:mm").format(new Date(jar.toFile().lastModified())) + ")");
        } catch (Exception e) {
            source.setText("Build: ?");
        }
        if (!LinkServer.running()) {
            server.setText("<html>Serwer nie działa (port " + LinkServer.port() + " zajęty?)</html>");
            server.setForeground(UI.DANGER);
        } else {
            long ago = LinkServer.lastRequest() == 0 ? -1 : (System.currentTimeMillis() - LinkServer.lastRequest()) / 1000;
            server.setText("<html>" + address() + "<br>" + (ago < 0 ? "jeszcze nikt się nie połączył"
                    : "ostatnie połączenie " + ago + " s temu  ·  zapytań: " + LinkServer.requests()) + "</html>");
            server.setForeground(ago >= 0 && ago < 30 ? UI.OK : UI.TEXT);
        }
    }

    /** The address stamped into every jar - what players' games connect to. */
    static String address() {
        return LinkServer.publicAddress();
    }

    private static String localIp() {
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback() || ni.isVirtual()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses()))
                    if (a instanceof Inet4Address && a.isSiteLocalAddress()) return a.getHostAddress();
            }
        } catch (Exception ignored) {
        }
        return "127.0.0.1";
    }

    private static JLabel caption(String text) {
        JLabel l = new JLabel(text);
        l.setFont(UI.tracked(new Font("Segoe UI", Font.BOLD, 11), 0.14f));
        l.setForeground(UI.TEXT_DIM);
        l.setAlignmentX(Component.LEFT_ALIGNMENT);
        return l;
    }

    private static void style(JLabel l, boolean bold) {
        l.setFont(new Font("Segoe UI", bold ? Font.BOLD : Font.PLAIN, bold ? 15 : 12));
        l.setForeground(UI.TEXT);
        l.setAlignmentX(Component.LEFT_ALIGNMENT);
        l.setBorder(BorderFactory.createEmptyBorder(2, 0, 2, 0));
    }
}
