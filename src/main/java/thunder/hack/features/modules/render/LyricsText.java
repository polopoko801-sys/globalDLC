package thunder.hack.features.modules.render;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.util.math.Vec3d;
import thunder.hack.features.modules.Module;
import thunder.hack.setting.Setting;
import thunder.hack.utility.spotify.SpotifyCard;
import thunder.hack.utility.render.Render3DEngine;

import java.awt.Color;
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

    public enum Style {Outline, Shadow}

    /** Line: the whole line in one spot. Split: the line cut into pieces of a few words, each somewhere else. Scatter: every word on its own spot. */
    public enum Layout {Line, Split, Scatter}

    /** How a word arrives: Pop (rises, grows past size and settles), Fade, Slide (in from the left), Typewriter (letter by letter). */
    public enum Anim {Pop, Fade, Slide, Typewriter}

    public enum ColorMode {White, Custom, Theme, Rainbow, Gradient}

    public final Setting<Layout> layout = new Setting<>("Layout", Layout.Line);
    public final Setting<Anim> anim = new Setting<>("Animation", Anim.Pop);
    public final Setting<ColorMode> colorMode = new Setting<>("ColorMode", ColorMode.White);
    public final Setting<thunder.hack.setting.impl.ColorSetting> textColor =
            new Setting<>("TextColor", new thunder.hack.setting.impl.ColorSetting(0xFFFFFFFF));
    /** One random word of every line drawn in the highlight colour, a little bigger. */
    public final Setting<Boolean> highlight = new Setting<>("Highlight", false);
    public final Setting<thunder.hack.setting.impl.ColorSetting> highlightColor =
            new Setting<>("HighlightColor", new thunder.hack.setting.impl.ColorSetting(0xFFA070FF));
    /** The short halo a word gets as it lands. */
    public final Setting<Boolean> glow = new Setting<>("Glow", true);

    /** Size on screen - fixed, it does not change with distance. */
    public final Setting<Float> scale = new Setting<>("Scale", 2.6f, 1f, 6f);
    /** How far in front of you a new line is placed, in blocks. */
    /** Screen: words land anywhere on the whole screen. World: pinned to points in front of you, around the crosshair. */
    public enum Placement {Screen, World}
    public final Setting<Placement> placement = new Setting<>("Placement", Placement.Screen);
    public final Setting<Float> distance = new Setting<>("Distance", 8f, 3f, 30f, v -> placement.getValue() == Placement.World);
    /** How far from the centre of the view a line may land: 0 = dead centre, 1 = the edges of the view. */
    public final Setting<Float> spread = new Setting<>("Spread", 0.55f, 0f, 1f, v -> placement.getValue() == Placement.World);
    /** How long before its moment a word shows up. */
    public final Setting<Integer> lead = new Setting<>("Lead", 350, 0, 1500);
    /** Shifts everything against Spotify's clock, for a recording that runs early or late. */
    public final Setting<Integer> offset = new Setting<>("Offset", 0, -1000, 1000);
    /** Reveal the words one by one, or the whole line at once. */
    public final Setting<Boolean> wordByWord = new Setting<>("Words", true);
    public final Setting<Style> style = new Setting<>("Style", Style.Outline);
    /** A small dim line at the top saying why nothing is shown. */
    public final Setting<Boolean> showStatus = new Setting<>("Status", true);

    /** Fade-in of a new word, and the gentle grow that comes with it. */
    private static final float IN_MS = 180f;
    /** Fade-out of a finished line. */
    private static final float OUT_MS = 300f;
    /** How long a line stays after the last one, when the lyrics end before the track does. */
    private static final long TAIL_MS = 4000L;
    /** The shortest gap between two words of one line. */
    private static final long MIN_WORD_MS = 70L;

    /** One word of a line: its text, when it is sung (ms in the song) and when it actually appeared (wall clock). */
    public static final class Word {
        public final String text;
        public final long atMs;
        public long appearedAt;

        public Word(String text, long atMs) {
            this.text = text;
            this.atMs = atMs;
        }
    }

    /**
     * A line on screen: its words, how they are grouped (one group for Line, a few words each for Split, one word
     * each for Scatter), the spot in the world each group is pinned to, when it is sung, and the highlighted word.
     */
    private record Line(List<Word> words, List<int[]> groups, List<Vec3d> anchors, long startMs, long endMs, int highlight) {
    }

    /** The groups of a line for a layout: [first, last) word indices. Split cuts into pieces of two or three words. */
    public static List<int[]> groupsFor(Layout layout, int words, Random random) {
        List<int[]> groups = new ArrayList<>();
        if (layout == Layout.Line || words <= 1) {
            groups.add(new int[]{0, words});
        } else if (layout == Layout.Scatter) {
            for (int i = 0; i < words; i++) groups.add(new int[]{i, i + 1});
        } else {
            int i = 0;
            while (i < words) {
                int n = Math.min(words - i, 2 + random.nextInt(2));
                if (words - i - n == 1) n++; // never leave one word dangling on its own at the end
                groups.add(new int[]{i, Math.min(words, i + n)});
                i += n;
            }
        }
        return groups;
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
        boolean onScreen = placement.getValue() == Placement.Screen;
        // word by word on the whole screen: every word gets its own spot
        List<int[]> groups = groupsFor(onScreen && wordByWord.getValue() ? Layout.Scatter : layout.getValue(), words.size(), random);
        List<Vec3d> anchors = new ArrayList<>(groups.size());
        for (int g = 0; g < groups.size(); g++) anchors.add(onScreen ? pickScreenSpot(anchors) : pickSpot());
        int hl = highlight.getValue() ? random.nextInt(words.size()) : -1;
        line = new Line(words, groups, anchors, 0L, end, hl);
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
    public static int syllables(String word) {
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
    /**
     * A random point anywhere on the screen, as fractions of its width and height (z = -1 marks it as a screen
     * spot), kept clear of the edges and, where it can be, of the spots the line already uses.
     */
    private Vec3d pickScreenSpot(List<Vec3d> taken) {
        Vec3d best = null;
        double bestGap = -1;
        for (int tries = 0; tries < 16; tries++) {
            Vec3d p = new Vec3d(0.1 + random.nextDouble() * 0.8, 0.1 + random.nextDouble() * 0.75, -1);
            double gap = Double.MAX_VALUE;
            for (Vec3d t : taken) gap = Math.min(gap, Math.hypot((p.x - t.x) * 1.6, p.y - t.y));
            if (leaving != null) for (Vec3d t : leaving.anchors()) gap = Math.min(gap, Math.hypot((p.x - t.x) * 1.6, p.y - t.y));
            if (gap > 0.22) return p;
            if (gap > bestGap) { bestGap = gap; best = p; }
        }
        return best;
    }

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
    public static final long EXIT_STAGGER_MS = 40L;

    private boolean drawLine(DrawContext context, Line l, long now, float fade, float rise) {
        return drawLine(context, l, now, fade, rise, 0L);
    }

    /** Each group of the line centred on where its world spot lands on screen. */
    private boolean drawLine(DrawContext context, Line l, long now, float fade, float rise, long exitAt) {
        if (fade <= 0.01f) return false;
        float[][] centres = new float[l.groups().size()][];
        for (int g = 0; g < centres.length; g++) {
            Vec3d a = l.anchors().get(g);
            if (a.z < 0) {
                centres[g] = new float[]{(float) a.x * mc.getWindow().getScaledWidth(), (float) a.y * mc.getWindow().getScaledHeight() - rise};
                continue;
            }
            Vec3d screen = Render3DEngine.worldSpaceToScreenSpace(l.anchors().get(g));
            if (screen.z > 0 && screen.z < 1) centres[g] = new float[]{(float) screen.x, (float) screen.y - rise};
        }
        drawGroups(context, l.words(), l.groups(), centres, now, exitAt, scale.getValue(), l.highlight(), fade);
        return true;
    }

    /**
     * The words, group by group, each group centred on its point. The whole group's width is reserved from the
     * start, so the words that have arrived never shift as the rest come in. Shared with the editor's preview.
     *
     * @param centres screen centre per group, null to skip that group (behind you)
     * @param exitAt  when the line started leaving, 0 while it is on
     */
    public void drawGroups(DrawContext context, List<Word> words, List<int[]> groups, float[][] centres, long now,
                           long exitAt, float baseScale, int highlightIndex, float fade) {
        var font = mc.textRenderer;
        float sw = mc.getWindow().getScaledWidth();
        int space = font.getWidth(" ");
        for (int g = 0; g < groups.size(); g++) {
            float[] c = centres[g];
            if (c == null) continue;
            int[] range = groups.get(g);
            int total = -space;
            for (int i = range[0]; i < range[1]; i++) total += font.getWidth(words.get(i).text) + space;
            // never wider than the screen: a long line shrinks instead of running off the sides
            float s = Math.min(baseScale, sw * 0.92f / Math.max(1, total));
            float x = c[0] - total * s / 2f;
            float y = c[1] - font.fontHeight * s / 2f;
            float cursor = 0f;
            for (int i = range[0]; i < range[1]; i++) {
                Word w = words.get(i);
                int width = font.getWidth(w.text);
                // leaving: each word exits a beat after the previous one - it fades, shrinks a little and drops
                float gone = exitAt == 0L ? 0f
                        : easeOutCubic(Math.clamp((now - exitAt - i * EXIT_STAGGER_MS) / OUT_MS, 0f, 1f));
                if (gone < 0.99f && w.appearedAt != 0L)
                    drawArriving(context, w, i, i == highlightIndex, x + (cursor + width / 2f) * s, y + font.fontHeight * s / 2f,
                            s, now, gone, fade);
                cursor += width + space;
            }
        }
    }

    /** One word in its arrival animation, with its colour. */
    private void drawArriving(DrawContext context, Word w, int index, boolean highlighted, float cx, float cy, float s,
                              long now, float gone, float fade) {
        var font = mc.textRenderer;
        float age = now - w.appearedAt;
        float t = easeOutCubic(Math.clamp(age / IN_MS, 0f, 1f));
        float alpha = t, scaleMul = 1f, dx = 0f, dy = 0f;
        int chars = w.text.length();
        switch (anim.getValue()) {
            case Pop -> {
                float raw = Math.clamp(age / (IN_MS * 1.6f), 0f, 1f);
                scaleMul = 0.82f + 0.18f * easeOutBack(raw);
                dy = (1f - t) * font.fontHeight * 0.45f * s;
            }
            case Fade -> alpha = easeOutCubic(Math.clamp(age / (IN_MS * 1.8f), 0f, 1f));
            case Slide -> dx = -(1f - t) * 14f * s;
            case Typewriter -> {
                chars = Math.clamp((int) (age / 45f) + 1, 1, w.text.length());
                alpha = 1f;
            }
        }
        if (highlighted) scaleMul *= 1.15f;
        float glowAmt = glow.getValue() ? 1f - Math.clamp(age / 520f, 0f, 1f) : 0f;
        float drop = gone * font.fontHeight * 0.7f * s;
        Color col = colorFor(index, highlighted, now);
        drawWord(context, w.text, chars, cx + dx, cy + dy + drop, s * scaleMul * (1f - 0.15f * gone),
                alpha * fade * (1f - gone), glowAmt * glowAmt * fade * (1f - gone), col,
                highlighted ? highlightColor.getValue().getColorObject() : glowColor(col));
    }

    private static Color glowColor(Color text) {
        // a white or grey word glows violet, as before; a coloured one glows in its own colour
        int max = Math.max(text.getRed(), Math.max(text.getGreen(), text.getBlue()));
        int min = Math.min(text.getRed(), Math.min(text.getGreen(), text.getBlue()));
        return max - min < 30 ? new Color(0xA070FF) : text;
    }

    /** The colour of a word: the mode's colour, or the highlight colour for the highlighted word. */
    public Color colorFor(int index, boolean highlighted, long now) {
        if (highlighted) return highlightColor.getValue().getColorObject();
        return switch (colorMode.getValue()) {
            case White -> Color.WHITE;
            case Custom -> textColor.getValue().getColorObject();
            case Theme -> thunder.hack.features.modules.client.HudEditor.getColor(index * 12);
            case Rainbow -> Color.getHSBColor(((now % 4000L) / 4000f + index * 0.08f) % 1f, 0.65f, 1f);
            case Gradient -> {
                Color a = textColor.getValue().getColorObject(), b = highlightColor.getValue().getColorObject();
                float k = (float) (0.5 + 0.5 * Math.sin(now / 600.0 + index * 0.7));
                yield new Color(Math.round(a.getRed() + (b.getRed() - a.getRed()) * k),
                        Math.round(a.getGreen() + (b.getGreen() - a.getGreen()) * k),
                        Math.round(a.getBlue() + (b.getBlue() - a.getBlue()) * k));
            }
        };
    }

    private void drawWord(DrawContext context, String word, int chars, float cx, float cy, float s, float alpha, float glowAmt,
                          Color color, Color glowCol) {
        int a = Math.round(255 * Math.clamp(alpha, 0f, 1f));
        if (a <= 4) return;
        var font = mc.textRenderer;
        int w = font.getWidth(word);
        String shown = chars >= word.length() ? word : word.substring(0, chars);

        var m = context.getMatrices();
        m.pushMatrix();
        m.translate(cx, cy);
        m.scale(s, s);
        int x = -Math.round(w / 2f), y = -font.fontHeight / 2;
        if (glowAmt > 0.02f) {
            // a soft halo: the word repeated around itself in two rings, faint and fading
            int ga = Math.round(70 * glowAmt * Math.clamp(alpha, 0f, 1f));
            int halo = (ga << 24) | (glowCol.getRGB() & 0xFFFFFF);
            for (int r = 1; r <= 2; r++)
                for (int k = 0; k < 8; k++) {
                    double ang = Math.PI * k / 4.0;
                    context.drawText(font, shown, x + (int) Math.round(Math.cos(ang) * r), y + (int) Math.round(Math.sin(ang) * r), halo, false);
                }
        }
        int rgb = color.getRGB() & 0xFFFFFF;
        if (style.is(Style.Outline)) {
            int ink = Math.round(a * 0.75f) << 24;
            for (int ox = -1; ox <= 1; ox++)
                for (int oy = -1; oy <= 1; oy++)
                    if (ox != 0 || oy != 0) context.drawText(font, shown, x + ox, y + oy, ink, false);
            context.drawText(font, shown, x, y, (a << 24) | rgb, false);
        } else {
            context.drawText(font, shown, x, y, (a << 24) | rgb, true);
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
