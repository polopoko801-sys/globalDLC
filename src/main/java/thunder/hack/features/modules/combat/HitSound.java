package thunder.hack.features.modules.combat;

import meteordevelopment.orbit.EventHandler;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.sound.SoundEvents;
import net.minecraft.network.packet.s2c.play.PlaySoundS2CPacket;
import thunder.hack.events.impl.EventAttack;
import thunder.hack.events.impl.PacketEvent;
import thunder.hack.features.modules.Module;
import thunder.hack.setting.Setting;
import thunder.hack.utility.Timer;

/**
 * Plays a client-side sound whenever you land a hit on an entity. Hooks {@link EventAttack} so it
 * fires for both manual attacks and Aura. Purely cosmetic.
 */
public final class HitSound extends Module {
    private final Setting<Sounds> sound = new Setting<>("Sound", Sounds.Bell);
    private final Setting<Float> volume = new Setting<>("Volume", 1f, 0.1f, 2f);
    private final Setting<Float> pitch = new Setting<>("Pitch", 1f, 0.5f, 2f);
    /**
     * Silences the vanilla hit sounds so only this one is heard.
     *
     * <p>Without it a crit fires two sounds at once - the game's and ours - and they smear into each
     * other. The vanilla crit in particular is loud enough to bury whatever you picked here.
     */
    private final Setting<Boolean> muteVanilla = new Setting<>("MuteVanilla", true);
    /** Rises with each hit in a combo and falls back when you stop. 0 = a flat pitch every time. */
    private final Setting<Float> pitchRamp = new Setting<>("PitchRamp", 0f, 0f, 0.5f);

    private final Timer cooldown = new Timer();
    /** How far the ramp has climbed, and when it last moved. */
    private float ramp;
    private long lastHit;

    public HitSound() {
        super("HitSound", Category.COMBAT);
    }

    @EventHandler
    public void onAttack(EventAttack e) {
        if (fullNullCheck()) return;
        if (e.isPre() || e.getEntity() == null) return;
        if (!cooldown.passedMs(40)) return;
        cooldown.reset();
        if (mc.world == null) return;

        // The ramp decays on its own, so a combo climbs and a single hit two seconds later starts low.
        long now = System.currentTimeMillis();
        if (now - lastHit > 1200) ramp = 0f;
        lastHit = now;

        float shown = pitch.getValue() + ramp;
        ramp = Math.min(0.9f, ramp + pitchRamp.getValue());

        // Celestial: no hit sound for the axe swing that breaks a shield
        if (Kilaura.isBreaked) return;
        SoundEvent event = sound.getValue() == Sounds.Moan
                ? Sounds.values()[Sounds.Moan1.ordinal() + (int) (Math.random() * 4)].event
                : sound.getValue().event;
        mc.world.playSound(mc.player, mc.player.getBlockPos(), event,
                SoundCategory.PLAYERS, volume.getValue(), Math.min(2f, shown));
    }

    /**
     * Drops the vanilla hit sounds before they reach the sound engine.
     *
     * <p>They arrive from the server as a play-sound packet, so the cancel happens at the packet rather
     * than in the sound engine - the alternative is muting a whole category and losing everything else
     * in it too.
     */
    @EventHandler
    public void onPacketReceive(PacketEvent.Receive event) {
        if (isOff() || !muteVanilla.getValue() || mc.player == null) return;
        if (!(event.getPacket() instanceof PlaySoundS2CPacket packet)) return;

        SoundEvent incoming = packet.getSound().value();
        if (!isVanillaHit(incoming)) return;

        // Only ours: somebody else critting across the arena should still be audible.
        double distance = mc.player.getEntityPos().squaredDistanceTo(packet.getX(), packet.getY(), packet.getZ());
        if (distance > 36) return;

        event.cancel();
    }

    private static boolean isVanillaHit(SoundEvent sound) {
        return sound == SoundEvents.ENTITY_PLAYER_ATTACK_CRIT
                || sound == SoundEvents.ENTITY_PLAYER_ATTACK_STRONG
                || sound == SoundEvents.ENTITY_PLAYER_ATTACK_WEAK
                || sound == SoundEvents.ENTITY_PLAYER_ATTACK_KNOCKBACK
                || sound == SoundEvents.ENTITY_PLAYER_ATTACK_SWEEP
                || sound == SoundEvents.ENTITY_PLAYER_ATTACK_NODAMAGE;
    }

    public enum Sounds {
        Bell(SoundEvents.BLOCK_NOTE_BLOCK_BELL.value()),
        Pling(SoundEvents.BLOCK_NOTE_BLOCK_PLING.value()),
        Bit(SoundEvents.BLOCK_NOTE_BLOCK_BIT.value()),
        Orb(SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP),
        Anvil(SoundEvents.BLOCK_ANVIL_LAND),
        // extra pleasant ones
        Chime(SoundEvents.BLOCK_NOTE_BLOCK_CHIME.value()),
        Xylophone(SoundEvents.BLOCK_NOTE_BLOCK_XYLOPHONE.value()),
        IronXylophone(SoundEvents.BLOCK_NOTE_BLOCK_IRON_XYLOPHONE.value()),
        Harp(SoundEvents.BLOCK_NOTE_BLOCK_HARP.value()),
        Flute(SoundEvents.BLOCK_NOTE_BLOCK_FLUTE.value()),
        Guitar(SoundEvents.BLOCK_NOTE_BLOCK_GUITAR.value()),
        Banjo(SoundEvents.BLOCK_NOTE_BLOCK_BANJO.value()),
        Bass(SoundEvents.BLOCK_NOTE_BLOCK_BASS.value()),
        Cowbell(SoundEvents.BLOCK_NOTE_BLOCK_COW_BELL.value()),
        Didgeridoo(SoundEvents.BLOCK_NOTE_BLOCK_DIDGERIDOO.value()),
        Amethyst(SoundEvents.BLOCK_AMETHYST_BLOCK_CHIME),
        AmethystHit(SoundEvents.BLOCK_AMETHYST_BLOCK_HIT),
        AmethystResonate(SoundEvents.BLOCK_AMETHYST_BLOCK_RESONATE),
        LevelUp(SoundEvents.ENTITY_PLAYER_LEVELUP),
        // Softer, rounder options than the note blocks - these are the ones that sit well under a fight
        // rather than cutting through it.
        Bubble(SoundEvents.ENTITY_BOAT_PADDLE_WATER),
        Glass(SoundEvents.BLOCK_GLASS_HIT),
        Snow(SoundEvents.BLOCK_SNOW_HIT),
        Wool(SoundEvents.BLOCK_WOOL_HIT),
        Candle(SoundEvents.BLOCK_CANDLE_EXTINGUISH),
        Sculk(SoundEvents.BLOCK_SCULK_SENSOR_CLICKING),
        Chorus(SoundEvents.BLOCK_CHORUS_FLOWER_GROW),
        Beacon(SoundEvents.BLOCK_BEACON_ACTIVATE),
        Trident(SoundEvents.ITEM_TRIDENT_RETURN),
        Arrow(SoundEvents.ENTITY_ARROW_HIT_PLAYER),

        // The client's own sound pack, already shipped in the jar and declared in sounds.json.
        //
        // <p>Built straight from the identifier rather than read off Managers.SOUND. This enum is
        // initialised by HitSound's constructor, which runs inside ModuleManager's static init, which
        // runs inside Managers' - so Managers.SOUND is still null at that moment and touching it threw
        // an ExceptionInInitializerError that stopped the client starting. An Identifier depends on
        // nothing.
        Uwu(custom("uwu")),
        Cutie(custom("cutie")),
        Skeet(custom("skeet")),
        Moan1(custom("moan1")),
        Moan2(custom("moan2")),
        Moan3(custom("moan3")),
        Moan4(custom("moan4")),
        // Celestial HitSounds "Moan": a random one of the moans on every hit
        Moan(custom("moan1"));

        private static SoundEvent custom(String name) {
            return SoundEvent.of(net.minecraft.util.Identifier.of("gombal", name));
        }

        public final SoundEvent event;

        Sounds(SoundEvent event) {
            this.event = event;
        }
    }
}
