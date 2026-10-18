package thunder.hack.features.modules.combat;

import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.network.listener.ClientPlayPacketListener;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.s2c.common.DisconnectS2CPacket;
import net.minecraft.network.packet.s2c.play.EntityPositionS2CPacket;
import net.minecraft.network.packet.s2c.play.EntityPositionSyncS2CPacket;
import net.minecraft.network.packet.s2c.play.EntityS2CPacket;
import net.minecraft.network.packet.s2c.play.GameJoinS2CPacket;
import net.minecraft.network.packet.s2c.play.PlayerPositionLookS2CPacket;
import net.minecraft.network.packet.s2c.play.PlayerRespawnS2CPacket;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import thunder.hack.core.manager.client.ModuleManager;
import thunder.hack.events.impl.EventAttack;
import thunder.hack.events.impl.PacketEvent;
import thunder.hack.features.modules.Module;
import thunder.hack.setting.Setting;
import thunder.hack.setting.impl.ColorSetting;
import thunder.hack.utility.render.Render3DEngine;

import java.awt.Color;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Backtrack, as most clients in the cheat-sources repo have it (Augustus 2.6 / Rise / Koks): while your target
 * moves AWAY from you, everything the server sends is held for a moment, so the target stays where it was -
 * closer - and you keep hitting it. As soon as holding stops helping (it comes back, leaves Range, a setback,
 * or Time ms passed) the held packets are played out in their original order; nothing is ever dropped.
 *
 * <p>Separate from Kilaura on purpose: Kilaura is not touched. The target is Kilaura's when it has one,
 * otherwise the last player you hit yourself.
 *
 * <p>Threading: packets arrive on the netty thread and are only queued there; deciding and replaying happen on
 * the main thread. Once anything is queued every later packet joins the queue, so the order never breaks.
 */
public final class Backtrack extends Module {
    public Backtrack() {
        super("Backtrack", Category.COMBAT);
    }

    public enum Mode {
        /** The target is frozen where it stands for Time ms, then catches up, then the next freeze. */
        Freeze,
        /** Only holds while the target is moving away from you (Augustus 2.6). */
        Smart
    }

    private final Setting<Mode> mode = new Setting<>("Mode", Mode.Freeze);
    private final Setting<Integer> time = new Setting<>("Time", 250, 50, 1000);
    /** Freeze: how long the target is let go between two freezes, so it catches up. */
    private final Setting<Integer> gap = new Setting<>("Gap", 100, 0, 1000, v -> mode.getValue() == Mode.Freeze);
    private final Setting<Float> range = new Setting<>("Range", 6.0f, 3.0f, 8.0f);
    private final Setting<Boolean> render = new Setting<>("Render", true);
    private final Setting<ColorSetting> color = new Setting<>("Color", new ColorSetting(new Color(120, 90, 255, 90)), v -> render.getValue());

    private final ConcurrentLinkedQueue<Packet<?>> held = new ConcurrentLinkedQueue<>();
    private volatile boolean holding;
    private volatile int targetId = Integer.MIN_VALUE;
    /** Where the server really has the target (followed through the packets being held). */
    private volatile Vec3d realPos;
    private long holdSince;
    private long releasedAt;
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
        if (event.getEntity() instanceof PlayerEntity p) {
            lastHit = p;
            lastHitAt = System.currentTimeMillis();
        }
    }

    private PlayerEntity target() {
        if (ModuleManager.kilaura.isEnabled() && ModuleManager.kilaura.getTarget() instanceof PlayerEntity p) return p;
        if (lastHit != null && System.currentTimeMillis() - lastHitAt < 3000 && !lastHit.isRemoved() && lastHit.isAlive()) return lastHit;
        return null;
    }

    /* -- netty thread --------------------------------------------------- */

    @EventHandler
    @SuppressWarnings("unused")
    public void onPacketReceive(PacketEvent.Receive event) {
        Packet<?> packet = event.getPacket();
        if (mc.player == null || packet instanceof DisconnectS2CPacket) return;
        trackReal(packet);
        // a setback / respawn / new world: everything goes first, the queue is about to be meaningless
        if (packet instanceof PlayerPositionLookS2CPacket || packet instanceof PlayerRespawnS2CPacket || packet instanceof GameJoinS2CPacket) {
            holding = false;
            if (!held.isEmpty()) {
                held.add(packet);
                event.cancel();
                mc.execute(this::flush);
            }
            return;
        }
        if (!holding && held.isEmpty()) return;
        held.add(packet);
        event.cancel();
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

    @Override
    public void onUpdate() {
        if (fullNullCheck()) return;
        PlayerEntity target = target();
        if (target == null) {
            release();
            targetId = Integer.MIN_VALUE;
            realPos = null;
            return;
        }
        if (target.getId() != targetId) {
            release();
            targetId = target.getId();
            realPos = target.getEntityPos();
            return;
        }
        if (realPos == null) realPos = target.getEntityPos();

        Vec3d eyes = mc.player.getEyePos();
        double shown = eyes.distanceTo(closest(eyes, target.getBoundingBox()));
        double real = eyes.distanceTo(closest(eyes, realBox(target)));

        boolean helps = mode.getValue() == Mode.Freeze
                ? shown < range.getValue() && System.currentTimeMillis() - releasedAt >= gap.getValue()
                : real > shown + 0.05 && real < range.getValue();
        if (helps && (!holding || System.currentTimeMillis() - holdSince < time.getValue())) {
            if (!holding) holdSince = System.currentTimeMillis();
            holding = true;
        } else {
            release();
        }
    }

    private Box realBox(Entity target) {
        return target.getBoundingBox().offset(realPos.subtract(target.getEntityPos()));
    }

    private static Vec3d closest(Vec3d p, Box b) {
        return new Vec3d(MathHelper.clamp(p.x, b.minX, b.maxX), MathHelper.clamp(p.y, b.minY, b.maxY), MathHelper.clamp(p.z, b.minZ, b.maxZ));
    }

    private void release() {
        if (holding) releasedAt = System.currentTimeMillis();
        holding = false;
        if (!held.isEmpty()) flush();
    }

    @SuppressWarnings("unchecked")
    private void flush() {
        var handler = mc.getNetworkHandler();
        Packet<?> p;
        while ((p = held.poll()) != null) {
            if (handler == null) continue;
            try {
                ((Packet<ClientPlayPacketListener>) p).apply(handler);
            } catch (Throwable ignored) {
                // a packet that no longer makes sense (entity gone etc.) is skipped, like vanilla would
            }
        }
        if (mc.world != null) {
            Entity t = mc.world.getEntityById(targetId);
            if (t != null) realPos = t.getEntityPos();
        }
    }

    /** The box where the server really has the target, while you are hitting the old one. */
    @Override
    public void onRender2D(DrawContext context) {
        if (!render.getValue() || !holding || mc.world == null || realPos == null) return;
        Entity t = mc.world.getEntityById(targetId);
        if (t == null) return;
        Color c = color.getValue().getColorObject();
        Color line = new Color(c.getRed(), c.getGreen(), c.getBlue(), 220);
        Render3DEngine.drawProjectedBox(context, realBox(t), c, c, line, 1.5f, true, true);
    }

    @Override
    public String getDisplayInfo() {
        return holding ? held.size() + "" : null;
    }
}
