package thunder.hack.utility.render.core;

import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import org.jetbrains.annotations.NotNull;
import thunder.hack.utility.render.Render2DEngine;

import java.awt.Color;

/**
 * Outlines and world-space lines, all through the batcher.
 *
 * <p>Every box here is a real projected cube: each of the six faces is projected on its own, back faces
 * dropped by testing the face normal against the camera. That is the difference between an outline that
 * sits on the block and the flat screen rectangle around its bounds that a naive projection gives you.
 *
 * <p>An outline is a supporting effect, never the whole visual. It marks the edge of something that is
 * already drawn - a model, a glow - rather than standing in for it.
 */
public final class CloudanOutlineRenderer {
    private CloudanOutlineRenderer() {
    }

    /** The client's one line weight, so nothing looks thicker than anything else by accident. */
    public static final float LINE_WIDTH = 1.5f;

    /** Outline only - no fill. The usual case. */
    public static void box(@NotNull Box box, @NotNull Color color, double maxDistance) {
        box(box, null, color, LINE_WIDTH, maxDistance);
    }

    /**
     * Outline with an optional fill.
     *
     * @param fill null for no fill; it is normally the same colour at a much lower alpha
     */
    public static void box(@NotNull Box box, Color fill, @NotNull Color outline, float lineWidth, double maxDistance) {
        Vec3d centre = box.getCenter();
        double radius = Math.max(box.getLengthX(), Math.max(box.getLengthY(), box.getLengthZ())) * 0.87;

        if (!CloudanCullingSystem.evaluate(centre, radius, maxDistance).drawable()) return;

        CloudanBatchRenderer.box(box,
                fill == null ? Render2DEngine.injectAlpha(outline, 0) : fill,
                outline, lineWidth, fill != null, true);
    }

    /** A block-sized outline, the shape almost every world marker wants. */
    public static void block(@NotNull net.minecraft.util.math.BlockPos pos, @NotNull Color color, double maxDistance) {
        box(new Box(pos), color, maxDistance);
    }

    /**
     * A line between two world points, projected to the screen.
     *
     * <p>Both ends have to be in front of the near plane or the line is dropped: a point behind the
     * camera projects to nonsense and smears a streak across the whole screen.
     */
    public static void line(@NotNull Vec3d from, @NotNull Vec3d to, @NotNull Color color, float width) {
        Vec3d a = thunder.hack.utility.render.Render3DEngine.worldSpaceToScreenSpace(from);
        Vec3d b = thunder.hack.utility.render.Render3DEngine.worldSpaceToScreenSpace(to);

        if (!(a.z > 0 && a.z < 1) || !(b.z > 0 && b.z < 1)) return;

        CloudanBatchRenderer.line((float) a.x, (float) a.y, (float) b.x, (float) b.y, color.getRGB(), width);
        CloudanRenderCore.countVisible(1);
    }

    /** A tracer from a fixed point on screen out to a world position. */
    public static void tracer(float originX, float originY, @NotNull Vec3d target, @NotNull Color color, float width) {
        Vec3d screen = thunder.hack.utility.render.Render3DEngine.worldSpaceToScreenSpace(target);
        if (!(screen.z > 0 && screen.z < 1)) return;

        CloudanBatchRenderer.line(originX, originY, (float) screen.x, (float) screen.y, color.getRGB(), width);
        CloudanRenderCore.countVisible(1);
    }
}
