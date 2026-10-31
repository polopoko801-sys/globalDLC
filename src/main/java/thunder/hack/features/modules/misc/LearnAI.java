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
    }

    private final Setting<Boolean> panel = new Setting<>("Panel", true);
    /** How fast new experience overwrites old: higher learns quicker but forgets quicker. */
    private final Setting<Float> learnRate = new Setting<>("LearnRate", 0.15f, 0.02f, 0.8f);
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
    private static final int DECIDE_TICKS = 4;

    /* ------------------------------------------------------------------ */
    /* Replay memory                                                       */
    /* ------------------------------------------------------------------ */

    private static final int MEMORY = 4000, REPLAY = 24;
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

    /** A chat line addressed to the agent (the part after "ai "). */
    public void onChat(String raw) {
        log(true, raw.strip());
        if (!isEnabled()) enable();   // the first order wakes it up - there is no switch to flip
        if (!allowed()) {
            reply("Na serwerach mam wyłączone - włącz opcję \"Servers\" w ustawieniach modułu.");
            return;
        }
        String msg = raw.toLowerCase(Locale.ROOT).strip()
                .replace('ą', 'a').replace('ę', 'e').replace('ś', 's').replace('ć', 'c')
                .replace('ł', 'l').replace('ó', 'o').replace('ż', 'z').replace('ź', 'z').replace('ń', 'n');
        if (msg.matches(".*(walcz|bij|atak|fight|zabij|polu).*")) {
            mode = Mode.Fight;
            reply(steps < 2000 ? "Walczę! Na początku będę się mylił - uczę się na błędach." : "Walczę. Zobaczymy, ile się nauczyłem.");
        } else if (msg.matches(".*(stop|stoj|przestan|koniec|spokoj).*")) {
            mode = Mode.Idle;
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
        } else if (msg.matches(".*(czego|nauczyl|wiesz|strategi|jak walczysz).*")) {
            explain();
        } else if (msg.matches(".*(reset|zapomnij|od nowa).*")) {
            q = new float[STATES][ACTIONS.length];
            visits = new int[STATES][ACTIONS.length];
            steps = kills = deaths = crits = 0;
            avgReward = 0f;
            memCount = memHead = 0;
            curveLen = 0;
            reply("Zapomniałem wszystko. Uczę się od zera.");
        } else {
            reply("Komendy: \"ai walcz\", \"ai stop\", \"ai klikaj prawym\", \"ai ucz sie\", \"ai statystyki\", \"ai czego sie nauczyles\", \"ai serwery\", \"ai reset\".");
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
        // a server the user did not allow: hands off the keys, no fighting
        if (!allowed()) {
            if (mode == Mode.Fight) {
                mode = Mode.Idle;
                releaseKeys();
            }
            return;
        }
        mc.options.useKey.setPressed(holdUse || (lastAction == Action.Eat && eating()));

        if (System.currentTimeMillis() - lastSave > 60_000L) save();

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
            return;
        }

        target = nearestMonster();
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
            int k = random.nextInt(memCount);
            update(memS[k], memA[k], memR[k], memNext[k]);
        }
    }

    private void update(int s, int a, float r, int next) {
        float future = next < 0 ? 0f : max(q[next]);
        q[s][a] += learnRate.getValue() * (r + GAMMA * future - q[s][a]);
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
    private Action choose(int state) {
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
            // monsters only: players are never a target
            if (!(e instanceof Monster) || !(e instanceof LivingEntity living) || !living.isAlive()) continue;
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

    private void hit(boolean crit) {
        snap(target);
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

    private void releaseMovement() {
        if (mc.options == null) return;
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
        return new File(ConfigManager.MISC_FOLDER, "learnai_v2.txt");
    }

    private void save() {
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
        } catch (Exception ignored) {
        }
    }

    private void load() {
        try {
            if (!file().exists()) return;
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
