import java.io.*;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.text.SimpleDateFormat;
import java.util.*;

/**
 * Warstwa danych dzielona przez Injector i Panel admina.
 * Lokalny magazyn plikowy w folderze ./data obok aplikacji:
 *   - licenses.txt : jedna licencja na linie -> uid|rank|label|createdMillis
 *   - release.txt  : version / channel / notes / time (format key=value)
 *
 * UWAGA: to magazyn LOKALNY (offline). W realnym sklepie zamiast plikow
 * uzylbys serwera z API, ale logika rang/UID/wydan pozostaje ta sama.
 */
public final class Store {

    public static final String[] RANKS = {"CUSTOMER", "SELLER", "OWNER"};

    // ---- model ----
    public static final class License {
        public String uid, rank, label;
        public long created;
        public boolean banned;
        public License(String uid, String rank, String label, long created) {
            this.uid = uid; this.rank = rank; this.label = label; this.created = created;
        }
    }

    public static final class Release {
        public String version = "1.7";
        public String channel = "stable";
        public String notes   = "Pierwsze wydanie";
        public long time = System.currentTimeMillis();
    }

    // ---- lokalizacja danych ----
    public static File baseDir() {
        try {
            File loc = new File(Store.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI());
            return loc.isFile() ? loc.getParentFile() : loc;
        } catch (URISyntaxException | NullPointerException e) {
            return new File(System.getProperty("user.dir"));
        }
    }
    public static File dataDir() {
        File d = new File(baseDir(), "data");
        if (!d.exists()) d.mkdirs();
        return d;
    }
    private static File licensesFile() { return new File(dataDir(), "licenses.txt"); }
    private static File releaseFile()  { return new File(dataDir(), "release.txt"); }

    // ---- licencje ----
    public static synchronized List<License> loadLicenses() {
        List<License> out = new ArrayList<>();
        File f = licensesFile();
        if (!f.exists()) return out;
        try (BufferedReader r = Files.newBufferedReader(f.toPath(), StandardCharsets.UTF_8)) {
            String line;
            while ((line = r.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                String[] p = line.split("\\|", -1);
                if (p.length < 2) continue;
                String uid = p[0].trim();
                String rank = p[1].trim();
                String label = p.length > 2 ? p[2] : "";
                long created = 0;
                if (p.length > 3) try { created = Long.parseLong(p[3].trim()); } catch (Exception ignored) {}
                License lic = new License(uid, rank, label, created);
                if (p.length > 4) {
                    String b = p[4].trim();
                    lic.banned = b.equals("1") || b.equalsIgnoreCase("true");
                }
                out.add(lic);
            }
        } catch (IOException e) {
            System.err.println("Nie moge odczytac licencji: " + e.getMessage());
        }
        return out;
    }

    public static synchronized void saveLicenses(List<License> list) {
        try (BufferedWriter w = Files.newBufferedWriter(licensesFile().toPath(), StandardCharsets.UTF_8)) {
            w.write("# ThunderHack licenses  ->  uid|rank|label|createdMillis|banned\n");
            for (License l : list) {
                w.write(l.uid + "|" + l.rank + "|" + safe(l.label) + "|" + l.created
                        + "|" + (l.banned ? "1" : "0"));
                w.newLine();
            }
        } catch (IOException e) {
            System.err.println("Nie moge zapisac licencji: " + e.getMessage());
        }
    }

    public static License find(String uid) {
        if (uid == null) return null;
        String want = uid.trim().toUpperCase(Locale.ROOT);
        for (License l : loadLicenses())
            if (l.uid.equalsIgnoreCase(want)) return l;
        return null;
    }

    /** Kolejny UID po kolei: 1, 2, 3, ... (max istniejacy numer + 1). */
    public static String nextUid(List<License> existing) {
        int max = 0;
        for (License l : existing) {
            try { max = Math.max(max, Integer.parseInt(l.uid.trim())); }
            catch (NumberFormatException ignored) {}
        }
        return String.valueOf(max + 1);
    }

    private static String safe(String s) { return s == null ? "" : s.replace("|", "/").replace("\n", " "); }

    // ---- wydania ----
    public static synchronized Release loadRelease() {
        Release r = new Release();
        File f = releaseFile();
        if (!f.exists()) { saveRelease(r); return r; }
        try {
            for (String line : Files.readAllLines(f.toPath(), StandardCharsets.UTF_8)) {
                int i = line.indexOf('=');
                if (i < 0) continue;
                String k = line.substring(0, i).trim();
                String v = line.substring(i + 1);
                switch (k) {
                    case "version": r.version = v.trim(); break;
                    case "channel": r.channel = v.trim(); break;
                    case "notes":   r.notes = v.replace("\\n", "\n"); break;
                    case "time":    try { r.time = Long.parseLong(v.trim()); } catch (Exception ignored) {} break;
                }
            }
        } catch (IOException e) {
            System.err.println("Nie moge odczytac wydania: " + e.getMessage());
        }
        return r;
    }

    public static synchronized void saveRelease(Release r) {
        try (BufferedWriter w = Files.newBufferedWriter(releaseFile().toPath(), StandardCharsets.UTF_8)) {
            w.write("version=" + r.version); w.newLine();
            w.write("channel=" + r.channel); w.newLine();
            w.write("notes=" + r.notes.replace("\n", "\\n")); w.newLine();
            w.write("time=" + r.time); w.newLine();
        } catch (IOException e) {
            System.err.println("Nie moge zapisac wydania: " + e.getMessage());
        }
    }

    // ---- plik UID na pulpicie (klikalny launcher injectora) ----
    public static File desktopDir() {
        String home = System.getProperty("user.home");
        File[] cands = {
                new File(home, "Desktop"),
                new File(home, "OneDrive/Desktop"),
                new File(home, "OneDrive/Pulpit"),
                new File(home, "Pulpit")
        };
        for (File f : cands) if (f.isDirectory()) return f;
        File d = new File(home, "Desktop"); d.mkdirs(); return d;
    }

    /**
     * Tworzy na pulpicie plik .vbs dla danego UID. Po dwukliku odpala
     * ThunderInjector.jar z tym UID jako argumentem (auto-login, bez terminala).
     */
    public static File writeUidFile(License l) {
        File dir = desktopDir();
        File f = new File(dir, "ThunderHack-UID-" + l.uid + ".vbs");
        String injDir = baseDir().getAbsolutePath();
        String content =
                "' ThunderHack Recode - Injector dla UID " + l.uid + " (" + l.rank + ")\r\n" +
                "' Klik 2x = otwiera injector zalogowany tym UID, bez terminala.\r\n" +
                "Set sh = CreateObject(\"WScript.Shell\")\r\n" +
                "sh.CurrentDirectory = \"" + injDir + "\"\r\n" +
                "sh.Run \"javaw -jar \"\"ThunderInjector.jar\"\" " + l.uid + "\", 0, False\r\n";
        try {
            Files.writeString(f.toPath(), content, StandardCharsets.UTF_8);
            return f;
        } catch (IOException e) {
            System.err.println("Nie moge zapisac pliku UID na pulpicie: " + e.getMessage());
            return null;
        }
    }
}
