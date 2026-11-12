package thunder.hack.features.modules.misc;

import meteordevelopment.orbit.EventHandler;
import net.minecraft.network.packet.s2c.common.CommonPingS2CPacket;
import net.minecraft.network.packet.s2c.play.GameJoinS2CPacket;
import net.minecraft.network.packet.s2c.play.GameMessageS2CPacket;
import net.minecraft.network.packet.s2c.play.PlayerListHeaderS2CPacket;
import net.minecraft.network.packet.s2c.play.ProfilelessChatMessageS2CPacket;
import net.minecraft.text.Text;
import org.jetbrains.annotations.NotNull;
import thunder.hack.core.Managers;
import thunder.hack.events.impl.PacketEvent;
import thunder.hack.features.modules.Module;
import thunder.hack.gui.notification.Notification;
import thunder.hack.setting.Setting;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * BKClient's Anticheat Detect ({@code systems/modules/modules/other/AnticheatDetect}, MIT, bkubiakdev), 1:1.
 * Guesses the server anticheat: ping (transaction) packets are numbered differently by each anticheat, so the ids
 * right after joining act as a fingerprint; the server brand and chat/tab texts add hints. Pings matching no
 * known pattern are reported as Custom. Purely passive: nothing is sent to the server.
 */
public class AnticheatDetect extends Module {
    public AnticheatDetect() {
        super("AnticheatDetect", Category.MISC);
    }

    private static final int MAX_SAMPLES = 16;
    private static final int BRAND_CONFIDENCE = 90;
    private static final int TEXT_CONFIDENCE = 60;
    private static final int CUSTOM_CONFIDENCE = 50;
    private static final int CUSTOM_AFTER_PINGS = 40;
    private static final String CUSTOM = "Custom";
    private static final String[][] KEYWORDS = {
            {"grim", "Grim"}, {"vulcan", "Vulcan"}, {"matrix", "Matrix"}, {"intave", "Intave"},
            {"polar", "Polar"}, {"verus", "Verus"}, {"karhu", "Karhu"}, {"spartan", "Spartan"},
            {"themis", "Themis"}, {"grizzly", "Grizzly"}, {"negativity", "Negativity"},
            {"nocheatplus", "NoCheatPlus"}, {"ncp", "NoCheatPlus"}, {"aac", "AAC"}
    };

    private final Setting<Integer> samples = new Setting<>("Samples", 8, 5, MAX_SAMPLES);
    private final Setting<Boolean> brand = new Setting<>("Brand", true);
    private final Setting<Boolean> textHints = new Setting<>("TextHints", true);
    private final Setting<Boolean> notify = new Setting<>("Notify", true);

    private final Object pingLock = new Object();
    private final List<Integer> startIds = new ArrayList<>();
    private final List<Integer> recentIds = new ArrayList<>();
    private int pingCount;
    private boolean newPings;
    private volatile String pendingHint;
    private boolean brandChecked;
    private int confidence;

    @EventHandler
    public void onPacketReceive(PacketEvent.@NotNull Receive event) {
        var packet = event.getPacket();
        if (packet instanceof GameJoinS2CPacket) {
            reset();
        } else if (packet instanceof CommonPingS2CPacket ping) {
            int size = samples.getValue();
            synchronized (pingLock) {
                if (startIds.size() < size) startIds.add(ping.getParameter());
                recentIds.add(ping.getParameter());
                while (recentIds.size() > size) recentIds.removeFirst();
                pingCount++;
                newPings = true;
            }
        } else if (textHints.getValue()) {
            if (packet instanceof GameMessageS2CPacket p) scanText(p.content());
            else if (packet instanceof ProfilelessChatMessageS2CPacket p) scanText(p.message());
            else if (packet instanceof PlayerListHeaderS2CPacket p) {
                scanText(p.header());
                scanText(p.footer());
            }
        }
    }

    @Override
    public void onUpdate() {
        if (mc.player == null || mc.getNetworkHandler() == null) return;

        if (brand.getValue() && !brandChecked && mc.getNetworkHandler().getBrand() != null) {
            brandChecked = true;
            String name = findKeyword(mc.getNetworkHandler().getBrand());
            if (name != null) report(name, BRAND_CONFIDENCE);
        }

        String hint = pendingHint;
        if (hint != null) {
            pendingHint = null;
            report(hint, TEXT_CONFIDENCE);
        }

        int[] start, recent;
        int count;
        synchronized (pingLock) {
            if (!newPings) return;
            newPings = false;
            start = toArray(startIds);
            recent = toArray(recentIds);
            count = pingCount;
        }
        report(classify(start));
        report(classify(recent));
        if (count >= CUSTOM_AFTER_PINGS && confidence == 0) report(CUSTOM, CUSTOM_CONFIDENCE);
    }

    @Override
    public void onEnable() {
        reset();
    }

    private void reset() {
        synchronized (pingLock) {
            startIds.clear();
            recentIds.clear();
            pingCount = 0;
            newPings = false;
        }
        pendingHint = null;
        brandChecked = false;
        confidence = 0;
    }

    private static int[] toArray(List<Integer> ids) {
        return ids.stream().mapToInt(Integer::intValue).toArray();
    }

    private void scanText(Text text) {
        if (text == null) return;
        String name = findKeyword(text.getString());
        if (name != null) pendingHint = name;
    }

    private static String findKeyword(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        for (String[] keyword : KEYWORDS)
            if (Pattern.compile("\\b" + keyword[0] + "\\b").matcher(lower).find()) return keyword[1];
        return null;
    }

    private void report(Detection detection) {
        if (detection != null) report(detection.name(), detection.confidence());
    }

    private void report(String name, int confidence) {
        if (confidence <= this.confidence) return;
        this.confidence = confidence;
        String result = name + " (" + confidence + "%)";
        sendMessage("Wykryty anticheat: " + result);
        if (notify.getValue()) Managers.NOTIFICATION.publicity("Anticheat", result, 4, Notification.Type.INFO);
    }

    /* ------------------------------------------------------------------ */
    /* BK's AnticheatPingClassifier                                        */
    /* ------------------------------------------------------------------ */

    public record Detection(String name, int confidence) {
    }

    /**
     * Matches a window of ping ids against the numbering of known anticheats. Start ids for Grim, Intave, Karhu,
     * Vulcan/Negativity and AGC follow NotRequiem/Anticheat-Detector (MIT); the rest are community-known patterns.
     * Returns null while the window is too short or the pattern is irregular.
     */
    public static Detection classify(int[] ids) {
        if (ids.length < 5) return null;
        int first = ids[0];
        int[] diffs = new int[ids.length - 1];
        for (int i = 1; i < ids.length; i++) diffs[i - 1] = ids[i] - ids[i - 1];

        if (allEqual(diffs, 0, 1)) {
            if (first == 1) return new Detection("AntiGamingChair", 85);
            if (first == -32768) return new Detection("Intave", 85);
            if (between(first, -23772, -23762)) return new Detection("Vulcan", 88);
            if (between(first, 95, 105) || between(first, -20005, -19995)) return new Detection("Matrix", 82);
            if (between(first, -32773, -32762)) return new Detection("Grizzly", 80);
            return new Detection("Verus / " + CUSTOM, 55);
        }
        if (allEqual(diffs, 0, -1)) {
            if (between(first, -5, 0)) return new Detection("Grim", 88);
            if (between(first, -3000, -2995)) return new Detection("Karhu", 82);
            if (between(first, -8287, -8280)) return new Detection("Errata", 80);
            if (first < -3000) return new Detection("Intave", 75);
            return new Detection("Polar / " + CUSTOM, 55);
        }
        if (first == -32767 && between(ids[1], -3000, -2995) && allEqual(diffs, 1, -1)) return new Detection("Karhu", 88);
        if (first == -30767 && ids[1] == -30766 && ids[2] == -25767 && allEqual(diffs, 3, 1))
            return new Detection("Vulcan / Negativity", 88);
        if (ids[0] == ids[1] && allEqual(diffs, 2, 1)) return new Detection("Verus", 82);
        if (diffs[0] >= 100 && allEqual(diffs, 1, -1)) return new Detection("Grim / Polar", 75);
        if (first < -3000) for (int id : ids) if (id == 0) return new Detection("Intave", 70);
        return null;
    }

    private static boolean allEqual(int[] values, int from, int expected) {
        if (from >= values.length) return false;
        for (int i = from; i < values.length; i++) if (values[i] != expected) return false;
        return true;
    }

    private static boolean between(int value, int min, int max) {
        return value >= min && value <= max;
    }
}
