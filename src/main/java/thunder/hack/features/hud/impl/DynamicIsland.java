package thunder.hack.features.hud.impl;

import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.hud.ClientBossBar;
import net.minecraft.client.gui.screen.ChatScreen;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.Identifier;
import org.jetbrains.annotations.NotNull;
import org.lwjgl.glfw.GLFW;
import thunder.hack.core.manager.client.ModuleManager;
import thunder.hack.events.impl.EventAttack;
import thunder.hack.events.impl.EventDeath;
import thunder.hack.events.impl.EventMouse;
import thunder.hack.events.impl.TotemPopEvent;
import thunder.hack.features.hud.HudElement;
import thunder.hack.features.modules.Module;
import thunder.hack.gui.font.BkFonts;
import thunder.hack.gui.font.FontRenderer;
import thunder.hack.gui.mainmenu.MenuMusicPanel;
import thunder.hack.setting.Setting;
import thunder.hack.utility.UiSounds;
import thunder.hack.utility.render.BkIslandMorph;
import thunder.hack.utility.render.BkLiquidGlass;
import thunder.hack.utility.render.GuiRender;
import thunder.hack.utility.render.Render2DEngine;
import thunder.hack.utility.render.TextureStorage;
import thunder.hack.utility.spotify.Lyrics;
import thunder.hack.utility.spotify.SpotifyAuth;
import thunder.hack.utility.spotify.SpotifyModels;
import thunder.hack.utility.spotify.SpotifyPlayer;

import java.awt.Color;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * BKClient's Dynamic Island ({@code ui/hud/impl/island/DynamicDock} and its statuses, MIT, bkubiakdev), ported 1:1
 * onto this client: the capsule fixed at the top centre, its size eased with BK's curves, the logo bubble rolling
 * out of its right end through the island-morph glass, the clock and connection beside it, and the same statuses
 * in the same priority - notifications, config loads, FakeLag, PvP timer, music (expandable from chat) and the
 * idle logo pill.
 *
 * <p>What BK read from its own systems is read from ours: module toggles, kills and totem pops feed the
 * notification row, music comes from the main menu's player source (Spotify or the window title) and the PvP
 * timer from the boss bar exactly like BK's BossBarHud hook.
 */
public class DynamicIsland extends HudElement {
    public DynamicIsland() {
        super("DynamicIsland", 48, 15, 0.46f, 0.008f);
    }

    /* ------------------------------------------------------------------ */
    /* Settings (BK's hud.dynamic_dock.*)                                  */
    /* ------------------------------------------------------------------ */

    private final Setting<Float> islandScale = new Setting<>("Scale", 1f, 1f, 2f);
    private final Setting<Boolean> clockVisible = new Setting<>("Clock", true);
    private final Setting<Boolean> connectionVisible = new Setting<>("Connection", true);
    private final Setting<Boolean> swapSides = new Setting<>("SwapSides", false);
    private final Setting<Boolean> musicLyrics = new Setting<>("MusicLyrics", true);
    private final Setting<Boolean> liquidBubble = new Setting<>("LiquidBubble", true);
    private final Setting<Boolean> showAlerts = new Setting<>("Alerts", true);
    private final Setting<Boolean> showConfig = new Setting<>("Config", true);
    private final Setting<Boolean> showBlink = new Setting<>("FakeLag", true);
    private final Setting<Boolean> showPvp = new Setting<>("PVP", true);
    private final Setting<Boolean> showMusic = new Setting<>("Music", true);
    private final Setting<Boolean> modules = new Setting<>("Modules", true);
    private final Setting<Boolean> kills = new Setting<>("Kills", true);
    private final Setting<Boolean> pops = new Setting<>("TotemPops", true);
    /** Off: also report pops of players you were not fighting. */
    private final Setting<Boolean> onlyMine = new Setting<>("OnlyMine", true);
    private final Setting<Boolean> sound = new Setting<>("Sound", true);

    private static final float DOCK_Y = 7.0F;
    private static final float COMPACT_SCALE = 0.75F;
    private static final int[] PING_THRESHOLDS = {450, 300, 150, 75};
    private static final Color WHITE = Color.WHITE;
    private static final Identifier AIRPLANE_ICON = Identifier.of("bkclient", "icons/airplane.png");
    private static final Identifier COMBAT_ICON = Identifier.of("bkclient", "icons/category/combat.png");
    private static final Identifier CONFIG_ICON = Identifier.of("gombal", "textures/gui/icons/configs.png");
    private static final Identifier SPOTIFY_ICON = Identifier.of("bkclient", "icons/media/spotify.png");
    private static final Identifier REPEAT = Identifier.of("bkclient", "icons/music/repeat.png");
    private static final Identifier REPEAT_ONE = Identifier.of("bkclient", "icons/music/repeat1.png");

    /* ------------------------------------------------------------------ */
    /* BK's animation framework                                            */
    /* ------------------------------------------------------------------ */

    interface Easing {
        float ease(float t);

        Easing LINEAR = t -> t;

        static Easing bezier(double x1, double y1, double x2, double y2) {
            float ax = (float) x1, ay = (float) y1, bx = (float) x2, by = (float) y2;
            return progress -> {
                if (progress <= 0f) return 0f;
                if (progress >= 1f) return 1f;
                float t = progress;
                for (int i = 0; i < 8; i++) {
                    float x = 3f * (1f - t) * (1f - t) * t * ax + 3f * (1f - t) * t * t * bx + t * t * t;
                    float dx = 3f * ((1f - t) * (1f - 3f * t) * ax + (2f * t - 3f * t * t) * bx) + 3f * t * t;
                    if (Math.abs(x - progress) < 1.0E-5F || Math.abs(dx) < 1.0E-6F) break;
                    t -= (x - progress) / dx;
                    t = Math.max(0f, Math.min(1f, t));
                }
                return 3f * (1f - t) * (1f - t) * t * ay + 3f * (1f - t) * t * t * by + t * t * t;
            };
        }
    }

    static final Easing BAKEK = Easing.bezier(0.45F, 1.45F, 0.49F, 1.15F);
    static final Easing BAKEK_SIZE = Easing.bezier(0.27F, 1.09F, 0.49F, 1.06F);
    static final Easing FIGMA_EASE_IN_OUT = Easing.bezier(0.42, 0.0, 0.58, 1.0);

    /** BK's {@code Animation}: eases from wherever it is to a new target over a fixed time. */
    static final class Anim {
        long duration;
        private final Easing easing;
        private float value, startValue, targetValue;
        private long startTime;

        Anim(long duration, float initial, Easing easing) {
            this.duration = duration;
            this.easing = easing;
            this.value = this.startValue = this.targetValue = initial;
        }

        float update(boolean on) {
            return update(on ? 1f : 0f);
        }

        float update(float target) {
            long now = System.currentTimeMillis();
            if (target != targetValue) {
                startValue = value;
                targetValue = target;
                startTime = now;
            }
            long elapsed = now - startTime;
            if (elapsed >= duration) value = targetValue;
            else value = startValue + (targetValue - startValue) * easing.ease((float) elapsed / duration);
            return value;
        }

        float get() {
            return value;
        }
    }

    /* ------------------------------------------------------------------ */
    /* Statuses                                                            */
    /* ------------------------------------------------------------------ */

    /** BK's {@code IslandStatus}: one thing the island can show, with its own size and crossfade. */
    abstract class Status {
        float width = 48f, height = 15f;
        final Anim animation = new Anim(500L, 0f, BAKEK_SIZE);

        abstract boolean canShow();

        abstract boolean enabled();

        /** @param a this status' opacity (BK's drawWithAlpha shader colour) */
        void draw(DrawContext ctx, float a) {
        }

        void click(float mx, float my, int button) {
        }

        boolean extendable() {
            return false;
        }
    }

    /** BK's {@code IslandTokenStatus}: a row of icon / divider / text / ring tokens. */
    abstract class TokenStatus extends Status {
        static final int TEXT = 0, ICON = 1, DIVIDER = 2, RING = 3;
        static final float FONT_SIZE = 6f, ICON_SIZE = 6f, DIVIDER_WIDTH = 1f, DIVIDER_HEIGHT = 5f, RING_RADIUS = 3.5f;

        record Token(int type, String text, Identifier icon, Color color, boolean tight, float progress) {
            static Token text(String text, Color color, boolean tight) {
                return new Token(TEXT, text, null, color, tight, 0f);
            }

            static Token icon(Identifier icon, Color color) {
                return new Token(ICON, "", icon, color, false, 0f);
            }

            static Token divider() {
                return new Token(DIVIDER, "", null, WHITE, false, 0f);
            }

            static Token ring(float progress) {
                return new Token(RING, "", null, accent(), false, progress);
            }
        }

        abstract List<Token> tokens();

        @Override
        void draw(DrawContext ctx, float a) {
            FontRenderer font = BkFonts.MEDIUM.get(FONT_SIZE);
            List<Token> tokens = tokens();
            float padding = 8f, gap = 5f, w = padding * 2f;
            for (int i = 0; i < tokens.size(); i++) {
                if (i > 0 && !tokens.get(i).tight()) w += gap;
                w += tokenWidth(font, tokens.get(i));
            }
            width = w;
            height = 15f;
            float left = sw() / 2f - size.width / 2f;
            float centerY = height / 2f;
            float cursor = left + padding;
            for (int i = 0; i < tokens.size(); i++) {
                Token t = tokens.get(i);
                if (i > 0 && !t.tight()) cursor += gap;
                switch (t.type()) {
                    case ICON -> {
                        // BK draws a 10 px icon-font glyph over the 6 px slot; the PNG's ink fills its box, so 7 px matches
                        float iconSize = 7f;
                        BkMusicUi.icon(ctx, t.icon(), cursor + ICON_SIZE / 2f - iconSize / 2f, centerY - iconSize / 2f - 0.25f, iconSize,
                                alpha(t.color(), t.color().getAlpha() * a));
                    }
                    case DIVIDER -> Render2DEngine.drawRound(ctx, cursor, centerY - DIVIDER_HEIGHT / 2f, DIVIDER_WIDTH, DIVIDER_HEIGHT, 0.5f,
                            alpha(WHITE, 42f * a));
                    case RING -> ring(ctx, cursor + RING_RADIUS, centerY, RING_RADIUS, 1f, t.progress(),
                            alpha(accent(), 38f * a), alpha(accent(), 255f * a));
                    default -> text(ctx, font, t.text(), cursor, centerY - textHeight(FONT_SIZE) / 2f - 0.5f,
                            alpha(t.color(), t.color().getAlpha() * a));
                }
                cursor += tokenWidth(font, t);
            }
        }

        private float tokenWidth(FontRenderer font, Token t) {
            return switch (t.type()) {
                case ICON -> ICON_SIZE;
                case DIVIDER -> DIVIDER_WIDTH;
                case RING -> RING_RADIUS * 2f;
                default -> font.getStringWidth(t.text());
            };
        }
    }

    /* --- notifications ------------------------------------------------- */

    private enum NotificationType {
        SUCCESS(new Color(74, 222, 128)), ERROR(new Color(239, 68, 68)), INFO(new Color(234, 179, 8));

        final Color color;

        NotificationType(Color color) {
            this.color = color;
        }
    }

    private static final class Notification {
        final NotificationType type;
        final String text;
        final long created = System.currentTimeMillis();
        final long duration;
        final Anim showing = new Anim(500L, 0f, BAKEK_SIZE);

        Notification(NotificationType type, String text, long duration) {
            this.type = type;
            this.text = text;
            this.duration = Math.max(250L, duration);
        }

        long elapsed() {
            return System.currentTimeMillis() - created;
        }

        boolean finished() {
            return elapsed() >= duration;
        }
    }

    private final List<Notification> notifications = new ArrayList<>();

    private void notify(NotificationType type, String text, long duration) {
        notifications.add(new Notification(type, text, duration));
        if (notifications.size() > 12) notifications.removeFirst();
        if (sound.getValue()) UiSounds.play(UiSounds.Sound.ISLAND);
    }

    final class NotificationStatus extends TokenStatus {
        @Override
        boolean enabled() {
            return showAlerts.getValue();
        }

        boolean hasNotification() {
            return !notifications.isEmpty() && !notifications.getLast().finished();
        }

        @Override
        boolean canShow() {
            return hasNotification();
        }

        @Override
        List<Token> tokens() {
            return List.of();
        }

        @Override
        void draw(DrawContext ctx, float a) {
            if (!hasNotification()) {
                super.draw(ctx, a);
                return;
            }
            Notification active = notifications.getLast();
            FontRenderer font = BkFonts.MEDIUM.get(7f);
            float padding = 8f, gap = 5f, ringSize = 6f;
            width = padding * 2f + ringSize + gap + 1f + gap + font.getStringWidth(active.text);
            height = 15f;
            float left = sw() / 2f - size.width / 2f;
            float centerY = height / 2f;

            for (Notification n : notifications) {
                n.showing.update(active == n);
                float al = Math.clamp(animation.get() * n.showing.get(), 0f, 1f) * a;
                float remaining = n.duration - n.elapsed();
                al *= Math.clamp(remaining / 250f, 0f, 1f);
                if (al <= 0.001f) continue;
                Color tint = n.type.color;
                // the tint stays inside the capsule and fades out across its left 69%
                Color from = alpha(tint, 71.4f * al), to = alpha(tint, 0);
                Render2DEngine.drawGradientRound(ctx, left, 0f, size.width * 0.69f, height, 7f, to, to, from, from);
            }
            for (Notification n : notifications) {
                float al = animation.get() * n.showing.get() * a;
                if (al <= 0.001f) continue;
                Color accent = n.type.color;
                float cursor = left + padding;
                float remaining = 1f - Math.min(1f, (float) n.elapsed() / n.duration);
                ring(ctx, cursor + ringSize / 2f, centerY, ringSize / 2f, 1f, remaining, alpha(accent, 38f * al), alpha(accent, 255f * al));
                cursor += ringSize + gap;
                Render2DEngine.drawRound(ctx, cursor, centerY - 2.5f, 1f, 5f, 0.5f, alpha(WHITE, 64f * al));
                cursor += 1f + gap;
                text(ctx, font, n.text, cursor, centerY - textHeight(7f) / 2f, alpha(WHITE, 255f * al));
            }
            notifications.removeIf(n -> n != notifications.getLast() && n.finished() && n.showing.get() <= 0.001f);
        }
    }

    /* --- config -------------------------------------------------------- */

    private static String configName = "";
    private static long configAt;
    private static final long CONFIG_DURATION = 3000L;

    /** Called by the config manager after a config has been loaded. */
    public static void configLoaded(String name) {
        configName = name == null || name.isBlank() ? "Config" : name;
        configAt = System.currentTimeMillis();
    }

    final class ConfigStatus extends TokenStatus {
        @Override
        boolean enabled() {
            return showConfig.getValue();
        }

        @Override
        boolean canShow() {
            return configAt != 0L && System.currentTimeMillis() - configAt < CONFIG_DURATION;
        }

        @Override
        List<Token> tokens() {
            float remaining = 1f - Math.min(1f, (System.currentTimeMillis() - configAt) / (float) CONFIG_DURATION);
            return List.of(
                    Token.icon(CONFIG_ICON, accent()),
                    Token.divider(),
                    Token.text("Config ", WHITE, false),
                    Token.text(configName, accent(), true),
                    Token.text(" Loaded", WHITE, true),
                    Token.divider(),
                    Token.ring(remaining));
        }
    }

    /* --- FakeLag ------------------------------------------------------- */

    final class BlinkStatus extends Status {
        @Override
        boolean enabled() {
            return showBlink.getValue();
        }

        @Override
        boolean canShow() {
            return ModuleManager.blink != null && ModuleManager.blink.isEnabled();
        }

        @Override
        void draw(DrawContext ctx, float a) {
            FontRenderer font = BkFonts.MEDIUM.get(7f);
            float x = sw() / 2f - size.width / 2f;
            width = 80f;
            height = 15f;
            text(ctx, font, "FakeLag", x - 4f + 10f * animation.get(), 5f, alpha(WHITE, 255f * a));
            float blinkWidth = width - font.getStringWidth("FakeLag") - 14f;
            float progress = blinkWidth * ModuleManager.blink.remainingFraction();
            Render2DEngine.drawRound(ctx, x + width - 5f - blinkWidth, 4.5f, blinkWidth, 6f, 2.5f, alpha(new Color(24, 24, 27), 255f * a));
            if (progress > 0.5f)
                Render2DEngine.drawRound(ctx, x + width - 5f - progress, 4.5f, progress, 6f, 2.5f, alpha(accent(), 255f * a));
        }
    }

    /* --- PvP ----------------------------------------------------------- */

    /** BK's BossBarHud hook: a boss bar naming combat with a "N sec" counter. */
    private static final Pattern PVP_TIME_PATTERN = Pattern.compile("(\\d+)\\s*[сcs][еe][кkc]?", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern ANY_NUMBER = Pattern.compile("(\\d+)");

    private static int combatSeconds() {
        if (mc.inGameHud == null) return 0;
        var bars = mc.inGameHud.getBossBarHud().bossBars;
        for (ClientBossBar bar : bars.values()) {
            if (bar.getName() == null) continue;
            String raw = bar.getName().getString();
            String name = raw.toLowerCase();
            if (name.contains("бой") || name.contains("pvp") || name.contains("walk") || name.contains("combat")) {
                Matcher m = PVP_TIME_PATTERN.matcher(raw);
                if (m.find()) return Integer.parseInt(m.group(1));
                m = ANY_NUMBER.matcher(raw);
                return m.find() ? Integer.parseInt(m.group(1)) : 0;
            }
        }
        return 0;
    }

    final class PvpStatus extends Status {
        private final Color danger = new Color(248, 91, 103);
        private int combatDuration;
        private int seconds;

        @Override
        boolean enabled() {
            return showPvp.getValue();
        }

        @Override
        boolean canShow() {
            seconds = combatSeconds();
            if (seconds <= 0) combatDuration = 0;
            return mc.player != null && seconds > 0;
        }

        @Override
        void draw(DrawContext ctx, float a) {
            FontRenderer font = BkFonts.MEDIUM.get(6.5f);
            FontRenderer timerFont = BkFonts.SEMIBOLD.get(6f);
            String label = "PVP Mode";
            String timer = seconds + "s";
            float badgeWidth = Math.max(20f, timerFont.getStringWidth(timer) + 9f);
            width = 39f + font.getStringWidth(label) + badgeWidth;
            height = 18f;
            float x = sw() / 2f - size.width / 2f;
            BkMusicUi.icon(ctx, COMBAT_ICON, x + 6, 5, 8, alpha(danger, 255f * a));
            text(ctx, font, label, x + 19, (18 - textHeight(6.5f)) / 2f, alpha(WHITE, 255f * a));
            float badgeX = x + width - badgeWidth - 16;
            Render2DEngine.drawRound(ctx, badgeX, 3, badgeWidth, 12, 4, alpha(danger, 30f * a));
            text(ctx, timerFont, timer, badgeX + badgeWidth / 2f - timerFont.getStringWidth(timer) / 2f,
                    (18 - textHeight(6f)) / 2f, alpha(danger, 255f * a));
            combatDuration = Math.max(combatDuration, seconds);
            float remaining = Math.clamp((float) seconds / Math.max(1, combatDuration), 0f, 1f);
            ring(ctx, x + width - 7, 9, 3, 1f, remaining, alpha(danger, 38f * a), alpha(danger, 255f * a));
        }
    }

    /* --- music --------------------------------------------------------- */

    final class MusicStatus extends Status {
        private final Anim[] waveAnims = new Anim[4];
        private final Anim pausingAnim = new Anim(600L, 0f, BAKEK_SIZE);
        private final Anim hoverPrevious = new Anim(300L, 0f, FIGMA_EASE_IN_OUT);
        private final Anim hoverPause = new Anim(300L, 0f, FIGMA_EASE_IN_OUT);
        private final Anim hoverNext = new Anim(300L, 0f, FIGMA_EASE_IN_OUT);
        private boolean showLyrics;
        private int lyricsOffset;
        private String compactText = "";
        private String previousCompactText = "";
        private String compactTrack = "";
        private long compactChangedAt;
        private MenuMusicPanel.View view;

        MusicStatus() {
            for (int i = 0; i < waveAnims.length; i++) waveAnims[i] = new Anim(1000L, 0f, Easing.LINEAR);
        }

        @Override
        boolean enabled() {
            return showMusic.getValue();
        }

        @Override
        boolean extendable() {
            return true;
        }

        @Override
        boolean canShow() {
            view = MenuMusicPanel.read();
            return view != null && view.present() && view.title() != null && !view.title().isBlank();
        }

        private Lyrics.Track lyrics() {
            if (view == null || !musicLyrics.getValue()) return null;
            Lyrics.Track t = Lyrics.get(view.title(), view.artist(), view.live() ? view.duration() : 0L);
            return t == null || !t.found() || t.lines() == null || t.lines().isEmpty() ? null : t;
        }

        @Override
        void draw(DrawContext ctx, float a) {
            if (view == null || !view.present()) return;
            float x = sw() / 2f - size.width / 2f;
            float y = 0f;
            Color textColor = WHITE;
            long positionMs = view.position();
            if (!musicLyrics.getValue()) {
                showLyrics = false;
                previousCompactText = "";
            }
            Lyrics.Track synced = lyrics();
            int lyricIndex = synced != null && synced.synced() ? synced.indexAt(positionMs) : -1;
            boolean hasActiveLyric = lyricIndex >= 0 && lyricIndex < synced.lines().size()
                    && !synced.lines().get(lyricIndex).text().isBlank();
            String line = hasActiveLyric ? synced.lines().get(lyricIndex).text() : view.title();
            if (line == null || line.isBlank()) line = view.artist() == null || view.artist().isBlank() ? "Music" : view.artist();
            String track = view.title() + "\u0000" + view.artist();
            if (!track.equals(compactTrack)) {
                compactTrack = track;
                compactText = "";
                previousCompactText = "";
            }
            if (!line.equals(compactText)) {
                previousCompactText = musicLyrics.getValue() ? compactText : "";
                compactText = line;
                compactChangedAt = System.nanoTime();
            }

            FontRenderer medium7 = BkFonts.MEDIUM.get(7f);
            FontRenderer regular7 = BkFonts.REGULAR.get(7f);
            FontRenderer regular5 = BkFonts.REGULAR.get(5f);
            float expWidth = 164f;
            float expHeight = showLyrics ? 125f : 80f;
            float maxWidth = Math.min(170f, sw() - 24f);
            float defaultWidth = Math.max(100f, 36f + medium7.getStringWidth(compactText));
            width = extended ? expWidth : Math.min(defaultWidth, maxWidth);
            height = extended ? expHeight : 15f;
            float extending = extendingAnim.get();
            float imageMargin = 4f + 6f * extending;
            float imageSize = 7f + 19f * extending;
            float imageY = y + (extended ? imageMargin : (size.height - imageSize) / 2f);
            float ia = animation.get();
            float coverX = x + imageMargin - 10f + 10f * ia;
            if (view.cover() != null)
                BkMusicUi.roundedTexture(ctx, view.cover(), coverX, imageY, imageSize, imageSize, 1f + 5f * extending, alpha(WHITE, 255f * a));
            else
                BkMusicUi.roundedTexture(ctx, BkMusicUi.NO_IMAGE, coverX, imageY, imageSize, imageSize, 1f + 5f * extending, alpha(WHITE, 255f * a));

            if (extending < 1f) {
                float lyricX = x + 15f;
                float lyricWidth = Math.max(0f, size.width - 34f);
                float elapsed = (System.nanoTime() - compactChangedAt) / 1_000_000_000f;
                float transition = Math.min(1f, elapsed / 0.32f);
                float visibility = (1f - extending) * ia * a;
                Render2DEngine.addWindow(ctx, lyricX, y + 2f, lyricX + lyricWidth, y + 13f, 1.0);
                try {
                    if (transition < 1f && !previousCompactText.isEmpty())
                        text(ctx, medium7, previousCompactText, lyricX, y + 4.5f - transition * 7f,
                                alpha(textColor, 255f * visibility * (1f - transition)));
                    float textWidth = medium7.getStringWidth(compactText);
                    float lineAge = hasActiveLyric ? Math.max(0L, positionMs - synced.lines().get(lyricIndex).atMs()) / 1000f : 0f;
                    float scroll = hasActiveLyric ? Math.min(Math.max(0f, textWidth - lyricWidth), Math.max(0f, lineAge - 0.8f) * 24f) : 0f;
                    float wordX = lyricX - scroll;
                    String[] words = compactText.split("\\s+");
                    for (int i = 0; i < words.length; i++) {
                        float progress = Math.max(0f, Math.min(1f, (elapsed - Math.min(i, 10) * 0.025f) / 0.30f));
                        float eased = 1f - (float) Math.pow(1f - progress, 3);
                        text(ctx, medium7, words[i], wordX, y + 4.5f + (1f - eased) * 6f, alpha(textColor, 255f * visibility * eased));
                        wordX += medium7.getStringWidth(words[i] + " ");
                    }
                } finally {
                    Render2DEngine.popWindow();
                }
            }

            if (extending > 0f) {
                fadeText(ctx, medium7, view.title(), x - 5f + 20f * ia + 29f * extending, y + 5f + 11f * extending,
                        alpha(textColor, 255f * a), extended ? expWidth - 30f : maxWidth + 5f, 0.85f);
                fadeText(ctx, regular7, view.artist(), x + 20f + 24f * extending, y + 5f + 19f * extending,
                        alpha(textColor, 178.5f * extending * a), extended ? expWidth - 30f : maxWidth + 5f, 0.85f);
                String pos = BkMusicUi.formatTime(positionMs / 1000L);
                String dur = view.duration() > 0 ? BkMusicUi.formatTime(view.duration() / 1000L) : "--:--";
                text(ctx, regular5, pos, sw() / 2f - expWidth / 2f + 11f * extending, y + 43f * extending, alpha(textColor, 255f * a));
                text(ctx, regular5, dur, sw() / 2f + expWidth / 2f - (9.5f + regular5.getStringWidth(dur) * extending), y + 43f * extending,
                        alpha(textColor, 255f * a));
                float barWidth = 116f;
                float barX = sw() / 2f - barWidth / 2f;
                float barY = y + expHeight - (showLyrics ? 45 : 0) - 36.5f * extending;
                Render2DEngine.drawRound(ctx, barX, barY, barWidth, 3f, 0.5f, alpha(textColor, 63.75f * a));
                float progressWidth = barWidth * (view.duration() > 0L ? Math.min(1f, (float) positionMs / view.duration()) : 0f);
                if (progressWidth > 0.5f) Render2DEngine.drawRound(ctx, barX, barY, progressWidth, 3f, 0.5f, alpha(textColor, 150f * a));
                pausingAnim.update(view.playing() ? 1f : 0f);

                if (extending > 0.7f) {
                    float controlY = y + expHeight - 25f * extending;
                    float[] mouse = localMouse();
                    float mx = mouse[0], my = mouse[1] - DOCK_Y;
                    float prevX = sw() / 2f - 40f, pauseX = sw() / 2f - 8f, nextX = sw() / 2f + 24f;
                    boolean hp = inside(mx, my, prevX, controlY, 16, 16), hz = inside(mx, my, pauseX, controlY, 16, 16),
                            hn = inside(mx, my, nextX, controlY, 16, 16);
                    hoverPrevious.update(hp);
                    hoverPause.update(hz);
                    hoverNext.update(hn);
                    BkMusicUi.icon(ctx, BkMusicUi.PREVIOUS, prevX, controlY, 16, alpha(textColor, (255f - 100f * hoverPrevious.get()) * a));
                    float anim = pausingAnim.get();
                    float cx = pauseX + 8f, cy = controlY + 8f;
                    var m = ctx.getMatrices();
                    m.pushMatrix();
                    m.translate(cx, cy);
                    m.rotate((float) Math.toRadians(90f * anim));
                    m.scale(Math.max(0.0001f, 1f - anim));
                    m.translate(-cx, -cy);
                    BkMusicUi.icon(ctx, BkMusicUi.PLAY, pauseX, controlY, 16,
                            alpha(textColor, Math.max(0f, 255f * (1f - anim) - 100f * hoverPause.get()) * a));
                    m.popMatrix();
                    m.pushMatrix();
                    m.translate(cx, cy);
                    m.rotate((float) Math.toRadians(-90f + 90f * anim));
                    m.scale(Math.max(0.0001f, anim));
                    m.translate(-cx, -cy);
                    BkMusicUi.icon(ctx, BkMusicUi.PAUSE, pauseX, controlY, 16,
                            alpha(textColor, Math.max(0f, 255f * anim - 100f * hoverPause.get()) * a));
                    m.popMatrix();
                    BkMusicUi.icon(ctx, BkMusicUi.NEXT, nextX, controlY, 16, alpha(textColor, (255f - 100f * hoverNext.get()) * a));
                }

                BkMusicUi.icon(ctx, SPOTIFY_ICON, x + expWidth - 24f, y + expHeight - 23f, 12f, alpha(WHITE, 255f * a));

                SpotifyModels.Repeat repeat = view.live() ? SpotifyPlayer.state().repeat() : SpotifyModels.Repeat.off;
                BkMusicUi.icon(ctx, repeat == SpotifyModels.Repeat.track ? REPEAT_ONE : REPEAT, x + 14f, y + expHeight - 21f, 8f,
                        alpha(textColor, (repeat == SpotifyModels.Repeat.off ? 150f : 255f) * a));

                if (synced == null) showLyrics = false;
                if (synced != null) {
                    float[] r = lyricsButton(x, y, expWidth, expHeight);
                    text(ctx, BkFonts.MEDIUM.get(6f), "TXT", r[0], r[1] + 1f, alpha(textColor, (showLyrics ? 255f : 150f) * a));
                }

                if (showLyrics && synced != null) {
                    List<Lyrics.Line> lines = synced.lines();
                    int activeIndex = synced.synced() ? synced.indexAt(positionMs) : -1;
                    int from = Math.max(0, Math.min(synced.synced() ? activeIndex : lyricsOffset, Math.max(0, lines.size() - 6)));
                    int maxLines = Math.min(6, lines.size());
                    for (int i = 0; i < maxLines && from + i < lines.size(); i++) {
                        int idx = from + i;
                        boolean current = idx == activeIndex;
                        fadeText(ctx, BkFonts.REGULAR.get(current ? 6.4f : 6f), lines.get(idx).text(), x + 10f, y + 55f + i * 7,
                                current ? alpha(WHITE, 255f * a) : alpha(textColor, 150f * extending * a), expWidth - 20f, 0.91f);
                    }
                }
            }

            Color waveColor = accent();
            long seconds = positionMs / 1000L;
            for (int i = 0; i < waveAnims.length; i++) {
                float phase = seconds * 8f + i * 0.7f;
                float sz = view.playing() ? (float) (2.0 + Math.abs(Math.sin(phase)) * 8.0) : 3f;
                waveAnims[i].update(sz);
                float wv = waveAnims[i].get();
                float barX = x + lerp(size.width, expWidth - 10f, extending) - 2f - 10f * ia + i * (2f + extending);
                float barY = y + lerp(4.25f, 14f, extending) + (7f - wv) / 2f;
                Render2DEngine.drawRound(ctx, barX, barY, 1f + extending, wv, 0.5f, alpha(waveColor, 255f * a));
            }
        }

        @Override
        void click(float mx, float my, int button) {
            if (view == null || !view.present()) return;
            float x = sw() / 2f - size.width / 2f;
            float y = 0f;
            float w = width, h = height;
            if (inside(mx, my, x + w / 2f - 40f, y + h - 9f - 16f, 16, 16)) MenuMusicPanel.previous();
            if (inside(mx, my, x + w / 2f - 8f, y + h - 9f - 16f, 16, 16)) MenuMusicPanel.toggle();
            if (inside(mx, my, x + w / 2f + 24f, y + h - 9f - 16f, 16, 16)) MenuMusicPanel.next();
            if (inside(mx, my, x + 14f, y + h - 21f, 8, 8) && view.live() && SpotifyAuth.authorised())
                SpotifyPlayer.repeat(SpotifyPlayer.state().repeat().next());
            float[] r = lyricsButton(x, y, w, h);
            if (lyrics() != null && inside(mx, my, r[0], r[1], r[2], r[3])) {
                showLyrics = !showLyrics;
                if (showLyrics) lyricsOffset = 0;
            }
        }

        private float[] lyricsButton(float x, float y, float w, float h) {
            return new float[]{x + w - 40f, y + h - 21f, 14f, 8f};
        }
    }

    /* --- idle ---------------------------------------------------------- */

    final class DefaultStatus extends Status {
        static final float LOGO_SIZE = 13f;

        @Override
        boolean enabled() {
            return true;
        }

        @Override
        boolean canShow() {
            return mc.world != null && mc.player != null;
        }

        @Override
        void draw(DrawContext ctx, float a) {
            String label = "GlobalDLC";
            FontRenderer font = BkFonts.MEDIUM.get(7f);
            float padding = 8f, gap = 5f;
            width = padding * 2f + LOGO_SIZE + gap + 1f + gap + font.getStringWidth(label);
            height = 15f;
            float left = sw() / 2f - width / 2f;
            float cursor = left + padding;
            float centerY = height / 2f;
            BkMusicUi.icon(ctx, TextureStorage.miniLogo, cursor - 1f, centerY - LOGO_SIZE / 2f - 0.2f, LOGO_SIZE, alpha(WHITE, 255f * a));
            cursor += LOGO_SIZE + gap;
            Render2DEngine.drawRound(ctx, cursor, centerY - 2.5f, 1f, 5f, 0.5f, alpha(WHITE, 64f * a));
            cursor += 1f + gap;
            text(ctx, font, label, cursor, centerY - textHeight(7f) / 2f, alpha(WHITE, 220f * a));
        }
    }

    // BK's order: TinyTask, Config, Notification, Displacement, Blink, PVP, Xray, Music, Default
    private final ConfigStatus configStatus = new ConfigStatus();
    private final NotificationStatus notificationStatus = new NotificationStatus();
    private final BlinkStatus blinkStatus = new BlinkStatus();
    private final PvpStatus pvpStatus = new PvpStatus();
    private final MusicStatus musicStatus = new MusicStatus();
    private final DefaultStatus defaultStatus = new DefaultStatus();
    private final List<Status> statuses = List.of(configStatus, notificationStatus, blinkStatus, pvpStatus, musicStatus, defaultStatus);

    /* ------------------------------------------------------------------ */
    /* Dock state                                                          */
    /* ------------------------------------------------------------------ */

    /** BK's IslandSize - the eased size the capsule is drawn at. */
    private static final class Size {
        float width = 48f, height = 15f;
    }

    private final Size size = new Size();
    private boolean extended;
    private final Anim extendingAnim = new Anim(200L, 0f, Easing.LINEAR);
    private final Anim widthAnim = new Anim(500L, 48f, BAKEK_SIZE);
    private final Anim heightAnim = new Anim(500L, 15f, BAKEK_SIZE);
    private final Anim showPing = new Anim(500L, 0f, BAKEK);
    private final Anim bubbleAnimation = new Anim(650L, 0f, FIGMA_EASE_IN_OUT);
    private float bubbleOffset;
    private Status last;
    private float x, y;

    private Status active() {
        List<Status> list = new ArrayList<>();
        for (Status s : statuses) if (s.enabled() && s.canShow()) list.add(s);
        if (list.size() > 1) list.remove(defaultStatus);
        return list.isEmpty() ? null : list.getFirst();
    }

    private float scaleValue() {
        return (1f + 0.5f * Math.clamp(islandScale.getValue(), 1f, 2f)) * COMPACT_SCALE;
    }

    private static float sw() {
        return mc.getWindow().getScaledWidth();
    }

    /* ------------------------------------------------------------------ */
    /* Render                                                              */
    /* ------------------------------------------------------------------ */

    @Override
    public void onRender2D(DrawContext context) {
        super.onRender2D(context);
        forgetRevivedPlayers();

        float scale = scaleValue();
        float centerX = sw() / 2f;
        float centerY = DOCK_Y + size.height / 2f;
        var m = context.getMatrices();
        m.pushMatrix();
        m.translate(centerX, centerY);
        m.scale(scale, scale);
        m.translate(-centerX, -centerY);
        try {
            renderComponent(context);
        } finally {
            m.popMatrix();
        }

        float sw = size.width * scale, sh = size.height * scale;
        setBounds(centerX - sw / 2f, DOCK_Y + (size.height - sh) / 2f, sw, sh);
    }

    private void renderComponent(DrawContext ctx) {
        Status status = active();
        if (status == null) return;
        if (status != last) extended = false;

        x = sw() / 2f - size.width / 2f;
        y = DOCK_Y;

        this.bubbleAnimation.update(liquidBubble.getValue() && !(status instanceof DefaultStatus) && !extended);
        float bubbleProgress = Math.clamp(bubbleAnimation.get(), 0f, 1f);
        float bubbleX = size.width - 7.5f + 19f * bubbleProgress;
        bubbleOffset = 19f * bubbleProgress;

        float alpha = 1f;
        boolean accentGlow = !(status instanceof NotificationStatus ns && ns.hasNotification());
        if (bubbleProgress > 0.001f) BkIslandMorph.draw(ctx, x, y, size.width, size.height, bubbleProgress, alpha);
        else BkLiquidGlass.panel(ctx, x, y, size.width, size.height, Math.min(7f, size.height / 2f), alpha);
        if (accentGlow) BkMusicUi.accentGlow(ctx, x, y, size.width, size.height, alpha, 7f);

        drawClockAndConnection(ctx);

        // the logo shows up only once the bubble has cleared the capsule's text
        float logoAlpha = Math.clamp((bubbleProgress - 0.7f) / 0.3f, 0f, 1f);
        if (logoAlpha > 0f)
            BkMusicUi.icon(ctx, TextureStorage.miniLogo, x + bubbleX - 6f + 0.5f, y + size.height / 2f - 5.7f, 11f,
                    alpha(WHITE, 255f * logoAlpha * alpha));

        for (Status s : statuses) s.animation.update(status == s ? 1f : 0f);

        var m = ctx.getMatrices();
        m.pushMatrix();
        m.translate(0f, DOCK_Y);
        Render2DEngine.addWindow(ctx, x, 0f, x + size.width, size.height, 1.0);
        try {
            if (status.animation.get() == 1f) {
                status.draw(ctx, 1f);
            } else {
                for (Status s : statuses) {
                    if (s.animation.get() > 0f) s.draw(ctx, Math.clamp(s.animation.get(), 0f, 1f));
                }
            }
        } finally {
            Render2DEngine.popWindow();
            m.popMatrix();
        }

        widthAnim.update(status.width);
        heightAnim.update(status.height);
        size.width = widthAnim.get();
        size.height = heightAnim.get();
        extendingAnim.update(extended ? 1f : 0f);
        last = status;

        if (!status.extendable()) extended = false;
        if (!(mc.currentScreen instanceof ChatScreen) && mc.player != null) extended = false;
    }

    private String clockText;
    private long clockSecond = Long.MIN_VALUE;

    private String clockText() {
        long second = Math.floorDiv(System.currentTimeMillis(), 1000L);
        if (clockText == null || second != clockSecond) {
            clockSecond = second;
            LocalTime t = LocalTime.now();
            clockText = String.format("%02d:%02d", t.getHour(), t.getMinute());
        }
        return clockText;
    }

    private void drawClockAndConnection(DrawContext ctx) {
        if (mc.player == null) return;
        float sideAlpha = 1f - extendingAnim.get();
        // BK samples the world under the island to pick black or white; the HUD here draws over a deferred
        // frame that cannot be read back mid-frame, so the island keeps BK's default white.
        Color elementColor = alpha(WHITE, 255f * sideAlpha);
        FontRenderer font = BkFonts.MEDIUM.get(7f);
        String time = clockText();
        float clockX = swapSides.getValue() ? x + size.width + bubbleOffset + 7 : x - font.getStringWidth(time) - 4;
        if (clockVisible.getValue()) text(ctx, font, time, clockX, y + 5f, elementColor);
        if (!connectionVisible.getValue()) return;
        float connectionX = swapSides.getValue() ? x - 42 : x + size.width + bubbleOffset;

        var entry = mc.getNetworkHandler() == null ? null : mc.getNetworkHandler().getPlayerListEntry(mc.player.getUuid());
        if (mc.isInSingleplayer() || entry == null) {
            BkMusicUi.icon(ctx, AIRPLANE_ICON, connectionX + 7f, y + 2.5f, 10f, elementColor);
            return;
        }

        float baseX = connectionX + 4f;
        float[] mouse = localMouse();
        showPing.update(inside(mouse[0], mouse[1], baseX + 4f * showPing.get(), y + 5f, 12.8f, 7f));
        int ping = entry.getLatency();
        float reveal = showPing.get();
        text(ctx, font, ping + " ms", baseX + 4f * reveal, y + 5f, alpha(elementColor, elementColor.getAlpha() * reveal));
        for (int i = 0; i < 4; i++) {
            float barX = connectionX + 9f + i * 2.7f + 4f * reveal;
            float barY = y + 8f - i;
            Render2DEngine.drawRound(ctx, barX, barY, 2f, 3f + i, 0.1f, alpha(elementColor, elementColor.getAlpha() * 0.2f * (1f - reveal)));
            if (ping < PING_THRESHOLDS[i])
                Render2DEngine.drawRound(ctx, barX, barY, 2f, 3f + i, 0.1f, alpha(elementColor, elementColor.getAlpha() * (1f - reveal)));
        }
    }

    /* ------------------------------------------------------------------ */
    /* Input                                                               */
    /* ------------------------------------------------------------------ */

    /** The cursor in the island's unscaled coordinates (the same space everything above is drawn in). */
    private float[] localMouse() {
        float scale = Math.max(0.001f, scaleValue());
        float mx = (float) mc.mouse.getScaledX(mc.getWindow());
        float my = (float) mc.mouse.getScaledY(mc.getWindow());
        float centerX = sw() / 2f;
        float centerY = DOCK_Y + size.height / 2f;
        return new float[]{centerX + (mx - centerX) / scale, centerY + (my - centerY) / scale};
    }

    private boolean islandHovered() {
        float[] p = localMouse();
        return inside(p[0], p[1], sw() / 2f - size.width / 2f, DOCK_Y, size.width, size.height);
    }

    @Override
    public void onMouse(@NotNull EventMouse event) {
        super.onMouse(event);
        // BK resolves the island on release, and only while the cursor is free (chat)
        if (event.getAction() != GLFW.GLFW_RELEASE || !(mc.currentScreen instanceof ChatScreen)) return;
        handleClick(event.getButton());
    }

    private void handleClick(int button) {
        boolean hovered = islandHovered();
        Status active = active();
        if (extended) {
            if (!hovered) {
                extended = false;
                if (mc.currentScreen instanceof ChatScreen) mc.setScreen(null);
            } else if (active != null) {
                float[] p = localMouse();
                active.click(p[0], p[1] - DOCK_Y, button);
            }
            return;
        }
        if (!hovered || active == null || !active.extendable()) return;
        extended = true;
    }

    /* ------------------------------------------------------------------ */
    /* Feeds: module toggles, kills, totem pops                            */
    /* ------------------------------------------------------------------ */

    private static final long CREDIT_MS = 5000L;
    private PlayerEntity lastHit;
    private long lastHitAt;
    private final java.util.Set<Integer> reportedDeaths = new java.util.HashSet<>();

    /** Called by {@link Module} when a module is switched on or off by hand. BK: "Name enabled/disabled", 1 s. */
    public void onToggle(Module module, boolean on) {
        if (!isEnabled() || !modules.getValue() || module == this) return;
        notify(on ? NotificationType.SUCCESS : NotificationType.ERROR,
                module.getName().replace(" ", "") + (on ? " włączony" : " wyłączony"), 1000L);
    }

    @EventHandler
    public void onAttack(EventAttack e) {
        Entity target = e.getEntity();
        if (target instanceof PlayerEntity p && p != mc.player && p.getHealth() > 0 && realPlayer(p)) {
            lastHit = p;
            lastHitAt = System.currentTimeMillis();
        }
    }

    private boolean mine(PlayerEntity p) {
        return lastHit != null && p.getId() == lastHit.getId() && System.currentTimeMillis() - lastHitAt <= CREDIT_MS;
    }

    /** A real player, not a server NPC: a valid name and a tab-list entry. */
    private static boolean realPlayer(PlayerEntity p) {
        String name = p.getName().getString();
        if (!name.matches("[A-Za-z0-9_]{3,16}")) return false;
        var handler = mc.getNetworkHandler();
        return handler == null || handler.getPlayerListEntry(p.getUuid()) != null;
    }

    @EventHandler
    public void onDeath(EventDeath e) {
        PlayerEntity p = e.getPlayer();
        if (p == null || p == mc.player || !reportedDeaths.add(p.getId())) return;
        boolean yours = mine(p) && realPlayer(p);
        if (yours) lastHit = null;
        if (!kills.getValue() || !yours) return;
        notify(NotificationType.ERROR, "Zabiłeś " + p.getName().getString(), 2500L);
    }

    @EventHandler
    public void onPop(TotemPopEvent e) {
        PlayerEntity p = e.getEntity();
        if (!pops.getValue() || p == null || p == mc.player || !realPlayer(p)) return;
        boolean yours = mine(p);
        if (onlyMine.getValue() && !yours) return;
        String count = e.getPops() > 1 ? " #" + e.getPops() : "";
        notify(NotificationType.INFO, p.getName().getString() + " popnął totem" + count, 2500L);
    }

    private void forgetRevivedPlayers() {
        if (mc.world == null || reportedDeaths.isEmpty()) return;
        reportedDeaths.removeIf(id -> {
            Entity ent = mc.world.getEntityById(id);
            return ent == null || (ent instanceof PlayerEntity pl && pl.getHealth() > 0);
        });
    }

    /* ------------------------------------------------------------------ */
    /* Drawing helpers                                                     */
    /* ------------------------------------------------------------------ */

    private static Color accent() {
        return BkMusicUi.accent();
    }

    private static Color alpha(Color c, float a255) {
        return new Color(c.getRed(), c.getGreen(), c.getBlue(), Math.max(0, Math.min(255, Math.round(a255))));
    }

    /** BK's MSDF font height (size * 0.7). */
    private static float textHeight(float size) {
        return size * 0.7f;
    }

    private static void text(DrawContext ctx, FontRenderer font, String s, float x, float y, Color c) {
        if (s == null || s.isEmpty() || c.getAlpha() <= 2) return;
        font.drawString(ctx, s, x, y, c.getRGB());
    }

    /** BK's drawFadeoutText: text that runs past {@code maxWidth} fades out over its last stretch instead of clipping hard. */
    private static void fadeText(DrawContext ctx, FontRenderer font, String s, float x, float y, Color c, float maxWidth, float fadeFrom) {
        if (s == null || s.isEmpty() || c.getAlpha() <= 2) return;
        if (font.getStringWidth(s) <= maxWidth) {
            font.drawString(ctx, s, x, y, c.getRGB());
            return;
        }
        float start = maxWidth * fadeFrom;
        float cursor = 0f;
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            String ch = new String(Character.toChars(cp));
            float w = font.getStringWidth(ch);
            if (cursor + w > maxWidth) break;
            float mid = cursor + w / 2f;
            float f = mid <= start ? 1f : Math.max(0f, 1f - (mid - start) / Math.max(1f, maxWidth - start));
            if (f > 0.01f) font.drawString(ctx, ch, x + cursor, y, alpha(c, c.getAlpha() * f).getRGB());
            cursor += w;
            i += Character.charCount(cp);
        }
    }

    /** BK's RingRenderer.progress: a full track ring, then the filled arc from the top, clockwise. */
    private static void ring(DrawContext ctx, float cx, float cy, float radius, float thickness, float progress, Color track, Color fill) {
        arc(ctx, cx, cy, radius, thickness, 0f, 360f, track);
        float p = Math.max(0f, Math.min(1f, progress));
        if (p > 0f) arc(ctx, cx, cy, radius, thickness, 0f, 360f * p, fill);
    }

    private static void arc(DrawContext ctx, float cx, float cy, float radius, float thickness, float start, float sweep, Color color) {
        if (radius <= 0 || thickness <= 0 || sweep <= 0 || color.getAlpha() <= 0) return;
        thickness = Math.min(thickness, radius);
        float outer = radius, inner = radius - thickness;
        int segments = Math.max(6, Math.round(sweep / 360f * 48f));
        int argb = color.getRGB();
        var pose = GuiRender.pose(ctx);
        GuiRender.quads(ctx, Render2DEngine.currentClip(), vc -> {
            for (int i = 0; i < segments; i++) {
                double a0 = Math.toRadians(start - 90f + sweep * i / segments);
                double a1 = Math.toRadians(start - 90f + sweep * (i + 1) / segments);
                float c0 = (float) Math.cos(a0), s0 = (float) Math.sin(a0), c1 = (float) Math.cos(a1), s1 = (float) Math.sin(a1);
                GuiRender.quad(vc, pose,
                        cx + c0 * outer, cy + s0 * outer,
                        cx + c0 * inner, cy + s0 * inner,
                        cx + c1 * inner, cy + s1 * inner,
                        cx + c1 * outer, cy + s1 * outer,
                        argb, argb, argb, argb);
            }
        });
    }

    private static boolean inside(float mx, float my, float x, float y, float w, float h) {
        return mx >= x && mx <= x + w && my >= y && my <= y + h;
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }
}
