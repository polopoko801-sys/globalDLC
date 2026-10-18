package thunder.hack.features.modules.misc.rynek;

import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ItemEnchantmentsComponent;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A "quick offer" the user sets up by middle-clicking an item on the market: which item, the max
 * price they'll pay, and what the enchantments have to look like. Persisted by
 * {@link RynekConfigManager} in {@code buywatch.json} and edited in {@link RynekOfferEditor}.
 *
 * <p>Every enchantment captured off the item is in one of three states, and that is the whole point
 * of the editor: <b>required</b> (the listing must carry it at that level or better), <b>unwanted</b>
 * (the listing must not carry it at all), or neither — don't care. Middle-clicking a pair of boots
 * with Protection 6 therefore snipes Protection 6 boots by default, instead of any boots at all,
 * which is what made the old "max price" offers buy junk.
 */
public class RynekBuyEntry {
    private String name;            // item registry id, e.g. "minecraft:nether_star"
    private String displayName;     // human label captured at creation time
    private long maxPrice;
    private List<String> unwantedEnchants = new ArrayList<>();
    private List<String> requiredEnchants = new ArrayList<>();
    private List<String> enchants = new ArrayList<>(); // enchants captured off the item (the editor's rows)
    /**
     * Minimum days left on a timed listing — pets and costumes run out, and one with two days on it is
     * worth nothing near the price of a fresh one. 0 = don't care, which is the default: most items on
     * the market carry no expiry line at all.
     */
    private int minDays;
    /**
     * Minimum "Dodatkowe obrażenia" percentage — the roll that decides what a sword is actually worth.
     * 0 = don't care.
     */
    private int minPercent;
    /**
     * Require the listing's display name to be the one captured. On a server with custom gear this is
     * the only thing that separates two items sharing an item id — "Ana bania 2" and "Ana bania" are
     * both netherite helmets, and the level printed on them ("Ochrona 6") lives in the name and lore,
     * not in the enchantment component, which caps out at the vanilla value. Same for books, which are
     * all {@code enchanted_book} and differ only by name.
     */
    private Boolean matchName;

    public RynekBuyEntry(String name, String displayName, long maxPrice, List<String> unwantedEnchants) {
        this(name, displayName, maxPrice, unwantedEnchants, null);
    }

    public RynekBuyEntry(String name, String displayName, long maxPrice, List<String> unwantedEnchants, List<String> enchants) {
        this.name = name;
        this.displayName = displayName;
        this.maxPrice = maxPrice;
        this.unwantedEnchants = unwantedEnchants != null ? unwantedEnchants : new ArrayList<>();
        this.enchants = enchants != null ? enchants : new ArrayList<>();
        this.requiredEnchants = new ArrayList<>();
    }

    /**
     * Builds an offer from a held/hovered stack, capturing its id, display name and enchantments.
     * Everything the item is enchanted with starts out REQUIRED — you middle-clicked those boots
     * because of what is on them.
     */
    public static RynekBuyEntry fromStack(ItemStack stack) {
        String id = Registries.ITEM.getId(stack.getItem()).toString();
        String display = stack.getName().getString();
        List<String> ench = readEnchants(stack);
        RynekBuyEntry entry = new RynekBuyEntry(id, display, 0, new ArrayList<>(), ench);
        entry.requiredEnchants = new ArrayList<>(ench);
        entry.icon = RynekIcon.from(stack);
        // Captured for display and left as "obojętne": the server words some lines per listing, and
        // demanding all of them by default would build a rule that quietly matches nothing.
        entry.loreLines = readLore(stack);
        return entry;
    }

    /**
     * Enchantments on a stack as {@code "path level"} strings, e.g. {@code "protection 6"}.
     * <p>
     * Enchanted books keep theirs in STORED_ENCHANTMENTS, not ENCHANTMENTS — reading only the latter is
     * why every book looked identical (empty enchant list) and no rule could tell "Niepodpalenie" from
     * anything else on the market.
     */
    /**
     * Re-reads everything that comes off the item — display name, enchantments, lore — while keeping
     * every decision the user made: max price, which lines are required or unwanted, day and percent
     * limits, the name lock.
     * <p>
     * Required enchantments are re-pointed to the levels now on the item, so a rule captured back when
     * the reader said "protection 5" starts demanding the 6 the item actually has.
     */
    public void refreshFrom(ItemStack stack) {
        this.displayName = Formatting.strip(stack.getName().getString());
        this.enchants = readEnchants(stack);
        this.loreLines = readLore(stack);
        // re-capture the model too: offers saved before this existed have no icon data at all, and
        // clicking the item again in /ah is the natural moment to pick it up
        this.icon = RynekIcon.from(stack);

        List<String> refreshed = new ArrayList<>();
        for (String old : getRequiredEnchants()) {
            String path = enchantPath(old);
            String replacement = old;
            for (String now : this.enchants)
                if (enchantPath(now).equals(path)) {
                    replacement = now;
                    break;
                }
            refreshed.add(replacement);
        }
        this.requiredEnchants = refreshed;
    }

    public static List<String> readEnchants(ItemStack stack) {
        Map<String, Integer> levels = new LinkedHashMap<>();
        collect(levels, EnchantmentHelper.getEnchantments(stack));
        try {
            collect(levels, stack.get(DataComponentTypes.STORED_ENCHANTMENTS));
        } catch (Exception ignored) {
        }
        collectFromLore(levels, stack);

        List<String> out = new ArrayList<>(levels.size());
        levels.forEach((path, level) -> out.add(path + " " + level));
        return out;
    }

    private static void collect(Map<String, Integer> out, ItemEnchantmentsComponent comp) {
        if (comp == null) return;
        try {
            for (RegistryEntry<Enchantment> e : comp.getEnchantments()) {
                String path = e.getKey().map(k -> k.getValue().getPath()).orElse("?");
                out.merge(path, comp.getLevel(e), Math::max);
            }
        } catch (Exception ignored) {
        }
    }

    /**
     * Enchantments as the server WRITES them, off the lore: {@code Ochrona VI}, {@code Ostrość 7}.
     * <p>
     * This is the only place a level above the vanilla cap exists. The enchantment component on an
     * "Ana bania 2" still reads protection 5 — the 6 lives in the text — so reading components alone
     * reported every tier-2 piece as a plain one. Where a Polish name maps onto a known enchantment the
     * two are merged and the HIGHER level wins; anything unrecognised is kept under its own wording, so
     * event gear with its own stat lines still shows up.
     */
    private static void collectFromLore(Map<String, Integer> out, ItemStack stack) {
        for (String line : readLore(stack)) {
            Matcher m = LORE_ENCHANT.matcher(line.trim());
            if (!m.matches()) continue;
            int level = parseLevel(m.group(2));
            if (level <= 0) continue;
            String word = fold(m.group(1)).trim().replace(' ', '_');
            out.merge(POLISH_ENCHANTS.getOrDefault(word, word), level, Math::max);
        }
    }

    /**
     * "Ochrona VI" / "Ostrość 7" — a name followed by a level, and nothing else on the line.
     * <p>
     * A leading bullet or arrow is allowed and thrown away: this server prefixes stat lines with things
     * like {@code » } or {@code ✦ }, and anchoring strictly at the name would skip every one of them.
     * A trailing dot or comma is tolerated for the same reason.
     */
    private static final Pattern LORE_ENCHANT = Pattern.compile(
            "^[^\\p{L}\\d]{0,4}\\s*([\\p{L}][\\p{L} ]{2,28}?)\\s*[:\\-\\u2013]?\\s*([IVXLC]{1,6}|\\d{1,3})\\s*[^\\p{L}\\d]{0,4}$");

    /** Polish enchantment names as this server prints them, mapped onto the registry paths. */
    private static final Map<String, String> POLISH_ENCHANTS = Map.ofEntries(
            Map.entry("ochrona", "protection"),
            Map.entry("niepodpalenie", "fire_protection"),
            Map.entry("ochrona_przed_pociskami", "projectile_protection"),
            Map.entry("ochrona_przed_eksplozjami", "blast_protection"),
            Map.entry("ostrosc", "sharpness"),
            Map.entry("wytrzymalosc", "unbreaking"),
            Map.entry("naprawa", "mending"),
            Map.entry("efektywnosc", "efficiency"),
            Map.entry("szczescie", "fortune"),
            Map.entry("jedwabny_dotyk", "silk_touch"),
            Map.entry("grabiez", "looting"),
            Map.entry("odrzut", "knockback"),
            Map.entry("zywiol_ognia", "fire_aspect"),
            Map.entry("plomien", "flame"),
            Map.entry("sila", "power"),
            Map.entry("odepchniecie", "punch"),
            Map.entry("nieskonczonosc", "infinity"),
            Map.entry("ciernie", "thorns"),
            Map.entry("oddech", "respiration"),
            Map.entry("powinowactwo_wodne", "aqua_affinity"),
            Map.entry("przebicie", "piercing"),
            Map.entry("wielostrzal", "multishot"),
            Map.entry("szybki_przeladunek", "quick_charge"),
            Map.entry("lojalnosc", "loyalty"),
            Map.entry("nadziewanie", "impaling"),
            Map.entry("przywolanie_pioruna", "channeling"),
            Map.entry("blogoslawienstwo_wody", "depth_strider"),
            Map.entry("stapanie_po_sniegu", "frost_walker"),
            Map.entry("lekki_krok", "feather_falling"));

    /** A level written as digits or as a Roman numeral. */
    private static int parseLevel(String raw) {
        if (raw == null || raw.isBlank()) return 0;
        String s = raw.trim();
        if (s.chars().allMatch(Character::isDigit)) {
            try {
                return Integer.parseInt(s);
            } catch (NumberFormatException e) {
                return 0;
            }
        }
        int total = 0, prev = 0;
        for (int i = s.length() - 1; i >= 0; i--) {
            int v = switch (Character.toUpperCase(s.charAt(i))) {
                case 'I' -> 1;
                case 'V' -> 5;
                case 'X' -> 10;
                case 'L' -> 50;
                case 'C' -> 100;
                default -> 0;
            };
            if (v == 0) return 0;
            if (v < prev) total -= v;
            else {
                total += v;
                prev = v;
            }
        }
        return total;
    }

    /** The {@code path} half of a {@code "path level"} entry. */
    public static String enchantPath(String spec) {
        if (spec == null) return "";
        int sp = spec.trim().lastIndexOf(' ');
        return (sp <= 0 ? spec : spec.substring(0, sp)).trim().toLowerCase();
    }

    /** The {@code level} half of a {@code "path level"} entry; 1 when it carries no number. */
    public static int enchantLevel(String spec) {
        if (spec == null) return 1;
        int sp = spec.trim().lastIndexOf(' ');
        if (sp <= 0) return 1;
        try {
            return Integer.parseInt(spec.trim().substring(sp + 1));
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    /**
     * The captured look of the item, so the offer draws with the server's texture rather than the base
     * item's. Null on every offer saved before this field existed — {@link #getIcon()} covers that.
     */
    private RynekIcon icon;

    /** Never null: an offer from an older config falls back to a bare stack built from the item id. */
    public RynekIcon getIcon() {
        if (icon == null) icon = new RynekIcon();
        return icon;
    }

    /** The offer's item, drawn with whatever model the server had on it. */
    public ItemStack toIconStack() {
        ItemStack stack = getIcon().toStack();
        if (!stack.isEmpty()) return stack;
        try {
            Identifier id = Identifier.tryParse(name);
            if (id == null) return ItemStack.EMPTY;
            var item = Registries.ITEM.get(id);
            return item == null ? ItemStack.EMPTY : new ItemStack(item);
        } catch (Exception e) {
            return ItemStack.EMPTY;
        }
    }

    public String getName() {
        return name;
    }

    public String getDisplayName() {
        return displayName != null ? displayName : name;
    }

    public long getMaxPrice() {
        return maxPrice;
    }

    public void setMaxPrice(long maxPrice) {
        this.maxPrice = Math.max(0, maxPrice);
    }

    public List<String> getUnwantedEnchants() {
        if (unwantedEnchants == null) unwantedEnchants = new ArrayList<>();
        return unwantedEnchants;
    }

    public void setUnwantedEnchants(List<String> unwantedEnchants) {
        this.unwantedEnchants = unwantedEnchants != null ? unwantedEnchants : new ArrayList<>();
    }

    public List<String> getRequiredEnchants() {
        if (requiredEnchants == null) requiredEnchants = new ArrayList<>();
        return requiredEnchants;
    }

    public void setRequiredEnchants(List<String> requiredEnchants) {
        this.requiredEnchants = requiredEnchants != null ? requiredEnchants : new ArrayList<>();
    }

    public List<String> getEnchants() {
        if (enchants == null) enchants = new ArrayList<>();
        return enchants;
    }

    public int getMinDays() {
        return minDays;
    }

    public void setMinDays(int minDays) {
        this.minDays = Math.max(0, minDays);
    }

    /**
     * Defaults to ON, and the field is a {@link Boolean} for exactly that reason: this class has no
     * no-arg constructor, so Gson builds it through Unsafe and field initialisers never run. A plain
     * {@code boolean} would therefore have come back false for every offer already saved in
     * buywatch.json — the name lock would have been silently off on all the existing rules, which is
     * every rule the user actually has.
     */
    public boolean isMatchName() {
        return matchName == null || matchName;
    }

    public void setMatchName(boolean matchName) {
        this.matchName = matchName;
    }

    public int getMinPercent() {
        return minPercent;
    }

    public void setMinPercent(int minPercent) {
        this.minPercent = Math.max(0, minPercent);
    }

    /**
     * The item's own lore lines — the "anarchia text" that carries the real stats: {@code Ochrona VI},
     * {@code Dodatkowe obrażenia 12%}, tier names, event markers. On this server that text is where the
     * item's identity actually lives; the enchantment components are capped at vanilla values and say
     * nothing about a tier-2 piece.
     * <p>
     * The market's OWN lines (price, seller, expiry, click hints) are stripped out on capture — those
     * differ per listing, so keeping them would mean a rule that can never match again.
     */
    private List<String> loreLines = new ArrayList<>();
    private List<String> requiredLore = new ArrayList<>();
    private List<String> unwantedLore = new ArrayList<>();

    /** Lines the market adds to every listing; not part of what the item IS. */
    private static final String[] MARKET_NOISE = {
            "wygasnie", "sprzedawca", "wystawi", "kliknij", "ppm", "lpm", "cena", "koszt",
            "kup", "aukcj", "oferta", "czas", "pozosta"
    };

    /** Item lore with the market's own bookkeeping removed. */
    public static List<String> readLore(ItemStack stack) {
        List<String> out = new ArrayList<>();
        List<String> kept = new ArrayList<>();
        try {
            LoreComponent lore = stack.get(DataComponentTypes.LORE);
            if (lore == null) return out;
            for (Text line : lore.lines()) {
                String raw = Formatting.strip(line.getString());
                if (raw == null) continue;
                String t = raw.trim();
                if (t.isEmpty()) continue;
                if (t.contains("$")) continue;          // price, in brackets or not
                if (!kept.contains(t)) kept.add(t);
                if (isMarketNoise(t)) continue;
                if (!out.contains(t)) out.add(t);
            }
        } catch (Exception ignored) {
        }
        // Every single line looked like bookkeeping. Showing the item's text as it came beats showing
        // nothing at all — an empty section is indistinguishable from an item with no description, and
        // that is exactly how a filter that is slightly too eager hides itself.
        return out.isEmpty() ? kept : out;
    }

    /**
     * True for the market's OWN lines rather than the item's.
     * <p>
     * Matched as a LABEL — the word has to open the line (past any {@code » } decoration) or sit right
     * in front of a colon — instead of anywhere inside it. A plain "contains" threw away real item text:
     * anything whose description mentioned a price, a time or a click lost the whole line, and an item
     * built entirely out of such lines came back with no description at all.
     */
    private static boolean isMarketNoise(String line) {
        String folded = fold(line).replaceAll("^[^\\p{L}\\d]+", "").trim();
        int colon = folded.indexOf(':');
        String label = colon > 0 ? folded.substring(0, colon) : folded;
        for (String n : MARKET_NOISE)
            if (label.startsWith(n) || folded.startsWith(n)) return true;
        return false;
    }

    /** Lowercase and strip Polish diacritics so captured text compares against a live listing. */
    public static String fold(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length());
        for (char c : s.toLowerCase().toCharArray())
            sb.append(switch (c) {
                case 'ą' -> 'a';
                case 'ć' -> 'c';
                case 'ę' -> 'e';
                case 'ł' -> 'l';
                case 'ń' -> 'n';
                case 'ó' -> 'o';
                case 'ś' -> 's';
                case 'ż', 'ź' -> 'z';
                default -> c;
            });
        return sb.toString();
    }

    public List<String> getLoreLines() {
        if (loreLines == null) loreLines = new ArrayList<>();
        return loreLines;
    }

    public List<String> getRequiredLore() {
        if (requiredLore == null) requiredLore = new ArrayList<>();
        return requiredLore;
    }

    public List<String> getUnwantedLore() {
        if (unwantedLore == null) unwantedLore = new ArrayList<>();
        return unwantedLore;
    }

    public EnchantState loreStateOf(String line) {
        String f = fold(line);
        for (String u : getUnwantedLore())
            if (fold(u).equals(f)) return EnchantState.UNWANTED;
        for (String r : getRequiredLore())
            if (fold(r).equals(f)) return EnchantState.REQUIRED;
        return EnchantState.ANY;
    }

    /** REQUIRED → ANY → UNWANTED → REQUIRED, same cycle as the enchant rows. */
    public void cycleLoreState(String line) {
        EnchantState next = switch (loreStateOf(line)) {
            case REQUIRED -> EnchantState.ANY;
            case ANY -> EnchantState.UNWANTED;
            case UNWANTED -> EnchantState.REQUIRED;
        };
        String f = fold(line);
        getUnwantedLore().removeIf(u -> fold(u).equals(f));
        getRequiredLore().removeIf(r -> fold(r).equals(f));
        switch (next) {
            case REQUIRED -> getRequiredLore().add(line);
            case UNWANTED -> getUnwantedLore().add(line);
            case ANY -> {
            }
        }
    }

    /** Three-way state of one captured enchantment, as shown and cycled in the editor. */
    public enum EnchantState { REQUIRED, ANY, UNWANTED }

    public EnchantState stateOf(String spec) {
        String path = enchantPath(spec);
        for (String u : getUnwantedEnchants())
            if (enchantPath(u).equals(path)) return EnchantState.UNWANTED;
        for (String r : getRequiredEnchants())
            if (enchantPath(r).equals(path)) return EnchantState.REQUIRED;
        return EnchantState.ANY;
    }

    /** REQUIRED → ANY → UNWANTED → REQUIRED. */
    public void cycleState(String spec) {
        setState(spec, switch (stateOf(spec)) {
            case REQUIRED -> EnchantState.ANY;
            case ANY -> EnchantState.UNWANTED;
            case UNWANTED -> EnchantState.REQUIRED;
        });
    }

    public void setState(String spec, EnchantState state) {
        String path = enchantPath(spec);
        getUnwantedEnchants().removeIf(u -> enchantPath(u).equals(path));
        getRequiredEnchants().removeIf(r -> enchantPath(r).equals(path));
        switch (state) {
            case REQUIRED -> getRequiredEnchants().add(spec);
            case UNWANTED -> getUnwantedEnchants().add(path);
            case ANY -> {
            }
        }
    }
}
