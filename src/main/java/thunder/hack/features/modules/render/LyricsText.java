package thunder.hack.features.modules.render;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.util.math.Vec3d;
import thunder.hack.features.modules.Module;
import thunder.hack.features.modules.client.HudEditor;
import thunder.hack.setting.Setting;
import thunder.hack.setting.impl.ColorSetting;
import thunder.hack.utility.NowPlaying;
import thunder.hack.utility.spotify.Lyrics;
import thunder.hack.utility.spotify.SpotifyAuth;
import thunder.hack.utility.spotify.SpotifyPlayer;
import thunder.hack.utility.render.Render3DEngine;

import java.awt.Color;
import java.util.Random;

/**
 * The words of the song playing on Spotify, written into the world in big Minecraft lettering - one line at
 * a time, each pinned to a random spot in your field of view, each word sliding in as it is sung. The text
 * stays put in the world as you move but always keeps the same size on screen.
 *
 * <p>Uses the same synced lyrics as the music widget ({@link Lyrics}, fetched per track from lrclib). The
 * playback position comes from the Spotify API when logged in, otherwise from the Windows media session
 * ({@link thunder.hack.utility.spotify.MediaSession}) - exact either way, so starting it mid-song shows the
 * right line at once. Lines without timestamps are not shown: with no clock to follow they would be guesses.
 */
public class LyricsText extends Module {
    public LyricsText() {
        super("TextInMinecraft", Category.RENDER);
    }

    private enum ColorMode {White, Sync, Custom}

    /**
     * How a line moves. The words always arrive one by one, but with {@link #Wave} the line also keeps
     * breathing for as long as it is on screen - which is what makes it read as animated rather than as a
     * caption that was simply put there.
     */
    private enum Anim {
        /** The line lifts into place as it fades up. */
        Slide,
        /** Each word pops out of nothing and settles at full size. */
        Zoom,
        /** A slow bob per word, each a moment behind its neighbour. */
        Wave,
        /** Nothing moves; the words just appear. */
        None
    }

    /** Size on screen - fixed, it does not change with distance. */
    private final Setting<Float> scale = new Setting<>("Scale", 3f, 1f, 6f);
    /** How far in front of you a new line is placed, in blocks. */
    private final Setting<Float> distance = new Setting<>("Distance", 8f, 3f, 30f);
    /** How far from the centre of the view a line may land: 0 = dead centre, 1 = the edges of the view. */
    private final Setting<Float> spread = new Setting<>("Spread", 0.7f, 0f, 1f);
    private final Setting<Boolean> wordByWord = new Setting<>("WordByWord", true);
    /** Wave by default: a still line is what "no animation" looked like. */
    private final Setting<Anim> animation = new Setting<>("Animation", Anim.Wave);
    private final Setting<Boolean> shadow = new Setting<>("Shadow", true);
    private final Setting<ColorMode> colorMode = new Setting<>("ColorMode", ColorMode.White);
    private final Setting<ColorSetting> color = new Setting<>("Color", new ColorSetting(Color.WHITE.getRGB()), v -> colorMode.is(ColorMode.Custom));
    /** A small dim line at the top saying why nothing is shown (nothing playing, searching, no synced text). */
    private final Setting<Boolean> showStatus = new Setting<>("Status", true);
    /** Shifts the lyrics against the music, for a feed that runs early or late. */
    private final Setting<Integer> offset = new Setting<>("Offset", 0, -2000, 2000);

    /** How long one line takes to fade out once the next begins. */
    private static final long FADE_MS = 220L;
    /** How long a single word takes to arrive. */
    private static final long WORD_MS = 180L;
    /** How long the whole line takes to arrive. */
    private static final long ENTER_MS = 420L;

    /** The line on screen: its text, timing and the spot in the world it was pinned to. */
    private String lastLine = "";
    private long lastLineStart, lastLineEnd;
    private Vec3d anchor;
    private String anchorKey = "";

    private final Random random = new Random();

    @Override
    public void onRender2D(DrawContext context) {
        if (fullNullCheck()) return;

        String title, artist, trackId;
        long duration, position;
        if (SpotifyAuth.authorised()) {
            // keeps the poller awake while the lyrics are on screen
            SpotifyPlayer.touch(1000L);
            SpotifyPlayer.Snapshot track = SpotifyPlayer.state();
            if (!track.present()) {
                status(context, "Spotify nic nie gra");
                return;
            }
            title = track.title();
            artist = track.artist();
            trackId = track.trackId();
            duration = track.durationMs();
            position = track.progressMs();
        } else {
            // Not logged in: the track comes from the Spotify window title, which has no playback position.
            // The clock starts when the title changes, i.e. when the song started - right as long as you did
            // not skip into the middle of it (Offset fixes the rest).
            // The shared TitleClock does the polling, the song-change detection and the pause handling.
            NowPlaying.Track track = thunder.hack.utility.spotify.TitleClock.current();
            if (track == null) {
                status(context, "Nie widzę Spotify - odpal piosenkę w aplikacji Spotify albo zaloguj się (@spotify login)");
                return;
            }
            title = track.title();
            artist = track.artist();
            trackId = thunder.hack.utility.spotify.TitleClock.key();
            duration = thunder.hack.utility.spotify.TitleClock.duration();
            position = thunder.hack.utility.spotify.TitleClock.position();
        }

        Lyrics.Track lyrics = Lyrics.get(title, artist, duration);


        if (lyrics == null || !lyrics.synced()) {
            // only the short "searching" hint; when nothing was found the screen simply stays clean
            if (lyrics == null && Lyrics.unavailable(title, artist))
                status(context, "Serwer z tekstami (lrclib) nie odpowiada - ponawiam co 10 s");
            else if (lyrics == null && Lyrics.pending(title, artist))
                status(context, "Szukam tekstu: " + artist + " - " + title);
            return;
        }

        long now = position + offset.getValue();
        int idx = lyrics.indexAt(now);

        if (idx >= 0) {
            Lyrics.Line line = lyrics.lines().get(idx);
            long end = idx + 1 < lyrics.lines().size() ? lyrics.lines().get(idx + 1).atMs() : line.atMs() + 5000L;
            if (!line.text().isBlank()) {
                // a new line gets a new random spot in front of you; it stays there for its whole life
                String key = trackId + "#" + idx;
                if (!key.equals(anchorKey) || anchor == null) {
                    anchorKey = key;
                    anchor = pickSpot();
                }
                draw(context, line.text(), anchor, now - line.atMs(), end - line.atMs(), 1f);
                lastLine = line.text();
                lastLineStart = line.atMs();
                lastLineEnd = end;
                return;
            }
        }

        // an instrumental gap (blank line) - let the previous line fade instead of vanishing
        long since = now - lastLineEnd;
        if (!lastLine.isEmpty() && anchor != null && since >= 0 && since < FADE_MS)
            draw(context, lastLine, anchor, now - lastLineStart, lastLineEnd - lastLineStart, 1f - since / (float) FADE_MS);
    }

    /**
     * A random point in the world inside the current field of view, {@link #distance} blocks out. Turned off
     * the look direction by up to half the FOV (scaled by {@link #spread}) - less vertically, since the view
     * is wider than it is tall.
     */
    private Vec3d pickSpot() {
        Vec3d eye = mc.getEntityRenderDispatcher().camera.getCameraPos();
        float fov = mc.options.getFov().getValue();
        float yaw = mc.player.getYaw() + (random.nextFloat() * 2f - 1f) * fov * 0.5f * spread.getValue();
        float pitch = mc.player.getPitch() + (random.nextFloat() * 2f - 1f) * fov * 0.28f * spread.getValue();
        pitch = Math.clamp(pitch, -89f, 89f);
        return eye.add(Vec3d.fromPolar(pitch, yaw).multiply(distance.getValue()));
    }

    /**
     * One line, centred on where its world spot lands on screen. The size never depends on distance - walk
     * up to it or away from it and the lettering stays exactly as big; only its place on screen moves.
     *
     * @param elapsed  ms since the line started
     * @param duration ms until the next line starts
     */
    private void draw(DrawContext context, String text, Vec3d spot, long elapsed, long duration, float fade) {
        Vec3d screen = Render3DEngine.worldSpaceToScreenSpace(spot);
        if (!(screen.z > 0 && screen.z < 1)) return;   // behind you

        var font = mc.textRenderer;
        float sw = mc.getWindow().getScaledWidth();

        // never wider than the screen: a long line shrinks instead of running off the sides
        float s = Math.min(scale.getValue(), sw * 0.92f / Math.max(1, font.getWidth(text)));
        float width = font.getWidth(text) * s;
        float x = (float) screen.x - width / 2f;
        float y = (float) screen.y - font.fontHeight * s / 2f;

        // the last bit of every line fades into the next
        float out = Math.clamp((duration - elapsed) / (float) FADE_MS, 0f, 1f);
        float alpha = fade * out;
        if (alpha <= 0.02f) return;

        Anim anim = animation.getValue();

        // The whole line arrives together: it fades up and (Slide) lifts into place, so a new lyric is never
        // just there from one frame to the next.
        float enter = anim == Anim.None ? 1f : easeOutCubic(Math.clamp(elapsed / (float) ENTER_MS, 0f, 1f));
        // ...and it leaves upward as it fades, so the next line takes its place instead of cutting it off.
        float lineRise = (anim == Anim.Slide ? (1f - enter) * 9f : 0f) + (1f - out) * 7f;
        float lineScale = anim == Anim.Zoom ? 0.9f + 0.1f * enter : 1f;

        String[] words = text.split(" ");
        // the words arrive over the first half of the line, so even the last one lands while it is being sung
        long spread = Math.max(1L, Math.min(duration / 2, 2500L));
        // the bob runs off the wall clock, not the song: a paused track should not freeze the motion
        long clock = System.currentTimeMillis();

        var matrices = context.getMatrices();
        float cursor = 0f;
        for (int i = 0; i < words.length; i++) {
            String word = i < words.length - 1 ? words[i] + " " : words[i];
            float wordWidth = font.getWidth(word);

            float reveal = 1f;
            if (wordByWord.getValue()) {
                long start = spread * i / words.length;
                reveal = Math.clamp((elapsed - start) / (float) WORD_MS, 0f, 1f);
            }
            if (reveal <= 0f) {
                cursor += wordWidth;   // not sung yet - its space on the line is still reserved
                continue;
            }

            // Zoom pops the word out of nothing with a little overshoot; the others simply fade it up
            float pop = anim == Anim.Zoom ? 0.7f + 0.3f * easeOutBack(reveal) : 1f;
            // a word still on its way up from below, when the line arrives one word at a time
            float rise = wordByWord.getValue() ? (1f - easeOutCubic(reveal)) * 7f : 0f;
            // the slow bob that keeps a finished line moving - each word a moment behind the one before it
            float bob = anim == Anim.Wave ? (float) Math.sin(clock / 420.0 + i * 0.85) * 2.4f : 0f;

            int a = Math.round(255 * Math.clamp(alpha * reveal * enter, 0f, 1f));
            if (a > 4) {
                // drawn around the word's own centre, so Zoom grows outwards instead of to the right
                float wordScale = s * lineScale * pop;
                matrices.pushMatrix();
                matrices.translate(x + (cursor + wordWidth / 2f) * s, y + lineRise + rise + bob);
                matrices.scale(wordScale, wordScale);
                context.drawText(font, word, Math.round(-wordWidth / 2f), 0,
                        (a << 24) | (colorFor(i) & 0xFFFFFF), shadow.getValue());
                matrices.popMatrix();
            }
            cursor += wordWidth;
        }
    }

    private static float easeOutCubic(float t) {
        float u = 1f - t;
        return 1f - u * u * u;
    }

    /** Overshoots a little past 1 before settling - the snap that makes a word pop instead of appear. */
    private static float easeOutBack(float t) {
        float c1 = 1.9f, c3 = c1 + 1f;
        float u = t - 1f;
        return 1f + c3 * u * u * u + c1 * u * u;
    }

    private void status(DrawContext context, String text) {
        if (!showStatus.getValue()) return;
        var font = mc.textRenderer;
        String line = "TextInMinecraft: " + text;
        float sw = mc.getWindow().getScaledWidth();
        context.drawText(font, line, Math.round((sw - font.getWidth(line)) / 2f), 6, 0xB0FFFFFF, true);
    }

    private int colorFor(int index) {
        return switch (colorMode.getValue()) {
            case White -> 0xFFFFFF;
            case Sync -> HudEditor.getColor(index * 12).getRGB();
            case Custom -> color.getValue().getColor();
        };
    }
}
