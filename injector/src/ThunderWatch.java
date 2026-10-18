import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import javax.swing.*;
import javax.swing.Timer;
import java.awt.*;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.dnd.*;
import java.awt.event.*;
import java.awt.geom.*;
import java.io.*;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;

/**
 * ThunderWatch — podglad uzytkownikow klienta.
 * <p>
 * Aplikacja robi dwie rzeczy: hostuje plik moda (zakladka MOD) i sluchа na heartbeaty, ktore ten mod wysyla
 * (zakladka UZYTKOWNICY). Kazdy gracz, ktory odpali klienta z ustawionym adresem panelu w
 * {@code config/thunderhack/watch.txt}, pojawia sie tu na zywo ze swoim nickiem.
 */
public class ThunderWatch extends GlassFrame {

    static final Path DATA_DIR = Paths.get("data");
    static final Path USERS_FILE = DATA_DIR.resolve("watch-users.txt");
    static final Path CONFIG_FILE = DATA_DIR.resolve("watch-config.txt");
    /** Po tylu ms bez heartbeatu gracz przestaje byc "online" (mod bije co 20 s). */
    static final long ONLINE_WINDOW_MS = 55_000L;

    static final Model MODEL = new Model();

    public ThunderWatch() {
        super("ThunderWatch", "", 1080, 700);
        // Bez ekranu logowania — panel otwiera się od razu na dashboardzie.
        setBody(new Dashboard());
    }

    public static void main(String[] args) {
        MODEL.load();
        SwingUtilities.invokeLater(() -> {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (Exception ignored) {
            }
            new ThunderWatch().setVisible(true);
        });
        Runtime.getRuntime().addShutdownHook(new Thread(MODEL::save));
    }

    /* ================================================================== */
    /* Model                                                               */
    /* ================================================================== */

    /** Jeden gracz. Pola sa pisane z watku serwera, czytane z EDT — stad volatile. */
    static final class Seen {
        final String nick;
        volatile String uid = "-", ver = "-", mc = "-", server = "-", ip = "-";
        volatile long first = System.currentTimeMillis(), last, sessionStart;
        volatile int beats;
        /** 0..1 — animacja wjazdu karty, zeby nowy gracz nie "wyskakiwal". */
        float intro;

        Seen(String nick) {
            this.nick = nick;
        }

        boolean online() {
            return System.currentTimeMillis() - last < ONLINE_WINDOW_MS;
        }
    }

    static final class Model {
        final Map<String, Seen> users = new ConcurrentHashMap<>();
        /** Liczba graczy online, probkowana co 5 s — zrodlo dla wykresu. */
        final int[] history = new int[140];
        volatile String modPath = "";
        /** Recznie wskazany accounts.txt — pusty = szukamy sami po instancjach. */
        volatile String accountsPath = "";
        volatile String host = "0.0.0.0";
        volatile int port = 7331;
        volatile HttpServer server;
        volatile java.util.concurrent.ExecutorService pool;

        List<Seen> sorted() {
            List<Seen> l = new ArrayList<>(users.values());
            l.sort((a, b) -> {
                if (a.online() != b.online()) return a.online() ? -1 : 1;
                return Long.compare(b.last, a.last);
            });
            return l;
        }

        int onlineCount() {
            int n = 0;
            for (Seen s : users.values()) if (s.online()) n++;
            return n;
        }

        int todayCount() {
            long dayAgo = System.currentTimeMillis() - 86_400_000L;
            int n = 0;
            for (Seen s : users.values()) if (s.last > dayAgo) n++;
            return n;
        }

        void pushHistory() {
            System.arraycopy(history, 1, history, 0, history.length - 1);
            history[history.length - 1] = onlineCount();
        }

        Seen beat(String nick, String uid, String ver, String server, String mcver, String ip) {
            Seen s = users.computeIfAbsent(nick, Seen::new);
            long now = System.currentTimeMillis();
            // przerwa dluzsza niz okno online = nowa sesja
            if (now - s.last > ONLINE_WINDOW_MS) s.sessionStart = now;
            s.uid = uid;
            s.ver = ver;
            s.server = server;
            s.mc = mcver;
            s.ip = ip;
            s.last = now;
            s.beats++;
            return s;
        }

        void load() {
            try {
                Files.createDirectories(DATA_DIR);
                if (Files.exists(CONFIG_FILE)) {
                    for (String line : Files.readAllLines(CONFIG_FILE)) {
                        int eq = line.indexOf('=');
                        if (eq < 0) continue;
                        String k = line.substring(0, eq).trim(), v = line.substring(eq + 1).trim();
                        switch (k) {
                            case "mod" -> modPath = v;
                            case "accounts" -> accountsPath = v;
                            case "host" -> host = v;
                            case "port" -> port = parseInt(v, 7331);
                        }
                    }
                }
                if (Files.exists(USERS_FILE)) {
                    for (String line : Files.readAllLines(USERS_FILE)) {
                        if (line.isBlank() || line.startsWith("#")) continue;
                        String[] p = line.split("\\|", -1);
                        if (p.length < 9) continue;
                        Seen s = new Seen(p[0]);
                        s.uid = p[1];
                        s.ver = p[2];
                        s.mc = p[3];
                        s.server = p[4];
                        s.ip = p[5];
                        s.first = Long.parseLong(p[6]);
                        s.last = Long.parseLong(p[7]);
                        s.beats = Integer.parseInt(p[8]);
                        s.intro = 1f;
                        users.put(s.nick, s);
                    }
                }
            } catch (Exception ignored) {
            }
        }

        void save() {
            try {
                Files.createDirectories(DATA_DIR);
                StringBuilder sb = new StringBuilder("# nick|uid|ver|mc|server|ip|first|last|beats\n");
                for (Seen s : users.values())
                    sb.append(s.nick).append('|').append(s.uid).append('|').append(s.ver).append('|')
                            .append(s.mc).append('|').append(s.server).append('|').append(s.ip).append('|')
                            .append(s.first).append('|').append(s.last).append('|').append(s.beats).append('\n');
                Files.writeString(USERS_FILE, sb.toString());
                Files.writeString(CONFIG_FILE, "mod=" + modPath + "\naccounts=" + accountsPath
                        + "\nhost=" + host + "\nport=" + port + "\n");
            } catch (Exception ignored) {
            }
        }
    }

    static int parseInt(String s, int def) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return def;
        }
    }

    /* ================================================================== */
    /* Serwer                                                              */
    /* ================================================================== */

    static void startServer() throws IOException {
        stopServer();
        HttpServer s = HttpServer.create(new InetSocketAddress(MODEL.port), 0);
        // wlasne watki demona: inaczej pula trzyma JVM przy zyciu po zatrzymaniu serwera
        java.util.concurrent.ExecutorService pool = Executors.newFixedThreadPool(4, r -> {
            Thread t = new Thread(r, "watch-http");
            t.setDaemon(true);
            return t;
        });
        s.setExecutor(pool);
        MODEL.pool = pool;

        s.createContext("/api/beat", ex -> {
            try {
                Map<String, String> f = readForm(ex);
                String nick = f.getOrDefault("nick", "").trim();
                if (!nick.isEmpty())
                    MODEL.beat(nick,
                            f.getOrDefault("uid", "-"),
                            f.getOrDefault("ver", "-"),
                            f.getOrDefault("server", "-"),
                            f.getOrDefault("mc", "-"),
                            ex.getRemoteAddress().getAddress().getHostAddress());
                reply(ex, 200, "ok");
            } catch (Exception e) {
                reply(ex, 400, "bad");
            }
        });

        s.createContext("/mod.jar", ex -> {
            Path p = Paths.get(MODEL.modPath);
            if (MODEL.modPath.isEmpty() || !Files.exists(p)) {
                reply(ex, 404, "no mod");
                return;
            }
            byte[] data = Files.readAllBytes(p);
            ex.getResponseHeaders().add("Content-Type", "application/java-archive");
            ex.getResponseHeaders().add("Content-Disposition",
                    "attachment; filename=\"" + p.getFileName() + "\"");
            ex.sendResponseHeaders(200, data.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(data);
            }
        });

        s.createContext("/", ex -> {
            StringBuilder sb = new StringBuilder("ThunderWatch\nonline: " + MODEL.onlineCount()
                    + " / " + MODEL.users.size() + "\n\n");
            for (Seen u : MODEL.sorted())
                sb.append(u.online() ? "[*] " : "[ ] ").append(u.nick)
                        .append("  ").append(u.ver).append("  ").append(u.server).append('\n');
            reply(ex, 200, sb.toString());
        });

        s.start();
        MODEL.server = s;
    }

    static void stopServer() {
        HttpServer s = MODEL.server;
        if (s != null) {
            s.stop(0);
            MODEL.server = null;
        }
        java.util.concurrent.ExecutorService p = MODEL.pool;
        if (p != null) {
            p.shutdownNow();
            MODEL.pool = null;
        }
    }

    static Map<String, String> readForm(HttpExchange ex) throws IOException {
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Map<String, String> m = new HashMap<>();
        for (String pair : body.split("&")) {
            int eq = pair.indexOf('=');
            if (eq <= 0) continue;
            m.put(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
                    URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
        }
        return m;
    }

    static void reply(HttpExchange ex, int code, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "text/plain; charset=UTF-8");
        ex.sendResponseHeaders(code, b.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(b);
        }
    }

    /** Adres LAN tej maszyny — to jego trzeba wpisac graczom w watch.txt. */
    static String localIp() {
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (ni.isLoopback() || !ni.isUp()) continue;
                for (java.net.InetAddress a : Collections.list(ni.getInetAddresses()))
                    if (a instanceof java.net.Inet4Address) return a.getHostAddress();
            }
        } catch (Exception ignored) {
        }
        return "127.0.0.1";
    }

    /* ================================================================== */
    /* Dashboard                                                           */
    /* ================================================================== */

    static class Dashboard extends JPanel {
        final CardLayout inner = new CardLayout();
        final JPanel pages = new JPanel(inner);

        Dashboard() {
            setOpaque(false);
            setLayout(new BorderLayout());
            setBorder(BorderFactory.createEmptyBorder(0, 16, 16, 16));

            JPanel tabs = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 6));
            tabs.setOpaque(false);
            AccountsPage accountsPage = new AccountsPage();
            TabBar tabBar = new TabBar();
            tabBar.add("UZYTKOWNICY", () -> inner.show(pages, "users"));
            tabBar.add("KONTA", () -> {
                accountsPage.reload();
                inner.show(pages, "acc");
            });
            tabBar.add("MOD / SERWER", () -> inner.show(pages, "mod"));
            tabs.add(tabBar);
            add(tabs, BorderLayout.NORTH);

            pages.setOpaque(false);
            pages.add(new UsersPage(), "users");
            pages.add(accountsPage, "acc");
            pages.add(new ModPage(), "mod");
            add(pages, BorderLayout.CENTER);
        }
    }

    /** Segmentowany przełącznik zakładek: jedna zaokrąglona listwa, aktywny segment podświetlony
     *  gradientem, który płynnie przesuwa się przy zmianie. */
    static class TabBar extends JComponent {
        final List<String> labels = new ArrayList<>();
        final List<Runnable> actions = new ArrayList<>();
        int active = 0, hoverIdx = -1;
        float indicator = 0;   // animowana pozycja podświetlenia (indeks ułamkowy)

        TabBar() {
            setOpaque(false);
            setPreferredSize(new Dimension(520, 42));
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            new Timer(16, e -> {
                if (Math.abs(indicator - active) > 0.001f) {
                    indicator += (active - indicator) * 0.25f;
                    repaint();
                }
            }).start();
            addMouseMotionListener(new MouseMotionAdapter() {
                public void mouseMoved(MouseEvent e) {
                    int idx = idxAt(e.getX());
                    if (idx != hoverIdx) {
                        hoverIdx = idx;
                        repaint();
                    }
                }
            });
            addMouseListener(new MouseAdapter() {
                public void mouseExited(MouseEvent e) {
                    hoverIdx = -1;
                    repaint();
                }

                public void mouseClicked(MouseEvent e) {
                    int idx = idxAt(e.getX());
                    if (idx >= 0 && idx != active) {
                        active = idx;
                        repaint();
                    }
                    if (idx >= 0) actions.get(idx).run();
                }
            });
        }

        void add(String label, Runnable action) {
            labels.add(label);
            actions.add(action);
            int w = 24;
            FontMetrics fm = getFontMetrics(new Font("Segoe UI", Font.BOLD, 13));
            for (String s : labels) w += fm.stringWidth(s) + 40;
            setPreferredSize(new Dimension(w, 42));
            setMaximumSize(new Dimension(w, 42));
            revalidate();
        }

        float segW() {
            return labels.isEmpty() ? 0 : (getWidth() - 8f) / labels.size();
        }

        int idxAt(int x) {
            if (labels.isEmpty()) return -1;
            int i = (int) ((x - 4) / segW());
            return (i >= 0 && i < labels.size()) ? i : -1;
        }

        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            int w = getWidth(), h = getHeight();

            RoundRectangle2D track = new RoundRectangle2D.Float(0, 0, w, h, 12, 12);
            g2.setColor(UI.CARD);
            g2.fill(track);
            g2.setColor(UI.BORDER);
            g2.draw(new RoundRectangle2D.Float(0.5f, 0.5f, w - 1, h - 1, 12, 12));

            float sw = segW();
            // podświetlenie aktywnego segmentu
            float ix = 4 + indicator * sw;
            RoundRectangle2D hl = new RoundRectangle2D.Float(ix + 2, 4, sw - 4, h - 8, 9, 9);
            g2.setPaint(new GradientPaint(ix, 0, UI.ACCENT, ix + sw, 0, UI.ACCENT2));
            g2.fill(hl);
            g2.setColor(UI.alpha(Color.WHITE, 40));
            g2.draw(hl);

            g2.setFont(new Font("Segoe UI", Font.BOLD, 13));
            FontMetrics fm = g2.getFontMetrics();
            for (int i = 0; i < labels.size(); i++) {
                String s = labels.get(i);
                float cx = 4 + i * sw + sw / 2f;
                boolean act = Math.abs(indicator - i) < 0.5f;
                g2.setColor(act ? Color.WHITE : (i == hoverIdx ? UI.TEXT : UI.TEXT_DIM));
                g2.drawString(s, cx - fm.stringWidth(s) / 2f, (h + fm.getAscent() - fm.getDescent()) / 2f);
            }
            g2.dispose();
        }
    }

    /* ================================================================== */
    /* Strona: uzytkownicy                                                 */
    /* ================================================================== */

    static class UsersPage extends JPanel {
        final StatCard cOnline = new StatCard("ONLINE TERAZ", UI.OK);
        final StatCard cToday = new StatCard("DZISIAJ", UI.ACCENT);
        final StatCard cTotal = new StatCard("LACZNIE", UI.ACCENT2);
        final StatCard cBeats = new StatCard("HEARTBEATY", UI.GOLD);
        final JTextField search = new JTextField();
        final JPanel list = new JPanel();
        final Graph graph = new Graph();
        final Map<String, UserCard> cards = new LinkedHashMap<>();
        long lastSample = 0;

        UsersPage() {
            setOpaque(false);
            setLayout(new BorderLayout(0, 12));
            setBorder(BorderFactory.createEmptyBorder(8, 2, 0, 2));

            JPanel top = new JPanel(new BorderLayout(12, 12));
            top.setOpaque(false);

            JPanel stats = new JPanel(new GridLayout(1, 4, 12, 0));
            stats.setOpaque(false);
            stats.add(cOnline);
            stats.add(cToday);
            stats.add(cTotal);
            stats.add(cBeats);
            top.add(stats, BorderLayout.NORTH);

            JPanel bar = new JPanel(new BorderLayout(12, 0));
            bar.setOpaque(false);
            bar.setBorder(BorderFactory.createEmptyBorder(12, 0, 0, 0));
            bar.add(graph, BorderLayout.CENTER);

            search.setForeground(UI.TEXT);
            search.setCaretColor(UI.ACCENT);
            search.setBackground(new Color(14, 12, 20));
            search.setFont(new Font("Segoe UI", Font.PLAIN, 13));
            search.setPreferredSize(new Dimension(230, 34));
            search.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(UI.BORDER, 1, true),
                    BorderFactory.createEmptyBorder(4, 10, 4, 10)));
            JPanel searchWrap = new JPanel(new BorderLayout());
            searchWrap.setOpaque(false);
            JLabel sl = new JLabel("SZUKAJ NICKU");
            sl.setForeground(UI.TEXT_DIM);
            sl.setFont(new Font("Segoe UI", Font.BOLD, 10));
            sl.setBorder(BorderFactory.createEmptyBorder(0, 2, 4, 0));
            searchWrap.add(sl, BorderLayout.NORTH);
            searchWrap.add(search, BorderLayout.CENTER);
            bar.add(searchWrap, BorderLayout.EAST);
            top.add(bar, BorderLayout.CENTER);
            add(top, BorderLayout.NORTH);

            list.setOpaque(false);
            list.setLayout(new BoxLayout(list, BoxLayout.Y_AXIS));
            JScrollPane sp = new JScrollPane(list);
            sp.setOpaque(false);
            sp.getViewport().setOpaque(false);
            sp.setBorder(null);
            sp.getVerticalScrollBar().setUnitIncrement(18);
            sp.getVerticalScrollBar().setPreferredSize(new Dimension(8, 0));
            add(sp, BorderLayout.CENTER);

            new Timer(500, e -> refresh()).start();
            new Timer(16, e -> {
                for (UserCard c : cards.values())
                    if (c.owner.intro < 1f) {
                        c.owner.intro = Math.min(1f, c.owner.intro + 0.07f);
                        c.repaint();
                    }
            }).start();
            search.addKeyListener(new KeyAdapter() {
                public void keyReleased(KeyEvent e) {
                    refresh();
                }
            });
            refresh();
        }

        void refresh() {
            long now = System.currentTimeMillis();
            if (now - lastSample > 5000) {
                MODEL.pushHistory();
                lastSample = now;
            }

            int beats = 0;
            for (Seen s : MODEL.users.values()) beats += s.beats;
            cOnline.set(MODEL.onlineCount());
            cToday.set(MODEL.todayCount());
            cTotal.set(MODEL.users.size());
            cBeats.set(beats);
            graph.repaint();

            String q = search.getText().trim().toLowerCase();
            List<Seen> want = new ArrayList<>();
            for (Seen s : MODEL.sorted())
                if (q.isEmpty() || s.nick.toLowerCase().contains(q)) want.add(s);

            // przebuduj tylko gdy zmienil sie sklad/kolejnosc — inaczej same repainty
            boolean same = want.size() == cards.size();
            if (same) {
                int i = 0;
                for (String nick : cards.keySet())
                    if (!want.get(i++).nick.equals(nick)) {
                        same = false;
                        break;
                    }
            }
            if (!same) {
                Map<String, UserCard> old = new LinkedHashMap<>(cards);
                cards.clear();
                list.removeAll();
                for (Seen s : want) {
                    UserCard c = old.getOrDefault(s.nick, new UserCard(s));
                    cards.put(s.nick, c);
                    list.add(c);
                    list.add(Box.createVerticalStrut(8));
                }
                if (want.isEmpty()) list.add(new EmptyState());
                list.revalidate();
            }
            list.repaint();
        }
    }

    /** Pusty stan listy — zamiast bialej pustki. */
    static class EmptyState extends JComponent {
        EmptyState() {
            setPreferredSize(new Dimension(10, 220));
        }

        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            int w = getWidth(), h = getHeight();
            g2.setColor(UI.TEXT_DIM);
            g2.setFont(new Font("Segoe UI", Font.BOLD, 15));
            String t = "Nikt jeszcze nie odpalil klienta";
            g2.drawString(t, (w - g2.getFontMetrics().stringWidth(t)) / 2, h / 2);
            g2.setFont(new Font("Segoe UI", Font.PLAIN, 12));
            String t2 = "Wlacz serwer w zakladce MOD i podaj graczom adres panelu";
            g2.setColor(UI.alpha(UI.TEXT_DIM, 150));
            g2.drawString(t2, (w - g2.getFontMetrics().stringWidth(t2)) / 2, h / 2 + 22);
            g2.dispose();
        }
    }

    /** Kafelek ze statystyka: duza liczba animowana do celu. */
    static class StatCard extends JComponent {
        final String label;
        final Color accent;
        double shown = 0;
        int target = 0;
        float pulse = 0;

        StatCard(String label, Color accent) {
            this.label = label;
            this.accent = accent;
            setPreferredSize(new Dimension(10, 84));
            new Timer(16, e -> {
                shown += (target - shown) * 0.14;
                if (Math.abs(target - shown) < 0.01) shown = target;
                pulse += 0.05f;
                repaint();
            }).start();
        }

        void set(int v) {
            target = v;
        }

        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            int w = getWidth(), h = getHeight();
            RoundRectangle2D r = new RoundRectangle2D.Float(0, 0, w, h, 16, 16);
            g2.setPaint(new GradientPaint(0, 0, UI.lighten(UI.CARD, 12), 0, h, UI.CARD));
            g2.fill(r);

            // pasek akcentu po lewej + delikatna poswiata
            Shape old = g2.getClip();
            g2.setClip(r);
            g2.setPaint(new GradientPaint(0, 0, UI.alpha(accent, 200), 4, 0, UI.alpha(accent, 60)));
            g2.fillRect(0, 0, 4, h);
            float p = 0.5f + 0.5f * (float) Math.sin(pulse);
            g2.setPaint(new RadialGradientPaint(new Point2D.Float(w * 0.12f, h * 0.5f), Math.max(1, h),
                    new float[]{0f, 1f},
                    new Color[]{UI.alpha(accent, (int) (28 + 16 * p)), UI.alpha(accent, 0)}));
            g2.fillRect(0, 0, w, h);
            g2.setClip(old);

            g2.setColor(UI.BORDER);
            g2.draw(new RoundRectangle2D.Float(0.5f, 0.5f, w - 1, h - 1, 16, 16));

            g2.setColor(UI.TEXT_DIM);
            g2.setFont(new Font("Segoe UI", Font.BOLD, 10));
            g2.drawString(label, 16, 24);

            g2.setFont(new Font("Segoe UI", Font.BOLD, 34));
            g2.setPaint(new GradientPaint(16, 0, UI.lighten(accent, 40), 16, h, accent));
            g2.drawString(String.valueOf((int) Math.round(shown)), 15, 66);
            g2.dispose();
        }
    }

    /** Wykres liczby graczy online w czasie (ostatnie ~12 minut). */
    static class Graph extends JComponent {
        Graph() {
            setPreferredSize(new Dimension(10, 84));
        }

        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            int w = getWidth(), h = getHeight();
            RoundRectangle2D r = new RoundRectangle2D.Float(0, 0, w, h, 16, 16);
            g2.setPaint(new GradientPaint(0, 0, UI.lighten(UI.CARD, 12), 0, h, UI.CARD));
            g2.fill(r);
            g2.setColor(UI.BORDER);
            g2.draw(new RoundRectangle2D.Float(0.5f, 0.5f, w - 1, h - 1, 16, 16));

            g2.setColor(UI.TEXT_DIM);
            g2.setFont(new Font("Segoe UI", Font.BOLD, 10));
            g2.drawString("AKTYWNOSC", 16, 20);

            int[] hist = MODEL.history;
            int max = 1;
            for (int v : hist) max = Math.max(max, v);

            float padX = 14, padTop = 28, padBot = 12;
            float gw = w - padX * 2, gh = h - padTop - padBot;
            if (gw <= 4) {
                g2.dispose();
                return;
            }

            Path2D line = new Path2D.Float(), area = new Path2D.Float();
            for (int i = 0; i < hist.length; i++) {
                float x = padX + gw * i / (hist.length - 1f);
                float y = padTop + gh - gh * hist[i] / (float) max;
                if (i == 0) {
                    line.moveTo(x, y);
                    area.moveTo(x, padTop + gh);
                    area.lineTo(x, y);
                } else {
                    line.lineTo(x, y);
                    area.lineTo(x, y);
                }
            }
            area.lineTo(padX + gw, padTop + gh);
            area.closePath();

            Shape old = g2.getClip();
            g2.setClip(r);
            g2.setPaint(new GradientPaint(0, padTop, UI.alpha(UI.ACCENT, 90), 0, padTop + gh, UI.alpha(UI.ACCENT, 0)));
            g2.fill(area);
            g2.setClip(old);

            g2.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g2.setPaint(new GradientPaint(padX, 0, UI.ACCENT2, padX + gw, 0, UI.ACCENT));
            g2.draw(line);

            g2.setColor(UI.TEXT_DIM);
            g2.setFont(new Font("Consolas", Font.PLAIN, 10));
            String m = "max " + max;
            g2.drawString(m, w - 14 - g2.getFontMetrics().stringWidth(m), 20);
            g2.dispose();
        }
    }

    /** Wiersz gracza: awatar, nick, plakietki, status. */
    static class UserCard extends JComponent {
        final Seen owner;
        float hover = 0, blink = 0;

        UserCard(Seen s) {
            this.owner = s;
            setPreferredSize(new Dimension(10, 66));
            setMaximumSize(new Dimension(Integer.MAX_VALUE, 66));
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setToolTipText("Kliknij aby skopiowac nick — prawy przycisk: wiecej");

            new Timer(16, e -> {
                float tgt = getMousePosition() != null ? 1f : 0f;
                hover += (tgt - hover) * 0.18f;
                blink += 0.07f;
                if (owner.online() || hover > 0.01f) repaint();
            }).start();

            addMouseListener(new MouseAdapter() {
                public void mouseClicked(MouseEvent e) {
                    if (SwingUtilities.isRightMouseButton(e)) menu(e);
                    else copy(owner.nick);
                }
            });
        }

        void menu(MouseEvent e) {
            JPopupMenu m = new JPopupMenu();
            JMenuItem a = new JMenuItem("Kopiuj nick");
            a.addActionListener(x -> copy(owner.nick));
            JMenuItem b = new JMenuItem("Kopiuj IP");
            b.addActionListener(x -> copy(owner.ip));
            JMenuItem c = new JMenuItem("Usun z listy");
            c.addActionListener(x -> {
                MODEL.users.remove(owner.nick);
                MODEL.save();
            });
            m.add(a);
            m.add(b);
            m.addSeparator();
            m.add(c);
            m.show(this, e.getX(), e.getY());
        }

        static void copy(String s) {
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(s), null);
        }

        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            int w = getWidth(), h = getHeight();
            float intro = Math.max(0f, Math.min(1f, owner.intro));
            // wjazd: lekki slide z prawej + fade
            g2.translate((1f - intro) * 26f, 0);
            g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.15f + 0.85f * intro));

            boolean on = owner.online();
            Color accent = on ? UI.OK : UI.TEXT_DIM;

            RoundRectangle2D r = new RoundRectangle2D.Float(0, 0, w, h, 14, 14);
            g2.setPaint(new GradientPaint(0, 0, UI.lighten(UI.CARD, (int) (10 + 14 * hover)), 0, h, UI.CARD));
            g2.fill(r);

            Shape old = g2.getClip();
            g2.setClip(r);
            if (on) {
                g2.setPaint(new GradientPaint(0, 0, UI.alpha(UI.OK, 170), 3, 0, UI.alpha(UI.OK, 40)));
                g2.fillRect(0, 0, 3, h);
            }
            if (hover > 0.01f) {
                g2.setPaint(new GradientPaint(0, 0, UI.alpha(UI.ACCENT, (int) (34 * hover)), w, 0, UI.alpha(UI.ACCENT, 0)));
                g2.fillRect(0, 0, w, h);
            }
            g2.setClip(old);
            g2.setColor(UI.alpha(Color.WHITE, (int) (30 + 40 * hover)));
            g2.draw(new RoundRectangle2D.Float(0.5f, 0.5f, w - 1, h - 1, 14, 14));

            // awatar: gradient z hashа nicku + inicjaly
            int av = 42, ax = 12, ay = (h - av) / 2;
            int hash = owner.nick.hashCode();
            Color c1 = Color.getHSBColor(((hash >>> 8) % 360) / 360f, 0.55f, 0.85f);
            Color c2 = Color.getHSBColor(((hash >>> 16) % 360) / 360f, 0.60f, 0.55f);
            RoundRectangle2D avr = new RoundRectangle2D.Float(ax, ay, av, av, 13, 13);
            g2.setPaint(new GradientPaint(ax, ay, c1, ax + av, ay + av, c2));
            g2.fill(avr);
            g2.setColor(UI.alpha(Color.WHITE, 60));
            g2.draw(avr);
            String ini = owner.nick.substring(0, Math.min(2, owner.nick.length())).toUpperCase();
            g2.setFont(new Font("Segoe UI", Font.BOLD, 17));
            g2.setColor(new Color(15, 12, 20, 220));
            FontMetrics fm = g2.getFontMetrics();
            g2.drawString(ini, ax + (av - fm.stringWidth(ini)) / 2, ay + (av + fm.getAscent() - fm.getDescent()) / 2);

            int tx = ax + av + 14;
            g2.setColor(UI.TEXT);
            g2.setFont(new Font("Segoe UI", Font.BOLD, 16));
            g2.drawString(owner.nick, tx, 28);

            // plakietki: wersja / uid / serwer
            int bx = tx;
            bx = chip(g2, bx, 38, owner.ver, UI.ACCENT);
            if (!"-".equals(owner.uid)) bx = chip(g2, bx, 38, "UID " + owner.uid, UI.ACCENT2);
            chip(g2, bx, 38, owner.server, UI.alpha(UI.TEXT_DIM, 200));

            // prawa strona: status + czas
            String status = on ? "ONLINE" : ago(owner.last);
            g2.setFont(new Font("Segoe UI", Font.BOLD, 12));
            fm = g2.getFontMetrics();
            int sw = fm.stringWidth(status);
            int sx = w - 16 - sw;
            g2.setColor(on ? UI.OK : UI.TEXT_DIM);
            g2.drawString(status, sx, 28);

            if (on) {
                float p = 0.5f + 0.5f * (float) Math.sin(blink);
                float rr = 4f + 3f * p;
                g2.setColor(UI.alpha(UI.OK, (int) (60 * (1 - p) + 30)));
                g2.fill(new Ellipse2D.Float(sx - 20 - rr, 24 - 4 - rr, rr * 2, rr * 2));
                g2.setColor(UI.OK);
                g2.fill(new Ellipse2D.Float(sx - 20 - 3.5f, 24 - 7.5f, 7, 7));
            }

            g2.setFont(new Font("Consolas", Font.PLAIN, 11));
            g2.setColor(UI.alpha(UI.TEXT_DIM, 190));
            String detail = (on ? "sesja " + dur(System.currentTimeMillis() - owner.sessionStart) + "  ·  " : "")
                    + owner.ip + "  ·  " + owner.beats + " beat";
            fm = g2.getFontMetrics();
            g2.drawString(detail, w - 16 - fm.stringWidth(detail), 48);

            g2.dispose();
        }

        int chip(Graphics2D g2, int x, int y, String text, Color c) {
            if (text == null || text.isBlank() || "-".equals(text)) return x;
            g2.setFont(new Font("Segoe UI", Font.BOLD, 10));
            FontMetrics fm = g2.getFontMetrics();
            int tw = fm.stringWidth(text), pw = tw + 16, ph = 17;
            g2.setColor(UI.alpha(c, 38));
            g2.fill(new RoundRectangle2D.Float(x, y - ph + 4, pw, ph, ph, ph));
            g2.setColor(UI.alpha(c, 110));
            g2.draw(new RoundRectangle2D.Float(x + 0.5f, y - ph + 4.5f, pw - 1, ph - 1, ph, ph));
            g2.setColor(c);
            g2.drawString(text, x + 8, y);
            return x + pw + 6;
        }
    }

    static String ago(long when) {
        if (when <= 0) return "nigdy";
        long s = (System.currentTimeMillis() - when) / 1000;
        if (s < 60) return s + " s temu";
        if (s < 3600) return (s / 60) + " min temu";
        if (s < 86400) return (s / 3600) + " h temu";
        return (s / 86400) + " dni temu";
    }

    static String dur(long ms) {
        long s = Math.max(0, ms / 1000);
        if (s < 60) return s + "s";
        if (s < 3600) return (s / 60) + "m " + (s % 60) + "s";
        return (s / 3600) + "h " + ((s % 3600) / 60) + "m";
    }

    /* ================================================================== */
    /* Strona: konta offline (nick + haslo)                                */
    /* ================================================================== */

    /**
     * Jedno konto wczytane z instancji. {@code premium=true} to konto Microsoft z {@code premium.txt} —
     * wtedy {@code pass} nie jest hasłem tylko statusem ("zapamiętane" / "brak tokenu").
     */
    static final class Acc {
        final String nick, pass, instance;
        final boolean premium;
        final boolean hasToken;

        Acc(String nick, String pass, String instance) {
            this(nick, pass, instance, false, false);
        }

        Acc(String nick, String pass, String instance, boolean premium, boolean hasToken) {
            this.nick = nick;
            this.pass = pass;
            this.instance = instance;
            this.premium = premium;
            this.hasToken = hasToken;
        }
    }

    /**
     * Szuka plikow {@code thunderhack/accounts.txt} we wszystkich instancjach Modrinth App i w
     * {@code .minecraft}, plus recznie wskazany plik. Format linii: {@code nick|haslo}; linia bez
     * kreski to stare konto bez hasla.
     */
    static List<Acc> findAccounts() {
        LinkedHashMap<String, Acc> out = new LinkedHashMap<>();
        List<Path> files = new ArrayList<>();
        List<String> labels = new ArrayList<>();

        if (!MODEL.accountsPath.isEmpty()) {
            Path p = Paths.get(MODEL.accountsPath);
            if (Files.exists(p)) {
                files.add(p);
                labels.add("wybrany plik");
            }
        }

        String appdata = System.getenv("APPDATA");
        if (appdata != null) {
            Path profiles = Paths.get(appdata, "ModrinthApp", "profiles");
            if (Files.isDirectory(profiles)) {
                try (var s = Files.list(profiles)) {
                    for (Path prof : s.toList()) {
                        Path f = prof.resolve("thunderhack").resolve("accounts.txt");
                        if (Files.exists(f)) {
                            files.add(f);
                            labels.add(prof.getFileName().toString());
                        }
                    }
                } catch (Exception ignored) {
                }
            }
            Path vanilla = Paths.get(appdata, ".minecraft", "thunderhack", "accounts.txt");
            if (Files.exists(vanilla)) {
                files.add(vanilla);
                labels.add(".minecraft");
            }
        }

        for (int i = 0; i < files.size(); i++) {
            try {
                for (String line : Files.readAllLines(files.get(i))) {
                    String s = line.trim();
                    if (s.isEmpty()) continue;
                    int bar = s.indexOf('|');
                    String nick = bar < 0 ? s : s.substring(0, bar).trim();
                    String pass = bar < 0 ? "" : s.substring(bar + 1);
                    if (nick.isEmpty()) continue;
                    // to samo konto w kilku instancjach: wygrywa to, ktore MA haslo
                    Acc prev = out.get(nick.toLowerCase());
                    if (prev == null || (prev.pass.isEmpty() && !pass.isEmpty()))
                        out.put(nick.toLowerCase(), new Acc(nick, pass, labels.get(i)));
                }
            } catch (Exception ignored) {
            }
        }

        // ---- konta premium (thunderhack/premium.txt: nick|uuid|refreshToken|lastMillis) ----
        // Klucz "premium:<nick>" żeby nie mieszać z kontami offline o tym samym nicku.
        if (appdata != null) {
            List<Path> premFiles = new ArrayList<>();
            List<String> premLabels = new ArrayList<>();
            Path profiles = Paths.get(appdata, "ModrinthApp", "profiles");
            if (Files.isDirectory(profiles)) {
                try (var s = Files.list(profiles)) {
                    for (Path prof : s.toList()) {
                        Path f = prof.resolve("thunderhack").resolve("premium.txt");
                        if (Files.exists(f)) {
                            premFiles.add(f);
                            premLabels.add(prof.getFileName().toString());
                        }
                    }
                } catch (Exception ignored) {
                }
            }
            Path vanillaPrem = Paths.get(appdata, ".minecraft", "thunderhack", "premium.txt");
            if (Files.exists(vanillaPrem)) {
                premFiles.add(vanillaPrem);
                premLabels.add(".minecraft");
            }

            for (int i = 0; i < premFiles.size(); i++) {
                try {
                    for (String line : Files.readAllLines(premFiles.get(i))) {
                        String s = line.trim();
                        if (s.isEmpty() || s.startsWith("#")) continue;
                        String[] p = s.split("\\|", -1);
                        if (p.length < 2 || p[0].isBlank()) continue;
                        boolean hasToken = p.length >= 3 && !p[2].isBlank();
                        String statusTxt = hasToken ? "zapamiętane · logowanie bez hasła" : "brak tokenu · zaloguj przez Microsoft";
                        String key = "premium:" + p[0].toLowerCase();
                        Acc prev = out.get(key);
                        // wygrywa wpis z tokenem
                        if (prev == null || (!prev.hasToken && hasToken))
                            out.put(key, new Acc(p[0], statusTxt, premLabels.get(i), true, hasToken));
                    }
                } catch (Exception ignored) {
                }
            }
        }

        // premium na górze listy
        List<Acc> result = new ArrayList<>();
        for (Acc a : out.values()) if (a.premium) result.add(a);
        for (Acc a : out.values()) if (!a.premium) result.add(a);
        return result;
    }

    static class AccountsPage extends JPanel {
        final JPanel list = new JPanel();
        final JTextField search = new JTextField();
        final JLabel info = new JLabel(" ");
        List<Acc> loaded = new ArrayList<>();

        AccountsPage() {
            setOpaque(false);
            setLayout(new BorderLayout(0, 12));
            setBorder(BorderFactory.createEmptyBorder(8, 2, 0, 2));

            UI.RoundPanel head = new UI.RoundPanel(16, UI.CARD);
            head.setLayout(new BorderLayout(12, 0));
            head.setBorder(BorderFactory.createEmptyBorder(12, 16, 12, 16));
            head.setPreferredSize(new Dimension(10, 74));

            JPanel left = new JPanel();
            left.setOpaque(false);
            left.setLayout(new BoxLayout(left, BoxLayout.Y_AXIS));
            JLabel t = new JLabel("TWOJE KONTA OFFLINE");
            t.setForeground(UI.TEXT_DIM);
            t.setFont(new Font("Segoe UI", Font.BOLD, 10));
            t.setAlignmentX(LEFT_ALIGNMENT);
            info.setForeground(UI.TEXT);
            info.setFont(new Font("Segoe UI", Font.BOLD, 15));
            info.setAlignmentX(LEFT_ALIGNMENT);
            left.add(t);
            left.add(Box.createVerticalStrut(4));
            left.add(info);
            head.add(left, BorderLayout.WEST);

            JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 6));
            right.setOpaque(false);
            search.setForeground(UI.TEXT);
            search.setCaretColor(UI.ACCENT);
            search.setBackground(new Color(14, 12, 20));
            search.setFont(new Font("Segoe UI", Font.PLAIN, 13));
            search.setPreferredSize(new Dimension(190, 32));
            search.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(UI.BORDER, 1, true),
                    BorderFactory.createEmptyBorder(4, 10, 4, 10)));
            search.addKeyListener(new KeyAdapter() {
                public void keyReleased(KeyEvent e) {
                    rebuild();
                }
            });
            UI.FlatButton refresh = new UI.FlatButton("Odśwież", UI.ACCENT2);
            refresh.setPreferredSize(new Dimension(112, 32));
            refresh.addActionListener(e -> reload());
            UI.FlatButton pick = new UI.FlatButton("Wskaż plik", UI.CARD_BTN);
            pick.setPreferredSize(new Dimension(120, 32));
            pick.addActionListener(e -> {
                JFileChooser fc = new JFileChooser();
                fc.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("accounts.txt", "txt"));
                if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
                    MODEL.accountsPath = fc.getSelectedFile().getAbsolutePath();
                    MODEL.save();
                    reload();
                }
            });
            right.add(search);
            right.add(refresh);
            right.add(pick);
            head.add(right, BorderLayout.EAST);
            add(head, BorderLayout.NORTH);

            list.setOpaque(false);
            list.setLayout(new BoxLayout(list, BoxLayout.Y_AXIS));
            JScrollPane sp = new JScrollPane(list);
            sp.setOpaque(false);
            sp.getViewport().setOpaque(false);
            sp.setBorder(null);
            sp.getVerticalScrollBar().setUnitIncrement(18);
            sp.getVerticalScrollBar().setPreferredSize(new Dimension(8, 0));
            add(sp, BorderLayout.CENTER);

            reload();
        }

        void reload() {
            loaded = findAccounts();
            int withPass = 0, prem = 0;
            for (Acc a : loaded) {
                if (a.premium) prem++;
                else if (!a.pass.isEmpty()) withPass++;
            }
            info.setText(loaded.isEmpty()
                    ? "Nie znalazlem zadnych kont"
                    : (loaded.size() - prem) + " offline (" + withPass + " z hasłem)  ·  " + prem + " premium");
            rebuild();
        }

        void rebuild() {
            String q = search.getText().trim().toLowerCase();
            list.removeAll();
            int shown = 0;
            for (Acc a : loaded) {
                if (!q.isEmpty() && !a.nick.toLowerCase().contains(q)) continue;
                list.add(new AccountRow(a));
                list.add(Box.createVerticalStrut(8));
                shown++;
            }
            if (shown == 0) list.add(new AccountsEmpty(loaded.isEmpty()));
            list.revalidate();
            list.repaint();
        }
    }

    static class AccountsEmpty extends JComponent {
        final boolean nothingFound;

        AccountsEmpty(boolean nothingFound) {
            this.nothingFound = nothingFound;
            setPreferredSize(new Dimension(10, 200));
        }

        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            int w = getWidth(), h = getHeight();
            g2.setColor(UI.TEXT_DIM);
            g2.setFont(new Font("Segoe UI", Font.BOLD, 15));
            String t = nothingFound ? "Brak zapisanych kont" : "Nic nie pasuje do wyszukiwania";
            g2.drawString(t, (w - g2.getFontMetrics().stringWidth(t)) / 2, h / 2);
            if (nothingFound) {
                g2.setFont(new Font("Segoe UI", Font.PLAIN, 12));
                g2.setColor(UI.alpha(UI.TEXT_DIM, 150));
                String t2 = "Dodaj konto w grze (menu -> ACCOUNTS) albo wskaz plik accounts.txt";
                g2.drawString(t2, (w - g2.getFontMetrics().stringWidth(t2)) / 2, h / 2 + 22);
            }
            g2.dispose();
        }
    }

    /** Wiersz konta: awatar, nick i HASLO JAWNIE — po to, zeby dalo sie je odczytac po zapomnieniu. */
    static class AccountRow extends JComponent {
        final Acc acc;
        float hover = 0;
        String flash = null;
        long flashUntil = 0;

        AccountRow(Acc acc) {
            this.acc = acc;
            setPreferredSize(new Dimension(10, 64));
            setMaximumSize(new Dimension(Integer.MAX_VALUE, 64));
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setToolTipText("Klik = kopiuj haslo, prawy przycisk = wiecej");

            new Timer(16, e -> {
                float tgt = getMousePosition() != null ? 1f : 0f;
                if (Math.abs(tgt - hover) > 0.001f || flash != null) {
                    hover += (tgt - hover) * 0.18f;
                    if (flash != null && System.currentTimeMillis() > flashUntil) flash = null;
                    repaint();
                }
            }).start();

            setToolTipText(acc.premium
                    ? "Konto premium — wejdź na nie w grze (menu ACCOUNTS)"
                    : "Klik = kopiuj hasło, prawy przycisk = więcej");

            addMouseListener(new MouseAdapter() {
                public void mouseClicked(MouseEvent e) {
                    if (SwingUtilities.isRightMouseButton(e)) {
                        JPopupMenu m = new JPopupMenu();
                        JMenuItem a = new JMenuItem("Kopiuj nick");
                        a.addActionListener(x -> copy(acc.nick, "nick skopiowany"));
                        m.add(a);
                        if (!acc.premium) {
                            JMenuItem b = new JMenuItem("Kopiuj hasło");
                            b.addActionListener(x -> copy(acc.pass, "hasło skopiowane"));
                            b.setEnabled(!acc.pass.isEmpty());
                            JMenuItem c = new JMenuItem("Kopiuj /login <hasło>");
                            c.addActionListener(x -> copy("/login " + acc.pass, "komenda skopiowana"));
                            c.setEnabled(!acc.pass.isEmpty());
                            m.add(b);
                            m.add(c);
                        }
                        m.show(AccountRow.this, e.getX(), e.getY());
                    } else if (acc.premium) {
                        copy(acc.nick, "nick skopiowany");
                    } else if (!acc.pass.isEmpty()) {
                        copy(acc.pass, "hasło skopiowane");
                    } else {
                        copy(acc.nick, "nick skopiowany");
                    }
                }
            });
        }

        void copy(String s, String msg) {
            UserCard.copy(s);
            flash = msg;
            flashUntil = System.currentTimeMillis() + 1400;
            repaint();
        }

        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            int w = getWidth(), h = getHeight();
            Color accent = acc.premium ? new Color(90, 170, 255) : UI.GOLD;

            RoundRectangle2D r = new RoundRectangle2D.Float(0, 0, w, h, 14, 14);
            g2.setPaint(new GradientPaint(0, 0, UI.lighten(UI.CARD, (int) (10 + 14 * hover)), 0, h, UI.CARD));
            g2.fill(r);
            Shape old = g2.getClip();
            g2.setClip(r);
            // pasek akcentu po lewej — premium na niebiesko
            g2.setPaint(new GradientPaint(0, 0, UI.alpha(accent, 200), 3, 0, UI.alpha(accent, 50)));
            g2.fillRect(0, 0, 3, h);
            if (hover > 0.01f) {
                g2.setPaint(new GradientPaint(0, 0, UI.alpha(accent, (int) (30 * hover)), w, 0, UI.alpha(accent, 0)));
                g2.fillRect(0, 0, w, h);
            }
            g2.setClip(old);
            g2.setColor(UI.alpha(Color.WHITE, (int) (30 + 40 * hover)));
            g2.draw(new RoundRectangle2D.Float(0.5f, 0.5f, w - 1, h - 1, 14, 14));

            int av = 40, ax = 12, ay = (h - av) / 2;
            int hash = acc.nick.hashCode();
            Color c1 = Color.getHSBColor(((hash >>> 8) % 360) / 360f, 0.55f, 0.85f);
            Color c2 = Color.getHSBColor(((hash >>> 16) % 360) / 360f, 0.60f, 0.55f);
            RoundRectangle2D avr = new RoundRectangle2D.Float(ax, ay, av, av, 12, 12);
            g2.setPaint(new GradientPaint(ax, ay, c1, ax + av, ay + av, c2));
            g2.fill(avr);
            g2.setColor(UI.alpha(Color.WHITE, 60));
            g2.draw(avr);
            String ini = acc.nick.substring(0, Math.min(2, acc.nick.length())).toUpperCase();
            g2.setFont(new Font("Segoe UI", Font.BOLD, 16));
            FontMetrics fm = g2.getFontMetrics();
            g2.setColor(new Color(15, 12, 20, 220));
            g2.drawString(ini, ax + (av - fm.stringWidth(ini)) / 2, ay + (av + fm.getAscent() - fm.getDescent()) / 2);

            int tx = ax + av + 14;
            g2.setColor(UI.TEXT);
            g2.setFont(new Font("Segoe UI", Font.BOLD, 16));
            g2.drawString(acc.nick, tx, 27);
            int nickW = g2.getFontMetrics().stringWidth(acc.nick);

            // plakietka PREMIUM tuż za nickiem
            if (acc.premium) {
                g2.setFont(new Font("Segoe UI", Font.BOLD, 9));
                FontMetrics bm = g2.getFontMetrics();
                String badge = "PREMIUM";
                int bw = bm.stringWidth(badge) + 12;
                g2.setColor(UI.alpha(accent, 45));
                g2.fill(new RoundRectangle2D.Float(tx + nickW + 8, 12, bw, 15, 15, 15));
                g2.setColor(accent);
                g2.drawString(badge, tx + nickW + 8 + 6, 23);
            }

            // druga linia: dla offline HASŁO jawnie, dla premium status tokenu
            if (acc.premium) {
                g2.setFont(new Font("Segoe UI", Font.BOLD, 12));
                g2.setColor(acc.hasToken ? new Color(150, 200, 255) : UI.TEXT_DIM);
                g2.drawString(acc.pass, tx, 48);
            } else {
                boolean has = !acc.pass.isEmpty();
                g2.setFont(new Font("Consolas", Font.BOLD, 14));
                g2.setColor(has ? UI.GOLD : UI.TEXT_DIM);
                g2.drawString(has ? acc.pass : "brak hasła", tx, 48);
            }

            // prawa strona: instancja + potwierdzenie kopiowania
            g2.setFont(new Font("Segoe UI", Font.BOLD, 10));
            fm = g2.getFontMetrics();
            String inst = acc.instance;
            int iw = fm.stringWidth(inst) + 16;
            g2.setColor(UI.alpha(UI.ACCENT, 38));
            g2.fill(new RoundRectangle2D.Float(w - 16 - iw, 16, iw, 17, 17, 17));
            g2.setColor(UI.ACCENT);
            g2.drawString(inst, w - 16 - iw + 8, 28);

            String hint = flash != null ? flash
                    : (hover > 0.4f ? (acc.premium ? "klik = kopiuj nick" : (!acc.pass.isEmpty() ? "klik = kopiuj hasło" : "klik = kopiuj nick")) : null);
            if (hint != null) {
                g2.setFont(new Font("Segoe UI", Font.BOLD, 11));
                fm = g2.getFontMetrics();
                g2.setColor(flash != null ? UI.OK : UI.TEXT_DIM);
                g2.drawString(hint, w - 16 - fm.stringWidth(hint), 48);
            }
            g2.dispose();
        }
    }

    /* ================================================================== */
    /* Strona: mod + serwer                                                */
    /* ================================================================== */

    static class ModPage extends JPanel {
        final DropZone drop = new DropZone(this::setMod);
        final JLabel fileName = new JLabel("Brak pliku");
        final JLabel fileMeta = new JLabel(" ");
        final JTextField portField = new JTextField(String.valueOf(MODEL.port));
        final JLabel status = new JLabel("SERWER WYLACZONY");
        final JLabel urlLabel = new JLabel(" ");
        final UI.FlatButton toggle = new UI.FlatButton("Włącz serwer", UI.ACCENT);
        final JTextArea snippet = new JTextArea();

        ModPage() {
            setOpaque(false);
            setLayout(new BorderLayout(0, 12));
            setBorder(BorderFactory.createEmptyBorder(8, 2, 0, 2));

            // ---- karta pliku ----
            UI.RoundPanel fileCard = new UI.RoundPanel(18, UI.CARD);
            fileCard.setLayout(new BorderLayout(14, 0));
            fileCard.setBorder(BorderFactory.createEmptyBorder(16, 18, 16, 18));
            fileCard.setPreferredSize(new Dimension(10, 200));
            drop.setPreferredSize(new Dimension(330, 10));
            fileCard.add(drop, BorderLayout.WEST);

            JPanel info = new JPanel();
            info.setOpaque(false);
            info.setLayout(new BoxLayout(info, BoxLayout.Y_AXIS));
            JLabel h = new JLabel("PLIK MODA");
            h.setForeground(UI.TEXT_DIM);
            h.setFont(new Font("Segoe UI", Font.BOLD, 10));
            fileName.setForeground(UI.TEXT);
            fileName.setFont(new Font("Segoe UI", Font.BOLD, 17));
            fileMeta.setForeground(UI.TEXT_DIM);
            fileMeta.setFont(UI.MONO);
            info.add(h);
            info.add(Box.createVerticalStrut(6));
            info.add(fileName);
            info.add(Box.createVerticalStrut(6));
            info.add(fileMeta);
            info.add(Box.createVerticalStrut(14));

            UI.FlatButton pick = new UI.FlatButton("Wybierz plik", UI.ACCENT2);
            pick.setPreferredSize(new Dimension(170, 38));
            pick.setMaximumSize(new Dimension(170, 38));
            pick.setAlignmentX(LEFT_ALIGNMENT);
            pick.addActionListener(e -> {
                JFileChooser fc = new JFileChooser(new File("..").getAbsoluteFile());
                fc.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("Mod (*.jar)", "jar"));
                if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION)
                    setMod(fc.getSelectedFile());
            });
            info.add(pick);
            fileCard.add(info, BorderLayout.CENTER);
            add(fileCard, BorderLayout.NORTH);

            // ---- karta serwera ----
            UI.RoundPanel srv = new UI.RoundPanel(18, UI.CARD);
            srv.setLayout(new BorderLayout(0, 10));
            srv.setBorder(BorderFactory.createEmptyBorder(16, 18, 18, 18));

            JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 12, 0));
            row.setOpaque(false);
            JLabel pl = new JLabel("PORT");
            pl.setForeground(UI.TEXT_DIM);
            pl.setFont(new Font("Segoe UI", Font.BOLD, 11));
            portField.setForeground(UI.TEXT);
            portField.setCaretColor(UI.ACCENT);
            portField.setBackground(new Color(14, 12, 20));
            portField.setFont(new Font("Consolas", Font.BOLD, 15));
            portField.setPreferredSize(new Dimension(90, 34));
            portField.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(UI.BORDER, 1, true),
                    BorderFactory.createEmptyBorder(4, 8, 4, 8)));
            toggle.setPreferredSize(new Dimension(200, 38));
            toggle.addActionListener(e -> toggleServer());
            status.setFont(new Font("Segoe UI", Font.BOLD, 12));
            status.setForeground(UI.TEXT_DIM);
            row.add(pl);
            row.add(portField);
            row.add(toggle);
            row.add(status);
            srv.add(row, BorderLayout.NORTH);

            JPanel mid = new JPanel();
            mid.setOpaque(false);
            mid.setLayout(new BoxLayout(mid, BoxLayout.Y_AXIS));
            mid.setBorder(BorderFactory.createEmptyBorder(12, 2, 0, 2));
            JLabel ul = new JLabel("LINK DO POBRANIA MODA");
            ul.setForeground(UI.TEXT_DIM);
            ul.setFont(new Font("Segoe UI", Font.BOLD, 10));
            ul.setAlignmentX(LEFT_ALIGNMENT);
            urlLabel.setForeground(UI.ACCENT);
            urlLabel.setFont(new Font("Consolas", Font.BOLD, 14));
            urlLabel.setAlignmentX(LEFT_ALIGNMENT);
            urlLabel.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            urlLabel.addMouseListener(new MouseAdapter() {
                public void mouseClicked(MouseEvent e) {
                    UserCard.copy(urlLabel.getText());
                    status.setText("SKOPIOWANO LINK");
                }
            });
            JLabel sl = new JLabel("WKLEJ GRACZOM DO  config/thunderhack/watch.txt");
            sl.setForeground(UI.TEXT_DIM);
            sl.setFont(new Font("Segoe UI", Font.BOLD, 10));
            sl.setAlignmentX(LEFT_ALIGNMENT);
            sl.setBorder(BorderFactory.createEmptyBorder(14, 0, 4, 0));
            snippet.setEditable(false);
            snippet.setForeground(UI.OK);
            snippet.setBackground(new Color(12, 11, 17));
            snippet.setFont(new Font("Consolas", Font.BOLD, 14));
            snippet.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(UI.BORDER, 1, true),
                    BorderFactory.createEmptyBorder(8, 10, 8, 10)));
            snippet.setAlignmentX(LEFT_ALIGNMENT);
            snippet.setMaximumSize(new Dimension(Integer.MAX_VALUE, 40));
            mid.add(ul);
            mid.add(urlLabel);
            mid.add(sl);
            mid.add(snippet);
            srv.add(mid, BorderLayout.CENTER);
            add(srv, BorderLayout.CENTER);

            if (!MODEL.modPath.isEmpty()) setMod(new File(MODEL.modPath));
            updateUrl();
            new Timer(1000, e -> updateStatus()).start();
        }

        void setMod(File f) {
            if (f == null || !f.isFile()) return;
            MODEL.modPath = f.getAbsolutePath();
            fileName.setText(f.getName());
            String sha = sha256(f);
            fileMeta.setText(String.format("%.2f MB   ·   %s   ·   sha256 %s",
                    f.length() / 1048576.0,
                    new SimpleDateFormat("dd.MM.yyyy HH:mm").format(new Date(f.lastModified())),
                    sha.substring(0, Math.min(16, sha.length()))));
            drop.loaded = true;
            drop.repaint();
            MODEL.save();
        }

        void toggleServer() {
            if (MODEL.server != null) {
                stopServer();
            } else {
                MODEL.port = parseInt(portField.getText(), 7331);
                try {
                    startServer();
                } catch (IOException ex) {
                    status.setText("BLAD: " + ex.getMessage());
                    status.setForeground(UI.DANGER);
                    return;
                }
                MODEL.save();
            }
            updateUrl();
            updateStatus();
        }

        void updateUrl() {
            urlLabel.setText("http://" + localIp() + ":" + MODEL.port + "/mod.jar");
            snippet.setText("http://" + localIp() + ":" + MODEL.port);
        }

        void updateStatus() {
            boolean on = MODEL.server != null;
            toggle.setText(on ? "Zatrzymaj serwer" : "Włącz serwer");
            if (status.getText().startsWith("BLAD") || status.getText().startsWith("SKOPIOWANO")) return;
            status.setText(on ? "SERWER DZIALA  ·  " + MODEL.onlineCount() + " online" : "SERWER WYLACZONY");
            status.setForeground(on ? UI.OK : UI.TEXT_DIM);
        }

        static String sha256(File f) {
            try {
                MessageDigest md = MessageDigest.getInstance("SHA-256");
                try (InputStream in = new FileInputStream(f)) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
                }
                StringBuilder sb = new StringBuilder();
                for (byte b : md.digest()) sb.append(String.format("%02x", b));
                return sb.toString();
            } catch (Exception e) {
                return "-";
            }
        }
    }

    /** Pole "przeciagnij tu jar-a" z animowana ramka. */
    static class DropZone extends JComponent {
        final java.util.function.Consumer<File> onFile;
        boolean loaded = false, over = false;
        float dash = 0, glow = 0;

        DropZone(java.util.function.Consumer<File> onFile) {
            this.onFile = onFile;
            setOpaque(false);
            new Timer(16, e -> {
                dash += 0.35f;
                float tgt = over ? 1f : 0f;
                glow += (tgt - glow) * 0.2f;
                repaint();
            }).start();

            new DropTarget(this, new DropTargetAdapter() {
                public void dragEnter(DropTargetDragEvent e) {
                    over = true;
                }

                public void dragExit(DropTargetEvent e) {
                    over = false;
                }

                @SuppressWarnings("unchecked")
                public void drop(DropTargetDropEvent e) {
                    over = false;
                    try {
                        e.acceptDrop(DnDConstants.ACTION_COPY);
                        List<File> files = (List<File>) e.getTransferable()
                                .getTransferData(DataFlavor.javaFileListFlavor);
                        for (File f : files)
                            if (f.getName().toLowerCase().endsWith(".jar")) {
                                onFile.accept(f);
                                break;
                            }
                    } catch (Exception ignored) {
                    }
                    repaint();
                }
            });
        }

        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            int w = getWidth(), h = getHeight();
            Color c = loaded ? UI.OK : UI.ACCENT;

            RoundRectangle2D r = new RoundRectangle2D.Float(1, 1, w - 2, h - 2, 16, 16);
            g2.setPaint(new RadialGradientPaint(new Point2D.Float(w / 2f, h / 2f), Math.max(w, h) / 1.6f,
                    new float[]{0f, 1f},
                    new Color[]{UI.alpha(c, (int) (18 + 34 * glow)), UI.alpha(c, 0)}));
            g2.fill(r);

            g2.setStroke(new BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND,
                    10f, new float[]{7f, 7f}, dash));
            g2.setColor(UI.alpha(c, (int) (110 + 110 * glow)));
            g2.draw(r);

            // ikona strzalki w dol do "kosza"
            int cx = w / 2, cy = h / 2 - 12;
            g2.setStroke(new BasicStroke(2.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g2.setColor(UI.alpha(c, 220));
            g2.drawLine(cx, cy - 16, cx, cy + 8);
            g2.drawLine(cx - 9, cy - 1, cx, cy + 8);
            g2.drawLine(cx + 9, cy - 1, cx, cy + 8);
            g2.drawArc(cx - 18, cy + 2, 36, 22, 200, 140);

            g2.setFont(new Font("Segoe UI", Font.BOLD, 13));
            g2.setColor(loaded ? UI.OK : UI.TEXT);
            String t = loaded ? "MOD ZALADOWANY" : "PRZECIAGNIJ TU .JAR";
            FontMetrics fm = g2.getFontMetrics();
            g2.drawString(t, cx - fm.stringWidth(t) / 2, cy + 48);

            g2.setFont(new Font("Segoe UI", Font.PLAIN, 11));
            g2.setColor(UI.TEXT_DIM);
            String t2 = "np. build/libs/GombalBeta-1.7-beta.jar";
            fm = g2.getFontMetrics();
            g2.drawString(t2, cx - fm.stringWidth(t2) / 2, cy + 68);
            g2.dispose();
        }
    }
}
