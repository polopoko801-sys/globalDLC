package thunder.hack.features.modules.combat;

import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.network.listener.ClientPlayPacketListener;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.s2c.common.CommonPingS2CPacket;
import net.minecraft.network.packet.s2c.common.DisconnectS2CPacket;
import net.minecraft.network.packet.s2c.play.ChatMessageS2CPacket;
import net.minecraft.network.packet.s2c.play.EntityAnimationS2CPacket;
import net.minecraft.network.packet.s2c.play.EntityDamageS2CPacket;
import net.minecraft.network.packet.s2c.play.EntitySetHeadYawS2CPacket;
import net.minecraft.network.packet.s2c.play.EntityStatusS2CPacket;
import net.minecraft.network.packet.s2c.play.PlaySoundFromEntityS2CPacket;
import net.minecraft.network.packet.s2c.play.PlaySoundS2CPacket;
import net.minecraft.network.packet.s2c.play.EntityPositionS2CPacket;
import net.minecraft.network.packet.s2c.play.GameMessageS2CPacket;
import net.minecraft.network.packet.s2c.play.HealthUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.EntityPositionSyncS2CPacket;
import net.minecraft.network.packet.s2c.play.EntityS2CPacket;
import net.minecraft.network.packet.s2c.play.EntityVelocityUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.ExplosionS2CPacket;
import net.minecraft.network.packet.s2c.play.GameJoinS2CPacket;
import net.minecraft.network.packet.s2c.play.PlayerPositionLookS2CPacket;
import net.minecraft.network.packet.s2c.play.PlayerRespawnS2CPacket;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import thunder.hack.core.Managers;
import thunder.hack.core.manager.client.ModuleManager;
import thunder.hack.events.impl.EventAttack;
import thunder.hack.events.impl.PacketEvent;
import thunder.hack.features.modules.Module;
import thunder.hack.setting.Setting;
import thunder.hack.setting.impl.ColorSetting;
import thunder.hack.features.modules.client.HudEditor;
import thunder.hack.gui.font.FontRenderers;
import thunder.hack.utility.render.Render2DEngine;
import thunder.hack.utility.render.Render3DEngine;

import java.awt.Color;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Backtrack modelled on LiquidBounce's (Modern) plus the older Freeze / Smart styles.
 *
 * <p>Everything the server sends is held while backtracking, so your target is shown where it was a moment
 * ago. Nothing is ever dropped: packets are replayed later in their original order.
 *
 * <ul>
 *   <li><b>Modern</b> - every packet is delayed by a random Delay..MaxDelay ms and released once it is that
 *   old, so the target is drawn smoothly behind instead of freezing and teleporting. If the real position
 *   becomes closer to you than the shown one, everything is flushed at once (holding would only hurt).</li>
 *   <li><b>Freeze</b> - the target is frozen on the spot until the server has it ReleaseDistance (8) blocks
 *   away from where it froze (or MaxHold ran out); then it snaps over, Cooldown, and it can freeze again.</li>
 *   <li><b>Smart</b> - freezes only while the target moves away from you (Augustus 2.6).</li>
 * </ul>
 *
 * <p>Activation (LiquidBounce {@code shouldBacktrack}): the target is inside MinRange..MaxRange, you hit it
 * within LastAttack ms, the Chance roll (re-rolled per hit) passed and the Cooldown after the previous
 * backtrack has run out. TrackingBuffer keeps it going for a moment after the target leaves the range.
 *
 * <p>Threading: packets arrive on the netty thread and are only queued there; deciding and replaying happen on
 * the main thread. Once anything is queued every later packet joins the queue, so the order never breaks.
 */
public final class Backtrack extends Module {
    public Backtrack() {
        super("Backtrack", Category.COMBAT);
    }

    public enum Mode {Modern, Freeze, Smart}

    private final Setting<Mode> mode = new Setting<>("Mode", Mode.Freeze);
    /** Freeze: let go once the real target is this far from the spot it was frozen on. */
    private final Setting<Float> releaseDistance = new Setting<>("ReleaseDistance", 2.8f, 0.5f, 20.0f, v -> mode.getValue() == Mode.Freeze);
    /** Freeze: let go once the REAL target is this far from your eyes - beyond it the server rejects the hits. */
    private final Setting<Float> releaseRange = new Setting<>("ReleaseRange", 3.0f, 2.0f, 10.0f, v -> mode.getValue() == Mode.Freeze);
    /**
     * Freeze: hold the server's pings (transactions) together with the target's movement, so an anticheat that
     * checks reach against what you have confirmed seeing (Grim, Vulcan, ...) accepts hits on the frozen body.
     */
    private final Setting<Boolean> holdPings = new Setting<>("HoldPings", true, v -> mode.getValue() == Mode.Freeze);

    public enum OnHurt {Hold, Release}

    /** Freeze + HoldPings: your own knockback is held with the rest (Hold) or ends the freeze (Release, Slinky's "Disable on hit"). */
    private final Setting<OnHurt> onHurt = new Setting<>("OnHurt", OnHurt.Hold, v -> mode.getValue() == Mode.Freeze && holdPings.getValue());
    /** Freeze: safety cap - the whole world is frozen while holding, so never longer than this. */
    private final Setting<Integer> maxHold = new Setting<>("MaxHold", 1500, 100, 10000, v -> mode.getValue() == Mode.Freeze);
    /**
     * Freeze: after a hit on the frozen body, the server has to confirm it (damage / hurt animation). If that does
     * not come back within your ping + a margin, the hit was a ghost - the freeze lets go at once, so the next hits
     * go at where the target really is.
     */
    private final Setting<Boolean> ghostRelease = new Setting<>("GhostRelease", true, v -> mode.getValue() == Mode.Freeze);
    /** Writes "ghost hit" in chat each time it happens, so you can see when and how often. */
    private final Setting<Boolean> ghostDebug = new Setting<>("GhostDebug", false, v -> mode.getValue() == Mode.Freeze && ghostRelease.getValue());
    private final Setting<Integer> delay = new Setting<>("Delay", 100, 0, 1000, v -> mode.getValue() != Mode.Freeze);
    private final Setting<Integer> maxDelay = new Setting<>("MaxDelay", 150, 0, 1000, v -> mode.getValue() == Mode.Modern);
    private final Setting<Float> minRange = new Setting<>("MinRange", 1.0f, 0.0f, 6.0f);
    private final Setting<Float> maxRange = new Setting<>("MaxRange", 3.0f, 1.0f, 8.0f);
    /** Cooldown after a backtrack ends before the next can start (LB NextBacktrackDelay). */
    private final Setting<Integer> cooldown = new Setting<>("Cooldown", 0, 0, 2000);
    /** Keep backtracking this long after the target left the range (LB TrackingBuffer). */
    private final Setting<Integer> trackingBuffer = new Setting<>("TrackingBuffer", 500, 0, 2000);
    private final Setting<Float> chance = new Setting<>("Chance", 100f, 0f, 100f);
    /** Only backtrack while you hit within this many ms; Freeze also lets go after this long without a hit. */
    private final Setting<Integer> lastAttack = new Setting<>("LastAttack", 1000, 0, 5000);
    private final Setting<Boolean> pauseOnHurt = new Setting<>("PauseOnHurt", false);
    private final Setting<Integer> hurtTime = new Setting<>("HurtTime", 3, 0, 10, v -> pauseOnHurt.getValue());
    /** Let everything out when YOU get knocked back, so the velocity is applied on time (Grim simulation). */
    private final Setting<Boolean> releaseOnVelocity = new Setting<>("ReleaseOnVelocity", true, v -> mode.getValue() != Mode.Freeze);
    private final Setting<Boolean> render = new Setting<>("Render", true);
    /** Take the colour from the client theme (HudEditor) instead of Color. */
    private final Setting<Boolean> sync = new Setting<>("SyncColor", true, v -> render.getValue());
    private final Setting<ColorSetting> color = new Setting<>("Color", new ColorSetting(new Color(120, 90, 255, 90)), v -> render.getValue() && !sync.getValue());

    private record Held(Packet<?> packet, long at) {
    }

    private final ConcurrentLinkedQueue<Held> held = new ConcurrentLinkedQueue<>();
    private volatile boolean holding;
    private volatile int targetId = Integer.MIN_VALUE;
    /** Where the server really has the target (followed through the packets being held). */
    private volatile Vec3d realPos;
    /** Freeze: where the target was standing when it got frozen. */
    private Vec3d freezePos;
    /** Real position one tick ago - for the look-ahead release check. */
    private Vec3d prevRealPos;
    private long holdSince;
    private long releasedAt;
    private long inRangeAt;
    private long currentDelay;
    private long currentCooldown;
    private boolean chancePassed = true;
    private PlayerEntity lastHit;
    private long lastHitAt;

    @Override
    public void onDisable() {
        release();
        targetId = Integer.MIN_VALUE;
        realPos = null;
    }

    @Override
    public void onLogout() {
        holding = false;
        held.clear();
        targetId = Integer.MIN_VALUE;
        realPos = null;
        lastHit = null;
    }

    @EventHandler
    @SuppressWarnings("unused")
    public void onAttack(EventAttack event) {
        // the attack hook posts the entity with pre=false (the pre=true one is doAttack, with no entity)
        if (!(event.getEntity() instanceof PlayerEntity p) || mc.player == null) return;
        long now = System.currentTimeMillis();
        lastHit = p;
        lastHitAt = now;
        chancePassed = ThreadLocalRandom.current().nextFloat() * 100f < chance.getValue();
        // ghost-hit watch: a hit on the frozen body must be confirmed by the server soon
        if (holding && p.getId() == targetId && now - targetHurtAt > 520) {
            pendingHitAt = now;
            pendingHits++;
        }

        // Freeze starts ON the hit, before the server's answer (the knockback) comes back - so the target stays
        // exactly where you hit it instead of being shoved first and frozen a tick later
        if (mode.getValue() == Mode.Freeze && !holding && chancePassed && !Managers.FRIEND.isFriend(p)
                && now - releasedAt >= currentCooldown
                && !(pauseOnHurt.getValue() && p.hurtTime >= hurtTime.getValue())) {
            if (p.getId() != targetId) {
                release();
                targetId = p.getId();
            }
            realPos = serverPos(p);
            // stop the client's own smoothing too: without this the body kept gliding for ~3 ticks towards the
            // last position it had been sent, so it visibly slid after the hit instead of stopping dead on it
            try {
                if (p.getInterpolator() != null) p.getInterpolator().clear();
            } catch (Throwable ignored) {
            }
            freezePos = p.getEntityPos();
            holdSince = now;
            holding = true;
        }
    }

    private PlayerEntity target() {
        // while frozen, stay on the frozen one even if the aura has moved on
        if (holding && lastHit != null && lastHit.getId() == targetId && !lastHit.isRemoved() && lastHit.isAlive()) return lastHit;
        if (ModuleManager.kilaura.isEnabled() && ModuleManager.kilaura.getTarget() instanceof PlayerEntity p) return p;
        if (lastHit != null && !lastHit.isRemoved() && lastHit.isAlive()) return lastHit;
        return null;
    }

    /* -- netty thread --------------------------------------------------- */

    @EventHandler
    @SuppressWarnings("unused")
    public void onPacketReceive(PacketEvent.Receive event) {
        Packet<?> packet = event.getPacket();
        if (mc.player == null || packet instanceof DisconnectS2CPacket) return;
        // LB nextgen: chat is never held - a delayed chat line is the one thing a human notices
        if (packet instanceof GameMessageS2CPacket || packet instanceof ChatMessageS2CPacket) return;
        // the server confirming a hit on the target: damage, or the hurt status - clears the ghost-hit watch
        if (packet instanceof EntityDamageS2CPacket dmg && dmg.entityId() == targetId
                || packet instanceof EntityStatusS2CPacket st && st.getStatus() == 2 && mc.world != null && st.getEntity(mc.world) != null && st.getEntity(mc.world).getId() == targetId) {
            targetHurtAt = System.currentTimeMillis();
            pendingHitAt = 0L;
            pendingHits = 0;
        }
        // the feedback of YOUR hit (red flash, hurt sound, swing, totem pop) is shown at once - it moves nothing,
        // so it can safely overtake the held positions, and the hit no longer looks like it landed late
        if (packet instanceof EntityDamageS2CPacket || packet instanceof EntityAnimationS2CPacket
                || packet instanceof EntityStatusS2CPacket || packet instanceof PlaySoundS2CPacket
                || packet instanceof PlaySoundFromEntityS2CPacket) return;
        trackReal(packet);

        // Freeze holds ONLY the target's movement. The rest of the world (you, your knockback, blocks, other
        // players, pings) keeps running, so nothing but that one player stands still, and your own knockback
        // no longer cuts the freeze short.
        if (mode.getValue() == Mode.Freeze) {
            if (packet instanceof PlayerRespawnS2CPacket || packet instanceof GameJoinS2CPacket) {
                holding = false;
                held.clear();
                return;
            }
            if (packet instanceof HealthUpdateS2CPacket hp && hp.getHealth() <= 0f) {
                holding = false;
                if (!held.isEmpty()) mc.execute(this::flush);
                return;
            }
            if (holdPings.getValue()) {
                // With the pings held, the anticheat believes you have not seen anything since the freeze - so
                // your own knockback or a setback applied now would look early to it (simulation flag). Those
                // end the freeze: everything held goes first, in order, then this packet.
                boolean selfVelocity = packet instanceof ExplosionS2CPacket
                        || packet instanceof EntityVelocityUpdateS2CPacket v && v.getEntityId() == mc.player.getId();
                // OnHurt=Hold (classic backtrack): your knockback queues with everything else and lands when the
                // freeze ends - the target stays pinned through the whole trade instead of letting go on every hit
                if (selfVelocity && onHurt.getValue() == OnHurt.Hold) {
                    if (holding || !held.isEmpty()) {
                        held.add(new Held(packet, System.currentTimeMillis()));
                        event.cancel();
                    }
                    return;
                }
                if (selfVelocity || packet instanceof PlayerPositionLookS2CPacket) {
                    boolean was = holding;
                    holding = false;
                    if (!held.isEmpty()) {
                        held.add(new Held(packet, System.currentTimeMillis()));
                        event.cancel();
                        mc.execute(() -> {
                            if (was) releasedAt = System.currentTimeMillis();
                            flush();
                        });
                    }
                    return;
                }
            }
            // !held.isEmpty(): released but not yet replayed - later moves must queue behind the older ones
            // Pings (transactions) are held with the target's moves: an anticheat (Grim) checks reach against what
            // the client has CONFIRMED seeing, so while they are held it still believes you see the frozen body
            // and accepts hits on it. Without them every hit on the frozen body is a reach flag and is cancelled.
            if ((holding || !held.isEmpty())
                    && (aboutTarget(packet) || holdPings.getValue() && packet instanceof CommonPingS2CPacket)) {
                held.add(new Held(packet, System.currentTimeMillis()));
                event.cancel();
            }
            return;
        }

        // LB nextgen: you are dying - let everything out so the death is processed on time
        if (packet instanceof HealthUpdateS2CPacket hp && hp.getHealth() <= 0f) {
            holding = false;
            if (!held.isEmpty()) {
                held.add(new Held(packet, System.currentTimeMillis()));
                event.cancel();
                mc.execute(this::flush);
            }
            return;
        }

        // a setback / respawn / new world / (optionally) our own knockback: everything goes now, in order
        boolean selfVelocity = releaseOnVelocity.getValue()
                && (packet instanceof ExplosionS2CPacket
                || packet instanceof EntityVelocityUpdateS2CPacket v && v.getEntityId() == mc.player.getId());
        if (selfVelocity || packet instanceof PlayerPositionLookS2CPacket
                || packet instanceof PlayerRespawnS2CPacket || packet instanceof GameJoinS2CPacket) {
            boolean was = holding;
            holding = false;
            if (!held.isEmpty()) {
                held.add(new Held(packet, System.currentTimeMillis()));
                event.cancel();
                mc.execute(() -> {
                    if (was) releasedAt = System.currentTimeMillis();
                    flush();
                });
            }
            return;
        }
        if (!holding && held.isEmpty()) return;
        held.add(new Held(packet, System.currentTimeMillis()));
        event.cancel();
    }

    /** A packet that moves / turns / pushes the target - the only kind Freeze holds. */
    private boolean aboutTarget(Packet<?> packet) {
        int id = targetId;
        try {
            if (packet instanceof EntityS2CPacket move) {
                Entity e = mc.world == null ? null : move.getEntity(mc.world);
                return e != null && e.getId() == id;
            }
            if (packet instanceof EntityPositionS2CPacket tp) return tp.entityId() == id;
            if (packet instanceof EntityPositionSyncS2CPacket sync) return sync.id() == id;
            if (packet instanceof EntityVelocityUpdateS2CPacket v) return v.getEntityId() == id;
            if (packet instanceof EntitySetHeadYawS2CPacket head) {
                Entity e = mc.world == null ? null : head.getEntity(mc.world);
                return e != null && e.getId() == id;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private void trackReal(Packet<?> packet) {
        if (mc.world == null || realPos == null) return;
        try {
            if (packet instanceof EntityS2CPacket move) {
                Entity e = move.getEntity(mc.world);
                if (e != null && e.getId() == targetId && move.isPositionChanged())
                    realPos = realPos.add(move.getDeltaX() / 4096.0, move.getDeltaY() / 4096.0, move.getDeltaZ() / 4096.0);
            } else if (packet instanceof EntityPositionS2CPacket tp && tp.entityId() == targetId) {
                realPos = tp.change().position();
            } else if (packet instanceof EntityPositionSyncS2CPacket sync && sync.id() == targetId) {
                realPos = sync.values().position();
            }
        } catch (Throwable ignored) {
        }
    }

    /* -- main thread ---------------------------------------------------- */

    /* -- ghost hits ------------------------------------------------------ */

    /** When an unconfirmed hit on the frozen body was sent (0 = none waiting), and how many are waiting. */
    private volatile long pendingHitAt;
    private volatile int pendingHits;
    /** Last time the server confirmed damage on the target - hits inside its invulnerability are not ghosts. */
    private volatile long targetHurtAt;
    private int ghostCount;

    /** No confirmation for a hit within ping + margin = a ghost hit: the freeze is dropped at once. */
    private void checkGhostHit() {
        if (!ghostRelease.getValue() || mode.getValue() != Mode.Freeze || pendingHitAt == 0L) return;
        if (!holding) {
            pendingHitAt = 0L;
            pendingHits = 0;
            return;
        }
        int ping = 80;
        try {
            var entry = mc.getNetworkHandler().getPlayerListEntry(mc.player.getUuid());
            if (entry != null) ping = entry.getLatency();
        } catch (Throwable ignored) {
        }
        long wait = 180L + Math.round(ping * 1.4);
        if (System.currentTimeMillis() - pendingHitAt < wait) return;

        ghostCount++;
        if (ghostDebug.getValue())
            sendMessage("§c[Backtrack] ghost hit §7(" + ghostCount + ", ping " + ping + "ms, czekałem " + wait + "ms) - puszczam zamrożenie");
        pendingHitAt = 0L;
        pendingHits = 0;
        release();
    }

    @Override
    public void onUpdate() {
        if (fullNullCheck()) return;
        checkGhostHit();
        PlayerEntity target = target();
        if (target == null || Managers.FRIEND.isFriend(target)) {
            reset();
            return;
        }
        if (target.getId() != targetId) {
            release();
            targetId = target.getId();
            realPos = serverPos(target);
            return;
        }
        if (realPos == null) realPos = serverPos(target);

        long now = System.currentTimeMillis();
        Vec3d eyes = mc.player.getEyePos();
        double shown = eyes.distanceTo(closest(eyes, target.getBoundingBox()));
        double real = eyes.distanceTo(closest(eyes, realBox(target)));

        if (real >= minRange.getValue() && real <= maxRange.getValue()) inRangeAt = now;
        boolean inRange = now - inRangeAt <= trackingBuffer.getValue();

        boolean allowed = inRange
                && mc.player.age > 10
                && chancePassed
                && now - lastHitAt <= lastAttack.getValue()
                && !(pauseOnHurt.getValue() && target.hurtTime >= hurtTime.getValue())
                && (holding || now - releasedAt >= currentCooldown);

        switch (mode.getValue()) {
            case Modern -> tickModern(allowed, shown, real, now);
            case Freeze -> tickFreeze(allowed, target, now);
            case Smart -> tickTimed(allowed && real > shown + 0.05, now);
        }
    }

    /**
     * Freeze: the target is frozen where it stands and stays there until the server has it ReleaseDistance
     * blocks away from that spot (or MaxHold ran out) - then everything is played out and it snaps over.
     * Once frozen, range / last-attack no longer end it; only the distance, the cap, or losing the target.
     */
    private void tickFreeze(boolean allowed, PlayerEntity target, long now) {
        if (!holding) {
            if (!allowed) return;
            holding = true;
            holdSince = now;
            freezePos = target.getEntityPos();
            return;
        }
        if (freezePos == null) freezePos = target.getEntityPos();
        // The server judges a hit by where the target REALLY is; in practice hits stop counting once the real
        // one is ~3 blocks off (ghost hits). Checked one tick AHEAD (current speed of the real position), so the
        // release happens before the line is crossed - not one tick after, which was the last ghost hit.
        Vec3d prev = prevRealPos == null ? realPos : prevRealPos;
        Vec3d ahead = realPos.add(realPos.subtract(prev));
        prevRealPos = realPos;
        Vec3d eyes = mc.player.getEyePos();
        Box aheadBox = target.getBoundingBox().offset(ahead.subtract(target.getEntityPos()));
        double realDist = Math.max(eyes.distanceTo(closest(eyes, realBox(target))), eyes.distanceTo(closest(eyes, aheadBox)));
        double moved = Math.max(realPos.distanceTo(freezePos), ahead.distanceTo(freezePos));
        // A pinned target's position does not change, so only the clock and how far the REAL one got can end
        // it - not "it came closer" or "you paused your clicks", which ended every freeze mid-fight before.
        if (moved >= releaseDistance.getValue()
                || realDist > releaseRange.getValue()
                || now - holdSince >= maxHold.getValue())
            release();
    }

    /** Smooth: every packet waits its own delay; the target trails behind instead of freezing. */
    private void tickModern(boolean allowed, double shown, double real, long now) {
        // the real spot is closer than the shown one - holding only makes the hit harder, let it all go
        if (!allowed || real + 0.05 < shown) {
            release();
            return;
        }
        if (!holding) {
            holding = true;
            holdSince = now;
            rollDelay();
        }
        Held h;
        while ((h = held.peek()) != null && now - h.at() >= currentDelay) {
            held.poll();
            apply(h.packet());
        }
        // LB nextgen re-rolls only once the queue has run dry, so one "lag" keeps one consistent delay
        if (held.isEmpty()) rollDelay();
    }

    /** Where the server last put the entity (the tracked position), not the smoothed on-screen one. */
    private static Vec3d serverPos(Entity e) {
        return e.getTrackedPosition().getPos();
    }

    private void tickTimed(boolean allowed, long now) {
        if (allowed && (!holding || now - holdSince < delay.getValue())) {
            if (!holding) holdSince = now;
            holding = true;
        } else {
            release();
        }
    }

    private void rollDelay() {
        int a = delay.getValue(), b = Math.max(a, maxDelay.getValue());
        currentDelay = a == b ? a : ThreadLocalRandom.current().nextInt(a, b + 1);
    }

    private Box realBox(Entity target) {
        return target.getBoundingBox().offset(realPos.subtract(target.getEntityPos()));
    }

    private static Vec3d closest(Vec3d p, Box b) {
        return new Vec3d(MathHelper.clamp(p.x, b.minX, b.maxX), MathHelper.clamp(p.y, b.minY, b.maxY), MathHelper.clamp(p.z, b.minZ, b.maxZ));
    }

    private void reset() {
        release();
        targetId = Integer.MIN_VALUE;
        realPos = null;
    }

    private void release() {
        if (holding) {
            releasedAt = System.currentTimeMillis();
            currentCooldown = cooldown.getValue();
        }
        holding = false;
        freezePos = null;
        prevRealPos = null;
        if (!held.isEmpty()) flush();
    }

    private void flush() {
        Held h;
        while ((h = held.poll()) != null) apply(h.packet());
        if (mc.world != null) {
            Entity t = mc.world.getEntityById(targetId);
            if (t != null) realPos = serverPos(t);
        }
    }

    @SuppressWarnings("unchecked")
    private void apply(Packet<?> p) {
        var handler = mc.getNetworkHandler();
        if (handler == null) return;
        try {
            ((Packet<ClientPlayPacketListener>) p).apply(handler);
        } catch (Throwable ignored) {
            // a packet that no longer makes sense (entity gone etc.) is skipped, like vanilla would
        }
    }

    /* -- visuals --------------------------------------------------------- */

    /** 0..1, eased in when a freeze starts and out when it ends, so nothing pops on or off. */
    private float fade;
    /** The drawn real box: glides after the packets instead of jumping 20 times a second. */
    private Box shownReal;
    /** Last frozen body box, kept for the fade-out after the entity has been released. */
    private Box shownFrozen;
    private long lastFrame;

    /**
     * Where the server really has the target, while you hit the frozen body: a gradient box that fades in,
     * glides after the real position, a glowing tether back to the frozen body and a distance tag.
     */
    @Override
    public void onRender2D(DrawContext context) {
        long nowNs = System.nanoTime();
        float dt = lastFrame == 0 ? 0.016f : Math.min(0.1f, (nowNs - lastFrame) / 1e9f);
        lastFrame = nowNs;
        if (!render.getValue() || mc.world == null) {
            fade = 0;
            shownReal = null;
            return;
        }

        Entity t = holding && realPos != null ? mc.world.getEntityById(targetId) : null;
        if (t != null) {
            Box real = realBox(t);
            float k = 1f - (float) Math.exp(-dt * 18f); // ~55 ms to settle, frame-rate independent
            shownReal = shownReal == null || shownReal.getCenter().distanceTo(real.getCenter()) > 12
                    ? real : Render3DEngine.interpolateBox(shownReal, real, k);
            shownFrozen = t.getBoundingBox();
        }
        fade += ((t != null ? 1f : 0f) - fade) * (1f - (float) Math.exp(-dt * 12f));
        if (fade < 0.02f || shownReal == null) {
            if (t == null) shownReal = null;
            return;
        }

        Color c = sync.getValue() ? HudEditor.getColor(0) : color.getValue().getColorObject();
        int baseA = sync.getValue() ? 255 : Math.max(c.getAlpha(), 60);
        Color top = alpha(c, (int) (baseA * 0.55f * fade));
        Color bottom = alpha(c, (int) (baseA * 0.12f * fade));
        Color outline = alpha(c, (int) (235 * fade));
        Render3DEngine.drawProjectedBox(context, shownReal, top, bottom, outline, 1.6f, true, true);

        if (shownFrozen != null) {
            // tether: frozen body -> where it really is
            Render3DEngine.drawGlowLine2D(context, shownFrozen.getCenter(), shownReal.getCenter(), alpha(c, (int) (150 * fade)), 6f);
            Render3DEngine.drawLine2D(context, shownFrozen.getCenter(), shownReal.getCenter(), alpha(Color.WHITE, (int) (200 * fade)));
        }

        // distance tag above the real box
        Vec3d head = new Vec3d(shownReal.getCenter().x, shownReal.maxY + 0.35, shownReal.getCenter().z);
        Vec3d s = Render3DEngine.worldSpaceToScreenSpace(head);
        if (s.z > 0 && s.z < 1 && freezePos != null && realPos != null) {
            String text = String.format("%.1fm", realPos.distanceTo(freezePos));
            var font = FontRenderers.sf_bold_mini;
            float w = font.getStringWidth(text) + 8f, h = 11f;
            float x = (float) s.x - w / 2f, y = (float) s.y - h;
            Render2DEngine.drawRound(context, x, y, w, h, 3f, alpha(new Color(14, 14, 20), (int) (200 * fade)));
            // progress strip: how close it is to ReleaseDistance
            float p = (float) MathHelper.clamp(realPos.distanceTo(freezePos) / releaseDistance.getValue(), 0, 1);
            Render2DEngine.drawRect(context, x + 2f, y + h - 2f, (w - 4f) * p, 1f, alpha(c, (int) (255 * fade)));
            font.drawCenteredString(context, text, s.x, y + 2.5f, alpha(Color.WHITE, (int) (255 * fade)).getRGB());
        }
    }

    private static Color alpha(Color c, int a) {
        return new Color(c.getRed(), c.getGreen(), c.getBlue(), MathHelper.clamp(a, 0, 255));
    }

    /** The entity id currently held frozen, or MIN_VALUE - Kilaura puts it first so it keeps hitting the frozen one. */
    public int frozenId() {
        return isEnabled() && holding ? targetId : Integer.MIN_VALUE;
    }

    @Override
    public String getDisplayInfo() {
        if (holding && mode.getValue() == Mode.Freeze && mc.world != null && freezePos != null && realPos != null)
            return String.format("Freeze %.1fm", realPos.distanceTo(freezePos));
        return holding ? mode.getValue().name() + " " + held.size() : mode.getValue().name();
    }
}
