package thunder.hack.features.modules.movement;

import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.c2s.play.PlayerInteractEntityC2SPacket;
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
        Combat
    }

    public enum GhostStyle {Box, Player}

    private final Setting<Mode> mode = new Setting<>("Mode", Mode.Live);
    /** Pulse: how long you stay frozen for everyone else before the whole queue goes out and you jump. */
    private final Setting<Integer> freezeMs = new Setting<>("FreezeMs", 1000, 50, 3000, v -> mode.getValue() == Mode.Pulse);
    private final Setting<Integer> lagPackets = new Setting<>("LagPackets", 10, 1, 50, v -> mode.getValue() == Mode.Combat);
    /** Distance window (eyes to the target's hitbox) in which Combat starts lagging - Augustus used 2.9..3.3. */
    private final Setting<Float> combatMin = new Setting<>("CombatMin", 2.9f, 1f, 6f, v -> mode.getValue() == Mode.Combat);
    private final Setting<Float> combatMax = new Setting<>("CombatMax", 3.3f, 1f, 6f, v -> mode.getValue() == Mode.Combat);
    private final Setting<GhostStyle> ghostStyle = new Setting<>("Ghost", GhostStyle.Box);

    /** How far behind the server sees you (Live), or how long a sample is held (Test). */
    private final Setting<Integer> delay = new Setting<>("DelayMs", 400, 0, 2000);
    /**
     * Held packets let out per tick. 1 is exactly the rate you would be sending anyway, so the server sees a
     * completely normal client that is simply behind. Going above 2 starts to look like a burst again.
     */
    private final Setting<Integer> releasePerTick = new Setting<>("ReleasePerTick", 1, 1, 4,
            v -> mode.getValue() == Mode.Live);
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
            v -> mode.getValue() == Mode.Live || mode.getValue() == Mode.Pulse);

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

    /** Rate limit on collapses, so a fight does not thrash the queue once per swing. Main thread only. */
    private final Timer collapseTimer = new Timer();
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
    private long liveCollapses;
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
        collapseTimer.reset();

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
        liveCollapses = 0L;
        livePeak = 0;

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

        if (mode.getValue() == Mode.Pulse) {
            if (pauseOnAura.getValue() && auraEngaged()) {
                if (heldCount() > 0) requestResync();
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
            if (event.getPacket() instanceof PlayerInteractEntityC2SPacket) {
                // Augustus: the hit releases everything first, so it lands from where you really are
                combatBlocking = false;
                flushAll();
                return;
            }
            if (!combatBlocking && event.getPacket() instanceof PlayerMoveC2SPacket && sweetSpot()) combatBlocking = true;
            if (combatBlocking) {
                event.cancel();
                hold(event.getPacket());
            }
            return;
        }

        // Your own hit needs the server to know where you are standing: everything held goes out first, in
        // order, and the hit right after it (sending it ahead of the held packets is a PacketOrder flag).
        if (event.getPacket() instanceof PlayerInteractEntityC2SPacket) {
            flushAll();
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
        synchronized (LOCK) {
            queue.add(new Held(event.getPacket(), System.currentTimeMillis()));
            if (queue.size() > livePeak) livePeak = queue.size();
        }
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
            default -> tickLive();
        }
        updateGhost();
    }

    /* -- Pulse / Combat ------------------------------------------------- */

    private final Timer pulseTimer = new Timer();
    private boolean combatBlocking;

    private void hold(Packet<?> packet) {
        synchronized (LOCK) {
            queue.add(new Held(packet, System.currentTimeMillis()));
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
        synchronized (LOCK) {
            for (Held h : queue) if (h.packet() instanceof PlayerMoveC2SPacket) moves++;
        }
        if (moves > lagPackets.getValue() || (combatBlocking && !auraEngaged())) {
            combatBlocking = false;
            flushAll();
        }
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

    private static final int GHOST_ID = -1337420;
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

    /**
     * Resyncs the server in ONE packet: every stale position is thrown away and only the newest is sent.
     *
     * <p>This is what used to be a burst, and the burst is what earned the setback - the server measures each
     * packet of a burst from the same tick-start position, so their distances add up until it decides you
     * moved too quickly. The intermediate steps buy nothing; only where you are right now matters.
     */
    private void release() {
        if (mc.getNetworkHandler() == null) {
            synchronized (LOCK) {
                queue.clear();
            }
            return;
        }

        List<Packet<?>> out = new ArrayList<>();

        synchronized (LOCK) {
            if (queue.isEmpty()) return;

            Packet<?> newest = queue.get(queue.size() - 1).packet();

            // A look-only packet carries no position, so the last positional one has to go with it or the
            // server keeps the stale spot.
            Packet<?> newestPos = null;
            for (int i = queue.size() - 1; i >= 0; i--) {
                if (queue.get(i).packet() instanceof PlayerMoveC2SPacket move && move.changesPosition()) {
                    newestPos = queue.get(i).packet();
                    break;
                }
            }

            queue.clear();

            if (newestPos != null) out.add(newestPos);
            if (newest != newestPos) out.add(newest);
        }

        sendAll(out);
    }

    /** A collapse that refuses to run more than a few times a second. Main thread only. */
    private void collapse() {
        if (!collapseTimer.passedMs(150)) return;
        collapseTimer.reset();
        liveCollapses++;
        release();
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
    private void drawGhost(DrawContext context) {
        Vec3d behind;

        if (mode.getValue() == Mode.Test) {
            LagSimulator.Sample state = simulator.testState();
            if (state == null) return;
            behind = state.pos();
        } else {
            if (heldCount() == 0) return;
            synchronized (LOCK) {
                behind = serverPos;
            }
        }

        Color line = HudEditor.getColor(0);
        Color fill = new Color(line.getRed(), line.getGreen(), line.getBlue(), 60);

        // Your real hitbox, moved onto the delayed position, so it shrinks when you sneak or swim too.
        Box ghost = mc.player.getBoundingBox().offset(behind.subtract(mc.player.getEntityPos()));
        Render3DEngine.drawProjectedBox(context, ghost, fill, fill, line, 1.5f, true, true);
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
                lines.add("Collapses: " + liveCollapses);
                lines.add("Peak Queue: " + livePeak);
                lines.add("Release/tick: " + releasePerTick.getValue());
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
