package thunder.hack.features.modules.render;

import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import thunder.hack.events.impl.EventTick;
import thunder.hack.features.modules.Module;
import thunder.hack.setting.Setting;
import thunder.hack.setting.impl.ColorSetting;
import thunder.hack.utility.render.Render2DEngine;
import thunder.hack.utility.render.core.CloudanCullingSystem;
import thunder.hack.utility.render.core.CloudanGlowRenderer;
import thunder.hack.utility.render.core.CloudanOutlineRenderer;
import thunder.hack.utility.render.core.CloudanRenderCore;

import java.awt.Color;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;

/**
 * Backtrack, as a viewer.
 *
 * <p>It draws where entities have recently been - a trail of ghost positions behind them - and does
 * nothing else. No packets are held, delayed, reordered or dropped; the server's idea of where anybody
 * is never changes because this module is on. It is a window onto history the client already has.
 *
 * <p>That is the whole scope on purpose. The useful half of backtrack is <em>seeing</em> the lag you are
 * fighting through; the half that manipulates what the server believes is a different feature and not
 * one this builds towards.
 */
public class BacktrackTest extends Module {
    public BacktrackTest() {
        super("BacktrackTest", Category.RENDER);
    }

    /** How many ticks of history to keep per entity. */
    private final Setting<Integer> historyLength = new Setting<>("HistoryLength", 20, 2, 60);
    /** How many of them to actually draw, spread across the history. */
    private final Setting<Integer> ghostCount = new Setting<>("GhostCount", 6, 1, 20);
    private final Setting<RenderMode> renderMode = new Setting<>("RenderMode", RenderMode.Boxes);
    private final Setting<ColorSetting> color = new Setting<>("Color", new ColorSetting(0xFF6FD8FF));
    private final Setting<Integer> opacity = new Setting<>("Opacity", 120, 20, 255);
    private final Setting<Boolean> fade = new Setting<>("Fade", true);
    private final Setting<Float> range = new Setting<>("Range", 24f, 4f, 64f);
    private final Setting<Boolean> playersOnly = new Setting<>("PlayersOnly", true);

    public enum RenderMode {
        Boxes,
        Points,
        Both
    }

    /** Per-entity ring of past positions, newest last. */
    private final Map<Integer, ArrayDeque<Vec3d>> history = new HashMap<>();

    @Override
    public void onDisable() {
        history.clear();
    }

    @Override
    public void onLogout() {
        history.clear();
    }

    @EventHandler
    public void onTick(EventTick event) {
        if (fullNullCheck()) return;

        int keep = historyLength.getValue();

        for (Entity entity : mc.world.getEntities()) {
            if (entity == mc.player) continue;
            if (playersOnly.getValue() && !(entity instanceof PlayerEntity)) continue;
            if (mc.player.squaredDistanceTo(entity) > range.getPow2Value()) continue;

            ArrayDeque<Vec3d> trail = history.computeIfAbsent(entity.getId(), id -> new ArrayDeque<>());
            trail.addLast(entity.getEntityPos());
            while (trail.size() > keep) trail.removeFirst();
        }

        // Entities that left keep their trail alive until it would be misleading; dropping them on the
        // first missed tick makes the trail flicker whenever somebody clips a chunk border.
        history.keySet().removeIf(id -> mc.world.getEntityById(id) == null);
    }

    @Override
    public void onRender2D(DrawContext context) {
        if (fullNullCheck() || mc.options.hudHidden || history.isEmpty()) return;

        Color base = color.getValue().getColorObject();
        int alpha = opacity.getValue();
        int ghosts = CloudanRenderCore.budget(ghostCount.getValue());

        for (Map.Entry<Integer, ArrayDeque<Vec3d>> entry : history.entrySet()) {
            Entity entity = mc.world.getEntityById(entry.getKey());
            if (entity == null) continue;

            Vec3d[] trail = entry.getValue().toArray(new Vec3d[0]);
            if (trail.length < 2) continue;

            // Spread the drawn ghosts across the whole history instead of taking the newest N, so the
            // trail shows the shape of the path rather than a clump just behind the entity.
            int step = Math.max(1, trail.length / ghosts);

            for (int i = 0; i < trail.length; i += step) {
                Vec3d pos = trail[i];
                if (!CloudanCullingSystem.inRange(pos, range.getValue())) continue;

                float age = i / (float) Math.max(1, trail.length - 1);
                int a = fade.getValue() ? Math.round(alpha * age) : alpha;
                if (a <= 3) continue;

                Color tint = Render2DEngine.injectAlpha(base, a);

                if (renderMode.getValue() != RenderMode.Points) {
                    Box box = entity.getBoundingBox().offset(pos.subtract(entity.getEntityPos()));
                    CloudanOutlineRenderer.box(box, null, tint, 1f, range.getValue());
                }

                if (renderMode.getValue() != RenderMode.Boxes)
                    CloudanGlowRenderer.halo(pos.add(0, entity.getHeight() * 0.5, 0), 0.35, base,
                            (a / 255f) * 0.6f, true);
            }
        }
    }

    @Override
    public String getDisplayInfo() {
        return history.isEmpty() ? null : history.size() + "";
    }
}
