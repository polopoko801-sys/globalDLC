package thunder.hack.utility.render;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;

import java.awt.*;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The shared "block preview" renderer (AutoTrap, Surround, SelfTrap, HoleFill, Scaffold, AutoCrystal...).
 *
 * <p>1.21.11: it used to push its boxes through the world-space 3D queue, which double-rotates on this
 * version - the previews slid away from their block as soon as you walked or turned. Everything here is
 * now drawn from the 2D pass as a real projected cube ({@link Render3DEngine#drawProjectedBox}), the same
 * path the working ESP uses, so a preview stays welded to its grid cell.
 */
public final class BlockAnimationUtility {
    private static final Map<BlockRenderData, Long> blocks = new ConcurrentHashMap<>();

    private static final long DURATION = 300L;

    public static void onRender2D(DrawContext context) {
        blocks.forEach((animation, time) -> {
            long passed = System.currentTimeMillis() - time;
            if (passed > DURATION)
                blocks.remove(animation);
            else
                animation.renderWithTime(passed, context);
        });
    }

    public static void renderBlock(BlockPos pos, Color lineColor, int lineWidth, Color fillColor, BlockAnimationMode animationMode, BlockRenderMode renderMode) {
        if (renderMode == BlockRenderMode.None) return;

        blocks.put(new BlockRenderData(pos, lineColor, lineWidth, fillColor, animationMode, renderMode), System.currentTimeMillis());
    }

    public static boolean isRendering(BlockPos pos) {
        return blocks.keySet().stream().anyMatch(blockRenderData -> blockRenderData.pos().equals(pos));
    }

    private static Color fade(Color c, float factor) {
        int a = (int) (c.getAlpha() * factor);
        return Render2DEngine.injectAlpha(c, Math.max(0, Math.min(255, a)));
    }

    private record BlockRenderData(BlockPos pos, Color lineColor, int lineWidth, Color fillColor,
                                   BlockAnimationMode animationMode, BlockRenderMode renderMode) {

        /** The animated box: a cube of side {@code scale} centred on the block. */
        private Box scaled(float scale) {
            Box box = new Box(pos.getX(), pos.getY(), pos.getZ(), pos.getX(), pos.getY(), pos.getZ());
            return box.shrink(scale, scale, scale).offset(0.5 + scale * 0.5, 0.5 + scale * 0.5, 0.5 + scale * 0.5);
        }

        void renderWithTime(long time, DrawContext context) {
            boolean doLine = renderMode == BlockRenderMode.All || renderMode == BlockRenderMode.Line;
            boolean doFill = renderMode == BlockRenderMode.All || renderMode == BlockRenderMode.Fill;
            if (!doLine && !doFill) return;

            float t = time / (float) DURATION;
            Box box;
            Color line = lineColor;
            Color fill = fillColor;

            switch (animationMode) {
                case Decrease -> {
                    box = scaled(1f - t);
                    fill = fade(fillColor, 1f - t);
                }
                case Fill -> {
                    box = scaled(t);
                    fill = fade(fillColor, t);
                }
                case Flash -> {
                    float scale = time > 100 ? 1f - (time - 100f) / 400f : time / 100f;
                    box = scaled(scale);
                    fill = fade(fillColor, scale);
                }
                case Grow -> {
                    box = new Box(pos.getX(), pos.getY() + t, pos.getZ(), pos.getX() + 1, pos.getY(), pos.getZ() + 1);
                    fill = fade(fillColor, t);
                }
                case TNT -> {
                    float scale = time < 200 ? 1f : 1f + (time - 200f) / 400f;
                    box = scaled(scale);
                    fill = fade(fillColor, scale);
                }
                case Pull -> {
                    float scale = time < 200 ? 1.5f - (time / 200f) * 0.5f : 1f;
                    box = scaled(scale);
                    fill = fade(fillColor, scale);
                }
                case Hover -> {
                    box = scaled(1f + time / 1500f);
                    line = fade(lineColor, 1f - t);
                    fill = fade(fillColor, 1f - t);
                }
                case Fade -> {
                    box = new Box(pos);
                    line = fade(lineColor, 1f - t);
                    fill = fade(fillColor, 1f - t);
                }
                default -> box = new Box(pos); // Static
            }

            Render3DEngine.drawProjectedBox(context, box, fill, fill, line, lineWidth, doFill, doLine);
        }
    }

    public enum BlockRenderMode {
        Fill, Line, All, None
    }

    public enum BlockAnimationMode {
        Fade, Hover, Decrease, Static, Flash, Grow, Fill, TNT, Pull
    }
}
