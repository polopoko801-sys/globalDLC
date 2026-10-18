package thunder.hack.gui.mainmenu;

import net.minecraft.client.sound.MovingSoundInstance;
import net.minecraft.client.sound.SoundInstance;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.random.Random;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;

import static thunder.hack.features.modules.Module.mc;

/**
 * The sound of the scene behind the Customize screen: a looping landscape ambience picked from the wallpaper
 * - rain for night streets and rainy scenes, birds and wind for everything else.
 *
 * <p>Both loops are CC0 from OpenGameArt ("Birds and Wind - Ambient" by Spring Spring, "Dark Rainy Night" ambience).
 * They fade in and out rather than cutting, and the menu music is stopped while one plays so the two do not
 * fight.
 */
public final class MenuAmbience {
    private MenuAmbience() {
    }

    private static final SoundEvent BIRDS_WIND = SoundEvent.of(Identifier.of("gombal", "ambient.birds_wind"));
    private static final SoundEvent RAINY_NIGHT = SoundEvent.of(Identifier.of("gombal", "ambient.rainy_night"));

    private static @Nullable Loop playing;

    /** Starts (or switches to) the ambience for this wallpaper. */
    public static void play(@Nullable String wallpaper) {
        SoundEvent want = pick(wallpaper);
        if (playing != null && playing.event == want && !playing.fadingOut) return;

        if (playing != null) playing.fadeOut();
        mc.getMusicTracker().stop();
        playing = new Loop(want);
        mc.getSoundManager().play(playing);
    }

    /** Fades the ambience out; called when Customize closes. */
    public static void stop() {
        if (playing != null) playing.fadeOut();
        playing = null;
    }

    private static SoundEvent pick(@Nullable String wallpaper) {
        String n = wallpaper == null ? "" : wallpaper.toLowerCase(Locale.ROOT);
        if (n.contains("rain") || n.contains("alley") || n.contains("city") || n.contains("storm") || n.contains("night-drive"))
            return RAINY_NIGHT;
        return BIRDS_WIND;
    }

    /** A looping, non-positional sound whose volume eases in at the start and out at the end. */
    private static final class Loop extends MovingSoundInstance {
        private static final float TARGET = 0.55f;
        private static final float STEP = 0.025f; // per tick: about a second in, a second out

        final SoundEvent event;
        boolean fadingOut;

        Loop(SoundEvent event) {
            super(event, SoundCategory.AMBIENT, Random.create());
            this.event = event;
            this.repeat = true;
            this.repeatDelay = 0;
            this.volume = 0.01f;
            this.relative = true;
            this.attenuationType = SoundInstance.AttenuationType.NONE;
        }

        void fadeOut() {
            fadingOut = true;
        }

        @Override
        public void tick() {
            if (fadingOut) {
                volume -= STEP;
                if (volume <= 0.01f) setDone();
            } else if (volume < TARGET) {
                volume = Math.min(TARGET, volume + STEP);
            }
        }
    }
}
