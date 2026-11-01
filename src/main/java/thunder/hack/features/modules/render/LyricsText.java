package thunder.hack.features.modules.render;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.util.math.Vec3d;
import thunder.hack.features.modules.Module;
import thunder.hack.setting.Setting;
import thunder.hack.utility.spotify.SpotifyCard;
import thunder.hack.utility.render.Render3DEngine;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * Live karaoke in the world: the line Spotify is singing right now is pinned to a spot in front of you, and its
 * words light up one by one as they are sung.
 *
 * <p><b>Where everything comes from.</b> Nothing is downloaded. {@link SpotifyCard} keeps a small, invisible
 * Spotify window open on its lyrics view and reports the line Spotify highlights, the moment the highlight
 * moves, and whether it is playing - so every line starts exactly when Spotify says it does. Spotify does not
 * time single words, so inside a line each word gets a start from its syllables, at a pace learned from how long
 * this song's lines really last.
 */
public class LyricsText extends Module {
    public LyricsText() {
        super("TextInMinecraft", Category.RENDER);
    }

    private enum Style {Outline, Shadow}

    /** Size on screen - fixed, it does not change with distance. */
    private final Setting<Float> scale = new Setting<>("Scale", 2.6f, 1f, 6f);
    /** How far in front of you a new line is placed, in blocks. */
    private final Setting<Float> distance = new Setting<>("Distance", 8f, 3f, 30f);
    /** How far from the centre of the view a line may land: 0 = dead centre, 1 = the edges of the view. */
    private final Setting<Float> spread = new Setting<>("Spread", 0.55f, 0f, 1f);
    /** How long before its moment a word shows up. */
    private final Setting<Integer> lead = new Setting<>("Lead", 350, 0, 1500);
    /** Shifts everything against Spotify's clock, for a recording that runs early or late. */
    private final Setting<Integer> offset = new Setting<>("Offset", 0, -1000, 1000);
    /** Reveal the words one by one, or the whole line at once. */
    private final Setting<Boolean> wordByWord = new Setting<>("Words", true);
    private final Setting<Style> style = new Setting<>("Style", Style.Outline);
    /** A small dim line at the top saying why nothing is shown. */
    private final Setting<Boolean> showStatus = new Setting<>("Status", true);

    /** Fade-in of a new word, and the gentle grow that comes with it. */
    private static final float IN_MS = 180f;
    /** Fade-out of a finished line. */
    private static final float OUT_MS = 300f;
    /** How long a line stays after the last one, when the lyrics end before the track does. */
    private static final long TAIL_MS = 4000L;
    /** The shortest gap between two words of one line. */
    private static final long MIN_WORD_MS = 70L;

    /** One word of a line: its text, when it is sung (ms in the song) and when it actually appeared (wall clock). */
    private static final class Word {
        final String text;
        final long atMs;
        long appearedAt;

        Word(String text, long atMs) {
            this.text = text;
            this.atMs = atMs;
        }
    }

    /** A line on screen: its words, the spot in the world it is pinned to, and when it is sung. */
    private record Line(List<Word> words, Vec3d anchor, long startMs, long endMs) {
    }

    /** The line Spotify highlights: its index in the song (-1 before the first one) and its text. */
    private int shownIndex = -1;
    private String shownText = "";
    /** When Spotify confirmed the current line, and how many syllables it has - for learning the pace. */
    private long confirmedSince;
    private int confirmedSyllables;
    /** The line on screen - one ahead of {@link #shownIndex} while the next line is put up early. */
    private int displayIndex = -1;
    /** Playing time since the line started, in ms - stands still while Spotify is paused. */
    private float lineClock;
    private long lastFrame;
    /** Measured length of one syllable, learned from how long the lines that went by really lasted. */
    private float syllableMs = DEFAULT_SYLLABLE_MS;
    private static final float DEFAULT_SYLLABLE_MS = 150f, MIN_SYLLABLE_MS = 70f, MAX_SYLLABLE_MS = 380f;
    /** The line was sung out and has gone - it must not come back until the next one starts. */
    private boolean finished;
    private Line line;
    private Line leaving;
    private long leavingAt;

    private final Random random = new Random();

    @Override
    public void onEnable() {
        shownIndex = -1;
        shownText = "";
        finished = false;
        line = null;
        leaving = null;
        lastFrame = 0L;
        syllableMs = DEFAULT_SYLLABLE_MS;
        // start the reader (and its small hidden Spotify window) right away, not only once a world is drawn
        SpotifyCard.state();
    }

    @Override
    public void onRender2D(DrawContext context) {
        if (fullNullCheck()) return;

        long now = System.currentTimeMillis();
        float dt = lastFrame == 0L ? 0f : Math.min(250f, now - lastFrame);
        lastFrame = now;

        // The text comes live from the small Spotify window the reader keeps open (SpotifyCard): nothing is
        // downloaded, the line is the one Spotify highlights at this moment.
        SpotifyCard.State state = source();
        String message = switch (state.status()) {
            case Unknown -> "Uruchamiam odczyt Spotify...";
            case NoWindow, NoLyrics -> "Ładuję tekst ze Spotify...";
            case NoText -> "Spotify nie ma tekstu do tej piosenki";
            case Intro, Line -> null;
        };
        if (message != null) status(context, message);

        String text = state.status() == SpotifyCard.Status.Line ? clean(state.line()) : "";
        if (!text.isEmpty()) {
            if (state.index() != shownIndex || !state.line().equals(shownText)) {
                // Spotify moved its highlight: this line is confirmed, and the gap since the last confirmation is
                // how long that line really lasted - the pace is learned from that.
                learn(state.index(), state.since());
                shownIndex = state.index();
                shownText = state.line();
                confirmedSince = state.since();
                confirmedSyllables = syllablesOf(text);
                float clock = Math.max(0f, now - state.since()) + offset.getValue();
                if (displayIndex == state.index() && line != null) {
                    // shown ahead of time already: keep it as it is, only line the clock up with Spotify
                    lineClock = Math.max(lineClock, clock);
                } else {
                    displayIndex = state.index();
                    finished = false;
                    lineClock = clock;
                    // exact source: the words are spread over the line's real length instead of a guessed pace
                    startLine(text, now, exactLen > 0
                            ? Math.clamp(exactLen * 0.88f / (syllablesOf(text) + 1f), MIN_SYLLABLE_MS, MAX_SYLLABLE_MS)
                            : syllableMs);
                }
            } else if (exactLen > 0) {
                // Exact source (lrclib + the real position): the clock IS the position in the line, every frame -
                // it cannot drift, pauses and seeks are followed, and the next line is never guessed early; it
                // comes when the song really gets there.
                lineClock = exactPos + offset.getValue();
            } else if (state.playing()) {
                lineClock += dt;
                // The next line is known before Spotify highlights it. Once this one is about to run out (at the
                // learned pace), put the next one up a lead early instead of waiting - otherwise every line would
                // start late by however long the highlight takes to be seen.
                String next = clean(state.next());
                if (displayIndex == shownIndex && line != null && !next.isEmpty()
                        && lineClock >= line.endMs() - lead.getValue()) {
                    float into = lineClock - line.endMs();
                    displayIndex = shownIndex + 1;
                    finished = false;
                    startLine(next, now);
                    lineClock = into;
                }
            }
            long position = Math.round(lineClock);
            if (line != null && !finished && position > line.endMs() + TAIL_MS) {
                // sung out and the next line is still far off: let it go, and keep it gone
                retire(now);
                finished = true;
            }
            if (line != null && !finished) reveal(position, now);
        } else {
            // intro, a gap between lines, no lyrics: the line on screen goes
            if (state.status() == SpotifyCard.Status.Line) learn(state.index(), state.since());
            shownIndex = state.status() == SpotifyCard.Status.Line ? state.index() : -1;
            shownText = "";
            confirmedSince = state.since();
            confirmedSyllables = 0;
            // a line put up early for a pause that came instead goes too
            displayIndex = shownIndex;
            retire(now);
        }

        drawLeaving(context, now);
        if (line != null) drawLine(context, line, now, 1f, 0f);
    }

    /* ------------------------------------------------------------------ */
    /* Source                                                              */
    /* ------------------------------------------------------------------ */

    /**
     * Where the current line comes from. First the same source the menu's music card uses - the track and the
     * exact position from Spotify (logged in) or the Windows media session / window title, and the synced lines
     * from lrclib - which is the one that works reliably. The hidden Spotify lyrics window ({@link SpotifyCard})
     * is only the fallback for songs lrclib does not have.
     */
    private SpotifyCard.State source() {
        exactLen = 0L;
        exactPos = 0L;
        SpotifyCard.State synced = fromLrclib();
        return synced != null ? synced : SpotifyCard.state();
    }

    /** From the exact source: how long the current line really lasts (0 = unknown) and how far into it we are. */
    private long exactLen, exactPos;

    /** The menu card's source, shaped like {@link SpotifyCard.State}; null when it has nothing for this song. */
    private SpotifyCard.State fromLrclib() {
        String title, artist;
        long position, length;
        boolean playing;
        try {
            if (thunder.hack.utility.spotify.SpotifyAuth.authorised()) {
                thunder.hack.utility.spotify.SpotifyPlayer.touch(1000L);
                var snap = thunder.hack.utility.spotify.SpotifyPlayer.state();
                if (!snap.present()) return null;
                title = snap.title();
                artist = snap.artist();
                position = snap.progressMs();
                length = snap.durationMs();
                playing = snap.playing();
            } else {
                var track = thunder.hack.utility.spotify.TitleClock.current();
                if (track == null) return null;
                title = track.title();
                artist = track.artist();
                position = thunder.hack.utility.spotify.TitleClock.position();
                length = thunder.hack.utility.spotify.TitleClock.duration();
                playing = thunder.hack.utility.spotify.TitleClock.playing();
            }
        } catch (Throwable t) {
            return null;
        }
        var lyrics = thunder.hack.utility.spotify.Lyrics.get(title, artist, length);
        if (lyrics == null || !lyrics.synced() || lyrics.lines().isEmpty()) return null;

        long now = System.currentTimeMillis();
        var lines = lyrics.lines();
        int idx = lyrics.indexAt(position);
        if (idx < 0) // before the first line: intro, the first line is next
            return new SpotifyCard.State(SpotifyCard.Status.Intro, -1, "", lines.get(0).text(), now - position, playing);
        var cur = lines.get(idx);
        String next = idx + 1 < lines.size() ? lines.get(idx + 1).text() : "";
        exactLen = idx + 1 < lines.size() ? Math.max(300L, lines.get(idx + 1).atMs() - cur.atMs()) : 5000L;
        exactPos = Math.max(0L, position - cur.atMs());
        // since = the wall-clock moment this line started, worked back from the exact position in the song
        return new SpotifyCard.State(SpotifyCard.Status.Line, idx, cur.text(), next, now - (position - cur.atMs()), playing);
    }

    /* ------------------------------------------------------------------ */
    /* Timing                                                              */
    /* ------------------------------------------------------------------ */

    /**
     * Learns how long a syllable lasts from the line that just ended: the real time between Spotify highlighting
     * it and highlighting the next, over its syllables. A line followed by an instrumental break lasts far longer
     * than its words and would teach a crawl, so anything well past the current estimate is left out.
     */
    private void learn(int nextIndex, long nextSince) {
        if (shownIndex < 0 || nextIndex != shownIndex + 1 || confirmedSyllables == 0 || confirmedSince == 0L) return;
        float perSyllable = (nextSince - confirmedSince) / (confirmedSyllables + 1f);
        if (perSyllable <= 0f || perSyllable > syllableMs * 2.2f) return;
        syllableMs += (Math.clamp(perSyllable, MIN_SYLLABLE_MS, MAX_SYLLABLE_MS) - syllableMs) * 0.5f;
    }

    private static String clean(String text) {
        return text == null ? "" : text.replaceAll("[♪♫]", "").strip();
    }

    private static int syllablesOf(String text) {
        int n = 0;
        for (String t : text.split("\\s+")) if (!t.isEmpty()) n += syllables(t);
        return n;
    }

    /**
     * Lays the line out: where it hangs in the world and when each word is sung, in ms from the line's start -
     * spread by syllables at the pace learned from this song.
     */
    private void startLine(String text, long now) {
        startLine(text, now, syllableMs);
    }

    /** @param pace length of one syllable for this line, in ms */
    private void startLine(String text, long now, float pace) {
        float syllableMs = pace;
        retire(now);
        String[] tokens = text.split("\s+");
        List<Word> words = new ArrayList<>(tokens.length);
        long at = 0;
        for (String token : tokens) {
            if (token.isEmpty()) continue;
            long wordAt = words.isEmpty() ? 0L : Math.max(Math.round(at * syllableMs), words.get(words.size() - 1).atMs + MIN_WORD_MS);
            words.add(new Word(token, wordAt));
            at += syllables(token);
        }
        if (words.isEmpty()) {
            line = null;
            return;
        }
        long end = Math.round(at * syllableMs);
        line = new Line(words, pickSpot(), 0L, end);
    }

    /** Shows the words whose moment has come. */
    private void reveal(long position, long now) {
        List<Word> words = line.words();
        boolean wholeLine = !wordByWord.getValue();
        for (Word w : words) {
            if (w.appearedAt != 0L) continue;
            long showAt = wholeLine ? line.startMs() : w.atMs - lead.getValue();
            // the first word is there the moment the line is known, however late that moment is
            if (!wholeLine && position < showAt && w != words.get(0)) continue;
            // a frame can arrive after the word was due; age it by that much so the fade-in is not rushed
            w.appearedAt = now - Math.min((long) IN_MS, Math.max(0L, position - showAt));
        }
    }

    /** Sends the line on screen into its fade-out. */
    private void retire(long now) {
        if (line == null) return;
        leaving = line;
        leavingAt = now;
        line = null;
    }

    /* ------------------------------------------------------------------ */
    /* Syllables                                                           */
    /* ------------------------------------------------------------------ */

    private static final String VOWELS = "aeiouyąęóáéíúàèìòùâêîôûäëïöüåæøœ";

    /**
     * Groups of vowels in the word, at least one. Counting groups rather than vowels is what keeps a softening
     * Polish "i" ("się", "nie") from making a second syllable. Close enough for English too.
     */
    static int syllables(String word) {
        String w = word.toLowerCase(Locale.ROOT);
        int count = 0;
        boolean inVowel = false;
        for (int i = 0; i < w.length(); i++) {
            boolean vowel = VOWELS.indexOf(w.charAt(i)) >= 0;
            if (vowel && !inVowel) count++;
            inVowel = vowel;
        }
        // a trailing "e" in English is usually silent, but erring on the long side keeps words from stacking
        return Math.max(1, count);
    }

    /* ------------------------------------------------------------------ */
    /* World placement                                                     */
    /* ------------------------------------------------------------------ */

    /**
     * A random point in the world inside the current field of view, {@link #distance} blocks out - turned off
     * the look direction by up to half the FOV (scaled by {@link #spread}), less vertically since the view is
     * wider than it is tall.
     */
    private Vec3d pickSpot() {
        Vec3d eye = mc.getEntityRenderDispatcher().camera.getCameraPos();
        float fov = mc.options.getFov().getValue();
        float yaw = mc.player.getYaw() + (random.nextFloat() * 2f - 1f) * fov * 0.5f * spread.getValue();
        float pitch = mc.player.getPitch() + (random.nextFloat() * 2f - 1f) * fov * 0.28f * spread.getValue();
        pitch = Math.clamp(pitch, -89f, 89f);
        return eye.add(Vec3d.fromPolar(pitch, yaw).multiply(distance.getValue()));
    }

    /* ------------------------------------------------------------------ */
    /* Drawing                                                             */
    /* ------------------------------------------------------------------ */

    private void drawLeaving(DrawContext context, long now) {
        if (leaving == null) return;
        // the line leaves word by word, left to right, each one a beat after the one before
        long total = (long) OUT_MS + EXIT_STAGGER_MS * Math.max(0, leaving.words().size() - 1);
        if (now - leavingAt >= total) {
            leaving = null;
            return;
        }
        if (!drawLine(context, leaving, now, 1f, 0f, leavingAt)) leaving = null;
    }

    /** How far apart the words of a finished line start to leave. */
    private static final long EXIT_STAGGER_MS = 40L;

    private boolean drawLine(DrawContext context, Line l, long now, float fade, float rise) {
        return drawLine(context, l, now, fade, rise, 0L);
    }

    /**
     * One line, centred on where its world spot lands on screen. The whole line's width is reserved from the
     * start, so the words that have arrived never shift as the rest come in.
     *
     * @param fade a whole-line opacity (for the line fading out), {@code rise} how far it has drifted up
     * @return false once the line is fully faded or no longer in the view
     */
    private boolean drawLine(DrawContext context, Line l, long now, float fade, float rise, long exitAt) {
        if (fade <= 0.01f) return false;
        Vec3d screen = Render3DEngine.worldSpaceToScreenSpace(l.anchor());
        if (!(screen.z > 0 && screen.z < 1)) return true;   // behind you

        var font = mc.textRenderer;
        float sw = mc.getWindow().getScaledWidth();
        int space = font.getWidth(" ");
        int total = -space;
        for (Word w : l.words()) total += font.getWidth(w.text) + space;

        // never wider than the screen: a long line shrinks instead of running off the sides
        float s = Math.min(scale.getValue(), sw * 0.92f / Math.max(1, total));
        float x = (float) screen.x - total * s / 2f;
        float y = (float) screen.y - font.fontHeight * s / 2f - rise;

        float cursor = 0f;
        int index = 0;
        for (Word w : l.words()) {
            int width = font.getWidth(w.text);
            // leaving: this word's own exit, a beat after the previous one - it fades, shrinks a little and
            // drops away, so the line crumbles off instead of vanishing as one block
            float gone = exitAt == 0L ? 0f
                    : easeOutCubic(Math.clamp((now - exitAt - index * EXIT_STAGGER_MS) / OUT_MS, 0f, 1f));
            index++;
            if (gone >= 0.99f) {
                cursor += width + space;
                continue;
            }
            if (w.appearedAt != 0L) {
                // each word pops up into place: fades in, rises from a little below, grows past full size and
                // settles, with a brief violet glow that dies away once it has landed
                float raw = Math.clamp((now - w.appearedAt) / (IN_MS * 1.6f), 0f, 1f);
                float t = easeOutCubic(Math.clamp((now - w.appearedAt) / IN_MS, 0f, 1f));
                float pop = 0.82f + 0.18f * easeOutBack(raw);
                float lift = (1f - t) * font.fontHeight * 0.45f * s;
                float glow = 1f - Math.clamp((now - w.appearedAt) / 520f, 0f, 1f);
                float drop = gone * font.fontHeight * 0.7f * s;
                drawWord(context, w.text, x + (cursor + width / 2f) * s, y + font.fontHeight * s / 2f + lift + drop,
                        s * pop * (1f - 0.15f * gone), t * fade * (1f - gone), glow * glow * fade * (1f - gone));
            }
            cursor += width + space;
        }
        return true;
    }

    private void drawWord(DrawContext context, String word, float cx, float cy, float s, float alpha, float glow) {
        int a = Math.round(255 * Math.clamp(alpha, 0f, 1f));
        if (a <= 4) return;
        var font = mc.textRenderer;
        int w = font.getWidth(word);

        var m = context.getMatrices();
        m.pushMatrix();
        m.translate(cx, cy);
        m.scale(s, s);
        int x = -Math.round(w / 2f), y = -font.fontHeight / 2;
        if (glow > 0.02f) {
            // a soft violet halo: the word repeated around itself in two rings, faint and fading
            int ga = Math.round(70 * glow * Math.clamp(alpha, 0f, 1f));
            int halo = (ga << 24) | 0xA070FF;
            for (int r = 1; r <= 2; r++)
                for (int k = 0; k < 8; k++) {
                    double ang = Math.PI * k / 4.0;
                    context.drawText(font, word, x + (int) Math.round(Math.cos(ang) * r), y + (int) Math.round(Math.sin(ang) * r), halo, false);
                }
        }
        if (style.is(Style.Outline)) {
            int ink = Math.round(a * 0.75f) << 24;
            for (int ox = -1; ox <= 1; ox++)
                for (int oy = -1; oy <= 1; oy++)
                    if (ox != 0 || oy != 0) context.drawText(font, word, x + ox, y + oy, ink, false);
            context.drawText(font, word, x, y, (a << 24) | 0xFFFFFF, false);
        } else {
            context.drawText(font, word, x, y, (a << 24) | 0xFFFFFF, true);
        }
        m.popMatrix();
    }

    /** Overshoots a little past 1 before settling - the pop of a word landing. */
    private static float easeOutBack(float t) {
        float c1 = 1.70158f, c3 = c1 + 1f, u = t - 1f;
        return 1f + c3 * u * u * u + c1 * u * u;
    }

    private static float easeOutCubic(float t) {
        float u = 1f - t;
        return 1f - u * u * u;
    }

    private void status(DrawContext context, String text) {
        if (!showStatus.getValue()) return;
        var font = mc.textRenderer;
        String msg = "TextInMinecraft: " + text;
        float sw = mc.getWindow().getScaledWidth();
        context.drawText(font, msg, Math.round((sw - font.getWidth(msg)) / 2f), 6, 0xB0FFFFFF, true);
    }
}
