import javax.swing.*;
import java.awt.*;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;

/**
 * The STATUS page of the GlobalDLC App: is the game actually going to load the build you just made?
 *
 * <p>Three places can hold the client - the fresh build, the Modrinth profile and the copy baked into the
 * injector - and every "nothing changed" so far was one of them being stale, or two copies sitting in the
 * mods folder at once. This page compares all three every few seconds and offers the one-click fix next to
 * each problem.
 *
 * <p>Copies are compared by a fingerprint of the jar's entries (name + CRC from the central directory, no
 * unpacking), skipping {@code uid.txt}: a UID-stamped jar is still "the same build" as the one it came from.
 */
public final class StatusPage extends JPanel {

    private static final String[] OURS = {"thunderhack", "gombal", "cloudan", "globaldlc"};
    private static final SimpleDateFormat TIME = new SimpleDateFormat("dd.MM HH:mm:ss");

    private final Path modsDir;
    private final Runnable pushLatest;
    private final Consumer<String> say;

    private final JPanel buildRows = rows();
    private final JPanel modRows = rows();
    private final JPanel injRows = rows();
    private final JLabel headline = new JLabel(" ");
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "globaldlc-status");
        t.setDaemon(true);
        return t;
    });
    private final Map<Path, long[]> fpStamp = new HashMap<>();
    private final Map<Path, String> fpCache = new HashMap<>();
    private volatile boolean busy;

    public StatusPage(Path modsDir, Runnable pushLatest, Consumer<String> say) {
        this.modsDir = modsDir;
        this.pushLatest = pushLatest;
        this.say = say;
        setOpaque(false);
        setLayout(new BorderLayout(0, 12));

        headline.setFont(new Font("Segoe UI", Font.BOLD, 15));
        headline.setForeground(UI.TEXT);
        headline.setBorder(BorderFactory.createEmptyBorder(0, 4, 0, 0));
        add(headline, BorderLayout.NORTH);

        JPanel cards = new JPanel(new GridLayout(1, 3, 14, 0));
        cards.setOpaque(false);
        cards.add(card("KLIENT  ·  BUILD", buildRows,
                button("Folder build", () -> open(Paths.get("..", "build", "libs")))));
        cards.add(card("MODRINTH  ·  Fabric 1.21.11", modRows,
                glow("WGRAJ AKTUALNY", () -> {
                    pushLatest.run();
                    refresh();
                }),
                pair(button("Usun duplikaty", this::removeDuplicates), button("Otworz mods", () -> open(modsDir))),
                button("Uruchom Modrinth", this::launchModrinth)));
        cards.add(card("INJECTOR", injRows,
                button("Przebuduj injector", this::rebuildInjector)));
        add(cards, BorderLayout.CENTER);

        new javax.swing.Timer(3000, e -> refresh()).start();
        refresh();
    }

    /* ------------------------------------------------------------------ */
    /* layout                                                              */
    /* ------------------------------------------------------------------ */

    private static JPanel rows() {
        JPanel p = new JPanel();
        p.setOpaque(false);
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        return p;
    }

    private JComponent card(String title, JPanel rows, JComponent... buttons) {
        UI.GlassPanel card = new UI.GlassPanel(20, 18);
        card.setLayout(new BorderLayout(0, 10));
        card.setBorder(BorderFactory.createEmptyBorder(14, 14, 14, 14));

        JLabel caption = new JLabel(title);
        caption.setFont(UI.tracked(new Font("Segoe UI", Font.BOLD, 11), 0.14f));
        caption.setForeground(UI.TEXT_DIM);
        card.add(caption, BorderLayout.NORTH);

        JScrollPane scroll = new JScrollPane(rows);
        scroll.setOpaque(false);
        scroll.getViewport().setOpaque(false);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        styleScroll(scroll);
        card.add(scroll, BorderLayout.CENTER);

        JPanel col = new JPanel();
        col.setOpaque(false);
        col.setLayout(new BoxLayout(col, BoxLayout.Y_AXIS));
        for (JComponent b : buttons) {
            b.setAlignmentX(Component.LEFT_ALIGNMENT);
            b.setMaximumSize(new Dimension(Integer.MAX_VALUE, 38));
            col.add(b);
            col.add(Box.createRigidArea(new Dimension(0, 8)));
        }
        card.add(col, BorderLayout.SOUTH);
        return card;
    }

    private static JComponent button(String text, Runnable action) {
        UI.GhostButton b = new UI.GhostButton(text);
        b.addActionListener(e -> action.run());
        return b;
    }

    private static JComponent pair(JComponent a, JComponent b) {
        JPanel p = new JPanel(new GridLayout(1, 2, 8, 0));
        p.setOpaque(false);
        p.add(a);
        p.add(b);
        return p;
    }

    /**
     * Thin rounded accent thumb on an invisible track, no arrow buttons - the default Windows scrollbar
     * looked like a stray widget on the glass cards. Shared with the UID list.
     */
    static void styleScroll(JScrollPane scroll) {
        scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        JScrollBar bar = scroll.getVerticalScrollBar();
        bar.setOpaque(false);
        bar.setPreferredSize(new Dimension(8, 0));
        bar.setUnitIncrement(16);
        bar.setUI(new javax.swing.plaf.basic.BasicScrollBarUI() {
            private boolean hover;

            @Override
            protected void installListeners() {
                super.installListeners();
                scrollbar.addMouseListener(new java.awt.event.MouseAdapter() {
                    @Override
                    public void mouseEntered(java.awt.event.MouseEvent e) {
                        hover = true;
                        scrollbar.repaint();
                    }

                    @Override
                    public void mouseExited(java.awt.event.MouseEvent e) {
                        hover = false;
                        scrollbar.repaint();
                    }
                });
            }

            @Override
            protected JButton createDecreaseButton(int orientation) {
                return zero();
            }

            @Override
            protected JButton createIncreaseButton(int orientation) {
                return zero();
            }

            private JButton zero() {
                JButton b = new JButton();
                b.setPreferredSize(new Dimension(0, 0));
                b.setMinimumSize(new Dimension(0, 0));
                b.setMaximumSize(new Dimension(0, 0));
                return b;
            }

            @Override
            protected void paintTrack(Graphics g, JComponent c, Rectangle r) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(new Color(255, 255, 255, 10));
                g2.fillRoundRect(r.x + 2, r.y, r.width - 4, r.height, 4, 4);
                g2.dispose();
            }

            @Override
            protected void paintThumb(Graphics g, JComponent c, Rectangle r) {
                if (r.isEmpty()) return;
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                Color a = UI.ACCENT;
                g2.setPaint(new GradientPaint(0, r.y, new Color(a.getRed(), a.getGreen(), a.getBlue(), hover || isDragging ? 230 : 150),
                        0, r.y + r.height, new Color(UI.ACCENT2.getRed(), UI.ACCENT2.getGreen(), UI.ACCENT2.getBlue(), hover || isDragging ? 230 : 150)));
                g2.fillRoundRect(r.x + 1, r.y + 1, r.width - 2, r.height - 2, r.width - 2, r.width - 2);
                g2.dispose();
            }
        });
    }

    private static JComponent glow(String text, Runnable action) {
        UI.GlowButton b = new UI.GlowButton(text, UI.ACCENT, UI.ACCENT2);
        b.setPreferredSize(new Dimension(10, 40));
        b.addActionListener(e -> action.run());
        return b;
    }

    private static JComponent line(String text, Color colour, boolean bold) {
        JLabel l = new JLabel(text);
        l.setFont(new Font(bold ? "Segoe UI Semibold" : "Segoe UI", bold ? Font.BOLD : Font.PLAIN, bold ? 13 : 12));
        l.setForeground(colour);
        l.setAlignmentX(Component.LEFT_ALIGNMENT);
        l.setBorder(BorderFactory.createEmptyBorder(2, 0, 2, 0));
        return l;
    }

    private static JComponent chip(String text, Color colour) {
        JLabel l = new JLabel(text) {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(new Color(colour.getRed(), colour.getGreen(), colour.getBlue(), 50));
                g2.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 12, 12);
                g2.setColor(colour);
                g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 12, 12);
                g2.dispose();
                super.paintComponent(g);
            }
        };
        l.setFont(new Font("Segoe UI", Font.BOLD, 10));
        l.setForeground(colour);
        l.setBorder(BorderFactory.createEmptyBorder(2, 8, 2, 8));
        JPanel wrap = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 2));
        wrap.setOpaque(false);
        wrap.setAlignmentX(Component.LEFT_ALIGNMENT);
        wrap.add(l);
        wrap.setMaximumSize(new Dimension(Integer.MAX_VALUE, 26));
        return wrap;
    }

    private static Component gap(int h) {
        Component c = Box.createRigidArea(new Dimension(0, h));
        ((JComponent) c).setAlignmentX(Component.LEFT_ALIGNMENT);
        return c;
    }

    /* ------------------------------------------------------------------ */
    /* state                                                               */
    /* ------------------------------------------------------------------ */

    private record JarInfo(Path path, long modified, long size, String fp) {
    }

    private record Snapshot(JarInfo build, List<JarInfo> mods, JarInfo injector, boolean modsExists) {
    }

    /** Recomputes off the UI thread; overlapping ticks are skipped rather than queued. */
    public void refresh() {
        if (busy) return;
        busy = true;
        worker.submit(() -> {
            Snapshot s;
            try {
                s = scan();
            } catch (Throwable t) {
                s = null;
            }
            Snapshot fin = s;
            SwingUtilities.invokeLater(() -> {
                busy = false;
                if (fin != null) show(fin);
            });
        });
    }

    private Snapshot scan() throws IOException {
        JarInfo build = null;
        for (Path libs : new Path[]{Paths.get("..", "build", "libs"), Paths.get("build", "libs")}) {
            List<Path> jars = ourJars(libs);
            if (!jars.isEmpty()) {
                build = info(jars.get(0));
                break;
            }
        }
        List<JarInfo> mods = new ArrayList<>();
        for (Path p : ourJars(modsDir)) mods.add(info(p));

        JarInfo inj = null;
        Path injector = Paths.get("ThunderInjector.jar");
        if (Files.isRegularFile(injector)) inj = embeddedInfo(injector);
        return new Snapshot(build, mods, inj, Files.isDirectory(modsDir));
    }

    /** Our jars in a folder (no -sources), newest first. */
    private static List<Path> ourJars(Path folder) throws IOException {
        if (!Files.isDirectory(folder)) return List.of();
        try (Stream<Path> files = Files.list(folder)) {
            return files.filter(p -> {
                        String n = p.getFileName().toString().toLowerCase();
                        if (!n.endsWith(".jar") || n.contains("sources")) return false;
                        for (String o : OURS) if (n.startsWith(o)) return true;
                        return false;
                    })
                    .sorted(Comparator.comparingLong((Path p) -> p.toFile().lastModified()).reversed())
                    .toList();
        }
    }

    private JarInfo info(Path p) {
        File f = p.toFile();
        return new JarInfo(p, f.lastModified(), f.length(), fingerprint(p, f.lastModified(), f.length()));
    }

    /** The client.jar packed inside the injector, fingerprinted through a temp copy (cached by mtime). */
    private JarInfo embeddedInfo(Path injector) {
        File f = injector.toFile();
        long[] stamp = fpStamp.get(injector);
        if (stamp != null && stamp[0] == f.lastModified() && stamp[1] == f.length())
            return new JarInfo(injector, f.lastModified(), f.length(), fpCache.get(injector));
        String fp = null;
        try (JarFile jf = new JarFile(f)) {
            JarEntry e = jf.getJarEntry("client.jar");
            if (e != null) {
                Path tmp = Files.createTempFile("globaldlc-inj", ".jar");
                try (InputStream in = jf.getInputStream(e)) {
                    Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
                }
                fp = entriesFingerprint(tmp);
                Files.deleteIfExists(tmp);
            }
        } catch (IOException ignored) {
        }
        fpStamp.put(injector, new long[]{f.lastModified(), f.length()});
        fpCache.put(injector, fp);
        return new JarInfo(injector, f.lastModified(), f.length(), fp);
    }

    private String fingerprint(Path p, long modified, long size) {
        long[] stamp = fpStamp.get(p);
        if (stamp != null && stamp[0] == modified && stamp[1] == size) return fpCache.get(p);
        String fp = entriesFingerprint(p);
        fpStamp.put(p, new long[]{modified, size});
        fpCache.put(p, fp);
        return fp;
    }

    /** Name + CRC of every entry except the UID stamp, in name order. Null when the jar can't be read. */
    static String entriesFingerprint(Path jar) {
        try (JarFile jf = new JarFile(jar.toFile())) {
            List<JarEntry> entries = Collections.list(jf.entries());
            entries.sort(Comparator.comparing(JarEntry::getName));
            long h = 1125899906842597L;
            for (JarEntry e : entries) {
                String n = e.getName();
                if (e.isDirectory() || n.equals("uid.txt") || n.equals("uid_avatar.png")) continue;
                h = 31 * h + n.hashCode();
                h = 31 * h + e.getCrc();
            }
            return Long.toHexString(h);
        } catch (IOException e) {
            return null;
        }
    }

    /* ------------------------------------------------------------------ */
    /* render                                                              */
    /* ------------------------------------------------------------------ */

    private void show(Snapshot s) {
        String buildFp = s.build() == null ? null : s.build().fp();

        buildRows.removeAll();
        if (s.build() == null) {
            buildRows.add(chip("BRAK BUILDA", UI.DANGER));
            buildRows.add(line("Zbuduj klienta (gradlew build).", UI.TEXT_DIM, false));
        } else {
            buildRows.add(chip("NAJNOWSZY", UI.OK));
            describe(buildRows, s.build());
        }

        modRows.removeAll();
        int stale = 0;
        if (!s.modsExists()) {
            modRows.add(chip("BRAK PROFILU", UI.DANGER));
            modRows.add(line("Nie ma folderu: " + modsDir, UI.TEXT_DIM, false));
        } else if (s.mods().isEmpty()) {
            modRows.add(chip("BRAK KLIENTA", UI.ORANGE));
            modRows.add(line("Kliknij WGRAJ AKTUALNY.", UI.TEXT_DIM, false));
        } else {
            if (s.mods().size() > 1) {
                modRows.add(chip("DUPLIKAT  ·  " + s.mods().size() + " JARY - GRA SIE NIE ODPALI", UI.DANGER));
                modRows.add(gap(4));
            }
            for (JarInfo m : s.mods()) {
                boolean fresh = buildFp != null && buildFp.equals(m.fp());
                if (!fresh) stale++;
                modRows.add(chip(fresh ? "AKTUALNY" : (buildFp == null ? "?" : "STARY"), fresh ? UI.OK : UI.ORANGE));
                describe(modRows, m);
                modRows.add(gap(8));
            }
        }

        injRows.removeAll();
        boolean injFresh = false;
        if (s.injector() == null) {
            injRows.add(chip("BRAK", UI.TEXT_DIM));
        } else {
            injFresh = buildFp != null && buildFp.equals(s.injector().fp());
            injRows.add(chip(injFresh ? "AKTUALNY" : "STARY KLIENT W SRODKU", injFresh ? UI.OK : UI.ORANGE));
            describe(injRows, s.injector());
            if (!injFresh)
                injRows.add(line("Przebuduj, inaczej wstrzykniesz stara wersje.", UI.TEXT_DIM, false));
        }

        boolean dup = s.mods().size() > 1;
        boolean ok = s.build() != null && !dup && stale == 0 && !s.mods().isEmpty();
        headline.setForeground(dup ? UI.DANGER : ok ? UI.OK : UI.ORANGE);
        headline.setText(dup ? "W mods sa dwa klienty naraz - kliknij Usun duplikaty."
                : ok ? "Wszystko aktualne - gra wczyta najnowszy build."
                : s.build() == null ? "Brak zbudowanego klienta."
                : "Modrinth ma stara wersje - kliknij WGRAJ AKTUALNY.");

        for (JPanel p : new JPanel[]{buildRows, modRows, injRows}) {
            p.revalidate();
            p.repaint();
        }
    }

    private static void describe(JPanel rows, JarInfo j) {
        rows.add(line(j.path().getFileName().toString(), UI.TEXT, true));
        rows.add(line(TIME.format(new Date(j.modified())) + "   ·   " + String.format(Locale.ROOT, "%.1f MB", j.size() / 1048576.0),
                UI.TEXT_DIM, false));
    }

    /* ------------------------------------------------------------------ */
    /* actions                                                             */
    /* ------------------------------------------------------------------ */

    /** Keeps the newest copy of the client in the mods folder and deletes the rest. */
    private void removeDuplicates() {
        try {
            List<Path> jars = ourJars(modsDir);
            int gone = 0;
            for (int i = 1; i < jars.size(); i++) if (Files.deleteIfExists(jars.get(i))) gone++;
            say.accept(gone == 0 ? "Nie ma duplikatow." : "Usunieto " + gone + " starszych jarow z mods.");
        } catch (IOException e) {
            say.accept("Nie moge usunac (gra otwarta?): " + e.getMessage());
        }
        refresh();
    }

    private void open(Path folder) {
        try {
            Files.createDirectories(folder);
            Desktop.getDesktop().open(folder.toFile());
        } catch (Exception e) {
            say.accept("Nie moge otworzyc: " + e.getMessage());
        }
    }

    private void launchModrinth() {
        String local = System.getenv("LOCALAPPDATA");
        List<Path> candidates = new ArrayList<>();
        if (local != null) {
            candidates.add(Paths.get(local, "Modrinth App", "Modrinth App.exe"));
            candidates.add(Paths.get(local, "Programs", "Modrinth App", "Modrinth App.exe"));
        }
        candidates.add(Paths.get("C:", "Program Files", "Modrinth App", "Modrinth App.exe"));
        String home = System.getProperty("user.home");
        for (String d : new String[]{"Desktop", "Pulpit", "OneDrive/Desktop", "OneDrive/Pulpit"})
            candidates.add(Paths.get(home, d, "Modrinth App.lnk"));
        for (Path p : candidates) {
            if (!Files.isRegularFile(p)) continue;
            try {
                Desktop.getDesktop().open(p.toFile());
                say.accept("Uruchamiam Modrinth App.");
                return;
            } catch (IOException ignored) {
            }
        }
        say.accept("Nie znalazlem Modrinth App.");
    }

    /** Runs Build.bat silent in the background (the app runs from a temp copy, so its own jar is not locked). */
    private void rebuildInjector() {
        say.accept("Przebudowuje injector...");
        worker.submit(() -> {
            String result;
            try {
                Process p = new ProcessBuilder("cmd", "/c", "Build.bat", "silent").redirectErrorStream(true).start();
                String out = new String(p.getInputStream().readAllBytes());
                int code = p.waitFor();
                String last = Arrays.stream(out.split("\\R")).filter(l -> l.startsWith("[")).reduce((a, b) -> b).orElse("");
                result = code == 0 ? "Injector przebudowany. " + last : "Build.bat: " + last;
            } catch (Exception e) {
                result = "Nie moge odpalic Build.bat: " + e.getMessage();
            }
            String fin = result;
            SwingUtilities.invokeLater(() -> {
                say.accept(fin);
                refresh();
            });
        });
    }
}
