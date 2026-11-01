package thunder.hack.utility.render;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.*;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.Entity;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.*;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix3x2f;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.lwjgl.opengl.GL11;
import thunder.hack.ThunderHack;
import thunder.hack.features.modules.client.ClientSettings;
import thunder.hack.features.modules.client.HudEditor;
import thunder.hack.gui.font.FontRenderers;
import thunder.hack.utility.render.core.CloudanAnimationEngine;
import thunder.hack.utility.render.core.CloudanBatchRenderer;
import thunder.hack.utility.render.core.CloudanCullingSystem;
import thunder.hack.utility.render.core.CloudanGlowRenderer;
import thunder.hack.utility.render.core.CloudanRenderCore;

import java.awt.*;
import java.util.ArrayList;
import java.util.List;

import static thunder.hack.features.modules.Module.mc;

/**
 * 1.21.11 port: all immediate-mode drawing now goes through {@link Render3DUtil}, which routes
 * geometry to vanilla world {@link RenderLayer}s via the shared entity vertex consumers. The old
 * RenderSystem state toggles (blend / depth / cull / lineWidth) are encapsulated by those layers,
 * so they were dropped here. Strip draw modes are converted: TRIANGLE_STRIP -> quads,
 * DEBUG_LINE_STRIP -> consecutive line segments.
 */
public class Render3DEngine {
    public static List<FillAction> FILLED_QUEUE = new ArrayList<>();
    public static List<OutlineAction> OUTLINE_QUEUE = new ArrayList<>();
    public static List<FadeAction> FADE_QUEUE = new ArrayList<>();
    public static List<FillSideAction> FILLED_SIDE_QUEUE = new ArrayList<>();
    public static List<OutlineSideAction> OUTLINE_SIDE_QUEUE = new ArrayList<>();
    public static List<DebugLineAction> DEBUG_LINE_QUEUE = new ArrayList<>();
    public static List<LineAction> LINE_QUEUE = new ArrayList<>();

    public static final Matrix4f lastProjMat = new Matrix4f();
    public static final Matrix4f lastModMat = new Matrix4f();
    public static final Matrix4f lastWorldSpaceMatrix = new Matrix4f();


    private static float prevCircleStep;
    private static float circleStep;

    // getTickDelta() -> mc.getRenderTickCounter().getTickProgress(true)

    public static void onRender3D(MatrixStack stack) {
        Matrix4f matrix = stack.peek().getPositionMatrix();

        if (!FILLED_QUEUE.isEmpty() || !FADE_QUEUE.isEmpty() || !FILLED_SIDE_QUEUE.isEmpty()) {
            VertexConsumer vc = Render3DUtil.buffer(Render3DUtil.QUADS);

            FILLED_QUEUE.forEach(action -> setFilledBoxVertexes(vc, matrix, action.box(), action.color()));
            FADE_QUEUE.forEach(action -> setFilledFadePoints(action.box(), vc, matrix, action.color(), action.color2()));
            FILLED_SIDE_QUEUE.forEach(action -> setFilledSidePoints(vc, matrix, action.box, action.color(), action.side()));

            Render3DUtil.flush(Render3DUtil.QUADS);

            FADE_QUEUE.clear();
            FILLED_SIDE_QUEUE.clear();
            FILLED_QUEUE.clear();
        }

        if (!OUTLINE_QUEUE.isEmpty() || !OUTLINE_SIDE_QUEUE.isEmpty()) {
            VertexConsumer vc = Render3DUtil.buffer(Render3DUtil.LINES);

            OUTLINE_QUEUE.forEach(action ->
                    setOutlinePoints(action.box(), matrixFrom(action.box().minX, action.box().minY, action.box().minZ), vc, action.color()));

            OUTLINE_SIDE_QUEUE.forEach(action ->
                    setSideOutlinePoints(action.box, matrixFrom(action.box().minX, action.box().minY, action.box().minZ), vc, action.color(), action.side()));

            Render3DUtil.flush(Render3DUtil.LINES);
            OUTLINE_QUEUE.clear();
            OUTLINE_SIDE_QUEUE.clear();
        }

        if (!DEBUG_LINE_QUEUE.isEmpty()) {
            VertexConsumer vc = Render3DUtil.buffer(Render3DUtil.LINES);
            DEBUG_LINE_QUEUE.forEach(action -> {
                MatrixStack matrices = matrixFrom(action.start.getX(), action.start.getY(), action.start.getZ());
                vertexLine(matrices, vc, 0f, 0f, 0f, (float) (action.end.getX() - action.start.getX()), (float) (action.end.getY() - action.start.getY()), (float) (action.end.getZ() - action.start.getZ()), action.color);
            });
            Render3DUtil.flush(Render3DUtil.LINES);
            DEBUG_LINE_QUEUE.clear();
        }

        if (!LINE_QUEUE.isEmpty()) {
            VertexConsumer vc = Render3DUtil.buffer(Render3DUtil.LINES);
            LINE_QUEUE.forEach(action -> {
                MatrixStack matrices = matrixFrom(action.start.getX(), action.start.getY(), action.start.getZ());
                Render3DUtil.line(vc, matrices.peek().getPositionMatrix(), 0f, 0f, 0f, (float) (action.end.getX() - action.start.getX()), (float) (action.end.getY() - action.start.getY()), (float) (action.end.getZ() - action.start.getZ()), action.color.getRGB(), action.width);
            });
            Render3DUtil.flush(Render3DUtil.LINES);
            LINE_QUEUE.clear();
        }
    }

    @Deprecated
    @SuppressWarnings("unused")
    public static void drawFilledBox(MatrixStack stack, Box box, Color c) {
        FILLED_QUEUE.add(new FillAction(box, c));
    }

    public static void setFilledBoxVertexes(@NotNull VertexConsumer vc, Matrix4f m, @NotNull Box box, @NotNull Color c) {
        float minX = (float) (box.minX - mc.getEntityRenderDispatcher().camera.getCameraPos().getX());
        float minY = (float) (box.minY - mc.getEntityRenderDispatcher().camera.getCameraPos().getY());
        float minZ = (float) (box.minZ - mc.getEntityRenderDispatcher().camera.getCameraPos().getZ());
        float maxX = (float) (box.maxX - mc.getEntityRenderDispatcher().camera.getCameraPos().getX());
        float maxY = (float) (box.maxY - mc.getEntityRenderDispatcher().camera.getCameraPos().getY());
        float maxZ = (float) (box.maxZ - mc.getEntityRenderDispatcher().camera.getCameraPos().getZ());

        int color = c.getRGB();

        Render3DUtil.quad(vc, m, minX, minY, minZ, maxX, minY, minZ, maxX, minY, maxZ, minX, minY, maxZ, color);
        Render3DUtil.quad(vc, m, minX, minY, minZ, minX, maxY, minZ, maxX, maxY, minZ, maxX, minY, minZ, color);
        Render3DUtil.quad(vc, m, maxX, minY, minZ, maxX, maxY, minZ, maxX, maxY, maxZ, maxX, minY, maxZ, color);
        Render3DUtil.quad(vc, m, minX, minY, maxZ, maxX, minY, maxZ, maxX, maxY, maxZ, minX, maxY, maxZ, color);
        Render3DUtil.quad(vc, m, minX, minY, minZ, minX, minY, maxZ, minX, maxY, maxZ, minX, maxY, minZ, color);
        Render3DUtil.quad(vc, m, minX, maxY, minZ, minX, maxY, maxZ, maxX, maxY, maxZ, maxX, maxY, minZ, color);
    }

    public static @NotNull Box interpolateBox(@NotNull Box from, @NotNull Box to, float delta) {
        double X = Render2DEngine.interpolate(from.maxX, to.maxX, delta);
        double Y = Render2DEngine.interpolate(from.maxY, to.maxY, delta);
        double Z = Render2DEngine.interpolate(from.maxZ, to.maxZ, delta);
        double X1 = Render2DEngine.interpolate(from.minX, to.minX, delta);
        double Y1 = Render2DEngine.interpolate(from.minY, to.minY, delta);
        double Z1 = Render2DEngine.interpolate(from.minZ, to.minZ, delta);
        return new Box(X1, Y1, Z1, X, Y, Z);
    }

    @Deprecated
    public static void drawFilledSide(MatrixStack stack, @NotNull Box box, Color c, Direction dir) {
        FILLED_SIDE_QUEUE.add(new FillSideAction(box, c, dir));
    }

    public static void setFilledSidePoints(VertexConsumer buffer, Matrix4f matrix, Box box, Color c, Direction dir) {
        float minX = (float) (box.minX - mc.getEntityRenderDispatcher().camera.getCameraPos().getX());
        float minY = (float) (box.minY - mc.getEntityRenderDispatcher().camera.getCameraPos().getY());
        float minZ = (float) (box.minZ - mc.getEntityRenderDispatcher().camera.getCameraPos().getZ());
        float maxX = (float) (box.maxX - mc.getEntityRenderDispatcher().camera.getCameraPos().getX());
        float maxY = (float) (box.maxY - mc.getEntityRenderDispatcher().camera.getCameraPos().getY());
        float maxZ = (float) (box.maxZ - mc.getEntityRenderDispatcher().camera.getCameraPos().getZ());

        int color = c.getRGB();

        if (dir == Direction.DOWN)
            Render3DUtil.quad(buffer, matrix, minX, minY, minZ, maxX, minY, minZ, maxX, minY, maxZ, minX, minY, maxZ, color);

        if (dir == Direction.NORTH)
            Render3DUtil.quad(buffer, matrix, minX, minY, minZ, minX, maxY, minZ, maxX, maxY, minZ, maxX, minY, minZ, color);

        if (dir == Direction.EAST)
            Render3DUtil.quad(buffer, matrix, maxX, minY, minZ, maxX, maxY, minZ, maxX, maxY, maxZ, maxX, minY, maxZ, color);

        if (dir == Direction.SOUTH)
            Render3DUtil.quad(buffer, matrix, minX, minY, maxZ, maxX, minY, maxZ, maxX, maxY, maxZ, minX, maxY, maxZ, color);

        if (dir == Direction.WEST)
            Render3DUtil.quad(buffer, matrix, minX, minY, minZ, minX, minY, maxZ, minX, maxY, maxZ, minX, maxY, minZ, color);

        if (dir == Direction.UP)
            Render3DUtil.quad(buffer, matrix, minX, maxY, minZ, minX, maxY, maxZ, maxX, maxY, maxZ, maxX, maxY, minZ, color);
    }

    public static void drawTextIn3D(String text, @NotNull Vec3d pos, double offX, double offY, double textOffset, @NotNull Color color) {
        MatrixStack matrices = new MatrixStack();
        Camera camera = mc.gameRenderer.getCamera();
        matrices.multiply(RotationAxis.POSITIVE_X.rotationDegrees(camera.getPitch()));
        matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(camera.getYaw() + 180.0F));
        matrices.translate(pos.getX() - camera.getCameraPos().x, pos.getY() - camera.getCameraPos().y, pos.getZ() - camera.getCameraPos().z);
        matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(-camera.getYaw()));
        matrices.multiply(RotationAxis.POSITIVE_X.rotationDegrees(camera.getPitch()));
        matrices.translate(offX, offY - 0.1, -0.01);
        matrices.scale(-0.025f, -0.025f, 0);
        FontRenderers.sf_medium.drawCenteredString(matrices, text, textOffset, 0f, color.getRGB());
    }

    /**
     * Draws a world-space box as a screen-projected 2D rectangle (fill + outline), the SAME way the working
     * entity ESP does it — projecting the 8 corners through {@link #worldSpaceToScreenSpace} and drawing with
     * the 2D primitives. Returns silently if the box is fully behind the camera.
     *
     * <p>This flattens the box to one axis-aligned screen rectangle, so a block reads as a flat 2D shape.
     * Prefer {@link #drawProjectedBox}, which projects each face separately and looks like an actual cube.
     */
    @Deprecated
    public static void drawBox2D(DrawContext context, Box box, Color fillColor, Color outlineColor, boolean doFill, boolean doOutline) {
        Vec3d[] corners = {
                new Vec3d(box.minX, box.minY, box.minZ), new Vec3d(box.minX, box.maxY, box.minZ),
                new Vec3d(box.maxX, box.minY, box.minZ), new Vec3d(box.maxX, box.maxY, box.minZ),
                new Vec3d(box.minX, box.minY, box.maxZ), new Vec3d(box.minX, box.maxY, box.maxZ),
                new Vec3d(box.maxX, box.minY, box.maxZ), new Vec3d(box.maxX, box.maxY, box.maxZ)
        };
        double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE, maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
        for (Vec3d c : corners) {
            Vec3d s = worldSpaceToScreenSpace(c);
            // If ANY corner is behind / on the near plane its projection blows up to enormous coords, which
            // used to fill the whole screen ("green screen" while turning). Skip the whole box in that case.
            if (!(s.z > 0 && s.z < 1)) return;
            minX = Math.min(minX, s.x);
            minY = Math.min(minY, s.y);
            maxX = Math.max(maxX, s.x);
            maxY = Math.max(maxY, s.y);
        }

        float bx = (float) minX, by = (float) minY, ex = (float) maxX, ey = (float) maxY;
        float bw = ex - bx, bh = ey - by, t = 1f;

        if (doFill)
            Render2DEngine.drawRect(context, bx, by, bw, bh, fillColor);
        if (doOutline) {
            Render2DEngine.drawRect(context, bx, by, bw, t, outlineColor);
            Render2DEngine.drawRect(context, bx, ey - t, bw, t, outlineColor);
            Render2DEngine.drawRect(context, bx, by, t, bh, outlineColor);
            Render2DEngine.drawRect(context, ex - t, by, t, bh, outlineColor);
        }
    }

    /* ------------------------------------------------------------------ */
    /* Projected block rendering (screen space, replaces the 3D queues)     */
    /* ------------------------------------------------------------------ */

    /** Corner order: 0-3 bottom ring (CCW seen from above), 4-7 the same ring at maxY. */
    private static final int[][] BOX_FACES = {
            {0, 1, 2, 3},  // DOWN
            {4, 5, 6, 7},  // UP
            {0, 1, 5, 4},  // NORTH (minZ)
            {3, 2, 6, 7},  // SOUTH (maxZ)
            {0, 3, 7, 4},  // WEST  (minX)
            {1, 2, 6, 5}   // EAST  (maxX)
    };

    private static final int[][] BOX_EDGES = {
            {0, 1}, {1, 2}, {2, 3}, {3, 0},
            {4, 5}, {5, 6}, {6, 7}, {7, 4},
            {0, 4}, {1, 5}, {2, 6}, {3, 7}
    };

    private static int faceIndex(@NotNull Direction dir) {
        return switch (dir) {
            case DOWN -> 0;
            case UP -> 1;
            case NORTH -> 2;
            case SOUTH -> 3;
            case WEST -> 4;
            case EAST -> 5;
        };
    }

    private static @NotNull Vec3d[] boxCorners(@NotNull Box b) {
        return new Vec3d[]{
                new Vec3d(b.minX, b.minY, b.minZ), new Vec3d(b.maxX, b.minY, b.minZ),
                new Vec3d(b.maxX, b.minY, b.maxZ), new Vec3d(b.minX, b.minY, b.maxZ),
                new Vec3d(b.minX, b.maxY, b.minZ), new Vec3d(b.maxX, b.maxY, b.minZ),
                new Vec3d(b.maxX, b.maxY, b.maxZ), new Vec3d(b.minX, b.maxY, b.maxZ)
        };
    }

    /**
     * True when face {@code f} of {@code box} points towards the camera. Tested against the face NORMAL
     * rather than the screen-space winding, so it stays correct whatever handedness the projection has.
     */
    private static boolean faceVisible(@NotNull Box box, @NotNull Vec3d cam, int f) {
        Vec3d c = box.getCenter();
        double hx = (box.maxX - box.minX) / 2, hy = (box.maxY - box.minY) / 2, hz = (box.maxZ - box.minZ) / 2;
        Vec3d n = switch (f) {
            case 0 -> new Vec3d(0, -1, 0);
            case 1 -> new Vec3d(0, 1, 0);
            case 2 -> new Vec3d(0, 0, -1);
            case 3 -> new Vec3d(0, 0, 1);
            case 4 -> new Vec3d(-1, 0, 0);
            default -> new Vec3d(1, 0, 0);
        };
        Vec3d center = c.add(n.x * hx, n.y * hy, n.z * hz);
        return n.dotProduct(cam.subtract(center)) > 0;
    }

    /**
     * Renders a world-space box as a REAL projected cube: every face is projected as its own quad — not as
     * one axis-aligned bounding rect the way {@link #drawBox2D} does — back faces are culled against the
     * camera, and the 12 edges are stroked as thick 2D lines. This is the working replacement for the
     * {@code FILLED_QUEUE}/{@code OUTLINE_QUEUE} path, which double-rotates on 1.21.11.
     *
     * @param fillTop    fill colour at the top face (a vertical gradient together with {@code fillBottom})
     * @param fillBottom fill colour at the bottom face
     */
    public static void drawProjectedBox(DrawContext context, Box box, Color fillTop, Color fillBottom, Color outlineColor, float lineWidth, boolean doFill, boolean doOutline) {
        Vec3d[] world = boxCorners(box);
        Vec3d[] s = new Vec3d[8];
        for (int i = 0; i < 8; i++) {
            s[i] = worldSpaceToScreenSpace(world[i]);
            // A corner at or behind the near plane projects to garbage that would smear across the screen.
            if (!(s[i].z > 0 && s[i].z < 1)) return;
        }

        Vec3d cam = mc.getEntityRenderDispatcher().camera.getCameraPos();

        if (doFill) {
            Matrix3x2f pose = GuiRender.pose(context);
            int top = fillTop.getRGB(), bottom = fillBottom.getRGB();
            GuiRender.quads(context, Render2DEngine.currentClip(), vc -> {
                for (int f = 0; f < 6; f++) {
                    if (!faceVisible(box, cam, f)) continue;
                    int[] q = BOX_FACES[f];
                    GuiRender.quad(vc, pose,
                            (float) s[q[0]].x, (float) s[q[0]].y,
                            (float) s[q[1]].x, (float) s[q[1]].y,
                            (float) s[q[2]].x, (float) s[q[2]].y,
                            (float) s[q[3]].x, (float) s[q[3]].y,
                            q[0] > 3 ? top : bottom, q[1] > 3 ? top : bottom,
                            q[2] > 3 ? top : bottom, q[3] > 3 ? top : bottom);
                }
            });
        }

        if (doOutline) {
            int rgb = outlineColor.getRGB();
            for (int[] e : BOX_EDGES)
                Render2DEngine.drawThickLine(context, (float) s[e[0]].x, (float) s[e[0]].y,
                        (float) s[e[1]].x, (float) s[e[1]].y, rgb, lineWidth);
        }
    }

    /** Single face of a projected box — the replacement for the side fill/outline queues. */
    public static void drawProjectedSide(DrawContext context, Box box, Direction dir, Color fillColor, Color outlineColor, float lineWidth, boolean doFill, boolean doOutline) {
        Vec3d[] world = boxCorners(box);
        int[] q = BOX_FACES[faceIndex(dir)];
        Vec3d[] s = new Vec3d[4];
        for (int i = 0; i < 4; i++) {
            s[i] = worldSpaceToScreenSpace(world[q[i]]);
            if (!(s[i].z > 0 && s[i].z < 1)) return;
        }

        if (doFill) {
            Matrix3x2f pose = GuiRender.pose(context);
            int rgb = fillColor.getRGB();
            GuiRender.quads(context, Render2DEngine.currentClip(), vc ->
                    GuiRender.quad(vc, pose,
                            (float) s[0].x, (float) s[0].y, (float) s[1].x, (float) s[1].y,
                            (float) s[2].x, (float) s[2].y, (float) s[3].x, (float) s[3].y,
                            rgb, rgb, rgb, rgb));
        }

        if (doOutline) {
            int rgb = outlineColor.getRGB();
            for (int i = 0; i < 4; i++) {
                Vec3d a = s[i], b = s[(i + 1) % 4];
                Render2DEngine.drawThickLine(context, (float) a.x, (float) a.y, (float) b.x, (float) b.y, rgb, lineWidth);
            }
        }
    }

    /** One block face in a {@link #drawProjectedSides} batch. */
    public record SideSpec(Box box, Direction dir, Color fill, Color outline, float lineWidth,
                           boolean doFill, boolean doOutline) {
    }

    /**
     * Batched {@link #drawProjectedSide}: many individual block faces as ONE GUI element.
     *
     * <p>Same reason as {@link #drawProjectedBoxes} - an element per face would stack a GuiRenderState
     * layer per face and the recursive layer walk overflows the stack. A surface wave running fifteen
     * blocks out reaches several hundred faces easily, so this is required, not an optimisation.
     *
     * <p>Faces are drawn in whatever order the caller submitted, which lets an effect fade along the
     * wave simply by ordering its list.
     */
    public static void drawProjectedSides(DrawContext context, @NotNull List<SideSpec> sides) {
        if (sides.isEmpty()) return;

        final List<SideSpec> kept = new ArrayList<>(sides.size());
        final List<Vec3d[]> screen = new ArrayList<>(sides.size());

        for (SideSpec spec : sides) {
            Vec3d[] world = boxCorners(spec.box());
            int[] q = BOX_FACES[faceIndex(spec.dir())];
            Vec3d[] s = new Vec3d[4];

            boolean ok = true;
            for (int i = 0; i < 4; i++) {
                s[i] = worldSpaceToScreenSpace(world[q[i]]);
                // A corner at or behind the near plane projects to nonsense and would smear the quad
                // across the whole screen.
                if (!(s[i].z > 0 && s[i].z < 1)) {
                    ok = false;
                    break;
                }
            }

            if (!ok) continue;
            kept.add(spec);
            screen.add(s);
        }

        if (kept.isEmpty()) return;

        final Matrix3x2f pose = GuiRender.pose(context);

        GuiRender.quads(context, Render2DEngine.currentClip(), vc -> {
            for (int i = 0; i < kept.size(); i++) {
                SideSpec spec = kept.get(i);
                if (!spec.doFill()) continue;

                Vec3d[] s = screen.get(i);
                int rgb = spec.fill().getRGB();
                GuiRender.quad(vc, pose,
                        (float) s[0].x, (float) s[0].y, (float) s[1].x, (float) s[1].y,
                        (float) s[2].x, (float) s[2].y, (float) s[3].x, (float) s[3].y,
                        rgb, rgb, rgb, rgb);
            }
        });

        for (int i = 0; i < kept.size(); i++) {
            SideSpec spec = kept.get(i);
            if (!spec.doOutline()) continue;

            Vec3d[] s = screen.get(i);
            int rgb = spec.outline().getRGB();
            for (int c = 0; c < 4; c++) {
                Vec3d a = s[c], b = s[(c + 1) % 4];
                Render2DEngine.drawThickLine(context, (float) a.x, (float) a.y, (float) b.x, (float) b.y, rgb, spec.lineWidth());
            }
        }
    }

    /** One box in a {@link #drawProjectedBoxes} batch. */
    public record BoxSpec(Box box, Color fillTop, Color fillBottom, Color outline, float lineWidth,
                          boolean doFill, boolean doOutline) {
        public BoxSpec(Box box, Color fill, Color outline, float lineWidth, boolean doFill, boolean doOutline) {
            this(box, fill, fill, outline, lineWidth, doFill, doOutline);
        }
    }

    /**
     * Batched {@link #drawProjectedBox}: the whole list is emitted as ONE GUI element.
     *
     * <p>This is not just a speed optimisation, it is a crash fix. Every {@code GuiRender.quads} call adds an
     * element whose bounds are the FULL screen, so vanilla's {@code GuiRenderState} treats each one as
     * intersecting the last and stacks it into a NEW layer. Layers are walked by RECURSION
     * ({@code GuiRenderState.traverseLayers}), so a few thousand elements in a frame - which one AutoBuild
     * ghost easily reaches at 13 elements per box - overflows the stack and kills the game. One element per
     * batch keeps the layer count at one no matter how many boxes are drawn.
     *
     * <p>All 8 corners of every box are projected up front (one {@code glGetIntegerv} for the batch), so the
     * deferred GUI builder does no GL work; boxes with a corner at or behind the near plane are dropped.
     */
    public static void drawProjectedBoxes(DrawContext context, @NotNull List<BoxSpec> boxes) {
        if (boxes.isEmpty()) return;

        Camera camera = mc.getEntityRenderDispatcher().camera;
        if (camera == null) return;
        final Vec3d cam = camera.getCameraPos();

        final Matrix4f pv = new Matrix4f(lastProjMat).mul(new Matrix4f(lastModMat));
        final int[] viewport = new int[4];
        screenViewport(viewport);
        final int displayHeight = mc.getWindow().getFramebufferHeight();
        final double scale = getScaleFactor();

        final List<BoxSpec> kept = new ArrayList<>(boxes.size());
        final List<float[]> screen = new ArrayList<>(boxes.size()); // 16 floats per box: x,y of each corner

        for (BoxSpec spec : boxes) {
            Vec3d[] world = boxCorners(spec.box());
            float[] xy = new float[16];
            boolean ok = true;
            for (int i = 0; i < 8; i++) {
                Vec3d p = project(world[i].x, world[i].y, world[i].z, cam, pv, viewport, displayHeight, scale);
                if (!(p.z > 0 && p.z < 1)) {
                    ok = false;
                    break;
                }
                xy[i * 2] = (float) p.x;
                xy[i * 2 + 1] = (float) p.y;
            }
            if (!ok) continue;
            kept.add(spec);
            screen.add(xy);
        }
        if (kept.isEmpty()) return;

        Matrix3x2f pose = GuiRender.pose(context);
        GuiRender.quads(context, Render2DEngine.currentClip(), vc -> {
            for (int b = 0; b < kept.size(); b++) {
                BoxSpec spec = kept.get(b);
                float[] xy = screen.get(b);

                if (spec.doFill()) {
                    int top = spec.fillTop().getRGB(), bottom = spec.fillBottom().getRGB();
                    for (int f = 0; f < 6; f++) {
                        if (!faceVisible(spec.box(), cam, f)) continue;
                        int[] q = BOX_FACES[f];
                        GuiRender.quad(vc, pose,
                                xy[q[0] * 2], xy[q[0] * 2 + 1], xy[q[1] * 2], xy[q[1] * 2 + 1],
                                xy[q[2] * 2], xy[q[2] * 2 + 1], xy[q[3] * 2], xy[q[3] * 2 + 1],
                                q[0] > 3 ? top : bottom, q[1] > 3 ? top : bottom,
                                q[2] > 3 ? top : bottom, q[3] > 3 ? top : bottom);
                    }
                }

                if (spec.doOutline()) {
                    int rgb = spec.outline().getRGB();
                    for (int[] e : BOX_EDGES)
                        thickLine(vc, pose, xy[e[0] * 2], xy[e[0] * 2 + 1], xy[e[1] * 2], xy[e[1] * 2 + 1],
                                rgb, spec.lineWidth());
                }
            }
        });
    }

    /** One screen-space segment in a {@link #drawLines2D} batch. */
    public record Line2DSpec(float x1, float y1, float x2, float y2, int color, float width) {
    }

    /**
     * Batched {@link Render2DEngine#drawThickLine}: every segment goes into ONE GUI element, for the same
     * reason {@link #drawProjectedBoxes} exists - a tracer per block would be a layer per block.
     */
    public static void drawLines2D(DrawContext context, @NotNull List<Line2DSpec> lines) {
        if (lines.isEmpty()) return;
        Matrix3x2f pose = GuiRender.pose(context);
        GuiRender.quads(context, Render2DEngine.currentClip(), vc -> {
            for (Line2DSpec l : lines)
                thickLine(vc, pose, l.x1(), l.y1(), l.x2(), l.y2(), l.color(), l.width());
        });
    }

    /** {@link Render2DEngine#drawThickLine} emitted into an existing batch instead of its own GUI element. */
    private static void thickLine(VertexConsumer vc, Matrix3x2f pose, float x, float y, float x1, float y1, int color, float width) {
        float dx = x1 - x, dy = y1 - y;
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len == 0) return;
        float half = Math.max(0.5f, width) / 2f;
        float nx = -dy / len * half, ny = dx / len * half;
        GuiRender.quad(vc, pose, x + nx, y + ny, x1 + nx, y1 + ny, x1 - nx, y1 - ny, x - nx, y - ny,
                color, color, color, color);
    }

    /**
     * Draws a STABLE screen box for a world point: projects the centre and derives the on-screen size from
     * the point's projected height, then draws a fixed square. Unlike {@link #drawBox2D} (which bounds all 8
     * projected corners and therefore grows/warps as the camera angle changes), this stays the same shape
     * regardless of viewing angle — only its size scales with distance.
     */
    public static void drawBoxCentered2D(DrawContext context, Vec3d center, double worldHeight, Color fillColor, Color outlineColor, boolean doFill, boolean doOutline) {
        Vec3d s = worldSpaceToScreenSpace(center);
        if (!(s.z > 0 && s.z < 1)) return;
        Vec3d top = worldSpaceToScreenSpace(center.add(0, worldHeight, 0));

        float half;
        if (top.z > 0 && top.z < 1)
            half = (float) Math.abs(s.y - top.y) / 2f;
        else
            // top corner projected behind the camera -> its screen pos is garbage. Falling through with it
            // blew the box up to cover the whole screen (the "green screen" when turning). Fall back to a
            // distance-scaled size instead.
            half = (float) (60.0 / Math.max(1.0, mc.getEntityRenderDispatcher().camera.getCameraPos().distanceTo(center)));

        // never let a projection glitch fill the whole screen
        half = MathHelper.clamp(half, 1.5f, mc.getWindow().getScaledHeight() / 2f);

        float bx = (float) s.x - half, by = (float) s.y - half, bw = half * 2f, bh = half * 2f, t = 1f;
        if (doFill)
            Render2DEngine.drawRect(context, bx, by, bw, bh, fillColor);
        if (doOutline) {
            Render2DEngine.drawRect(context, bx, by, bw, t, outlineColor);
            Render2DEngine.drawRect(context, bx, by + bh - t, bw, t, outlineColor);
            Render2DEngine.drawRect(context, bx, by, t, bh, outlineColor);
            Render2DEngine.drawRect(context, bx + bw - t, by, t, bh, outlineColor);
        }
    }

    /**
     * Thick GLOWING line between two world points, projected to screen. Drawn as stacked parallel 1px lines
     * whose alpha peaks in the centre and fades to the edges (soft centre->outside gradient), so it reads as
     * a glowing beam. Width is in screen pixels.
     */
    public static void drawGlowLine2D(DrawContext context, Vec3d a, Vec3d b, Color color, float width) {
        Vec3d sa = worldSpaceToScreenSpace(a);
        Vec3d sb = worldSpaceToScreenSpace(b);
        if (!(sa.z > 0 && sa.z < 1) || !(sb.z > 0 && sb.z < 1)) return;

        float ax = (float) sa.x, ay = (float) sa.y, bx = (float) sb.x, by = (float) sb.y;
        float dx = bx - ax, dy = by - ay;
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len < 0.01f) return;
        float px = -dy / len, py = dx / len; // perpendicular unit vector

        int layers = (int) MathHelper.clamp(width, 2f, 44f);
        for (int l = -layers; l <= layers; l++) {
            float off = (l / (float) layers) * (width / 2f);
            float f = 1f - Math.abs(l) / (float) (layers + 1); // 1 at centre, ~0 at edge
            int alpha = (int) (color.getAlpha() * f * f);        // quadratic falloff -> soft glow
            if (alpha <= 0) continue;
            int rgb = Render2DEngine.injectAlpha(color, alpha).getRGB();
            Render2DEngine.drawLine(context, ax + px * off, ay + py * off, bx + px * off, by + py * off, rgb);
        }
    }

    /** {@link #worldSpaceToScreenSpace} with the per-frame state passed in, for projecting many points at once. */
    private static Vec3d project(double x, double y, double z, Vec3d camPos, Matrix4f pv, int[] viewport, int displayHeight, double scale) {
        Vector4f v = new Vector4f((float) (x - camPos.x), (float) (y - camPos.y), (float) (z - camPos.z), 1f).mul(lastWorldSpaceMatrix);
        Vector3f target = new Vector3f();
        pv.project(v.x(), v.y(), v.z(), viewport, target);
        return new Vec3d(target.x / scale, (displayHeight - target.y) / scale, target.z);
    }

    /** {@link #project} for a world point, returning null when it lands behind the near plane. */
    private static @org.jetbrains.annotations.Nullable Vec3d project3(@NotNull Vec3d pos, Vec3d camPos, Matrix4f pv, int[] viewport, int displayHeight, double scale) {
        final Vec3d s = project(pos.x, pos.y, pos.z, camPos, pv, viewport, displayHeight, scale);
        return (s.z > 0 && s.z < 1) ? s : null;
    }

    /**
     * Circle outline in world space, lying in the plane of {@code facing}, projected to screen — used to mark
     * where a projectile is going to land. Orienting it to the hit face means it lies flat on the floor when
     * something lands on the ground and flush against a wall when it hits one.
     */
    public static void drawCircle2D(DrawContext context, @NotNull Vec3d center, @NotNull Direction facing, double radius, Color color, int segments) {
        if (segments < 3 || radius <= 0) return;

        // two orthogonal vectors spanning the plane perpendicular to the hit face
        final Vec3d normal = Vec3d.of(facing.getVector());
        final Vec3d helper = Math.abs(normal.y) > 0.9 ? new Vec3d(1, 0, 0) : new Vec3d(0, 1, 0);
        final Vec3d u = normal.crossProduct(helper).normalize();
        final Vec3d v = normal.crossProduct(u).normalize();

        final Vec3d camPos = mc.getEntityRenderDispatcher().camera.getCameraPos();
        final int displayHeight = mc.getWindow().getFramebufferHeight();
        final int[] viewport = new int[4];
        screenViewport(viewport);
        final Matrix4f pv = new Matrix4f(lastProjMat).mul(new Matrix4f(lastModMat));
        final double scale = getScaleFactor();
        final int argb = color.getRGB();

        Vec3d previous = null;
        for (int i = 0; i <= segments; i++) {
            final double angle = i * 2.0 * Math.PI / segments;
            final Vec3d point = center
                    .add(u.multiply(Math.cos(angle) * radius))
                    .add(v.multiply(Math.sin(angle) * radius));
            final Vec3d screen = project3(point, camPos, pv, viewport, displayHeight, scale);
            if (previous != null && screen != null)
                Render2DEngine.drawLine(context, (float) previous.x, (float) previous.y, (float) screen.x, (float) screen.y, argb);
            previous = screen;
        }
    }

    /** Draws a 2D line between two WORLD-space points by projecting both to screen (for Skeleton etc.). */
    public static void drawLine2D(DrawContext context, Vec3d a, Vec3d b, Color color) {
        Vec3d sa = worldSpaceToScreenSpace(a);
        Vec3d sb = worldSpaceToScreenSpace(b);
        if (!(sa.z > 0 && sa.z < 1) || !(sb.z > 0 && sb.z < 1)) return;
        Render2DEngine.drawLine(context, (float) sa.x, (float) sa.y, (float) sb.x, (float) sb.y, color.getRGB());
    }

    public static @NotNull Vec3d worldSpaceToScreenSpace(@NotNull Vec3d pos) {
        Camera camera = mc.getEntityRenderDispatcher().camera;
        int displayHeight = mc.getWindow().getFramebufferHeight();
        int[] viewport = new int[4];
        screenViewport(viewport);
        Vector3f target = new Vector3f();

        double deltaX = pos.x - camera.getCameraPos().x;
        double deltaY = pos.y - camera.getCameraPos().y;
        double deltaZ = pos.z - camera.getCameraPos().z;

        Vector4f transformedCoordinates = new Vector4f((float) deltaX, (float) deltaY, (float) deltaZ, 1.f).mul(lastWorldSpaceMatrix);
        Matrix4f matrixProj = new Matrix4f(lastProjMat);
        Matrix4f matrixModel = new Matrix4f(lastModMat);
        matrixProj.mul(matrixModel).project(transformedCoordinates.x(), transformedCoordinates.y(), transformedCoordinates.z(), viewport, target);

        return new Vec3d(target.x / getScaleFactor(), (displayHeight - target.y) / getScaleFactor(), target.z);
    }

    /**
     * The viewport world points are projected into: the whole main framebuffer.
     *
     * <p>This used to be {@code glGetIntegerv(GL_VIEWPORT)}, which on 1.21.11 reads whatever viewport the
     * last GPU pass happened to leave behind - GUI rendering is deferred, so by the time our 2D code runs that
     * is not the world's viewport but anything a mod, a framebuffer blit or the driver set last. It matched
     * on one machine and not on another, and every projected visual (ESP boxes, tracers, particles, Name Tags)
     * landed beside its target. It was also paired with the WINDOW height, which differs from the framebuffer
     * height under Windows display scaling (125%/150% laptops). Both now come from the framebuffer.
     */
    public static void screenViewport(int[] viewport) {
        viewport[0] = 0;
        viewport[1] = 0;
        viewport[2] = mc.getWindow().getFramebufferWidth();
        viewport[3] = mc.getWindow().getFramebufferHeight();
    }

    public static double getScaleFactor() {
        return ClientSettings.scaleFactorFix.getValue() ? ClientSettings.scaleFactorFixValue.getValue() : mc.getWindow().getScaleFactor();
    }

    @Deprecated
    @SuppressWarnings("unused")
    public static void drawFilledFadeBox(@NotNull MatrixStack stack, @NotNull Box box, @NotNull Color c, @NotNull Color c1) {
        FADE_QUEUE.add(new FadeAction(box, c, c1));
    }

    public static void setFilledFadePoints(Box box, VertexConsumer buffer, Matrix4f posMatrix, Color c, Color c1) {
        float minX = (float) (box.minX - mc.getEntityRenderDispatcher().camera.getCameraPos().getX());
        float minY = (float) (box.minY - mc.getEntityRenderDispatcher().camera.getCameraPos().getY());
        float minZ = (float) (box.minZ - mc.getEntityRenderDispatcher().camera.getCameraPos().getZ());
        float maxX = (float) (box.maxX - mc.getEntityRenderDispatcher().camera.getCameraPos().getX());
        float maxY = (float) (box.maxY - mc.getEntityRenderDispatcher().camera.getCameraPos().getY());
        float maxZ = (float) (box.maxZ - mc.getEntityRenderDispatcher().camera.getCameraPos().getZ());

        int bottom = c.getRGB();
        int top = c1.getRGB();

        Render3DUtil.vertex(buffer, posMatrix, minX, minY, minZ, bottom);
        Render3DUtil.vertex(buffer, posMatrix, minX, maxY, minZ, top);
        Render3DUtil.vertex(buffer, posMatrix, maxX, maxY, minZ, top);
        Render3DUtil.vertex(buffer, posMatrix, maxX, minY, minZ, bottom);

        Render3DUtil.vertex(buffer, posMatrix, maxX, minY, minZ, bottom);
        Render3DUtil.vertex(buffer, posMatrix, maxX, maxY, minZ, top);
        Render3DUtil.vertex(buffer, posMatrix, maxX, maxY, maxZ, top);
        Render3DUtil.vertex(buffer, posMatrix, maxX, minY, maxZ, bottom);

        Render3DUtil.vertex(buffer, posMatrix, minX, minY, maxZ, bottom);
        Render3DUtil.vertex(buffer, posMatrix, maxX, minY, maxZ, bottom);
        Render3DUtil.vertex(buffer, posMatrix, maxX, maxY, maxZ, top);
        Render3DUtil.vertex(buffer, posMatrix, minX, maxY, maxZ, top);

        Render3DUtil.vertex(buffer, posMatrix, minX, minY, minZ, bottom);
        Render3DUtil.vertex(buffer, posMatrix, minX, minY, maxZ, bottom);
        Render3DUtil.vertex(buffer, posMatrix, minX, maxY, maxZ, top);
        Render3DUtil.vertex(buffer, posMatrix, minX, maxY, minZ, top);

        Render3DUtil.vertex(buffer, posMatrix, minX, maxY, minZ, top);
        Render3DUtil.vertex(buffer, posMatrix, minX, maxY, maxZ, top);
        Render3DUtil.vertex(buffer, posMatrix, maxX, maxY, maxZ, top);
        Render3DUtil.vertex(buffer, posMatrix, maxX, maxY, minZ, top);
    }

    public static void drawLine(@NotNull Vec3d start, @NotNull Vec3d end, @NotNull Color color) {
        LINE_QUEUE.add(new LineAction(start, end, color, Render3DUtil.LINE_WIDTH));
    }

    public static void drawLine(@NotNull Vec3d start, @NotNull Vec3d end, @NotNull Color color, float width) {
        LINE_QUEUE.add(new LineAction(start, end, color, width));
    }

    @Deprecated
    public static void drawBoxOutline(@NotNull Box box, Color color, float lineWidth) {
        OUTLINE_QUEUE.add(new OutlineAction(box, color, lineWidth));
    }

    public static void setOutlinePoints(Box box, MatrixStack matrices, VertexConsumer buffer, Color color) {
        box = box.offset(new Vec3d(box.minX, box.minY, box.minZ).negate());

        float x1 = (float) box.minX;
        float y1 = (float) box.minY;
        float z1 = (float) box.minZ;
        float x2 = (float) box.maxX;
        float y2 = (float) box.maxY;
        float z2 = (float) box.maxZ;

        vertexLine(matrices, buffer, x1, y1, z1, x2, y1, z1, color);
        vertexLine(matrices, buffer, x2, y1, z1, x2, y1, z2, color);
        vertexLine(matrices, buffer, x2, y1, z2, x1, y1, z2, color);
        vertexLine(matrices, buffer, x1, y1, z2, x1, y1, z1, color);
        vertexLine(matrices, buffer, x1, y1, z2, x1, y2, z2, color);
        vertexLine(matrices, buffer, x1, y1, z1, x1, y2, z1, color);
        vertexLine(matrices, buffer, x2, y1, z2, x2, y2, z2, color);
        vertexLine(matrices, buffer, x2, y1, z1, x2, y2, z1, color);
        vertexLine(matrices, buffer, x1, y2, z1, x2, y2, z1, color);
        vertexLine(matrices, buffer, x2, y2, z1, x2, y2, z2, color);
        vertexLine(matrices, buffer, x2, y2, z2, x1, y2, z2, color);
        vertexLine(matrices, buffer, x1, y2, z2, x1, y2, z1, color);
    }

    @Deprecated
    public static void drawSideOutline(@NotNull Box box, Color color, float lineWidth, Direction dir) {
        OUTLINE_SIDE_QUEUE.add(new OutlineSideAction(box, color, lineWidth, dir));
    }

    public static void setSideOutlinePoints(Box box, MatrixStack matrices, VertexConsumer buffer, Color color, Direction dir) {
        box = box.offset(new Vec3d(box.minX, box.minY, box.minZ).negate());

        float x1 = (float) box.minX;
        float y1 = (float) box.minY;
        float z1 = (float) box.minZ;
        float x2 = (float) box.maxX;
        float y2 = (float) box.maxY;
        float z2 = (float) box.maxZ;

        switch (dir) {
            case UP -> {
                vertexLine(matrices, buffer, x1, y2, z1, x2, y2, z1, color);
                vertexLine(matrices, buffer, x2, y2, z1, x2, y2, z2, color);
                vertexLine(matrices, buffer, x2, y2, z2, x1, y2, z2, color);
                vertexLine(matrices, buffer, x1, y2, z2, x1, y2, z1, color);
            }
            case DOWN -> {
                vertexLine(matrices, buffer, x1, y1, z1, x2, y1, z1, color);
                vertexLine(matrices, buffer, x2, y1, z1, x2, y1, z2, color);
                vertexLine(matrices, buffer, x2, y1, z2, x1, y1, z2, color);
                vertexLine(matrices, buffer, x1, y1, z2, x1, y1, z1, color);
            }
            case EAST -> {
                vertexLine(matrices, buffer, x2, y1, z1, x2, y2, z1, color);
                vertexLine(matrices, buffer, x2, y1, z2, x2, y2, z2, color);
                vertexLine(matrices, buffer, x2, y2, z2, x2, y2, z1, color);
                vertexLine(matrices, buffer, x2, y1, z2, x2, y1, z1, color);
            }
            case WEST -> {
                vertexLine(matrices, buffer, x1, y1, z1, x1, y2, z1, color);
                vertexLine(matrices, buffer, x1, y1, z2, x1, y2, z2, color);
                vertexLine(matrices, buffer, x1, y2, z2, x1, y2, z1, color);
                vertexLine(matrices, buffer, x1, y1, z2, x1, y1, z1, color);
            }
            case NORTH -> {
                vertexLine(matrices, buffer, x2, y1, z1, x2, y2, z1, color);
                vertexLine(matrices, buffer, x1, y1, z1, x1, y2, z1, color);
                vertexLine(matrices, buffer, x2, y1, z1, x1, y1, z1, color);
                vertexLine(matrices, buffer, x2, y2, z1, x1, y2, z1, color);
            }
            case SOUTH -> {
                vertexLine(matrices, buffer, x1, y1, z2, x1, y2, z2, color);
                vertexLine(matrices, buffer, x2, y1, z2, x2, y2, z2, color);
                vertexLine(matrices, buffer, x1, y1, z2, x2, y1, z2, color);
                vertexLine(matrices, buffer, x1, y2, z2, x2, y2, z2, color);
            }
        }
    }

    public static void drawHoleOutline(@NotNull Box box, Color color, float lineWidth) {
        MatrixStack matrices = matrixFrom(box.minX, box.minY, box.minZ);
        VertexConsumer buffer = Render3DUtil.buffer(Render3DUtil.LINES);

        box = box.offset(new Vec3d(box.minX, box.minY, box.minZ).negate());

        float x1 = (float) box.minX;
        float y1 = (float) box.minY;
        float y2 = (float) box.maxY;
        float z1 = (float) box.minZ;
        float x2 = (float) box.maxX;
        float z2 = (float) box.maxZ;

        vertexLine(matrices, buffer, x1, y1, z1, x2, y1, z1, color);
        vertexLine(matrices, buffer, x2, y1, z1, x2, y1, z2, color);
        vertexLine(matrices, buffer, x2, y1, z2, x1, y1, z2, color);
        vertexLine(matrices, buffer, x1, y1, z2, x1, y1, z1, color);

        vertexLine(matrices, buffer, x1, y1, z1, x1, y2, z1, color);
        vertexLine(matrices, buffer, x2, y1, z2, x2, y2, z2, color);
        vertexLine(matrices, buffer, x1, y1, z2, x1, y2, z2, color);
        vertexLine(matrices, buffer, x2, y1, z1, x2, y2, z1, color);

        Render3DUtil.flush(Render3DUtil.LINES);
    }

    public static void vertexLine(@NotNull MatrixStack matrices, @NotNull VertexConsumer buffer, float x1, float y1, float z1, float x2, float y2, float z2, @NotNull Color lineColor) {
        Render3DUtil.line(buffer, matrices.peek().getPositionMatrix(), x1, y1, z1, x2, y2, z2, lineColor.getRGB());
    }

    public static @NotNull Vector3f getNormal(float x1, float y1, float z1, float x2, float y2, float z2) {
        float xNormal = x2 - x1;
        float yNormal = y2 - y1;
        float zNormal = z2 - z1;
        float normalSqrt = MathHelper.sqrt(xNormal * xNormal + yNormal * yNormal + zNormal * zNormal);

        return new Vector3f(xNormal / normalSqrt, yNormal / normalSqrt, zNormal / normalSqrt);
    }

    public static @NotNull MatrixStack matrixFrom(double x, double y, double z) {
        MatrixStack matrices = new MatrixStack();

        Camera camera = MinecraftClient.getInstance().gameRenderer.getCamera();
        matrices.multiply(RotationAxis.POSITIVE_X.rotationDegrees(camera.getPitch()));
        matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(camera.getYaw() + 180.0F));

        matrices.translate(x - camera.getCameraPos().x, y - camera.getCameraPos().y, z - camera.getCameraPos().z);

        return matrices;
    }

    public static void setupRender() {
        // Blend / depth / cull state is now encapsulated by the RenderLayers in Render3DUtil. No-op.
    }

    public static void endRender() {
        // No-op; kept for API compatibility with callers from the immediate-mode era.
    }

    public static void drawTargetEsp(MatrixStack stack, @NotNull Entity target) {
        ArrayList<Vec3d> vecs = new ArrayList<>();
        ArrayList<Vec3d> vecs1 = new ArrayList<>();
        ArrayList<Vec3d> vecs2 = new ArrayList<>();

        double x = target.lastX + (target.getX() - target.lastX) * getTickDelta()
                - mc.getEntityRenderDispatcher().camera.getCameraPos().getX();
        double y = target.lastY + (target.getY() - target.lastY) * getTickDelta()
                - mc.getEntityRenderDispatcher().camera.getCameraPos().getY();
        double z = target.lastZ + (target.getZ() - target.lastZ) * getTickDelta()
                - mc.getEntityRenderDispatcher().camera.getCameraPos().getZ();

        double height = target.getHeight();

        for (int i = 0; i <= 361; ++i) {
            double v = Math.sin(Math.toRadians(i));
            double u = Math.cos(Math.toRadians(i));
            vecs.add(new Vec3d((float) (u * 0.5f), height, (float) (v * 0.5f)));

            double v1 = Math.sin(Math.toRadians((i + 120) % 360));
            double u1 = Math.cos(Math.toRadians(i + 120) % 360);
            vecs1.add(new Vec3d((float) (u1 * 0.5f), height, (float) (v1 * 0.5f)));

            double v2 = Math.sin(Math.toRadians((i + 240) % 360));
            double u2 = Math.cos(Math.toRadians((i + 240) % 360));
            vecs2.add(new Vec3d((float) (u2 * 0.5f), height, (float) (v2 * 0.5f)));
            height -= 0.004f;
        }

        stack.push();
        stack.translate(x, y, z);
        Matrix4f matrix = stack.peek().getPositionMatrix();

        VertexConsumer vc = Render3DUtil.buffer(Render3DUtil.QUADS);
        drawRibbon(vc, matrix, vecs);
        drawRibbon(vc, matrix, vecs1);
        drawRibbon(vc, matrix, vecs2);
        Render3DUtil.flush(Render3DUtil.QUADS);

        stack.translate(-x, -y, -z);
        stack.pop();
    }

    // Converts the original TRIANGLE_STRIP ribbon (alternating low/high ring points) into quads.
    private static void drawRibbon(VertexConsumer vc, Matrix4f matrix, @NotNull List<Vec3d> vecs) {
        for (int j = 0; j < vecs.size() - 2; ++j) {
            float alpha = 1f - (((float) j + ((System.currentTimeMillis() - ThunderHack.initTime) / 5f)) % 360) / 60f;
            int color = Render2DEngine.injectAlpha(HudEditor.getColor((int) (j / 20f)), (int) (alpha * 255)).getRGB();

            Vec3d a0 = vecs.get(j);
            Vec3d b0 = vecs.get(j + 1);
            Vec3d b1 = vecs.get(j + 2);

            Render3DUtil.quad(vc, matrix,
                    (float) a0.x, (float) a0.y, (float) a0.z,
                    (float) b0.x, (float) b0.y + 0.1f, (float) b0.z,
                    (float) b1.x, (float) b1.y + 0.1f, (float) b1.z,
                    (float) b0.x, (float) b0.y, (float) b0.z,
                    color);
        }
    }

    public static void renderCrosses(@NotNull Box box, Color color, float lineWidth) {
        MatrixStack matrices = matrixFrom(box.minX, box.minY, box.minZ);
        VertexConsumer buffer = Render3DUtil.buffer(Render3DUtil.LINES);

        box = box.offset(new Vec3d(box.minX, box.minY, box.minZ).negate());

        vertexLine(matrices, buffer, (float) box.maxX, (float) box.minY, (float) box.minZ, (float) box.minX, (float) box.minY, (float) box.maxZ, color);
        vertexLine(matrices, buffer, (float) box.minX, (float) box.minY, (float) box.minZ, (float) box.maxX, (float) box.minY, (float) box.maxZ, color);

        Render3DUtil.flush(Render3DUtil.LINES);
    }

    public static void drawSphere(MatrixStack matrix, float radius, int slices, int stacks, int color) {
        float drho = 3.1415927F / ((float) stacks);
        float dtheta = 6.2831855F / ((float) slices - 1f);
        Matrix4f m = matrix.peek().getPositionMatrix();
        VertexConsumer buffer = Render3DUtil.buffer(Render3DUtil.LINES);

        for (int i = 1; i < stacks; ++i) {
            float rho = (float) i * drho;
            float prevX = 0, prevY = 0, prevZ = 0;
            for (int j = 0; j < slices; ++j) {
                float theta = (float) j * dtheta;
                float cx = (float) (Math.cos(theta) * Math.sin(rho)) * radius;
                float cy = (float) (Math.sin(theta) * Math.sin(rho)) * radius;
                float cz = (float) Math.cos(rho) * radius;
                if (j > 0)
                    Render3DUtil.line(buffer, m, prevX, prevY, prevZ, cx, cy, cz, color);
                prevX = cx;
                prevY = cy;
                prevZ = cz;
            }
        }

        for (int j = 0; j < slices; ++j) {
            float theta = (float) j * dtheta;
            float prevX = 0, prevY = 0, prevZ = 0;
            for (int i = 0; i <= stacks; ++i) {
                float rho = (float) i * drho;
                float cx = (float) (Math.cos(theta) * Math.sin(rho)) * radius;
                float cy = (float) (Math.sin(theta) * Math.sin(rho)) * radius;
                float cz = (float) Math.cos(rho) * radius;
                if (i > 0)
                    Render3DUtil.line(buffer, m, prevX, prevY, prevZ, cx, cy, cz, color);
                prevX = cx;
                prevY = cy;
                prevZ = cz;
            }
        }
        Render3DUtil.flush(Render3DUtil.LINES);
    }

    public static void drawCylinder(MatrixStack stack, final float radius, final float height, final int slices, final int stacks, int color) {
        final float da = (float) ((Math.PI * 2f) / slices);
        final float dz = height / stacks;
        Matrix4f m = stack.peek().getPositionMatrix();
        VertexConsumer buffer = Render3DUtil.buffer(Render3DUtil.LINES);

        float y = 0;
        for (int j = 0; j <= stacks; ++j) {
            float prevX = 0, prevZ = 0;
            for (int i = 0; i <= slices; ++i) {
                final float x = (float) Math.cos(i * da) * radius;
                final float z = (float) Math.sin(i * da) * radius;
                if (i > 0)
                    Render3DUtil.line(buffer, m, prevX, y, prevZ, x, y, z, color);
                prevX = x;
                prevZ = z;
            }
            y += dz;
        }

        for (int i = 0; i <= slices; ++i) {
            final float x = (float) Math.cos(i * da) * radius;
            final float z = (float) Math.sin(i * da) * radius;
            Render3DUtil.line(buffer, m, x, 0, z, x, height, z, color);
        }

        Render3DUtil.flush(Render3DUtil.LINES);
    }

    public static void drawCircle3D(MatrixStack stack, Entity ent, float radius, int color, int points, boolean hudColor, int colorOffset) {
        double x = ent.lastX + (ent.getX() - ent.lastX) * getTickDelta() - mc.getEntityRenderDispatcher().camera.getCameraPos().getX();
        double y = ent.lastY + (ent.getY() - ent.lastY) * getTickDelta() - mc.getEntityRenderDispatcher().camera.getCameraPos().getY();
        double z = ent.lastZ + (ent.getZ() - ent.lastZ) * getTickDelta() - mc.getEntityRenderDispatcher().camera.getCameraPos().getZ();
        stack.push();
        stack.translate(x, y, z);

        Matrix4f matrix = stack.peek().getPositionMatrix();
        VertexConsumer buffer = Render3DUtil.buffer(Render3DUtil.LINES);

        float prevX = 0, prevZ = 0;
        for (int i = 0; i <= points; i++) {
            if (hudColor)
                color = HudEditor.getColor(i * colorOffset).getRGB();

            float cx = (float) (radius * Math.cos(i * 6.28 / points));
            float cz = (float) (radius * Math.sin(i * 6.28 / points));
            if (i > 0)
                Render3DUtil.line(buffer, matrix, prevX, 0f, prevZ, cx, 0f, cz, color);
            prevX = cx;
            prevZ = cz;
        }

        Render3DUtil.flush(Render3DUtil.LINES);
        stack.translate(-x, -y, -z);
        stack.pop();
    }

    public static void drawOldTargetEsp(MatrixStack stack, Entity target) {
        double cs = prevCircleStep + (circleStep - prevCircleStep) * getTickDelta();
        double prevSinAnim = absSinAnimation(cs - 0.45f);
        double sinAnim = absSinAnimation(cs);
        double x = target.lastX + (target.getX() - target.lastX) * getTickDelta() - mc.getEntityRenderDispatcher().camera.getCameraPos().getX();
        double y = target.lastY + (target.getY() - target.lastY) * getTickDelta() - mc.getEntityRenderDispatcher().camera.getCameraPos().getY() + prevSinAnim * target.getHeight();
        double z = target.lastZ + (target.getZ() - target.lastZ) * getTickDelta() - mc.getEntityRenderDispatcher().camera.getCameraPos().getZ();
        double nextY = target.lastY + (target.getY() - target.lastY) * getTickDelta() - mc.getEntityRenderDispatcher().camera.getCameraPos().getY() + sinAnim * target.getHeight();
        stack.push();
        Matrix4f matrix = stack.peek().getPositionMatrix();
        VertexConsumer buffer = Render3DUtil.buffer(Render3DUtil.QUADS);

        for (int i = 0; i < 30; i++) {
            float cos0 = (float) (x + Math.cos(i * 6.28 / 30) * target.getWidth() * 0.8);
            float sin0 = (float) (z + Math.sin(i * 6.28 / 30) * target.getWidth() * 0.8);
            float cos1 = (float) (x + Math.cos((i + 1) * 6.28 / 30) * target.getWidth() * 0.8);
            float sin1 = (float) (z + Math.sin((i + 1) * 6.28 / 30) * target.getWidth() * 0.8);

            int top0 = Render2DEngine.injectAlpha(HudEditor.getColor(i), 170).getRGB();
            int bottom0 = Render2DEngine.injectAlpha(HudEditor.getColor(i), 0).getRGB();
            int top1 = Render2DEngine.injectAlpha(HudEditor.getColor(i + 1), 170).getRGB();
            int bottom1 = Render2DEngine.injectAlpha(HudEditor.getColor(i + 1), 0).getRGB();

            // quad: top_i, bottom_i, bottom_{i+1}, top_{i+1}
            Render3DUtil.vertex(buffer, matrix, cos0, (float) nextY, sin0, top0);
            Render3DUtil.vertex(buffer, matrix, cos0, (float) y, sin0, bottom0);
            Render3DUtil.vertex(buffer, matrix, cos1, (float) y, sin1, bottom1);
            Render3DUtil.vertex(buffer, matrix, cos1, (float) nextY, sin1, top1);
        }

        Render3DUtil.flush(Render3DUtil.QUADS);
        stack.pop();
    }

    // Kalry не пасть
    // anti yg protection
    /** The ghosts' eased lean: the target it belongs to and its smoothed horizontal velocity. */
    private static int ghostTiltId = -1;
    private static double ghostTiltX, ghostTiltZ;

    /** How the ThunderHack V2 ghosts circle the target. */
    public enum GhostStyle {
        /** The classic: three trails round the body at chest height. */
        Orbit,
        /** Each trail on its own tilted, slowly turning orbit - an atom seen from above. */
        Diagonal,
        /** A tight ring above the head only, like a halo. */
        Halo
    }

    public static void renderGhosts(int espLength, int factor, float shaking, float amplitude, Entity target) {
        renderGhosts(espLength, factor, shaking, amplitude, target, GhostStyle.Orbit);
    }

    /** One camera-facing glow sprite of the given half-size, centred on the matrix origin. */
    private static void ghostSprite(VertexConsumer buffer, Matrix4f matrix, float s, int color) {
        Render3DUtil.vertexTex(buffer, matrix, -s, s, 0, 0f, 1f, color);
        Render3DUtil.vertexTex(buffer, matrix, s, s, 0, 1f, 1f, color);
        Render3DUtil.vertexTex(buffer, matrix, s, -s, 0, 1f, 0f, color);
        Render3DUtil.vertexTex(buffer, matrix, -s, -s, 0, 0f, 0f, color);
    }

    public static void renderGhosts(int espLength, int factor, float shaking, float amplitude, Entity target, GhostStyle style) {
        Camera camera = mc.gameRenderer.getCamera();

        double tPosX = Render2DEngine.interpolate(target.lastRenderX, target.getX(), Render3DEngine.getTickDelta()) - camera.getCameraPos().x;
        double tPosY = Render2DEngine.interpolate(target.lastRenderY, target.getY(), Render3DEngine.getTickDelta()) - camera.getCameraPos().y;
        double tPosZ = Render2DEngine.interpolate(target.lastRenderZ, target.getZ(), Render3DEngine.getTickDelta()) - camera.getCameraPos().z;
        float iAge = (float) Render2DEngine.interpolate(target.age - 1, target.age, Render3DEngine.getTickDelta());

        // ghostGlow, not fireflyAlpha: firefly_a is a hard-edged disc, so the trail read as a string of separate
        // circles. The soft halo lets neighbouring sprites melt into one smooth glowing streak, as in ThunderHack.
        RenderLayer layer = Render3DUtil.texturedLayerNoDepth(TextureStorage.ghostGlow);
        VertexConsumer buffer = Render3DUtil.buffer(layer);

        // One MatrixStack for the whole effect (push/pop per quad) instead of a fresh one — plus the four
        // rotation quaternions hoisted out of the loop. At EspLength 14 this used to build 45 stacks and
        // 180 quaternions EVERY frame, which is exactly the kind of per-frame garbage that shows up as
        // micro-stutter while a target is locked.
        MatrixStack matrices = new MatrixStack();
        float camPitch = camera.getPitch();
        float camYaw = camera.getYaw();
        var pitchRot = RotationAxis.POSITIVE_X.rotationDegrees(camPitch);
        var yawRotBack = RotationAxis.POSITIVE_Y.rotationDegrees(camYaw + 180.0F);
        var yawRotInv = RotationAxis.POSITIVE_Y.rotationDegrees(-camYaw);
        float width = target.getWidth();

        // Lean with the motion: the orbit tips over in the direction the target travels - a little when it runs,
        // a lot on an elytra - so the souls trail like they are being dragged along. Eased so it never snaps.
        double velX = target.getX() - target.lastX, velZ = target.getZ() - target.lastZ;
        if (target.getId() != ghostTiltId) {
            ghostTiltId = target.getId();
            ghostTiltX = ghostTiltZ = 0;
        }
        ghostTiltX += (velX - ghostTiltX) * 0.12;
        ghostTiltZ += (velZ - ghostTiltZ) * 0.12;
        double speed = Math.sqrt(ghostTiltX * ghostTiltX + ghostTiltZ * ghostTiltZ);
        // rise per block of horizontal offset: 0 standing, ~0.35 sprinting, capped at 0.9 flying
        double lean = Math.min(0.9, speed * 1.2);
        double dirX = speed > 1e-4 ? ghostTiltX / speed : 0, dirZ = speed > 1e-4 ? ghostTiltZ / speed : 0;

        for (int j = 0; j < 3; j++) {
            for (int i = 0; i <= espLength; i++) {
                double radians = Math.toRadians((((float) i / 1.5f + iAge) * factor + (j * 120)) % (factor * 360));
                double sinQuad = Math.sin(Math.toRadians(iAge * 2.5f + i * (j + 1)) * amplitude) / shaking;

                double ox = Math.cos(radians) * width, oz = Math.sin(radians) * width;
                double baseY = 1 + sinQuad;
                switch (style) {
                    case Diagonal -> {
                        // each trail's orbit tipped ~35 degrees about its own horizontal axis, the three axes
                        // 120 degrees apart and turning slowly - crossing rings, like an atom
                        double axis = Math.toRadians(iAge * 1.2 + j * 120);
                        baseY = 1 + sinQuad * 0.35 + (ox * Math.cos(axis) + oz * Math.sin(axis)) * 0.7;
                    }
                    case Halo -> {
                        // a tight ring just above the head, barely bobbing
                        ox *= 0.6;
                        oz *= 0.6;
                        baseY = target.getHeight() + 0.25 + sinQuad * 0.12;
                    }
                    default -> {
                    }
                }
                // the leading side of the orbit dips, the trailing side lifts
                double tilt = -(ox * dirX + oz * dirZ) * lean;

                float offset = ((float) i / espLength);
                matrices.push();
                matrices.multiply(pitchRot);
                matrices.multiply(yawRotBack);
                matrices.translate(tPosX + ox, (tPosY + baseY + tilt), tPosZ + oz);
                matrices.multiply(yawRotInv);
                matrices.multiply(pitchRot);
                Matrix4f matrix = matrices.peek().getPositionMatrix();
                java.awt.Color base = HudEditor.getColor((int) (180 * offset));
                // larger than the old disc: most of ghostGlow is soft halo, and the halos must overlap
                float scale = Math.max(0.24f * (offset), 0.2f) * 1.7f;
                ghostSprite(buffer, matrix, scale, Render2DEngine.applyOpacity(base, offset).getRGB());
                matrices.pop();
            }
        }

        Render3DUtil.flush(layer);
    }

    /* ------------------------------------------------------------------ */
    /* World sprites - effects drawn from an IMAGE instead of hand-coded   */
    /* ------------------------------------------------------------------ */

    /**
     * Starts a world-space matrix that carries the camera rotation exactly once, so geometry baked
     * through it stands still in the world as the camera turns. Every sprite helper below builds on it -
     * it is the same setup the target-ESP fireflies have always used, which is the one textured path on
     * 1.21.11 that is known to sit correctly in the world.
     */
    private static @NotNull MatrixStack worldMatrix() {
        Camera camera = mc.gameRenderer.getCamera();
        MatrixStack matrices = new MatrixStack();
        matrices.multiply(RotationAxis.POSITIVE_X.rotationDegrees(camera.getPitch()));
        matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(camera.getYaw() + 180f));
        return matrices;
    }

    /**
     * A texture lying flat on the ground in real world space - the jump ring, a marker circle, a rune.
     *
     * <p>This replaces the old screen-projected mesh, which subdivided the square into 144 cells and
     * projected every corner by hand. Actual world geometry is one quad, needs no near-plane special
     * cases, and cannot shear: the perspective is the GPU's job, not ours.
     *
     * @param angle rotation around the sprite's own centre, in radians
     */
    public static void drawGroundSprite3D(@NotNull Identifier texture, @NotNull Vec3d center,
                                          double radius, double angle, @NotNull Color tint, boolean throughWalls) {
        RenderLayer layer = Render3DUtil.spriteLayer(TintedTextures.tinted(texture, tint), throughWalls);
        tint = alphaOnly(tint);
        VertexConsumer buffer = Render3DUtil.buffer(layer);
        emitGroundSprite(buffer, center, radius, angle, tint);
        Render3DUtil.flush(layer);
    }

    /** Batched twin of {@link #drawGroundSprite3D} - many rings on one texture, one flush. */
    public static void drawGroundSprites3D(@NotNull Identifier texture, @NotNull List<GroundSprite> sprites, boolean throughWalls) {
        if (sprites.isEmpty()) return;

        // One layer per texture, so a batch has to share a tint: the first sprite's colour is the one
        // the whole batch gets. Callers batch by effect, and an effect is one colour.
        RenderLayer layer = Render3DUtil.spriteLayer(
                TintedTextures.tinted(texture, sprites.getFirst().tint()), throughWalls);
        VertexConsumer buffer = Render3DUtil.buffer(layer);
        for (GroundSprite sprite : sprites)
            emitGroundSprite(buffer, sprite.center(), sprite.radius(), sprite.angle(), alphaOnly(sprite.tint()),
                    sprite.u0(), sprite.v0(), sprite.u1(), sprite.v1());
        Render3DUtil.flush(layer);
    }

    /**
     * One textured quad in the world.
     *
     * <p>{@code u0..v1} is the WINDOW of the texture the quad shows. That window is what lets one sheet run
     * on across many quads: hand each block the window its world position falls into and the picture
     * continues from its neighbour instead of restarting, which is the difference between a tiled surface
     * and a grid of little squares. The four-argument form shows the whole texture, which is what every
     * sprite that is a picture of something (rings, sigils, halos) wants.
     */
    public record GroundSprite(Vec3d center, double radius, double angle, Color tint,
                               float u0, float v0, float u1, float v1) {
        public GroundSprite(Vec3d center, double radius, double angle, Color tint) {
            this(center, radius, angle, tint, 0f, 0f, 1f, 1f);
        }
    }

    /**
     * One camera-facing quad showing a REGION of a texture - a skin's face, say - pixel-sharp.
     *
     * @param spinDeg rotation in the view plane
     */
    public static void drawBillboardRegion(@NotNull Identifier texture, @NotNull Vec3d center, double half,
                                           float u0, float v0, float u1, float v1, int alpha, float spinDeg) {
        RenderLayer layer = Render3DUtil.pixelLayer(texture);
        VertexConsumer buffer = Render3DUtil.buffer(layer);
        Camera camera = mc.gameRenderer.getCamera();
        Vec3d cam = camera.getCameraPos();
        org.joml.Quaternionf rotation = camera.getRotation();
        Matrix4f m = worldMatrix().peek().getPositionMatrix();

        int argb = new Color(255, 255, 255, Math.max(0, Math.min(255, alpha))).getRGB();
        float cos = (float) Math.cos(Math.toRadians(spinDeg)), sin = (float) Math.sin(Math.toRadians(spinDeg));
        float px = (float) (center.x - cam.x), py = (float) (center.y - cam.y), pz = (float) (center.z - cam.z);
        float[][] offsets = {{-1, -1}, {1, -1}, {1, 1}, {-1, 1}};
        float[][] uvs = {{u0, v1}, {u1, v1}, {u1, v0}, {u0, v0}};
        Vector3f corner = new Vector3f();
        float h = (float) half;
        for (int i = 0; i < 4; i++) {
            float ox = offsets[i][0] * h, oy = offsets[i][1] * h;
            corner.set(ox * cos - oy * sin, ox * sin + oy * cos, 0f);
            rotation.transform(corner);
            Render3DUtil.vertexTex(buffer, m, px + corner.x, py + corner.y, pz + corner.z, uvs[i][0], uvs[i][1], argb);
        }
        Render3DUtil.flush(layer);
    }

    /**
     * Camera-facing quads, one draw call per texture - the way vanilla draws its own particles.
     *
     * <p>Particles used to go through {@link #drawGroundSprites3D}, which lays every quad FLAT. A star hanging
     * in the air seen from the side is then a quad edge-on: zero pixels thick, so trails, motes and sparks
     * simply did not show unless you looked straight down at them. Rotating each corner by the camera's
     * orientation keeps the quad square to the view from any angle.
     */
    public static void drawBillboards3D(@NotNull Identifier texture, @NotNull List<GroundSprite> sprites, boolean throughWalls) {
        if (sprites.isEmpty()) return;

        RenderLayer layer = Render3DUtil.spriteLayer(
                TintedTextures.tinted(texture, sprites.getFirst().tint()), throughWalls);
        VertexConsumer buffer = Render3DUtil.buffer(layer);

        Camera camera = mc.gameRenderer.getCamera();
        Vec3d cam = camera.getCameraPos();
        org.joml.Quaternionf rotation = camera.getRotation();
        Matrix4f m = worldMatrix().peek().getPositionMatrix();

        Vector3f corner = new Vector3f();
        float[][] offsets = {{-1, -1}, {1, -1}, {1, 1}, {-1, 1}};

        for (GroundSprite sprite : sprites) {
            int argb = alphaOnly(sprite.tint()).getRGB();
            float r = (float) sprite.radius();
            float cos = (float) Math.cos(sprite.angle()), sin = (float) Math.sin(sprite.angle());
            float px = (float) (sprite.center().x - cam.x), py = (float) (sprite.center().y - cam.y), pz = (float) (sprite.center().z - cam.z);

            for (int i = 0; i < 4; i++) {
                float ox = offsets[i][0] * r, oy = offsets[i][1] * r;
                corner.set(ox * cos - oy * sin, ox * sin + oy * cos, 0f);
                rotation.transform(corner);
                // corner order is (-,-) (+,-) (+,+) (-,+), and a billboard's V runs the other way up
                float u = (i == 0 || i == 3) ? sprite.u0() : sprite.u1();
                float v = i < 2 ? sprite.v1() : sprite.v0();
                Render3DUtil.vertexTex(buffer, m, px + corner.x, py + corner.y, pz + corner.z, u, v, argb);
            }
        }
        Render3DUtil.flush(layer);
    }

    /**
     * White with the colour's alpha.
     *
     * <p>The picture already carries the colour, so the vertex must not multiply it a second time - a
     * blue sprite tinted blue again is nearly black. Alpha still has to come through here, because
     * that is what every fade in the client is built on.
     */
    private static Color alphaOnly(Color color) {
        return new Color(255, 255, 255, color.getAlpha());
    }

    private static void emitGroundSprite(VertexConsumer buffer, Vec3d center, double radius, double angle, Color tint) {
        emitGroundSprite(buffer, center, radius, angle, tint, 0f, 0f, 1f, 1f);
    }

    private static void emitGroundSprite(VertexConsumer buffer, Vec3d center, double radius, double angle, Color tint,
                                         float u0, float v0, float u1, float v1) {
        Vec3d cam = mc.gameRenderer.getCamera().getCameraPos();
        MatrixStack matrices = worldMatrix();
        matrices.translate(center.x - cam.x, center.y - cam.y, center.z - cam.z);
        Matrix4f m = matrices.peek().getPositionMatrix();

        double cos = Math.cos(angle), sin = Math.sin(angle);
        int argb = tint.getRGB();

        // corner order and UVs match: (-,-) (+,-) (+,+) (-,+)
        for (int i = 0; i < 4; i++) {
            double lx = ((i == 0 || i == 3) ? -1 : 1) * radius;
            double lz = (i < 2 ? -1 : 1) * radius;
            float x = (float) (lx * cos - lz * sin);
            float z = (float) (lx * sin + lz * cos);
            Render3DUtil.vertexTex(buffer, m, x, 0f, z, (i == 0 || i == 3) ? u0 : u1, i < 2 ? v0 : v1, argb);
        }
    }

    /**
     * A box that is NOT axis aligned: it stands at an origin, turned to a yaw, optionally tilted about a
     * pivot inside itself.
     *
     * <p>Every other box helper here takes a {@link Box}, and a Box cannot be turned - it is defined by
     * two opposite corners in world axes. That is fine for block outlines, which really are axis aligned,
     * and useless for anything shaped like a limb: a player silhouette built out of Boxes always faces
     * north and its arms can never swing.
     *
     * <p>Built on {@link #worldMatrix()}, the same footing as the world sprites, so it sits still in the
     * world as the camera turns.
     *
     * @param local the box in the model's own coordinates: x right, y up from the feet, z forward
     * @param pivot the point in those coordinates that {@code pitch} rotates about - a shoulder, a hip
     */
    public static void drawOrientedBox3D(@NotNull Vec3d origin, float yaw, float pitch, @NotNull Vec3d pivot,
                                         @NotNull Box local, @NotNull Color fill, @NotNull Color outline,
                                         float lineWidth, boolean throughWalls) {
        Vec3d cam = mc.gameRenderer.getCamera().getCameraPos();

        MatrixStack matrices = worldMatrix();
        matrices.translate(origin.x - cam.x, origin.y - cam.y, origin.z - cam.z);
        matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(-yaw));
        if (pitch != 0f) {
            matrices.translate(pivot.x, pivot.y, pivot.z);
            matrices.multiply(RotationAxis.POSITIVE_X.rotationDegrees(pitch));
            matrices.translate(-pivot.x, -pivot.y, -pivot.z);
        }

        Matrix4f m = matrices.peek().getPositionMatrix();

        float x1 = (float) local.minX, y1 = (float) local.minY, z1 = (float) local.minZ;
        float x2 = (float) local.maxX, y2 = (float) local.maxY, z2 = (float) local.maxZ;

        if (fill.getAlpha() > 0) {
            VertexConsumer buffer = Render3DUtil.buffer(throughWalls ? Render3DUtil.QUADS_NO_DEPTH : Render3DUtil.QUADS);
            int argb = fill.getRGB();

            Render3DUtil.quad(buffer, m, x1, y1, z1, x2, y1, z1, x2, y1, z2, x1, y1, z2, argb);   // bottom
            Render3DUtil.quad(buffer, m, x1, y2, z1, x1, y2, z2, x2, y2, z2, x2, y2, z1, argb);   // top
            Render3DUtil.quad(buffer, m, x1, y1, z1, x1, y2, z1, x2, y2, z1, x2, y1, z1, argb);   // north
            Render3DUtil.quad(buffer, m, x2, y1, z2, x2, y2, z2, x1, y2, z2, x1, y1, z2, argb);   // south
            Render3DUtil.quad(buffer, m, x1, y1, z2, x1, y2, z2, x1, y2, z1, x1, y1, z1, argb);   // west
            Render3DUtil.quad(buffer, m, x2, y1, z1, x2, y2, z1, x2, y2, z2, x2, y1, z2, argb);   // east

            Render3DUtil.flush(throughWalls ? Render3DUtil.QUADS_NO_DEPTH : Render3DUtil.QUADS);
        }

        if (outline.getAlpha() > 0 && lineWidth > 0) {
            // The twelve edges, drawn as geometry rather than through the LINES layer.
            //
            // <p>LINES gives you a hairline whose width is in SCREEN pixels, so an outline drawn with it
            // is the same thickness whether the thing is at your feet or forty blocks away, and it never
            // sits quite on the shape it is supposed to be edging. These are real bars measured in
            // blocks: they stay attached to the box, they thin out with distance the way everything else
            // does, and they take the tint through the same path the faces do.
            RenderLayer layer = throughWalls ? Render3DUtil.QUADS_NO_DEPTH : Render3DUtil.QUADS;
            VertexConsumer buffer = Render3DUtil.buffer(layer);
            int argb = outline.getRGB();
            float t = lineWidth;

            for (int i = 0; i < 4; i++) {
                float ex = (i == 0 || i == 3) ? x1 : x2;
                float ez = (i < 2) ? z1 : z2;
                float nx = (i == 0 || i == 1) ? x2 : x1;
                float nz = (i == 1 || i == 2) ? z2 : z1;

                edgeBar(buffer, m, ex, y1, ez, nx, y1, nz, t, argb);   // bottom ring
                edgeBar(buffer, m, ex, y2, ez, nx, y2, nz, t, argb);   // top ring
                edgeBar(buffer, m, ex, y1, ez, ex, y2, ez, t, argb);   // upright
            }

            Render3DUtil.flush(layer);
        }
    }

    /**
     * One edge of a box, as two thin quads crossed at right angles along it.
     *
     * <p>A cross rather than a full bar: from any direction at least one of the two faces you, so it
     * reads as a solid edge, at a third of the geometry a six-sided bar would need.
     */
    private static void edgeBar(VertexConsumer buffer, Matrix4f m,
                                float ax, float ay, float az, float bx, float by, float bz,
                                float thickness, int argb) {
        float half = thickness / 2f;

        // Two directions perpendicular to the edge. The edges of an axis-aligned box run along exactly
        // one axis, so picking them is a matter of naming the other two.
        float px, py, pz, qx, qy, qz;
        if (ay != by) {
            px = half; py = 0; pz = 0;
            qx = 0; qy = 0; qz = half;
        } else if (ax != bx) {
            px = 0; py = half; pz = 0;
            qx = 0; qy = 0; qz = half;
        } else {
            px = 0; py = half; pz = 0;
            qx = half; qy = 0; qz = 0;
        }

        Render3DUtil.quad(buffer, m,
                ax - px, ay - py, az - pz, bx - px, by - py, bz - pz,
                bx + px, by + py, bz + pz, ax + px, ay + py, az + pz, argb);
        Render3DUtil.quad(buffer, m,
                ax - qx, ay - qy, az - qz, bx - qx, by - qy, bz - qz,
                bx + qx, by + qy, bz + qz, ax + qx, ay + qy, az + qz, argb);
    }

    /**
     * A sky lantern as real geometry: a tapered paper box that you can walk around.
     *
     * <p>Four painted sides rather than one billboard. A billboard turns to face you, which means a
     * lantern seen from two places looks identical from both and the whole swarm pivots together as you
     * move - the single thing that gives a flat sprite away. Four fixed sides have a front and a back,
     * so walking round one actually shows you its other face.
     *
     * <p>Each side carries the same painted picture, so the frame and the flame line up all the way
     * round, and the box tapers to a smaller mouth at the bottom exactly as the picture does.
     *
     * @param bottom the mouth's centre - the lantern is built upwards from it
     * @param width  the width at the shoulders; the mouth is drawn in from this
     */
    public static void drawLantern3D(@NotNull Identifier texture, @NotNull Vec3d bottom, double width,
                                     double height, float yaw, float lean, @NotNull Color tint,
                                     boolean throughWalls) {
        RenderLayer layer = Render3DUtil.spriteLayer(TintedTextures.tinted(texture, tint), throughWalls);
        VertexConsumer buffer = Render3DUtil.buffer(layer);

        Vec3d cam = mc.gameRenderer.getCamera().getCameraPos();
        MatrixStack matrices = worldMatrix();
        matrices.translate(bottom.x - cam.x, bottom.y - cam.y, bottom.z - cam.z);
        matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(-yaw));
        if (lean != 0f) matrices.multiply(RotationAxis.POSITIVE_Z.rotationDegrees(lean));

        Matrix4f m = matrices.peek().getPositionMatrix();
        int argb = alphaOnly(tint).getRGB();

        float top = (float) height;
        float wide = (float) (width / 2);
        // The mouth is drawn in to match the painted taper, so the geometry and the picture agree.
        float narrow = wide * 0.62f;

        for (int side = 0; side < 4; side++) {
            double a0 = Math.PI / 2 * side + Math.PI / 4;
            double a1 = a0 + Math.PI / 2;

            float tx0 = (float) (Math.cos(a0) * wide), tz0 = (float) (Math.sin(a0) * wide);
            float tx1 = (float) (Math.cos(a1) * wide), tz1 = (float) (Math.sin(a1) * wide);
            float bx0 = (float) (Math.cos(a0) * narrow), bz0 = (float) (Math.sin(a0) * narrow);
            float bx1 = (float) (Math.cos(a1) * narrow), bz1 = (float) (Math.sin(a1) * narrow);

            Render3DUtil.vertexTex(buffer, m, tx0, top, tz0, 0f, 0f, argb);
            Render3DUtil.vertexTex(buffer, m, tx1, top, tz1, 1f, 0f, argb);
            Render3DUtil.vertexTex(buffer, m, bx1, 0f, bz1, 1f, 1f, argb);
            Render3DUtil.vertexTex(buffer, m, bx0, 0f, bz0, 0f, 1f, argb);
        }

        Render3DUtil.flush(layer);
    }

    /**
     * A seamless band of texture wrapped all the way round the camera - a sky, in other words.
     *
     * <p>One picture, mapped once around the full circle, so there is no edge anywhere to give it away.
     * The alternative this replaces was a ring of separate upright sprites, and a ring of rectangles
     * reads as exactly that: a ring of rectangles, however carefully each one is painted.
     *
     * <p>The band turns by ROTATING THE GEOMETRY rather than by sliding the texture coordinates. Sliding
     * them would push u past 1 on whichever quad happens to straddle the wrap, and a standalone texture
     * clamps at its edge rather than repeating, so that one quad would smear its last pixel column into
     * a visible stripe. Turning the ring itself is the same motion with nothing to go wrong.
     *
     * @param centre   the band's centre - pass the camera position plus an altitude to hang it in the sky
     * @param height   how tall the band is, in blocks, measured downwards from {@code centre}
     * @param rotation the band's rotation about its axis, in radians
     */
    /**
     * Scale for sky geometry whose farthest point is {@code furthest} blocks from the camera.
     *
     * <p>The projection's far plane follows the render distance - at 2 chunks it is about 128 blocks - and
     * everything past it is clipped. A sky band at 160-200 blocks was therefore simply cut away on low render
     * distances: the sky visuals drew nothing, and worked on a machine with a longer view. A sky is
     * centred on the camera, so scaling it uniformly towards the camera keeps every angle - it looks exactly
     * the same - and it only has to stay past the last terrain chunk so it never covers the world.
     *
     * @return the factor to multiply every distance and height by
     */
    private static boolean skyLogged;

    public static double skyFit(double furthest) {
        if (furthest <= 0) return 1;
        double far = mc.gameRenderer.getFarPlaneDistance();
        // The CLIENT render distance, not only the game renderer's: on a server (or the integrated one) that
        // caps view distance to 2 chunks the renderer reports 32 blocks while chunks the client already has
        // keep drawing much further - and a sky placed at 48 blocks stood in front of all of them.
        double terrain = Math.max(mc.gameRenderer.getViewDistanceBlocks(), mc.options.getClampedViewDistance() * 16.0);
        // Always just past the last terrain chunk and inside the far plane - scaled DOWN when the far plane
        // would clip it, and UP when the terrain reaches further than the sky (long render distance), or the
        // sky stood in front of distant trees and hills and covered them.
        double place = Math.min(far * 0.9, Math.max(terrain * 1.45, 48));
        if (!skyLogged) {
            skyLogged = true;
            thunder.hack.ThunderHack.LOGGER.info("[Cloudan] skyFit far={} terrain={} place={} furthest={}", far, terrain, place, furthest);
        }
        return place / furthest;
    }

    /**
     * A whole sky: a dome from a little below the horizon up to the zenith, one texture wrapped round it.
     *
     * <p>U runs round the horizon (so the texture must tile left to right), V runs from the zenith (0) down
     * to the bottom edge (1). Rows converge at the top, which pinches the picture into a swirl overhead - for
     * clouds and smoke that reads as a vortex, not as a seam. Unlike a band plus a flat cap there is no edge
     * anywhere, which is what made the old sky look like a square hung overhead.
     *
     * @param belowDeg how far under the horizon the dome reaches, so no gap shows over low terrain
     */
    public static void drawSkyDome(@NotNull Identifier texture, @NotNull Vec3d centre, double radius,
                                   @NotNull Color tint, double rotation, int segments, int rings, double belowDeg) {
        drawSkyDome(texture, radius, tint, rotation, segments, rings, belowDeg, true, 0);
    }

    /**
     * @param bakeView true from our 3D pass (the live modelview is identity there, so the camera rotation is
     *                 baked into the vertices); false from inside the vanilla sky pass, where the modelview
     *                 already carries the camera rotation and baking it again would turn the sky twice.
     */
    public static void drawSkyDome(@NotNull Identifier texture, double radius, @NotNull Color tint, double rotation,
                                   int segments, int rings, double belowDeg, boolean bakeView, double yOffset) {
        RenderLayer layer = Render3DUtil.spriteLayer(TintedTextures.tinted(texture, tint), false);
        tint = alphaOnly(tint);
        VertexConsumer buffer = Render3DUtil.buffer(layer);

        MatrixStack matrices = bakeView ? worldMatrix() : new MatrixStack();
        // world-vertical shift, so the sky can stay put while the camera jumps (a sky that stays put while the camera jumps)
        if (yOffset != 0) matrices.translate(0, yOffset, 0);
        Matrix4f m = matrices.peek().getPositionMatrix();
        int argb = tint.getRGB();

        double bottom = -Math.toRadians(belowDeg), top = Math.PI / 2;
        for (int r = 0; r < rings; r++) {
            // elevation of this ring's upper and lower edge
            double e0 = top - (top - bottom) * r / rings;
            double e1 = top - (top - bottom) * (r + 1) / rings;
            float v0 = r / (float) rings, v1 = (r + 1) / (float) rings;
            double c0 = Math.cos(e0) * radius, s0 = Math.sin(e0) * radius;
            double c1 = Math.cos(e1) * radius, s1 = Math.sin(e1) * radius;

            for (int i = 0; i < segments; i++) {
                double a0 = rotation + Math.PI * 2 * i / segments;
                double a1 = rotation + Math.PI * 2 * (i + 1) / segments;
                float u0 = i / (float) segments, u1 = (i + 1) / (float) segments;

                Render3DUtil.vertexTex(buffer, m, (float) (Math.cos(a0) * c0), (float) s0, (float) (Math.sin(a0) * c0), u0, v0, argb);
                Render3DUtil.vertexTex(buffer, m, (float) (Math.cos(a1) * c0), (float) s0, (float) (Math.sin(a1) * c0), u1, v0, argb);
                Render3DUtil.vertexTex(buffer, m, (float) (Math.cos(a1) * c1), (float) s1, (float) (Math.sin(a1) * c1), u1, v1, argb);
                Render3DUtil.vertexTex(buffer, m, (float) (Math.cos(a0) * c1), (float) s1, (float) (Math.sin(a0) * c1), u0, v1, argb);
            }
        }

        Render3DUtil.flush(layer);
    }

    /**
     * A classic skybox: a cube centred on the camera, drawn from inside the vanilla sky pass (the modelview
     * already carries the camera rotation, and terrain draws over it afterwards), so it is infinitely far away -
     * walking, flying or Freecam never move you against it. It turns slowly about the vertical axis.
     *
     * <p>The side picture runs round the four walls (a quarter per wall, so it must tile left to right), top
     * of the picture at the top edge. The ceiling uses {@code top}, or the side picture's top row when there is
     * none; the floor uses the side picture's bottom row, so the horizon colour simply continues downwards.
     *
     * @param rotation turn about the vertical axis, in radians
     */
    public static void drawSkyBox(@NotNull Identifier side, @Nullable Identifier top, @NotNull Color tint, double rotation) {
        float h = 50f;
        MatrixStack matrices = new MatrixStack();
        matrices.multiply(net.minecraft.util.math.RotationAxis.POSITIVE_Y.rotation((float) rotation));
        Matrix4f m = matrices.peek().getPositionMatrix();
        int argb = alphaOnly(tint).getRGB();
        // the four corners of the cube seen from above, in order round the horizon
        float[][] c = {{h, h}, {-h, h}, {-h, -h}, {h, -h}};

        RenderLayer sideLayer = Render3DUtil.spriteLayer(TintedTextures.tinted(side, tint), false);
        VertexConsumer buf = Render3DUtil.buffer(sideLayer);
        for (int i = 0; i < 4; i++) {
            float[] a = c[i], b = c[(i + 1) % 4];
            float u0 = i / 4f, u1 = (i + 1) / 4f;
            Render3DUtil.vertexTex(buf, m, a[0], h, a[1], u0, 0f, argb);
            Render3DUtil.vertexTex(buf, m, b[0], h, b[1], u1, 0f, argb);
            Render3DUtil.vertexTex(buf, m, b[0], -h, b[1], u1, 1f, argb);
            Render3DUtil.vertexTex(buf, m, a[0], -h, a[1], u0, 1f, argb);
        }
        // floor: the side picture's bottom row, stretched
        Render3DUtil.vertexTex(buf, m, c[0][0], -h, c[0][1], 0f, 0.995f, argb);
        Render3DUtil.vertexTex(buf, m, c[1][0], -h, c[1][1], 0.25f, 0.995f, argb);
        Render3DUtil.vertexTex(buf, m, c[2][0], -h, c[2][1], 0.5f, 0.995f, argb);
        Render3DUtil.vertexTex(buf, m, c[3][0], -h, c[3][1], 0.75f, 0.995f, argb);
        if (top == null) {
            // ceiling from the side picture's top row
            Render3DUtil.vertexTex(buf, m, c[0][0], h, c[0][1], 0f, 0.005f, argb);
            Render3DUtil.vertexTex(buf, m, c[1][0], h, c[1][1], 0.25f, 0.005f, argb);
            Render3DUtil.vertexTex(buf, m, c[2][0], h, c[2][1], 0.5f, 0.005f, argb);
            Render3DUtil.vertexTex(buf, m, c[3][0], h, c[3][1], 0.75f, 0.005f, argb);
        }
        Render3DUtil.flush(sideLayer);

        if (top != null) {
            RenderLayer topLayer = Render3DUtil.spriteLayer(TintedTextures.tinted(top, tint), false);
            VertexConsumer tb = Render3DUtil.buffer(topLayer);
            Render3DUtil.vertexTex(tb, m, c[0][0], h, c[0][1], 1f, 1f, argb);
            Render3DUtil.vertexTex(tb, m, c[1][0], h, c[1][1], 0f, 1f, argb);
            Render3DUtil.vertexTex(tb, m, c[2][0], h, c[2][1], 0f, 0f, argb);
            Render3DUtil.vertexTex(tb, m, c[3][0], h, c[3][1], 1f, 0f, argb);
            Render3DUtil.flush(topLayer);
        }
    }

    public static void drawSkyBand(@NotNull Identifier texture, @NotNull Vec3d centre, double radius,
                                   double height, @NotNull Color tint, double rotation, int segments,
                                   boolean throughWalls) {
        RenderLayer layer = Render3DUtil.spriteLayer(TintedTextures.tinted(texture, tint), throughWalls);
        tint = alphaOnly(tint);
        VertexConsumer buffer = Render3DUtil.buffer(layer);

        Vec3d cam = mc.gameRenderer.getCamera().getCameraPos();
        MatrixStack matrices = worldMatrix();
        matrices.translate(centre.x - cam.x, centre.y - cam.y, centre.z - cam.z);
        Matrix4f m = matrices.peek().getPositionMatrix();

        int argb = tint.getRGB();
        float top = 0f;
        float bottom = (float) -height;

        for (int i = 0; i < segments; i++) {
            double a0 = rotation + Math.PI * 2 * i / segments;
            double a1 = rotation + Math.PI * 2 * (i + 1) / segments;

            float x0 = (float) (Math.cos(a0) * radius), z0 = (float) (Math.sin(a0) * radius);
            float x1 = (float) (Math.cos(a1) * radius), z1 = (float) (Math.sin(a1) * radius);
            float u0 = i / (float) segments;
            float u1 = (i + 1) / (float) segments;

            Render3DUtil.vertexTex(buffer, m, x0, top, z0, u0, 0f, argb);
            Render3DUtil.vertexTex(buffer, m, x1, top, z1, u1, 0f, argb);
            Render3DUtil.vertexTex(buffer, m, x1, bottom, z1, u1, 1f, argb);
            Render3DUtil.vertexTex(buffer, m, x0, bottom, z0, u0, 1f, argb);
        }

        Render3DUtil.flush(layer);
    }

    /**
     * A textured quad standing in the world at an arbitrary orientation - not lying on the ground and not
     * turned to face the camera.
     *
     * <p>This is what a wing needs: it is attached to a player's back, so it has to keep its angle as you
     * walk around them rather than swivelling to stay flat-on. The quad is built in a local frame (right
     * along the span, up across the chord) and that frame is rotated by yaw, then pitch, then roll.
     *
     * @param mirror flips the texture along its span - one painted wing serves both sides
     */
    public static void drawOrientedSprite3D(@NotNull Identifier texture, @NotNull Vec3d origin,
                                            float yawDegrees, float pitchDegrees, float rollDegrees,
                                            double spanStart, double span, double chord,
                                            @NotNull Color tint, boolean mirror, boolean throughWalls) {
        RenderLayer layer = Render3DUtil.spriteLayer(TintedTextures.tinted(texture, tint), throughWalls);
        tint = alphaOnly(tint);
        VertexConsumer buffer = Render3DUtil.buffer(layer);

        Vec3d cam = mc.gameRenderer.getCamera().getCameraPos();
        MatrixStack matrices = worldMatrix();
        matrices.translate(origin.x - cam.x, origin.y - cam.y, origin.z - cam.z);
        matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(yawDegrees));
        matrices.multiply(RotationAxis.POSITIVE_X.rotationDegrees(pitchDegrees));
        matrices.multiply(RotationAxis.POSITIVE_Z.rotationDegrees(rollDegrees));

        Matrix4f m = matrices.peek().getPositionMatrix();
        int argb = tint.getRGB();

        float x0 = (float) spanStart;
        float x1 = (float) (spanStart + span);
        float y0 = 0f;
        float y1 = (float) -chord;

        float u0 = mirror ? 1f : 0f;
        float u1 = mirror ? 0f : 1f;

        Render3DUtil.vertexTex(buffer, m, x0, y0, 0f, u0, 0f, argb);
        Render3DUtil.vertexTex(buffer, m, x1, y0, 0f, u1, 0f, argb);
        Render3DUtil.vertexTex(buffer, m, x1, y1, 0f, u1, 1f, argb);
        Render3DUtil.vertexTex(buffer, m, x0, y1, 0f, u0, 1f, argb);

        Render3DUtil.flush(layer);
    }

    /**
     * Fills the faces of a block box with a tiling, scrolling texture (e.g. animated plasma inside a highlighted
     * block). UVs are offset by (uOff, vOff) each frame so the texture appears to flow. White+alpha textures are
     * tinted by {@code tint}.
     */
    public static void drawTexturedBox3D(@NotNull Identifier texture, Box box, float uOff, float vOff,
                                         float tile, @NotNull Color tint, boolean throughWalls) {
        RenderLayer layer = Render3DUtil.spriteLayer(TintedTextures.tinted(texture, tint), throughWalls);
        int argb = alphaOnly(tint).getRGB();
        VertexConsumer buffer = Render3DUtil.buffer(layer);

        Vec3d cam = mc.gameRenderer.getCamera().getCameraPos();
        MatrixStack matrices = worldMatrix();
        matrices.push();
        matrices.translate(-cam.x, -cam.y, -cam.z);
        Matrix4f m = matrices.peek().getPositionMatrix();

        float x0 = (float) box.minX, y0 = (float) box.minY, z0 = (float) box.minZ;
        float x1 = (float) box.maxX, y1 = (float) box.maxY, z1 = (float) box.maxZ;
        float a = uOff, b = vOff, t = tile;

        // top
        quadTex(buffer, m, x0, y1, z0, x1, y1, z0, x1, y1, z1, x0, y1, z1, a, b, t, argb);
        // bottom
        quadTex(buffer, m, x0, y0, z1, x1, y0, z1, x1, y0, z0, x0, y0, z0, a, b, t, argb);
        // north (z0)
        quadTex(buffer, m, x1, y1, z0, x0, y1, z0, x0, y0, z0, x1, y0, z0, a, b, t, argb);
        // south (z1)
        quadTex(buffer, m, x0, y1, z1, x1, y1, z1, x1, y0, z1, x0, y0, z1, a, b, t, argb);
        // west (x0)
        quadTex(buffer, m, x0, y1, z0, x0, y1, z1, x0, y0, z1, x0, y0, z0, a, b, t, argb);
        // east (x1)
        quadTex(buffer, m, x1, y1, z1, x1, y1, z0, x1, y0, z0, x1, y0, z1, a, b, t, argb);

        Render3DUtil.flush(layer);
        matrices.pop();
    }

    private static void quadTex(VertexConsumer buf, Matrix4f m, float ax, float ay, float az,
                                float bx, float by, float bz, float cx, float cy, float cz,
                                float dx, float dy, float dz, float uOff, float vOff, float tile, int argb) {
        Render3DUtil.vertexTex(buf, m, ax, ay, az, uOff, vOff, argb);
        Render3DUtil.vertexTex(buf, m, bx, by, bz, uOff + tile, vOff, argb);
        Render3DUtil.vertexTex(buf, m, cx, cy, cz, uOff + tile, vOff + tile, argb);
        Render3DUtil.vertexTex(buf, m, dx, dy, dz, uOff, vOff + tile, argb);
    }

    /**
     * A trail drawn from an IMAGE: the path is turned into a strip of textured quads and the picture on
     * the texture supplies the soft edges, the core and the falloff.
     *
     * <p>The hand-coded version this replaces built the glow out of stacked gradient quads, and the maths
     * that had to look like light never quite did - edges banded, the middle washed out to white and the
     * colour was lost. A texture just looks like whatever was painted into it.
     *
     * @param upright true = an upright curtain rising from the path; false = a ribbon turned to face the camera
     * @param width   the ribbon's width (or the curtain's height) in blocks
     */
    /**
     * A trail that IS a picture: the whole image is stretched once from the tail (U=0) to the head (U=1) and
     * across the ribbon's width, untinted, so what you see is exactly the image - its own colours, its own
     * shape. The ribbon still turns to face the camera. Only the alpha of {@code alphas} is used, for the fade.
     */
    public static void drawImageTrail3D(@NotNull Identifier texture, @NotNull List<Vec3d> points,
                                        @NotNull List<Color> alphas, double width, boolean throughWalls) {
        drawImageTrail3D(texture, points, alphas, width, throughWalls, null);
    }

    /**
     * @param tint colour for a white picture (null keeps the picture's own colours)
     */
    public static void drawImageTrail3D(@NotNull Identifier texture, @NotNull List<Vec3d> points,
                                        @NotNull List<Color> alphas, double width, boolean throughWalls, @Nullable Color tint) {
        int n = points.size();
        if (n < 2 || alphas.size() < n) return;

        double total = 0;
        for (int i = 0; i < n - 1; i++) total += points.get(i).distanceTo(points.get(i + 1));
        if (total <= 1e-4) return;

        RenderLayer layer = Render3DUtil.spriteLayer(tint == null ? texture : TintedTextures.tinted(texture, tint), throughWalls);
        VertexConsumer buffer = Render3DUtil.buffer(layer);

        Vec3d cam = mc.gameRenderer.getCamera().getCameraPos();
        MatrixStack matrices = worldMatrix();
        matrices.translate(-cam.x, -cam.y, -cam.z);
        Matrix4f m = matrices.peek().getPositionMatrix();

        // A cross-section TUBE, not a flat ribbon. Two bands run along the path at right angles to each other
        // (a "+" seen end-on), so from any direction at least one band faces you - the trail never thins to a
        // line and never leaves a hole on a turn. The two edge frames are carried along the path by parallel
        // transport (each point's frame is the previous one rotated onto the new tangent), so there is no flip
        // and no twist where the path bends - which is what made turns overlap and tear before.
        double half = width / 2.0;
        Vec3d[] nrm = new Vec3d[n], bnm = new Vec3d[n];
        Vec3d prevN = null, prevT = null;
        for (int i = 0; i < n; i++) {
            Vec3d t = points.get(Math.min(n - 1, i + 1)).subtract(points.get(Math.max(0, i - 1)));
            if (t.lengthSquared() < 1e-10) t = prevT != null ? prevT : new Vec3d(1, 0, 0);
            t = t.normalize();
            Vec3d nv;
            if (prevN == null) {
                nv = t.crossProduct(new Vec3d(0, 1, 0));
                if (nv.lengthSquared() < 1e-8) nv = t.crossProduct(new Vec3d(1, 0, 0));
            } else {
                // carry the previous normal across the bend, then re-orthogonalise against the new tangent
                nv = prevN.subtract(t.multiply(prevN.dotProduct(t)));
                if (nv.lengthSquared() < 1e-8) nv = t.crossProduct(new Vec3d(0, 1, 0));
            }
            nv = nv.normalize();
            prevN = nv;
            prevT = t;
            nrm[i] = nv.multiply(half);
            bnm[i] = t.crossProduct(nv).normalize().multiply(half);
        }

        double run = 0;
        for (int i = 0; i < n - 1; i++) {
            Vec3d a = points.get(i), b = points.get(i + 1);
            double segment = a.distanceTo(b);
            if (segment <= 1e-5) continue;
            float u0 = (float) (run / total), u1 = (float) ((run + segment) / total);
            run += segment;
            int ca = alphaOnly(alphas.get(i)).getRGB(), cb = alphaOnly(alphas.get(i + 1)).getRGB();

            band(buffer, m, a, b, nrm[i], nrm[i + 1], u0, u1, ca, cb);
            band(buffer, m, a, b, bnm[i], bnm[i + 1], u0, u1, ca, cb);
        }

        Render3DUtil.flush(layer);
    }

    private static void band(VertexConsumer buffer, Matrix4f m, Vec3d a, Vec3d b, Vec3d oa, Vec3d ob,
                             float u0, float u1, int ca, int cb) {
        Vec3d a0 = a.subtract(oa), a1 = a.add(oa), b0 = b.subtract(ob), b1 = b.add(ob);
        Render3DUtil.vertexTex(buffer, m, (float) a0.x, (float) a0.y, (float) a0.z, u0, 1f, ca);
        Render3DUtil.vertexTex(buffer, m, (float) b0.x, (float) b0.y, (float) b0.z, u1, 1f, cb);
        Render3DUtil.vertexTex(buffer, m, (float) b1.x, (float) b1.y, (float) b1.z, u1, 0f, cb);
        Render3DUtil.vertexTex(buffer, m, (float) a1.x, (float) a1.y, (float) a1.z, u0, 0f, ca);
    }

    /**
     * The sideways half-width offset at every POINT of a camera-facing ribbon.
     *
     * <p>Computed per point from the direction averaged over both neighbouring segments, so consecutive
     * segments share their edge exactly - per-segment offsets left wedge gaps and overlaps at every bend.
     * And the sign is kept continuous: on a turn the cross product with the view can swap sides, and an
     * offset that flips mid-ribbon twists it into a bow-tie and folds the picture - the "turning looks
     * terrible" problem. Flipping it back whenever it points against the previous one prevents that.
     */
    static Vec3d[] ribbonOffsets(List<Vec3d> points, Vec3d cam, double half) {
        int n = points.size();
        Vec3d[] out = new Vec3d[n];
        Vec3d previous = null;
        for (int i = 0; i < n; i++) {
            Vec3d dir = points.get(Math.min(n - 1, i + 1)).subtract(points.get(Math.max(0, i - 1)));
            if (dir.lengthSquared() < 1e-10) dir = previous != null ? previous.crossProduct(new Vec3d(0, 1, 0)) : new Vec3d(1, 0, 0);
            Vec3d side = dir.normalize().crossProduct(cam.subtract(points.get(i)).normalize());
            if (side.lengthSquared() < 1e-8) side = previous != null ? previous : new Vec3d(0, 1, 0);
            side = side.normalize();
            if (previous != null && side.dotProduct(previous) < 0) side = side.multiply(-1);
            previous = side;
            out[i] = side.multiply(half);
        }
        return out;
    }

    /**
     * Many short glowing beams in one draw call - wireframe edges, rays, anything made of segments.
     *
     * <p>Each segment is a quad turned to face the camera (so it never goes edge-on and vanishes), V runs
     * across it so a soft beam texture gives it a bright core and a glow. One layer and one flush for the
     * whole list; the layer takes the first colour's tint, the rest only carry alpha.
     *
     * @param segments pairs of points: [a0, b0, a1, b1, ...]
     */
    public static void drawBeamSegments3D(@NotNull Identifier texture, @NotNull List<Vec3d> segments,
                                          @NotNull List<Color> colors, double width, boolean throughWalls) {
        if (segments.size() < 2 || colors.isEmpty()) return;

        RenderLayer layer = Render3DUtil.spriteLayer(TintedTextures.tinted(texture, colors.getFirst()), throughWalls);
        VertexConsumer buffer = Render3DUtil.buffer(layer);
        Vec3d cam = mc.gameRenderer.getCamera().getCameraPos();
        MatrixStack matrices = worldMatrix();
        matrices.translate(-cam.x, -cam.y, -cam.z);
        Matrix4f m = matrices.peek().getPositionMatrix();
        double half = width / 2.0;

        for (int i = 0; i + 1 < segments.size(); i += 2) {
            Vec3d a = segments.get(i), b = segments.get(i + 1);
            Vec3d dir = b.subtract(a);
            if (dir.lengthSquared() < 1e-10) continue;
            Vec3d side = dir.crossProduct(cam.subtract(a.add(b).multiply(0.5)));
            if (side.lengthSquared() < 1e-12) continue;
            side = side.normalize().multiply(half);
            int c = alphaOnly(colors.get(Math.min(colors.size() - 1, i / 2))).getRGB();

            Render3DUtil.vertexTex(buffer, m, (float) (a.x - side.x), (float) (a.y - side.y), (float) (a.z - side.z), 0f, 1f, c);
            Render3DUtil.vertexTex(buffer, m, (float) (b.x - side.x), (float) (b.y - side.y), (float) (b.z - side.z), 1f, 1f, c);
            Render3DUtil.vertexTex(buffer, m, (float) (b.x + side.x), (float) (b.y + side.y), (float) (b.z + side.z), 1f, 0f, c);
            Render3DUtil.vertexTex(buffer, m, (float) (a.x + side.x), (float) (a.y + side.y), (float) (a.z + side.z), 0f, 0f, c);
        }
        Render3DUtil.flush(layer);
    }

    /**
     * Segments as solid square bars - four faces each, every face a slightly different brightness so the
     * edges of the bar read. A camera-facing ribbon is flat and soft; this is the hard, angular look of a
     * wireframe made of real rods. Drawn from the solid middle row of the line texture, one draw call.
     *
     * @param segments pairs of points: [a0, b0, a1, b1, ...]
     */
    public static void drawPrismSegments3D(@NotNull List<Vec3d> segments, @NotNull List<Color> colors,
                                           double thickness, boolean throughWalls) {
        if (segments.size() < 2 || colors.isEmpty()) return;

        RenderLayer layer = Render3DUtil.spriteLayer(TintedTextures.tinted(TextureStorage.fxLine, colors.getFirst()), throughWalls);
        VertexConsumer buffer = Render3DUtil.buffer(layer);
        Vec3d cam = mc.gameRenderer.getCamera().getCameraPos();
        MatrixStack matrices = worldMatrix();
        matrices.translate(-cam.x, -cam.y, -cam.z);
        Matrix4f m = matrices.peek().getPositionMatrix();
        double h = thickness / 2.0;
        // face brightness, as alpha on top of the tint: the light catches two faces, two sit in shadow
        float[] shade = {1f, 0.72f, 0.88f, 0.6f};

        for (int i = 0; i + 1 < segments.size(); i += 2) {
            Vec3d a = segments.get(i), b = segments.get(i + 1);
            Vec3d dir = b.subtract(a);
            if (dir.lengthSquared() < 1e-10) continue;
            dir = dir.normalize();
            Vec3d p = Math.abs(dir.y) < 0.9 ? dir.crossProduct(new Vec3d(0, 1, 0)) : dir.crossProduct(new Vec3d(1, 0, 0));
            p = p.normalize().multiply(h);
            Vec3d q = dir.crossProduct(p).normalize().multiply(h);
            Color base = colors.get(Math.min(colors.size() - 1, i / 2));

            // the bar's four long corners, going round
            Vec3d[] o = {p.add(q), p.subtract(q), p.multiply(-1).subtract(q), p.multiply(-1).add(q)};
            for (int f = 0; f < 4; f++) {
                Vec3d c0 = o[f], c1 = o[(f + 1) % 4];
                int argb = new Color(255, 255, 255, Math.round(base.getAlpha() * shade[f])).getRGB();
                Render3DUtil.vertexTex(buffer, m, (float) (a.x + c0.x), (float) (a.y + c0.y), (float) (a.z + c0.z), 0f, 0.5f, argb);
                Render3DUtil.vertexTex(buffer, m, (float) (b.x + c0.x), (float) (b.y + c0.y), (float) (b.z + c0.z), 1f, 0.5f, argb);
                Render3DUtil.vertexTex(buffer, m, (float) (b.x + c1.x), (float) (b.y + c1.y), (float) (b.z + c1.z), 1f, 0.5f, argb);
                Render3DUtil.vertexTex(buffer, m, (float) (a.x + c1.x), (float) (a.y + c1.y), (float) (a.z + c1.z), 0f, 0.5f, argb);
            }
        }
        Render3DUtil.flush(layer);
    }

    public static void drawTexturedTrail3D(@NotNull Identifier texture, @NotNull List<Vec3d> points,
                                           @NotNull List<Color> colors, double width, boolean upright,
                                           boolean throughWalls) {
        int n = points.size();
        if (n < 2 || colors.size() < n) return;

        // A trail is one strip on one layer, so it gets one tint: the head's. The per-point colours then
        // carry only their alpha, which is where a trail's fade actually lives anyway.
        RenderLayer layer = Render3DUtil.spriteLayer(TintedTextures.tinted(texture, colors.getFirst()), throughWalls);
        VertexConsumer buffer = Render3DUtil.buffer(layer);

        Vec3d cam = mc.gameRenderer.getCamera().getCameraPos();
        MatrixStack matrices = worldMatrix();
        matrices.translate(-cam.x, -cam.y, -cam.z);
        Matrix4f m = matrices.peek().getPositionMatrix();

        // U runs along the trail so the texture is not stretched over a long path; V runs across it, which
        // is the axis the image actually paints.
        float u = 0f;
        Vec3d[] sides = upright ? null : ribbonOffsets(points, cam, width / 2.0);

        for (int i = 0; i < n - 1; i++) {
            Vec3d a = points.get(i);
            Vec3d b = points.get(i + 1);
            int ca = alphaOnly(colors.get(i)).getRGB();
            int cb = alphaOnly(colors.get(i + 1)).getRGB();

            float segment = (float) a.distanceTo(b);
            if (segment <= 1e-5f) continue;
            float u1 = u + segment;

            Vec3d offsetA, offsetB;
            if (upright) {
                offsetA = new Vec3d(0, width, 0);
                offsetB = offsetA;
            } else {
                // Per-point, continuous offsets (see ribbonOffsets): no wedges at bends, no flip on turns.
                offsetA = sides[i];
                offsetB = sides[i + 1];
            }

            Vec3d a0 = upright ? a : a.subtract(offsetA);
            Vec3d a1 = a.add(offsetA);
            Vec3d b0 = upright ? b : b.subtract(offsetB);
            Vec3d b1 = b.add(offsetB);

            Render3DUtil.vertexTex(buffer, m, (float) a0.x, (float) a0.y, (float) a0.z, u, 1f, ca);
            Render3DUtil.vertexTex(buffer, m, (float) b0.x, (float) b0.y, (float) b0.z, u1, 1f, cb);
            Render3DUtil.vertexTex(buffer, m, (float) b1.x, (float) b1.y, (float) b1.z, u1, 0f, cb);
            Render3DUtil.vertexTex(buffer, m, (float) a1.x, (float) a1.y, (float) a1.z, u, 0f, ca);

            u = u1;
        }

        Render3DUtil.flush(layer);
    }

    /**
     * The Cloudan target marker: a stack of painted rings lying flat under the target, plus a soft halo.
     *
     * <p>Everything you can see here comes out of a texture - the ring's edge falloff, the halo's
     * gradient, the glow. None of it is drawn with maths that tries to look like light, which is why it
     * reads as a designed effect rather than a wireframe. Two rings turning against each other at
     * different speeds give it motion without a single particle.
     *
     * <p>The position is interpolated off the entity's last/current position, so the marker sits under a
     * moving player instead of stepping once per tick.
     */
    public static void drawCloudanTargetEsp(@NotNull Entity target, @NotNull Color accent, float scale, boolean throughWalls) {
        Vec3d pos = CloudanRenderCore.interpolated(target).add(0, 0.02, 0);

        // Below Low the marker is not worth the draw; past 128 blocks there is no target worth marking.
        CloudanCullingSystem.Lod lod = CloudanCullingSystem.evaluate(pos, 2.0, CloudanCullingSystem.LOW_DISTANCE);
        if (!lod.drawable()) return;

        // A slow breath rather than a strobe: the ring should say "locked", not flash for attention.
        float pulse = CloudanAnimationEngine.pulse(2.8f, 0.06f);
        double base = Math.max(0.6, target.getWidth() * 0.9) * scale * pulse;
        float intensity = lod.scale();

        // Two rings turning against each other, over a halo. The motion comes from the counter-rotation,
        // not from anything flashing - which is what keeps it readable while you are fighting.
        CloudanGlowRenderer.halo(pos, base * 1.35, accent, 0.55f * intensity, throughWalls);
        CloudanGlowRenderer.ring(pos, base, CloudanAnimationEngine.spin(7f), accent, intensity, throughWalls);
        CloudanGlowRenderer.ring(pos, base * 0.62, -CloudanAnimationEngine.spin(4.2f), accent, 0.75f * intensity, throughWalls);

        // An outline round the target itself, through the screen-projected path.
        //
        // <p>This is here because the rings alone are a ground marker, and a ground marker tells you
        // where the target's FEET are, not where the target is. It also means the accent colour appears
        // on solid geometry rather than only through a tinted sprite, so the marker is unmistakably the
        // colour you picked.
        CloudanBatchRenderer.box(target.getBoundingBox().expand(0.06),
                Render2DEngine.injectAlpha(accent, Math.round(40 * intensity)),
                Render2DEngine.injectAlpha(accent, Math.round(210 * intensity)),
                1.6f, true, true);
    }

    /**
     * GombalV3: the target sealed in a glass case, with shards hanging around it.
     *
     * <p>Three parts, each doing something the others cannot. The case is a translucent box on the
     * target's own hitbox with bright edges, so the marker says where the target IS rather than where
     * its feet are - and it is drawn through the screen-projected path, which is the one that reliably
     * carries a tint, so the accent colour is unmistakable. The shards orbit at their own heights and
     * speeds, which is the part that reads as alive. The disc at the feet grounds the whole thing.
     *
     * @param scale multiplies the case and the orbit, for a marker that suits the target's size
     */
    public static void drawEntityBillboard(@NotNull Entity target, @NotNull Identifier tex, @NotNull Color tint,
                                           float scale, boolean throughWalls) {
        Vec3d base = CloudanRenderCore.interpolated(target);
        double h = target.getHeight() * 1.9 * scale; // crystals in the picture extend past the body
        Vec3d top = base.add(0, target.getHeight() * 0.5 + h * 0.5, 0);
        float cameraYaw = CloudanRenderCore.cameraYaw();
        drawOrientedSprite3D(tex, top, -cameraYaw, 0f, 0f, -h / 2, h, h, tint, false, throughWalls);
    }

    public static void drawGombalV3TargetEsp(@NotNull Entity target, @NotNull Color accent, float scale,
                                             int shardCount, boolean throughWalls) {
        drawGombalV3TargetEsp(target, accent, scale, shardCount, throughWalls, 0f);
    }

    /**
     *  lift how far up the orbit and the disc ride, as a fraction of the target-s height. The
     *             marker used to hang around the legs, where the body itself hides half of it.
     */
    public static void drawGombalV3TargetEsp(@NotNull Entity target, @NotNull Color accent, float scale,
                                             int shardCount, boolean throughWalls, float lift) {
        drawGombalV3TargetEsp(target, accent, scale, shardCount, throughWalls, lift, 0f);
    }

    /**
     * @param exit 0 while the target is held; running up to 1 after it leaves range - the shards fly outwards
     *             and up off a widening orbit and fade, the case and the disc dissolve. A smooth goodbye instead
     *             of the marker blinking out the moment the target steps past the aura's reach.
     */
    public static void drawGombalV3TargetEsp(@NotNull Entity target, @NotNull Color accent, float scale,
                                             int shardCount, boolean throughWalls, float lift, float exit) {
        Vec3d pos = CloudanRenderCore.interpolated(target);
        float stay = 1f - exit;
        double flyOut = 1 - Math.pow(1 - exit, 3);

        CloudanCullingSystem.Lod lod = CloudanCullingSystem.evaluate(pos, 2.0, CloudanCullingSystem.LOW_DISTANCE);
        if (!lod.drawable()) return;

        float intensity = lod.scale();

        // The case. Expanded a touch so it never z-fights with the skin it is wrapped round.
        Box box = target.getBoundingBox().expand(0.08 * scale);
        if (stay > 0.02f)
            CloudanBatchRenderer.box(box,
                    Render2DEngine.injectAlpha(accent, Math.round(46 * intensity * stay)),
                    Render2DEngine.injectAlpha(accent, Math.round(220 * intensity * stay)),
                    1.8f, true, true);

        // The disc. It sits at the feet by default and rides up with the orbit when lifted, so the two
        // never come apart into a ring at the ankles and a swarm round the head.
        double raise = target.getHeight() * lift;
        CloudanGlowRenderer.halo(pos.add(0, 0.02 + raise, 0), Math.max(0.5, target.getWidth()) * 0.9 * scale,
                accent, 0.55f * intensity * stay, throughWalls);

        // The shards. Each on its own ring, at its own height and speed, turning on its own face -
        // shared values would make them read as one rigid object spinning rather than as a swarm.
        int shards = Math.max(1, Math.round(shardCount * Math.min(1f, CloudanRenderCore.qualityScale())));
        double orbit = Math.max(0.55, target.getWidth()) * 1.05 * scale * (1 + 2.6 * flyOut);
        float cameraYaw = CloudanRenderCore.cameraYaw();

        for (int i = 0; i < shards; i++) {
            double phase = Math.PI * 2 * i / shards;
            double lap = CloudanAnimationEngine.spin(9f + (i % 3) * 2.5f) * (i % 2 == 0 ? 1 : -1);
            double angle = phase + lap;

            double height = raise + target.getHeight() * (0.20 + 0.62 * ((i * 0.37) % 1.0))
                    + Math.sin(CloudanAnimationEngine.spin(4.1f) + phase) * 0.12;
            height += flyOut * (0.6 + 0.8 * ((i * 0.53) % 1.0));

            Vec3d at = pos.add(Math.cos(angle) * orbit, height, Math.sin(angle) * orbit);
            if (!CloudanCullingSystem.isVisible(at, 0.4)) continue;

            double size = 0.22 * scale * (0.75 + 0.45 * ((i * 0.61) % 1.0)) * (1 - 0.45 * exit);
            float roll = (float) Math.toDegrees(CloudanAnimationEngine.spin(5.5f) + phase);

            drawOrientedSprite3D(TextureStorage.fxShard, at,
                    -cameraYaw, 0f, roll,
                    -size / 2, size, -size * 1.4,
                    Render2DEngine.injectAlpha(accent, Math.round(235 * intensity * (float) Math.pow(stay, 1.5))), false, throughWalls);

            // A four-point sparkle glint pulsing on each crystal - the twinkle from the reference.
            float glint = 0.5f + 0.5f * (float) Math.sin(CloudanAnimationEngine.spin(2.2f) + phase * 1.7);
            double gs = size * (1.1 + 0.9 * glint);
            Color glintCol = new Color(
                    Math.min(255, (accent.getRed() + 255) / 2),
                    Math.min(255, (accent.getGreen() + 255) / 2),
                    Math.min(255, (accent.getBlue() + 255) / 2),
                    Math.round(200 * intensity * glint * (float) Math.pow(stay, 1.5)));
            drawOrientedSprite3D(TextureStorage.fxStar, at,
                    -cameraYaw, 0f, roll * 0.5f,
                    -gs / 2, gs, -gs * 0.5, glintCol, false, throughWalls);
        }
    }

    public static void updateTargetESP() {
        prevCircleStep = circleStep;
        circleStep += 0.15f;
    }

    public static double absSinAnimation(double input) {
        return Math.abs(1 + Math.sin(input)) / 2;
    }

    public static Vec3d interpolatePos(float prevposX, float prevposY, float prevposZ, float posX, float posY, float posZ) {
        double x = prevposX + ((posX - prevposX) * getTickDelta()) - mc.getEntityRenderDispatcher().camera.getCameraPos().getX();
        double y = prevposY + ((posY - prevposY) * getTickDelta()) - mc.getEntityRenderDispatcher().camera.getCameraPos().getY();
        double z = prevposZ + ((posZ - prevposZ) * getTickDelta()) - mc.getEntityRenderDispatcher().camera.getCameraPos().getZ();
        return new Vec3d(x, y, z);
    }

    // Same interpolation but in ABSOLUTE world space (no camera subtraction). Feed this to
    // worldSpaceToScreenSpace / distanceTo, which subtract the camera themselves — otherwise the
    // camera offset gets applied twice.
    public static Vec3d interpolatePosAbsolute(float prevposX, float prevposY, float prevposZ, float posX, float posY, float posZ) {
        double x = prevposX + ((posX - prevposX) * getTickDelta());
        double y = prevposY + ((posY - prevposY) * getTickDelta());
        double z = prevposZ + ((posZ - prevposZ) * getTickDelta());
        return new Vec3d(x, y, z);
    }

    public static void drawLineDebug(Vec3d start, Vec3d end, Color color) {
        DEBUG_LINE_QUEUE.add(new DebugLineAction(start, end, color));
    }

    public static float getTickDelta() {
        return mc.getRenderTickCounter().getTickProgress(true);
    }

    public record FillAction(Box box, Color color) {
    }

    public record OutlineAction(Box box, Color color, float lineWidth) {
    }

    public record FadeAction(Box box, Color color, Color color2) {
    }

    public record FillSideAction(Box box, Color color, Direction side) {
    }

    public record OutlineSideAction(Box box, Color color, float lineWidth, Direction side) {
    }

    public record DebugLineAction(Vec3d start, Vec3d end, Color color) {
    }

    public record LineAction(Vec3d start, Vec3d end, Color color, float width) {
    }
}