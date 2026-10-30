package thunder.hack.features.hud.impl;

import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import thunder.hack.events.impl.EventAttack;
import thunder.hack.events.impl.EventDeath;
import thunder.hack.events.impl.TotemPopEvent;
import thunder.hack.features.hud.HudElement;
import thunder.hack.features.modules.Module;
import thunder.hack.gui.font.FontRenderers;
import thunder.hack.setting.Setting;
import thunder.hack.utility.UiSounds;
import thunder.hack.utility.render.Render2DEngine;

import java.awt.Color;
import java.util.ArrayDeque;

/**
 * A black pill at the top of the screen that grows to tell you what is happening - a kill, a totem pop, a
 * module switched on or off - and, while you are in a fight, stays open as a live "Walczysz z X" card with the
 * opponent's health. Between events it shrinks back to a small idle pill.
 *
 * <p>Whether a kill or a pop was YOURS is decided by who you hit last: the attack event remembers the target and
 * when, and a death or pop of that same player within {@link #CREDIT_MS} counts as yours. The same window is
 * what "in a fight" means.
 *
 * <p>Size is eased, never snapped: the pill reshapes first and the text fades in once the shape has nearly
 * arrived, so it reads as one surface morphing rather than a box appearing.
 */
public class DynamicIsland extends HudElement {
    public DynamicIsland() {
        super("DynamicIsland", (int) IDLE_W, (int) IDLE_H, 0.46f, 0.008f);
    }

    private final Setting<Boolean> kills = new Setting<>("Kills", true);
    private final Setting<Boolean> pops = new Setting<>("TotemPops", true);
    /** Off: also show pops of players you were not fighting. */
    private final Setting<Boolean> onlyMine = new Setting<>("OnlyMine", true);
    private final Setting<Boolean> modules = new Setting<>("Modules", true);
    /** The live card while you are fighting someone: their name and health. */
    private final Setting<Boolean> fight = new Setting<>("Fight", true);
    /** The small pill while nothing is happening. Off: the island only appears when it has something to say. */
    private final Setting<Boolean> idle = new Setting<>("IdlePill", true);
    private final Setting<Float> duration = new Setting<>("Duration", 2.8f, 1f, 8f);
    private final Setting<Boolean> sound = new Setting<>("Sound", true);

    private static final float IDLE_W = 64f, IDLE_H = 16f;
    private static final float OPEN_H = 32f;
    private static final long CREDIT_MS = 5000L;
    /** Module toggles are quick glances, shorter than the fight news. */
    private static final float TOGGLE_SECONDS = 1.4f;

    private enum Kind {KILL, POP, ON, OFF, FIGHT}

    /** @param who the player whose head is shown, or null for module notes */
    private record Message(Kind kind, String title, String subtitle, Color accent, java.util.UUID who) {
    }

    private final ArrayDeque<Message> queue = new ArrayDeque<>();
    private Message showing;
    private long shownAt;

    /** Eased size of the pill and opacity of its content. */
    private float w = IDLE_W, h = IDLE_H, content;
    private long lastFrame;
    /** What the content is currently showing, kept while it fades out so the text does not vanish mid-fade. */
    private Message drawn;

    /** Who you hit last, and when - the basis for "yours" and for "in a fight". */
    private PlayerEntity lastHit;
    private long lastHitAt;

    /* ------------------------------------------------------------------ */
    /* Events                                                              */
    /* ------------------------------------------------------------------ */

    @EventHandler
    public void onAttack(EventAttack e) {
        Entity target = e.getEntity();
        // only a living, real player starts a fight - lobby NPCs and holograms are PlayerEntities too
        if (target instanceof PlayerEntity p && p != mc.player && p.getHealth() > 0 && realPlayer(p)) {
            lastHit = p;
            lastHitAt = System.currentTimeMillis();
        }
    }

    private boolean mine(PlayerEntity p) {
        return lastHit != null && p.getId() == lastHit.getId() && System.currentTimeMillis() - lastHitAt <= CREDIT_MS;
    }

    /**
     * A real player, not a server NPC: a valid Minecraft name and an entry in the tab list. NPCs (lobby
     * characters, shop keepers, hologram rigs) are PlayerEntities with odd generated names, usually not in
     * the tab list, and often sitting at 0 health - which is what produced "Zabiłeś <garbage>".
     */
    private static boolean realPlayer(PlayerEntity p) {
        String name = p.getName().getString();
        if (!name.matches("[A-Za-z0-9_]{3,16}")) return false;
        var handler = mc.getNetworkHandler();
        return handler == null || handler.getPlayerListEntry(p.getUuid()) != null;
    }

    /** Deaths already reported - EventDeath fires every tick for as long as the body is on the ground. */
    private final java.util.Set<Integer> reportedDeaths = new java.util.HashSet<>();

    @EventHandler
    public void onDeath(EventDeath e) {
        PlayerEntity p = e.getPlayer();
        if (p == null || p == mc.player || !reportedDeaths.add(p.getId())) return;
        boolean yours = mine(p) && realPlayer(p);
        if (yours) lastHit = null; // the fight is over
        if (!kills.getValue() || !yours) return;
        push(new Message(Kind.KILL, "Zabiłeś " + p.getName().getString(), "Eliminacja", new Color(255, 92, 92), p.getUuid()));
    }

    @EventHandler
    public void onPop(TotemPopEvent e) {
        PlayerEntity p = e.getEntity();
        if (!pops.getValue() || p == null || p == mc.player || !realPlayer(p)) return;
        boolean yours = mine(p);
        if (onlyMine.getValue() && !yours) return;
        String name = p.getName().getString();
        String count = e.getPops() > 1 ? "  #" + e.getPops() : "";
        push(new Message(Kind.POP, yours ? "Popnąłeś totem " + name : name + " popnął totem",
                "Totem of Undying" + count, new Color(255, 205, 80), p.getUuid()));
    }

    /** Called by {@link Module} when a module is switched on or off by hand. */
    public void onToggle(Module module, boolean on) {
        if (!isEnabled() || !modules.getValue() || module == this) return;
        // a quick flick off-and-on replaces the pending note for that module instead of queueing both
        queue.removeIf(m -> (m.kind() == Kind.ON || m.kind() == Kind.OFF) && m.title().equals(module.getName()));
        push(new Message(on ? Kind.ON : Kind.OFF, module.getName(), on ? "Włączono" : "Wyłączono",
                on ? new Color(90, 220, 120) : new Color(255, 95, 95), null));
    }

    private void push(Message m) {
        // No queue: the newest news replaces whatever is showing, at once. Queued messages each waited their
        // full time, so a few quick toggles made the island lag seconds behind what was actually happening.
        queue.clear();
        queue.addLast(m);
    }

    /** The live fight card, or null when you are not fighting anyone. */
    private Message fightCard() {
        if (!fight.getValue() || lastHit == null) return null;
        if (System.currentTimeMillis() - lastHitAt > CREDIT_MS || !lastHit.isAlive() || lastHit.isRemoved()) return null;
        float hp = lastHit.getHealth() + lastHit.getAbsorptionAmount();
        float frac = Math.clamp(hp / Math.max(1f, lastHit.getMaxHealth()), 0f, 1f);
        Color c = frac > 0.5f
                ? Render2DEngine.interpolateColorC(new Color(255, 205, 80), new Color(90, 220, 120), (frac - 0.5f) * 2f)
                : Render2DEngine.interpolateColorC(new Color(255, 92, 92), new Color(255, 205, 80), frac * 2f);
        return new Message(Kind.FIGHT, "Walczysz z " + lastHit.getName().getString(),
                String.format("%.1f HP", hp), c, lastHit.getUuid());
    }

    /* ------------------------------------------------------------------ */
    /* Render                                                              */
    /* ------------------------------------------------------------------ */

    @Override
    public void onRender2D(DrawContext context) {
        super.onRender2D(context);

        long now = System.currentTimeMillis();
        float dt = lastFrame == 0L ? 16f : Math.min(100f, now - lastFrame);
        lastFrame = now;

        // a player back on their feet can die again; forget them once they are alive (or gone)
        if (mc.world != null && !reportedDeaths.isEmpty())
            reportedDeaths.removeIf(id -> {
                Entity ent = mc.world.getEntityById(id);
                return ent == null || (ent instanceof PlayerEntity pl && pl.getHealth() > 0);
            });

        if (showing != null) {
            float seconds = showing.kind() == Kind.ON || showing.kind() == Kind.OFF ? TOGGLE_SECONDS : duration.getValue();
            if (now - shownAt > seconds * 1000f) showing = null;
        }
        // a new message takes over immediately, even mid-display - the pill just morphs to the new size
        if (!queue.isEmpty()) {
            showing = queue.pollFirst();
            shownAt = now;
            if (sound.getValue()) UiSounds.play(UiSounds.Sound.ISLAND);
        }

        // news first; with nothing new the island falls back to the fight it is tracking
        Message current = showing != null ? showing : fightCard();
        if (current != null) drawn = current;

        float targetW = IDLE_W, targetH = IDLE_H;
        if (current != null) {
            float textW = Math.max(FontRenderers.sf_bold.getStringWidth(current.title()),
                    FontRenderers.sf_medium_mini.getStringWidth(current.subtitle()));
            targetW = 10f + 16f + 10f + textW + 16f;
            targetH = OPEN_H;
        }

        // ease: fast to start, soft landing; size first, content after
        float k = 1f - (float) Math.pow(0.0001, dt / 1000f * 2.2f);
        w += (targetW - w) * k;
        h += (targetH - h) * k;
        float arrived = current == null ? 0f : Math.clamp(1f - Math.abs(targetW - w) / Math.max(8f, Math.abs(targetW - IDLE_W)), 0f, 1f);
        float contentTarget = current != null && arrived > 0.8f && Math.abs(targetH - h) < 4f ? 1f : 0f;
        content += (contentTarget - content) * Math.min(1f, dt / 90f);

        boolean visible = current != null || content > 0.02f || idle.getValue() || Math.abs(w - IDLE_W) > 1f;
        float cx = getPosX() + IDLE_W / 2f;
        float x = cx - w / 2f, y = getPosY();
        setBounds(getPosX(), getPosY(), IDLE_W, IDLE_H);
        if (!visible) return;

        float r = h / 2f;
        Render2DEngine.drawRoundedShadow(context, x, y, w, h, r, 6f, 110);
        Render2DEngine.drawRound(context, x, y, w, h, r, new Color(8, 8, 10, 240));

        if (drawn == null || content <= 0.02f) return;
        int a = Math.round(255 * content);

        drawIcon(context, drawn, x + 10f + 8f, y + h / 2f, a);

        float tx = x + 10f + 16f + 8f;
        FontRenderers.sf_bold.drawString(context, drawn.title(), tx, y + 8f, new Color(255, 255, 255, a).getRGB());
        FontRenderers.sf_medium_mini.drawString(context, drawn.subtitle(), tx, y + 20f,
                Render2DEngine.injectAlpha(drawn.accent(), Math.round(a * 0.9f)).getRGB());
    }

    /* ------------------------------------------------------------------ */
    /* Icons                                                               */
    /* ------------------------------------------------------------------ */

    /** The player's skin from the tab list, or null once they have left (the drawn icon is used instead). */
    private static net.minecraft.util.Identifier skinOf(java.util.UUID uuid) {
        var handler = mc.getNetworkHandler();
        if (handler == null) return null;
        var entry = handler.getPlayerListEntry(uuid);
        return entry == null ? null : entry.getSkinTextures().body().texturePath();
    }

    /**
     * The event's icon, centred on (cx, cy): a round chip in the event's colour with a white glyph drawn from
     * shapes - crossed swords for a fight or a kill, a totem figure for a pop, a switch for a module. Drawn,
     * not item sprites, so it stays sharp at any GUI scale and matches the rest of the pill.
     */
    private void drawIcon(DrawContext context, Message m, float cx, float cy, int a) {
        float r = 8f;
        Color accent = m.accent();

        // a fight, a kill or a pop shows the other player's face: a thin ring in the event's colour, then the
        // skin's face with its hat layer on top - the same two regions PlayerHead draws
        net.minecraft.util.Identifier skin = m.who() != null ? skinOf(m.who()) : null;
        if (skin != null && a > 150) {
            Render2DEngine.drawRound(context, cx - r - 1f, cy - r - 1f, r * 2f + 2f, r * 2f + 2f, 4f,
                    Render2DEngine.injectAlpha(accent, a));
            float s = r * 2f;
            Render2DEngine.renderTexture(context, skin, cx - r, cy - r, s, s, 8, 8, 8, 8, 64, 64);
            Render2DEngine.renderTexture(context, skin, cx - r, cy - r, s, s, 40, 8, 8, 8, 64, 64);
            return;
        }


        Render2DEngine.drawRound(context, cx - r, cy - r, r * 2f, r * 2f, r, Render2DEngine.injectAlpha(accent, Math.round(a * 0.28f)));
        Color ink = new Color(255, 255, 255, a);
        int inkRgb = ink.getRGB();

        switch (m.kind()) {
            case KILL, FIGHT -> {
                // two blades crossing, each with a short guard near its hilt
                float s = 4.6f;
                Render2DEngine.drawThickLine(context, cx - s, cy + s, cx + s, cy - s, inkRgb, 1.6f);
                Render2DEngine.drawThickLine(context, cx + s, cy + s, cx - s, cy - s, inkRgb, 1.6f);
                float g = 1.8f, hx = cx - s * 0.55f, hy = cy + s * 0.55f;
                Render2DEngine.drawThickLine(context, hx - g, hy - g, hx + g, hy + g, inkRgb, 1.4f);
                hx = cx + s * 0.55f;
                Render2DEngine.drawThickLine(context, hx - g, hy + g, hx + g, hy - g, inkRgb, 1.4f);
                if (m.kind() == Kind.KILL) {
                    // a small spark at the crossing marks the finished fight
                    Render2DEngine.drawRound(context, cx - 1.6f, cy - 1.6f, 3.2f, 3.2f, 1.6f, accent);
                }
            }
            case POP -> {
                // totem figure: head, body, outstretched wings
                Render2DEngine.drawRound(context, cx - 2.2f, cy - 5.6f, 4.4f, 4.4f, 2.2f, ink);
                Render2DEngine.drawRound(context, cx - 2.4f, cy - 1.2f, 4.8f, 6.6f, 1.6f, ink);
                Render2DEngine.drawThickLine(context, cx - 2.4f, cy + 0.2f, cx - 5.6f, cy - 2.4f, inkRgb, 1.5f);
                Render2DEngine.drawThickLine(context, cx + 2.4f, cy + 0.2f, cx + 5.6f, cy - 2.4f, inkRgb, 1.5f);
            }
            case ON, OFF -> {
                // a little switch: the track, and the knob on the side it is set to
                boolean on = m.kind() == Kind.ON;
                float tw = 10f, th = 5.6f;
                Render2DEngine.drawRound(context, cx - tw / 2f, cy - th / 2f, tw, th, th / 2f,
                        on ? Render2DEngine.injectAlpha(accent, a) : new Color(255, 255, 255, Math.round(a * 0.35f)));
                float knob = th - 1.6f;
                float kx = on ? cx + tw / 2f - knob - 0.8f : cx - tw / 2f + 0.8f;
                Render2DEngine.drawRound(context, kx, cy - knob / 2f, knob, knob, knob / 2f, ink);
            }
        }
    }
}
