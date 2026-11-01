package thunder.hack.utility;

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
 * The client's own synthesised sounds ({@code assets/gombal/hitsounds/*.wav}) - generated in code, nothing from
 * Minecraft. Played through javax.sound like {@link UiSounds}: read once, kept as PCM, each play a fresh Clip on a
 * background thread. Pitch is done by playing the same samples at a different sample rate.
 */
public final class SynthSounds {
    private SynthSounds() {
    }

    private record Pcm(AudioFormat format, byte[] data) {
    }

    private static final Map<String, Pcm> CACHE = new ConcurrentHashMap<>();
    private static final ExecutorService POOL = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Gombal-SynthSounds");
        t.setDaemon(true);
        return t;
    });

    /** @param volume 0..2 (1 = as recorded), @param pitch 0.5..2 */
    public static void play(String name, float volume, float pitch) {
        if (volume <= 0.01f) return;
        POOL.execute(() -> {
            try {
                Pcm pcm = CACHE.computeIfAbsent(name, SynthSounds::load);
                if (pcm == null) return;
                AudioFormat f = pcm.format();
                AudioFormat shifted = new AudioFormat(f.getEncoding(), f.getSampleRate() * Math.max(0.5f, Math.min(2f, pitch)),
                        f.getSampleSizeInBits(), f.getChannels(), f.getFrameSize(), f.getFrameRate() * Math.max(0.5f, Math.min(2f, pitch)), f.isBigEndian());
                Clip clip = AudioSystem.getClip();
                clip.open(shifted, pcm.data(), 0, pcm.data().length);
                if (clip.isControlSupported(FloatControl.Type.MASTER_GAIN)) {
                    FloatControl gain = (FloatControl) clip.getControl(FloatControl.Type.MASTER_GAIN);
                    float db = (float) (20.0 * Math.log10(Math.max(0.0001, volume)));
                    gain.setValue(Math.max(gain.getMinimum(), Math.min(gain.getMaximum(), db)));
                }
                clip.addLineListener(e -> {
                    if (e.getType() == javax.sound.sampled.LineEvent.Type.STOP) clip.close();
                });
                clip.start();
            } catch (Throwable ignored) {
                // no audio device / mixer busy - a hit sound is decoration
            }
        });
    }

    private static Pcm load(String name) {
        try (InputStream raw = SynthSounds.class.getResourceAsStream("/assets/gombal/hitsounds/" + name + ".wav")) {
            if (raw == null) return null;
            try (AudioInputStream in = AudioSystem.getAudioInputStream(new ByteArrayInputStream(raw.readAllBytes()))) {
                return new Pcm(in.getFormat(), in.readAllBytes());
            }
        } catch (Throwable t) {
            return null;
        }
    }
}
