package thunder.hack.features.modules.misc;

import thunder.hack.core.manager.client.ConfigManager;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * LearnAI's own language understanding - no outside model, no internet. It turns whatever the player types into one
 * of the agent's plain orders.
 *
 * <p><b>How.</b> Every word is first spell-checked against a small dictionary (by shared letter triples, so
 * "dimenty" is "diamenty" and a glued "kopacdiamenty" splits into "kopac diamenty"). The sentence then becomes a
 * bag of letter triples and is compared with a few hundred example sentences (cosine similarity): the closest
 * example's meaning wins. That understands word forms, word order, slang and typos it has never seen.
 *
 * <p><b>Learning.</b> When the best match is weak it asks "did you mean …?"; a "tak" stores the sentence as a new
 * example for that meaning, on disk, so next time it simply understands.
 */
final class AiLanguage {
    private AiLanguage() {
    }

    /** What a sentence can mean. */
    enum Intent {
        Go("chodzenie"), Village("szukanie wioski"), Wander("spacer gdzieś"), Back("powrót"), Mine("kopanie"), Hut("budowa domku"),
        Wall("budowa ściany"), Pillar("budowa słupa"), Fight("walka z mobami"), Stop("zatrzymanie"),
        Learn("nauka"), Stats("statystyki"), Explain("co umiem"), Reset("reset pamięci"), Use("prawy przycisk"),
        Eat("jedzenie"), On("włączenie modułu"), Off("wyłączenie modułu"), Change("zmiana modułu"),
        Craft("crafting"), Smelt("przetapianie"), Sleep("spanie"), Store("chowanie do skrzyni"), Farm("farma"),
        Record("nagrywanie mnie"), Replay("powtarzanie po mnie"),
        Hello("powitanie"), Thanks("podziękowanie"), HowAreYou("pogawędka");

        final String label;

        Intent(String label) {
            this.label = label;
        }
    }

    /** The seed knowledge: example sentences for every meaning. */
    private static final Object[][] SEED = {
            {Intent.Go, "idz do przodu", "idz 5 kratek", "przejdz kawalek", "rusz sie", "chodz w lewo", "idz w prawo",
                    "podejdz troche", "przesun sie", "idz na polnoc", "idz dwie kratki", "idz 2 bloki do tylu", "zrob krok",
                    "idz kawalek do przodu", "przejdz na wschod", "cofnij sie", "idz prosto", "idz do drzewa", "podejdz do krowy",
                    "idz do wody", "podejdz do owcy", "idz do skrzyni", "idz kolo pieca"},
            {Intent.Village, "idz do wioski", "znajdz wioske", "szukaj wioski", "zaprowadz mnie do wioski", "gdzie jest wioska", "idz do najblizszej wioski", "znajdz wiesniakow", "chce do wioski"},
            {Intent.Wander, "idz gdzies", "pospaceruj", "pochodz sobie", "zwiedzaj", "idz przed siebie", "pozwiedzaj mape",
                    "idz gdziekolwiek", "poszwedaj sie", "pochodz po mapie", "idz na spacer"},
            {Intent.Back, "wroc", "wracaj", "wroc tam gdzie byles", "wroc na miejsce", "wracaj na start", "przyjdz z powrotem"},
            {Intent.Mine, "wykop diamenty", "kop diamenty", "nakop zelaza", "wydobadz wegiel", "zbierz drewno", "zetnij drzewo",
                    "kop rude", "nakop mi diamentow", "szukaj diamentow", "wykop kamien", "naszukaj zlota", "nazbieraj drewna",
                    "wykop mi troche zelaza", "potrzebuje diamentow", "przynies diamenty", "kop", "wydobywaj rudy", "nakop wegla"},
            {Intent.Hut, "zbuduj dom", "zbuduj domek", "postaw chatke", "zrob schronienie", "zbuduj baze", "zbuduj mi dom",
                    "postaw domek", "zrob dom", "potrzebuje schronienia"},
            {Intent.Wall, "zbuduj sciane", "postaw mur", "zrob sciane z blokow", "zbuduj mur", "postaw sciane"},
            {Intent.Pillar, "zbuduj slup", "postaw wieze", "zrob slupek", "wejdz do gory na blokach", "zbuduj kolumne",
                    "zbuduj wieze", "wyskocz w gore na blokach", "zrob wieze", "zrob wieze z blokow", "postaw slup z blokow"},
            {Intent.Fight, "walcz", "bij moby", "zabij zombie", "atakuj potwory", "walcz z mobami", "bron mnie", "obron mnie",
                    "zabijaj moby", "pozabijaj potwory", "idz walczyc", "zabij szkieleta", "bij sie", "zabij owce", "bij krowy",
                    "zabij swinie", "walcz ze szkieletami", "upoluj kurczaka", "zabij wszystko"},
            {Intent.Stop, "stop", "stoj", "przestan", "koniec", "zatrzymaj sie", "nic nie rob", "czekaj", "spokoj", "wystarczy",
                    "przestan to robic", "dosc"},
            {Intent.Learn, "ucz sie", "naucz sie", "trenuj", "cwicz", "ucz sie sam", "ucz sie mechanik", "trenuj water clutch",
                    "ucz sie grac", "naucz sie grac", "rob postepy", "cwicz sam", "graj sam", "graj", "baw sie sam", "przetrwaj",
                    "graj samodzielnie", "radz sobie sam", "zyj sam"},
            {Intent.Stats, "statystyki", "ile zabiles", "jak ci idzie", "wyniki", "postepy", "pokaz statystyki", "ile masz zabojstw", "ile mobow zabiles"},
            {Intent.Explain, "czego sie nauczyles", "co umiesz", "jak walczysz", "jaka masz strategie", "co wiesz", "czego sie dowiedziales", "co potrafisz", "co juz umiesz"},
            {Intent.Reset, "zapomnij wszystko", "reset", "zacznij od nowa", "wyczysc pamiec", "zresetuj sie"},
            {Intent.Use, "klikaj prawym", "trzymaj prawy", "uzywaj przedmiotu", "blokuj tarcza", "trzymaj tarcze", "klikaj prawy przycisk"},
            {Intent.Eat, "zjedz cos", "jedz", "najedz sie", "zjedz", "jestes glodny", "zjedz jedzenie", "nakarm sie"},
            {Intent.On, "wlacz", "odpal", "uruchom", "pokaz", "wlacz to", "daj"},
            {Intent.Off, "wylacz", "zgas", "schowaj", "ukryj", "wylacz to", "zabierz to"},
            {Intent.Change, "zmien", "przelacz", "zmien tryb", "daj inne", "nastepne", "zmien na inne", "zmien kolor"},
            {Intent.Craft, "zrob miecz", "zcraftuj kilof", "zrob stol", "wytworz pochodnie", "zrob deski", "zrob patyki", "zrob mi zelazny miecz",
                    "stworz siekiere", "zrob piec", "zrob skrzynie", "zcraftuj"},
            {Intent.Smelt, "przetop zelazo", "przetop rude", "upiecz mieso", "wytop zloto", "wrzuc do pieca", "zrob sztabki"},
            {Intent.Sleep, "idz spac", "spij", "zasnij", "poloz sie do lozka", "dobranoc", "przespij noc"},
            {Intent.Store, "schowaj rzeczy do skrzyni", "odloz rzeczy", "wyladuj ekwipunek", "wrzuc wszystko do skrzyni", "schowaj"},
            {Intent.Farm, "zbierz zboze", "zajmij sie farma", "posadz nasiona", "zbierz plony", "zasiej pole", "farmuj", "zniwa"},
            {Intent.Record, "patrz co robie", "nagrywaj", "ucz sie ode mnie", "zapamietaj co robie", "obserwuj mnie", "nagraj"},
            {Intent.Replay, "powtorz", "zrob to samo", "powtorz to co robilem", "odtworz", "zrob jak ja"},
            {Intent.Hello, "czesc", "hej", "siema", "elo", "witaj", "dzien dobry", "yo", "hejka", "siemka"},
            {Intent.Thanks, "dzieki", "dziekuje", "super", "dobra robota", "brawo", "fajnie", "swietnie", "dobrze"},
            {Intent.HowAreYou, "jak sie masz", "co tam", "jak leci", "co robisz", "kim jestes", "jak sie czujesz"},
    };

    /** Words the spell-checker knows: actions, materials, places, things. */
    private static final String[] DICTIONARY = {
            "idz", "wykop", "kop", "kopac", "nakop", "zbuduj", "postaw", "wlacz", "wylacz", "zmien", "walcz", "ucz", "sie",
            "wroc", "stop", "zjedz", "zetnij", "zbierz", "wydobadz", "szukaj", "trenuj", "cwicz", "klikaj", "trzymaj",
            "diamenty", "diament", "zelazo", "zelaza", "zloto", "zlota", "wegiel", "wegla", "miedz", "redstone", "lapis",
            "szmaragdy", "drewno", "drewna", "drzewo", "kamien", "ziemia", "piasek", "niebo", "jasnosc", "fullbright",
            "przodu", "przod", "tylu", "lewo", "prawo", "polnoc", "poludnie", "wschod", "zachod", "domek", "dom", "sciane",
            "sciana", "slup", "wieze", "kratki", "kratek", "bloki", "blokow", "gdzies", "moby", "zombie", "statystyki",
            "napisy", "muzyka", "wyspa", "zbroja", "smuga", "trail", "mape", "prawym", "jedzenie",
            "wioska", "wioski", "wioske", "najblizszej", "wiesniakow", "zrob", "przetop", "spac", "schowaj", "skrzyni", "zboze",
            "farma", "nagrywaj", "powtorz", "miecz", "kilof", "pochodnie", "piec", "stol", "zelazo",
            "dzieki", "dziekuje", "czesc", "siema", "hej", "super", "brawo", "jak", "leci", "potrafisz", "umiesz", "zabojstw",
    };

    private static final Map<Intent, List<Map<String, Double>>> examples = new HashMap<>();
    private static final List<String[]> learned = new ArrayList<>();
    private static boolean ready;

    /** The result: what was understood, the cleaned-up sentence, and how sure it is (0..1). */
    record Meaning(Intent intent, String sentence, double confidence) {
    }

    /** Understands a (normalised, diacritics-free) sentence. */
    static Meaning understand(String msg) {
        init();
        String fixed = spellcheck(msg);
        Map<String, Double> v = vector(fixed.replaceAll("\\d+", " "));
        Intent best = Intent.Stop;
        double bestScore = 0;
        for (var e : examples.entrySet())
            for (Map<String, Double> ex : e.getValue()) {
                double s = cosine(v, ex);
                if (s > bestScore) {
                    bestScore = s;
                    best = e.getKey();
                }
            }
        return new Meaning(best, fixed, bestScore);
    }

    /** Adds a sentence as a new example of a meaning, and keeps it for next time. */
    static void learn(String sentence, Intent intent) {
        init();
        examples.get(intent).add(vector(sentence.replaceAll("\\d+", " ")));
        learned.add(new String[]{intent.name(), sentence});
        try {
            StringBuilder sb = new StringBuilder();
            for (String[] l : learned) sb.append(l[0]).append('\t').append(l[1]).append('\n');
            Files.createDirectories(file().getParentFile().toPath());
            Files.writeString(file().toPath(), sb.toString(), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }

    static int learnedCount() {
        init();
        return learned.size();
    }

    private static File file() {
        return new File(ConfigManager.MISC_FOLDER, "learnai_phrases.txt");
    }

    private static void init() {
        if (ready) return;
        ready = true;
        for (Intent i : Intent.values()) examples.put(i, new ArrayList<>());
        for (Object[] row : SEED) {
            Intent intent = (Intent) row[0];
            for (int k = 1; k < row.length; k++) examples.get(intent).add(vector((String) row[k]));
        }
        try {
            if (!file().exists()) return;
            for (String line : Files.readString(file().toPath(), StandardCharsets.UTF_8).split("\n")) {
                String[] f = line.split("\t", 2);
                if (f.length < 2) continue;
                try {
                    Intent intent = Intent.valueOf(f[0]);
                    examples.get(intent).add(vector(f[1]));
                    learned.add(f);
                } catch (IllegalArgumentException ignored) {
                }
            }
        } catch (Exception ignored) {
        }
    }

    /* ------------------------------------------------------------------ */
    /* Spelling                                                            */
    /* ------------------------------------------------------------------ */

    /** Each word close enough to a dictionary word becomes that word; a long unknown word is tried as two glued ones. */
    static String spellcheck(String msg) {
        StringBuilder out = new StringBuilder();
        for (String w : msg.split("\\s+")) {
            if (w.isEmpty()) continue;
            if (!out.isEmpty()) out.append(' ');
            if (w.matches("\\d+") || w.length() <= 2) {
                out.append(w);
                continue;
            }
            // a long word may be two glued together - try that before squeezing it into one dictionary word
            String split = w.length() >= 9 ? splitGlued(w) : null;
            if (split != null) {
                out.append(split);
                continue;
            }
            String fixed = closest(w, 0.6);
            out.append(fixed != null ? fixed : w);
        }
        return out.toString();
    }

    private static String closest(String w, double min) {
        String best = null;
        double bestScore = min;
        for (String d : DICTIONARY) {
            if (d.equals(w)) return d;
            double s = dice(w, d);
            if (s > bestScore) {
                bestScore = s;
                best = d;
            }
        }
        return best;
    }

    /** "kopacdiamenty" -> "kopac diamenty": the split where both halves are closest to known words. */
    private static String splitGlued(String w) {
        if (w.length() < 7) return null;
        String best = null;
        double bestScore = 1.25;
        for (int i = 3; i <= w.length() - 3; i++) {
            String a = w.substring(0, i), b = w.substring(i);
            String fa = closest(a, 0.5), fb = closest(b, 0.5);
            if (fa == null || fb == null) continue;
            double s = dice(a, fa) + dice(b, fb);
            if (s > bestScore) {
                bestScore = s;
                best = fa + " " + fb;
            }
        }
        return best;
    }

    /* ------------------------------------------------------------------ */
    /* Similarity                                                          */
    /* ------------------------------------------------------------------ */

    /** Letter triples of every word (with word edges marked), counted. */
    private static Map<String, Double> vector(String text) {
        Map<String, Double> v = new HashMap<>();
        for (String w : text.split("[^a-z0-9]+")) {
            if (w.isEmpty()) continue;
            String p = "^" + w + "$";
            for (int i = 0; i + 3 <= p.length(); i++) v.merge(p.substring(i, i + 3), 1.0, Double::sum);
            // short words count whole too, so "kop" and "dom" are not lost
            v.merge("#" + w, 1.5, Double::sum);
        }
        return v;
    }

    private static double cosine(Map<String, Double> a, Map<String, Double> b) {
        double dot = 0, na = 0, nb = 0;
        for (var e : a.entrySet()) {
            na += e.getValue() * e.getValue();
            Double o = b.get(e.getKey());
            if (o != null) dot += e.getValue() * o;
        }
        for (double x : b.values()) nb += x * x;
        return na == 0 || nb == 0 ? 0 : dot / Math.sqrt(na * nb);
    }

    private static double dice(String a, String b) {
        if (a.length() < 2 || b.length() < 2) return a.equals(b) ? 1 : 0;
        Map<String, Integer> grams = new HashMap<>();
        for (int i = 0; i + 2 <= a.length(); i++) grams.merge(a.substring(i, i + 2), 1, Integer::sum);
        int hit = 0;
        for (int i = 0; i + 2 <= b.length(); i++) {
            String g = b.substring(i, i + 2);
            Integer c = grams.get(g);
            if (c != null && c > 0) {
                hit++;
                grams.put(g, c - 1);
            }
        }
        return 2.0 * hit / (a.length() - 1 + b.length() - 1);
    }

    /* ------------------------------------------------------------------ */
    /* Small talk                                                          */
    /* ------------------------------------------------------------------ */

    private static final Random random = new Random();

    static String smallTalk(Intent intent) {
        String[] lines = switch (intent) {
            case Hello -> new String[]{"Cześć! Co robimy?", "Siema! Daj mi jakieś zadanie.", "Hej! Mogę kopać, budować, walczyć albo się uczyć."};
            case Thanks -> new String[]{"Nie ma sprawy!", "Spoko, dla ciebie wszystko.", "Dzięki, staram się!"};
            default -> new String[]{"Dobrze, uczę się grać coraz lepiej.", "Jestem twoim AI - kopię, buduję, walczę i uczę się.", "Czekam na zadanie!"};
        };
        return lines[random.nextInt(lines.length)];
    }
}
