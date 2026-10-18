package thunder.hack.features.modules.misc;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.NotNull;
import thunder.hack.core.Managers;
import thunder.hack.features.modules.Module;
import thunder.hack.gui.font.FontRenderers;
import thunder.hack.gui.notification.Notification;
import thunder.hack.setting.Setting;
import thunder.hack.utility.render.Render2DEngine;

import java.awt.Color;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * "WLOT DETECTED" - someone is flying around you.
 *
 * <p>Anyone airborne inside the radius for long enough, with no ground under them and without falling,
 * is a flyer: creative flight, a hacked client, an elytra. Invisibility changes nothing here - an
 * invisible player is still an entity the client knows about, which is exactly why this is worth having.
 * A player the server hides completely (a vanished admin) cannot be seen by any client-side check.
 */
public class WlotDetector extends Module {
    public WlotDetector() {
        super("WlotDetector", Category.MISC);
    }

    private final Setting<Integer> range = new Setting<>("Range", 100, 8, 256);
    private final Setting<Integer> airTicks = new Setting<>("AirTicks", 20, 4, 100);
    private final Setting<Integer> groundScan = new Setting<>("GroundScan", 4, 1, 16);
    private final Setting<Boolean> elytra = new Setting<>("Elytra", true);
    private final Setting<Boolean> invisible = new Setting<>("Invisible", true);
    private final Setting<Boolean> friends = new Setting<>("Friends", false);

    private final Setting<Boolean> screenText = new Setting<>("ScreenText", true);
    private final Setting<Integer> textY = new Setting<>("TextHeight", 25, 5, 90, v -> screenText.getValue());
    private final Setting<Integer> holdTime = new Setting<>("HoldSeconds", 5, 1, 30);
    private final Setting<Boolean> chat = new Setting<>("Chat", true);
    private final Setting<Boolean> notification = new Setting<>("Notification", true);
    private final Setting<Boolean> sound = new Setting<>("Sound", true);

    private static final Color WARN = new Color(242, 70, 70);
    private static final Color TEXT = new Color(235, 236, 242);
    private static final Color DIM = new Color(170, 173, 186);

    /** Per-player flight history. Dropped as soon as a player leaves the radius or the world. */
    private static final class Track {
        double lastY;
        int air;
        double vertical;
        boolean flying;
        long alerted;
    }

    private record Flyer(String name, int distance, int height, boolean invisible, boolean elytra) {
    }

    private final Map<UUID, Track> tracks = new HashMap<>();
    private final List<Flyer> flyers = new ArrayList<>();
    private long lastSeen;

    @Override
    public void onDisable() {
        tracks.clear();
        flyers.clear();
        lastSeen = 0;
    }

    @Override
    public String getDisplayInfo() {
        return flyers.isEmpty() ? null : String.valueOf(flyers.size());
    }

    @Override
    public void onUpdate() {
        if (fullNullCheck()) return;

        flyers.clear();
        double maxDistance = range.getValue();

        for (PlayerEntity player : mc.world.getPlayers()) {
            if (player == mc.player || player.isSpectator()) continue;
            if (!friends.getValue() && Managers.FRIEND.isFriend(player.getName().getString())) continue;

            double distance = mc.player.distanceTo(player);
            if (distance > maxDistance) {
                tracks.remove(player.getUuid());
                continue;
            }
            if (player.isInvisible() && !invisible.getValue()) continue;

            Track track = tracks.computeIfAbsent(player.getUuid(), u -> {
                Track t = new Track();
                t.lastY = player.getY();
                return t;
            });

            double dy = player.getY() - track.lastY;
            track.lastY = player.getY();

            boolean gliding = player.isGliding();
            if (gliding && !elytra.getValue()) {
                reset(track);
                continue;
            }

            if (airborne(player)) {
                track.air++;
                track.vertical += dy;
            } else {
                reset(track);
                continue;
            }

            // Free fall accelerates fast; a flyer hovers, climbs, or descends slowly.
            boolean falling = track.vertical / track.air < -0.35d;
            // Low flight over terrain still counts, it just has to last longer than any jump can:
            // a jump is back on the ground within ~12 ticks, and landing resets the counter.
            int needed = groundBelow(player) ? airTicks.getValue() * 2 : airTicks.getValue();
            boolean flying = track.air >= needed && !falling;

            if (!flying) continue;

            flyers.add(new Flyer(player.getName().getString(), (int) distance,
                    (int) (player.getY() - mc.player.getY()), player.isInvisible(), gliding));

            if (!track.flying && System.currentTimeMillis() - track.alerted > 5000L) {
                track.alerted = System.currentTimeMillis();
                alert(player, (int) distance, gliding);
            }
            track.flying = true;
        }

        if (!flyers.isEmpty()) lastSeen = System.currentTimeMillis();

        // players that walked out of range or logged off
        Iterator<Map.Entry<UUID, Track>> it = tracks.entrySet().iterator();
        while (it.hasNext())
            if (mc.world.getPlayerByUuid(it.next().getKey()) == null) it.remove();
    }

    private static void reset(@NotNull Track track) {
        track.air = 0;
        track.vertical = 0;
        track.flying = false;
    }

    /** Airborne means: not standing, not swimming, not climbing, not riding anything. */
    private static boolean airborne(@NotNull PlayerEntity player) {
        if (player.isOnGround() || player.hasVehicle()) return false;
        return !player.isTouchingWater() && !player.isInLava() && !player.isClimbing();
    }

    /** Something solid within the scan depth below - jumping and parkour happen here, real flight rarely. */
    private boolean groundBelow(@NotNull PlayerEntity player) {
        BlockPos feet = player.getBlockPos();
        for (int i = 1; i <= groundScan.getValue(); i++) {
            BlockPos pos = feet.down(i);
            if (!mc.world.getBlockState(pos).getCollisionShape(mc.world, pos).isEmpty()) return true;
        }
        return false;
    }

    private void alert(@NotNull PlayerEntity player, int distance, boolean gliding) {
        String name = player.getName().getString();
        String what = (player.isInvisible() ? " (invis)" : "") + (gliding ? " (elytra)" : "");
        String message = Formatting.RED + "WLOT: " + Formatting.WHITE + name + Formatting.GRAY
                + " lata " + distance + "m od ciebie" + what;

        if (chat.getValue()) sendMessage(message);

        if (notification.getValue())
            Managers.NOTIFICATION.publicity("WLOT DETECTED", name + " - " + distance + "m" + what, 3, Notification.Type.WARNING);

        if (sound.getValue()) {
            try {
                mc.world.playSound(mc.player, mc.player.getBlockPos(), SoundEvents.ENTITY_PLAYER_LEVELUP, SoundCategory.BLOCKS, 1f, 1.4f);
            } catch (Exception ignored) {
            }
        }
    }

    @Override
    public void onRender2D(DrawContext context) {
        if (!screenText.getValue() || fullNullCheck() || mc.options.hudHidden) return;

        long since = System.currentTimeMillis() - lastSeen;
        long hold = holdTime.getValue() * 1000L;
        if (lastSeen == 0 || since > hold) return;

        // full strength while someone is up there, fading out over the last second of the hold
        float fade = flyers.isEmpty() ? Math.max(0f, Math.min(1f, (hold - since) / 1000f)) : 1f;
        float pulse = 0.75f + 0.25f * (float) Math.sin(System.currentTimeMillis() / 180d);

        float cx = mc.getWindow().getScaledWidth() / 2f;
        float y = mc.getWindow().getScaledHeight() * (textY.getValue() / 100f);

        String title = "WLOT DETECTED";
        float titleW = FontRenderers.sf_bold_big.getStringWidth(title);
        Render2DEngine.drawRound(context, cx - titleW / 2f - 8f, y - 5f, titleW + 16f, 20f, 4f,
                new Color(0, 0, 0, (int) (120 * fade)));
        FontRenderers.sf_bold_big.drawCenteredString(context, title, cx, y,
                Render2DEngine.injectAlpha(WARN, (int) (255 * fade * pulse)).getRGB());

        float rowY = y + 18f;
        for (Flyer flyer : flyers) {
            String row = flyer.name() + "  " + flyer.distance() + "m  "
                    + (flyer.height() >= 0 ? "+" : "") + flyer.height() + "y"
                    + (flyer.invisible() ? "  invis" : "") + (flyer.elytra() ? "  elytra" : "");

            FontRenderers.sf_medium.drawCenteredString(context, row, cx, rowY,
                    Render2DEngine.injectAlpha(flyer.invisible() ? DIM : TEXT, (int) (255 * fade)).getRGB());
            rowY += 11f;
        }
    }
}
