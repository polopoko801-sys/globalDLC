package thunder.hack.features.modules.misc;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.ai.RangedAttackMob;
import net.minecraft.entity.mob.CreeperEntity;
import net.minecraft.entity.mob.Monster;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.RaycastContext;
import thunder.hack.core.manager.client.ConfigManager;
import thunder.hack.features.modules.Module;
import thunder.hack.features.modules.client.HudEditor;
import thunder.hack.gui.font.FontRenderers;
import thunder.hack.setting.Setting;
import thunder.hack.utility.player.InventoryUtility;
import thunder.hack.utility.render.Render2DEngine;

import java.awt.Color;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Locale;
import java.util.Random;

/**
 * An experiment: a small agent that teaches itself to fight mobs by trial and error, and takes orders in chat.
 *
 * <p><b>How it learns.</b> Tabular Q-learning with experience replay. Five times a second it reads the situation
 * - how far the nearest monster is and which way, what kind it is (a creeper, an archer, anything else), whether
 * its own swing is charged, how much health it has, whether it was just hit - picks a move, and afterwards scores
 * it: damage dealt is good, a critical hit better, a kill very good; damage taken is bad, lingering next to a
 * creeper is bad, dying is very bad. Every step is also kept in a short memory and replayed, so one fight teaches
 * many times over. Early on it mostly experiments; the more it has seen, the more it does what worked. The table is
 * saved, so learning carries over between sessions.
 *
 * <p><b>What is hard-coded, not learned.</b> Facing the target, and safety: it will never walk into lava, fire or
 * off a ledge, whatever the table says - a learning agent has to survive long enough to learn.
 *
 * <p><b>Talking to it.</b> Chat lines starting with {@code ai } go to the agent, not to the server.
 *
 * <p><b>Where it runs.</b> In singleplayer always; on a server when the {@code Servers} setting is on. There it
 * does exactly what a player does - it presses the movement keys and sends swings - so there is nothing for the
 * server to disagree with except the decisions themselves. Two things are different on a server: damage arrives
 * a ping late, so a swing is scored when the health drop actually shows up instead of at the next decision; and
 * a swing is only sent when the target is inside reach and in sight, because a server refuses the rest and the
 * agent would learn from damage that never happened.
 *
 * <p>It only ever goes after monsters, never players.
 */
public class LearnAI extends Module {
    public LearnAI() {
        super("LearnAI", Category.MISC);
        // the game closing does not always switch modules off - save what was learned on the way out too
        Runtime.getRuntime().addShutdownHook(new Thread(this::save, "LearnAI-save"));
    }

    private final Setting<Boolean> panel = new Setting<>("Panel", true);
    /** How fast new experience overwrites old: higher learns quicker but forgets quicker. */
    private final Setting<Float> learnRate = new Setting<>("LearnRate", 0.3f, 0.02f, 0.8f);
    /** How far it looks for monsters, in blocks. */
    private final Setting<Float> range = new Setting<>("Range", 16f, 4f, 32f);
    /** Eat from the hotbar when health runs low. */
    private final Setting<Boolean> autoEat = new Setting<>("Eat", true);
    /** Say what it is doing and learning in chat now and then. */
    private final Setting<Boolean> chatty = new Setting<>("Talk", true);
    /** Run on servers too, not only in singleplayer. */
    private final Setting<Boolean> servers = new Setting<>("Servers", true);

    /* ------------------------------------------------------------------ */
    /* Moves                                                               */
    /* ------------------------------------------------------------------ */

    private enum Action {
        Approach("podchodzę"), Retreat("cofam się"), StrafeLeft("unik w lewo"), StrafeRight("unik w prawo"),
        CircleLeft("okrążam w lewo"), CircleRight("okrążam w prawo"), Strike("uderzam"),
        CritJump("skok i cios krytyczny"), Wait("czekam na moment"), Eat("jem");

        final String label;

        Action(String label) {
            this.label = label;
        }
    }

    private static final Action[] ACTIONS = Action.values();

    /* ------------------------------------------------------------------ */
    /* What it sees                                                        */
    /* ------------------------------------------------------------------ */

    private static final int DIST = 5, ANGLE = 4, KIND = 3, CHARGED = 2, HEALTH = 3, HURT = 2;
    private static final int STATES = DIST * ANGLE * KIND * CHARGED * HEALTH * HURT;   // 720
    private float[][] q = new float[STATES][ACTIONS.length];
    private int[][] visits = new int[STATES][ACTIONS.length];

    private static final float GAMMA = 0.9f;
    /** A decision every other tick: twice the experience per minute of fighting. */
    private static final int DECIDE_TICKS = 2;
    /** Only this kind of creature when set ("zabij owcę"); null: anything alive. */
    private net.minecraft.entity.EntityType<?> fightType;

    /* ------------------------------------------------------------------ */
    /* Replay memory                                                       */
    /* ------------------------------------------------------------------ */

    private static final int MEMORY = 6000, REPLAY = 64;
    private final int[] memS = new int[MEMORY], memA = new int[MEMORY], memNext = new int[MEMORY];
    private final float[] memR = new float[MEMORY];
    private int memCount, memHead;

    /* ------------------------------------------------------------------ */
    /* State of play                                                       */
    /* ------------------------------------------------------------------ */

    private enum Mode {Idle, Fight}

    private Mode mode = Mode.Idle;
    private boolean holdUse;
    private float curiosityBoost;

    private int lastState = -1;
    private Action lastAction = Action.Wait;
    private float lastPlayerHealth;
    private LivingEntity target;

    /* A swing whose damage has not arrived yet. On a server the health of the mob updates a ping later, so the
       reward for hitting cannot be read one decision later - it is read whenever the drop shows up. */
    private int pendingState = -1;
    private Action pendingAction = Action.Wait;
    private LivingEntity pendingTarget;
    private float pendingHealth;
    private boolean pendingCrit;
    private long pendingAt;
    private int ticks, idleNotice, previousSlot = -1;

    private long steps;
    private int kills, deaths, crits;
    private float avgReward;
    /** Average reward per 100 decisions, for the learning curve on the panel. */
    private final float[] curve = new float[48];
    private int curveLen;
    private float curveSum;
    private int curveCount;

    private final Random random = new Random();
    private long lastSave;

    /** Walking, mining and building on plain orders. */
    private final AiTasks tasks = new AiTasks(this::reply);
    /** Whether the agent is holding the movement keys / the use key - so it only lets go of what it pressed. */
    private boolean movementHeld, useHeld;

    @Override
    public void onEnable() {
        load();
        mode = Mode.Idle;
        lastState = -1;
        lastPlayerHealth = mc.player != null ? mc.player.getHealth() : 20f;
        if (mc.player == null || !allowed()) return;
        reply(steps == 0
                ? "Cześć! Nic jeszcze nie umiem. Napisz \"ai walcz\", a zacznę uczyć się walki z mobami."
                : String.format(Locale.ROOT, "Wróciłem. Mam za sobą %d decyzji i %d pokonanych mobów. \"ai walcz\" - lecimy.", steps, kills));
    }

    @Override
    public void onDisable() {
        releaseKeys();
        clearPending();
        save();
    }

    /** Singleplayer always; on a server only when the user said so in the settings. */
    private boolean allowed() {
        return mc.player == null || mc.isInSingleplayer() || servers.getValue();
    }

    /**
     * Not a module you switch: it never shows in any menu. It runs in the background from the first order given
     * through the chat badge, and on a server it simply stays idle.
     */
    @Override
    public boolean isHidden() {
        return true;
    }

    /* ------------------------------------------------------------------ */
    /* Chat                                                                */
    /* ------------------------------------------------------------------ */

    /** A "did you mean" waiting for its yes or no. */
    private AiLanguage.Intent pendingIntent;
    private String pendingSentence = "";

    /** What an understood sentence means as one of the plain orders the rest of the agent reads. */
    private static String canonical(AiLanguage.Intent intent, String s) {
        return switch (intent) {
            case Go -> "idz " + s;
            case Village -> "idz do wioski";
            case Craft -> "zrob " + s;
            case Smelt -> "przetop " + s;
            case Sleep -> "idz spac";
            case Store -> "schowaj rzeczy do skrzyni";
            case Farm -> "farma";
            case Record -> "nagrywaj";
            case Replay -> "powtorz";
            case Wander -> "idz gdzies";
            case Back -> "wroc";
            case Mine -> "wykop " + s;
            case Hut -> "zbuduj domek";
            case Wall -> "zbuduj sciane " + s.replaceAll("[^0-9 ]", " ");
            case Pillar -> "zbuduj slup " + s.replaceAll("[^0-9 ]", " ");
            case Fight -> "walcz " + s;
            case Stop -> "stop";
            case Learn -> "ucz sie";
            case Stats -> "statystyki";
            case Explain -> "czego sie nauczyles";
            case Reset -> "reset";
            case Use -> "klikaj prawym";
            case Eat -> "zjedz";
            case On -> "wlacz " + s;
            case Off -> "wylacz " + s;
            case Change -> "zmien " + s;
            default -> s;
        };
    }

    /** A chat line addressed to the agent (the part after "ai "). */
    public void onChat(String raw) {
        log(true, raw.strip());
        if (!isEnabled()) enable();   // the first order wakes it up - there is no switch to flip
        if (!allowed()) {
            reply("Na serwerach mam wyłączone - włącz opcję \"Servers\" w ustawieniach modułu.");
            return;
        }
        String msg = AiTasks.normalize(raw);
        // while recording, "koniec" / "stop" ends the recording rather than stopping anything else
        if (tasks.recording() && msg.matches(".*\\b(koniec|stop|wystarczy|zakoncz|nagryw)\\w*.*")) {
            reply("Koniec nagrywania: " + tasks.stopRecording() + " s. Napisz \"powtórz\", a zrobię to samo.");
            return;
        }

        // the answer to its own "did you mean ...?": a yes teaches it the sentence for good
        if (pendingIntent != null) {
            AiLanguage.Intent was = pendingIntent;
            String sentence = pendingSentence;
            pendingIntent = null;
            if (msg.matches("(tak|ta|yes|no tak|dokladnie|zgadza sie|yhm|aha|mhm|jasne|oczywiscie)\\b.*")) {
                AiLanguage.learn(sentence, was);
                reply("Zapamiętałem: \"" + sentence + "\" to " + was.label + ". Następnym razem zrozumiem od razu.");
                msg = canonical(was, sentence);
            } else if (msg.matches("(nie|no|nope|zle|nie o to)\\b.*")) {
                reply("Dobra, napisz to inaczej - spróbuję zrozumieć.");
                return;
            }
        } else {
            // own language understanding: any sentence, typos and glued words included, becomes a plain order
            AiLanguage.Meaning m = AiLanguage.understand(msg);
            if (m.confidence() < 0.5) {
                pendingIntent = m.intent();
                pendingSentence = m.sentence();
                reply("Nie jestem pewien, o co chodzi. Chodziło ci o: " + m.intent().label + "? (tak/nie)");
                return;
            }
            switch (m.intent()) {
                case Hello, Thanks, HowAreYou -> {
                    reply(AiLanguage.smallTalk(m.intent()));
                    return;
                }
                default -> msg = canonical(m.intent(), m.sentence());
            }
        }
        // plain orders first: walking, mining, building ("idź 2 kratki", "wykop 8 diamentów", "zbuduj domek")
        if (!msg.matches(".*(stop|stoj|przestan|koniec|spokoj).*")) {
            String answer = tasks.tryStart(msg);
            if (answer != null) {
                mode = Mode.Idle;
                reply(answer);
                return;
            }
        }
        if (msg.matches(".*(walcz|bij|atak|fight|zabij|polu).*")) {
            tasks.stop();
            mode = Mode.Fight;
            // "zabij owcę", "bij tylko krowy": just that kind; plain "walcz": anything alive
            fightType = AiTasks.creatureNamed(msg);
            if (mc.player != null) wieldWeapon();   // into the fight sword in hand
            reply((fightType != null ? "Biję: " + fightType.getName().getString().toLowerCase(Locale.ROOT) + ". " : "")
                    + (steps < 2000 ? "Walczę! Na początku będę się mylił - uczę się na błędach." : "Walczę. Zobaczymy, ile się nauczyłem."));
        } else if (msg.matches(".*(stop|stoj|przestan|koniec|spokoj).*")) {
            mode = Mode.Idle;
            tasks.stop();
            releaseMovement();
            reply("Stoję.");
        } else if (msg.matches(".*(prawy|prawym|uzywaj|blok|tarcz).*")) {
            holdUse = !holdUse;
            reply(holdUse ? "Trzymam prawy przycisk." : "Puszczam prawy przycisk.");
        } else if (msg.matches(".*(serwer|server|multi).*")) {
            // the module is hidden from the menus, so the setting also has an order of its own
            servers.setValue(!servers.getValue());
            if (!servers.getValue() && !mc.isInSingleplayer()) {
                mode = Mode.Idle;
                releaseKeys();
            }
            reply(servers.getValue() ? "Gram też na serwerach." : "Tylko świat jednoosobowy.");
        } else if (msg.matches(".*(ucz|eksplor|probuj|kombinuj).*")) {
            curiosityBoost = 0.35f;
            reply("Przez chwilę będę próbował więcej nowych rzeczy.");
        } else if (msg.matches(".*(stat|ile|wynik|postep).*")) {
            reply(String.format(Locale.ROOT, "Decyzje: %d, pokonane moby: %d, ciosy krytyczne: %d, śmierci: %d, średnia nagroda: %.2f, ciekawość: %d%%",
                    steps, kills, crits, deaths, avgReward, Math.round(epsilon() * 100)));
            reply(tasks.skills());
        } else if (msg.matches(".*(czego|nauczyl|wiesz|strategi|jak walczysz).*")) {
            explain();
        } else if (msg.matches(".*(reset|zapomnij|od nowa).*")) {
            q = new float[STATES][ACTIONS.length];
            visits = new int[STATES][ACTIONS.length];
            prime();
            steps = kills = deaths = crits = 0;
            avgReward = 0f;
            memCount = memHead = 0;
            curveLen = 0;
            reply("Zapomniałem wszystko, co sam odkryłem - została mi tylko wiedza startowa.");
        } else {
            // orders about the client itself: "włącz fullbright", "zmień sky", "wyłącz trail"
            String control = AiTasks.control(msg);
            if (control != null) {
                reply(control);
                return;
            }
            reply("Nie zrozumiałem. Możesz napisać np.: \"idź 2 kratki do przodu\", \"idź gdzieś\", \"wykop 8 diamentów\", \"zetnij 10 drewna\", "
                    + "\"zbuduj domek\", \"zbuduj słup 5\", \"wróć\", \"walcz\", \"ucz się\", \"zmień niebo\", \"włącz fullbright\", \"stop\".");
        }
    }

    /** Tells what it learned for a few typical situations - read straight out of the table. */
    private void explain() {
        if (steps < 300) {
            reply("Jeszcze za mało widziałem, żeby coś powiedzieć.");
            return;
        }
        reply("Zombie przede mną, cios naładowany: " + best(encode(0, 0, 0, 1, 0, 0)).label + ".");
        reply("Zombie przede mną, cios się ładuje: " + best(encode(0, 0, 0, 0, 0, 0)).label + ".");
        reply("Creeper tuż obok: " + best(encode(0, 0, 1, 1, 0, 0)).label + ".");
        reply("Mało życia i dostałem: " + best(encode(1, 0, 0, 0, 2, 1)).label + ".");
    }

    private void reply(String text) {
        sendMessage("[LearnAI] " + text);
        log(false, text);
    }

    /** One line of the conversation shown in the AI window: who said it, and what. */
    public record Said(boolean fromPlayer, String text, long at) {
    }

    /** The conversation so far, oldest first, for the AI chat window. */
    public final java.util.List<Said> conversation = new java.util.concurrent.CopyOnWriteArrayList<>();

    private void log(boolean fromPlayer, String text) {
        conversation.add(new Said(fromPlayer, text, System.currentTimeMillis()));
        while (conversation.size() > 60) conversation.remove(0);
    }

    /* ------------------------------------------------------------------ */
    /* The loop                                                            */
    /* ------------------------------------------------------------------ */

    @Override
    public void onUpdate() {
        if (fullNullCheck()) return;
        // recording the player ("nagrywaj"): every tick, whatever else is going on
        tasks.recordTick();
        // a server the user did not allow: hands off the keys, no fighting
        if (!allowed()) {
            if (mode == Mode.Fight) {
                mode = Mode.Idle;
                releaseKeys();
            }
            return;
        }
        // The use key is only ever held, never forced up every tick - that took right click away from the player.
        boolean wantUse = holdUse || (lastAction == Action.Eat && eating());
        if (wantUse) {
            mc.options.useKey.setPressed(true);
            useHeld = true;
        } else if (useHeld) {
            mc.options.useKey.setPressed(false);
            useHeld = false;
        }

        // Playing on its own ("ucz się" / "graj sam"): it explores and practises, and when a monster comes close it
        // drops what it is doing and fights, then goes back to it.
        if (tasks.active() && tasks.learning() && mc.isInSingleplayer()) {
            boolean threat = nearestThreat(10) != null;
            if (threat && !autoFight) {
                tasks.release();
                mode = Mode.Fight;
                autoFight = true;
                if (chatty.getValue()) reply("Potwór! Walczę.");
            } else if (!threat && autoFight) {
                mode = Mode.Idle;
                autoFight = false;
                releaseMovement();
            }
            if (!autoFight) {
                tasks.tick();
                return;
            }
        } else if (tasks.active()) {
            // a task (walking, mining, building) has the controls while it runs
            tasks.tick();
            return;
        } else if (autoFight) {
            autoFight = false;
            mode = Mode.Idle;
        }

        if (System.currentTimeMillis() - lastSave > 15_000L) save();

        if (mc.player.isDead() || mc.player.getHealth() <= 0f) {
            clearPending();
            if (lastState >= 0) {
                remember(lastState, lastAction.ordinal(), -12f, -1);
                deaths++;
                if (chatty.getValue()) reply(deaths % 5 == 1 ? "Ała. Zapamiętam to." : "Znowu... uczę się.");
                lastState = -1;
            }
            releaseMovement();
            return;
        }

        if (mode != Mode.Fight) {
            releaseMovement();
            // a swing sent before the "stop" is still scored when its damage arrives
            if (pendingState >= 0) resolveHit(-1);
            // while the player fights by hand, it watches and learns from them
            if (mc.isInSingleplayer()) observePlayer();
            return;
        }

        // playing alone it fights the monster that came at it, not the nearest sheep
        target = autoFight ? nearestThreat(16) : nearestMonster();
        int state = target == null ? -1 : encode(target);
        // a swing is scored here, as soon as the damage it caused has arrived - not one decision later, which
        // on a server is long before the answer comes back
        resolveHit(state);

        if (target == null) {
            releaseMovement();
            if (++idleNotice % 300 == 1 && chatty.getValue()) reply("Nie widzę żadnego moba w pobliżu.");
            lastState = -1;
            return;
        }
        idleNotice = 0;

        // always face the target - WHAT to do about it is what gets learned
        lookAt(target, 0.35f);

        // instincts before any learned move: shield up, bow at range, run when nearly dead with nothing to eat
        if (combatInstinct()) return;

        if (++ticks % DECIDE_TICKS != 0) {
            perform(lastAction);
            return;
        }

        // score the previous move by what happened since: damage dealt is scored by resolveHit, what is left
        // here is the cost of the moment - being hit, standing next to a creeper, being out of position
        if (lastState >= 0) {
            float taken = Math.max(0f, lastPlayerHealth - mc.player.getHealth());
            double dist = mc.player.distanceTo(target);
            float reward = -taken * 1.3f - 0.02f;
            // a creeper up close is a bad place to be, even before it goes off
            if (target instanceof CreeperEntity && dist < 3.0) reward -= 0.6f;
            // the sweet spot: in reach with a charged swing
            if (dist > 2.2 && dist < 3.1 && charged()) reward += 0.05f;
            remember(lastState, lastAction.ordinal(), reward, state);
            record(reward);
        }

        replay();

        Action action = choose(state);
        lastState = state;
        lastAction = action;
        lastPlayerHealth = mc.player.getHealth();
        steps++;
        curiosityBoost = Math.max(0f, curiosityBoost - 0.0015f);
        if (action == Action.Eat) startEating();
        perform(action);
    }

    /* ------------------------------------------------------------------ */
    /* Learning                                                            */
    /* ------------------------------------------------------------------ */

    /** Learns from a step now and keeps it for replay. */
    private void remember(int s, int a, float r, int next) {
        update(s, a, r, next);
        memS[memHead] = s;
        memA[memHead] = a;
        memR[memHead] = r;
        memNext[memHead] = next;
        memHead = (memHead + 1) % MEMORY;
        memCount = Math.min(memCount + 1, MEMORY);
        visits[s][a]++;
    }

    /** Re-learns from a handful of past steps: every fight teaches many times over, and rare lessons stick. */
    private void replay() {
        if (memCount < 50) return;
        for (int i = 0; i < REPLAY; i++) {
            // half the replays favour the steps that mattered - big rewards and big penalties teach most
            int k = random.nextInt(memCount);
            if ((i & 1) == 0) {
                int other = random.nextInt(memCount);
                if (Math.abs(memR[other]) > Math.abs(memR[k])) k = other;
            }
            update(memS[k], memA[k], memR[k], memNext[k]);
        }
    }

    private void update(int s, int a, float r, int next) {
        float future = next < 0 ? 0f : max(q[next]);
        Action before = best(s);
        float delta = learnRate.getValue() * (r + GAMMA * future - q[s][a]);
        q[s][a] += delta;
        generalize(s, a, delta);
        Action after = best(s);
        // its own experience just overruled what it thought best here: say so, so the learning can be seen
        if (after != before && chatty.getValue() && System.currentTimeMillis() - lastLesson > 15_000L) {
            lastLesson = System.currentTimeMillis();
            reply("Nauczyłem się: " + describeState(s) + " lepiej \"" + after.label + "\" niż \"" + before.label + "\".");
        }
    }

    private long lastLesson;

    /**
     * Shares a lesson with the situations that only differ in details that hardly change what to do - the mob
     * standing on another side, or having just been hit or not - at a quarter strength. One fight then teaches
     * many situations at once instead of each having to be lived through on its own.
     */
    private void generalize(int s, int a, float delta) {
        int hurt = s % HURT;
        int rest = s / HURT;
        int health = rest % HEALTH;
        rest /= HEALTH;
        int charged = rest % CHARGED;
        rest /= CHARGED;
        int kind = rest % KIND;
        rest /= KIND;
        int angle = rest % ANGLE;
        int dist = rest / ANGLE;
        for (int ang = 0; ang < ANGLE; ang++)
            for (int h = 0; h < HURT; h++) {
                if (ang == angle && h == hurt) continue;
                q[encode(dist, ang, kind, charged, health, h)][a] += delta * 0.25f;
            }
    }

    /** A situation in words, decoded from its table index. */
    private static String describeState(int s) {
        int hurt = s % HURT;
        s /= HURT;
        int health = s % HEALTH;
        s /= HEALTH;
        int charged = s % CHARGED;
        s /= CHARGED;
        int kind = s % KIND;
        s /= KIND;
        s /= ANGLE;
        int dist = s;
        String mob = kind == 1 ? "creeper" : kind == 2 ? "mob strzelający" : "mob";
        String where = dist == 0 ? "tuż obok" : dist == 1 ? "blisko" : dist == 2 ? "kilka kratek dalej" : "daleko";
        return mob + " " + where + (charged == 1 ? ", cios naładowany" : ", cios się ładuje")
                + (health == 2 ? ", mało życia" : "") + (hurt == 1 ? ", oberwałem" : "") + " -";
    }

    private void record(float reward) {
        avgReward += (reward - avgReward) * 0.02f;
        curveSum += reward;
        if (++curveCount >= 100) {
            if (curveLen == curve.length) System.arraycopy(curve, 1, curve, 0, curve.length - 1);
            else curveLen++;
            curve[curveLen - 1] = curveSum / curveCount;
            curveSum = 0f;
            curveCount = 0;
        }
    }

    /**
     * Mostly the best known move, sometimes a random one - less often the more it has learned. A move never tried
     * in this situation gets a small bonus, so it does not settle before it has tried things.
     */
    /* ---- combat instincts: things a decent player just does ---- */

    private boolean shieldUp;
    private int bowDraw;

    /**
     * Instincts that come before any learned move. Shield up (from the off hand) when a creeper next to it is
     * hissing or an archer is drawing on it; the bow when a monster keeps its distance; running when it is down to
     * three hearts with nothing to eat. Returns true while an instinct has the controls this tick.
     */
    private boolean combatInstinct() {
        double dist = mc.player.distanceTo(target);

        // run: nearly dead and nothing to eat - back off until it has healed a little
        if (mc.player.getHealth() <= 6f && !canEat()) {
            dropShield();
            perform(Action.Retreat);
            mc.options.sprintKey.setPressed(true);
            return true;
        }

        // shield: a creeper about to go off, or an archer drawing its bow
        boolean hasShield = mc.player.getOffHandStack().isOf(net.minecraft.item.Items.SHIELD);
        boolean creeperHiss = target instanceof CreeperEntity c && dist < 4.5 && c.getFuseSpeed() > 0;
        boolean archerAims = target instanceof RangedAttackMob && target.isUsingItem() && dist > 3.5 && dist < 20;
        if (hasShield && (creeperHiss || archerAims)) {
            if (!shieldUp) {
                releaseMovement();
                shieldUp = true;
            }
            mc.options.useKey.setPressed(true);
            useHeld = true;
            if (creeperHiss) {
                // shield up AND step back from the blast
                mc.options.backKey.setPressed(true);
                movementHeld = true;
            }
            return true;
        }
        dropShield();

        // bow: a monster out of reach - shoot it, with a little lift for the arrow's drop
        int bow = -1;
        for (int i = 0; i < 9; i++) if (mc.player.getInventory().getStack(i).isOf(net.minecraft.item.Items.BOW)) bow = i;
        boolean arrows = mc.player.getInventory().contains(new net.minecraft.item.ItemStack(net.minecraft.item.Items.ARROW));
        if (bow >= 0 && arrows && dist > 8 && dist < 40) {
            if (mc.player.getInventory().selectedSlot != bow) InventoryUtility.switchTo(bow);
            releaseMovement();
            float lift = (float) (dist * dist * 0.006);
            double dx = target.getX() - mc.player.getX(), dz = target.getZ() - mc.player.getZ();
            double dy = target.getEyeY() + lift - mc.player.getEyeY();
            mc.player.setYaw((float) Math.toDegrees(Math.atan2(dz, dx)) - 90f);
            mc.player.setPitch((float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz))));
            mc.options.useKey.setPressed(true);
            useHeld = true;
            if (++bowDraw >= 22) {
                // full draw: let go - the arrow flies
                mc.options.useKey.setPressed(false);
                mc.interactionManager.stopUsingItem(mc.player);
                bowDraw = 0;
            }
            return true;
        }
        if (bowDraw > 0) {
            mc.options.useKey.setPressed(false);
            bowDraw = 0;
        }
        return false;
    }

    private void dropShield() {
        if (!shieldUp) return;
        shieldUp = false;
        mc.options.useKey.setPressed(false);
        useHeld = false;
    }

    /** Fighting because a monster turned up while it was playing on its own - not because it was told to. */
    private boolean autoFight;

    /** The nearest monster within {@code range} blocks - what makes it stop and fight while playing alone. */
    private LivingEntity nearestThreat(double range) {
        LivingEntity best = null;
        double bestDist = range * range;
        for (Entity e : mc.world.getEntities())
            if (e instanceof Monster && e instanceof LivingEntity l && l.isAlive()) {
                double d = mc.player.squaredDistanceTo(e);
                if (d < bestDist) {
                    bestDist = d;
                    best = l;
                }
            }
        return best;
    }

    /* ---- learning by watching the player ---- */

    private int watchTick, watched;

    /**
     * While the player fights a mob by hand, the agent watches: it reads the situation the same way it reads its
     * own, works out from the keys which move the player made - stepping in, striking, a jump crit, circling,
     * backing off, eating - and takes that move as a good one for that situation. What a person does is its
     * best teacher; its own fights then refine it.
     */
    private void observePlayer() {
        if (++watchTick % DECIDE_TICKS != 0) return;
        LivingEntity t = nearestThreat(7);
        if (t == null) return;
        boolean attacking = mc.options.attackKey.isPressed();
        boolean moving = mc.options.forwardKey.isPressed() || mc.options.backKey.isPressed()
                || mc.options.leftKey.isPressed() || mc.options.rightKey.isPressed();
        if (!attacking && !moving && !mc.player.isUsingItem()) return;
        Action a = playerMove(attacking);
        int s = encode(t);
        float delta = learnRate.getValue() * 0.5f * (1.2f - q[s][a.ordinal()]);
        q[s][a.ordinal()] += delta;
        generalize(s, a.ordinal(), delta);
        visits[s][a.ordinal()]++;
        if (++watched % 150 == 1 && chatty.getValue())
            reply("Patrzę, jak walczysz, i uczę się od ciebie (" + watched + " podpatrzonych ruchów).");
    }

    /** The player's move this moment, read from their keys and their motion. */
    private Action playerMove(boolean attacking) {
        if (mc.player.isUsingItem() && mc.player.getActiveItem().getComponents().contains(DataComponentTypes.FOOD)) return Action.Eat;
        if (attacking && !mc.player.isOnGround() && mc.player.getVelocity().y < 0) return Action.CritJump;
        if (attacking) return Action.Strike;
        boolean f = mc.options.forwardKey.isPressed(), b = mc.options.backKey.isPressed();
        boolean l = mc.options.leftKey.isPressed(), r = mc.options.rightKey.isPressed();
        if (b) return Action.Retreat;
        if (f && l) return Action.CircleLeft;
        if (f && r) return Action.CircleRight;
        if (l) return Action.StrafeLeft;
        if (r) return Action.StrafeRight;
        if (f) return Action.Approach;
        return Action.Wait;
    }

    private Action choose(int state) {
        // instinct, not a choice: four hearts or less and food in the hotbar - eat
        if (mc.player.getHealth() <= 8f && canEat()) return Action.Eat;
        if (random.nextFloat() < epsilon()) {
            Action a = ACTIONS[random.nextInt(ACTIONS.length)];
            return a == Action.Eat && !canEat() ? Action.Wait : a;
        }
        Action best = Action.Wait;
        float bestScore = -Float.MAX_VALUE;
        for (Action a : ACTIONS) {
            if (a == Action.Eat && !canEat()) continue;
            float score = q[state][a.ordinal()] + (visits[state][a.ordinal()] == 0 ? 0.3f : 0f);
            if (score > bestScore) {
                bestScore = score;
                best = a;
            }
        }
        return best;
    }

    private Action best(int state) {
        int b = 0;
        for (int i = 1; i < ACTIONS.length; i++) if (q[state][i] > q[state][b]) b = i;
        return ACTIONS[b];
    }

    private float epsilon() {
        return Math.min(0.9f, Math.max(0.04f, 0.4f * (float) Math.exp(-steps / 6000.0)) + curiosityBoost);
    }

    private static float max(float[] row) {
        float m = row[0];
        for (float v : row) m = Math.max(m, v);
        return m;
    }

    /* ------------------------------------------------------------------ */
    /* Seeing                                                              */
    /* ------------------------------------------------------------------ */

    private LivingEntity nearestMonster() {
        LivingEntity best = null;
        double bestDist = range.getValue() * range.getValue();
        for (Entity e : mc.world.getEntities()) {
            // anything alive - animals too - except armor stands and players (never a player)
            if (!(e instanceof LivingEntity living) || !living.isAlive() || e == mc.player
                    || e instanceof net.minecraft.entity.decoration.ArmorStandEntity || e instanceof net.minecraft.entity.player.PlayerEntity) continue;
            if (fightType != null && e.getType() != fightType) continue;
            double d = mc.player.squaredDistanceTo(e);
            if (d < bestDist) {
                bestDist = d;
                best = living;
            }
        }
        return best;
    }

    private boolean charged() {
        return mc.player.getAttackCooldownProgress(0.5f) >= 0.9f;
    }

    private int encode(LivingEntity t) {
        double d = mc.player.distanceTo(t);
        int dist = d < 2.5 ? 0 : d < 3.5 ? 1 : d < 6 ? 2 : d < 10 ? 3 : 4;

        double dx = t.getX() - mc.player.getX(), dz = t.getZ() - mc.player.getZ();
        float toTarget = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90f;
        float rel = MathHelper.wrapDegrees(toTarget - mc.player.getBodyYaw());
        int angle = Math.abs(rel) < 45 ? 0 : rel >= 45 && rel < 135 ? 1 : rel <= -45 && rel > -135 ? 2 : 3;

        int kind = t instanceof CreeperEntity ? 1 : t instanceof RangedAttackMob ? 2 : 0;
        float hp = mc.player.getHealth();
        int health = hp > 14 ? 0 : hp > 7 ? 1 : 2;
        return encode(dist, angle, kind, charged() ? 1 : 0, health, mc.player.hurtTime > 0 ? 1 : 0);
    }

    /**
     * Starting knowledge: what a player who knows the basics would do, written into the table as modest scores so
     * the agent starts out competent - and its own experience can still overrule every one of them.
     *
     * <p>In reach with a charged swing: jump and crit, or a plain hit. Swing still charging: circle and wait for
     * it. Far off: close in. A creeper close by: back off and sidestep. An archer: zigzag in. Low on health and
     * being hit: eat or pull back.
     */
    private void prime() {
        for (int dist = 0; dist < DIST; dist++)
            for (int angle = 0; angle < ANGLE; angle++)
                for (int kind = 0; kind < KIND; kind++)
                    for (int charged = 0; charged < CHARGED; charged++)
                        for (int health = 0; health < HEALTH; health++)
                            for (int hurt = 0; hurt < HURT; hurt++) {
                                float[] row = q[encode(dist, angle, kind, charged, health, hurt)];
                                java.util.Arrays.fill(row, 0f);
                                boolean near = dist <= 1, inReach = dist == 0, low = health == 2;

                                if (kind == 1 && near) {                       // creeper up close: get away
                                    row[Action.Retreat.ordinal()] = 1.2f;
                                    row[Action.StrafeLeft.ordinal()] = 0.6f;
                                    row[Action.StrafeRight.ordinal()] = 0.6f;
                                    if (charged == 1 && inReach && !low) row[Action.Strike.ordinal()] = 0.7f;
                                    continue;
                                }
                                if (low && hurt == 1) {                        // in trouble: heal or back off
                                    row[Action.Eat.ordinal()] = 1.1f;
                                    row[Action.Retreat.ordinal()] = 0.8f;
                                }
                                if (inReach) {
                                    if (charged == 1) {
                                        row[Action.CritJump.ordinal()] = Math.max(row[Action.CritJump.ordinal()], 1.0f);
                                        row[Action.Strike.ordinal()] = Math.max(row[Action.Strike.ordinal()], 0.85f);
                                    } else {
                                        row[Action.CircleLeft.ordinal()] = 0.45f;
                                        row[Action.CircleRight.ordinal()] = 0.45f;
                                        row[Action.Wait.ordinal()] = 0.35f;
                                    }
                                } else if (dist == 1) {
                                    row[Action.Approach.ordinal()] = 0.6f;
                                    if (charged == 1) row[Action.CritJump.ordinal()] = Math.max(row[Action.CritJump.ordinal()], 0.65f);
                                } else {
                                    row[Action.Approach.ordinal()] = Math.max(row[Action.Approach.ordinal()], 0.7f);
                                    if (kind == 2) {                           // an archer: come in zigzagging
                                        row[Action.CircleLeft.ordinal()] = 0.6f;
                                        row[Action.CircleRight.ordinal()] = 0.6f;
                                    }
                                }
                                if (low && !inReach) row[Action.Eat.ordinal()] = Math.max(row[Action.Eat.ordinal()], 0.9f);
                            }
        // the starting knowledge counts as tried once, so the agent does not rush to re-test all of it
        for (int[] v : visits) java.util.Arrays.fill(v, 1);
    }

    private static int encode(int dist, int angle, int kind, int charged, int health, int hurt) {
        return ((((dist * ANGLE + angle) * KIND + kind) * CHARGED + charged) * HEALTH + health) * HURT + hurt;
    }

    /** @param smooth how much of the way to the target to turn in this tick; 1 = exactly onto it */
    private void lookAt(Entity t, float smooth) {
        double dx = t.getX() - mc.player.getX();
        double dy = t.getEyeY() - 0.3 - mc.player.getEyeY();
        double dz = t.getZ() - mc.player.getZ();
        float yaw = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90f;
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
        // turn smoothly, like a person would
        mc.player.setYaw(mc.player.getYaw() + MathHelper.wrapDegrees(yaw - mc.player.getYaw()) * smooth);
        mc.player.setPitch(MathHelper.clamp(mc.player.getPitch() + (pitch - mc.player.getPitch()) * smooth, -90f, 90f));
    }

    /**
     * Puts the crosshair exactly on the target just before the swing, and on a server says so in the same breath.
     * The server does not check where you look when you swing, but every other player sees it - and on a server
     * that does check, a swing that came from a crosshair pointing at the sky is a ban.
     */
    private void snap(Entity t) {
        lookAt(t, 1f);
        if (mc.isInSingleplayer()) return;
        sendPacket(new PlayerMoveC2SPacket.LookAndOnGround(mc.player.getYaw(), mc.player.getPitch(),
                mc.player.isOnGround(), mc.player.horizontalCollision));
    }

    /** Eye to the target's hitbox - the same distance a server allows a swing from. */
    private boolean inReach(LivingEntity t) {
        var eye = mc.player.getEyePos();
        var box = t.getBoundingBox();
        double dx = eye.x - MathHelper.clamp(eye.x, box.minX, box.maxX);
        double dy = eye.y - MathHelper.clamp(eye.y, box.minY, box.maxY);
        double dz = eye.z - MathHelper.clamp(eye.z, box.minZ, box.maxZ);
        return dx * dx + dy * dy + dz * dz <= 9.0;
    }

    /** Whether nothing solid stands between the eyes and the target: a swing through a wall is refused server-side. */
    private boolean canSee(LivingEntity t) {
        return mc.world.raycast(new RaycastContext(mc.player.getEyePos(), t.getBoundingBox().getCenter(),
                RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, mc.player)).getType()
                == HitResult.Type.MISS;
    }

    /* ------------------------------------------------------------------ */
    /* Doing                                                               */
    /* ------------------------------------------------------------------ */

    private void perform(Action a) {
        movementHeld = true;
        boolean fwd = a == Action.Approach || a == Action.Strike || a == Action.CritJump || a == Action.CircleLeft || a == Action.CircleRight;
        boolean back = a == Action.Retreat;
        boolean left = a == Action.StrafeLeft || a == Action.CircleLeft;
        boolean right = a == Action.StrafeRight || a == Action.CircleRight;

        // safety first: never step into lava, fire or off a ledge, whatever the table says
        float yaw = (float) Math.toRadians(mc.player.getYaw());
        double fx = -Math.sin(yaw), fz = Math.cos(yaw);
        if (fwd && !safe(fx, fz)) fwd = false;
        if (back && !safe(-fx, -fz)) back = false;
        if (left && !safe(fz, -fx)) left = false;
        if (right && !safe(-fz, fx)) right = false;

        set(mc.options.forwardKey, fwd);
        set(mc.options.backKey, back);
        set(mc.options.leftKey, left);
        set(mc.options.rightKey, right);
        set(mc.options.sprintKey, a == Action.Approach && mc.player.distanceTo(target) > 4);
        set(mc.options.jumpKey, a == Action.CritJump && mc.player.isOnGround());

        if (target == null) return;
        // the critical hit lands on the way down - that is what makes it a crit
        boolean crit = a == Action.CritJump && !mc.player.isOnGround() && mc.player.getVelocity().y < -0.05;
        if (a != Action.Strike && !crit) return;
        if (mc.interactionManager == null) return;
        if (!charged() || mc.player.isUsingItem() || !inReach(target) || !canSee(target)) return;
        hit(crit);
    }

    /**
     * The best weapon in the hotbar: a sword before an axe (faster swings), and within each the better material.
     * -1 when there is no weapon at all.
     */
    private int bestWeaponSlot() {
        int best = -1, bestScore = 0;
        for (int i = 0; i < 9; i++) {
            var stack = mc.player.getInventory().getStack(i);
            if (stack.isEmpty()) continue;
            String id = net.minecraft.registry.Registries.ITEM.getId(stack.getItem()).getPath();
            int kind = id.endsWith("_sword") ? 100 : id.endsWith("_axe") ? 60 : id.equals("trident") || id.equals("mace") ? 50 : 0;
            if (kind == 0) continue;
            int tier = id.startsWith("netherite") ? 6 : id.startsWith("diamond") ? 5 : id.startsWith("iron") ? 4
                    : id.startsWith("copper") || id.startsWith("stone") ? 3 : id.startsWith("golden") ? 2 : 1;
            int score = kind + tier * 5;
            if (score > bestScore) {
                bestScore = score;
                best = i;
            }
        }
        return best;
    }

    /** Takes the best weapon in hand before a swing - it remembers to fight with the sword. */
    private void wieldWeapon() {
        int slot = bestWeaponSlot();
        if (slot >= 0 && mc.player.getInventory().selectedSlot != slot) InventoryUtility.switchTo(slot);
    }

    private void hit(boolean crit) {
        snap(target);
        wieldWeapon();
        mc.interactionManager.attackEntity(mc.player, target);
        mc.player.swingHand(Hand.MAIN_HAND);
        // the damage is scored when it arrives, not now: see resolveHit
        pendingState = lastState;
        pendingAction = lastAction;
        pendingTarget = target;
        pendingHealth = target.getHealth();
        pendingCrit = crit;
        pendingAt = System.currentTimeMillis();
    }

    /**
     * Scores the swing whose damage has just shown up (or whose target has just died), whenever that happens.
     *
     * <p>This is what makes the agent work on a server: the mob's health is only updated once the server's answer
     * comes back, which can be several decisions after the swing. Crediting the damage to whatever move happened
     * to be active at the time would teach the wrong move - so the swing itself carries the score.
     */
    private void resolveHit(int stateNow) {
        if (pendingState < 0) return;
        LivingEntity t = pendingTarget;

        // the mob is gone from the world without dying: it despawned or its chunk unloaded. Nobody killed it,
        // so this swing is simply forgotten - the old code would have counted a +10 for the walk-away.
        if (t == null || (t.isRemoved() && t.isAlive())) {
            clearPending();
            return;
        }

        if (t.isAlive()) {
            float dealt = pendingHealth - t.getHealth();
            if (dealt <= 0f) {
                // nothing yet - give it a ping or two, then let it go rather than credit it to a later move
                if (System.currentTimeMillis() - pendingAt > 2500L) clearPending();
                return;
            }
            int s = pendingState;
            Action a = pendingAction;
            boolean crit = pendingCrit;
            clearPending();
            if (crit) crits++;
            float reward = dealt + (crit ? 1.5f : 0f);
            update(s, a.ordinal(), reward, stateNow);
            record(reward);
            return;
        }

        int s = pendingState;
        Action a = pendingAction;
        clearPending();
        kills++;
        update(s, a.ordinal(), 10f, -1);
        record(10f);
        if (chatty.getValue() && kills % 10 == 0)
            reply(String.format(Locale.ROOT, "%d pokonanych! Średnia nagroda %.2f - %s.", kills, avgReward,
                    avgReward > 0.5f ? "idzie mi coraz lepiej" : "dalej się uczę"));
    }

    private void clearPending() {
        pendingState = -1;
        pendingTarget = null;
    }

    /** Whether a step that way is safe: solid ground within two blocks below, no lava or fire. */
    private boolean safe(double dx, double dz) {
        BlockPos ahead = BlockPos.ofFloored(mc.player.getX() + dx * 0.9, mc.player.getY(), mc.player.getZ() + dz * 0.9);
        for (int down = 0; down <= 2; down++) {
            BlockState s = mc.world.getBlockState(ahead.down(down));
            if (s.isOf(Blocks.LAVA) || s.isOf(Blocks.FIRE) || s.isOf(Blocks.MAGMA_BLOCK) || s.isOf(Blocks.CACTUS)) return false;
            if (down > 0 && !s.isAir() && s.getFluidState().isEmpty()) return true;
        }
        return false;
    }

    /* ------------------------------------------------------------------ */
    /* Eating                                                              */
    /* ------------------------------------------------------------------ */

    private int foodSlot() {
        for (int i = 0; i < 9; i++)
            if (mc.player.getInventory().getStack(i).getComponents().contains(DataComponentTypes.FOOD)) return i;
        return -1;
    }

    private boolean canEat() {
        return autoEat.getValue() && foodSlot() >= 0 && mc.player.getHungerManager().isNotFull() && mc.player.getHealth() < 14f;
    }

    private void startEating() {
        int slot = foodSlot();
        if (slot < 0) return;
        if (previousSlot < 0) previousSlot = mc.player.getInventory().selectedSlot;
        InventoryUtility.switchTo(slot);
    }

    /** Keeps the use key down while there is something to eat; switches back to the weapon afterwards. */
    private boolean eating() {
        if (foodSlot() >= 0 && mc.player.getHungerManager().isNotFull()) return true;
        if (previousSlot >= 0) {
            InventoryUtility.switchTo(previousSlot);
            previousSlot = -1;
        }
        return false;
    }

    /* ------------------------------------------------------------------ */
    /* Keys                                                                */
    /* ------------------------------------------------------------------ */

    private static void set(KeyBinding key, boolean down) {
        key.setPressed(down);
    }

    /**
     * Lets go of the movement keys - but only if the agent is the one holding them. Called every idle tick, it
     * used to force them up every tick, which froze the player in place whenever the agent was not fighting.
     */
    private void releaseMovement() {
        if (mc.options == null || !movementHeld) return;
        movementHeld = false;
        set(mc.options.forwardKey, false);
        set(mc.options.backKey, false);
        set(mc.options.leftKey, false);
        set(mc.options.rightKey, false);
        set(mc.options.jumpKey, false);
        set(mc.options.sprintKey, false);
    }

    private void releaseKeys() {
        if (mc.options == null) return;
        releaseMovement();
        holdUse = false;
        set(mc.options.useKey, false);
        if (previousSlot >= 0 && mc.player != null) InventoryUtility.switchTo(previousSlot);
        previousSlot = -1;
    }

    /* ------------------------------------------------------------------ */
    /* Panel                                                               */
    /* ------------------------------------------------------------------ */

    @Override
    public void onRender2D(DrawContext context) {
        if (!panel.getValue() || fullNullCheck()) return;
        // only while it is actually doing something it was told to - idle, the screen stays clean
        if (mode != Mode.Fight && !tasks.active() && !tasks.recording()) return;
        var font = FontRenderers.sf_medium_mini;
        boolean fighting = mode == Mode.Fight && target != null;
        String[] lines = {
                "LearnAI  ·  " + (mode == Mode.Fight ? "walka" : "czeka") + (holdUse ? "  ·  prawy" : "")
                        + (mc.isInSingleplayer() ? "" : "  ·  serwer"),
                "teraz: " + (fighting ? lastAction.label : "-"),
                String.format(Locale.ROOT, "pokonane %d  ·  kryty %d  ·  śmierci %d", kills, crits, deaths),
                String.format(Locale.ROOT, "decyzje %d  ·  ciekawość %d%%", steps, Math.round(epsilon() * 100))
        };
        float w = 150f;
        for (String l : lines) w = Math.max(w, font.getStringWidth(l) + 18f);
        float graphH = 26f, barsH = fighting && lastState >= 0 ? 34f : 0f;
        float h = lines.length * 10f + 12f + graphH + barsH;
        float x = 6f, y = mc.getWindow().getScaledHeight() / 2f - h / 2f;
        Color accent = HudEditor.getColor(0);

        Render2DEngine.drawRoundSmooth(context, x, y, w, h, 7f, new Color(12, 12, 18, 195));
        Render2DEngine.drawRoundSmooth(context, x, y + 5f, 2f, h - 10f, 1f, accent);
        for (int i = 0; i < lines.length; i++)
            font.drawString(context, lines[i], x + 9f, y + 7f + i * 10f, i == 0 ? Color.WHITE.getRGB() : new Color(205, 205, 215).getRGB());

        // learning curve: average reward per 100 decisions - rising means it is getting better
        float gy = y + 10f + lines.length * 10f, gx = x + 9f, gw = w - 18f;
        Render2DEngine.drawRoundSmooth(context, gx, gy, gw, graphH - 4f, 3f, new Color(255, 255, 255, 12));
        if (curveLen >= 2) {
            float lo = Float.MAX_VALUE, hi = -Float.MAX_VALUE;
            for (int i = 0; i < curveLen; i++) {
                lo = Math.min(lo, curve[i]);
                hi = Math.max(hi, curve[i]);
            }
            float span = Math.max(0.05f, hi - lo);
            for (int i = 1; i < curveLen; i++) {
                float x0 = gx + 2f + (gw - 4f) * (i - 1) / (curve.length - 1f), x1 = gx + 2f + (gw - 4f) * i / (curve.length - 1f);
                float y0 = gy + graphH - 7f - (curve[i - 1] - lo) / span * (graphH - 10f);
                float y1 = gy + graphH - 7f - (curve[i] - lo) / span * (graphH - 10f);
                Render2DEngine.drawThickLine(context, x0, y0, x1, y1, accent.getRGB(), 1.4f);
            }
        } else {
            font.drawString(context, "krzywa nauki pojawi się po 200 decyzjach", gx + 4f, gy + 8f, new Color(150, 150, 160).getRGB());
        }

        // what it thinks of each move right now: the top three, as bars
        if (barsH > 0f) {
            float by = gy + graphH;
            float[] row = q[lastState];
            Integer[] order = new Integer[ACTIONS.length];
            for (int i = 0; i < order.length; i++) order[i] = i;
            java.util.Arrays.sort(order, (a, b) -> Float.compare(row[b], row[a]));
            float top = Math.max(0.01f, Math.abs(row[order[0]]));
            for (int k = 0; k < 3; k++) {
                int i = order[k];
                float frac = Math.clamp(Math.abs(row[i]) / top, 0.05f, 1f);
                font.drawString(context, ACTIONS[i].label, gx, by + k * 10f + 1f, new Color(190, 190, 200).getRGB());
                float bx = gx + gw * 0.55f, bw = gw * 0.45f;
                Render2DEngine.drawRoundSmooth(context, bx, by + k * 10f + 2f, bw, 4f, 2f, new Color(255, 255, 255, 18));
                Render2DEngine.drawRoundSmooth(context, bx, by + k * 10f + 2f, bw * frac, 4f, 2f,
                        row[i] >= 0 ? accent : new Color(220, 90, 80));
            }
        }
    }

    /* ------------------------------------------------------------------ */
    /* Memory on disk                                                      */
    /* ------------------------------------------------------------------ */

    private static File file() {
        return new File(ConfigManager.MISC_FOLDER, "learnai_v3.txt");
    }

    /** Whether the knowledge was read from disk this session - never write an empty table over the real one. */
    private boolean loaded;

    private void save() {
        if (!loaded) return;
        lastSave = System.currentTimeMillis();
        try {
            StringBuilder sb = new StringBuilder();
            sb.append(steps).append(' ').append(kills).append(' ').append(deaths).append(' ').append(crits).append(' ').append(avgReward).append('\n');
            for (int s = 0; s < STATES; s++) {
                for (int a = 0; a < ACTIONS.length; a++)
                    sb.append(a == 0 ? "" : " ").append(q[s][a]).append(':').append(visits[s][a]);
                sb.append('\n');
            }
            Files.createDirectories(file().getParentFile().toPath());
            Files.writeString(file().toPath(), sb.toString(), StandardCharsets.UTF_8);
            saveCurve();
        } catch (Exception ignored) {
        }
    }

    private void load() {
        if (loaded) return;
        loaded = true;
        loadCurrent();
        migrateOld();
        loadCurve();
    }

    /**
     * Folds the older knowledge file (learnai_v2, from before the starting knowledge) into this one, so nothing
     * learned is lost: the counters add up, and for every situation the two tables are averaged by how often each
     * tried it. The old file is then renamed so it is never counted twice.
     */
    private void migrateOld() {
        File old = new File(ConfigManager.MISC_FOLDER, "learnai_v2.txt");
        if (!old.exists()) return;
        try {
            String[] rows = Files.readString(old.toPath(), StandardCharsets.UTF_8).split("\n");
            String[] head = rows[0].trim().split(" ");
            steps += Long.parseLong(head[0]);
            kills += Integer.parseInt(head[1]);
            deaths += Integer.parseInt(head[2]);
            crits += Integer.parseInt(head[3]);
            for (int s = 0; s < STATES && s + 1 < rows.length; s++) {
                String[] v = rows[s + 1].trim().split(" ");
                for (int a = 0; a < ACTIONS.length && a < v.length; a++) {
                    String[] pv = v[a].split(":");
                    float oq = Float.parseFloat(pv[0]);
                    int ov = pv.length > 1 ? Integer.parseInt(pv[1]) : 0;
                    if (ov <= 0) continue;
                    int nv = visits[s][a];
                    q[s][a] = (q[s][a] * nv + oq * ov) / (nv + ov);
                    visits[s][a] = nv + ov;
                }
            }
            Files.move(old.toPath(), new File(ConfigManager.MISC_FOLDER, "learnai_v2.migrated").toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            save();
            reply("Wczytałem też moją starszą naukę - wszystko połączone, nic nie przepadło.");
        } catch (Exception ignored) {
        }
    }

    /* the learning curve on the panel, kept across sessions too */
    private static File curveFile() {
        return new File(ConfigManager.MISC_FOLDER, "learnai_curve.txt");
    }

    private void saveCurve() {
        try {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < curveLen; i++) sb.append(i == 0 ? "" : " ").append(curve[i]);
            Files.writeString(curveFile().toPath(), sb.toString(), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }

    private void loadCurve() {
        try {
            if (!curveFile().exists()) return;
            String[] v = Files.readString(curveFile().toPath(), StandardCharsets.UTF_8).trim().split(" ");
            curveLen = 0;
            for (String s : v) {
                if (s.isBlank() || curveLen >= curve.length) continue;
                curve[curveLen++] = Float.parseFloat(s);
            }
        } catch (Exception ignored) {
        }
    }

    private void loadCurrent() {
        try {
            if (!file().exists()) {
                prime();
                return;
            }
            String[] rows = Files.readString(file().toPath(), StandardCharsets.UTF_8).split("\n");
            String[] head = rows[0].trim().split(" ");
            steps = Long.parseLong(head[0]);
            kills = Integer.parseInt(head[1]);
            deaths = Integer.parseInt(head[2]);
            crits = Integer.parseInt(head[3]);
            avgReward = Float.parseFloat(head[4]);
            for (int s = 0; s < STATES && s + 1 < rows.length; s++) {
                String[] v = rows[s + 1].trim().split(" ");
                for (int a = 0; a < ACTIONS.length && a < v.length; a++) {
                    String[] pv = v[a].split(":");
                    q[s][a] = Float.parseFloat(pv[0]);
                    visits[s][a] = pv.length > 1 ? Integer.parseInt(pv[1]) : 0;
                }
            }
        } catch (Exception e) {
            q = new float[STATES][ACTIONS.length];
            visits = new int[STATES][ACTIONS.length];
        }
    }
}
