package thunder.hack.features.modules.movement;

import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInteractBlockC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInteractEntityC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInteractItemC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.network.packet.s2c.play.EntityVelocityUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.ExplosionS2CPacket;
import net.minecraft.network.packet.s2c.play.GameJoinS2CPacket;
import net.minecraft.network.packet.s2c.play.PlayerPositionLookS2CPacket;
import net.minecraft.network.packet.s2c.play.PlayerRespawnS2CPacket;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import thunder.hack.events.impl.EventTick;
import thunder.hack.events.impl.PacketEvent;
import thunder.hack.features.modules.Module;
import thunder.hack.features.modules.client.HudEditor;
import thunder.hack.features.modules.movement.fakelag.LagSimulator;
import thunder.hack.gui.font.FontRenderers;
import thunder.hack.setting.Setting;
import thunder.hack.utility.Timer;
import thunder.hack.utility.render.Render2DEngine;
import thunder.hack.utility.render.Render3DEngine;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static thunder.hack.ThunderHack.LOGGER;

/**
 * Two modes, one module: a real rolling packet delay, and a local simulation of one that touches nothing.
 *
 * <h2>Live</h2>
 * Your movement packets are parked for {@code Delay} ms and released at the normal rate, so the server - and
 * therefore every other player - always works with where you WERE. Your own client is unaffected: you move,
 * see and fight with zero delay. Only the world's picture of you is behind. Only movement packets are held;
 * attacks, item use and inventory clicks go out immediately.
 *
 * <h2>Test</h2>
 * The same delay, applied to a copy of your position instead of to the network. Nothing is held, cancelled or
 * sent; {@link LagSimulator} parks a sample and hands it back later, and the ghost box plus the overlay are
 * drawn from that. It is how you pick a Delay and see what it costs before any server sees it, and it is safe
 * on singleplayer, where Live is not.
 *
 * <h2>Why Live used to pull you back, and what changed</h2>
 * The burst was the problem. Every held packet used to go out in one go, and the server handles all of them
 * inside a single tick - where {@code lastTickX/Y/Z} does not move between them. So every packet in the burst
 * measures its distance from the SAME starting point, and those distances add up: the last packet of a 400 ms
 * burst claims eight ticks of walking at once, a 2000 ms burst claims forty. Vanilla allows 100 squared - ten
 * blocks - from the tick start, and a sprint or an elytra sails straight past it, at which point
 * {@code ServerPlayNetworkHandler} logs "moved too quickly" and teleports you back.
 *
 * <p>So the queue is no longer emptied in bursts. Every packet carries the time it was taken and leaves once
 * it is {@code Delay} old, one or two per tick, and when the server genuinely needs to know where you are
 * right now - knockback, an explosion, your own attack - the queue is COLLAPSED instead of replayed:
 * everything stale is thrown away and one packet with your current position goes out. One packet resyncs
 * instantly and cannot trip a per-tick distance check; replaying thirty of them is what tripped it.
 *
 * <p>Nothing is ever dropped in Live mode: over the cap the queue drains faster instead. A drop leaves a hole
 * the next packet has to jump across, and that jump is itself what earns a setback. (Test mode does drop and
 * counts them - there a drop is a measurement, not a desync.)
 *
 * <h2>Threading</h2>
 * {@code PacketEvent.Receive} is posted from {@code ClientConnection.handlePacket}, which runs on the netty IO
 * thread - before vanilla bounces anything to the main thread. So the receive handler shares no mutable state
 * with the tick except through {@link #LOCK} and two atomics, and it never sends: it records what needs doing
 * and the next tick does it, on the main thread. One tick of latency is nothing against a delay measured in
 * hundreds of milliseconds, and it keeps every packet send and every render read on the thread that owns them.
 */
public final class FakeLag extends Module {

    public enum Mode {
        /**
         * Really hold the packets. Needs a real server, and is the mode that can earn a setback if the
         * numbers are wrong - which is what Test exists to let you find out first.
         */
        Live,
        /**
         * Simulate the delay locally. Touches no packet at all, works on singleplayer, cannot be detected
         * because there is nothing to detect.
         */
        Test,
        /**
         * MoonWare / NeverHook / DreamCore "Fake Lags": every movement packet is held and the whole queue goes
         * out at once every {@code FreezeMs} - to everyone else (and the ghost) you freeze and then jump.
         */
        Pulse,
        /**
         * Augustus 2.6 FakeLag "Combat": lag only kicks in when the aura's target stands in the sweet spot
         * (2.9-3.3 blocks from your eyes, and you are closer than the server thinks), holds up to
         * {@code LagPackets} movement packets and lets them all out the moment you hit.
         */
        Combat,
        /**
         * LiquidBounce FakeLag "Dynamic": every packet is held (each for a random DelayMs..MaxDelayMs) only
         * while an enemy is within MinRange..MaxRange AND the server still has you farther from it than you
         * really are - i.e. while you are closing in. The enemy sees you late, you get the first hit. Anything
         * in FlushOn releases the queue at once, then RecoilMs has to pass before lagging again.
         */
        Dynamic
    }

    public enum GhostStyle {Box, Player}

    private final Setting<Mode> mode = new Setting<>("Mode", Mode.Dynamic);
    /** Pulse: how long you stay frozen for everyone else before the whole queue goes out and you jump. */
    private final Setting<Integer> freezeMs = new Setting<>("FreezeMs", 1000, 50, 3000, v -> mode.getValue() == Mode.Pulse);
    private final Setting<Integer> lagPackets = new Setting<>("LagPackets", 10, 1, 50, v -> mode.getValue() == Mode.Combat);
    /** Distance window (eyes to the target's hitbox) in which Combat starts lagging - Augustus used 2.9..3.3. */
    private final Setting<Float> combatMin = new Setting<>("CombatMin", 2.9f, 1f, 6f, v -> mode.getValue() == Mode.Combat);
    private final Setting<Float> combatMax = new Setting<>("CombatMax", 3.3f, 1f, 6f, v -> mode.getValue() == Mode.Combat);
    private final Setting<GhostStyle> ghostStyle = new Setting<>("Ghost", GhostStyle.Box);

    /** How far behind the server sees you (Live), or how long a sample is held (Test). */
    private final Setting<Integer> delay = new Setting<>("DelayMs", 400, 0, 2000);
    /** Dynamic: upper end of the random per-packet delay (LB default 300..600). */
    private final Setting<Integer> maxDelay = new Setting<>("MaxDelayMs", 600, 0, 2000, v -> mode.getValue() == Mode.Dynamic);
    private final Setting<Float> minRange = new Setting<>("MinRange", 2.0f, 0f, 8f, v -> mode.getValue() == Mode.Dynamic);
    private final Setting<Float> maxRange = new Setting<>("MaxRange", 5.0f, 1f, 10f, v -> mode.getValue() == Mode.Dynamic);
    /** Dynamic: how long after a flush before lagging may start again. */
    private final Setting<Integer> recoil = new Setting<>("RecoilMs", 250, 0, 2000, v -> mode.getValue() == Mode.Dynamic);
    private final Setting<Boolean> flushOnAttack = new Setting<>("FlushOnAttack", true, v -> mode.getValue() == Mode.Dynamic);
    private final Setting<Boolean> flushOnBlock = new Setting<>("FlushOnBlock", true, v -> mode.getValue() == Mode.Dynamic);
    /** After a hit nothing is held for this long, so the swing / aim that go with it are not delayed. */
    private final Setting<Integer> attackGrace = new Setting<>("AttackGrace", 300, 0, 1000, v -> mode.getValue() != Mode.Test);
    private final Setting<Boolean> flushOnAction = new Setting<>("FlushOnAction", true, v -> mode.getValue() == Mode.Dynamic);
    /**
     * How often the simulation lets a batch out, in ms. 0 releases every tick, which is the smooth case; a
     * larger value is what a burst actually looks like, and seeing it is the point.
     */
    private final Setting<Integer> burstInterval = new Setting<>("BurstInterval", 0, 0, 2000,
            v -> mode.getValue() == Mode.Test);
    /** Cap on how many entries may be held. */
    private final Setting<Integer> queueLimit = new Setting<>("QueueLimit", 60, 5, 200);
    /** Master switch for the simulation, so Test mode can be left on with the pipeline paused. */
    private final Setting<Boolean> simulationEnabled = new Setting<>("SimulationEnabled", true,
            v -> mode.getValue() == Mode.Test);

    /**
     * Stop holding packets while the aura has a target.
     *
     * <p>On by default: a combat module that misses is worse than a lag trick that pauses for the length of a
     * fight. Live only - Test holds nothing, so there is nothing for it to spoil.
     */
    private final Setting<Boolean> pauseOnAura = new Setting<>("PauseOnAura", true,
            v -> mode.getValue() == Mode.Live || mode.getValue() == Mode.Pulse || mode.getValue() == Mode.Dynamic);

    /** Box on the position the server still believes you are standing on (Live) or the test state (Test). */
    private final Setting<Boolean> render = new Setting<>("Render", true);
    private final Setting<Boolean> debugOverlay = new Setting<>("DebugOverlay", false);
    private final Setting<Boolean> statistics = new Setting<>("Statistics", true,
            v -> debugOverlay.getValue());
    private final Setting<Float> overlayX = new Setting<>("OverlayX", 0.01f, 0f, 1f, v -> debugOverlay.getValue());
    private final Setting<Float> overlayY = new Setting<>("OverlayY", 0.35f, 0f, 1f, v -> debugOverlay.getValue());

    /* ------------------------------------------------------------------ */
    /* Live state                                                          */
    /* ------------------------------------------------------------------ */

    /** One held packet and the moment it was taken, so the delay is measured per packet, not globally. */
    private record Held(Packet<?> packet, long queuedAt) {
    }

    /**
     * Guards {@link #queue} and {@link #serverPos}.
     *
     * <p>Needed because the receive handler runs on the netty thread while the tick and the send handler run
     * on the main one - an unsynchronised ArrayList between those two is a ConcurrentModificationException or
     * a torn read waiting for a busy server. Nothing that blocks happens inside it: packets to send are
     * collected under the lock and sent after it is released, so a network stall can never hold up a tick.
     */
    private final Object LOCK = new Object();

    private final List<Held> queue = new ArrayList<>();
    private Vec3d serverPos = Vec3d.ZERO;

    /** Set from the netty thread, acted on by the next tick - see the threading note on the class. */
    private final AtomicBoolean resyncRequested = new AtomicBoolean();
    private final AtomicBoolean teleported = new AtomicBoolean();

    /** Dynamic: no lagging before this moment (RecoilMs after a flush), and the current per-packet delay. */
    private long recoilUntil;
    private long dynDelay;
    /** When the last attack went out - nothing is held for AttackGrace ms after it. */
    private long lastAttackAt;
    /** True while we are the ones sending, so a release does not walk straight back into the queue. */
    private boolean releasing;

    /* ------------------------------------------------------------------ */
    /* Test state                                                          */
    /* ------------------------------------------------------------------ */

    private final LagSimulator simulator = new LagSimulator();
    /** Whether the simulation queue has been announced, so the log is not written every tick. */
    private boolean queueAnnounced;

    /* ------------------------------------------------------------------ */
    /* Shared statistics (Live)                                            */
    /* ------------------------------------------------------------------ */

    private long liveReleased;
    private int livePeak;

    public FakeLag() {
        super("FakeLag", Category.MOVEMENT);
        LOGGER.info("[Cloudan] FakeLag initialized");
    }

    @Override
    public String getDisplayInfo() {
        int held = mode.getValue() == Mode.Test ? simulator.queued() : heldCount();
        return mode.getValue().name() + " " + delay.getValue() + "ms " + held;
    }

    private int heldCount() {
        synchronized (LOCK) {
            return queue.size();
        }
    }

    /* ------------------------------------------------------------------ */
    /* Lifecycle                                                           */
    /* ------------------------------------------------------------------ */

    @Override
    public void onEnable() {
        if (fullNullCheck()) {
            disable();
            return;
        }

        // Live holds your own packets back from a real server. Against your own integrated server that does
        // nothing but desync you from yourself - but Test is pure local simulation, so it is fine there and
        // is in fact the sensible place to try your numbers out.
        if (mode.getValue() != Mode.Test && mc.isIntegratedServerRunning()) {
            disable("Live nie dziala na singlu - wejdz na serwer albo przelacz Mode na Test.");
            return;
        }

        clearAll("enabled");
        serverPos = mc.player.getEntityPos();

        LOGGER.info("[Cloudan] FakeLag enabled (mode={}, delay={}ms)", mode.getValue(), delay.getValue());
    }

    @Override
    public void onDisable() {
        // Live / Pulse / Combat: flush what is held, so the server is left with your real position rather than
        // a stale one.
        if (mode.getValue() != Mode.Test) flushAll();
        removeGhost();

        // Unconditional, and after the flush: this is the answer to "does any state survive a disable".
        // Module.disable() skips onDisable() entirely when the world is already gone, which is exactly the
        // disconnect case - so onLogout() below clears as well and neither path can leave a queue behind.
        clearAll("disabled");

        LOGGER.info("[Cloudan] FakeLag disabled");
    }

    /**
     * World teardown: disconnect, reconnect, quitting to the menu, changing dimension or server.
     *
     * <p>This hook used to be dead across the entire client - nothing ever called
     * {@code ModuleManager.onLogout()} - so a queue full of positions from the previous server survived into
     * the next one. It is dispatched from the client tick now (see {@code ModuleManager.watchWorld}).
     */
    @Override
    public void onLogout() {
        clearAll("world changed");
        LOGGER.info("[Cloudan] World changed - resetting FakeLag");
    }

    @Override
    public void onUnload() {
        clearAll("client closing");
    }

    /**
     * Empties both pipelines and both sets of counters.
     *
     * @param reason logged once per event, never per tick - a reset happens on a handful of discrete
     *               occasions and each one is worth a line; a line every tick would bury all of them.
     */
    private void clearAll(String reason) {
        boolean hadSomething;
        synchronized (LOCK) {
            hadSomething = !queue.isEmpty();
            queue.clear();
        }

        boolean hadSimulation = !simulator.empty();
        simulator.reset();

        resyncRequested.set(false);
        combatBlocking = false;
        pulseTimer.reset();
        if (ghost != null && mc.world != null && ghost.getEntityWorld() == mc.world) removeGhost();
        ghost = null;
        teleported.set(false);
        releasing = false;
        queueAnnounced = false;
        liveReleased = 0L;
        livePeak = 0;
        recoilUntil = 0L;

        if (hadSomething || hadSimulation)
            LOGGER.info("[Cloudan] FakeLag simulation queue cleared ({})", reason);
    }

    /* ------------------------------------------------------------------ */
    /* Outgoing packets - Live only                                        */
    /* ------------------------------------------------------------------ */

    @EventHandler
    @SuppressWarnings("unused")
    public void onPacketSend(PacketEvent.Send event) {
        // Test mode never touches a packet. This early return is the whole guarantee, and it is first.
        if (mode.getValue() == Mode.Test) return;
        if (fullNullCheck() || releasing) return;
        // Protocol packets are never held. The server-switch acknowledgement (joining a duel through the proxy)
        // sat in the queue, the world change then cleared the queue, the proxy never got it and kicked you with
        // "Timed out". A dropped keep-alive answer does the same. Whatever is held goes out first, then this one.
        if (neverHold(event.getPacket())) {
            if (heldCount() > 0) flushAll();
            return;
        }
        handleSend(event);
        // whatever went out un-held is now where the server has you - Dynamic needs this to know when to start
        if (!event.isCancelled() && event.getPacket() instanceof PlayerMoveC2SPacket move && move.changesPosition())
            synchronized (LOCK) {
                serverPos = new Vec3d(move.getX(serverPos.x), move.getY(serverPos.y), move.getZ(serverPos.z));
            }
    }

    private void handleSend(PacketEvent.Send event) {
        Packet<?> hit = event.getPacket();
        long now = System.currentTimeMillis();

        // THE HIT, in every mode: everything held goes out first (order), then the attack itself - never queued.
        if (hit instanceof PlayerInteractEntityC2SPacket && (mode.getValue() != Mode.Dynamic || flushOnAttack.getValue())) {
            combatBlocking = false;
            if (heldCount() > 0) flushAll();
            lastAttackAt = now;
            pulseTimer.reset();
            if (mode.getValue() == Mode.Dynamic) recoilUntil = now + recoil.getValue();
            return;
        }
        // ...and for AttackGrace ms after it nothing is held either: the swing, the sprint reset and the aim that
        // belong to the hit used to sit in the queue for DelayMs, so everyone saw the hit ~0.5 s late.
        if (now - lastAttackAt < attackGrace.getValue() && heldCount() == 0) return;

        if (mode.getValue() == Mode.Dynamic) {
            Packet<?> p = event.getPacket();
            if ((flushOnBlock.getValue() && (p instanceof PlayerInteractBlockC2SPacket || p instanceof PlayerInteractItemC2SPacket))
                    || (flushOnAction.getValue() && p instanceof PlayerActionC2SPacket)) {
                // queue first, then this packet - the order the server would have seen without us
                if (heldCount() > 0) {
                    flushAll();
                    recoilUntil = System.currentTimeMillis() + recoil.getValue();
                }
                return;
            }
            // The aura has a target = it is in hit range: from here on nothing is held, so every hit, swing and
            // aim goes out the moment it happens. Dynamic lags you IN (approach unseen), then the fight is clean.
            if (pauseOnAura.getValue() && auraEngaged()) {
                if (heldCount() > 0) flushAll();
                return;
            }
            // LB nextgen: never lag while dead, in water, in a GUI or eating/drinking (the server-side
            // use-item timing would desync); the queue goes out instead
            if (!mc.player.isAlive() || mc.player.isTouchingWater() || mc.currentScreen != null || mc.player.isUsingItem()
                    || p instanceof net.minecraft.network.packet.c2s.common.ResourcePackStatusC2SPacket) {
                if (heldCount() > 0) {
                    flushAll();
                    recoilUntil = System.currentTimeMillis() + recoil.getValue();
                }
                return;
            }
            // once something is held everything after it must queue too, or the order breaks
            if (heldCount() > 0 || (System.currentTimeMillis() >= recoilUntil && dynamicShouldLag())) {
                if (heldCount() == 0) rollDynDelay();
                event.cancel();
                hold(p);
            }
            return;
        }

        if (mode.getValue() == Mode.Pulse) {
            // Flushed right here, not on the next tick: letting this packet through first would put it ahead
            // of everything already held - a PacketOrder flag.
            if (pauseOnAura.getValue() && auraEngaged()) {
                if (heldCount() > 0) flushAll();
                return;
            }
            // Vanadium-style: EVERY packet is held (pongs and tick-ends too) and the pulse sends them all in the
            // original order. Holding only movement or dropping some is what Grim flagged (PacketOrderO/TickTimer/
            // Simulation): with everything delayed together it just looks like a higher ping.
            event.cancel();
            hold(event.getPacket());
            return;
        }

        if (mode.getValue() == Mode.Combat) {
            // Once blocking, EVERYTHING is held (pongs too), not just movement - holding only moves while pongs
            // overtake them is the PacketOrder flag Pulse already learned about.
            if (!combatBlocking && event.getPacket() instanceof PlayerMoveC2SPacket && sweetSpot()) combatBlocking = true;
            if (combatBlocking || heldCount() > 0) {
                event.cancel();
                hold(event.getPacket());
            }
            return;
        }

        // While the aura has somebody, hold nothing back at all.
        //
        // Collapsing on the hit packet is not enough on its own: the aura also steers your rotation, and
        // those rotations ride on the movement packets this module is sitting on. The server ends up
        // receiving a burst of stale aim immediately before the attack, resolves the hit against the wrong
        // one, and the aura looks broken - it swings and nothing lands. Nothing is queued in the first place
        // while a fight is on, so there is no burst to get it wrong.
        if (pauseOnAura.getValue() && auraEngaged()) {
            if (heldCount() > 0) flushAll();
            return;
        }

        // All packets, not just movement - see Pulse above.
        event.cancel();
        hold(event.getPacket());
    }

    /* ------------------------------------------------------------------ */
    /* Incoming packets - NETTY THREAD                                     */
    /* ------------------------------------------------------------------ */

    /**
     * Runs on the netty IO thread. It records intent and returns; it never sends and never renders.
     *
     * <p>Resets on a respawn too, which is both a death and a dimension change - the one world transition
     * that does not replace the ClientWorld instance, so the global watcher cannot see it.
     */
    @EventHandler
    @SuppressWarnings("unused")
    public void onPacketReceive(PacketEvent.Receive event) {
        if (mode.getValue() == Mode.Test) return;
        if (mc.player == null) return;

        // A teleport (setback, pearl, portal, plugin tp) makes every queued position a lie: sending them says
        // you walked back to where you were just corrected from, the server answers with another correction,
        // and one honest teleport snowballs into permanent rubberbanding. Dropping them is safe - the client's
        // own next packet reports the post-teleport position anyway.
        // (The queue is no longer thrown away here: it holds pongs as well, and dropping those is a flag. The
        // next tick flushes it in order, the teleport confirm included.)
        if (event.getPacket() instanceof PlayerPositionLookS2CPacket) {
            teleported.set(true);
            return;
        }

        if (event.getPacket() instanceof PlayerRespawnS2CPacket || event.getPacket() instanceof GameJoinS2CPacket) {
            synchronized (LOCK) {
                queue.clear();
            }
            teleported.set(true);
            return;
        }

        // Knockback has to be applied from where you really are, or you get shoved from a stale spot and the
        // client and server disagree about where you land - which the server resolves by moving you back.
        // LB nextgen Dynamic: any health change (= you took damage) flushes
        if (mode.getValue() == Mode.Dynamic
                && event.getPacket() instanceof net.minecraft.network.packet.s2c.play.HealthUpdateS2CPacket) {
            requestResync();
            return;
        }

        if (event.getPacket() instanceof ExplosionS2CPacket) {
            requestResync();
            return;
        }

        if (event.getPacket() instanceof EntityVelocityUpdateS2CPacket velocity
                && velocity.getEntityId() == mc.player.getId())
            requestResync();
    }

    /** Thread safe from either side: the flag is read and cleared by the tick. */
    private void requestResync() {
        resyncRequested.set(true);
    }

    /* ------------------------------------------------------------------ */
    /* The tick - MAIN THREAD, where everything actually happens           */
    /* ------------------------------------------------------------------ */

    @EventHandler
    @SuppressWarnings("unused")
    public void onTick(EventTick event) {
        if (fullNullCheck()) return;

        if (mode.getValue() == Mode.Test) {
            // Switched to Test while Live/Pulse/Combat still held packets: Test never touches the queue, so they
            // would stay held for good and the server would see you frozen - exactly a real fake lag. Let them go.
            if (heldCount() > 0) flushAll();
            removeGhost();
            tickSimulation();
            return;
        }

        switch (mode.getValue()) {
            case Pulse -> tickPulse();
            case Combat -> tickCombat();
            case Dynamic -> tickDynamic();
            default -> tickLive();
        }
        updateGhost();
    }

    /* -- Pulse / Combat ------------------------------------------------- */

    private final Timer pulseTimer = new Timer();
    private boolean combatBlocking;

    private void hold(Packet<?> packet) {
        long now = System.currentTimeMillis();
        synchronized (LOCK) {
            queue.add(new Held(packet, now));
            if (queue.size() > livePeak) livePeak = queue.size();
        }
    }

    /** Sends the whole queue in order, as the originals did. */
    private void flushAll() {
        List<Packet<?>> out = new ArrayList<>();
        synchronized (LOCK) {
            for (Held h : queue) out.add(h.packet());
            queue.clear();
        }
        sendAll(out);
    }

    /** Teleports drop the queue; knockback/explosions flush it (the server must push you from your real spot). */
    private boolean handleIntents() {
        if (teleported.compareAndSet(true, false)) {
            combatBlocking = false;
            flushAll();
            serverPos = mc.player.getEntityPos();
        }
        if (resyncRequested.compareAndSet(true, false)) {
            combatBlocking = false;
            flushAll();
        }
        return mc.getNetworkHandler() != null;
    }

    private void tickPulse() {
        if (!handleIntents()) return;
        if (pulseTimer.passedMs(freezeMs.getValue())) {
            flushAll();
            pulseTimer.reset();
        }
    }

    private void tickCombat() {
        if (!handleIntents()) return;
        int moves = 0;
        long oldest = 0;
        synchronized (LOCK) {
            for (Held h : queue) if (h.packet() instanceof PlayerMoveC2SPacket) moves++;
            if (!queue.isEmpty()) oldest = System.currentTimeMillis() - queue.getFirst().queuedAt();
        }
        // DelayMs caps how long anything may sit, so a fight with no hit can never freeze you for good
        if (moves > lagPackets.getValue() || oldest > delay.getValue() || (combatBlocking && !auraEngaged())) {
            combatBlocking = false;
            flushAll();
        }
    }

    /* -- Dynamic (LiquidBounce) ---------------------------------------- */

    private void tickDynamic() {
        boolean had = heldCount() > 0;
        if (!handleIntents()) return;
        if (had && heldCount() == 0) recoilUntil = System.currentTimeMillis() + recoil.getValue();
        if (heldCount() == 0) return;
        long now = System.currentTimeMillis();
        if (!dynamicShouldLag()) {
            // stopped closing in (or the enemy left the range): everything goes, then recoil
            flushAll();
            recoilUntil = now + recoil.getValue();
            return;
        }
        List<Packet<?>> due = new ArrayList<>();
        synchronized (LOCK) {
            int over = queue.size() - queueLimit.getValue() * 3;
            while (!queue.isEmpty() && (over-- > 0 || now - queue.getFirst().queuedAt() >= dynDelay))
                due.add(queue.removeFirst().packet());
        }
        if (!due.isEmpty()) {
            sendAll(due);
            rollDynDelay();
        }
    }

    private void rollDynDelay() {
        int a = delay.getValue(), b = Math.max(a, maxDelay.getValue());
        dynDelay = a == b ? a : java.util.concurrent.ThreadLocalRandom.current().nextInt(a, b + 1);
    }

    /**
     * LB nextgen Dynamic: an enemy must be within MinRange..MaxRange. With nothing held yet that is enough to
     * start. While holding, keep going only as long as the server's (old) position is NOT closer to the nearest
     * enemy than your real one and no enemy's hitbox overlaps you there - i.e. stop once lagging would make
     * you look closer / hittable instead of farther.
     */
    private boolean dynamicShouldLag() {
        Vec3d me = mc.player.getEntityPos();
        Vec3d server;
        synchronized (LOCK) {
            server = serverPos;
        }
        boolean nearby = false;
        double clientDist = Double.MAX_VALUE, serverDist = Double.MAX_VALUE;
        Box serverBox = mc.player.getBoundingBox().offset(server.subtract(me));
        for (net.minecraft.entity.player.PlayerEntity p : mc.world.getPlayers()) {
            if (p == mc.player || p.getId() == GHOST_ID || !p.isAlive() || thunder.hack.core.Managers.FRIEND.isFriend(p)) continue;
            double d = mc.player.distanceTo(p);
            if (d >= minRange.getValue() && d <= maxRange.getValue()) nearby = true;
            Vec3d at = p.getEntityPos();
            if (server.distanceTo(at) <= maxRange.getValue()) {
                if (p.getBoundingBox().intersects(serverBox)) return false;
                serverDist = Math.min(serverDist, server.distanceTo(at));
                clientDist = Math.min(clientDist, me.distanceTo(at));
            }
        }
        if (!nearby) return false;
        if (heldCount() == 0) return true;
        if (serverDist == Double.MAX_VALUE) return false;
        return serverDist >= clientDist;
    }

    /**
     * Augustus' trigger: the target is a player who has not just been hit, your eyes are 2.9-3.3 blocks from
     * the nearest point of its hitbox, and closer than the eyes the server still has for you.
     */
    private boolean sweetSpot() {
        if (!auraEngaged() || !(thunder.hack.core.manager.client.ModuleManager.kilaura.getTarget() instanceof net.minecraft.entity.player.PlayerEntity player))
            return false;
        if (player.hurtTime >= 3) return false;
        Vec3d eyes = mc.player.getEyePos();
        Vec3d serverEyes;
        synchronized (LOCK) {
            serverEyes = serverPos.add(0, mc.player.getStandingEyeHeight(), 0);
        }
        Box bb = player.getBoundingBox();
        Vec3d hit = new Vec3d(
                net.minecraft.util.math.MathHelper.clamp(eyes.x, bb.minX, bb.maxX),
                net.minecraft.util.math.MathHelper.clamp(eyes.y, bb.minY, bb.maxY),
                net.minecraft.util.math.MathHelper.clamp(eyes.z, bb.minZ, bb.maxZ));
        double d = eyes.distanceTo(hit);
        return d > combatMin.getValue() && d < combatMax.getValue() && d < serverEyes.distanceTo(hit);
    }

    /* -- Ghost player (Debug 1.2.6) ------------------------------------- */

    public static final int GHOST_ID = -1337420;
    private net.minecraft.client.network.OtherClientPlayerEntity ghost;

    /** Debug's FakeLag spawned a copy of you where the server last saw you; same here, while packets are held. */
    private void updateGhost() {
        if (ghostStyle.getValue() != GhostStyle.Player || !render.getValue() || heldCount() == 0) {
            removeGhost();
            return;
        }
        Vec3d at;
        synchronized (LOCK) {
            at = serverPos;
        }
        if (ghost == null || ghost.getEntityWorld() != mc.world) {
            ghost = new net.minecraft.client.network.OtherClientPlayerEntity(mc.world, mc.player.getGameProfile());
            ghost.setId(GHOST_ID);
            for (net.minecraft.entity.EquipmentSlot slot : net.minecraft.entity.EquipmentSlot.values())
                ghost.equipStack(slot, mc.player.getEquippedStack(slot).copy());
            ghost.refreshPositionAndAngles(at.x, at.y, at.z, mc.player.getYaw(), mc.player.getPitch());
            mc.world.addEntity(ghost);
        }
        ghost.refreshPositionAndAngles(at.x, at.y, at.z, mc.player.getYaw(), mc.player.getPitch());
        ghost.setHeadYaw(mc.player.getHeadYaw());
        ghost.setBodyYaw(mc.player.getBodyYaw());
        ghost.setSneaking(mc.player.isSneaking());
    }

    private void removeGhost() {
        if (ghost != null && mc.world != null) mc.world.removeEntity(GHOST_ID, net.minecraft.entity.Entity.RemovalReason.DISCARDED);
        ghost = null;
    }

    /* -- Test ---------------------------------------------------------- */

    /**
     * LIVE STATE → TEST QUEUE → DELAY → RELEASE → TEST STATE, once per tick.
     *
     * <p>No packet is read, held, cancelled or sent anywhere in this method or anything it calls.
     */
    private void tickSimulation() {
        if (!simulationEnabled.getValue()) {
            if (!simulator.empty()) {
                simulator.reset();
                LOGGER.info("[Cloudan] FakeLag simulation queue cleared (simulation paused)");
                queueAnnounced = false;
            }
            return;
        }

        if (!queueAnnounced) {
            queueAnnounced = true;
            LOGGER.info("[Cloudan] FakeLag simulation queue created (delay={}ms, burst={}ms, limit={})",
                    delay.getValue(), burstInterval.getValue(), queueLimit.getValue());
        }

        Vec3d live = mc.player.getEntityPos();
        simulator.sample(live, mc.player.getYaw(), mc.player.getPitch(), queueLimit.getValue());
        simulator.step(delay.getValue(), burstInterval.getValue(), live);
    }

    /* -- Live ---------------------------------------------------------- */

    /**
     * Every packet that has been held long enough goes out, oldest first, a couple per tick.
     *
     * <p>The age is per packet rather than one clock for the whole queue. With a single clock any resync
     * restarted it, so after every hit the module went completely silent for another full Delay and then
     * caught up - which is the stutter you feel rather than a lag you chose.
     */
    private void tickLive() {
        // Intents recorded by the netty thread are acted on FIRST, before anything is released, so a
        // teleport or a knockback can never be followed by a stale packet going out in the same tick.
        // Nothing is collapsed or dropped any more - a setback or knockback just lets everything out in order.
        if (teleported.compareAndSet(true, false)) {
            flushAll();
            serverPos = mc.player.getEntityPos();
        }

        if (resyncRequested.compareAndSet(true, false)) flushAll();

        if (mc.getNetworkHandler() == null) {
            synchronized (LOCK) {
                queue.clear();
            }
            return;
        }

        long now = System.currentTimeMillis();
        long hold = delay.getValue();

        List<Packet<?>> due = new ArrayList<>();

        synchronized (LOCK) {
            if (queue.isEmpty()) return;

            // Over the cap the queue is drained FASTER, never trimmed. Throwing a movement packet away leaves
            // a hole the next one has to jump across, and a jump is exactly what earns a setback - the one
            // thing this module must not cause.
            // Every packet that is Delay old goes, in order, whatever its type - so pongs, tick-ends and moves
            // keep exactly the spacing they were sent with. Over the cap the oldest go early.
            int over = queue.size() - queueLimit.getValue() * 3;
            while (!queue.isEmpty() && (over-- > 0 || now - queue.getFirst().queuedAt() >= hold))
                due.add(queue.removeFirst().packet());
        }

        // Outside the lock: sending can block on the channel, and a tick must never wait on the network
        // while holding a lock the netty thread also wants.
        sendAll(due);
    }

    private void sendAll(List<Packet<?>> packets) {
        if (packets.isEmpty()) return;
        if (mc.getNetworkHandler() == null) return;

        releasing = true;
        try {
            for (Packet<?> packet : packets) {
                mc.getNetworkHandler().sendPacket(packet);
                liveReleased++;

                if (packet instanceof PlayerMoveC2SPacket move && move.changesPosition())
                    synchronized (LOCK) {
                        serverPos = new Vec3d(move.getX(serverPos.x), move.getY(serverPos.y), move.getZ(serverPos.z));
                    }
            }
        } finally {
            releasing = false;
        }
    }

    /**
     * Packets that must never sit in (or be dropped with) the queue: the proxy's server-switch handshake, the
     * keep-alive answer, respawn, chat/commands, resource pack and client settings. None of them move you, so
     * sending them on time changes nothing about the lag - holding them is what disconnects you.
     */
    private static boolean neverHold(Packet<?> p) {
        return p instanceof net.minecraft.network.packet.c2s.play.AcknowledgeReconfigurationC2SPacket
                || p instanceof net.minecraft.network.packet.c2s.common.KeepAliveC2SPacket
                || p instanceof net.minecraft.network.packet.c2s.play.ClientStatusC2SPacket
                || p instanceof net.minecraft.network.packet.c2s.play.ChatMessageC2SPacket
                || p instanceof net.minecraft.network.packet.c2s.play.CommandExecutionC2SPacket
                || p instanceof net.minecraft.network.packet.c2s.play.ChatCommandSignedC2SPacket
                || p instanceof net.minecraft.network.packet.c2s.common.ResourcePackStatusC2SPacket
                || p instanceof net.minecraft.network.packet.c2s.common.CustomPayloadC2SPacket
                || p instanceof net.minecraft.network.packet.c2s.common.CookieResponseC2SPacket
                || p instanceof net.minecraft.network.packet.c2s.common.ClientOptionsC2SPacket;
    }

    /** True while Kilaura is on and actually locked onto something. */
    private static boolean auraEngaged() {
        var kilaura = thunder.hack.core.manager.client.ModuleManager.kilaura;
        return kilaura.isEnabled() && kilaura.getTarget() != null;
    }

    /* ------------------------------------------------------------------ */
    /* Render - MAIN THREAD                                                */
    /* ------------------------------------------------------------------ */

    @Override
    public void onRender2D(DrawContext context) {
        if (mc.player == null || mc.world == null) return;

        if (render.getValue() && ghostStyle.getValue() == GhostStyle.Box) drawGhost(context);
        if (debugOverlay.getValue()) drawOverlay(context);
    }

    /**
     * A box the size of your own hitbox, standing where the delayed position is.
     *
     * <p>In Live that is where the server still thinks you are; in Test it is the released test state. Drawn
     * as a real projected cube, so it looks like a body standing there rather than a flat rectangle.
     */
    /** Ghost visuals: eased fade, a position that glides instead of stepping per packet, and the frame clock. */
    private float ghostFade;
    private Vec3d ghostShown;
    private long ghostFrame;

    private void drawGhost(DrawContext context) {
        long nowNs = System.nanoTime();
        float dt = ghostFrame == 0 ? 0.016f : Math.min(0.1f, (nowNs - ghostFrame) / 1e9f);
        ghostFrame = nowNs;

        Vec3d behind = null;
        long ageMs = 0;
        int held = 0;
        if (mode.getValue() == Mode.Test) {
            LagSimulator.Sample state = simulator.testState();
            if (state != null) behind = state.pos();
        } else {
            synchronized (LOCK) {
                held = queue.size();
                if (held > 0) {
                    behind = serverPos;
                    ageMs = System.currentTimeMillis() - queue.getFirst().queuedAt();
                }
            }
        }

        // hidden while it would sit inside you anyway - no flicker of a box glued to your own hitbox
        boolean visible = behind != null && behind.distanceTo(mc.player.getEntityPos()) > 0.05;
        if (visible) {
            float k = 1f - (float) Math.exp(-dt * 20f);
            ghostShown = ghostShown == null || ghostShown.distanceTo(behind) > 10 ? behind : ghostShown.lerp(behind, k);
        }
        ghostFade += ((visible ? 1f : 0f) - ghostFade) * (1f - (float) Math.exp(-dt * 12f));
        if (ghostFade < 0.02f || ghostShown == null) {
            if (!visible) ghostShown = null;
            return;
        }

        Color c = HudEditor.getColor(0);
        Color top = alpha(c, (int) (110 * ghostFade));
        Color bottom = alpha(c, (int) (25 * ghostFade));
        Color outline = alpha(c, (int) (235 * ghostFade));

        // Your real hitbox, moved onto the delayed position, so it shrinks when you sneak or swim too.
        Box ghost = mc.player.getBoundingBox().offset(ghostShown.subtract(mc.player.getEntityPos()));
        Render3DEngine.drawProjectedBox(context, ghost, top, bottom, outline, 1.6f, true, true);

        // tether from you to where the server still has you
        Vec3d from = mc.player.getBoundingBox().getCenter();
        Render3DEngine.drawGlowLine2D(context, from, ghost.getCenter(), alpha(c, (int) (120 * ghostFade)), 6f);

        // tag: how much is held and how far behind the server is
        Vec3d s = Render3DEngine.worldSpaceToScreenSpace(new Vec3d(ghost.getCenter().x, ghost.maxY + 0.3, ghost.getCenter().z));
        if (s.z > 0 && s.z < 1 && mode.getValue() != Mode.Test) {
            String text = held + " pkt  " + ageMs + "ms";
            var font = FontRenderers.sf_bold_mini;
            float w = font.getStringWidth(text) + 8f, h = 11f;
            float x = (float) s.x - w / 2f, y = (float) s.y - h;
            Render2DEngine.drawRound(context, x, y, w, h, 3f, alpha(new Color(14, 14, 20), (int) (200 * ghostFade)));
            float p = Math.min(1f, ageMs / (float) Math.max(1, mode.getValue() == Mode.Dynamic ? maxDelay.getValue() : delay.getValue()));
            Render2DEngine.drawRect(context, x + 2f, y + h - 2f, (w - 4f) * p, 1f, alpha(c, (int) (255 * ghostFade)));
            font.drawCenteredString(context, text, s.x, y + 2.5f, alpha(Color.WHITE, (int) (255 * ghostFade)).getRGB());
        }
    }

    private static Color alpha(Color c, int a) {
        return new Color(c.getRed(), c.getGreen(), c.getBlue(), Math.max(0, Math.min(255, a)));
    }

    /** The FAKELAG TEST panel. Reads published snapshots only - it never walks a live queue. */
    private void drawOverlay(DrawContext context) {
        boolean test = mode.getValue() == Mode.Test;
        LagSimulator.Stats stats = simulator.stats();

        List<String> lines = new ArrayList<>();

        if (test) {
            boolean active = simulationEnabled.getValue() && stats.active();
            lines.add("State: " + (active ? "ACTIVE" : "PAUSED"));
            lines.add("Simulated Delay: " + delay.getValue() + "ms");
            lines.add("Queue: " + stats.queued() + " / " + queueLimit.getValue());

            if (statistics.getValue()) {
                lines.add("Released: " + stats.released());
                lines.add("Dropped: " + stats.dropped());
                lines.add("Average Delay: " + stats.averageDelayMs() + "ms");
                lines.add("Last Delay: " + stats.lastDelayMs() + "ms");
                lines.add("Peak Queue: " + stats.peakQueue());
                lines.add(String.format("Behind: %.2f blocks", stats.behindBlocks()));
                if (burstInterval.getValue() > 0) lines.add("Burst: " + burstInterval.getValue() + "ms");
            }
        } else {
            int held = heldCount();
            lines.add("State: " + (held > 0 ? "HOLDING" : "IDLE"));
            lines.add("Target Delay: " + delay.getValue() + "ms");
            lines.add("Queue: " + held + " / " + queueLimit.getValue());

            if (statistics.getValue()) {
                lines.add("Released: " + liveReleased);
                lines.add("Dropped: 0 (never drops)");
                lines.add("Peak Queue: " + livePeak);
            }
        }

        var font = FontRenderers.sf_medium_mini;
        var titleFont = FontRenderers.sf_bold_mini;
        String title = test ? "FAKELAG TEST" : "FAKELAG LIVE";

        float width = titleFont.getStringWidth(title);
        for (String line : lines) width = Math.max(width, font.getStringWidth(line));

        float pad = 6f;
        float lineHeight = 10f;
        float panelWidth = width + pad * 2;
        float panelHeight = pad * 2 + 12f + lines.size() * lineHeight;

        float x = Math.clamp(overlayX.getValue() * mc.getWindow().getScaledWidth(),
                0f, mc.getWindow().getScaledWidth() - panelWidth);
        float y = Math.clamp(overlayY.getValue() * mc.getWindow().getScaledHeight(),
                0f, mc.getWindow().getScaledHeight() - panelHeight);

        Render2DEngine.drawRound(context, x, y, panelWidth, panelHeight, 5f, new Color(14, 14, 19, 225));

        Color accent = HudEditor.getColor(0);
        titleFont.drawString(context, title, x + pad, y + pad, accent.getRGB());

        float ly = y + pad + 13f;
        for (String line : lines) {
            // The counter that means something is wrong gets the only colour, so the panel can be read at a
            // glance instead of line by line.
            boolean bad = line.startsWith("Dropped: ") && !line.startsWith("Dropped: 0");
            font.drawString(context, line, x + pad, ly, (bad ? new Color(0xC06A6A) : new Color(0xB4B4C0)).getRGB());
            ly += lineHeight;
        }
    }
}
