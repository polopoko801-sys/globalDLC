package thunder.hack.features.cosmetics.render;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.client.sound.SoundInstance;
import net.minecraft.sound.SoundCategory;
import net.minecraft.util.Identifier;

/** One animation track's sound cursor. Call on the client thread with the same clock as its pose. */
public final class CosmeticSoundPlayer {
    interface Playback {
        SoundInstance play(String effect);
        void stop(SoundInstance sound);
        boolean isPlaying(SoundInstance sound);
    }

    private final Playback playback;
    private final List<SoundInstance> playing = new ArrayList<>();
    private BedrockAnimation.Clip clip;
    private boolean loop;
    private int cursor;
    private long cycle;
    private float previous = -1;

    public CosmeticSoundPlayer() {
        this(new Playback() {
            @Override public SoundInstance play(String effect) {
                MinecraftClient client = MinecraftClient.getInstance();
                if (client.player == null || client.world == null || client.getOverlay() != null) return null;
                // The authored event names are preserved; only the asset namespace changes.
                String name = effect.startsWith("evolution:") ? effect.substring("evolution:".length()) : effect;
                Identifier id = name.indexOf(':') >= 0 ? Identifier.tryParse(name) : Identifier.tryParse("bkclient:" + name);
                if (id == null || client.getSoundManager().get(id) == null) return null;
                SoundInstance sound = new PositionedSoundInstance(id, SoundCategory.PLAYERS, 1, 1,
                    SoundInstance.createRandom(), false, 0, SoundInstance.AttenuationType.LINEAR,
                    client.player.getX(), client.player.getY(), client.player.getZ(), false);
                client.getSoundManager().play(sound);
                return sound;
            }
            @Override public void stop(SoundInstance sound) {
                MinecraftClient.getInstance().getSoundManager().stop(sound);
            }
            @Override public boolean isPlaying(SoundInstance sound) {
                return MinecraftClient.getInstance().getSoundManager().isPlaying(sound);
            }
        });
    }

    CosmeticSoundPlayer(Playback playback) { this.playback = playback; }

    public void start(BedrockAnimation.Clip clip) { start(clip, clip != null && clip.loop()); }

    /** Replacing even the same clip interrupts its previous sounds and restarts the cue cursor. */
    public void start(BedrockAnimation.Clip clip, boolean loop) {
        stop();
        this.clip = clip;
        this.loop = loop;
    }

    /**
     * Absolute, unwrapped seconds since start. Freeze this clock while paused; Minecraft pauses
     * active sounds itself. A backward seek resets the cursor and cancels the old sounds.
     */
    public void update(float elapsedSeconds) {
        if (clip == null || !Float.isFinite(elapsedSeconds)) return;
        float elapsed = Math.max(0, elapsedSeconds);
        if (elapsed == previous) return;
        if (elapsed < previous) {
            stopSounds();
            cursor = 0;
            cycle = 0;
        }
        playing.removeIf(sound -> !playback.isPlaying(sound));
        float duration = clip.duration();
        if (loop && duration > 0) {
            long nextCycle = (long) Math.floor((double) elapsed / duration);
            if (nextCycle > cycle) {
                fireThrough(duration);
                cursor = 0;
                // Like the source player, skip fully missed cycles after a stall instead of
                // playing a burst of every sound accumulated while no frames were rendered.
            }
            cycle = nextCycle;
            fireThrough(elapsed % duration);
        } else {
            fireThrough(Math.min(elapsed, duration));
        }
        previous = elapsed;
    }

    /** Cancel on unequip, interruption, disconnect, or clock reset. */
    public void stop() {
        stopSounds();
        clip = null;
        cursor = 0;
        cycle = 0;
        previous = -1;
    }

    private void fireThrough(float time) {
        List<BedrockAnimation.SoundCue> cues = clip.soundCues();
        while (cursor < cues.size() && cues.get(cursor).time() <= time) {
            SoundInstance sound = playback.play(cues.get(cursor++).effect());
            if (sound != null) playing.add(sound);
        }
    }

    private void stopSounds() {
        for (SoundInstance sound : playing) playback.stop(sound);
        playing.clear();
    }
}
