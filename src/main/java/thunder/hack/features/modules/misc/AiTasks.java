package thunder.hack.features.modules.misc;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.entity.ItemEntity;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import thunder.hack.utility.player.InventoryUtility;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;

import static thunder.hack.features.modules.Module.mc;

/**
 * LearnAI's hands: understands plain orders ("idź 2 kratki do przodu", "wykop 8 diamentów", "zbuduj domek") and
 * carries them out one at a time - walking, mining a kind of block, building a wall, a pillar or a small hut.
 *
 * <p>Understanding is local and simple: it picks the action word, a number and a direction or a material out of
 * the sentence, in Polish (with or without diacritics) and some English. Singleplayer only.
 *
 * <p>Safety is hard-coded: it never breaks a block that has lava or water next to it, never steps into lava, fire
 * or off a drop, and gives up on a target it cannot reach instead of digging forever.
 */
final class AiTasks {
    private final Consumer<String> say;

    AiTasks(Consumer<String> say) {
        this.say = say;
    }

    /* ------------------------------------------------------------------ */
    /* Understanding                                                       */
    /* ------------------------------------------------------------------ */

    private interface Task {
        /** One tick of work; false once the task is over. */
        boolean tick();

        String describe();
    }

    private Task task;
    /** Where the last task started - "wróć" goes back here. */
    private Vec3d home;

    /** Whether it is playing on its own ("ucz się") - the agent may break off to fight. */
    boolean learning() {
        return task instanceof Learn;
    }

    boolean active() {
        return task != null;
    }

    String current() {
        return task == null ? null : task.describe();
    }

    void stop() {
        task = null;
        release();
    }

    /**
     * Reads an order. Returns what to answer when it is a task (and starts it), or null when the sentence is not
     * about walking, mining or building - the caller then tries its own orders.
     */
    String tryStart(String msg) {
        boolean go = msg.matches(".*\\b(idz|pojdz|rusz|przejdz|chodz|walk|go|podejdz|przesun)\\b.*");
        boolean back = msg.matches(".*\\b(wroc|wracaj|come back|return)\\b.*");
        boolean mine = msg.matches(".*\\b(kop|wykop|wykopaj|kopnij|wydobadz|wydobac|zbierz|zetnij|scinaj|nazbieraj|mine|dig|chop|kopac|kopiesz)\\b.*")
                || msg.matches(".*\\b(kop\\w*)\\b.*");
        boolean build = msg.matches(".*\\b(zbuduj|buduj|postaw|budowac|build|zrob)\\b.*");

        if (!mc.isInSingleplayer()) return go || back || mine || build ? "Chodzenie, kopanie i budowanie robię tylko w świecie jednoosobowym." : null;

        // ---- survival skills ----
        boolean sp = mc.isInSingleplayer();
        if (msg.matches(".*\\b(nagrywaj|nagraj|nagrywanie|obserwuj|patrz co|zapamietaj co|ucz sie ode mnie|record)\\w*.*")) {
            if (!sp) return "Nagrywam tylko w świecie jednoosobowym.";
            if (recordingOn) {
                int sec = stopRecording();
                return "Koniec nagrywania: " + sec + " s. Napisz \"powtórz\", a zrobię to samo.";
            }
            startRecording();
            return "Nagrywam, co robisz. Gdy skończysz, napisz \"koniec nagrywania\" albo jeszcze raz \"nagrywaj\".";
        }
        if (recordingOn && msg.matches(".*\\b(koniec|stop|wystarczy|zakoncz)\\w*.*")) {
            int sec = stopRecording();
            return "Koniec nagrywania: " + sec + " s. Napisz \"powtórz\", a zrobię to samo.";
        }
        if (msg.matches(".*\\b(powtorz|powtarzaj|odtworz|replay|zrob to samo|zrob jak ja)\\w*.*")) {
            if (!sp) return "Powtarzam tylko w świecie jednoosobowym.";
            if (recording.isEmpty()) return "Nic jeszcze nie nagrałem. Napisz \"nagrywaj\" i zrób to, czego mam się nauczyć.";
            start(new Replay());
            return "Powtarzam to, co robiłeś (" + recording.size() / 20 + " s).";
        }
        if (msg.matches(".*\\b(spij|spac|zasnij|dobranoc|lozk\\w*|sleep)\\b.*") && !build) {
            if (!sp) return "Śpię na rozkaz tylko w świecie jednoosobowym.";
            long t = mc.world.getTimeOfDay() % 24000L;
            if (t < 12542 || t > 23460) return "Teraz jest dzień - spać można tylko w nocy.";
            start(new Sleep());
            return "Idę spać do najbliższego łóżka.";
        }
        if (msg.matches(".*\\b(schowaj|odloz|wrzuc|wyladuj|skrzyni\\w*|chest)\\b.*") && !build) {
            if (!sp) return "To robię tylko w świecie jednoosobowym.";
            start(new Store());
            return "Chowam rzeczy do najbliższej skrzyni.";
        }
        if (msg.matches(".*\\b(farm\\w*|zbior\\w*|zasiej|posadz|zniw\\w*|plony|plonow|zboz\\w*|pszenic\\w*|harvest)\\b.*") && !build) {
            if (!sp) return "Na farmie pracuję tylko w świecie jednoosobowym.";
            start(new Farm());
            return "Zbieram dojrzałe plony i sadzę nowe.";
        }
        if (msg.matches(".*\\b(przetop\\w*|wytop\\w*|upiecz\\w*|piecu|smelt\\w*)\\b.*")) {
            if (!sp) return "Przetapiam tylko w świecie jednoosobowym.";
            net.minecraft.item.Item in = msg.matches(".*zelaz.*") ? net.minecraft.item.Items.RAW_IRON
                    : msg.matches(".*zlot.*") ? net.minecraft.item.Items.RAW_GOLD
                    : msg.matches(".*miedz.*") ? net.minecraft.item.Items.RAW_COPPER
                    : msg.matches(".*piasek.*|.*piasku.*") ? net.minecraft.item.Items.SAND : itemNamed(msg);
            if (in == null) return "Co mam przetopić? Np. \"przetop żelazo\".";
            if (countOf(in) == 0) return "Nie mam " + in.getName().getString() + " w ekwipunku.";
            start(new Smelt(in));
            return "Przetapiam: " + in.getName().getString() + ".";
        }
        boolean structure = msg.matches(".*\\b(dom\\w*|chat\\w*|scian\\w*|mur\\w*|slup\\w*|wiez\\w*|kolumn\\w*)\\b.*");
        if (msg.matches(".*\\b(zrob|zcraftuj|wytworz|craft\\w*|stworz|wykuj)\\w*.*") && !structure) {
            net.minecraft.item.Item item = itemNamed(msg);
            if (item != null) {
                if (!sp) return "Craftuję tylko w świecie jednoosobowym.";
                int how = Math.max(1, number(msg));
                start(new Craft(item, countOf(item) + how));
                return "Robię: " + item.getName().getString() + (how > 1 ? " ×" + how : "") + ".";
            }
        }

        if (msg.matches(".*\\b(wiosk\\w*|wies|wsi|village\\w*|wiesniak\\w*|osad\\w*)\\b.*") && !mine && !build) {
            if (!mc.isInSingleplayer()) return "Szukam wiosek tylko w świecie jednoosobowym.";
            start(new FindVillage());
            return "Szukam najbliższej wioski. Jak żadnej nie widać, idę na zwiad coraz dalej.";
        }
        if (msg.matches(".*\\b(zjedz|jedz|najedz|nakarm|eat)\\w*.*") && !go && !mine && !build) {
            if (!mc.isInSingleplayer()) return "Jem na rozkaz tylko w świecie jednoosobowym.";
            if (!mc.player.getHungerManager().isNotFull()) return "Nie jestem głodny - mam pełny pasek jedzenia.";
            start(new EatNow());
            return "Jem.";
        }
        boolean learn = msg.matches(".*\\b(ucz|uczy|naucz|trenuj|cwicz|learn|train)\\w*.*");
        if (learn && !mine && !build && !go) {
            if (!mc.isInSingleplayer()) return "Uczę się tylko w świecie jednoosobowym.";
            start(new Learn());
            return "Tryb nauki! Sam wymyślam, co ćwiczyć: water clutch (jak mam wiadro wody i bloki) i zwiedzanie z kopaniem rud. "
                    + "Zapamiętuję, co działa. \"stop\" kończy.";
        }

        int n = number(msg);
        if (back) {
            if (home == null) return "Nie mam dokąd wracać - jeszcze nigdzie nie szedłem.";
            start(new WalkTo(home, "wracam"));
            return "Wracam tam, skąd zacząłem.";
        }
        if (mine) {
            Material m = material(msg);
            if (m == null) return "Co mam kopać? Znam: diamenty, żelazo, złoto, węgiel, miedź, redstone, lapis, szmaragdy, drewno, kamień, ziemię, piasek.";
            int count = n > 0 ? n : 1;
            start(new Mine(m, count));
            return "Szukam i kopię: " + m.name + " ×" + count + ". Nie wchodzę w lawę ani w wodę.";
        }
        if (build) {
            int size = n > 0 ? n : 5;
            if (msg.matches(".*\\b(dom|domek|domku|chatk\\w*|house|schronienie)\\b.*")) {
                start(new Build(hut(), "domek"));
                return "Buduję domek 5×5 z dachem, z drzwiami do przodu - stoję w środku.";
            }
            if (msg.matches(".*\\b(slup\\w*|wiez\\w*|tower|pillar|kolumn\\w*)\\b.*")) {
                start(new Pillar(Math.min(size, 40)));
                return "Buduję słup pod sobą: " + Math.min(size, 40) + " bloków w górę.";
            }
            start(new Build(wall(Math.min(size, 16)), "ścianę"));
            return "Buduję ścianę długą na " + Math.min(size, 16) + " bloków, dwa bloki wysoko, przede mną.";
        }
        if (go && msg.matches(".*\\b(gdzies|gdziekolwiek|losowo|przed siebie|na spacer|pospaceruj|zwiedz\\w*)\\b.*")) {
            double a = Math.random() * Math.PI * 2;
            int blocks = n > 0 ? Math.min(n, 200) : 15 + (int) (Math.random() * 16);
            start(new WalkTo(mc.player.getEntityPos().add(Math.cos(a) * blocks, 0, Math.sin(a) * blocks), "idę gdzieś"));
            return "Idę gdzieś przed siebie, jakieś " + blocks + " kratek.";
        }
        if (go && msg.matches(".*\\b(do|kolo|przy|obok)\\b.*")) {
            // "idź do krowy", "idź do drzewa": to the nearest creature of that kind, or the nearest such block
            var creature = creatureNamed(msg);
            Material place = creature == null ? material(msg) : null;
            if (creature != null || place != null) {
                String name = creature != null ? creature.getName().getString().toLowerCase(Locale.ROOT) : place.name;
                start(new GoToThing(creature, place, name));
                return "Idę do: " + name + ".";
            }
        }
        if (go) {
            Vec3d dir = direction(msg);
            int blocks = n > 0 ? Math.min(n, 200) : 5;
            Vec3d to = mc.player.getEntityPos().add(dir.multiply(blocks));
            start(new WalkTo(to, "idę " + blocks + " " + (blocks == 1 ? "kratkę" : blocks < 5 ? "kratki" : "kratek")));
            return "Idę " + blocks + " " + (blocks == 1 ? "kratkę" : blocks < 5 ? "kratki" : "kratek") + " " + directionName(msg) + ".";
        }
        return null;
    }

    private void start(Task t) {
        release();
        home = mc.player.getEntityPos();
        task = t;
    }

    void tick() {
        if (task == null) return;
        if (!mc.isInSingleplayer() || mc.player.isDead()) {
            stop();
            return;
        }
        // instinct: in the dark, a torch at the feet now and then
        lightUp();
        // instinct: hungry or hurt with food in the hotbar - stop and eat first, then carry on
        if (eat()) return;
        if (!task.tick()) {
            task = null;
            release();
        }
    }

    /**
     * "Go to the nearest village": looks for villagers, a bell or beds in the loaded world and walks there; when
     * none is in sight it scouts in a widening square spiral - a long leg, a turn, a longer leg - until one is.
     */
    private final class FindVillage implements Task {
        Vec3d village, waypoint;
        Direction leg = mc.player.getHorizontalFacing();
        int legLength = 48, ticks, scan, stuck, walked;
        Vec3d lastPos, legStart;

        @Override
        public boolean tick() {
            if (++ticks > 20 * 600) {
                say.accept("Po 10 minutach zwiadu nie znalazłem wioski - może jest bardzo daleko.");
                return false;
            }
            if (village == null && --scan <= 0) {
                scan = 40;
                village = lookForVillage();
                if (village != null) say.accept("Widzę wioskę! Jakieś " + Math.round(mc.player.getEntityPos().distanceTo(village)) + " kratek stąd - idę.");
            }
            Vec3d p = mc.player.getEntityPos();
            if (village != null) {
                double dx = village.x - p.x, dz = village.z - p.z;
                if (dx * dx + dz * dz < 6 * 6) {
                    say.accept("Jestem w wiosce!");
                    return false;
                }
                navigate(village, 5.0);
                return true;
            }
            // scouting: walk a leg of the spiral, then turn right and make the next one longer
            if (waypoint == null || p.squaredDistanceTo(new Vec3d(waypoint.x, p.y, waypoint.z)) < 4 || stuck > 60) {
                if (waypoint != null) {
                    walked += legLength;
                    leg = leg.rotateYClockwise();
                    legLength += 24;
                    say.accept("Szukam wioski... przeszedłem już ok. " + walked + " kratek.");
                }
                stuck = 0;
                waypoint = p.add(leg.getOffsetX() * legLength, 0, leg.getOffsetZ() * legLength);
            }
            if (lastPos != null && p.squaredDistanceTo(lastPos) < 0.0004) stuck++;
            else stuck = 0;
            lastPos = p;
            navigate(waypoint, 2.0);
            return true;
        }

        @Override
        public String describe() {
            return village != null ? "idę do wioski" : "szukam wioski";
        }
    }

    /** The nearest sign of a village in the loaded world: a villager, a bell, or else a bed - null when none. */
    private static Vec3d lookForVillage() {
        Vec3d best = null;
        double bestDist = Double.MAX_VALUE;
        for (var e : mc.world.getEntities())
            if (e instanceof net.minecraft.entity.passive.VillagerEntity) {
                double d = mc.player.squaredDistanceTo(e);
                if (d < bestDist) {
                    bestDist = d;
                    best = e.getEntityPos();
                }
            }
        if (best != null) return best;
        BlockPos me = mc.player.getBlockPos();
        BlockPos.Mutable p = new BlockPos.Mutable();
        BlockPos bed = null;
        for (int dx = -64; dx <= 64; dx += 1)
            for (int dz = -64; dz <= 64; dz += 1)
                for (int dy = -12; dy <= 12; dy++) {
                    p.set(me.getX() + dx, me.getY() + dy, me.getZ() + dz);
                    BlockState s = mc.world.getBlockState(p);
                    if (s.isOf(Blocks.BELL)) return Vec3d.ofCenter(p);
                    if (bed == null && s.isIn(BlockTags.BEDS)) bed = p.toImmutable();
                }
        return bed == null ? null : Vec3d.ofCenter(bed);
    }

    /** "zjedz coś": eats from the hotbar until full, then switches back. */
    private final class EatNow implements Task {
        int ticks;
        int before = mc.player.getInventory().selectedSlot;

        @Override
        public boolean tick() {
            int slot = -1;
            for (int i = 0; i < 9; i++)
                if (mc.player.getInventory().getStack(i).getComponents().contains(net.minecraft.component.DataComponentTypes.FOOD)) {
                    slot = i;
                    break;
                }
            if (slot < 0 || !mc.player.getHungerManager().isNotFull() || ++ticks > 20 * 30) {
                set(mc.options.useKey, false);
                InventoryUtility.switchTo(before);
                say.accept(slot < 0 ? "Nie mam nic do jedzenia w pasku." : "Najedzony!");
                return false;
            }
            InventoryUtility.switchTo(slot);
            set(mc.options.useKey, true);
            return true;
        }

        @Override
        public String describe() {
            return "jem";
        }
    }

    private int eatSlotBefore = -1;
    private boolean eating;

    /** Eats while hunger or health is low; true while it is busy eating (the task waits). */
    private boolean eat() {
        var hunger = mc.player.getHungerManager();
        boolean needs = hunger.getFoodLevel() <= 14 || (mc.player.getHealth() < 12f && hunger.isNotFull());
        int slot = -1;
        for (int i = 0; i < 9; i++)
            if (mc.player.getInventory().getStack(i).getComponents().contains(net.minecraft.component.DataComponentTypes.FOOD)) {
                slot = i;
                break;
            }
        if (eating && (!hunger.isNotFull() || slot < 0 || !needs && hunger.getFoodLevel() >= 19)) {
            set(mc.options.useKey, false);
            if (eatSlotBefore >= 0) InventoryUtility.switchTo(eatSlotBefore);
            eatSlotBefore = -1;
            eating = false;
            return false;
        }
        if (!eating && (!needs || slot < 0)) return false;
        if (!eating) {
            eating = true;
            eatSlotBefore = mc.player.getInventory().selectedSlot;
            say.accept("Jestem głodny - jem.");
            set(mc.options.forwardKey, false);
            set(mc.options.jumpKey, false);
            set(mc.options.sprintKey, false);
            if (mc.interactionManager != null) mc.interactionManager.cancelBlockBreaking();
        }
        InventoryUtility.switchTo(slot);
        set(mc.options.useKey, true);
        return true;
    }

    /* ------------------------------------------------------------------ */
    /* Words                                                               */
    /* ------------------------------------------------------------------ */

    private static final String[] NUMBER_WORDS = {"zero", "jed", "dwa", "trzy", "czter", "piec", "szesc", "siedem", "osiem", "dziewiec", "dziesiec"};

    /** The first number in the sentence, in digits or as a Polish word (1-10, "kilka" = 3); 0 when none. */
    private static int number(String msg) {
        var m = java.util.regex.Pattern.compile("\\d+").matcher(msg);
        if (m.find()) {
            try {
                return Integer.parseInt(m.group());
            } catch (NumberFormatException ignored) {
            }
        }
        for (String w : msg.split("[^a-z]+")) {
            if (w.startsWith("dwie") || w.startsWith("dwoch") || w.startsWith("dwu")) return 2;
            if (w.startsWith("kilk")) return 3;
            if (w.startsWith("kilkanas")) return 12;
            if (w.startsWith("jedenas")) return 11;
            if (w.startsWith("dwanas")) return 12;
            if (w.startsWith("pietnas")) return 15;
            if (w.startsWith("dwadziesc")) return 20;
            for (int i = NUMBER_WORDS.length - 1; i >= 1; i--) if (w.startsWith(NUMBER_WORDS[i])) return i;
        }
        return 0;
    }

    /** Which way, from the player's own facing (forward/back/left/right) or the compass. */
    private static Vec3d direction(String msg) {
        float yaw = (float) Math.toRadians(mc.player.getYaw());
        Vec3d fwd = new Vec3d(-Math.sin(yaw), 0, Math.cos(yaw));
        Vec3d right = new Vec3d(-fwd.z, 0, fwd.x);
        if (msg.matches(".*\\b(tyl|tylu|wstecz|back|backward)\\b.*")) return fwd.negate();
        if (msg.matches(".*\\b(lewo|lewa|left)\\b.*")) return right.negate();
        if (msg.matches(".*\\b(prawo|prawa|right)\\b.*")) return right;
        if (msg.matches(".*\\b(polnoc\\w*|north)\\b.*")) return new Vec3d(0, 0, -1);
        if (msg.matches(".*\\b(poludni\\w*|south)\\b.*")) return new Vec3d(0, 0, 1);
        if (msg.matches(".*\\b(wschod\\w*|east)\\b.*")) return new Vec3d(1, 0, 0);
        if (msg.matches(".*\\b(zachod\\w*|west)\\b.*")) return new Vec3d(-1, 0, 0);
        return fwd;
    }

    private static String directionName(String msg) {
        if (msg.matches(".*\\b(tyl|tylu|wstecz|back|backward)\\b.*")) return "do tyłu";
        if (msg.matches(".*\\b(lewo|lewa|left)\\b.*")) return "w lewo";
        if (msg.matches(".*\\b(prawo|prawa|right)\\b.*")) return "w prawo";
        if (msg.matches(".*\\b(polnoc\\w*|north)\\b.*")) return "na północ";
        if (msg.matches(".*\\b(poludni\\w*|south)\\b.*")) return "na południe";
        if (msg.matches(".*\\b(wschod\\w*|east)\\b.*")) return "na wschód";
        if (msg.matches(".*\\b(zachod\\w*|west)\\b.*")) return "na zachód";
        return "do przodu";
    }

    /** A kind of block to mine, with its name for answers. */
    private record Material(String name, Predicate<BlockState> matches) {
    }

    private static Material material(String msg) {
        if (msg.matches(".*(diament|diax|diamond).*"))
            return new Material("diamenty", s -> s.isOf(Blocks.DIAMOND_ORE) || s.isOf(Blocks.DEEPSLATE_DIAMOND_ORE));
        if (msg.matches(".*(zelaz|iron).*"))
            return new Material("żelazo", s -> s.isOf(Blocks.IRON_ORE) || s.isOf(Blocks.DEEPSLATE_IRON_ORE));
        if (msg.matches(".*(zlot|gold).*"))
            return new Material("złoto", s -> s.isOf(Blocks.GOLD_ORE) || s.isOf(Blocks.DEEPSLATE_GOLD_ORE) || s.isOf(Blocks.NETHER_GOLD_ORE));
        if (msg.matches(".*(wegl|wegiel|coal).*"))
            return new Material("węgiel", s -> s.isOf(Blocks.COAL_ORE) || s.isOf(Blocks.DEEPSLATE_COAL_ORE));
        if (msg.matches(".*(miedz|copper).*"))
            return new Material("miedź", s -> s.isOf(Blocks.COPPER_ORE) || s.isOf(Blocks.DEEPSLATE_COPPER_ORE));
        if (msg.matches(".*(redstone|czerwon).*"))
            return new Material("redstone", s -> s.isOf(Blocks.REDSTONE_ORE) || s.isOf(Blocks.DEEPSLATE_REDSTONE_ORE));
        if (msg.matches(".*(lapis|lazur).*"))
            return new Material("lapis", s -> s.isOf(Blocks.LAPIS_ORE) || s.isOf(Blocks.DEEPSLATE_LAPIS_ORE));
        if (msg.matches(".*(szmaragd|emerald).*"))
            return new Material("szmaragdy", s -> s.isOf(Blocks.EMERALD_ORE) || s.isOf(Blocks.DEEPSLATE_EMERALD_ORE));
        if (msg.matches(".*(drew|drzew|klod|pien|wood|log).*"))
            return new Material("drewno", s -> s.isIn(BlockTags.LOGS));
        if (msg.matches(".*(kamie|kamien|stone|bruk).*"))
            return new Material("kamień", s -> s.isOf(Blocks.STONE) || s.isOf(Blocks.COBBLESTONE) || s.isOf(Blocks.DEEPSLATE));
        if (msg.matches(".*(ziemi|ziemia|dirt|trawy).*"))
            return new Material("ziemię", s -> s.isOf(Blocks.DIRT) || s.isOf(Blocks.GRASS_BLOCK));
        if (msg.matches(".*(piasek|piasku|sand).*"))
            return new Material("piasek", s -> s.isOf(Blocks.SAND) || s.isOf(Blocks.RED_SAND));
        // any other block in the game, by its in-game name (Polish or English) - "obsydian", "glina", "kwarc"...
        Block block = blockNamed(msg);
        if (block != null) {
            String path = net.minecraft.registry.Registries.BLOCK.getId(block).getPath();
            Block deep = net.minecraft.registry.Registries.BLOCK.get(net.minecraft.util.Identifier.ofVanilla("deepslate_" + path));
            Block finalDeep = path.endsWith("_ore") ? deep : null;
            return new Material(block.getName().getString().toLowerCase(Locale.ROOT),
                    s -> s.isOf(block) || (finalDeep != null && finalDeep != Blocks.AIR && s.isOf(finalDeep)));
        }
        return null;
    }

    /* ------------------------------------------------------------------ */
    /* The game's own names                                                */
    /* ------------------------------------------------------------------ */

    /** Every block and creature name the game knows - in the game's language and as ids - normalised. */
    private static List<Object[]> names;

    /** Words that are never the thing being named: verbs, prepositions, fillers. */
    private static final Set<String> STOP = new HashSet<>(List.of(
            "idz", "idze", "pojdz", "podejdz", "do", "na", "w", "z", "ze", "od", "po", "mi", "mnie", "sie", "ten", "ta", "te", "to",
            "najblizszej", "najblizszego", "najblizsza", "najblizszy", "jakis", "jakas", "jakies", "troche", "kilka", "wykop", "kop",
            "nakop", "zbierz", "zetnij", "wydobadz", "zabij", "bij", "walcz", "atakuj", "tylko", "wszystkie", "kratki", "kratek",
            "blokow", "bloki", "blok", "szukaj", "znajdz", "przynies", "daj", "prosze", "zrob", "chce", "ai", "przodu", "przod",
            "tylu", "tyl", "lewo", "prawo", "polnoc", "poludnie", "wschod", "zachod", "prosto", "gdzies", "kawalek", "wioski",
            "wioska", "wioske", "kolo", "przy", "obok", "moby", "mobow", "moba", "potwory", "wszystko", "sam", "sama"));

    private static void buildNames() {
        if (names != null) return;
        names = new ArrayList<>();
        for (Block b : net.minecraft.registry.Registries.BLOCK) {
            if (b == Blocks.AIR || b == Blocks.CAVE_AIR || b == Blocks.VOID_AIR) continue;
            names.add(new Object[]{normalize(b.getName().getString()), b});
            names.add(new Object[]{net.minecraft.registry.Registries.BLOCK.getId(b).getPath().replace('_', ' '), b});
        }
        for (net.minecraft.entity.EntityType<?> t : net.minecraft.registry.Registries.ENTITY_TYPE) {
            names.add(new Object[]{normalize(t.getName().getString()), t});
            names.add(new Object[]{net.minecraft.registry.Registries.ENTITY_TYPE.getId(t).getPath().replace('_', ' '), t});
        }
        // Polish names that work whatever language the game is set to
        String[][] creatures = {{"owca", "sheep"}, {"krowa", "cow"}, {"swinia", "pig"}, {"kurczak", "chicken"}, {"kura", "chicken"},
                {"szkielet", "skeleton"}, {"pajak", "spider"}, {"wiesniak", "villager"}, {"kon", "horse"}, {"wilk", "wolf"},
                {"kot", "cat"}, {"lis", "fox"}, {"kalmar", "squid"}, {"szlam", "slime"}, {"wiedzma", "witch"}, {"krolik", "rabbit"},
                {"pszczola", "bee"}, {"utopiec", "drowned"}, {"zombie wiesniak", "zombie_villager"}, {"golem", "iron_golem"},
                {"osiol", "donkey"}, {"lama", "llama"}, {"zolw", "turtle"}, {"dorsz", "cod"}, {"losos", "salmon"}, {"nietoperz", "bat"}};
        for (String[] c : creatures)
            names.add(new Object[]{c[0], net.minecraft.registry.Registries.ENTITY_TYPE.get(net.minecraft.util.Identifier.ofVanilla(c[1]))});
        String[][] blocks = {{"drzewo", "oak_log"}, {"woda", "water"}, {"lawa", "lava"}, {"obsydian", "obsidian"}, {"glina", "clay"},
                {"zwir", "gravel"}, {"liscie", "oak_leaves"}, {"kwarc", "nether_quartz_ore"}, {"snieg", "snow_block"},
                {"lod", "ice"}, {"dynia", "pumpkin"}, {"arbuz", "melon"}, {"trzcina", "sugar_cane"}, {"kaktus", "cactus"},
                {"skrzynia", "chest"}, {"piec", "furnace"}, {"stol", "crafting_table"}, {"lozko", "red_bed"}, {"welna", "white_wool"}};
        for (String[] b : blocks)
            names.add(new Object[]{b[0], net.minecraft.registry.Registries.BLOCK.get(net.minecraft.util.Identifier.ofVanilla(b[1]))});
    }

    /**
     * The block or creature the sentence names, matched on 1-3 word phrases against every name in the game, with
     * room for Polish endings and typos ("owce" is a sheep, "obsydianu" obsidian). {@code kind} picks blocks or
     * creatures; null when nothing matches well.
     */
    private static Object named(String msg, Class<?> kind) {
        buildNames();
        List<String> words = new ArrayList<>();
        for (String w : msg.split("[^a-z0-9]+")) if (!w.isEmpty() && !STOP.contains(w) && !w.matches("\\d+")) words.add(w);
        Object best = null;
        double bestScore = 0.7;
        for (int len = 3; len >= 1; len--)
            for (int i = 0; i + len <= words.size(); i++) {
                String phrase = String.join(" ", words.subList(i, i + len));
                for (Object[] n : names) {
                    if (!kind.isInstance(n[1])) continue;
                    double s = similarity(phrase, (String) n[0]);
                    if (s > bestScore) {
                        bestScore = s;
                        best = n[1];
                    }
                }
            }
        return best;
    }

    static Block blockNamed(String msg) {
        Object o = named(msg, Block.class);
        return o instanceof Block b ? b : null;
    }

    /** The creature the sentence names, or null - used to fight just that kind ("zabij owcę"). */
    static net.minecraft.entity.EntityType<?> creatureNamed(String msg) {
        Object o = named(msg, net.minecraft.entity.EntityType.class);
        return o instanceof net.minecraft.entity.EntityType<?> t ? t : null;
    }

    /** Letter-pair overlap, with a bonus for a shared start: Polish changes the ends of words, not the starts. */
    private static double similarity(String a, String b) {
        if (a.equals(b)) return 1.2;
        double d = dice2(a, b);
        int prefix = 0;
        while (prefix < Math.min(a.length(), b.length()) && a.charAt(prefix) == b.charAt(prefix)) prefix++;
        if (prefix >= 4 || (prefix >= 3 && Math.min(a.length(), b.length()) <= 5)) d += 0.18;
        return d;
    }

    private static double dice2(String a, String b) {
        if (a.length() < 2 || b.length() < 2) return 0;
        java.util.Map<String, Integer> grams = new java.util.HashMap<>();
        for (int i = 0; i + 2 <= a.length(); i++) grams.merge(a.substring(i, i + 2), 1, Integer::sum);
        int hit = 0;
        for (int i = 0; i + 2 <= b.length(); i++) {
            Integer c = grams.get(b.substring(i, i + 2));
            if (c != null && c > 0) {
                hit++;
                grams.put(b.substring(i, i + 2), c - 1);
            }
        }
        return 2.0 * hit / (a.length() + b.length() - 2);
    }

    /** "idź do krowy", "idź do drzewa": walks to the nearest creature of the kind, or the nearest block. */
    private final class GoToThing implements Task {
        final net.minecraft.entity.EntityType<?> creature;
        final Material block;
        final String name;
        int ticks, scan;
        Vec3d target;

        GoToThing(net.minecraft.entity.EntityType<?> creature, Material block, String name) {
            this.creature = creature;
            this.block = block;
            this.name = name;
        }

        @Override
        public boolean tick() {
            if (++ticks > 20 * 180) {
                say.accept("Nie doszedłem do: " + name + ".");
                return false;
            }
            if (--scan <= 0) {
                scan = 20;
                target = null;
                if (creature != null) {
                    double best = 64 * 64;
                    for (var e : mc.world.getEntities())
                        if (e.getType() == creature && e != mc.player && mc.player.squaredDistanceTo(e) < best) {
                            best = mc.player.squaredDistanceTo(e);
                            target = e.getEntityPos();
                        }
                } else {
                    BlockPos p = find(block, java.util.Set.of());
                    if (p != null) target = Vec3d.ofBottomCenter(p);
                }
                if (target == null) {
                    say.accept("Nie widzę w pobliżu: " + name + ".");
                    return false;
                }
            }
            Vec3d p = mc.player.getEntityPos();
            if (p.squaredDistanceTo(target.x, p.y, target.z) < 2.5 * 2.5) {
                say.accept("Jestem przy: " + name + ".");
                return false;
            }
            navigate(target, 2.0);
            return true;
        }

        @Override
        public String describe() {
            return "idę do: " + name;
        }
    }

    /* ------------------------------------------------------------------ */
    /* Walking                                                             */
    /* ------------------------------------------------------------------ */

    private final class WalkTo implements Task {
        final Vec3d to;
        final String what;
        int ticks, stuck;
        Vec3d lastPos;

        WalkTo(Vec3d to, String what) {
            this.to = to;
            this.what = what;
        }

        @Override
        public boolean tick() {
            Vec3d p = mc.player.getEntityPos();
            double dx = to.x - p.x, dz = to.z - p.z;
            if (dx * dx + dz * dz < 0.3 * 0.3) {
                say.accept("Jestem na miejscu.");
                return false;
            }
            if (++ticks > 20 * 120) {
                say.accept("Nie dałem rady dojść - za daleko albo coś blokuje.");
                return false;
            }
            if (lastPos != null && p.squaredDistanceTo(lastPos) < 0.0004) {
                if (++stuck > 60) {
                    say.accept("Utknąłem - coś mi blokuje drogę.");
                    return false;
                }
            } else stuck = 0;
            lastPos = p;
            navigate(to, 0.3);
            return true;
        }

        @Override
        public String describe() {
            return what;
        }
    }

    /* ------------------------------------------------------------------ */
    /* Path planning (A*)                                                   */
    /* ------------------------------------------------------------------ */

    private List<BlockPos> path;
    private int pathIndex, replanIn, pathStuck;
    private Vec3d pathFor;
    private Vec3d pathLastPos;

    /**
     * Gets to {@code dest} the clever way: plans a route block by block with A* - around trees and walls, up
     * single steps, down safe drops, never through lava, fire or cactus - and follows it, re-planning when it gets
     * stuck or the destination moves. With no route found it falls back to walking straight at it.
     *
     * @return true once within {@code arrive} blocks (horizontally) of the destination
     */
    private boolean navigate(Vec3d dest, double arrive) {
        Vec3d p = mc.player.getEntityPos();
        double hx = dest.x - p.x, hz = dest.z - p.z;
        if (hx * hx + hz * hz <= arrive * arrive && Math.abs(dest.y - p.y) < 3) {
            path = null;
            return true;
        }
        if (pathLastPos != null && p.squaredDistanceTo(pathLastPos) < 0.0004) pathStuck++;
        else pathStuck = 0;
        pathLastPos = p;

        boolean moved = pathFor == null || pathFor.squaredDistanceTo(dest) > 4;
        if (path == null || moved || --replanIn <= 0 || pathStuck > 30) {
            pathFor = dest;
            replanIn = 100;
            pathStuck = 0;
            path = plan(mc.player.getBlockPos(), BlockPos.ofFloored(dest));
            pathIndex = 0;
        }
        if (path == null || path.isEmpty()) {
            walkToward(dest, false);
            return false;
        }
        // skip waypoints already reached
        while (pathIndex < path.size() - 1) {
            Vec3d w = Vec3d.ofBottomCenter(path.get(pathIndex));
            if (p.squaredDistanceTo(w.x, p.y, w.z) < 0.35 * 0.35 && Math.abs(p.y - w.y) < 1.2) pathIndex++;
            else break;
        }
        BlockPos next = path.get(pathIndex);
        Vec3d w = Vec3d.ofBottomCenter(next);
        walkToward(w, pathIndex == path.size() - 1);
        // a step up on the route: jump onto it
        if (next.getY() > mc.player.getBlockY() && mc.player.isOnGround()) set(mc.options.jumpKey, true);
        if (pathIndex == path.size() - 1 && p.squaredDistanceTo(w.x, p.y, w.z) < 0.4 * 0.4) path = null;   // partial route done: plan on
        return false;
    }

    /**
     * A* over standable block positions. When the goal cannot be reached within the search budget it returns the
     * route to the explored spot closest to it, so the agent still makes progress and plans again from there.
     */
    private static List<BlockPos> plan(BlockPos start, BlockPos goal) {
        if (!standable(start)) {
            if (standable(start.up())) start = start.up();
            else if (standable(start.down())) start = start.down();
        }
        java.util.Map<BlockPos, Double> g = new java.util.HashMap<>();
        java.util.Map<BlockPos, BlockPos> from = new java.util.HashMap<>();
        java.util.PriorityQueue<Object[]> open = new java.util.PriorityQueue<>((x, y) -> Double.compare((double) x[1], (double) y[1]));
        g.put(start, 0.0);
        open.add(new Object[]{start, heuristic(start, goal)});
        BlockPos closest = start;
        double closestH = heuristic(start, goal);
        int budget = 4000;
        while (!open.isEmpty() && budget-- > 0) {
            BlockPos cur = (BlockPos) open.poll()[0];
            double h = heuristic(cur, goal);
            if (h < closestH) {
                closestH = h;
                closest = cur;
            }
            if (h < 1.5) {
                closest = cur;
                break;
            }
            for (Object[] n : neighbours(cur)) {
                BlockPos nb = (BlockPos) n[0];
                double cost = g.get(cur) + (double) n[1];
                if (cost < g.getOrDefault(nb, Double.MAX_VALUE)) {
                    g.put(nb, cost);
                    from.put(nb, cur);
                    open.add(new Object[]{nb, cost + heuristic(nb, goal)});
                }
            }
        }
        List<BlockPos> route = new ArrayList<>();
        for (BlockPos at = closest; at != null && !at.equals(start); at = from.get(at)) route.add(0, at);
        return route;
    }

    private static double heuristic(BlockPos a, BlockPos b) {
        double dx = a.getX() - b.getX(), dy = a.getY() - b.getY(), dz = a.getZ() - b.getZ();
        return Math.sqrt(dx * dx + dz * dz) + Math.abs(dy) * 0.6;
    }

    /** Where one can go from a standing spot: 8 ways on the level, a step up, or a safe drop of up to 3. */
    private static List<Object[]> neighbours(BlockPos p) {
        List<Object[]> out = new ArrayList<>(10);
        for (int dx = -1; dx <= 1; dx++)
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) continue;
                boolean diagonal = dx != 0 && dz != 0;
                // no cutting corners through solid blocks
                if (diagonal && (!clear(p.add(dx, 0, 0)) || !clear(p.add(0, 0, dz)))) continue;
                double step = diagonal ? 1.414 : 1.0;
                BlockPos q = p.add(dx, 0, dz);
                if (standable(q)) {
                    out.add(new Object[]{q, step});
                } else if (!diagonal && standable(q.up()) && passable(p.up(2))) {
                    out.add(new Object[]{q.up(), step + 0.6});
                } else if (clear(q)) {
                    for (int drop = 1; drop <= 3; drop++) {
                        BlockPos land = q.down(drop);
                        if (standable(land)) {
                            out.add(new Object[]{land, step + 0.4 * drop});
                            break;
                        }
                        if (!passable(land)) break;
                    }
                }
            }
        return out;
    }

    private static boolean hazard(BlockState s) {
        Block b = s.getBlock();
        return b == Blocks.LAVA || b == Blocks.FIRE || b == Blocks.SOUL_FIRE || b == Blocks.MAGMA_BLOCK || b == Blocks.CACTUS
                || b == Blocks.SWEET_BERRY_BUSH || b == Blocks.POWDER_SNOW || b == Blocks.WITHER_ROSE || b == Blocks.CAMPFIRE;
    }

    /** Nothing to bump into at this block, and nothing harmful. */
    private static boolean passable(BlockPos p) {
        BlockState s = mc.world.getBlockState(p);
        return s.getCollisionShape(mc.world, p).isEmpty() && !hazard(s) && s.getFluidState().isEmpty();
    }

    /** Room for the whole body: feet and head. */
    private static boolean clear(BlockPos feet) {
        return passable(feet) && passable(feet.up());
    }

    /** Somewhere a player can stand: body room, and solid, harmless ground under the feet. */
    private static boolean standable(BlockPos feet) {
        if (!clear(feet)) return false;
        BlockPos below = feet.down();
        BlockState s = mc.world.getBlockState(below);
        return !s.getCollisionShape(mc.world, below).isEmpty() && !hazard(s);
    }

    /** Faces the point and walks to it; hops up single steps; refuses to step into danger. */
    private void walkToward(Vec3d to, boolean slow) {
        Vec3d p = mc.player.getEntityPos();
        float yaw = (float) Math.toDegrees(Math.atan2(to.z - p.z, to.x - p.x)) - 90f;
        mc.player.setYaw(mc.player.getYaw() + MathHelper.wrapDegrees(yaw - mc.player.getYaw()) * 0.5f);
        mc.player.setPitch(mc.player.getPitch() * 0.8f);

        float r = (float) Math.toRadians(mc.player.getYaw());
        double fx = -Math.sin(r), fz = Math.cos(r);
        boolean safe = safeStep(fx, fz);
        set(mc.options.forwardKey, safe);
        set(mc.options.sneakKey, slow || !safe);
        BlockPos front = BlockPos.ofFloored(p.x + fx * 0.8, p.y, p.z + fz * 0.8);
        boolean wall = solid(front) && !solid(front.up()) && !solid(front.up(2));
        // a long way to go on open ground: sprint and hop, like a player - quicker than walking
        double far = Math.sqrt((to.x - p.x) * (to.x - p.x) + (to.z - p.z) * (to.z - p.z));
        boolean run = safe && !slow && far > 5 && !solid(front.up()) && !solid(feetAbove());
        set(mc.options.sprintKey, run);
        set(mc.options.jumpKey, safe && mc.player.isOnGround() && (wall || run));
    }

    /* ------------------------------------------------------------------ */
    /* Mining                                                              */
    /* ------------------------------------------------------------------ */

    private final class Mine implements Task {
        final Material m;
        final int want;
        int got, ticks, scanTimer, stuck, collect;
        BlockPos target;
        Vec3d lastPos;
        final Set<BlockPos> skip = new HashSet<>();

        Mine(Material m, int want) {
            this.m = m;
            this.want = want;
        }

        @Override
        public boolean tick() {
            if (++ticks > 20 * 600) {
                say.accept("Kończę po 10 minutach: wykopałem " + got + "/" + want + " (" + m.name + ").");
                return false;
            }

            // after a block breaks, pick up what fell for a moment
            if (collect > 0) {
                collect--;
                ItemEntity drop = nearestDrop();
                if (drop != null) walkToward(drop.getEntityPos(), false);
                else release();
                return true;
            }

            if (target != null && !m.matches.test(mc.world.getBlockState(target))) {
                // it is gone: mined (by us, most likely) - count it and pick up the drop
                got++;
                say.accept("Mam " + got + "/" + want + " (" + m.name + ").");
                target = null;
                if (got >= want) {
                    say.accept("Gotowe! Wykopałem " + got + " (" + m.name + ").");
                    return false;
                }
                collect = 25;
                return true;
            }

            if (target == null || --scanTimer <= 0) {
                scanTimer = 40;
                BlockPos found = find(m, skip);
                if (found == null && target == null) {
                    say.accept("Nie widzę już " + m.name + " w pobliżu (24 kratki dookoła). Wykopałem " + got + "/" + want + ".");
                    return false;
                }
                if (found != null && (target == null || eyes().squaredDistanceTo(Vec3d.ofCenter(found)) + 4 < eyes().squaredDistanceTo(Vec3d.ofCenter(target))))
                {
                    if (!found.equals(target)) heading = null;
                    target = found;
                }
            }

            // stuck on the way: give this one up and look for another
            Vec3d p = mc.player.getEntityPos();
            if (lastPos != null && p.squaredDistanceTo(lastPos) < 0.0004 && !mc.interactionManager.isBreakingBlock()) {
                if (++stuck > 120) {
                    skip.add(target);
                    target = null;
                    stuck = 0;
                    release();
                    return true;
                }
            } else stuck = 0;
            lastPos = p;

            // far away: walk there along a planned route; only with no way through, dig a tunnel
            Vec3d tc = Vec3d.ofCenter(target);
            double flat = Math.sqrt(Math.pow(tc.x - p.x, 2) + Math.pow(tc.z - p.z, 2));
            boolean close = eyes().distanceTo(tc) <= 4.4 && flat <= 3.2 && Math.abs(target.getY() - mc.player.getBlockY()) <= 2;
            if (!close) {
                navigate(tc, 2.5);
                if (path != null && !path.isEmpty()) return true;
            }
            approachAndBreak(target, () -> {
                skip.add(target);
                target = null;
            });
            return true;
        }

        @Override
        public String describe() {
            return "kopię " + m.name + " " + got + "/" + want;
        }
    }

    /** Nearest block of the kind within 24 blocks around and 16 up and down, nearest first. */
    private static BlockPos find(Material m, Set<BlockPos> skip) {
        BlockPos me = mc.player.getBlockPos();
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        BlockPos.Mutable p = new BlockPos.Mutable();
        for (int dy = -16; dy <= 16; dy++)
            for (int dx = -24; dx <= 24; dx++)
                for (int dz = -24; dz <= 24; dz++) {
                    p.set(me.getX() + dx, me.getY() + dy, me.getZ() + dz);
                    if (!m.matches.test(mc.world.getBlockState(p)) || skip.contains(p)) continue;
                    double d = dx * dx + dz * dz + dy * dy * 1.5;
                    if (d < bestDist && !nearLiquid(p)) {
                        bestDist = d;
                        best = p.toImmutable();
                    }
                }
        return best;
    }

    /**
     * Gets to the block and breaks it: in reach and in sight - break it; something in the line of sight - break
     * that first; out of reach - walk there, tunnelling through what is in the way (feet and head height), stepping
     * down or up a block at a time. Calls {@code unreachable} for a target that would need breaking next to a liquid.
     */
    /** The tunnel's heading, kept until the other axis clearly wins - flipping every tick dug both ways and spun. */
    private Direction heading;

    private void approachAndBreak(BlockPos target, Runnable unreachable) {
        Vec3d eye = eyes();
        Vec3d center = Vec3d.ofCenter(target);
        BlockPos feet = mc.player.getBlockPos();
        Vec3d p = mc.player.getEntityPos();
        double dx = center.x - p.x, dz = center.z - p.z;
        int dy = target.getY() - feet.getY();
        double flat = Math.sqrt(dx * dx + dz * dz);

        // Close by: break it straight away, or whatever stands in the line of sight - but only when it really is
        // close, and never the block underfoot (that is how it used to dig a pit around itself).
        if (eye.distanceTo(center) <= 4.4 && flat <= 3.2 && Math.abs(dy) <= 2) {
            BlockPos inWay = firstInWay(eye, center, target);
            if (inWay == null || (!inWay.equals(feet.down()) && inWay.getY() >= feet.getY())) {
                BlockPos hit = inWay != null ? inWay : target;
                if (nearLiquid(hit)) {
                    unreachable.run();
                    release();
                    return;
                }
                set(mc.options.forwardKey, false);
                set(mc.options.jumpKey, false);
                breakBlock(hit);
                return;
            }
        }

        // Further: tunnel along one axis at a time, keeping the heading until the other axis clearly wins.
        Direction along = Math.abs(dx) > Math.abs(dz) ? (dx > 0 ? Direction.EAST : Direction.WEST) : (dz > 0 ? Direction.SOUTH : Direction.NORTH);
        if (heading == null) heading = along;
        else if (heading != along) {
            double onHeading = Math.abs(heading.getAxis() == Direction.Axis.X ? dx : dz);
            double onOther = Math.abs(along.getAxis() == Direction.Axis.X ? dx : dz);
            boolean reversed = heading.getOffsetX() * dx + heading.getOffsetZ() * dz < 0;
            if (reversed || onHeading < 0.6 || onOther > onHeading + 1.5) heading = along;
        }
        BlockPos front = feet.offset(heading);
        // face the way it is digging, not the target - two aims at once were the spinning
        Vec3d aim = Vec3d.ofBottomCenter(front);
        float yaw = (float) Math.toDegrees(Math.atan2(aim.z - p.z, aim.x - p.x)) - 90f;
        mc.player.setYaw(mc.player.getYaw() + MathHelper.wrapDegrees(yaw - mc.player.getYaw()) * 0.5f);

        BlockPos toBreak = null;
        if (dy < -1) toBreak = solid(front.up()) ? front.up() : solid(front) ? front : solid(front.down()) ? front.down() : null;   // stairs down
        else if (solid(front.up())) toBreak = front.up();
        else if (solid(front) && (dy <= 0 || solid(front.up(2)) || solid(feet.up(2)))) toBreak = front;
        else if (dy > 1 && solid(feet.up(2))) toBreak = feet.up(2);

        if (toBreak != null) {
            if (nearLiquid(toBreak)) {
                unreachable.run();
                release();
                return;
            }
            set(mc.options.forwardKey, false);
            breakBlock(toBreak);
            return;
        }
        mc.interactionManager.cancelBlockBreaking();
        walkToward(Vec3d.ofBottomCenter(front), false);
        // up a step: jump when the front is one block high and above it is free
        if (solid(front) && !solid(front.up()) && mc.player.isOnGround()) set(mc.options.jumpKey, true);
    }

    /** The first other solid block on the straight line from the eyes to the target, or null if it is in sight. */
    private static BlockPos firstInWay(Vec3d eye, Vec3d center, BlockPos target) {
        BlockHitResult r = mc.world.raycast(new RaycastContext(eye, center, RaycastContext.ShapeType.COLLIDER,
                RaycastContext.FluidHandling.NONE, mc.player));
        if (r.getType() != HitResult.Type.BLOCK || r.getBlockPos().equals(target)) return null;
        return r.getBlockPos();
    }

    /** Faces the block, takes the best tool for it and keeps hitting it. */
    private void breakBlock(BlockPos pos) {
        Vec3d c = Vec3d.ofCenter(pos);
        lookAt(c);
        BlockState s = mc.world.getBlockState(pos);
        int best = -1;
        float speed = 1f;
        for (int i = 0; i < 9; i++) {
            float v = mc.player.getInventory().getStack(i).getMiningSpeedMultiplier(s);
            if (v > speed) {
                speed = v;
                best = i;
            }
        }
        if (best >= 0) InventoryUtility.switchTo(best);
        Direction side = Direction.getFacing(eyes().x - c.x, eyes().y - c.y, eyes().z - c.z);
        mc.interactionManager.updateBlockBreakingProgress(pos, side);
        mc.player.swingHand(Hand.MAIN_HAND);
    }

    private static ItemEntity nearestDrop() {
        ItemEntity best = null;
        double bestDist = 36;
        for (var e : mc.world.getEntities())
            if (e instanceof ItemEntity item) {
                double d = mc.player.squaredDistanceTo(item);
                if (d < bestDist) {
                    bestDist = d;
                    best = item;
                }
            }
        return best;
    }

    /* ------------------------------------------------------------------ */
    /* Learning on its own                                                 */
    /* ------------------------------------------------------------------ */

    /** Any ore at all - for exploring. */
    private static final Material ANY_ORE = new Material("rudy", s ->
            net.minecraft.registry.Registries.BLOCK.getId(s.getBlock()).getPath().endsWith("_ore"));

    /**
     * The learning mode: with no order it keeps itself busy, picking drills and getting better at them. With a
     * water bucket and blocks it practises the water clutch (and learns when to pour); otherwise it explores -
     * walks off somewhere and mines the ores it comes across.
     */
    private final class Learn implements Task {
        Task drill;
        int drills;
        final java.util.Random random = new java.util.Random();

        @Override
        public boolean tick() {
            if (drill != null) {
                if (drill.tick()) return true;
                drill = null;
                release();
                drills++;
                return true;
            }
            boolean canClutch = slotOf(net.minecraft.item.Items.WATER_BUCKET) >= 0 && blockSlot() >= 0
                    && mc.player.getHealth() >= 16f && mc.world.getRegistryKey() != net.minecraft.world.World.NETHER;
            if (canClutch && random.nextFloat() < 0.6f) {
                drill = new WaterClutch();
            } else if (random.nextBoolean()) {
                BlockPos ore = find(ANY_ORE, java.util.Set.of());
                if (ore != null) drill = new Mine(ANY_ORE, 1);
            }
            if (drill == null) {
                double a = random.nextDouble() * Math.PI * 2;
                Vec3d to = mc.player.getEntityPos().add(Math.cos(a) * 14, 0, Math.sin(a) * 14);
                drill = new WalkTo(to, "zwiedzam");
            }
            return true;
        }

        @Override
        public String describe() {
            return "uczę się" + (drill != null ? ": " + drill.describe() : "");
        }
    }

    /* ---- water clutch: a bandit over "how high above the ground to pour" ---- */

    /** Heights above the ground (in blocks) at which to pour the water - the knob being learned. */
    private static final float[] POUR_AT = {1.5f, 2f, 2.5f, 3f, 3.5f, 4f, 5f};
    private static final int[] tries = new int[POUR_AT.length], wins = new int[POUR_AT.length];
    private static boolean skillsLoaded;

    /** Best known pour height, with some exploring: a bandit (optimistic estimate for rarely tried heights). */
    private static int pickPour(java.util.Random r) {
        if (r.nextFloat() < 0.15f) return r.nextInt(POUR_AT.length);
        int best = 0;
        double bestScore = -1;
        for (int i = 0; i < POUR_AT.length; i++) {
            double score = (wins[i] + 1.0) / (tries[i] + 2.0);   // a never-tried height starts at 50%
            if (score > bestScore) {
                bestScore = score;
                best = i;
            }
        }
        return best;
    }

    private final class WaterClutch implements Task {
        enum Phase {Pillar, StepOff, Fall, PickUp}

        Phase phase = Phase.Pillar;
        final Pillar pillar;
        final int choice;
        final float startHealth;
        int ticks, height;
        boolean poured;
        BlockPos water;

        WaterClutch() {
            loadSkills();
            height = 7 + new java.util.Random().nextInt(6);   // 7..12: survivable even if it goes wrong
            pillar = new Pillar(height);
            choice = pickPour(new java.util.Random());
            startHealth = mc.player.getHealth();
            say.accept("Ćwiczę water clutch z " + height + " kratek - wylewam " + POUR_AT[choice] + " nad ziemią.");
        }

        @Override
        public boolean tick() {
            if (++ticks > 20 * 90) return false;
            switch (phase) {
                case Pillar -> {
                    if (!pillar.tick()) phase = Phase.StepOff;
                }
                case StepOff -> {
                    // walk off the top of the pillar, any free side
                    mc.player.setPitch(30f);
                    set(mc.options.forwardKey, true);
                    if (!mc.player.isOnGround()) {
                        set(mc.options.forwardKey, false);
                        phase = Phase.Fall;
                    }
                }
                case Fall -> {
                    double toGround = groundBelow();
                    if (!poured && toGround <= POUR_AT[choice] && toGround > 0.2) {
                        int slot = slotOf(net.minecraft.item.Items.WATER_BUCKET);
                        if (slot >= 0) {
                            InventoryUtility.switchTo(slot);
                            mc.player.setPitch(90f);
                            mc.interactionManager.interactItem(mc.player, Hand.MAIN_HAND);
                            poured = true;
                        }
                    }
                    if (mc.player.isOnGround() || mc.player.isTouchingWater()) {
                        boolean ok = mc.player.getHealth() >= startHealth - 0.01f;
                        tries[choice]++;
                        if (ok) wins[choice]++;
                        saveSkills();
                        say.accept((ok ? "Udany water clutch" : "Nie wyszło, straciłem " + Math.round(startHealth - mc.player.getHealth()) + " HP")
                                + " (wylewałem " + POUR_AT[choice] + " nad ziemią). Skuteczność tej wysokości: " + wins[choice] + "/" + tries[choice] + ".");
                        water = mc.player.getBlockPos();
                        phase = Phase.PickUp;
                        ticks = 20 * 80;   // a few seconds left to scoop the water back up
                    }
                }
                case PickUp -> {
                    // scoop the water back into the bucket
                    int slot = slotOf(net.minecraft.item.Items.BUCKET);
                    if (slot < 0) return false;
                    InventoryUtility.switchTo(slot);
                    BlockPos source = null;
                    for (BlockPos p : BlockPos.iterate(water.add(-2, -2, -2), water.add(2, 1, 2)))
                        if (mc.world.getFluidState(p).isStill()) {
                            source = p.toImmutable();
                            break;
                        }
                    if (source == null) return false;
                    lookAt(Vec3d.ofCenter(source));
                    mc.interactionManager.interactItem(mc.player, Hand.MAIN_HAND);
                    return false;
                }
            }
            return true;
        }

        @Override
        public String describe() {
            return "water clutch (" + phase + ")";
        }
    }

    /** Distance from the feet to the first solid block straight below, in blocks (99 when nothing within 40). */
    private static double groundBelow() {
        Vec3d from = mc.player.getEntityPos();
        BlockHitResult r = mc.world.raycast(new RaycastContext(from, from.add(0, -40, 0), RaycastContext.ShapeType.COLLIDER,
                RaycastContext.FluidHandling.ANY, mc.player));
        return r.getType() == HitResult.Type.BLOCK ? from.y - r.getPos().y : 99;
    }

    private static int slotOf(net.minecraft.item.Item item) {
        for (int i = 0; i < 9; i++) if (mc.player.getInventory().getStack(i).isOf(item)) return i;
        return -1;
    }

    private static java.io.File skillsFile() {
        return new java.io.File(thunder.hack.core.manager.client.ConfigManager.MISC_FOLDER, "learnai_skills.txt");
    }

    private static void loadSkills() {
        if (skillsLoaded) return;
        skillsLoaded = true;
        try {
            if (!skillsFile().exists()) return;
            String[] lines = java.nio.file.Files.readString(skillsFile().toPath()).split("\n");
            for (String line : lines) {
                String[] f = line.trim().split(" ");
                if (f.length == 4 && f[0].equals("pour")) {
                    int i = Integer.parseInt(f[1]);
                    if (i >= 0 && i < POUR_AT.length) {
                        tries[i] = Integer.parseInt(f[2]);
                        wins[i] = Integer.parseInt(f[3]);
                    }
                }
            }
        } catch (Exception ignored) {
        }
    }

    private static void saveSkills() {
        try {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < POUR_AT.length; i++) sb.append("pour ").append(i).append(' ').append(tries[i]).append(' ').append(wins[i]).append('\n');
            java.nio.file.Files.createDirectories(skillsFile().getParentFile().toPath());
            java.nio.file.Files.writeString(skillsFile().toPath(), sb.toString());
        } catch (Exception ignored) {
        }
    }

    /** What it has learned about the water clutch so far, for "statystyki". */
    String skills() {
        loadSkills();
        int total = 0, won = 0, best = -1;
        for (int i = 0; i < POUR_AT.length; i++) {
            total += tries[i];
            won += wins[i];
            if (tries[i] > 0 && (best < 0 || (double) wins[i] / tries[i] > (double) wins[best] / tries[best])) best = i;
        }
        if (total == 0) return "Water clutcha jeszcze nie ćwiczyłem.";
        return "Water clutch: " + won + "/" + total + " udanych, najlepiej wychodzi wylewanie " + POUR_AT[best] + " kratki nad ziemią.";
    }

    /* ------------------------------------------------------------------ */
    /* Building                                                            */
    /* ------------------------------------------------------------------ */

    /** A wall in front: {@code length} blocks to the right, two high, starting two blocks ahead. */
    private static List<BlockPos> wall(int length) {
        BlockPos feet = mc.player.getBlockPos();
        Direction fwd = mc.player.getHorizontalFacing();
        Direction right = fwd.rotateYClockwise();
        BlockPos base = feet.offset(fwd, 2).offset(right.getOpposite(), length / 2);
        List<BlockPos> out = new ArrayList<>();
        for (int y = 0; y < 2; y++)
            for (int i = 0; i < length; i++) out.add(base.offset(right, i).up(y));
        return out;
    }

    /** A 5x5 hut around the player: walls three high with a doorway ahead, and a flat roof. */
    private static List<BlockPos> hut() {
        BlockPos c = mc.player.getBlockPos();
        Direction fwd = mc.player.getHorizontalFacing();
        BlockPos door = c.offset(fwd, 2);
        List<BlockPos> out = new ArrayList<>();
        for (int y = 0; y < 3; y++)
            for (int dx = -2; dx <= 2; dx++)
                for (int dz = -2; dz <= 2; dz++) {
                    if (Math.abs(dx) != 2 && Math.abs(dz) != 2) continue;
                    BlockPos p = c.add(dx, y, dz);
                    if (y < 2 && p.getX() == door.getX() && p.getZ() == door.getZ()) continue;
                    out.add(p);
                }
        // the roof, from the edges in, so every block has a neighbour to be placed against
        for (int ring = 2; ring >= 0; ring--)
            for (int dx = -ring; dx <= ring; dx++)
                for (int dz = -ring; dz <= ring; dz++)
                    if (Math.max(Math.abs(dx), Math.abs(dz)) == ring) out.add(c.add(dx, 3, dz));
        return out;
    }

    private final class Build implements Task {
        final List<BlockPos> plan;
        final String what;
        int placed, ticks, cooldown;

        Build(List<BlockPos> plan, String what) {
            this.plan = plan;
            this.what = what;
            int have = blocksInHotbar();
            if (have < plan.size())
                say.accept("Mam w pasku " + have + " bloków, a potrzeba " + plan.size() + " - zbuduję, ile się da.");
        }

        @Override
        public boolean tick() {
            if (++ticks > 20 * 300) {
                say.accept("Budowa trwa za długo - przerywam.");
                return false;
            }
            if (cooldown-- > 0) return true;
            if (blockSlot() < 0) {
                say.accept("Skończyły mi się bloki w pasku. Postawiłem " + placed + ".");
                return false;
            }
            for (BlockPos p : plan) {
                if (!mc.world.getBlockState(p).isReplaceable()) continue;
                // never wall the player in their own feet / head
                if (p.equals(mc.player.getBlockPos()) || p.equals(mc.player.getBlockPos().up())) continue;
                if (place(p)) {
                    placed++;
                    cooldown = 2;
                    return true;
                }
            }
            boolean done = true;
            for (BlockPos p : plan) if (mc.world.getBlockState(p).isReplaceable()) done = false;
            say.accept(done ? "Zbudowałem " + what + "! (" + placed + " bloków)" : "Postawiłem " + placed + " bloków - reszty nie sięgam.");
            return false;
        }

        @Override
        public String describe() {
            return "buduję " + what + " (" + placed + "/" + plan.size() + ")";
        }
    }

    /** A pillar under the player: jump, and put a block under the feet at the top of each jump. */
    private final class Pillar implements Task {
        final int height;
        int placed, ticks;
        final int startY;

        Pillar(int height) {
            this.height = height;
            this.startY = mc.player.getBlockY();
        }

        @Override
        public boolean tick() {
            if (++ticks > 20 * 120 || placed >= height) {
                release();
                say.accept("Słup gotowy: " + placed + " bloków.");
                return false;
            }
            if (blockSlot() < 0) {
                say.accept("Nie mam bloków w pasku. Postawiłem " + placed + ".");
                return false;
            }
            mc.player.setPitch(90f);
            if (mc.player.isOnGround()) {
                set(mc.options.jumpKey, true);
                return true;
            }
            set(mc.options.jumpKey, false);
            BlockPos below = mc.player.getBlockPos().down();
            if (mc.world.getBlockState(below).isReplaceable() && mc.player.getY() - below.getY() >= 1.05) {
                if (place(below)) placed++;
            }
            return true;
        }

        @Override
        public String describe() {
            return "buduję słup " + placed + "/" + height;
        }
    }

    /** Places a block from the hotbar at {@code p}, against any solid neighbour; false when that is not possible yet. */
    private static boolean place(BlockPos p) {
        Vec3d eye = eyes();
        for (Direction d : Direction.values()) {
            BlockPos support = p.offset(d);
            if (!solid(support)) continue;
            Direction face = d.getOpposite();
            Vec3d hit = Vec3d.ofCenter(support).add(face.getOffsetX() * 0.5, face.getOffsetY() * 0.5, face.getOffsetZ() * 0.5);
            if (eye.distanceTo(hit) > 4.5) continue;
            int slot = blockSlot();
            if (slot < 0) return false;
            InventoryUtility.switchTo(slot);
            lookAt(hit);
            mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, new BlockHitResult(hit, face, support, false));
            mc.player.swingHand(Hand.MAIN_HAND);
            return true;
        }
        return false;
    }

    private static int blockSlot() {
        for (int i = 0; i < 9; i++) {
            ItemStack s = mc.player.getInventory().getStack(i);
            if (s.getItem() instanceof BlockItem bi && bi.getBlock().getDefaultState().isFullCube(mc.world, BlockPos.ORIGIN)
                    && !(bi.getBlock() == Blocks.SAND || bi.getBlock() == Blocks.GRAVEL || bi.getBlock() == Blocks.TNT)) return i;
        }
        return -1;
    }

    private static int blocksInHotbar() {
        int n = 0;
        for (int i = 0; i < 9; i++) {
            ItemStack s = mc.player.getInventory().getStack(i);
            if (s.getItem() instanceof BlockItem) n += s.getCount();
        }
        return n;
    }

    /* ------------------------------------------------------------------ */
    /* Helpers                                                             */
    /* ------------------------------------------------------------------ */

    /** Two blocks above the feet - a low ceiling makes hopping a bad idea. */
    private static BlockPos feetAbove() {
        return mc.player.getBlockPos().up(2);
    }

    private static Vec3d eyes() {
        return mc.player.getEyePos();
    }

    private static void lookAt(Vec3d v) {
        Vec3d e = eyes();
        double dx = v.x - e.x, dy = v.y - e.y, dz = v.z - e.z;
        mc.player.setYaw((float) Math.toDegrees(Math.atan2(dz, dx)) - 90f);
        mc.player.setPitch((float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz))));
    }

    private static boolean solid(BlockPos p) {
        BlockState s = mc.world.getBlockState(p);
        return !s.isAir() && s.getFluidState().isEmpty() && !s.isReplaceable();
    }

    /** Lava or water touching this block - breaking it would let the liquid in. */
    private static boolean nearLiquid(BlockPos p) {
        for (Direction d : Direction.values()) {
            BlockState s = mc.world.getBlockState(p.offset(d));
            if (!s.getFluidState().isEmpty()) return true;
        }
        return false;
    }

    /** Solid ground within two blocks below the next step, and no lava, fire or cactus there. */
    private static boolean safeStep(double fx, double fz) {
        BlockPos ahead = BlockPos.ofFloored(mc.player.getX() + fx * 0.9, mc.player.getY(), mc.player.getZ() + fz * 0.9);
        for (int down = 0; down <= 3; down++) {
            BlockState s = mc.world.getBlockState(ahead.down(down));
            Block b = s.getBlock();
            if (b == Blocks.LAVA || b == Blocks.FIRE || b == Blocks.MAGMA_BLOCK || b == Blocks.CACTUS) return false;
            if (down > 0 && !s.isAir() && s.getFluidState().isEmpty()) return true;
        }
        return false;
    }

    private static void set(KeyBinding key, boolean down) {
        key.setPressed(down);
    }

    /** Lets go of every key the tasks may hold - once, when a task ends, never every tick. */
    void release() {
        if (mc.options == null) return;
        set(mc.options.forwardKey, false);
        set(mc.options.backKey, false);
        set(mc.options.leftKey, false);
        set(mc.options.rightKey, false);
        set(mc.options.jumpKey, false);
        set(mc.options.sneakKey, false);
        set(mc.options.sprintKey, false);
        if (mc.interactionManager != null) mc.interactionManager.cancelBlockBreaking();
    }

    /* ------------------------------------------------------------------ */
    /* Survival skills: crafting, smelting, sleeping, chests, farming       */
    /* ------------------------------------------------------------------ */

    /** Items by name, the same way as blocks and creatures. */
    static net.minecraft.item.Item itemNamed(String msg) {
        buildNames();
        if (itemNames == null) {
            itemNames = new ArrayList<>();
            for (net.minecraft.item.Item it : net.minecraft.registry.Registries.ITEM) {
                itemNames.add(new Object[]{normalize(it.getName().getString()), it});
                itemNames.add(new Object[]{net.minecraft.registry.Registries.ITEM.getId(it).getPath().replace('_', ' '), it});
            }
            String[][] pl = {{"miecz", "iron_sword"}, {"kilof", "iron_pickaxe"}, {"siekiera", "iron_axe"}, {"lopata", "iron_shovel"},
                    {"motyka", "iron_hoe"}, {"pochodnia", "torch"}, {"pochodnie", "torch"}, {"stol", "crafting_table"},
                    {"piec", "furnace"}, {"skrzynia", "chest"}, {"deski", "oak_planks"}, {"patyki", "stick"}, {"patyk", "stick"},
                    {"chleb", "bread"}, {"tarcza", "shield"}, {"luk", "bow"}, {"strzaly", "arrow"}, {"wiadro", "bucket"},
                    {"drewniany kilof", "wooden_pickaxe"}, {"kamienny kilof", "stone_pickaxe"}, {"zelazny kilof", "iron_pickaxe"},
                    {"diamentowy kilof", "diamond_pickaxe"}, {"drewniany miecz", "wooden_sword"}, {"kamienny miecz", "stone_sword"},
                    {"zelazny miecz", "iron_sword"}, {"diamentowy miecz", "diamond_sword"}, {"sztabka zelaza", "iron_ingot"},
                    {"sztabki zelaza", "iron_ingot"}, {"lozko", "white_bed"}};
            for (String[] p : pl)
                itemNames.add(new Object[]{p[0], net.minecraft.registry.Registries.ITEM.get(net.minecraft.util.Identifier.ofVanilla(p[1]))});
        }
        List<String> words = new ArrayList<>();
        for (String w : msg.split("[^a-z0-9]+"))
            if (!w.isEmpty() && !STOP.contains(w) && !w.matches("\\d+") && !w.matches("zrob\\w*|zcraftuj\\w*|wytworz\\w*|craft\\w*|stworz\\w*|przetop\\w*|upiecz\\w*|wytop\\w*"))
                words.add(w);
        Object best = null;
        double bestScore = 0.7;
        for (int len = 3; len >= 1; len--)
            for (int i = 0; i + len <= words.size(); i++) {
                String phrase = String.join(" ", words.subList(i, i + len));
                for (Object[] n : itemNames) {
                    double s = similarity(phrase, (String) n[0]) + len * 0.02;   // prefer the fuller phrase
                    if (s > bestScore) {
                        bestScore = s;
                        best = n[1];
                    }
                }
            }
        return best instanceof net.minecraft.item.Item it && it != net.minecraft.item.Items.AIR ? it : null;
    }

    private static List<Object[]> itemNames;

    private static int countOf(net.minecraft.item.Item item) {
        int n = 0;
        for (int i = 0; i < mc.player.getInventory().size(); i++) {
            ItemStack s = mc.player.getInventory().getStack(i);
            if (s.isOf(item)) n += s.getCount();
        }
        return n;
    }

    /** The nearest block that matches, within {@code r} blocks - a table, a furnace, a bed, a chest. */
    private static BlockPos nearest(Predicate<BlockState> what, int r) {
        BlockPos me = mc.player.getBlockPos(), best = null;
        double bestD = Double.MAX_VALUE;
        for (BlockPos p : BlockPos.iterate(me.add(-r, -6, -r), me.add(r, 6, r)))
            if (what.test(mc.world.getBlockState(p))) {
                double d = p.getSquaredDistance(me);
                if (d < bestD) {
                    bestD = d;
                    best = p.toImmutable();
                }
            }
        return best;
    }

    /** Right-clicks a block (opens a table, furnace, chest, lies in a bed) facing it. */
    private static void use(BlockPos p) {
        Vec3d c = Vec3d.ofCenter(p);
        lookAt(c);
        Direction side = Direction.getFacing(eyes().x - c.x, eyes().y - c.y, eyes().z - c.z);
        mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, new BlockHitResult(c, side, p, false));
    }

    /**
     * Crafts an item from the recipe book: in the 2x2 grid when it fits, otherwise at a crafting table - the
     * nearest one, or one it places (crafting one first if it has to).
     */
    private final class Craft implements Task {
        final net.minecraft.item.Item item;
        final int want;
        int ticks, wait, made;
        BlockPos table;
        boolean placingTable;

        Craft(net.minecraft.item.Item item, int want) {
            this.item = item;
            this.want = want;
        }

        @Override
        public boolean tick() {
            if (++ticks > 20 * 120) {
                say.accept("Nie udało się zrobić: " + item.getName().getString() + ".");
                return closeAndEnd();
            }
            if (wait-- > 0) return true;
            if (countOf(item) >= want || made >= want) {
                say.accept("Zrobione: " + item.getName().getString() + " ×" + countOf(item) + ".");
                return closeAndEnd();
            }
            net.minecraft.recipe.RecipeDisplayEntry recipe = recipeFor(item);
            if (recipe == null) {
                say.accept("Nie umiem zrobić: " + item.getName().getString() + " - brak składników albo przepisu w księdze przepisów.");
                return closeAndEnd();
            }
            boolean bigGrid = needsTable(recipe);
            var handler = mc.player.currentScreenHandler;
            if (!bigGrid) {
                craftIn(mc.player.playerScreenHandler.syncId, recipe);
                return true;
            }
            if (handler instanceof net.minecraft.screen.CraftingScreenHandler) {
                craftIn(handler.syncId, recipe);
                return true;
            }
            if (table == null) table = nearest(s -> s.isOf(Blocks.CRAFTING_TABLE), 16);
            if (table == null) {
                // no table about: put one down - or make one first
                int slot = slotOf(net.minecraft.item.Items.CRAFTING_TABLE);
                if (slot < 0) {
                    var tableRecipe = recipeFor(net.minecraft.item.Items.CRAFTING_TABLE);
                    if (tableRecipe == null) {
                        say.accept("Do tego potrzeba stołu rzemieślniczego, a nie mam z czego go zrobić.");
                        return closeAndEnd();
                    }
                    craftIn(mc.player.playerScreenHandler.syncId, tableRecipe);
                    return true;
                }
                BlockPos spot = mc.player.getBlockPos().offset(mc.player.getHorizontalFacing(), 2);
                InventoryUtility.switchTo(slot);
                if (placeHeld(spot)) {
                    table = spot;
                    wait = 4;
                }
                return true;
            }
            if (!navigate(Vec3d.ofCenter(table), 3.0)) return true;
            release();
            use(table);
            wait = 6;
            return true;
        }

        private void craftIn(int syncId, net.minecraft.recipe.RecipeDisplayEntry recipe) {
            mc.interactionManager.clickRecipe(syncId, recipe.id(), false);
            mc.interactionManager.clickSlot(syncId, 0, 0, net.minecraft.screen.slot.SlotActionType.QUICK_MOVE, mc.player);
            made++;
            wait = 4;
        }

        private boolean closeAndEnd() {
            if (mc.player.currentScreenHandler != mc.player.playerScreenHandler) mc.player.closeHandledScreen();
            return false;
        }

        @Override
        public String describe() {
            return "robię: " + item.getName().getString();
        }
    }

    /** A recipe in the player's book that makes {@code item} and can be made from what is in the inventory now. */
    private static net.minecraft.recipe.RecipeDisplayEntry recipeFor(net.minecraft.item.Item item) {
        var finder = new net.minecraft.recipe.RecipeFinder();
        mc.player.getInventory().populateRecipeFinder(finder);
        var ctx = net.minecraft.recipe.display.SlotDisplayContexts.createParameters(mc.world);
        for (var group : mc.player.getRecipeBook().getOrderedResults())
            for (var entry : group.getAllRecipes()) {
                List<ItemStack> out = entry.display().result().getStacks(ctx);
                if (out.isEmpty() || !out.get(0).isOf(item)) continue;
                if (!(entry.display() instanceof net.minecraft.recipe.display.ShapedCraftingRecipeDisplay)
                        && !(entry.display() instanceof net.minecraft.recipe.display.ShapelessCraftingRecipeDisplay)) continue;
                if (entry.isCraftable(finder)) return entry;
            }
        return null;
    }

    private static boolean needsTable(net.minecraft.recipe.RecipeDisplayEntry e) {
        if (e.display() instanceof net.minecraft.recipe.display.ShapedCraftingRecipeDisplay s) return s.width() > 2 || s.height() > 2;
        if (e.display() instanceof net.minecraft.recipe.display.ShapelessCraftingRecipeDisplay s) return s.ingredients().size() > 4;
        return true;
    }

    /** Places the block in the main hand at {@code p}, against any solid neighbour. */
    private static boolean placeHeld(BlockPos p) {
        Vec3d eye = eyes();
        for (Direction d : Direction.values()) {
            BlockPos support = p.offset(d);
            if (!solid(support)) continue;
            Direction face = d.getOpposite();
            Vec3d hit = Vec3d.ofCenter(support).add(face.getOffsetX() * 0.5, face.getOffsetY() * 0.5, face.getOffsetZ() * 0.5);
            if (eye.distanceTo(hit) > 4.5) continue;
            lookAt(hit);
            mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, new BlockHitResult(hit, face, support, false));
            mc.player.swingHand(Hand.MAIN_HAND);
            return true;
        }
        return false;
    }

    /** Smelts what it carries in the nearest furnace (or one it places): ore in, fuel in, ingots out. */
    private final class Smelt implements Task {
        final net.minecraft.item.Item input;
        int ticks, wait, phase;
        BlockPos furnace;

        Smelt(net.minecraft.item.Item input) {
            this.input = input;
        }

        @Override
        public boolean tick() {
            if (++ticks > 20 * 240) {
                say.accept("Przetapianie trwa za długo - kończę.");
                return end();
            }
            if (wait-- > 0) return true;
            var handler = mc.player.currentScreenHandler;
            if (handler instanceof net.minecraft.screen.AbstractFurnaceScreenHandler f) {
                if (phase == 0) {
                    // shift-click puts what smelts into the top slot and what burns into the fuel slot
                    for (int s = 3; s < f.slots.size(); s++) {
                        ItemStack st = f.slots.get(s).getStack();
                        if (st.isOf(input)) mc.interactionManager.clickSlot(f.syncId, s, 0, net.minecraft.screen.slot.SlotActionType.QUICK_MOVE, mc.player);
                    }
                    if (f.slots.get(1).getStack().isEmpty())
                        for (int s = 3; s < f.slots.size(); s++) {
                            ItemStack st = f.slots.get(s).getStack();
                            if (st.isOf(net.minecraft.item.Items.COAL) || st.isOf(net.minecraft.item.Items.CHARCOAL) || st.isIn(net.minecraft.registry.tag.ItemTags.PLANKS) || st.isIn(net.minecraft.registry.tag.ItemTags.LOGS)) {
                                mc.interactionManager.clickSlot(f.syncId, s, 0, net.minecraft.screen.slot.SlotActionType.QUICK_MOVE, mc.player);
                                break;
                            }
                        }
                    phase = 1;
                    say.accept("Przetapiam...");
                    wait = 10;
                    return true;
                }
                if (!f.slots.get(2).getStack().isEmpty())
                    mc.interactionManager.clickSlot(f.syncId, 2, 0, net.minecraft.screen.slot.SlotActionType.QUICK_MOVE, mc.player);
                if (f.slots.get(0).getStack().isEmpty() && f.slots.get(2).getStack().isEmpty()) {
                    say.accept("Przetopione!");
                    return end();
                }
                if (f.slots.get(1).getStack().isEmpty() && !f.isBurning()) {
                    say.accept("Skończyło się paliwo w piecu.");
                    return end();
                }
                wait = 10;
                return true;
            }
            if (furnace == null) furnace = nearest(s -> s.isOf(Blocks.FURNACE) || s.isOf(Blocks.BLAST_FURNACE), 16);
            if (furnace == null) {
                int slot = slotOf(net.minecraft.item.Items.FURNACE);
                if (slot < 0) {
                    say.accept("Nie widzę pieca i nie mam go w pasku.");
                    return end();
                }
                BlockPos spot = mc.player.getBlockPos().offset(mc.player.getHorizontalFacing(), 2);
                InventoryUtility.switchTo(slot);
                if (placeHeld(spot)) furnace = spot;
                wait = 4;
                return true;
            }
            if (!navigate(Vec3d.ofCenter(furnace), 3.0)) return true;
            release();
            use(furnace);
            wait = 6;
            return true;
        }

        private boolean end() {
            if (mc.player.currentScreenHandler != mc.player.playerScreenHandler) mc.player.closeHandledScreen();
            return false;
        }

        @Override
        public String describe() {
            return "przetapiam";
        }
    }

    /** Lies down in the nearest bed when it is night. */
    private final class Sleep implements Task {
        BlockPos bed = nearest(s -> s.isIn(BlockTags.BEDS), 32);
        int ticks;

        @Override
        public boolean tick() {
            if (bed == null) {
                say.accept("Nie widzę łóżka w pobliżu.");
                return false;
            }
            if (mc.player.isSleeping()) {
                say.accept("Śpię. Dobranoc!");
                return false;
            }
            if (++ticks > 20 * 60) return false;
            if (!navigate(Vec3d.ofCenter(bed), 2.0)) return true;
            release();
            use(bed);
            return ticks < 20 * 60 && !mc.player.isSleeping();
        }

        @Override
        public String describe() {
            return "idę spać";
        }
    }

    /** Puts everything outside the hotbar into the nearest chest. */
    private final class Store implements Task {
        BlockPos chest = nearest(s -> s.isOf(Blocks.CHEST) || s.isOf(Blocks.BARREL) || s.isOf(Blocks.TRAPPED_CHEST), 24);
        int ticks, wait;

        @Override
        public boolean tick() {
            if (chest == null) {
                say.accept("Nie widzę skrzyni w pobliżu.");
                return false;
            }
            if (++ticks > 20 * 60) return false;
            if (wait-- > 0) return true;
            var h = mc.player.currentScreenHandler;
            if (h instanceof net.minecraft.screen.GenericContainerScreenHandler g) {
                int chestSlots = g.getRows() * 9;
                // the player's main inventory follows the chest's slots; the last 9 are the hotbar - those stay
                for (int s = chestSlots; s < chestSlots + 27; s++)
                    if (!g.slots.get(s).getStack().isEmpty())
                        mc.interactionManager.clickSlot(g.syncId, s, 0, net.minecraft.screen.slot.SlotActionType.QUICK_MOVE, mc.player);
                mc.player.closeHandledScreen();
                say.accept("Schowałem rzeczy do skrzyni (pasek zostawiłem).");
                return false;
            }
            if (!navigate(Vec3d.ofCenter(chest), 3.0)) return true;
            release();
            use(chest);
            wait = 6;
            return true;
        }

        @Override
        public String describe() {
            return "chowam rzeczy";
        }
    }

    /** Harvests ripe crops around and plants them again. */
    private final class Farm implements Task {
        int ticks, wait, harvested;

        @Override
        public boolean tick() {
            if (++ticks > 20 * 180) return done();
            if (wait-- > 0) return true;
            BlockPos ripe = nearest(s -> s.getBlock() instanceof net.minecraft.block.CropBlock c && c.isMature(s), 16);
            if (ripe != null) {
                if (!navigate(Vec3d.ofCenter(ripe), 3.0)) return true;
                release();
                breakBlock(ripe);
                if (mc.world.getBlockState(ripe).isAir()) harvested++;
                wait = 2;
                return true;
            }
            // empty farmland: plant whatever seeds it has
            BlockPos bare = nearest(s -> s.isOf(Blocks.FARMLAND), 16);
            if (bare != null && mc.world.getBlockState(bare.up()).isAir()) {
                int seeds = -1;
                for (int i = 0; i < 9; i++) {
                    var it = mc.player.getInventory().getStack(i).getItem();
                    if (it == net.minecraft.item.Items.WHEAT_SEEDS || it == net.minecraft.item.Items.CARROT || it == net.minecraft.item.Items.POTATO || it == net.minecraft.item.Items.BEETROOT_SEEDS) {
                        seeds = i;
                        break;
                    }
                }
                if (seeds >= 0) {
                    if (!navigate(Vec3d.ofCenter(bare), 3.0)) return true;
                    release();
                    InventoryUtility.switchTo(seeds);
                    Vec3d top = Vec3d.ofCenter(bare).add(0, 0.5, 0);
                    lookAt(top);
                    mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, new BlockHitResult(top, Direction.UP, bare, false));
                    wait = 3;
                    return true;
                }
            }
            return done();
        }

        private boolean done() {
            say.accept(harvested > 0 ? "Zebrałem " + harvested + " plonów i posadziłem nowe." : "Nie ma tu nic dojrzałego do zebrania.");
            return false;
        }

        @Override
        public String describe() {
            return "pracuję na farmie";
        }
    }

    /* ---- doing what the player did: record, then replay ---- */

    /** One tick of the player: keys as bits, look, hotbar slot, and whether the attack key went down this tick. */
    private record Frame(int keys, float yaw, float pitch, int slot, boolean attackClick) {
    }

    private final List<Frame> recording = new ArrayList<>();
    private boolean recordingOn;
    private boolean lastAttack;

    boolean recording() {
        return recordingOn;
    }

    void startRecording() {
        recording.clear();
        recordingOn = true;
        lastAttack = false;
    }

    /** Stops recording; returns how many seconds were recorded. */
    int stopRecording() {
        recordingOn = false;
        return recording.size() / 20;
    }

    /** Called every tick while recording: keeps what the player does. Five minutes at most. */
    void recordTick() {
        if (!recordingOn || mc.player == null) return;
        var o = mc.options;
        int k = (o.forwardKey.isPressed() ? 1 : 0) | (o.backKey.isPressed() ? 2 : 0) | (o.leftKey.isPressed() ? 4 : 0)
                | (o.rightKey.isPressed() ? 8 : 0) | (o.jumpKey.isPressed() ? 16 : 0) | (o.sneakKey.isPressed() ? 32 : 0)
                | (o.sprintKey.isPressed() ? 64 : 0) | (o.attackKey.isPressed() ? 128 : 0) | (o.useKey.isPressed() ? 256 : 0);
        boolean attack = o.attackKey.isPressed();
        recording.add(new Frame(k, mc.player.getYaw(), mc.player.getPitch(), mc.player.getInventory().selectedSlot, attack && !lastAttack));
        lastAttack = attack;
        if (recording.size() >= 20 * 300) {
            recordingOn = false;
            say.accept("Nagrałem 5 minut - to maksimum. Napisz \"powtórz\", a zrobię to samo.");
        }
    }

    /** Does what was recorded, from where it stands now, turned to the way it is facing. */
    private final class Replay implements Task {
        final List<Frame> frames = new ArrayList<>(recording);
        final float yawOffset = frames.isEmpty() ? 0f : mc.player.getYaw() - frames.get(0).yaw();
        int i;

        @Override
        public boolean tick() {
            if (i >= frames.size()) {
                release();
                set(mc.options.attackKey, false);
                set(mc.options.useKey, false);
                say.accept("Powtórzyłem wszystko, co nagrałem.");
                return false;
            }
            Frame f = frames.get(i++);
            var o = mc.options;
            set(o.forwardKey, (f.keys() & 1) != 0);
            set(o.backKey, (f.keys() & 2) != 0);
            set(o.leftKey, (f.keys() & 4) != 0);
            set(o.rightKey, (f.keys() & 8) != 0);
            set(o.jumpKey, (f.keys() & 16) != 0);
            set(o.sneakKey, (f.keys() & 32) != 0);
            set(o.sprintKey, (f.keys() & 64) != 0);
            set(o.attackKey, (f.keys() & 128) != 0);   // held: breaking blocks carries on by itself
            set(o.useKey, (f.keys() & 256) != 0);      // held: placing, eating, drawing a bow
            mc.player.setYaw(f.yaw() + yawOffset);
            mc.player.setPitch(f.pitch());
            if (mc.player.getInventory().selectedSlot != f.slot()) InventoryUtility.switchTo(f.slot());
            // a click on a creature is a hit - the held key alone would not swing at it
            if (f.attackClick() && mc.crosshairTarget instanceof net.minecraft.util.hit.EntityHitResult eh) {
                mc.interactionManager.attackEntity(mc.player, eh.getEntity());
                mc.player.swingHand(Hand.MAIN_HAND);
            }
            return true;
        }

        @Override
        public String describe() {
            return "powtarzam po tobie " + (i / 20) + "/" + (frames.size() / 20) + " s";
        }
    }

    /** In the dark during a task: a torch at the feet, now and then. */
    private int torchCooldown;

    private void lightUp() {
        if (--torchCooldown > 0) return;
        torchCooldown = 40;
        BlockPos feet = mc.player.getBlockPos();
        if (mc.world.getLightLevel(net.minecraft.world.LightType.BLOCK, feet) >= 7 || mc.world.isSkyVisible(feet)) return;
        int slot = slotOf(net.minecraft.item.Items.TORCH);
        if (slot < 0 || !mc.world.getBlockState(feet).isReplaceable() || !solid(feet.down())) return;
        int before = mc.player.getInventory().selectedSlot;
        InventoryUtility.switchTo(slot);
        Vec3d top = Vec3d.ofCenter(feet.down()).add(0, 0.5, 0);
        mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, new BlockHitResult(top, Direction.UP, feet.down(), false));
        InventoryUtility.switchTo(before);
    }

    /* ------------------------------------------------------------------ */
    /* The client itself: "włącz fullbright", "zmień sky", "wyłącz trail"  */
    /* ------------------------------------------------------------------ */

    /** Polish (and loose English) words for modules, to their names. */
    private static final String[][] MODULE_WORDS = {
            {"niebo|nieba|sky", "CustomSky"}, {"jasnosc|fullbright|rozjasn|ciemno", "Fullbright"},
            {"smug|trail", "Trails"}, {"napis|tekst|lyrics|karaoke", "TextInMinecraft"},
            {"muzyk|music|spotify", "MusicWidget"}, {"wysp|island|pigulk", "DynamicIsland"},
            {"zbroj|armor", "ArmorHud"}, {"mikstur|potion|efekt", "PotionHud"}, {"czasteczk|particle", "Particles"},
            {"kolor|color grading|grading", "ColorGrading"}, {"obrys|esp", "ESP"}, {"tracer|linie", "Tracers"},
    };

    /**
     * Orders about the client rather than the game world: switching a module on or off, or "change" - the next
     * value of its mode. The module is found by its name anywhere in the sentence, or by a Polish word for it.
     * Returns the answer, or null when the sentence names no module.
     */
    static String control(String msg) {
        thunder.hack.features.modules.Module module = null;
        for (String[] w : MODULE_WORDS)
            if (msg.matches(".*(" + w[0] + ").*")) {
                module = thunder.hack.core.Managers.MODULE.get(w[1]);
                if (module != null) break;
            }
        if (module == null) {
            String flat = msg.replace(" ", "");
            int bestLen = 0;
            for (thunder.hack.features.modules.Module m : thunder.hack.core.Managers.MODULE.modules) {
                String name = m.getName().toLowerCase(Locale.ROOT);
                if (name.length() >= 3 && flat.contains(name) && name.length() > bestLen && !(m instanceof LearnAI)) {
                    module = m;
                    bestLen = name.length();
                }
            }
        }
        if (module == null) return null;

        String name = module.getName();
        if (msg.matches(".*\\b(wylacz|wylaczyc|zgas|zgasic|off|disable|schowaj|ukryj|usun)\\w*.*")) {
            if (module.isEnabled()) module.disable();
            return "Wyłączyłem " + name + ".";
        }
        if (msg.matches(".*\\b(zmien|zmienic|przelacz|nastepn|inny|inne|inna|change|next|switch)\\w*.*")) {
            for (thunder.hack.setting.Setting<?> s : module.getSettings()) {
                if (!s.isEnumSetting()) continue;
                s.increaseEnum();
                if (!module.isEnabled()) module.enable();
                return "Zmieniłem " + name + ": " + s.getName() + " → " + s.currentEnumName() + ".";
            }
            module.toggle();
            return (module.isEnabled() ? "Włączyłem " : "Wyłączyłem ") + name + ".";
        }
        if (msg.matches(".*\\b(wlacz|wlaczyc|odpal|uruchom|pokaz|on|enable|daj)\\w*.*")) {
            if (!module.isEnabled()) module.enable();
            return "Włączyłem " + name + ".";
        }
        module.toggle();
        return (module.isEnabled() ? "Włączyłem " : "Wyłączyłem ") + name + ".";
    }

    static String normalize(String raw) {
        return raw.toLowerCase(Locale.ROOT).strip()
                .replace('ą', 'a').replace('ę', 'e').replace('ś', 's').replace('ć', 'c')
                .replace('ł', 'l').replace('ó', 'o').replace('ż', 'z').replace('ź', 'z').replace('ń', 'n');
    }
}
