package thunder.hack.features.modules.render;

import meteordevelopment.orbit.EventHandler;
import net.minecraft.network.packet.s2c.play.WorldTimeUpdateS2CPacket;
import thunder.hack.events.impl.PacketEvent;
import thunder.hack.features.modules.Module;
import thunder.hack.setting.Setting;

/**
 * 1:1 port of Celestial's {@code Ambience}: the server's time updates are dropped and the client's time of
 * day is pinned to Day / Night / Morning / Sunset, or spun forward by "TimeSpin Speed" x 100 ticks per tick.
 */
public class Ambience extends Module {
    public Ambience() {
        super("Ambience", Category.RENDER);
    }

    private enum Mode {Day, Night, Morning, Sunset, Spin}

    private final Setting<Mode> ambienceMode = new Setting<>("Ambience Mode", Mode.Night);
    private final Setting<Float> time = new Setting<>("TimeSpin Speed", 2.0f, 1.0f, 10.0f, v -> ambienceMode.is(Mode.Spin));

    private long spin = 0L;

    @EventHandler
    private void onPacket(PacketEvent.Receive event) {
        if (event.getPacket() instanceof WorldTimeUpdateS2CPacket) event.cancel();
    }

    @Override
    public void onUpdate() {
        if (fullNullCheck()) return;
        long timeOfDay = switch (ambienceMode.getValue()) {
            case Spin -> {
                long t = spin;
                spin = (long) ((float) spin + time.getValue() * 100.0f);
                yield t;
            }
            case Day -> 5000L;
            case Night -> 17000L;
            case Morning -> 0L;
            case Sunset -> 13000L;
        };
        mc.world.setTime(mc.world.getTime(), timeOfDay, false);
    }

    @Override
    public String getDisplayInfo() {
        return ambienceMode.getValue().name();
    }
}
