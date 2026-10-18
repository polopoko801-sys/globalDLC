import javax.swing.*;
import javax.swing.Timer;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.awt.event.*;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

/**
 * ThunderHack Admin - panel sprzedawcy (Ty).
 * Logowanie haslem 2137. Pozwala:
 *   - generowac UID-y i nadawac im range (CUSTOMER / DEV / TESTER),
 *   - wypuszczac aktualizacje (wersja + kanal + notatki).
 * Dane trafiaja do ./data, ktore czyta gotowy Injector.
 */
public class ThunderAdmin extends GlassFrame {

    static final String OWNER_PASSWORD = "2137";

    private final CardLayout cards = new CardLayout();
    private final JPanel deck = new JPanel(cards);

    public ThunderAdmin() {
        super("ThunderHack Admin", "ThunderHack  Panel Admina", 860, 600);
        deck.setOpaque(false);
        deck.add(new Login(this::onLogged), "login");
        deck.add(new Dashboard(), "dash");
        cards.show(deck, "login");
        setBody(deck);
    }
    private void onLogged() { cards.show(deck, "dash"); }

    // ====================== Logowanie wlasciciela ======================
    static class Login extends JPanel {
        final JPasswordField field = new JPasswordField();
        final JLabel error = new JLabel(" ");
        int shake = 0; Timer shakeTimer;
        Login(Runnable onOk) {
            setOpaque(false); setLayout(new GridBagLayout());
            UI.RoundPanel card = new UI.RoundPanel(22, UI.PANEL);
            card.setLayout(new GridBagLayout());
            card.setBorder(BorderFactory.createEmptyBorder(32, 44, 32, 44));
            card.setPreferredSize(new Dimension(430, 300));
            GridBagConstraints g = new GridBagConstraints();
            g.gridx = 0; g.gridy = 0; g.fill = GridBagConstraints.HORIZONTAL; g.weightx = 1;

            card.add(new UI.LogoStar(46), g);
            g.gridy++;
            JLabel title = UI.gradLabel("Panel Admina", 28);
            title.setHorizontalAlignment(SwingConstants.CENTER);
            card.add(title, g);
            g.gridy++;
            JLabel sub = new JLabel("ZARZADZANIE LICENCJAMI I WYDANIAMI", SwingConstants.CENTER);
            sub.setForeground(UI.TEXT_DIM); sub.setFont(new Font("Segoe UI", Font.BOLD, 11));
            card.add(sub, g);
            g.gridy++; g.insets = new Insets(24, 0, 0, 0);
            JLabel lbl = new JLabel("HASLO WLASCICIELA");
            lbl.setForeground(UI.TEXT_DIM); lbl.setFont(new Font("Segoe UI", Font.BOLD, 11));
            card.add(lbl, g);
            g.gridy++; g.insets = new Insets(8, 0, 0, 0);
            field.setEchoChar('●'); field.setForeground(UI.TEXT); field.setCaretColor(UI.ACCENT);
            field.setBackground(new Color(10, 14, 26));
            field.setHorizontalAlignment(JTextField.CENTER);
            field.setFont(new Font("Consolas", Font.BOLD, 20));
            field.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(new Color(40, 224, 212, 120), 1, true),
                    BorderFactory.createEmptyBorder(10, 12, 10, 12)));
            field.addActionListener(e -> tryLogin(onOk));
            card.add(field, g);
            g.gridy++; g.insets = new Insets(6, 0, 0, 0);
            error.setForeground(UI.DANGER); error.setFont(new Font("Segoe UI", Font.PLAIN, 11));
            error.setHorizontalAlignment(SwingConstants.CENTER);
            card.add(error, g);
            g.gridy++; g.insets = new Insets(16, 0, 0, 0);
            UI.GlowButton b = new UI.GlowButton("WEJDZ", UI.ACCENT, UI.ACCENT2);
            b.addActionListener(e -> tryLogin(onOk));
            card.add(b, g);
            add(card);
            SwingUtilities.invokeLater(field::requestFocusInWindow);
        }
        void tryLogin(Runnable onOk) {
            if (OWNER_PASSWORD.equals(new String(field.getPassword()))) onOk.run();
            else { error.setText("Niepoprawne haslo"); field.setText(""); doShake(); }
        }
        void doShake() {
            if (shakeTimer != null && shakeTimer.isRunning()) return;
            final int[] n = {0};
            shakeTimer = new Timer(18, null);
            shakeTimer.addActionListener(e -> {
                n[0]++; shake = (int) (Math.sin(n[0] * 0.9) * (10 - n[0] * 0.5));
                if (n[0] > 18) { shake = 0; shakeTimer.stop(); }
                repaint();
            });
            shakeTimer.start();
        }
        public void paint(Graphics g) { g.translate(shake, 0); super.paint(g); g.translate(-shake, 0); }
    }

    // ====================== Dashboard ======================
    static class Dashboard extends JPanel {
        final CardLayout inner = new CardLayout();
        final JPanel pages = new JPanel(inner);
        Dashboard() {
            setOpaque(false);
            setLayout(new BorderLayout());
            setBorder(BorderFactory.createEmptyBorder(6, 18, 18, 18));

            JPanel tabs = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 6));
            tabs.setOpaque(false);
            UI.GlowButton tLic = new UI.GlowButton("LICENCJE / UID", UI.ACCENT, UI.ACCENT2);
            UI.GlowButton tRel = new UI.GlowButton("AKTUALIZACJE", UI.ACCENT2, UI.ACCENT);
            tLic.setPreferredSize(new Dimension(190, 40));
            tRel.setPreferredSize(new Dimension(190, 40));
            tLic.addActionListener(e -> inner.show(pages, "lic"));
            tRel.addActionListener(e -> inner.show(pages, "rel"));
            tabs.add(tLic); tabs.add(tRel);
            add(tabs, BorderLayout.NORTH);

            pages.setOpaque(false);
            pages.add(new LicensePage(), "lic");
            pages.add(new ReleasePage(), "rel");
            add(pages, BorderLayout.CENTER);
            inner.show(pages, "lic");
        }
    }

    // ---------- Strona licencji ----------
    static class LicensePage extends UI.RoundPanel {
        final DefaultListModel<Store.License> model = new DefaultListModel<>();
        final JList<Store.License> list = new JList<>(model);
        final JComboBox<String> rankBox = new JComboBox<>(Store.RANKS);
        final JTextField labelField = new JTextField();
        final JLabel info = new JLabel(" ");

        LicensePage() {
            super(18, new Color(16, 21, 38, 220));
            setLayout(new BorderLayout(14, 14));
            setBorder(BorderFactory.createEmptyBorder(18, 18, 18, 18));

            // lista
            list.setOpaque(false);
            list.setCellRenderer(new LicenseRenderer());
            list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
            list.addMouseListener(new MouseAdapter() {
                public void mousePressed(MouseEvent e) { maybePopup(e); }
                public void mouseReleased(MouseEvent e) { maybePopup(e); }
                void maybePopup(MouseEvent e) {
                    if (!e.isPopupTrigger()) return;
                    int idx = list.locationToIndex(e.getPoint());
                    if (idx < 0 || !list.getCellBounds(idx, idx).contains(e.getPoint())) return;
                    list.setSelectedIndex(idx);
                    showMenu(e, model.get(idx));
                }
            });
            JScrollPane sp = new JScrollPane(list);
            sp.setOpaque(false); sp.getViewport().setOpaque(false);
            sp.setBorder(BorderFactory.createLineBorder(new Color(255, 255, 255, 24), 1, true));
            add(sp, BorderLayout.CENTER);

            // panel akcji po prawej
            JPanel side = new JPanel();
            side.setOpaque(false);
            side.setLayout(new BoxLayout(side, BoxLayout.Y_AXIS));
            side.setPreferredSize(new Dimension(280, 10));

            side.add(label("NOWA LICENCJA"));
            side.add(Box.createVerticalStrut(8));
            side.add(small("Ranga"));
            styleCombo(rankBox);
            cap(rankBox, 40); side.add(rankBox);
            side.add(Box.createVerticalStrut(8));
            side.add(small("Etykieta (np. nick kupujacego)"));
            styleField(labelField);
            cap(labelField, 38); side.add(labelField);
            side.add(Box.createVerticalStrut(12));

            UI.GlowButton gen = new UI.GlowButton("GENERUJ UID", UI.ACCENT, UI.ACCENT2);
            cap(gen, 44); gen.addActionListener(e -> generate());
            side.add(gen);
            side.add(Box.createVerticalStrut(8));
            UI.GlowButton copy = new UI.GlowButton("KOPIUJ UID", UI.ACCENT2, UI.ACCENT);
            cap(copy, 40); copy.addActionListener(e -> copySelected());
            side.add(copy);
            side.add(Box.createVerticalStrut(8));
            UI.GlowButton del = new UI.GlowButton("USUN ZAZNACZONY", UI.DANGER, new Color(255, 140, 120));
            cap(del, 40); del.addActionListener(e -> deleteSelected());
            side.add(del);
            side.add(Box.createVerticalStrut(14));
            info.setForeground(UI.TEXT_DIM); info.setFont(new Font("Consolas", Font.PLAIN, 11));
            info.setAlignmentX(LEFT_ALIGNMENT);
            side.add(info);
            side.add(Box.createVerticalGlue());

            add(side, BorderLayout.EAST);
            reload();
        }

        void reload() {
            model.clear();
            for (Store.License l : Store.loadLicenses()) model.addElement(l);
            info.setText(model.size() + " aktywnych licencji");
        }
        void generate() {
            String rank = (String) rankBox.getSelectedItem();
            String lbl = labelField.getText().trim();
            List<Store.License> all = Store.loadLicenses();
            String uid = Store.nextUid(all);
            Store.License lic = new Store.License(uid, rank, lbl, System.currentTimeMillis());
            all.add(lic);
            Store.saveLicenses(all);
            labelField.setText("");
            reload();
            for (int i = 0; i < model.size(); i++)
                if (model.get(i).uid.equals(uid)) { list.setSelectedIndex(i); list.ensureIndexIsVisible(i); }
            toClipboard(uid);
            File f = Store.writeUidFile(lic);                 // klikalny plik na pulpicie
            info.setForeground(UI.OK);
            info.setText("UID " + uid + " (" + rank + ") - plik na pulpicie: "
                    + (f != null ? f.getName() : "blad zapisu"));
        }

        void showMenu(java.awt.event.MouseEvent e, Store.License sel) {
            JPopupMenu menu = new JPopupMenu();
            menu.setBackground(new Color(18, 23, 42));
            menu.setBorder(BorderFactory.createLineBorder(new Color(255, 255, 255, 40)));
            JMenuItem ban = menuItem(sel.banned ? "Odbanuj UID" : "Zbanuj UID");
            JMenuItem get = menuItem("Pobierz UID (na pulpit)");
            ban.addActionListener(a -> toggleBan(sel));
            get.addActionListener(a -> download(sel));
            menu.add(ban); menu.add(get);
            menu.show(list, e.getX(), e.getY());
        }
        JMenuItem menuItem(String text) {
            JMenuItem m = new JMenuItem(text);
            m.setBackground(new Color(18, 23, 42));
            m.setForeground(UI.TEXT);
            m.setFont(new Font("Segoe UI", Font.BOLD, 12));
            m.setBorder(BorderFactory.createEmptyBorder(7, 14, 7, 16));
            return m;
        }
        void toggleBan(Store.License sel) {
            List<Store.License> all = Store.loadLicenses();
            boolean now = false;
            for (Store.License l : all)
                if (l.uid.equalsIgnoreCase(sel.uid)) { l.banned = !l.banned; now = l.banned; }
            Store.saveLicenses(all);
            reload();
            for (int i = 0; i < model.size(); i++)
                if (model.get(i).uid.equalsIgnoreCase(sel.uid)) list.setSelectedIndex(i);
            info.setForeground(now ? UI.DANGER : UI.OK);
            info.setText("UID " + sel.uid + (now ? " zbanowany" : " odbanowany"));
        }
        void download(Store.License sel) {
            File f = Store.writeUidFile(sel);
            info.setForeground(UI.OK);
            info.setText(f != null ? "Zapisano na pulpicie: " + f.getName() : "Blad zapisu pliku");
        }
        void copySelected() {
            Store.License l = list.getSelectedValue();
            if (l == null) { warn("Zaznacz licencje"); return; }
            toClipboard(l.uid);
            info.setForeground(UI.OK); info.setText("Skopiowano: " + l.uid);
        }
        void deleteSelected() {
            Store.License l = list.getSelectedValue();
            if (l == null) { warn("Zaznacz licencje"); return; }
            List<Store.License> all = Store.loadLicenses();
            all.removeIf(x -> x.uid.equalsIgnoreCase(l.uid));
            Store.saveLicenses(all);
            reload();
            info.setForeground(UI.TEXT_DIM); info.setText("Usunieto " + l.uid);
        }
        void warn(String s) { info.setForeground(UI.DANGER); info.setText(s); }
        void toClipboard(String s) {
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(s), null);
        }
    }

    static class LicenseRenderer extends JPanel implements ListCellRenderer<Store.License> {
        final JLabel uid = new JLabel(), meta = new JLabel(), rank = new JLabel();
        LicenseRenderer() {
            setLayout(new BorderLayout());
            setBorder(BorderFactory.createEmptyBorder(8, 12, 8, 12));
            JPanel left = new JPanel(); left.setOpaque(false);
            left.setLayout(new BoxLayout(left, BoxLayout.Y_AXIS));
            uid.setFont(new Font("Consolas", Font.BOLD, 14));
            meta.setFont(new Font("Segoe UI", Font.PLAIN, 11));
            left.add(uid); left.add(meta);
            add(left, BorderLayout.CENTER);
            rank.setFont(new Font("Segoe UI", Font.BOLD, 13));
            rank.setBorder(BorderFactory.createEmptyBorder(0, 10, 0, 4));
            add(rank, BorderLayout.EAST);
        }
        public Component getListCellRendererComponent(JList<? extends Store.License> l, Store.License v,
                                                      int i, boolean sel, boolean foc) {
            setOpaque(true);
            setBackground(sel ? new Color(40, 224, 212, 40) : new Color(255, 255, 255, i % 2 == 0 ? 8 : 14));
            String when = v.created > 0 ? new SimpleDateFormat("yyyy-MM-dd").format(new Date(v.created)) : "-";
            uid.setForeground(v.banned ? UI.DANGER : UI.TEXT);
            uid.setText("#" + v.uid + (v.banned ? "  (BAN)" : ""));
            meta.setForeground(UI.TEXT_DIM);
            meta.setText((v.label == null || v.label.isEmpty() ? "(bez etykiety)" : v.label) + "   -   " + when);
            rank.setForeground(v.banned ? UI.DANGER : UI.rankColor(v.rank));
            rank.setText(v.banned ? "BAN" : v.rank);
            return this;
        }
    }

    // ---------- Strona wydan ----------
    static class ReleasePage extends UI.RoundPanel {
        final JTextField version = new JTextField();
        final JComboBox<String> channel = new JComboBox<>(new String[]{"stable", "beta", "dev"});
        final JTextArea notes = new JTextArea();
        final JLabel current = new JLabel();
        final JLabel info = new JLabel(" ");
        ReleasePage() {
            super(18, new Color(16, 21, 38, 220));
            setLayout(new BorderLayout(16, 12));
            setBorder(BorderFactory.createEmptyBorder(20, 22, 20, 22));

            JPanel form = new JPanel();
            form.setOpaque(false);
            form.setLayout(new BoxLayout(form, BoxLayout.Y_AXIS));

            JLabel title = UI.gradLabel("Wypuszczenie aktualizacji", 22);
            title.setAlignmentX(LEFT_ALIGNMENT);
            form.add(title);
            current.setForeground(UI.TEXT_DIM); current.setFont(new Font("Consolas", Font.PLAIN, 12));
            current.setAlignmentX(LEFT_ALIGNMENT);
            form.add(current);
            form.add(Box.createVerticalStrut(16));

            form.add(small("Wersja (np. 1.8)"));
            styleField(version); cap(version, 38); version.setAlignmentX(LEFT_ALIGNMENT);
            form.add(version);
            form.add(Box.createVerticalStrut(10));

            form.add(small("Kanal"));
            styleCombo(channel); cap(channel, 38); channel.setAlignmentX(LEFT_ALIGNMENT);
            form.add(channel);
            form.add(Box.createVerticalStrut(10));

            form.add(small("Notatki do wydania"));
            notes.setLineWrap(true); notes.setWrapStyleWord(true);
            notes.setForeground(UI.TEXT); notes.setCaretColor(UI.ACCENT);
            notes.setBackground(new Color(10, 14, 26));
            notes.setFont(new Font("Segoe UI", Font.PLAIN, 13));
            notes.setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));
            JScrollPane sp = new JScrollPane(notes);
            sp.setAlignmentX(LEFT_ALIGNMENT);
            sp.setBorder(BorderFactory.createLineBorder(new Color(40, 224, 212, 120), 1, true));
            sp.setMaximumSize(new Dimension(Integer.MAX_VALUE, 150));
            sp.setPreferredSize(new Dimension(10, 150));
            sp.getViewport().setBackground(new Color(10, 14, 26));
            form.add(sp);
            form.add(Box.createVerticalStrut(14));

            UI.GlowButton publish = new UI.GlowButton("WYPUSC AKTUALIZACJE", UI.ACCENT, UI.ACCENT2);
            publish.setAlignmentX(LEFT_ALIGNMENT); cap(publish, 46);
            publish.addActionListener(e -> publish());
            form.add(publish);
            form.add(Box.createVerticalStrut(8));
            info.setForeground(UI.TEXT_DIM); info.setFont(new Font("Consolas", Font.PLAIN, 11));
            info.setAlignmentX(LEFT_ALIGNMENT);
            form.add(info);

            add(form, BorderLayout.NORTH);
            loadCurrent();
        }
        void loadCurrent() {
            Store.Release r = Store.loadRelease();
            version.setText(r.version);
            channel.setSelectedItem(r.channel);
            notes.setText(r.notes);
            String when = new SimpleDateFormat("yyyy-MM-dd HH:mm").format(new Date(r.time));
            current.setText("Aktualne wydanie: " + r.version + " (" + r.channel + ")  -  " + when);
        }
        void publish() {
            String v = version.getText().trim();
            if (v.isEmpty()) { info.setForeground(UI.DANGER); info.setText("Podaj wersje"); return; }
            Store.Release r = new Store.Release();
            r.version = v;
            r.channel = (String) channel.getSelectedItem();
            r.notes = notes.getText().trim();
            r.time = System.currentTimeMillis();
            Store.saveRelease(r);
            loadCurrent();
            info.setForeground(UI.OK);
            info.setText("Wypuszczono " + v + " (" + r.channel + ") - Injectory zobacza nowy build");
        }
    }

    // ---------- helpery UI ----------
    static JLabel label(String s) {
        JLabel l = new JLabel(s); l.setForeground(UI.TEXT);
        l.setFont(new Font("Segoe UI", Font.BOLD, 14)); l.setAlignmentX(LEFT_ALIGNMENT);
        return l;
    }
    static JLabel small(String s) {
        JLabel l = new JLabel(s); l.setForeground(UI.TEXT_DIM);
        l.setFont(new Font("Segoe UI", Font.BOLD, 11)); l.setAlignmentX(LEFT_ALIGNMENT);
        l.setBorder(BorderFactory.createEmptyBorder(2, 0, 4, 0));
        return l;
    }
    static void styleField(JTextField f) {
        f.setForeground(UI.TEXT); f.setCaretColor(UI.ACCENT);
        f.setBackground(new Color(10, 14, 26));
        f.setFont(new Font("Consolas", Font.BOLD, 15));
        f.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(40, 224, 212, 120), 1, true),
                BorderFactory.createEmptyBorder(8, 10, 8, 10)));
    }
    static void styleCombo(JComboBox<String> c) {
        c.setForeground(UI.TEXT); c.setBackground(new Color(10, 14, 26));
        c.setFont(new Font("Segoe UI", Font.BOLD, 13));
    }
    static void cap(JComponent c, int h) {
        c.setMaximumSize(new Dimension(Integer.MAX_VALUE, h));
        c.setAlignmentX(LEFT_ALIGNMENT);
    }

    public static void main(String[] args) {
        try { UIManager.setLookAndFeel(UIManager.getCrossPlatformLookAndFeelClassName()); }
        catch (Exception ignored) {}
        SwingUtilities.invokeLater(() -> new ThunderAdmin().setVisible(true));
    }
}
