package thunder.hack.utility.render.core;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import org.jetbrains.annotations.NotNull;
import thunder.hack.utility.render.Render3DEngine;

import java.awt.Color;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Collects what every visual wants drawn and submits it in as few calls as the pipelines allow.
 *
 * <p>This is not a nicety on 1.21.6+, it is a crash fix. Screen-space geometry goes through
 * {@code GuiRenderState}, which puts each submitted element into its own layer when they overlap, and
 * vanilla walks those layers by recursion - a few thousand elements in one frame is a StackOverflow and
 * an instant close. One element per box is therefore never allowed; one element per <em>batch</em> is.
 *
 * <p>World sprites batch for a duller reason: each flush is a draw call, and drawing forty rings on one
 * texture as forty flushes costs forty times what it should.
 */
public final class CloudanBatchRenderer {
    private CloudanBatchRenderer() {
    }

    private static final List<Render3DEngine.BoxSpec> BOXES = new ArrayList<>();
    private static final List<Render3DEngine.Line2DSpec> LINES = new ArrayList<>();
    private static final List<Render3DEngine.SideSpec> SIDES = new ArrayList<>();
    /** Ground sprites grouped by texture, because a flush can only carry one texture. */
    private static final Map<Identifier, List<Render3DEngine.GroundSprite>> SPRITES = new LinkedHashMap<>();
    private static final Map<Identifier, List<Render3DEngine.GroundSprite>> SPRITES_THROUGH = new LinkedHashMap<>();
    /** Camera-facing quads (particles), batched per texture like the ground sprites. */
    private static final Map<Identifier, List<Render3DEngine.GroundSprite>> BILLBOARDS = new LinkedHashMap<>();
    private static final Map<Identifier, List<Render3DEngine.GroundSprite>> BILLBOARDS_THROUGH = new LinkedHashMap<>();

    static void begin() {
        BOXES.clear();
        LINES.clear();
        SIDES.clear();
        SPRITES.clear();
        SPRITES_THROUGH.clear();
        BILLBOARDS.clear();
        BILLBOARDS_THROUGH.clear();
    }

    static void clear() {
        begin();
    }

    /* ------------------------------------------------------------------ */
    /* Submitting                                                          */
    /* ------------------------------------------------------------------ */

    /** A world-space cube, projected. Culled here so callers do not each write the same check. */
    public static void box(@NotNull Box box, @NotNull Color fill, @NotNull Color outline,
                           float lineWidth, boolean doFill, boolean doOutline) {
        if (!CloudanCullingSystem.isVisible(box)) {
            CloudanRenderCore.countCulled(1);
            return;
        }

        BOXES.add(new Render3DEngine.BoxSpec(box, fill, fill, outline, lineWidth, doFill, doOutline));
        CloudanRenderCore.countVisible(1);
    }

    /** One real block face - the surface a wave runs over. */
    public static void side(@NotNull Box box, @NotNull net.minecraft.util.math.Direction dir,
                            @NotNull Color fill, boolean outline, @NotNull Color outlineColor, float lineWidth) {
        SIDES.add(new Render3DEngine.SideSpec(box, dir, fill, outlineColor, lineWidth, true, outline));
    }

    /** A screen-space line between two already-projected points. */
    public static void line(float x1, float y1, float x2, float y2, int argb, float width) {
        LINES.add(new Render3DEngine.Line2DSpec(x1, y1, x2, y2, argb, width));
    }

    /** A textured quad lying flat on the ground - rings, markers, runes. */
    public static void groundSprite(@NotNull Identifier texture, @NotNull Vec3d centre,
                                    double radius, double angle, @NotNull Color tint, boolean throughWalls) {
        groundSprite(texture, centre, radius, angle, 0f, 0f, 1f, 1f, tint, throughWalls);
    }

    /**
     * A flat quad showing a WINDOW of the texture.
     *
     * <p>This is what turns many quads into one continuous surface: give each block the window its world
     * position falls into ({@code floor(x / tile) / tile}) and the picture runs on across the block
     * boundaries. Textures repeat by default on this version, so a window that runs past 1 wraps rather
     * than smearing its edge texel.
     */
    public static void groundSprite(@NotNull Identifier texture, @NotNull Vec3d centre, double radius, double angle,
                                    float u0, float v0, float u1, float v1,
                                    @NotNull Color tint, boolean throughWalls) {
        if (!CloudanCullingSystem.isVisible(centre, radius)) {
            CloudanRenderCore.countCulled(1);
            return;
        }

        (throughWalls ? SPRITES_THROUGH : SPRITES)
                .computeIfAbsent(texture, t -> new ArrayList<>())
                .add(new Render3DEngine.GroundSprite(centre, radius, angle, tint, u0, v0, u1, v1));
        CloudanRenderCore.countVisible(1);
    }

    /** A quad that always faces the camera - for anything floating in the air (particles, sparks, motes). */
    public static void billboard(@NotNull Identifier texture, @NotNull Vec3d centre, double radius, double angle,
                                 @NotNull Color tint, boolean throughWalls) {
        if (!CloudanCullingSystem.isVisible(centre, radius)) {
            CloudanRenderCore.countCulled(1);
            return;
        }
        (throughWalls ? BILLBOARDS_THROUGH : BILLBOARDS)
                .computeIfAbsent(texture, t -> new ArrayList<>())
                .add(new Render3DEngine.GroundSprite(centre, radius, angle, tint));
        CloudanRenderCore.countVisible(1);
    }

    /* ------------------------------------------------------------------ */
    /* Flushing                                                            */
    /* ------------------------------------------------------------------ */

    /** World-space work, from the 3D pass. One draw call per texture. */
    static void flushWorld() {
        flushSprites(SPRITES, false);
        flushSprites(SPRITES_THROUGH, true);
        flushBillboards(BILLBOARDS, false);
        flushBillboards(BILLBOARDS_THROUGH, true);
    }

    private static void flushBillboards(Map<Identifier, List<Render3DEngine.GroundSprite>> map, boolean throughWalls) {
        if (map.isEmpty()) return;
        for (Map.Entry<Identifier, List<Render3DEngine.GroundSprite>> entry : map.entrySet()) {
            Render3DEngine.drawBillboards3D(entry.getKey(), entry.getValue(), throughWalls);
            CloudanRenderCore.countDrawCall();
        }
        map.clear();
    }

    private static void flushSprites(Map<Identifier, List<Render3DEngine.GroundSprite>> map, boolean throughWalls) {
        if (map.isEmpty()) return;

        for (Map.Entry<Identifier, List<Render3DEngine.GroundSprite>> entry : map.entrySet()) {
            Render3DEngine.drawGroundSprites3D(entry.getKey(), entry.getValue(), throughWalls);
            CloudanRenderCore.countDrawCall();
        }
        map.clear();
    }

    /** Screen-space work, from the 2D pass: one element for every box, one for every line. */
    static void flushScreen(@NotNull DrawContext context) {
        if (!BOXES.isEmpty()) {
            Render3DEngine.drawProjectedBoxes(context, BOXES);
            CloudanRenderCore.countDrawCall();
            BOXES.clear();
        }

        if (!LINES.isEmpty()) {
            Render3DEngine.drawLines2D(context, LINES);
            CloudanRenderCore.countDrawCall();
            LINES.clear();
        }

        if (!SIDES.isEmpty()) {
            Render3DEngine.drawProjectedSides(context, SIDES);
            CloudanRenderCore.countDrawCall();
            SIDES.clear();
        }
    }

    public static int pending() {
        return BOXES.size() + LINES.size() + SIDES.size() + SPRITES.size() + SPRITES_THROUGH.size() + BILLBOARDS.size() + BILLBOARDS_THROUGH.size();
    }
}
