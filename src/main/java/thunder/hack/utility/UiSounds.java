package thunder.hack.utility;

import thunder.hack.core.manager.client.ModuleManager;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;
import javax.sound.sampled.FloatControl;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The interface's own sounds: short synthesised chimes and slides bundled in {@code assets/gombal/ui}.
 *
 * <p>Played through javax.sound rather than the game's sound engine, so they work on the title screen and in
 * menus where there is no world (and no player) to play a world sound at. Each file is read from the jar once
 * and kept as raw PCM; playing it is a fresh {@link Clip} on a background thread, so a click never waits on
 * audio. Volume and the on/off switch live in the ClientSound module.
 */
public final class UiSounds {
    private UiSounds() {
    }

    public enum Sound {
        GUI_OPEN("gui_open"), GUI_CLOSE("gui_close"), SLIDE_IN("slide_in"), SLIDE_OUT("slide_out"),
        TAP("tap"), ISLAND("island");

        final String file;

        Sound(String file) {
            this.file = file;
        }
    }

    private record Pcm(AudioFormat format, byte[] data) {
    }

    private static final Map<Sound, Pcm> CACHE = new ConcurrentHashMap<>();
    private static final ExecutorService POOL = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Gombal-UiSounds");
        t.setDaemon(true);
        return t;
    });

    public static void play(Sound sound) {
        play(sound, 1f);
    }

    /** @param gain a multiplier on top of ClientSound's Volume - below 1 for the lightest touches, like switching category */
    public static void play(Sound sound, float gain) {
        try {
            var cs = ModuleManager.clientSound;
            if (cs == null || !cs.isEnabled()) return;
            if (sound == Sound.ISLAND ? !cs.island.getValue() : !cs.gui.getValue()) return;
            float volume = cs.volume.getValue() / 100f * gain;
            if (volume <= 0.01f) return;
            POOL.execute(() -> playNow(sound, volume));
        } catch (Throwable ignored) {
            // sound is decoration - never let it break a click
        }
    }

    private static void playNow(Sound sound, float volume) {
        try {
            Pcm pcm = CACHE.computeIfAbsent(sound, UiSounds::load);
            if (pcm == null) return;
            Clip clip = AudioSystem.getClip();
            clip.open(pcm.format(), pcm.data(), 0, pcm.data().length);
            if (clip.isControlSupported(FloatControl.Type.MASTER_GAIN)) {
                FloatControl gain = (FloatControl) clip.getControl(FloatControl.Type.MASTER_GAIN);
                float db = (float) (20.0 * Math.log10(Math.max(0.0001, volume)));
                gain.setValue(Math.max(gain.getMinimum(), Math.min(gain.getMaximum(), db)));
            }
            // free the line once it has finished, or every click would leak one
            clip.addLineListener(e -> {
                if (e.getType() == javax.sound.sampled.LineEvent.Type.STOP) clip.close();
            });
            clip.start();
        } catch (Throwable ignored) {
            // no audio device, or the mixer is busy - silently skip
        }
    }

    private static Pcm load(Sound sound) {
        try (InputStream raw = UiSounds.class.getResourceAsStream("/assets/gombal/ui/" + sound.file + ".wav")) {
            if (raw == null) return null;
            try (AudioInputStream in = AudioSystem.getAudioInputStream(new ByteArrayInputStream(raw.readAllBytes()))) {
                return new Pcm(in.getFormat(), in.readAllBytes());
            }
        } catch (Throwable t) {
            return null;
        }
    }
}
