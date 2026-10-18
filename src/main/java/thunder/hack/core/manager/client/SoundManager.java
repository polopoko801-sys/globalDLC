package thunder.hack.core.manager.client;

import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.registry.SimpleRegistry;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.sound.SoundCategory;
import thunder.hack.injection.accesors.ISimpleRegistry;
import thunder.hack.injection.accesors.IRegistryEntryReference;
import net.minecraft.sound.SoundEvent;
import net.minecraft.util.Identifier;
import thunder.hack.core.manager.IManager;
import thunder.hack.core.Managers;
import thunder.hack.features.modules.client.SoundFX;
import thunder.hack.features.modules.misc.ChatUtils;
import thunder.hack.utility.Timer;
import thunder.hack.utility.math.MathUtility;

import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;
import javax.sound.sampled.FloatControl;
import java.io.File;

import static thunder.hack.features.cmd.Command.sendMessage;
import static thunder.hack.core.manager.client.ConfigManager.SOUNDS_FOLDER;
import static thunder.hack.features.modules.client.ClientSettings.isRu;

public class SoundManager implements IManager {
    public final Identifier KEYPRESS_SOUND = Identifier.of("gombal:keypress");
    public SoundEvent KEYPRESS_SOUNDEVENT = SoundEvent.of(KEYPRESS_SOUND);
    public final Identifier KEYRELEASE_SOUND = Identifier.of("gombal:keyrelease");
    public SoundEvent KEYRELEASE_SOUNDEVENT = SoundEvent.of(KEYRELEASE_SOUND);
    public final Identifier UWU_SOUND = Identifier.of("gombal:uwu");
    public SoundEvent UWU_SOUNDEVENT = SoundEvent.of(UWU_SOUND);
    public final Identifier ENABLE_SOUND = Identifier.of("gombal:enable");
    public SoundEvent ENABLE_SOUNDEVENT = SoundEvent.of(ENABLE_SOUND);
    public final Identifier DISABLE_SOUND = Identifier.of("gombal:disable");
    public SoundEvent DISABLE_SOUNDEVENT = SoundEvent.of(DISABLE_SOUND);
    public final Identifier MOAN1_SOUND = Identifier.of("gombal:moan1");
    public SoundEvent MOAN1_SOUNDEVENT = SoundEvent.of(MOAN1_SOUND);
    public final Identifier MOAN2_SOUND = Identifier.of("gombal:moan2");
    public SoundEvent MOAN2_SOUNDEVENT = SoundEvent.of(MOAN2_SOUND);
    public final Identifier MOAN3_SOUND = Identifier.of("gombal:moan3");
    public SoundEvent MOAN3_SOUNDEVENT = SoundEvent.of(MOAN3_SOUND);
    public final Identifier MOAN4_SOUND = Identifier.of("gombal:moan4");
    public SoundEvent MOAN4_SOUNDEVENT = SoundEvent.of(MOAN4_SOUND);
    public final Identifier SKEET_SOUND = Identifier.of("gombal:skeet");
    public SoundEvent SKEET_SOUNDEVENT = SoundEvent.of(SKEET_SOUND);
    public final Identifier ORTHODOX_SOUND = Identifier.of("gombal:orthodox");
    public SoundEvent ORTHODOX_SOUNDEVENT = SoundEvent.of(ORTHODOX_SOUND);
    public final Identifier BOOLEAN_SOUND = Identifier.of("gombal:boolean");
    public SoundEvent BOOLEAN_SOUNDEVENT = SoundEvent.of(BOOLEAN_SOUND);
    public final Identifier SCROLL_SOUND = Identifier.of("gombal:scroll");
    public SoundEvent SCROLL_SOUNDEVENT = SoundEvent.of(SCROLL_SOUND);
    public final Identifier SWIPEIN_SOUND = Identifier.of("gombal:swipein");
    public SoundEvent SWIPEIN_SOUNDEVENT = SoundEvent.of(SWIPEIN_SOUND);
    public final Identifier SWIPEOUT_SOUND = Identifier.of("gombal:swipeout");
    public SoundEvent SWIPEOUT_SOUNDEVENT = SoundEvent.of(SWIPEOUT_SOUND);
    public final Identifier ALERT_SOUND = Identifier.of("gombal:alert");
    public SoundEvent ALERT_SOUNDEVENT = SoundEvent.of(ALERT_SOUND);
    public final Identifier PM_SOUND = Identifier.of("gombal:pmsound");
    public SoundEvent PM_SOUNDEVENT = SoundEvent.of(PM_SOUND);
    public final Identifier RIFK_SOUND = Identifier.of("gombal:rifk");
    public SoundEvent RIFK_SOUNDEVENT = SoundEvent.of(RIFK_SOUND);
    public final Identifier CUTIE_SOUND = Identifier.of("gombal:cutie");
    public SoundEvent CUTIE_SOUNDEVENT = SoundEvent.of(CUTIE_SOUND);


    private final Timer scrollTimer = new Timer();

    public void registerSounds() {
        // Two runtime environments differ in whether registries are frozen during mod init:
        //  - Feather ships fabric-registry-sync, which keeps SOUND_EVENT unfrozen here and freezes it
        //    itself in afterModInit -> a plain register() works and sync binds the values. We must NOT
        //    touch the frozen flag, or sync's later freeze throws "Registry is already frozen".
        //  - Environments without registry-sync have SOUND_EVENT frozen since bootstrap, so we briefly
        //    unfreeze, register, manually bind each value (add() defers binding to freeze(), which we
        //    can't re-run here), then re-freeze.
        ISimpleRegistry reg = (ISimpleRegistry) (Object) Registries.SOUND_EVENT;
        boolean wasFrozen = Registries.SOUND_EVENT instanceof SimpleRegistry && reg.isFrozen();
        if (wasFrozen) reg.setFrozen(false);
        try {
            registerSoundsInternal(wasFrozen);
        } finally {
            if (wasFrozen) reg.setFrozen(true);
        }
    }

    @SuppressWarnings("unchecked")
    private void reg(Identifier id, SoundEvent event, boolean manualBind) {
        RegistryEntry.Reference<SoundEvent> ref = Registry.registerReference(Registries.SOUND_EVENT, id, event);
        // Only when we bypassed the real freeze() (unfrozen-by-us path) must we bind the value ourselves;
        // otherwise the registry's own freeze binds it.
        if (manualBind) ((IRegistryEntryReference<SoundEvent>) (Object) ref).thSetValue(event);
    }

    private void registerSoundsInternal(boolean manualBind) {
        reg(KEYPRESS_SOUND, KEYPRESS_SOUNDEVENT, manualBind);
        reg(KEYRELEASE_SOUND, KEYRELEASE_SOUNDEVENT, manualBind);
        reg(ENABLE_SOUND, ENABLE_SOUNDEVENT, manualBind);
        reg(DISABLE_SOUND, DISABLE_SOUNDEVENT, manualBind);
        reg(MOAN1_SOUND, MOAN1_SOUNDEVENT, manualBind);
        reg(MOAN2_SOUND, MOAN2_SOUNDEVENT, manualBind);
        reg(MOAN3_SOUND, MOAN3_SOUNDEVENT, manualBind);
        reg(MOAN4_SOUND, MOAN4_SOUNDEVENT, manualBind);
        reg(UWU_SOUND, UWU_SOUNDEVENT, manualBind);

        reg(SKEET_SOUND, SKEET_SOUNDEVENT, manualBind);
        reg(ORTHODOX_SOUND, ORTHODOX_SOUNDEVENT, manualBind);
        reg(SCROLL_SOUND, SCROLL_SOUNDEVENT, manualBind);
        reg(BOOLEAN_SOUND, BOOLEAN_SOUNDEVENT, manualBind);
        reg(SWIPEIN_SOUND, SWIPEIN_SOUNDEVENT, manualBind);
        reg(SWIPEOUT_SOUND, SWIPEOUT_SOUNDEVENT, manualBind);
        reg(ALERT_SOUND, ALERT_SOUNDEVENT, manualBind);
        reg(PM_SOUND, PM_SOUNDEVENT, manualBind);
        reg(RIFK_SOUND, RIFK_SOUNDEVENT, manualBind);
        reg(CUTIE_SOUND, CUTIE_SOUNDEVENT, manualBind);
    }

    public void playHitSound(SoundFX.HitSound value) {
        switch (value) {
            case UWU -> playSound(UWU_SOUNDEVENT);
            case SKEET -> playSound(SKEET_SOUNDEVENT);
            case KEYBOARD -> playSound(KEYPRESS_SOUNDEVENT);
            case CUTIE -> playSound(CUTIE_SOUNDEVENT);
            case MOAN -> {
                SoundEvent sound = switch ((int) (MathUtility.random(0, 3))) {
                    case 0 -> MOAN1_SOUNDEVENT;
                    case 1 -> MOAN2_SOUNDEVENT;
                    case 2 -> MOAN3_SOUNDEVENT;
                    default -> MOAN4_SOUNDEVENT;
                };
                playSound(sound);
            }
            case RIFK -> playSound(RIFK_SOUNDEVENT);
            case CUSTOM -> playSound("hit");
        }
    }

    public void playEnable() {
        if (ModuleManager.soundFX.enableMode.getValue() == SoundFX.OnOffSound.Inertia) {
            playSound(ENABLE_SOUNDEVENT);
        } else if (ModuleManager.soundFX.enableMode.getValue() == SoundFX.OnOffSound.Custom) {
            playSound("enable");
        }
    }

    public void playDisable() {
        if (ModuleManager.soundFX.disableMode.getValue() == SoundFX.OnOffSound.Inertia) {
            playSound(DISABLE_SOUNDEVENT);
        } else if (ModuleManager.soundFX.disableMode.getValue() == SoundFX.OnOffSound.Custom) {
            playSound("disable");
        }
    }

    public void playScroll() {
        if (scrollTimer.every(50)) {
            if (ModuleManager.soundFX.scrollSound.getValue() == SoundFX.ScrollSound.KeyBoard) {
                playSound(KEYPRESS_SOUNDEVENT);
            } else if (ModuleManager.soundFX.scrollSound.getValue() == SoundFX.ScrollSound.Custom) {
                playSound("scroll");
            }
        }
    }

    public void playSound(SoundEvent sound) {
        if (mc.player != null && mc.world != null)
            mc.world.playSound(mc.player, mc.player.getBlockPos(), sound, SoundCategory.BLOCKS, (float) ModuleManager.soundFX.volume.getValue() / 100f, 1f);
    }

    public void playSound(String name) {
        try {
            Clip clip = AudioSystem.getClip();
            clip.open(AudioSystem.getAudioInputStream(new File(SOUNDS_FOLDER, name + ".wav").getAbsoluteFile()));
            FloatControl floatControl = (FloatControl) clip.getControl(FloatControl.Type.MASTER_GAIN);
            floatControl.setValue((floatControl.getMaximum() - floatControl.getMinimum() * ((float) ModuleManager.soundFX.volume.getValue() / 100f)) + floatControl.getMinimum());
            clip.start();
        } catch (Exception e) {
            sendMessage((isRu() ? "Ошибка воспроизведения звука! Проверь " : "Error with playing sound! Check ") + new File(SOUNDS_FOLDER, name + ".wav").getAbsolutePath());
        }
    }

    public void playSlider() {
        playSound(SCROLL_SOUNDEVENT);
    }

    public void playBoolean() {
        playSound(BOOLEAN_SOUNDEVENT);
    }

    public void playSwipeIn() {
        playSound(SWIPEIN_SOUNDEVENT);
    }

    public void playSwipeOut() {
        playSound(SWIPEOUT_SOUNDEVENT);
    }

    public void playPmSound(ChatUtils.PMSound sound) {
        if (sound == ChatUtils.PMSound.Default) playSound(PM_SOUNDEVENT);
        else Managers.SOUND.playSound("pmsound");
    }
}
