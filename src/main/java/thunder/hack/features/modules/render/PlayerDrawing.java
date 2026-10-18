package thunder.hack.features.modules.render;

import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import thunder.hack.features.modules.Module;
import thunder.hack.features.modules.client.HudEditor;
import thunder.hack.setting.Setting;
import thunder.hack.setting.impl.ColorSetting;
import thunder.hack.utility.render.Render2DEngine;
import thunder.hack.utility.render.Render3DEngine;
import thunder.hack.utility.render.core.CloudanCullingSystem;
import thunder.hack.utility.render.core.CloudanGlowRenderer;
import thunder.hack.utility.render.core.CloudanRenderCore;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Leaves a glowing copy of you standing where you were, once a second.
 *
 * <p>Each copy is a frozen snapshot: position, facing and limb pose at the moment it was taken. It does
 * not follow you, does not update and does not know you have moved - it is a drawing of a moment, which
 * is what makes a line of them read as a path you walked rather than as a ghost chasing you.
 *
 * <p>Built out of six boxes - head, body, two arms, two legs - laid out on the vanilla player
 * proportions and turned to the body yaw, with the arms and legs swung to the pose that was captured.
 * The alternative would be to clone the real {@code PlayerEntity} and run it through the entity
 * renderer, which is exactly what PopChams used to do and exactly what 1.21.6 removed.
 *
 * <p>Colour follows the client accent like everything else, so setting the client green turns the
 * drawings green.
 *
 * <p>Purely a renderer. Nothing is spawned, nothing is sent, and no other player can see any of it.
 */
public class PlayerDrawing extends Module {
    public PlayerDrawing() {
        super("PlayerDrawing", Category.RENDER);
    }

    /** Milliseconds between snapshots. */
    private final Setting<Integer> interval = new Setting<>("Interval", 1000, 100, 5000);
    /** How long one drawing lasts, in milliseconds. */
    private final Setting<Integer> duration = new Setting<>("Duration", 2500, 300, 10000);
    private final Setting<Integer> maxDrawings = new Setting<>("Max", 12, 1, 40);
    private final Setting<Mode> colorMode = new Setting<>("ColorMode", Mode.Sync);
    private final Setting<ColorSetting> color = new Setting<>("Color", new ColorSetting(0xFFFFFFFF), v -> colorMode.getValue() == Mode.Custom);
    private final Setting<Integer> opacity = new Setting<>("Opacity", 210, 20, 255);
    /**
     * Outline draws only the edges of the figure; Filled fills it in as well.
     *
     * <p>Outline is the default because a filled figure at this size is a bright blob - the shape is
     * only legible from its edges, and those edges are also what lets you see through one drawing to
     * the ones behind it.
     */
    private final Setting<Style> style = new Setting<>("Style", Style.Outline);
    /** Thickness of the outline bars, in blocks. */
    private final Setting<Float> outlineWidth = new Setting<>("OutlineWidth", 0.035f, 0.01f, 0.12f, v -> style.getValue() != Style.Filled);
    /** How much of the fill remains in Both mode - the edges are meant to stay the bright part. */
    private final Setting<Integer> fillOpacity = new Setting<>("FillOpacity", 60, 5, 255, v -> style.getValue() != Style.Outline);
    private final Setting<Boolean> glow = new Setting<>("Glow", true);
    /** Only leave a drawing when you have actually moved since the last one. */
    private final Setting<Boolean> onlyMoving = new Setting<>("OnlyMoving", false);
    /** Shrink each drawing as it fades, so it collapses instead of simply going out. */
    private final Setting<Boolean> shrink = new Setting<>("Shrink", false);
    private final Setting<Boolean> throughWalls = new Setting<>("ThroughWalls", false);
    private final Setting<Integer> renderDistance = new Setting<>("RenderDistance", 64, 8, 128);

    private enum Mode {
        Custom, Sync
    }

    public enum Style {
        /** Edges only. */
        Outline,
        /** Solid faces only. */
        Filled,
        Both
    }

    /* ------------------------------------------------------------------ */
    /* Proportions                                                         */
    /* ------------------------------------------------------------------ */

    /* The vanilla player model in blocks: a pixel is 1/16, the model is 32 tall, the hitbox is 1.8. */
    private static final double PX = 1 / 16.0;
    private static final double SCALE = 1.8 / (32 * PX);

    private static final double LEG_TOP = 12 * PX * SCALE;
    private static final double BODY_TOP = LEG_TOP + 12 * PX * SCALE;
    private static final double HEAD_TOP = BODY_TOP + 8 * PX * SCALE;

    private static final double HALF_BODY = 4 * PX * SCALE;
    private static final double HALF_LIMB = 2 * PX * SCALE;
    private static final double HALF_DEPTH = 2 * PX * SCALE;
    private static final double HALF_HEAD = 4 * PX * SCALE;

    /** One frozen snapshot of the player. */
    private record Drawing(Vec3d pos, float bodyYaw, float swing, long born) {
    }

    /** Nothing to draw, rather than a colour. Kept as a constant so it is not rebuilt every box. */
    private static final Color TRANSPARENT = new Color(0, 0, 0, 0);

    private final List<Drawing> drawings = new ArrayList<>();
    private long lastSnapshot;
    private Vec3d lastPos;

    @Override
    public void onEnable() {
        drawings.clear();
        lastSnapshot = 0;
        lastPos = null;
    }

    @Override
    public void onDisable() {
        drawings.clear();
    }

    @Override
    public void onUpdate() {
        if (fullNullCheck()) return;

        long now = System.currentTimeMillis();
        if (now - lastSnapshot < interval.getValue()) return;

        Vec3d pos = mc.player.getEntityPos();
        if (onlyMoving.getValue() && lastPos != null && pos.squaredDistanceTo(lastPos) < 0.01) return;

        lastSnapshot = now;
        lastPos = pos;

        // The limb swing the vanilla model uses: a cosine of how far you have walked, scaled by how fast.
        // Taking it here rather than at render time is the point - the drawing keeps the stride it was
        // captured mid-way through.
        float limbPos = mc.player.limbAnimator.getAnimationProgress();
        float limbSpeed = Math.min(1f, mc.player.limbAnimator.getSpeed());
        float swing = MathHelper.cos(limbPos * 0.6662f) * 1.4f * limbSpeed;

        drawings.add(new Drawing(pos, mc.player.bodyYaw, swing, now));
        while (drawings.size() > maxDrawings.getValue()) drawings.removeFirst();
    }

    @Override
    public void onRender3D(MatrixStack matrices) {
        if (fullNullCheck() || drawings.isEmpty()) return;

        long now = System.currentTimeMillis();
        Color base = colorMode.getValue() == Mode.Sync
                ? HudEditor.getColor(0)
                : color.getValue().getColorObject();

        for (Iterator<Drawing> it = drawings.iterator(); it.hasNext(); ) {
            Drawing drawing = it.next();

            float t = (now - drawing.born()) / (float) duration.getValue();
            if (t >= 1f) {
                it.remove();
                continue;
            }

            if (!CloudanCullingSystem.inRange(drawing.pos(), renderDistance.getValue())) continue;
            if (!CloudanCullingSystem.isVisible(drawing.pos(), 2.5)) continue;

            float fade = 1f - t * t;
            double scale = shrink.getValue() ? 0.35 + 0.65 * (1 - t) : 1.0;

            int alpha = Math.round(opacity.getValue() * fade);
            if (alpha <= 2) continue;

            Color fill = style.getValue() == Style.Outline
                    ? TRANSPARENT
                    : Render2DEngine.injectAlpha(base, Math.round(
                    (style.getValue() == Style.Filled ? alpha : fillOpacity.getValue() * fade)));
            Color edge = style.getValue() == Style.Filled
                    ? TRANSPARENT
                    : Render2DEngine.injectAlpha(base, alpha);

            draw(drawing, fill, edge, scale);

            if (glow.getValue())
                CloudanGlowRenderer.halo(drawing.pos().add(0, BODY_TOP * scale * 0.7, 0),
                        1.1 * scale, fill, 0.30f * fade, throughWalls.getValue());
        }
    }

    /** The six boxes, in the drawing's own frame: x right, y up from the feet, z forward. */
    private void draw(Drawing drawing, Color fill, Color edge, double scale) {
        float yaw = drawing.bodyYaw();
        float swing = (float) Math.toDegrees(drawing.swing());
        float width = style.getValue() == Style.Filled ? 0f : outlineWidth.getValue();

        double legTop = LEG_TOP * scale;
        double bodyTop = BODY_TOP * scale;
        double headTop = HEAD_TOP * scale;
        double halfBody = HALF_BODY * scale;
        double halfLimb = HALF_LIMB * scale;
        double halfDepth = HALF_DEPTH * scale;
        double halfHead = HALF_HEAD * scale;

        // Torso and head: no swing, so no pivot is needed.
        box(drawing, yaw, 0f, Vec3d.ZERO,
                new Box(-halfBody, legTop, -halfDepth, halfBody, bodyTop, halfDepth), fill, edge, width);
        box(drawing, yaw, 0f, Vec3d.ZERO,
                new Box(-halfHead, bodyTop, -halfHead, halfHead, headTop, halfHead), fill, edge, width);

        // Arms hang from the shoulders, legs from the hips, and the pairs swing opposite each other -
        // that opposition is what makes a still figure read as mid-stride rather than as a star shape.
        Vec3d shoulder = new Vec3d(0, bodyTop, 0);
        Vec3d hip = new Vec3d(0, legTop, 0);

        box(drawing, yaw, swing, shoulder,
                new Box(-halfBody - halfLimb * 2, legTop, -halfDepth, -halfBody, bodyTop, halfDepth), fill, edge, width);
        box(drawing, yaw, -swing, shoulder,
                new Box(halfBody, legTop, -halfDepth, halfBody + halfLimb * 2, bodyTop, halfDepth), fill, edge, width);

        box(drawing, yaw, -swing, hip,
                new Box(-halfBody, 0, -halfDepth, 0, legTop, halfDepth), fill, edge, width);
        box(drawing, yaw, swing, hip,
                new Box(0, 0, -halfDepth, halfBody, legTop, halfDepth), fill, edge, width);
    }

    private void box(Drawing drawing, float yaw, float pitch, Vec3d pivot, Box local,
                     Color fill, Color edge, float width) {
        Render3DEngine.drawOrientedBox3D(drawing.pos(), yaw, pitch, pivot, local, fill, edge, width,
                throughWalls.getValue());
    }

    @Override
    public String getDisplayInfo() {
        return drawings.isEmpty() ? null : drawings.size() + "";
    }
}
