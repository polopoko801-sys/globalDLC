import javax.swing.*;
import javax.swing.Timer;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.awt.event.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.io.File;
import java.io.InputStream;
import java.nio.file.*;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Enumeration;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.stream.Stream;

/**
 * GLOBALDLC APP — the local identity book and control panel for the client.
 *
 * <p>Generates, stores and switches between UIDs in the {@code CLOUDAN-XXXX-XXXX} format. Everything it
 * does happens on this machine and nowhere else: the list lives in a text file next to the app and the
 * active UID is written where the client reads it. Nothing is sent anywhere, there is no account, no
 * server and no check-in — a UID here is a name you gave yourself.
 */
public class GlobalDLCApp extends GlassFrame {

    private static final Path DATA_DIR = Paths.get("data");
    /** Every UID you have generated or imported, newest first. */
    private static final Path STORE = DATA_DIR.resolve("uid-store.txt");
    /** The one the client should use. Written as a bare line so anything can read it. */
    private static final Path ACTIVE = DATA_DIR.resolve("uid-active.txt");
    /** UIDs marked banned. Local to this machine - see toggleBan. */
    private static final Path BANNED = DATA_DIR.resolve("uid-banned.txt");
    /** Why each UID is banned, as {@code uid=reason} lines - sent to the client and shown on its ban screen. */
    private static final Path BAN_REASONS = DATA_DIR.resolve("uid-ban-reasons.txt");
    /** Rank per UID, as {@code uid=RANK} lines. Written on the card so the client can print it. */
    private static final Path RANKS = DATA_DIR.resolve("uid-ranks.txt");
    private static final String PREFIX = "CLOUDAN";

    /**
     * Where the Modrinth App keeps the mods of the profile this client is built for.
     *
     * <p>Hard-coded to one profile on purpose - it is the profile the jar is compiled against, and
     * dropping a 1.21.11 mod into a 1.21.8 profile is the one mistake that looks like the mod being
     * broken rather than like the wrong folder.
     */
    private static final String MODRINTH_REL = "ModrinthApp/profiles/Fabric 1.21.11/mods";

    /** Jars in that folder that are this client under some older name - replaced, never stacked. */
    private static final String[] SIBLINGS = {"thunderhack", "gombal", "cloudan", "globaldlc"};

    /** The rank tabs, in the order they are shown. The first one is "everything". */
    private static final String[] FILTERS = {"WSZYSTKIE", "OWNER", "SELLER", "CUSTOMER", "BAN"};

    /**
     * Every UID, in the order it is stored.
     *
     * <p>Separate from the list model, which only ever holds what the tabs and the search box let
     * through. Mutating the model directly would mean a UID hidden by a filter quietly surviving a
     * delete, so every change goes through here and the model is rebuilt from it.
     */
    private final List<String> all = new ArrayList<>();

    private final DefaultListModel<String> model = new DefaultListModel<>();
    private final JList<String> list = new JList<>(model);
    private final UI.SearchField search = new UI.SearchField("szukaj UID...");
    private final UI.StatPill countPill = new UI.StatPill(UI.ACCENT, "0", "UID", null);
    private final UI.StatPill activePill = new UI.StatPill(UI.OK, "brak", "aktywny", this::copyActive);
    private UI.SegTabs tabs;
    private int filter;
    private final JLabel status = new JLabel(" ");
    private final LinkedHashSet<String> banned = new LinkedHashSet<>();
    private final java.util.LinkedHashMap<String, String> banReasons = new java.util.LinkedHashMap<>();
    /** UID to rank. Missing means CUSTOMER, which is what an ordinary buyer gets. */
    private final java.util.LinkedHashMap<String, String> ranks = new java.util.LinkedHashMap<>();

    private final CardLayout pages = new CardLayout();
    private final JPanel pageHost = new JPanel(pages);
    private UI.SegTabs pageTabs;
    private StatusPage statusPage;

    public GlobalDLCApp() {
        super("GlobalDLC App", "GLOBALDLC", 1000, 800);
        setBody(buildBody());
        shortcuts();
        load();
        refreshActive();
    }

    /* ------------------------------------------------------------------ */
    /* UI                                                                  */
    /* ------------------------------------------------------------------ */

    private JComponent buildBody() {
        JPanel body = new JPanel(new BorderLayout(0, 0));
        body.setOpaque(false);
        body.setBorder(BorderFactory.createEmptyBorder(6, 22, 18, 22));

        body.add(header(), BorderLayout.NORTH);
        // Two pages under one header: the UID book, and STATUS - whether the game will actually load the
        // newest build (stale Modrinth jar, duplicates in mods, an injector carrying an old client).
        JPanel mid = new JPanel(new BorderLayout(0, 12));
        mid.setOpaque(false);
        pageHost.setOpaque(false);
        pageHost.add(centre(), "uid");
        statusPage = new StatusPage(modrinthMods(), this::pushActive, this::say);
        pageHost.add(statusPage, "status");
        pageHost.add(new UpdatePage(this::say, () -> {
            java.util.List<String[]> out = new java.util.ArrayList<>();
            for (String uid : new ArrayList<>(all)) out.add(new String[]{uid, rankOf(uid), isBanned(uid) ? "1" : "0"});
            return out;
        }), "update");
        // the live link every running client talks to: releases + bans (see LinkServer)
        LinkServer.start(n -> banned.stream().anyMatch(b -> LinkServer.norm(b).equals(n)),
                n -> banned.stream().filter(b -> LinkServer.norm(b).equals(n)).map(b -> banReasons.getOrDefault(b, "")).findFirst().orElse(""),
                () -> SwingUtilities.invokeLater(list::repaint));
        LinkServer.setAdmin(new LinkServer.Admin() {
            public java.util.List<String[]> users() {
                java.util.List<String[]> out = new java.util.ArrayList<>();
                for (String uid : new ArrayList<>(all))
                    out.add(new String[]{uid, rankOf(uid), isBanned(uid) ? "1" : "0", banReasons.getOrDefault(uid, "")});
                return out;
            }

            public void ban(String uid, String reason) {
                onEdt(() -> { String u = resolveUid(uid); if (u != null) setBan(u, true, reason); });
            }

            public void unban(String uid) {
                onEdt(() -> { String u = resolveUid(uid); if (u != null) setBan(u, false, null); });
            }
        });
        pageTabs = new UI.SegTabs(new String[]{"UID-Y", "STATUS", "AKTUALIZACJE"}, i -> {
            pages.show(pageHost, i == 0 ? "uid" : i == 1 ? "status" : "update");
            if (i == 1) statusPage.refresh();
        });
        pageTabs.setPreferredSize(new Dimension(440, 38));
        mid.add(left(pageTabs), BorderLayout.NORTH);
        mid.add(pageHost, BorderLayout.CENTER);
        body.add(mid, BorderLayout.CENTER);
        body.add(footer(), BorderLayout.SOUTH);
        return body;
    }

    /**
     * The top of the window: label, headline, one sentence, the two things you came here to press, and
     * the row of readouts.
     *
     * <p>Laid out like a product page rather than like a settings dialog - the one action that matters
     * (make a UID) is a filled button in the headline block, and everything else waits in the card on
     * the right until it is needed.
     */
    private JComponent header() {
        JPanel head = new JPanel();
        head.setOpaque(false);
        head.setLayout(new BoxLayout(head, BoxLayout.Y_AXIS));
        head.setBorder(BorderFactory.createEmptyBorder(8, 2, 18, 2));

        head.add(left(new UI.Eyebrow("lokalna ksiazka uid")));
        head.add(Box.createRigidArea(new Dimension(0, 6)));
        head.add(left(new UI.Hero("GlobalDLC", "App.", 40)));
        head.add(Box.createRigidArea(new Dimension(0, 4)));

        JLabel sub = new JLabel("UID-y, rangi i status klienta w jednym miejscu. Ctrl+M = wgraj do Modrintha.");
        sub.setFont(new Font("Segoe UI", Font.PLAIN, 13));
        sub.setForeground(UI.TEXT_DIM);
        head.add(left(sub));
        head.add(Box.createRigidArea(new Dimension(0, 14)));

        UI.GlowButton make = new UI.GlowButton("GENERUJ UID", UI.ACCENT, UI.ACCENT2);
        make.setPreferredSize(new Dimension(190, 42));
        make.addActionListener(e -> generate());

        UI.GhostButton update = new UI.GhostButton("Wydaj update");
        update.setPreferredSize(new Dimension(170, 42));
        update.addActionListener(e -> publishUpdate());

        UI.GhostButton modrinth = new UI.GhostButton("Wgraj do Modrintha");
        modrinth.setPreferredSize(new Dimension(180, 42));
        modrinth.addActionListener(e -> pushActive());

        UI.GhostButton folder = new UI.GhostButton("Folder mods  →");
        folder.setPreferredSize(new Dimension(150, 42));
        folder.addActionListener(e -> openModrinth());

        JPanel cta = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
        cta.setOpaque(false);
        cta.add(make);
        cta.add(update);
        cta.add(modrinth);
        cta.add(folder);
        head.add(left(cta));
        head.add(Box.createRigidArea(new Dimension(0, 14)));

        head.add(left(UI.pillRow(countPill, activePill,
                new UI.StatPill(UI.ACCENT2, "Fabric 1.21.11", "profil Modrintha", this::openModrinth))));
        return head;
    }

    /** Pins a component to the left edge of a vertical stack instead of letting it stretch or centre. */
    private static JComponent left(JComponent component) {
        JPanel row = new JPanel(new BorderLayout());
        row.setOpaque(false);
        row.add(component, BorderLayout.WEST);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, component.getPreferredSize().height));
        return row;
    }

    private JComponent centre() {
        JPanel centre = new JPanel(new BorderLayout(16, 0));
        centre.setOpaque(false);

        centre.add(listCard(), BorderLayout.CENTER);
        centre.add(actions(), BorderLayout.EAST);
        return centre;
    }

    private JComponent listCard() {
        list.setBackground(new Color(0, 0, 0, 0));
        list.setOpaque(false);
        list.setForeground(UI.TEXT);
        list.setFont(new Font("Consolas", Font.PLAIN, 16));
        list.setSelectionBackground(new Color(UI.ACCENT.getRed(), UI.ACCENT.getGreen(), UI.ACCENT.getBlue(), 70));
        list.setSelectionForeground(Color.WHITE);
        list.setFixedCellHeight(34);
        list.setBorder(BorderFactory.createEmptyBorder(8, 12, 8, 12));
        list.setCellRenderer(new UidRenderer());
        list.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && SwingUtilities.isLeftMouseButton(e)) setActive();
            }

            // Windows fires the popup trigger on press, other platforms on release - both are handled so
            // the menu never goes missing depending on where this is run.
            @Override
            public void mousePressed(MouseEvent e) {
                maybePopup(e);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                maybePopup(e);
            }

            private void maybePopup(MouseEvent e) {
                if (!e.isPopupTrigger()) return;

                int index = list.locationToIndex(e.getPoint());
                if (index < 0) return;
                // Right-clicking a row selects it first, so the menu can never act on a different UID than
                // the one under the cursor.
                list.setSelectedIndex(index);
                rowMenu(model.get(index)).show(list, e.getX(), e.getY());
            }
        });

        JScrollPane scroll = new JScrollPane(list);
        scroll.setOpaque(false);
        scroll.getViewport().setOpaque(false);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        StatusPage.styleScroll(scroll);

        UI.GlassPanel card = new UI.GlassPanel(20, 18);
        card.setLayout(new BorderLayout(0, 12));
        card.setBorder(BorderFactory.createEmptyBorder(14, 14, 14, 14));
        card.add(listTools(), BorderLayout.NORTH);
        card.add(scroll, BorderLayout.CENTER);
        return card;
    }

    /** The tab strip and the search box that sit over the list and decide what it shows. */
    private JComponent listTools() {
        JPanel tools = new JPanel();
        tools.setOpaque(false);
        tools.setLayout(new BoxLayout(tools, BoxLayout.Y_AXIS));

        tabs = new UI.SegTabs(FILTERS, index -> {
            filter = index;
            refreshList();
        });
        tools.add(tabs);
        tools.add(Box.createRigidArea(new Dimension(0, 10)));

        search.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            public void insertUpdate(javax.swing.event.DocumentEvent e) { refreshList(); }
            public void removeUpdate(javax.swing.event.DocumentEvent e) { refreshList(); }
            public void changedUpdate(javax.swing.event.DocumentEvent e) { refreshList(); }
        });
        tools.add(search);
        return tools;
    }

    /**
     * The card on the right: everything you can do to the highlighted UID.
     *
     * <p>Quiet buttons stacked in the middle, and the one that ships a jar filled and alone at the
     * bottom. A panel where every button is filled has no primary action - here there is no doubt which
     * one finishes the job.
     */
    private JComponent actions() {
        UI.GlassPanel card = new UI.GlassPanel(20, 18);
        card.setLayout(new BorderLayout(0, 12));
        card.setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 16));
        card.setPreferredSize(new Dimension(232, 0));

        JLabel caption = new JLabel("ZAZNACZONY UID");
        caption.setFont(UI.tracked(new Font("Segoe UI", Font.BOLD, 11), 0.14f));
        caption.setForeground(UI.TEXT_DIM);
        card.add(caption, BorderLayout.NORTH);

        JPanel col = new JPanel();
        col.setOpaque(false);
        col.setLayout(new BoxLayout(col, BoxLayout.Y_AXIS));

        col.add(action(new UI.GhostButton("Ustaw aktywny"), e -> setActive()));
        col.add(gap());
        col.add(action(new UI.GhostButton("Edytuj"), e -> regenerate()));
        col.add(gap());
        col.add(action(new UI.GhostButton("Kopiuj"), e -> copy(selected())));
        col.add(gap());
        col.add(action(new UI.GhostButton("Wczytaj"), e -> load()));
        col.add(gap());
        col.add(action(new UI.GhostButton("Zapisz"), e -> save()));
        col.add(gap());
        col.add(action(new UI.GhostButton("Usun"), e -> remove()));
        col.add(Box.createVerticalGlue());
        card.add(col, BorderLayout.CENTER);

        UI.GlowButton build = new UI.GlowButton("MOD NA PULPIT", UI.ACCENT, UI.ACCENT2);
        build.setPreferredSize(new Dimension(10, 44));
        build.addActionListener(e -> withSelected(this::export));
        card.add(build, BorderLayout.SOUTH);
        return card;
    }

    private JComponent action(AbstractButton button, ActionListener listener) {
        button.addActionListener(listener);
        button.setAlignmentX(Component.LEFT_ALIGNMENT);
        button.setMaximumSize(new Dimension(Integer.MAX_VALUE, 38));
        return button;
    }

    private Component gap() {
        return Box.createRigidArea(new Dimension(0, 8));
    }

    private JComponent footer() {
        status.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        status.setForeground(UI.TEXT_DIM);
        status.setBorder(BorderFactory.createEmptyBorder(12, 4, 0, 0));
        return status;
    }

    /* ------------------------------------------------------------------ */
    /* Actions                                                             */
    /* ------------------------------------------------------------------ */

    /**
     * Asks for the number you want and turns it into your UID. The window is the whole feature: a UID you
     * picked yourself is one you can remember, which a random one never is.
     */
    private void generate() {
        UidPrompt.Result made = UidPrompt.ask(this);
        if (made == null) return;

        String uid = made.uid();
        if (all.contains(uid)) {
            // Re-adding one that exists is taken as editing it: the rank and the face just picked are
            // the newer answer, so they win rather than being thrown away with a "already on the list".
            apply(made);
            select(uid);
            say(uid + " juz byl na liscie - zaktualizowano range i awatar.");
            return;
        }

        all.add(0, uid);
        apply(made);
        save();
        select(uid);
        say("Dodano " + uid + "  (" + made.rank() + ")");
    }

    /** Writes down what the prompt decided about one UID: its rank. */
    private void apply(UidPrompt.Result made) {
        ranks.put(made.uid(), made.rank());
        writeRanks();
        syncActive(made.uid());
        refreshList();
        refreshActive();
    }

    /**
     * Shows a UID and highlights it, opening the tab it lives under when a filter is hiding it.
     *
     * <p>Otherwise adding an OWNER while the CUSTOMER tab is open looks like nothing happened.
     */
    private void select(String uid) {
        if (!matchesFilter(uid)) {
            filter = 0;
            if (tabs != null) tabs.set(0);
        }
        search.setText("");
        refreshList();
        list.setSelectedValue(uid, true);
    }


    /**
     * Replaces the selected UID with a fresh one in the same slot. If it happened to be the active one,
     * the active file follows it - otherwise the client would be left pointing at a UID that is gone.
     */
    private void regenerate() {
        String old = selected();
        if (old == null) {
            say("Najpierw zaznacz UID.");
            return;
        }

        UidPrompt.Result made = UidPrompt.ask(this, old, rankOf(old));
        if (made == null) return;

        String uid = made.uid();
        all.set(all.indexOf(old), uid);

        // The old UID.s rank moves with it rather than being left behind on an id that is gone.
        if (!uid.equals(old)) ranks.remove(old);
        apply(made);

        if (old.equals(readActive())) writeActive(uid);
        save();
        refreshActive();
        select(uid);
        say(old + " -> " + uid + "  (" + made.rank() + ")");
    }

    private void setActive() {
        String uid = selected();
        if (uid == null) {
            say("Najpierw zaznacz UID.");
            return;
        }

        writeActive(uid);
        refreshActive();
        say("Aktywny: " + uid);
    }

    private void remove() {
        String uid = selected();
        if (uid == null) {
            say("Najpierw zaznacz UID.");
            return;
        }

        all.remove(uid);
        if (uid.equals(readActive())) writeActive("");
        ranks.remove(uid);
        writeRanks();
        save();
        refreshList();
        refreshActive();
        say("Usunieto " + uid);
    }

    /* ------------------------------------------------------------------ */
    /* Right-click menu                                                    */
    /* ------------------------------------------------------------------ */

    private JPopupMenu rowMenu(String uid) {
        JPopupMenu menu = new JPopupMenu();
        menu.setBackground(UI.CARD);
        menu.setBorder(BorderFactory.createLineBorder(new Color(255, 255, 255, 40)));

        menu.add(item("Wrzuc do Modrintha", () -> pushToModrinth(uid)));
        menu.add(item("Mod na pulpit", () -> export(uid)));
        menu.add(item("Karta UID na pulpit", () -> exportCard(uid)));
        menu.addSeparator();
        menu.add(item("Edytuj (numer i ranga)", this::regenerate));
        menu.add(rankMenu(uid));
        menu.add(item(isBanned(uid) ? "Odbanuj" : "Zbanuj", () -> toggleBan(uid)));
        menu.addSeparator();
        menu.add(item("Kopiuj", () -> copy(uid)));
        menu.add(item("Ustaw aktywny", this::setActive));
        menu.addSeparator();
        menu.add(item("Usun", this::remove));
        return menu;
    }

    /**
     * The rank submenu.
     *
     * <p>The rank rides along on the exported card and is what the client prints in the corner of its
     * menu. It is a label and nothing more - no part of the client behaves differently because of it.
     */
    private JMenu rankMenu(String uid) {
        JMenu sub = new JMenu("Ranga");
        sub.setBackground(UI.CARD);
        sub.setForeground(UI.TEXT);
        sub.setOpaque(true);

        String current = rankOf(uid);
        for (String rank : Store.RANKS) {
            String marked = rank.equals(current) ? "2022 " + rank : "   " + rank;
            sub.add(item(marked, () -> setRank(uid, rank)));
        }
        return sub;
    }

    /** The rank on this UID. Anyone without one is an ordinary buyer. */
    private String rankOf(String uid) {
        return ranks.getOrDefault(uid, Store.RANKS[0]);
    }

    private void setRank(String uid, String rank) {
        ranks.put(uid, rank);
        writeRanks();
        syncActive(uid);
        refreshList();
        refreshActive();
        say(uid + " -> " + rank);
    }

    /**
     * Rewrites the shared licence file when the UID that just changed is the active one.
     *
     * <p>The client re-reads that file while it runs, so a rank changed here lands in the game within a
     * second or two - no new jar, no restart. Without this the file would still say what it said when
     * "USTAW AKTYWNY" was last pressed.
     */
    private void syncActive(String uid) {
        if (uid.equals(readActive())) writeActive(uid);
    }

    private void writeRanks() {
        List<String> lines = new ArrayList<>(ranks.size());
        ranks.forEach((uid, rank) -> lines.add(uid + "=" + rank));
        writeLines(RANKS, lines);
    }

    private JMenuItem item(String text, Runnable action) {
        JMenuItem mi = new JMenuItem(text);
        mi.setBackground(UI.CARD);
        mi.setForeground(UI.TEXT);
        mi.setFont(new Font("Segoe UI", Font.PLAIN, 13));
        mi.setBorder(BorderFactory.createEmptyBorder(6, 12, 6, 18));
        mi.addActionListener(e -> action.run());
        return mi;
    }

    /* ------------------------------------------------------------------ */
    /* Ban list                                                            */
    /* ------------------------------------------------------------------ */

    private boolean isBanned(String uid) {
        return banned.contains(uid);
    }

    /**
     * Marks a UID banned, or lifts it. The list is a file on this machine, so it changes what this app
     * shows and what a freshly exported card says - it cannot reach a jar somebody is already holding.
     * Re-export after banning if the new status has to travel with the card.
     */
    private void toggleBan(String uid) {
        if (banned.contains(uid)) {
            setBan(uid, false, null);
        } else {
            String reason = JOptionPane.showInputDialog(this, "Powód bana dla " + uid + ":", "Ban", JOptionPane.PLAIN_MESSAGE);
            if (reason == null) return;                       // cancelled - no ban
            setBan(uid, true, reason);
        }
    }

    private static void onEdt(Runnable r) {
        try {
            if (SwingUtilities.isEventDispatchThread()) r.run();
            else SwingUtilities.invokeAndWait(r);
        } catch (Exception ignored) {
        }
    }

    /** The stored UID matching any spelling of it (1, 0000-0001, CLOUDAN-0000-0001), or null. */
    private String resolveUid(String any) {
        String n = LinkServer.norm(any);
        for (String uid : all) if (LinkServer.norm(uid).equals(n)) return uid;
        return null;
    }

    /** Bans (with a reason) or unbans; used by the list menu and by the in-game GlobalPanel. */
    private void setBan(String uid, boolean ban, String reason) {
        if (!ban) {
            if (!banned.remove(uid)) return;
            banReasons.remove(uid);
            say(uid + " odbanowany.");
        } else {
            banned.add(uid);
            banReasons.put(uid, reason == null ? "" : reason.trim());
            say(uid + " zabanowany" + (reason == null || reason.isBlank() ? "." : " - " + reason.trim()));
        }

        writeLines(BANNED, new ArrayList<>(banned));
        List<String> reasons = new ArrayList<>();
        banReasons.forEach((k, v) -> reasons.add(k + "=" + v.replace("\n", " ")));
        writeLines(BAN_REASONS, reasons);
        syncActive(uid);
        refreshList();
        refreshActive();
    }

    /* ------------------------------------------------------------------ */
    /* Export                                                              */
    /* ------------------------------------------------------------------ */

    /**
     * Writes the client itself, stamped with this UID, straight to the Desktop.
     *
     * <p>The file that lands there IS the mod: drag it into a Modrinth profile's mods folder and that
     * copy of the client is this licence - it prints this UID on the watermark, this rank in the menu
     * corner and this face beside it. An earlier version of this button wrote a little viewer window
     * instead, which was a jar you could not do anything with.
     *
     * <p>No file chooser: there is exactly one place this ever goes, and being asked where to put it
     * every single time is a dialog that only ever has one right answer. The path is reported in the
     * status line so it is never a mystery where the file went.
     */
    private void export(String uid) {
        File out = new File(desktop(), "GlobalDLC-" + uid.replace("CLOUDAN-", "") + ".jar");
        try {
            stampClient(out.toPath(), uid);
            say("Na pulpicie: " + out.getName() + "  - wrzuc do mods.");
        } catch (Exception e) {
            say("Nie moge zbudowac moda: " + e.getMessage());
        }
    }

    /**
     * Puts the stamped client into the Modrinth profile, replacing whatever copy is there.
     *
     * <p>Replacing rather than adding is the whole point: two jars of the same mod in one folder is a
     * duplicate mod id, which Fabric refuses to start with. So every sibling of ours in that folder goes
     * first and exactly one stamped jar is left behind.
     */
    private void pushToModrinth(String uid) {
        Path mods = modrinthMods();

        try {
            if (!Files.isDirectory(mods)) {
                say("Nie widze profilu Modrintha: " + mods);
                return;
            }

            int replaced = clearSiblings(mods);
            Path out = mods.resolve("GlobalDLC-" + uid.replace("CLOUDAN-", "") + ".jar");
            stampClient(out, uid);

            say("Wrzucone do Modrintha (" + uid + ", " + rankOf(uid) + ")"
                    + (replaced > 0 ? " - podmieniono stary jar." : "."));
        } catch (Exception e) {
            say("Nie moge wrzucic do Modrintha: " + e.getMessage());
        }
    }

    /**
     * Builds a fresh stamped jar for a whole group at once.
     *
     * <p>An update is not a special kind of file - it is this build of the client with somebody's
     * licence on it. So "updating" a rank means stamping the current build once per UID in that rank
     * and dropping the lot in one folder on the Desktop, ready to hand out.
     *
     * <p>Per rank rather than only all-or-one because that is how they actually go out: the testers get
     * a build before the customers do, and an owner's copy is handed over on its own.
     */
    private void publishUpdate() {
        String[] choices = new String[Store.RANKS.length + 2];
        choices[0] = "Wszystkie UID (" + all.size() + ")";
        for (int i = 0; i < Store.RANKS.length; i++) {
            String rank = Store.RANKS[i];
            choices[i + 1] = rank + " (" + countOf(rank) + ")";
        }
        choices[choices.length - 1] = "Tylko zaznaczony";

        Object picked = JOptionPane.showInputDialog(this, "Dla kogo zbudowac aktualizacje?",
                "Aktualizacja", JOptionPane.PLAIN_MESSAGE, null, choices, choices[0]);
        if (picked == null) return;

        int index = java.util.Arrays.asList(choices).indexOf(picked);
        List<String> targets = new ArrayList<>();

        if (index == 0) targets.addAll(all);
        else if (index == choices.length - 1) {
            String selected = selected();
            if (selected == null) {
                say("Najpierw zaznacz UID.");
                return;
            }
            targets.add(selected);
        } else {
            String rank = Store.RANKS[index - 1];
            for (String uid : all) if (rank.equals(rankOf(uid))) targets.add(uid);
        }

        if (targets.isEmpty()) {
            say("Nikogo nie ma w tej grupie.");
            return;
        }

        File folder = new File(desktop(), "GlobalDLC-update-" + new SimpleDateFormat("yyyyMMdd-HHmm").format(new Date()));
        int done = 0;
        for (String uid : targets) {
            try {
                stampClient(new File(folder, "GlobalDLC-" + uid.replace("CLOUDAN-", "") + ".jar").toPath(), uid);
                done++;
            } catch (Exception e) {
                say("Nie zbudowalem dla " + uid + ": " + e.getMessage());
            }
        }

        say("Zbudowano " + done + "/" + targets.size() + " w " + folder.getName() + " na pulpicie.");
        if (done > 0) {
            try {
                Desktop.getDesktop().open(folder);
            } catch (IOException ignored) {
                // The status line already says where it went.
            }
        }
    }

    /**
     * One click from "I just built it" to "the game will load it": the active UID (or the highlighted one)
     * stamped into Modrinth, or - with no UID at all - the plain build copied there. Either way every older
     * copy of the client in that folder goes first, so there is never a duplicate mod.
     */
    private void pushActive() {
        String uid = readActive();
        if (uid.isEmpty()) uid = selected();
        if (uid != null && !uid.isEmpty()) {
            pushToModrinth(uid);
        } else {
            try {
                Path mods = modrinthMods();
                if (!Files.isDirectory(mods)) {
                    say("Nie widze profilu Modrintha: " + mods);
                    return;
                }
                Path base = clientJar();
                if (base == null) {
                    say("Nie znalazlem jara klienta (build/libs).");
                    return;
                }
                clearSiblings(mods);
                Files.copy(base, mods.resolve("GlobalDLC.jar"), StandardCopyOption.REPLACE_EXISTING);
                say("Wgrano najnowszy build do Modrintha (bez UID).");
            } catch (Exception e) {
                say("Nie moge wgrac (gra otwarta?): " + e.getMessage());
            }
        }
        if (statusPage != null) statusPage.refresh();
    }

    /**
     * Ctrl+N new UID, Ctrl+F search, Ctrl+M push to Modrinth, F5 reload, Ctrl+1/2 pages; on the list:
     * Enter = set active, Delete = remove, Ctrl+C = copy.
     */
    private void shortcuts() {
        JRootPane root = getRootPane();
        bind(root, JComponent.WHEN_IN_FOCUSED_WINDOW, "ctrl N", this::generate);
        bind(root, JComponent.WHEN_IN_FOCUSED_WINDOW, "ctrl F", () -> {
            showPage(0);
            search.requestFocusInWindow();
        });
        bind(root, JComponent.WHEN_IN_FOCUSED_WINDOW, "ctrl M", this::pushActive);
        bind(root, JComponent.WHEN_IN_FOCUSED_WINDOW, "F5", () -> {
            load();
            statusPage.refresh();
        });
        bind(root, JComponent.WHEN_IN_FOCUSED_WINDOW, "ctrl 1", () -> showPage(0));
        bind(root, JComponent.WHEN_IN_FOCUSED_WINDOW, "ctrl 2", () -> showPage(1));
        bind(list, JComponent.WHEN_FOCUSED, "ENTER", this::setActive);
        bind(list, JComponent.WHEN_FOCUSED, "DELETE", this::remove);
        bind(list, JComponent.WHEN_FOCUSED, "ctrl C", () -> copy(selected()));
    }

    private void showPage(int i) {
        pageTabs.set(i);
        pages.show(pageHost, i == 0 ? "uid" : "status");
        if (i == 1) statusPage.refresh();
    }

    private static void bind(JComponent c, int when, String key, Runnable action) {
        c.getInputMap(when).put(KeyStroke.getKeyStroke(key), key);
        c.getActionMap().put(key, new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                action.run();
            }
        });
    }

    private int countOf(String rank) {
        int n = 0;
        for (String uid : all) if (rank.equals(rankOf(uid))) n++;
        return n;
    }

    /** Opens that mods folder, for when you want to see what actually landed there. */
    private void openModrinth() {
        try {
            Path mods = modrinthMods();
            Files.createDirectories(mods);
            Desktop.getDesktop().open(mods.toFile());
        } catch (Exception e) {
            say("Nie moge otworzyc folderu: " + e.getMessage());
        }
    }

    private static Path modrinthMods() {
        String appdata = System.getenv("APPDATA");
        Path roaming = (appdata != null && !appdata.isBlank())
                ? Paths.get(appdata)
                : Paths.get(System.getProperty("user.home"), "AppData", "Roaming");
        return roaming.resolve(MODRINTH_REL);
    }

    /** Deletes the copies of this client already sitting in a mods folder. Returns how many went. */
    private static int clearSiblings(Path mods) throws IOException {
        int gone = 0;
        try (Stream<Path> files = Files.list(mods)) {
            for (Path file : files.filter(p -> p.toString().toLowerCase().endsWith(".jar")).toList()) {
                String name = file.getFileName().toString().toLowerCase();
                for (String sibling : SIBLINGS) {
                    if (!name.startsWith(sibling)) continue;
                    if (Files.deleteIfExists(file)) gone++;
                    break;
                }
            }
        }
        return gone;
    }

    /**
     * Copies the built client and adds this licence to it.
     *
     * <p>The licence is three lines in {@code uid.txt} - the id, the status, the rank - plus the avatar
     * The client reads them straight out of the jar it finds in the mods folder, so a stamped jar
     * carries its whole licence and needs nothing else installed beside it. The avatar is not written:
     * the client detects your Discord account by itself and uses that picture.
     */
    private void stampClient(Path out, String uid) throws Exception {
        Path base = clientJar();
        if (base == null) throw new IOException("nie znalazlem jara klienta (build/libs)");

        byte[] licence = (uid + "\n" + (isBanned(uid) ? "banned" : "active") + "\n" + rankOf(uid) + "\n")
                .getBytes(StandardCharsets.UTF_8);

        Files.createDirectories(out.toAbsolutePath().getParent());
        try (JarFile source = new JarFile(base.toFile());
             JarOutputStream jar = new JarOutputStream(Files.newOutputStream(out))) {

            Enumeration<JarEntry> entries = source.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                String name = entry.getName();
                // Our own file is rewritten, never doubled - re-stamping a jar that was stamped before
                // has to end with one licence in it, not two. uid_avatar.png is dropped for the same
                // reason: jars stamped by an older build carry one, and the client no longer reads it.
                if (name.equals("uid.txt") || name.equals("uid_avatar.png") || name.equals(LinkServer.ADDRESS_ENTRY)) continue;

                jar.putNextEntry(new JarEntry(name));
                if (!entry.isDirectory()) {
                    try (InputStream in = source.getInputStream(entry)) {
                        in.transferTo(jar);
                    }
                }
                jar.closeEntry();
            }

            jar.putNextEntry(new JarEntry("uid.txt"));
            jar.write(licence);
            jar.closeEntry();

            // the address of this app, so the card connects back here with no setup on the player's side
            jar.putNextEntry(new JarEntry(LinkServer.ADDRESS_ENTRY));
            jar.write(LinkServer.publicAddress().getBytes(StandardCharsets.UTF_8));
            jar.closeEntry();

        }
    }

    /**
     * The built client to stamp.
     *
     * <p>Freshest first, not nearest: the Gradle output of a checkout sitting next to the app, then the
     * copy packed into this app when it was built, then a jar already installed in the Modrinth profile.
     * The build folder goes first because on this machine it is the one that moves - stamping a UID onto
     * a client from three rebuilds ago is the bug you would not notice until the game started.
     *
     * <p>The installed jar matters at the other end: on a machine with no source tree it is the only
     * copy there is, and re-stamping it is exactly what changing a UID means.
     */
    private Path clientJar() throws IOException {
        for (Path libs : new Path[]{Paths.get("..", "build", "libs"), Paths.get("build", "libs")}) {
            Path newest = newestJar(libs);
            if (newest != null) return newest;
        }

        Path packed = unpackEmbedded();
        if (packed != null) return packed;

        return newestJar(modrinthMods());
    }

    /** The newest non-sources jar of ours in a folder, or null. */
    private static Path newestJar(Path folder) throws IOException {
        if (!Files.isDirectory(folder)) return null;

        Path best = null;
        try (Stream<Path> files = Files.list(folder)) {
            for (Path file : files.toList()) {
                String name = file.getFileName().toString().toLowerCase();
                if (!name.endsWith(".jar") || name.contains("sources")) continue;

                boolean ours = false;
                for (String sibling : SIBLINGS) ours |= name.startsWith(sibling);
                if (!ours) continue;

                if (best == null || Files.getLastModifiedTime(file).compareTo(Files.getLastModifiedTime(best)) > 0)
                    best = file;
            }
        }
        return best;
    }

    /** The client packed into this app at build time, written out to a temp file so it can be read as a jar. */
    private Path unpackEmbedded() throws IOException {
        try (InputStream in = GlobalDLCApp.class.getResourceAsStream("/client.jar")) {
            if (in == null) return null;

            Path temp = Files.createTempFile("globaldlc-client", ".jar");
            temp.toFile().deleteOnExit();
            Files.copy(in, temp, StandardCopyOption.REPLACE_EXISTING);
            return temp;
        }
    }

    /**
     * Writes a runnable jar that shows this one UID and nothing else.
     *
     * <p>The jar is this app's own classes plus a {@code uid.txt} resource and a manifest pointing at
     * {@link UidCard}. Copying our own classes rather than shipping a stub is what lets the card keep the
     * same window, the same glass and the same star field on a machine that has nothing else installed.
     *
     * <p>Kept beside {@link #export} as the thing you hand somebody to prove a UID, not the thing they
     * install - it is a window, not a mod.
     */
    private void exportCard(String uid) {
        File out = new File(desktop(), uid.replace("CLOUDAN-", "KARTA-") + ".jar");
        try {
            writeCardJar(out, uid, isBanned(uid));
            say("Karta na pulpicie: " + out.getName());
        } catch (Exception e) {
            say("Nie moge zapisac karty: " + e.getMessage());
        }
    }

    /** The Desktop folder, falling back to the home directory on a system that has none. */
    private static File desktop() {
        File home = new File(System.getProperty("user.home"));
        for (String name : new String[]{"Desktop", "Pulpit", "OneDrive/Desktop", "OneDrive/Pulpit"}) {
            File candidate = new File(home, name);
            if (candidate.isDirectory()) return candidate;
        }
        return home;
    }

    private void writeCardJar(File out, String uid, boolean isBanned) throws Exception {
        Path source = Paths.get(GlobalDLCApp.class.getProtectionDomain().getCodeSource().getLocation().toURI());

        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, "UidCard");

        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(out.toPath()), manifest)) {
            if (Files.isDirectory(source)) copyClassesFrom(source, jar);
            else copyClassesFrom(new JarFile(source.toFile()), jar);

            jar.putNextEntry(new JarEntry("uid.txt"));
            jar.write((uid + "\n" + (isBanned ? "banned" : "active") + "\n" + rankOf(uid) + "\n")
                    .getBytes(StandardCharsets.UTF_8));
            jar.closeEntry();

        }
    }

    /** Running from a jar (the normal case). */
    private static void copyClassesFrom(JarFile source, JarOutputStream jar) throws IOException {
        try (source) {
            Enumeration<JarEntry> entries = source.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                if (!entry.getName().endsWith(".class")) continue;

                jar.putNextEntry(new JarEntry(entry.getName()));
                try (InputStream in = source.getInputStream(entry)) {
                    in.transferTo(jar);
                }
                jar.closeEntry();
            }
        }
    }

    /** Running from loose classes, i.e. straight out of the build directory. */
    private static void copyClassesFrom(Path root, JarOutputStream jar) throws IOException {
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".class")).toList()) {
                jar.putNextEntry(new JarEntry(root.relativize(file).toString().replace('\\', '/')));
                Files.copy(file, jar);
                jar.closeEntry();
            }
        }
    }

    /**
     * One row: the rank badge, the UID, the rank.
     *
     * <p>The client prints the same id and rank in the corner of its menu, beside the Discord avatar it
     * detects on whichever machine is running the jar.
     */
    private final class UidRenderer extends JComponent implements ListCellRenderer<String> {
        private String uid = "";
        private boolean selected;

        @Override
        public Component getListCellRendererComponent(JList<? extends String> l, String value, int index,
                                                      boolean isSelected, boolean focus) {
            uid = value == null ? "" : value;
            selected = isSelected;
            setPreferredSize(new Dimension(10, 34));
            return this;
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);

            int w = getWidth(), h = getHeight();
            boolean isBanned = isBanned(uid);

            if (selected) {
                g2.setColor(new Color(UI.ACCENT.getRed(), UI.ACCENT.getGreen(), UI.ACCENT.getBlue(), 60));
                g2.fill(new java.awt.geom.RoundRectangle2D.Float(2, 2, w - 4, h - 4, 10, 10));
            }

            int x = 10;
            int d = h - 12;
            java.awt.geom.Ellipse2D circle = new java.awt.geom.Ellipse2D.Float(x, 6, d, d);

            // The Discord avatar the client sent for this UID (LinkServer /api/avatar); until one arrives,
            // the rank's initial in the rank's colour.
            String badge = isBanned(uid) ? "BAN" : rankOf(uid);
            Color tint = isBanned(uid) ? UI.DANGER : UI.rankColor(badge);
            Image face = avatarImage(uid);
            if (face != null) {
                Shape old = g2.getClip();
                g2.clip(circle);
                g2.drawImage(face, x, 6, d, d, null);
                g2.setClip(old);
            } else {
                g2.setColor(new Color(tint.getRed(), tint.getGreen(), tint.getBlue(), 40));
                g2.fill(circle);

                g2.setFont(new Font("Segoe UI", Font.BOLD, 11));
                FontMetrics im = g2.getFontMetrics();
                String initial = badge.substring(0, 1);
                g2.setColor(tint);
                g2.drawString(initial, x + (d - im.stringWidth(initial)) / 2f,
                        6 + (d + im.getAscent() - im.getDescent()) / 2f);
            }

            g2.setColor(new Color(255, 255, 255, 40));
            g2.draw(circle);
            x += d + 10;

            g2.setFont(new Font("Consolas", Font.BOLD, 15));
            g2.setColor(isBanned ? UI.DANGER : (selected ? Color.WHITE : UI.TEXT));
            FontMetrics fm = g2.getFontMetrics();
            int baseline = (h + fm.getAscent() - fm.getDescent()) / 2;
            g2.drawString(uid, x, baseline);
            x += fm.stringWidth(uid) + 12;

            String label = isBanned ? "BAN" : rankOf(uid);
            Color colour = isBanned ? UI.DANGER : UI.rankColor(label);
            g2.setFont(new Font("Segoe UI", Font.BOLD, 10));
            FontMetrics rm = g2.getFontMetrics();
            int cw = rm.stringWidth(label) + 16;
            java.awt.geom.RoundRectangle2D chip = new java.awt.geom.RoundRectangle2D.Float(x, (h - 18) / 2f, cw, 18, 9, 9);
            g2.setColor(new Color(colour.getRed(), colour.getGreen(), colour.getBlue(), 55));
            g2.fill(chip);
            g2.setColor(colour);
            g2.draw(chip);
            g2.drawString(label, x + 8, (h + rm.getAscent() - rm.getDescent()) / 2);
            x += cw + 12;

            // who is behind it (Discord name sent by the client) and, for a ban, why
            String extra = isBanned && !banReasons.getOrDefault(uid, "").isBlank()
                    ? "powód: " + banReasons.get(uid)
                    : LinkServer.discordNameOf(uid);
            if (extra != null && !extra.isBlank()) {
                g2.setFont(new Font("Segoe UI", Font.PLAIN, 12));
                g2.setColor(isBanned ? new Color(255, 150, 160) : UI.TEXT_DIM);
                FontMetrics em = g2.getFontMetrics();
                g2.drawString(extra, x, (h + em.getAscent() - em.getDescent()) / 2);
            }

            g2.dispose();
        }
    }

    /** Decoded avatars, re-read only when the file changes. */
    private final java.util.Map<String, Object[]> avatarCache = new java.util.HashMap<>();

    private Image avatarImage(String uid) {
        Path p = LinkServer.avatarOf(uid);
        if (p == null) return null;
        long stamp = p.toFile().lastModified();
        Object[] hit = avatarCache.get(uid);
        if (hit != null && (long) hit[0] == stamp) return (Image) hit[1];
        try {
            Image img = javax.imageio.ImageIO.read(p.toFile());
            avatarCache.put(uid, new Object[]{stamp, img});
            return img;
        } catch (Exception e) {
            return null;
        }
    }

    private void copy(String uid) {
        if (uid == null || uid.isEmpty()) {
            say("Nie ma czego kopiowac.");
            return;
        }

        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(uid), null);
        say("Skopiowano " + uid);
    }

    private String selected() {
        return list.getSelectedValue();
    }

    /** Runs an action on the highlighted UID, or says why it did not. */
    private void withSelected(java.util.function.Consumer<String> action) {
        String uid = selected();
        if (uid == null) {
            say("Najpierw zaznacz UID.");
            return;
        }
        action.accept(uid);
    }

    /* ------------------------------------------------------------------ */
    /* Storage                                                             */
    /* ------------------------------------------------------------------ */

    private void load() {
        // A set, because importing the same file twice should not leave you with two of everything; the
        // order is kept so the newest stays at the top where it was put.
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        for (String line : readLines(STORE)) {
            String uid = line.trim();
            if (!uid.isEmpty() && isValid(uid)) seen.add(uid);
        }

        ranks.clear();
        for (String line : readLines(RANKS)) {
            int eq = line.indexOf('=');
            if (eq > 0) ranks.put(line.substring(0, eq).trim(), line.substring(eq + 1).trim());
        }

        banned.clear();
        for (String line : readLines(BANNED)) {
            String uid = line.trim();
            if (!uid.isEmpty()) banned.add(uid);
        }

        banReasons.clear();
        for (String line : readLines(BAN_REASONS)) {
            int eq = line.indexOf('=');
            if (eq > 0) banReasons.put(line.substring(0, eq).trim(), line.substring(eq + 1).trim());
        }

        all.clear();
        all.addAll(seen);
        refreshList();
        say(all.isEmpty() ? "Pusto — kliknij GENERUJ UID." : "Wczytano " + all.size() + " UID.");
    }

    /**
     * Rebuilds the visible list from {@link #all}, keeping what the tabs and the search box allow.
     *
     * <p>The highlighted UID is put back afterwards when it survived the filter, so typing in the search
     * box does not quietly move the selection onto somebody else's licence - which, with buttons that
     * write jars, would be the expensive kind of surprise.
     */
    private void refreshList() {
        String held = selected();

        model.clear();
        String query = search.getText().trim().toUpperCase();
        for (String uid : all) {
            if (!matchesFilter(uid) || !uid.contains(query)) continue;
            model.addElement(uid);
        }

        if (held != null) list.setSelectedValue(held, true);
        countPill.set(String.valueOf(all.size()), model.size() == all.size()
                ? "UID"
                : "UID  (" + model.size() + " widocznych)");
    }

    /** Whether a UID belongs under the tab that is open. */
    private boolean matchesFilter(String uid) {
        return switch (filter) {
            case 0 -> true;
            case 4 -> isBanned(uid);
            default -> !isBanned(uid) && FILTERS[filter].equalsIgnoreCase(rankOf(uid));
        };
    }

    private void save() {
        writeLines(STORE, new ArrayList<>(all));
    }

    private String readActive() {
        for (String line : readLines(ACTIVE)) {
            String uid = line.trim();
            if (!uid.isEmpty()) return uid;
        }
        return "";
    }

    /**
     * Writes the active UID next to the app AND into the shared spot the client reads.
     *
     * <p>Two files, because the app and the game do not live in the same folder: this one is the app's own
     * record, and {@link #sharedFile()} under your user profile is the one anything else on the machine
     * can pick up. Still entirely local - it is a file on your own disk, not a request to anybody.
     */
    private void writeActive(String uid) {
        writeLines(ACTIVE, uid.isEmpty() ? List.of() : List.of(uid));

        try {
            Path shared = sharedFile();
            Files.createDirectories(shared.getParent());

            // The whole licence, not just the id: the client falls back to this file when the jar it is
            // running from was never stamped, and a rank it cannot read is a blank corner in the menu.
            Files.write(shared, uid.isEmpty()
                            ? List.of()
                            : List.of(uid, isBanned(uid) ? "banned" : "active", rankOf(uid)),
                    StandardCharsets.UTF_8);

        } catch (IOException e) {
            say("UID zapisany lokalnie, ale nie do profilu: " + e.getMessage());
        }
    }

    private static Path sharedFile() {
        return Paths.get(System.getProperty("user.home"), ".cloudan", "uid.txt");
    }

    private void refreshActive() {
        String uid = readActive();
        activePill.set(uid.isEmpty() ? "—" : uid,
                uid.isEmpty() ? "brak aktywnego UID" : "aktywny  ·  " + rankOf(uid));
        list.repaint();
    }

    /** The active-UID pill copies what it shows - it is the id you actually hand to somebody. */
    private void copyActive() {
        copy(readActive());
    }

    private static List<String> readLines(Path path) {
        try {
            if (!Files.exists(path)) return List.of();
            return Files.readAllLines(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return List.of();
        }
    }

    private void writeLines(Path path, List<String> lines) {
        try {
            Files.createDirectories(DATA_DIR);
            Files.write(path, lines, StandardCharsets.UTF_8);
        } catch (IOException e) {
            say("Nie moge zapisac: " + e.getMessage());
        }
    }

    /* ------------------------------------------------------------------ */
    /* UID                                                                 */
    /* ------------------------------------------------------------------ */

    /**
     * Loose on purpose: the current window only ever makes digits, but stores written before it accepted
     * letters too, and a stricter check would silently drop UIDs somebody is still using.
     */
    private static boolean isValid(String uid) {
        return uid.matches(PREFIX + "-[A-Z0-9]{4}-[A-Z0-9]{4}");
    }

    private void say(String message) {
        status.setText(new SimpleDateFormat("HH:mm:ss").format(new Date()) + "  " + message);
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            GlobalDLCApp app = new GlobalDLCApp();
            app.setVisible(true);
            if (args.length > 0 && args[0].equalsIgnoreCase("status")) app.showPage(1);
        });
    }
}
