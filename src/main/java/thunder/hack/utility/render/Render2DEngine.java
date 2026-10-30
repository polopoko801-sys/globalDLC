package thunder.hack.utility.render;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.ScreenRect;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;
import org.apache.commons.lang3.RandomStringUtils;
import org.jetbrains.annotations.NotNull;
import org.joml.Matrix3x2f;
import org.joml.Matrix3x2fStack;
import org.joml.Vector2f;
import org.lwjgl.BufferUtils;
import thunder.hack.features.modules.client.HudEditor;
import thunder.hack.gui.font.Texture;
import thunder.hack.utility.math.MathUtility;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Stack;
import java.util.function.Consumer;

import static thunder.hack.features.modules.Module.mc;

/**
 * 1.21.6+ rewrite. The old immediate-mode path (thunder.hack.utility.render.RSCompat.setShader + Tessellator/BufferBuilder
 * + BufferRenderer.drawWithGlobalProgram) was removed by Mojang's RenderPipeline overhaul. All 2D
 * geometry now goes through {@link GuiRender}, which submits a custom SimpleGuiElementRenderState to
 * the DrawContext's GuiRenderState (vanilla batches by pipeline+texture+scissor and binds UBOs).
 *
 * Consequences of the port:
 *  - Every draw method takes a {@link DrawContext} instead of a MatrixStack; the 2D pose is the
 *    {@code Matrix3x2f} from {@code context.getMatrices()} and is baked into vertices by GuiRender.
 *  - Triangle fans are triangulated and emitted as (degenerate) quads on the QUADS GUI pipeline.
 *  - The custom satin shaders (HUD_SHADER/BLUR_PROGRAM/ARC_PROGRAM/RECTANGLE_SHADER/MAIN_MENU_PROGRAM)
 *    are on the deleted ManagedCoreShader API and not yet migrated to RenderPipeline+UBO. Their draw
 *    methods are approximated here with rounded fills as an interim until the shaders are reimplemented.
 */
public class Render2DEngine {
    public static HashMap<Integer, BlurredShadow> shadowCache = new HashMap<>();
    public static HashMap<Integer, BlurredShadow> shadowCache1 = new HashMap<>();
    final static Stack<ScreenRect> clipStack = new Stack<>();

    /* ------------------------------------------------------------------ */
    /* Scissor / clipping                                                  */
    /* ------------------------------------------------------------------ */

    // public so FontRenderer can honor the active clip too (its glyphs were drawn with null scissor,
    // which let text overflow GUI panels — e.g. module descriptions spilling outside the ClickGUI).
    public static ScreenRect currentClip() {
        return clipStack.isEmpty() ? null : clipStack.peek();
    }

    public static void addWindow(DrawContext context, Rectangle r1) {
        Matrix3x2f matrix = context.getMatrices();
        Vector2f a = matrix.transformPosition(new Vector2f(r1.x, r1.y));
        Vector2f b = matrix.transformPosition(new Vector2f(r1.x1, r1.y1));
        int x = (int) Math.min(a.x, b.x);
        int y = (int) Math.min(a.y, b.y);
        int w = (int) Math.abs(b.x - a.x);
        int h = (int) Math.abs(b.y - a.y);
        ScreenRect rect = new ScreenRect(x, y, w, h);
        if (!clipStack.isEmpty()) {
            ScreenRect inter = clipStack.peek().intersection(rect);
            rect = inter == null ? new ScreenRect(x, y, 0, 0) : inter;
        }
        clipStack.push(rect);
    }

    public static void popWindow() {
        if (!clipStack.isEmpty()) clipStack.pop();
    }

    public static void addWindow(DrawContext context, float x, float y, float x1, float y1, double animation_factor) {
        float h = y + y1;
        float h2 = (float) (h * (1d - MathUtility.clamp(animation_factor, 0, 1.0025f)));

        float x3 = x;
        float y3 = y + h2;
        float x4 = x1;
        float y4 = y1 - h2;

        if (x4 < x3) x4 = x3;
        if (y4 < y3) y4 = y3;
        addWindow(context, new Rectangle(x3, y3, x4, y4));
    }

    /* ------------------------------------------------------------------ */
    /* Solid / gradient rectangles                                         */
    /* ------------------------------------------------------------------ */

    public static void horizontalGradient(DrawContext context, float x1, float y1, float x2, float y2, Color startColor, Color endColor) {
        Matrix3x2f pose = GuiRender.pose(context);
        int s = startColor.getRGB();
        int e = endColor.getRGB();
        GuiRender.quads(context, currentClip(), vc -> GuiRender.quad(vc, pose, x1, y1, x1, y2, x2, y2, x2, y1, s, s, e, e));
    }

    public static void verticalGradient(DrawContext context, float left, float top, float right, float bottom, Color startColor, Color endColor) {
        Matrix3x2f pose = GuiRender.pose(context);
        int s = startColor.getRGB();
        int e = endColor.getRGB();
        GuiRender.quads(context, currentClip(), vc -> GuiRender.quad(vc, pose, left, top, left, bottom, right, bottom, right, top, s, e, e, s));
    }

    public static void drawRect(DrawContext context, float x, float y, float width, float height, Color c) {
        Matrix3x2f pose = GuiRender.pose(context);
        int argb = c.getRGB();
        GuiRender.quads(context, currentClip(), vc -> GuiRender.quad(vc, pose, x, y, x + width, y + height, argb));
    }

    /**
     * Rect drawn with MULTIPLY blending: {@code result = dst * colour}. White squares whatever is under it,
     * which is how ColorGrading gets saturation and contrast without a shader.
     */
    public static void drawMultiplyRect(DrawContext context, float x, float y, float width, float height, Color c) {
        Matrix3x2f pose = GuiRender.pose(context);
        int argb = c.getRGB();
        GuiRender.quadsMultiply(context, currentClip(), vc -> GuiRender.quad(vc, pose, x, y, x + width, y + height, argb));
    }

    public static void drawRectWithOutline(DrawContext context, float x, float y, float width, float height, Color c, Color c2) {
        Matrix3x2f pose = GuiRender.pose(context);
        int fill = c.getRGB();
        int outline = c2.getRGB();
        GuiRender.quads(context, currentClip(), vc -> {
            GuiRender.quad(vc, pose, x, y, x + width, y + height, fill);
            // outline as four 1px quads (the old DEBUG_LINE_STRIP path is gone)
            GuiRender.quad(vc, pose, x, y, x + width, y + 1, outline);
            GuiRender.quad(vc, pose, x, y + height - 1, x + width, y + height, outline);
            GuiRender.quad(vc, pose, x, y, x + 1, y + height, outline);
            GuiRender.quad(vc, pose, x + width - 1, y, x + width, y + height, outline);
        });
    }

    public static void drawRectDumbWay(DrawContext context, float x, float y, float x1, float y1, Color c1) {
        Matrix3x2f pose = GuiRender.pose(context);
        int argb = c1.getRGB();
        GuiRender.quads(context, currentClip(), vc -> GuiRender.quad(vc, pose, x, y, x1, y1, argb));
    }

    public static void draw2DGradientRect(DrawContext context, float left, float top, float right, float bottom, Color leftBottomColor, Color leftTopColor, Color rightBottomColor, Color rightTopColor) {
        Matrix3x2f pose = GuiRender.pose(context);
        int lb = leftBottomColor.getRGB();
        int lt = leftTopColor.getRGB();
        int rb = rightBottomColor.getRGB();
        int rt = rightTopColor.getRGB();
        GuiRender.quads(context, currentClip(), vc ->
                GuiRender.quad(vc, pose, right, top, left, top, left, bottom, right, bottom, rt, lt, lb, rb));
    }

    public static boolean isHovered(double mouseX, double mouseY, double x, double y, double width, double height) {
        return mouseX >= x && mouseX - width <= x && mouseY >= y && mouseY - height <= y;
    }

    /* ------------------------------------------------------------------ */
    /* Blurred shadows (CPU-blurred cached textures)                       */
    /* ------------------------------------------------------------------ */

    public static void drawBlurredShadow(DrawContext context, float x, float y, float width, float height, int blurRadius, Color color) {
        if (!HudEditor.glow.getValue()) return;
        width = width + blurRadius * 2;
        height = height + blurRadius * 2;
        x = x - blurRadius;
        y = y - blurRadius;

        int identifier = (int) (width * height + width * blurRadius);
        BlurredShadow shadow = shadowCache.get(identifier);
        if (shadow == null) {
            BufferedImage original = new BufferedImage((int) width, (int) height, BufferedImage.TYPE_INT_ARGB);
            Graphics g = original.getGraphics();
            g.setColor(new Color(-1));
            g.fillRect(blurRadius, blurRadius, (int) (width - blurRadius * 2), (int) (height - blurRadius * 2));
            g.dispose();
            GaussianFilter op = new GaussianFilter(blurRadius);
            BufferedImage blurred = op.filter(original, null);
            shadowCache.put(identifier, new BlurredShadow(blurred));
            return;
        }

        renderTexture(context, shadow.getId(), x, y, width, height, 0, 0, width, height, width, height, color);
    }

    public static void drawGradientBlurredShadow(DrawContext context, float x, float y, float width, float height, int blurRadius, Color color1, Color color2, Color color3, Color color4) {
        if (!HudEditor.glow.getValue()) return;
        width = width + blurRadius * 2;
        height = height + blurRadius * 2;
        x = x - blurRadius;
        y = y - blurRadius;

        int identifier = (int) (width * height + width * blurRadius);
        BlurredShadow shadow = shadowCache.get(identifier);
        if (shadow == null) {
            BufferedImage original = new BufferedImage((int) width, (int) height, BufferedImage.TYPE_INT_ARGB);
            Graphics g = original.getGraphics();
            g.setColor(new Color(-1));
            g.fillRect(blurRadius, blurRadius, (int) (width - blurRadius * 2), (int) (height - blurRadius * 2));
            g.dispose();
            GaussianFilter op = new GaussianFilter(blurRadius);
            BufferedImage blurred = op.filter(original, null);
            shadowCache.put(identifier, new BlurredShadow(blurred));
            return;
        }

        renderGradientTexture(context, shadow.getId(), x, y, width, height, 0, 0, width, height, width, height, color1, color2, color3, color4);
    }

    public static void drawGradientBlurredShadow1(DrawContext context, float x, float y, float width, float height, int blurRadius, Color color1, Color color2, Color color3, Color color4) {
        if (!HudEditor.glow.getValue()) return;
        width = width + blurRadius * 2;
        height = height + blurRadius * 2;
        x = x - blurRadius;
        y = y - blurRadius;

        int identifier = (int) (width * height + width * blurRadius);
        BlurredShadow shadow = shadowCache1.get(identifier);
        if (shadow == null) {
            BufferedImage original = new BufferedImage((int) width, (int) height, BufferedImage.TYPE_INT_ARGB);
            Graphics g = original.getGraphics();
            g.setColor(new Color(-1));
            g.fillRect(blurRadius, blurRadius, (int) (width - blurRadius * 2), (int) (height - blurRadius * 2));
            g.dispose();
            BufferedImage blurred = new GaussianFilter(blurRadius).filter(original, null);

            BufferedImage black = new BufferedImage((int) width + blurRadius * 2, (int) height + blurRadius * 2, BufferedImage.TYPE_INT_ARGB);
            Graphics g2 = black.getGraphics();
            g2.setColor(new Color(0x000000));
            g2.fillRect(0, 0, (int) width + blurRadius * 2, (int) height + blurRadius * 2);
            g2.dispose();

            BufferedImage combined = new BufferedImage((int) width, (int) height, BufferedImage.TYPE_INT_ARGB);
            Graphics g1 = combined.getGraphics();
            g1.drawImage(black, -blurRadius, -blurRadius, null);
            g1.drawImage(blurred, 0, 0, null);
            g1.dispose();

            shadowCache1.put(identifier, new BlurredShadow(combined));
            return;
        }

        renderGradientTexture(context, shadow.getId(), x, y, width, height, 0, 0, width, height, width, height, color1, color2, color3, color4);
    }

    public static void registerBufferedImageTexture(Texture i, BufferedImage bi) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(bi, "png", baos);
            byte[] bytes = baos.toByteArray();
            registerTexture(i, bytes);
        } catch (Exception ignored) {
        }
    }

    private static void registerTexture(Texture i, byte[] content) {
        try {
            ByteBuffer data = BufferUtils.createByteBuffer(content.length).put(content);
            data.flip();
            NativeImageBackedTexture tex = new NativeImageBackedTexture(() -> "gombal", NativeImage.read(data));
            mc.execute(() -> mc.getTextureManager().registerTexture(i.getId(), tex));
        } catch (Exception ignored) {
        }
    }

    /* ------------------------------------------------------------------ */
    /* Textured quads (GUI_TEXTURED = POSITION_TEX_COLOR)                  */
    /* ------------------------------------------------------------------ */

    public static void renderTexture(DrawContext context, Identifier texture, double x0, double y0, double width, double height, float u, float v, double regionWidth, double regionHeight, double textureWidth, double textureHeight) {
        renderTexture(context, texture, x0, y0, width, height, u, v, regionWidth, regionHeight, textureWidth, textureHeight, Color.WHITE);
    }

    public static void renderTexture(DrawContext context, Identifier texture, double x0, double y0, double width, double height, float u, float v, double regionWidth, double regionHeight, double textureWidth, double textureHeight, Color color) {
        double x1 = x0 + width;
        double y1 = y0 + height;
        Matrix3x2f pose = GuiRender.pose(context);
        int argb = color.getRGB();
        float u0 = u / (float) textureWidth;
        float u1 = (u + (float) regionWidth) / (float) textureWidth;
        float v0 = v / (float) textureHeight;
        float v1 = (v + (float) regionHeight) / (float) textureHeight;
        GuiRender.textured(context, texture, currentClip(), vc -> {
            GuiRender.vertexTex(vc, pose, (float) x0, (float) y1, u0, v1, argb);
            GuiRender.vertexTex(vc, pose, (float) x1, (float) y1, u1, v1, argb);
            GuiRender.vertexTex(vc, pose, (float) x1, (float) y0, u1, v0, argb);
            GuiRender.vertexTex(vc, pose, (float) x0, (float) y0, u0, v0, argb);
        });
    }

    public static void renderGradientTexture(DrawContext context, Identifier texture, double x0, double y0, double width, double height, float u, float v, double regionWidth, double regionHeight, double textureWidth, double textureHeight, Color c1, Color c2, Color c3, Color c4) {
        renderGradientTexture(context, texture, x0, y0, width, height, u, v, regionWidth, regionHeight, textureWidth, textureHeight, c1, c2, c3, c4, false);
    }

    /**
     * Particle sprites ship as opaque PNGs on a black background, so they need additive blending to hide
     * that backdrop; pass {@code additive = true} for those.
     */
    public static void renderGradientTexture(DrawContext context, Identifier texture, double x0, double y0, double width, double height, float u, float v, double regionWidth, double regionHeight, double textureWidth, double textureHeight, Color c1, Color c2, Color c3, Color c4, boolean additive) {
        double x1 = x0 + width;
        double y1 = y0 + height;
        Matrix3x2f pose = GuiRender.pose(context);
        int a1 = c1.getRGB();
        int a2 = c2.getRGB();
        int a3 = c3.getRGB();
        int a4 = c4.getRGB();
        float u0 = u / (float) textureWidth;
        float u1 = (u + (float) regionWidth) / (float) textureWidth;
        float v0 = v / (float) textureHeight;
        float v1 = (v + (float) regionHeight) / (float) textureHeight;
        Consumer<VertexConsumer> quad = vc -> {
            GuiRender.vertexTex(vc, pose, (float) x0, (float) y1, u0, v1, a1);
            GuiRender.vertexTex(vc, pose, (float) x1, (float) y1, u1, v1, a2);
            GuiRender.vertexTex(vc, pose, (float) x1, (float) y0, u1, v0, a3);
            GuiRender.vertexTex(vc, pose, (float) x0, (float) y0, u0, v0, a4);
        };
        if (additive) GuiRender.texturedAdditive(context, texture, currentClip(), quad);
        else GuiRender.textured(context, texture, currentClip(), quad);
    }

    /* ------------------------------------------------------------------ */
    /* Rounded quads (triangle-fan -> degenerate quads)                    */
    /* ------------------------------------------------------------------ */

    public static void renderRoundedGradientRect(DrawContext context, Color color1, Color color2, Color color3, Color color4, float x, float y, float width, float height, float radius) {
        // The old colorMask/clear stencil trick is unavailable on the GUI pipeline; approximate with
        // a rounded gradient fill.
        renderRoundedQuad2(context, color1, color2, color3, color4, x, y, x + width, y + height, radius);
    }

    public static void drawRound(DrawContext context, float x, float y, float width, float height, float radius, Color color) {
        renderRoundedQuad(context, color, x, y, width + x, height + y, radius, 4);
    }

    public static void renderRoundedQuad(DrawContext context, Color c, double fromX, double fromY, double toX, double toY, double radius, double samples) {
        Matrix3x2f pose = GuiRender.pose(context);
        int argb = c.getRGB();
        List<double[]> pts = new ArrayList<>();
        buildRoundedPoints(pts, fromX, fromY, toX, toY, radius, samples);
        GuiRender.quads(context, currentClip(), vc -> {
            double[] o = pts.get(0);
            for (int i = 1; i < pts.size() - 1; i++) {
                double[] a = pts.get(i);
                double[] b = pts.get(i + 1);
                GuiRender.tri(vc, pose, (float) o[0], (float) o[1], (float) a[0], (float) a[1], (float) b[0], (float) b[1], argb);
            }
        });
    }

    public static void renderRoundedQuad2(DrawContext context, Color c, Color c2, Color c3, Color c4, double fromX, double fromY, double toX, double toY, double radius) {
        Matrix3x2f pose = GuiRender.pose(context);
        // corner color mapping mirrors the old renderRoundedQuadInternal2 switch: 0->c2, 1->c, 2->c3, 3->c4
        int[] cornerColors = {c2.getRGB(), c.getRGB(), c3.getRGB(), c4.getRGB()};
        List<double[]> pts = new ArrayList<>();
        List<Integer> cols = new ArrayList<>();
        double[][] map = {
                {toX - radius, toY - radius}, {toX - radius, fromY + radius},
                {fromX + radius, fromY + radius}, {fromX + radius, toY - radius}
        };
        for (int i = 0; i < 4; i++) {
            double cx = map[i][0];
            double cy = map[i][1];
            for (double r = i * 90; r < (90 + i * 90); r += 10) {
                double rad = Math.toRadians(r);
                pts.add(new double[]{cx + Math.sin(rad) * radius, cy + Math.cos(rad) * radius});
                cols.add(cornerColors[i]);
            }
        }
        GuiRender.quads(context, currentClip(), vc -> {
            double[] o = pts.get(0);
            int oc = cols.get(0);
            for (int i = 1; i < pts.size() - 1; i++) {
                double[] a = pts.get(i);
                double[] b = pts.get(i + 1);
                GuiRender.vertex(vc, pose, (float) o[0], (float) o[1], oc);
                GuiRender.vertex(vc, pose, (float) a[0], (float) a[1], cols.get(i));
                GuiRender.vertex(vc, pose, (float) b[0], (float) b[1], cols.get(i + 1));
                GuiRender.vertex(vc, pose, (float) b[0], (float) b[1], cols.get(i + 1));
            }
        });
    }

    /**
     * Real backdrop blur: samples this frame's {@link ScreenCapture} inside a rounded rect and averages a
     * ring of offset taps, which is a box/bokeh blur done with geometry instead of a core shader (those are
     * gone in 1.21.6+). The average has to be order independent, so the panel is first filled with black and
     * every tap is then added at 1/N brightness — additive blending sums to exactly the mean colour.
     *
     * @return false when no snapshot is available (unsupported backend) so callers can fall back
     */
    public static boolean drawBackdropBlur(DrawContext context, float x, float y, float width, float height, float radius, float blurPixels) {
        return drawBackdropBlur(context, x, y, width, height, radius, blurPixels, 1f);
    }

    /**
     * {@link #drawBackdropBlur} with a lens: {@code zoom} below 1 magnifies the backdrop around the panel's
     * centre, like looking through a drop of glass (0.9 = 10% magnified). 1 = flat glass.
     */
    public static boolean drawBackdropBlur(DrawContext context, float x, float y, float width, float height, float radius, float blurPixels, float zoom) {
        return drawBackdropBlur(context, x, y, width, height, radius, blurPixels, zoom, 1f);
    }

    /**
     * {@link #drawBackdropBlur} that can be faded: every layer's alpha is scaled by {@code opacity}, so at 1 it
     * is the exact blur and below that the sharp background shows through - close enough to a true mix for a
     * panel animating in or out.
     */
    public static boolean drawBackdropBlur(DrawContext context, float x, float y, float width, float height, float radius, float blurPixels, float zoom, float opacity) {
        if (!ScreenCapture.available() || width <= 0f || height <= 0f) return false;
        opacity = MathHelper.clamp(opacity, 0f, 1f);
        if (opacity <= 0.004f) return true;

        float sw = context.getScaledWindowWidth();
        float sh = context.getScaledWindowHeight();
        if (sw <= 0f || sh <= 0f) return false;

        List<float[]> taps = blurTaps(MathHelper.clamp(blurPixels, 1f, 40f));

        Matrix3x2f pose = GuiRender.pose(context);
        List<double[]> pts = new ArrayList<>();
        buildRoundedPoints(pts, x, y, x + width, y + height, radius, 6);
        org.joml.Vector2f centre = pose.transformPosition(x + width / 2f, y + height / 2f, new org.joml.Vector2f());

        // Progressive alpha (1/1, 1/2, 1/3 … 1/N) over alpha blending: layer k pulls the result 1/k of the
        // way towards its own colour, so after N layers the panel holds the exact mean of every tap. No
        // black base is needed — the first tap is already opaque — so nothing can flash as a black box.
        for (int k = 0; k < taps.size(); k++) {
            int alpha = Math.max(1, Math.round(255f * opacity / (k + 1)));
            backdropPass(context, GuiRender.GUI_TEXTURED_TRANSLUCENT, pose, pts, taps.get(k)[0], taps.get(k)[1],
                    sw, sh, new Color(255, 255, 255, alpha).getRGB(), zoom, centre);
        }
        return true;
    }

    /**
     * Adds a fraction of the (unblurred) backdrop on top of itself inside the panel - the "vibrancy" lift
     * that makes iOS-style glass look bright and saturated instead of grey. {@code amount} 0.04-0.1 is plenty.
     */
    public static boolean drawBackdropBoost(DrawContext context, float x, float y, float width, float height, float radius, float amount) {
        if (!ScreenCapture.available() || width <= 0f || height <= 0f || amount <= 0.003f) return false;
        float sw = context.getScaledWindowWidth();
        float sh = context.getScaledWindowHeight();
        if (sw <= 0f || sh <= 0f) return false;

        Matrix3x2f pose = GuiRender.pose(context);
        List<double[]> pts = new ArrayList<>();
        buildRoundedPoints(pts, x, y, x + width, y + height, radius, 6);
        int g = MathHelper.clamp(Math.round(255 * amount), 0, 255);
        backdropPass(context, GuiRender.GUI_TEXTURED_ADDITIVE, pose, pts, 0f, 0f, sw, sh,
                new Color(g, g, g, 255).getRGB(), 1f, null);
        return true;
    }

    /**
     * Tap offsets for the backdrop blur: a golden-angle (Vogel) spiral filling a disc. Unlike rings of 8, no
     * two taps line up, so a wide blur reads as a smooth haze instead of a few ghost copies of the image; the
     * radius exponent below 1 packs taps toward the centre, which gives a gaussian-like falloff.
     */
    private static List<float[]> blurTaps(float r) {
        int n = r > 12f ? 32 : r > 5f ? 22 : 12;
        List<float[]> taps = new ArrayList<>(n);
        double golden = Math.PI * (3 - Math.sqrt(5));
        for (int i = 0; i < n; i++) {
            double d = r * Math.pow((i + 0.5) / n, 0.7);
            double a = i * golden;
            taps.add(new float[]{(float) (Math.cos(a) * d), (float) (Math.sin(a) * d)});
        }
        return taps;
    }

    private static void backdropPass(DrawContext context, com.mojang.blaze3d.pipeline.RenderPipeline pipeline, Matrix3x2f pose,
                                     List<double[]> pts, float dx, float dy, float sw, float sh, int argb,
                                     float zoom, org.joml.Vector2f centre) {
        GuiRender.texturedView(context, pipeline, ScreenCapture.view(), ScreenCapture.sampler(), currentClip(), vc -> {
            double[] o = pts.getFirst();
            for (int i = 1; i < pts.size() - 1; i++) {
                double[] a = pts.get(i);
                double[] b = pts.get(i + 1);
                backdropVertex(vc, pose, o, dx, dy, sw, sh, argb, zoom, centre);
                backdropVertex(vc, pose, a, dx, dy, sw, sh, argb, zoom, centre);
                backdropVertex(vc, pose, b, dx, dy, sw, sh, argb, zoom, centre);
                backdropVertex(vc, pose, b, dx, dy, sw, sh, argb, zoom, centre);
            }
        });
    }

    /**
     * Light-catching glass edge: a thin rounded ring, bright where light from the top-left hits it, with a
     * weaker bounce on the bottom-right and fading to nothing on its inner side so it never reads as a stroke.
     */
    public static void drawGlassRim(DrawContext context, float x, float y, float width, float height, float radius, float thickness, float opacity) {
        drawGlassRim(context, x, y, width, height, radius, thickness, opacity, DEFAULT_LIGHT_X, DEFAULT_LIGHT_Y);
    }

    /** Where the key light sits inside a pane unless a caller moves it: just past the top-left corner. */
    private static final float DEFAULT_LIGHT_X = 0.12f;
    private static final float DEFAULT_LIGHT_Y = 0.04f;

    /**
     * {@link #drawGlassRim} with the key light placed inside the pane: {@code lightX}/{@code lightY} are 0..1
     * panel coordinates and the bright arc slides towards whichever edge faces the light. Tracking the cursor
     * with it is what turns a static white outline into a specular highlight that behaves like real glass.
     */
    public static void drawGlassRim(DrawContext context, float x, float y, float width, float height, float radius, float thickness, float opacity, float lightX, float lightY) {
        float op = MathHelper.clamp(opacity, 0f, 1f);
        if (op <= 0.01f || width <= thickness * 2f || height <= thickness * 2f) return;
        List<double[]> outer = new ArrayList<>(), inner = new ArrayList<>();
        buildRoundedPoints(outer, x, y, x + width, y + height, radius, 6);
        buildRoundedPoints(inner, x + thickness, y + thickness, x + width - thickness, y + height - thickness,
                Math.max(0f, radius - thickness), 6);
        if (outer.size() != inner.size()) return;

        float lx = MathHelper.clamp(lightX, 0f, 1f), ly = MathHelper.clamp(lightY, 0f, 1f);
        Matrix3x2f pose = GuiRender.pose(context);
        GuiRender.quads(context, currentClip(), vc -> {
            for (int i = 0; i < outer.size(); i++) {
                int j = (i + 1) % outer.size();
                double[] o0 = outer.get(i), o1 = outer.get(j), i0 = inner.get(i), i1 = inner.get(j);
                GuiRender.vertex(vc, pose, (float) o0[0], (float) o0[1], rimColor(o0, x, y, width, height, op, 1f, lx, ly));
                GuiRender.vertex(vc, pose, (float) o1[0], (float) o1[1], rimColor(o1, x, y, width, height, op, 1f, lx, ly));
                GuiRender.vertex(vc, pose, (float) i1[0], (float) i1[1], rimColor(i1, x, y, width, height, op, 0.12f, lx, ly));
                GuiRender.vertex(vc, pose, (float) i0[0], (float) i0[1], rimColor(i0, x, y, width, height, op, 0.12f, lx, ly));
            }
        });
    }

    private static int rimColor(double[] p, float x, float y, float w, float h, float op, float edge, float lightX, float lightY) {
        float fy = (float) ((p[1] - y) / h), fx = (float) ((p[0] - x) / w);
        // how squarely this point of the rim faces the light: the pane centre -> rim direction against
        // the pane centre -> light direction, so the hot spot travels around the edge with the light
        double dx = fx - 0.5, dy = fy - 0.5;
        double lx = lightX - 0.5, ly = lightY - 0.5;
        double dl = Math.sqrt(dx * dx + dy * dy), ll = Math.sqrt(lx * lx + ly * ly);
        float facing = dl < 1.0e-4 || ll < 1.0e-4 ? 1f
                : (float) MathHelper.clamp(0.5 + 0.5 * ((dx * lx + dy * ly) / (dl * ll)), 0.0, 1.0);
        float key = facing * facing;                                             // key light
        float bounce = MathHelper.clamp((fy + fx) / 2f - 0.62f, 0f, 1f) * 1.6f; // opposite side reflection
        float a = (0.08f + 0.64f * key + 0.22f * bounce) * op * edge;
        return new Color(255, 255, 255, MathHelper.clamp(Math.round(a * 255), 0, 255)).getRGB();
    }

    /**
     * Paints this frame's snapshot ({@link ScreenCapture}) back over the whole screen.
     *
     * @param argb     tint/alpha of the copy - white with a partial alpha mixes it back in, a grey
     *                 {@code (b,b,b)} with {@code additive} adds a fraction of the frame to itself
     * @param additive additive blending instead of alpha blending
     * @return false when there is no snapshot this frame (nothing was drawn)
     */
    public static boolean drawScreenSnapshot(DrawContext context, int argb, boolean additive) {
        if (!ScreenCapture.available()) return false;

        float sw = context.getScaledWindowWidth();
        float sh = context.getScaledWindowHeight();
        if (sw <= 0f || sh <= 0f) return false;

        Matrix3x2f pose = GuiRender.pose(context);
        GuiRender.texturedView(context, additive ? GuiRender.GUI_TEXTURED_ADDITIVE : GuiRender.GUI_TEXTURED_TRANSLUCENT,
                ScreenCapture.view(), ScreenCapture.sampler(), currentClip(), vc -> {
                    // the snapshot covers the window, so screen position maps straight to UV; V is flipped
                    // because a render target's first row is the BOTTOM of the screen
                    GuiRender.vertexTex(vc, pose, 0f, 0f, 0f, 1f, argb);
                    GuiRender.vertexTex(vc, pose, 0f, sh, 0f, 0f, argb);
                    GuiRender.vertexTex(vc, pose, sw, sh, 1f, 0f, argb);
                    GuiRender.vertexTex(vc, pose, sw, 0f, 1f, 1f, argb);
                });
        return true;
    }

    /**
     * One vertex of a blur tap. The snapshot covers the whole window, so screen position maps straight to
     * UV; V is flipped because a render target's first row is the BOTTOM of the screen, unlike GUI textures.
     */
    private static void backdropVertex(net.minecraft.client.render.VertexConsumer vc, Matrix3x2f pose,
                                       double[] p, float dx, float dy, float sw, float sh, int argb,
                                       float zoom, org.joml.Vector2f centre) {
        float px = (float) p[0];
        float py = (float) p[1];
        // UV must come from where the vertex really lands on screen: a scaled/translated pose (GUI open
        // animation, HUD scale) would otherwise sample the backdrop from the wrong place
        org.joml.Vector2f scr = pose.transformPosition(px, py, new org.joml.Vector2f());
        if (centre != null && zoom != 1f) {
            // lens: sample closer to the centre than the vertex is, which magnifies what is behind the glass
            scr.x = centre.x + (scr.x - centre.x) * zoom;
            scr.y = centre.y + (scr.y - centre.y) * zoom;
        }
        float u = MathHelper.clamp((scr.x + dx) / sw, 0f, 1f);
        float v = MathHelper.clamp(1f - (scr.y + dy) / sh, 0f, 1f);
        GuiRender.vertexTex(vc, pose, px, py, u, v, argb);
    }

    private static void buildRoundedPoints(List<double[]> pts, double fromX, double fromY, double toX, double toY, double radius, double samples) {
        double[][] map = {
                {toX - radius, toY - radius}, {toX - radius, fromY + radius},
                {fromX + radius, fromY + radius}, {fromX + radius, toY - radius}
        };
        for (int i = 0; i < 4; i++) {
            double cx = map[i][0];
            double cy = map[i][1];
            for (double r = i * 90d; r < (360 / 4d + i * 90d); r += (90 / samples)) {
                double rad = Math.toRadians(r);
                pts.add(new double[]{cx + Math.sin(rad) * radius, cy + Math.cos(rad) * radius});
            }
            double rad = Math.toRadians(360 / 4d + i * 90d);
            pts.add(new double[]{cx + Math.sin(rad) * radius, cy + Math.cos(rad) * radius});
        }
    }

    public static void setupRender() {
        // no-op: blend/depth state is encoded in the RenderPipeline now.
    }

    public static void endRender() {
        // no-op: blend/depth state is encoded in the RenderPipeline now.
    }

    /* ------------------------------------------------------------------ */
    /* Tracer arrows                                                       */
    /* ------------------------------------------------------------------ */

    public static void drawTracerPointer(DrawContext context, float x, float y, float size, float tracerWidth, float downHeight, boolean down, boolean glow, int color) {
        switch (HudEditor.arrowsStyle.getValue()) {
            case Default -> {
                if (HudEditor.arrowsFilled.getValue())
                    drawDefaultArrow(context, x, y, size, tracerWidth, downHeight, down, glow, color);
                else
                    drawArrowOutline(context, x, y, size, tracerWidth, downHeight, glow, color);
            }
            case New -> drawNewArrow(context, x, y, size + 8, new Color(color));
            case Sleek -> drawSleekArrow(context, x, y, size, tracerWidth, downHeight, glow, color);
        }
    }

    /**
     * Sharper arrow: the body fades from a bright tip into a translucent base and always carries a crisp
     * rim, so it stays readable on any background. With HudEditor.ArrowsFilled off only that rim is drawn.
     */
    public static void drawSleekArrow(DrawContext context, float x, float y, float size, float tracerWidth, float downHeight, boolean glow, int color) {
        Color c = new Color(color);
        float halfW = size * tracerWidth;

        float tipX = x, tipY = y;
        float leftX = x - halfW, leftY = y + size;
        float rightX = x + halfW, rightY = y + size;
        float notchX = x, notchY = y + size - downHeight;

        if (glow)
            drawBlurredShadow(context, x - halfW, y, halfW * 2f, size, 10, injectAlpha(c, 120));

        if (HudEditor.arrowsFilled.getValue()) {
            Matrix3x2f pose = GuiRender.pose(context);
            int tip = injectAlpha(c, Math.min(255, c.getAlpha())).getRGB();
            int base = injectAlpha(darker(c, 0.75f), (int) (c.getAlpha() * 0.35f)).getRGB();
            GuiRender.quads(context, currentClip(), vc -> {
                GuiRender.vertex(vc, pose, tipX, tipY, tip);
                GuiRender.vertex(vc, pose, leftX, leftY, base);
                GuiRender.vertex(vc, pose, notchX, notchY, base);
                GuiRender.vertex(vc, pose, tipX, tipY, tip);

                GuiRender.vertex(vc, pose, tipX, tipY, tip);
                GuiRender.vertex(vc, pose, notchX, notchY, base);
                GuiRender.vertex(vc, pose, rightX, rightY, base);
                GuiRender.vertex(vc, pose, tipX, tipY, tip);
            });
        }

        float t = HudEditor.arrowsThickness.getValue();
        Color rim = HudEditor.arrowsFilled.getValue() ? injectAlpha(Color.WHITE, (int) (c.getAlpha() * 0.75f)) : c;
        thickLine(context, tipX, tipY, leftX, leftY, t, rim);
        thickLine(context, leftX, leftY, notchX, notchY, t, rim);
        thickLine(context, notchX, notchY, rightX, rightY, t, rim);
        thickLine(context, rightX, rightY, tipX, tipY, t, rim);
    }

    /** Hollow variant of the classic arrow — same silhouette, empty middle. */
    public static void drawArrowOutline(DrawContext context, float x, float y, float size, float tracerWidth, float downHeight, boolean glow, int color) {
        Color c = new Color(color);
        float halfW = size * tracerWidth;
        if (glow)
            drawBlurredShadow(context, x - halfW, y, halfW * 2f, size, 10, injectAlpha(c, 110));

        float t = HudEditor.arrowsThickness.getValue();
        thickLine(context, x, y, x - halfW, y + size, t, c);
        thickLine(context, x - halfW, y + size, x, y + size - downHeight, t, c);
        thickLine(context, x, y + size - downHeight, x + halfW, y + size, t, c);
        thickLine(context, x + halfW, y + size, x, y, t, c);
    }

    /** A line with real width, emitted as a quad (the GUI pipeline has no line width). */
    public static void thickLine(DrawContext context, float x1, float y1, float x2, float y2, float thickness, Color color) {
        float dx = x2 - x1, dy = y2 - y1;
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len < 0.0001f) return;
        float nx = -dy / len * thickness / 2f;
        float ny = dx / len * thickness / 2f;

        Matrix3x2f pose = GuiRender.pose(context);
        int argb = color.getRGB();
        GuiRender.quads(context, currentClip(), vc -> {
            GuiRender.vertex(vc, pose, x1 + nx, y1 + ny, argb);
            GuiRender.vertex(vc, pose, x2 + nx, y2 + ny, argb);
            GuiRender.vertex(vc, pose, x2 - nx, y2 - ny, argb);
            GuiRender.vertex(vc, pose, x1 - nx, y1 - ny, argb);
        });
    }

    public static void drawNewArrow(DrawContext context, float x, float y, float size, Color color) {
        Matrix3x2f pose = GuiRender.pose(context);
        int argb = color.getRGB();
        GuiRender.textured(context, TextureStorage.arrow, currentClip(), vc -> {
            GuiRender.vertexTex(vc, pose, x - (size / 2f), y + size, 0f, 1f, argb);
            GuiRender.vertexTex(vc, pose, x + size / 2f, y + size, 1f, 1f, argb);
            GuiRender.vertexTex(vc, pose, x + size / 2f, y, 1f, 0f, argb);
            GuiRender.vertexTex(vc, pose, x - (size / 2f), y, 0f, 0f, argb);
        });
    }

    public static void drawDefaultArrow(DrawContext context, float x, float y, float size, float tracerWidth, float downHeight, boolean down, boolean glow, int color) {
        if (glow)
            drawBlurredShadow(context, x - size * tracerWidth, y, (x + size * tracerWidth) - (x - size * tracerWidth), size, 10, injectAlpha(new Color(color), 140));

        Matrix3x2f pose = GuiRender.pose(context);
        Color base = new Color(color, true);
        // bright at the tip, fading towards the tail — a flat two-tone triangle is what made it look cheap
        int c0 = injectAlpha(brighter(base, 1.25f), base.getAlpha()).getRGB();
        int c1 = injectAlpha(base, (int) (base.getAlpha() * 0.85f)).getRGB();
        int c2 = injectAlpha(darker(base, 0.72f), (int) (base.getAlpha() * 0.55f)).getRGB();
        float halfW = size * tracerWidth;
        GuiRender.quads(context, currentClip(), vc -> {
            // left half (triangle as degenerate quad): bright at the tip, translucent at the outer corner
            GuiRender.vertex(vc, pose, x, y, c0);
            GuiRender.vertex(vc, pose, x - halfW, y + size, c2);
            GuiRender.vertex(vc, pose, x, y + size - downHeight, c1);
            GuiRender.vertex(vc, pose, x, y, c0);
            // right half
            GuiRender.vertex(vc, pose, x, y, c0);
            GuiRender.vertex(vc, pose, x, y + size - downHeight, c1);
            GuiRender.vertex(vc, pose, x + halfW, y + size, c2);
            GuiRender.vertex(vc, pose, x, y, c0);
            if (down) {
                GuiRender.vertex(vc, pose, x - halfW, y + size, c2);
                GuiRender.vertex(vc, pose, x + halfW, y + size, c2);
                GuiRender.vertex(vc, pose, x, y + size - downHeight, c1);
                GuiRender.vertex(vc, pose, x - halfW, y + size, c2);
            }
        });

        // crisp rim along the two leading edges keeps the shape readable on bright terrain
        float t = MathHelper.clamp(HudEditor.arrowsThickness.getValue() * 0.7f, 0.4f, 2f);
        Color rim = injectAlpha(brighter(base, 1.5f), (int) (base.getAlpha() * 0.8f));
        thickLine(context, x, y, x - halfW, y + size, t, rim);
        thickLine(context, x + halfW, y + size, x, y, t, rim);
    }

    public static void drawGradientRound(DrawContext context, float v, float v1, float i, float i1, float v2, Color darker, Color darker1, Color darker2, Color darker3) {
        renderRoundedQuad2(context, darker, darker1, darker2, darker3, v, v1, v + i, v1 + i1, v2);
    }

    /* ------------------------------------------------------------------ */
    /* Color / animation helpers (unchanged)                               */
    /* ------------------------------------------------------------------ */

    public static float scrollAnimate(float endPoint, float current, float speed) {
        boolean shouldContinueAnimation = endPoint > current;
        if (speed < 0.0f) {
            speed = 0.0f;
        } else if (speed > 1.0f) {
            speed = 1.0f;
        }

        float dif = Math.max(endPoint, current) - Math.min(endPoint, current);
        float factor = dif * speed;
        return current + (shouldContinueAnimation ? factor : -factor);
    }

    public static Color injectAlpha(final Color color, final int alpha) {
        return new Color(color.getRed(), color.getGreen(), color.getBlue(), MathHelper.clamp(alpha, 0, 255));
    }

    public static Color TwoColoreffect(Color cl1, Color cl2, double speed, double count) {
        int angle = (int) (((System.currentTimeMillis()) / speed + count) % 360);
        angle = (angle >= 180 ? 360 - angle : angle) * 2;
        return interpolateColorC(cl1, cl2, angle / 360f);
    }

    public static Color astolfo(boolean clickgui, int yOffset) {
        float speed = clickgui ? 35 * 100 : 30 * 100;
        float hue = (System.currentTimeMillis() % (int) speed) + yOffset;
        if (hue > speed) {
            hue -= speed;
        }
        hue /= speed;
        if (hue > 0.5F) {
            hue = 0.5F - (hue - 0.5F);
        }
        hue += 0.5F;
        return Color.getHSBColor(hue, 0.4F, 1F);
    }

    public static Color rainbow(int delay, float saturation, float brightness) {
        double rainbow = Math.ceil((System.currentTimeMillis() + delay) / 16f);
        rainbow %= 360;
        return Color.getHSBColor((float) (rainbow / 360), saturation, brightness);
    }

    public static Color skyRainbow(int speed, int index) {
        int angle = (int) ((System.currentTimeMillis() / speed + index) % 360);
        return Color.getHSBColor((double) ((float) ((angle %= 360) / 360.0)) < 0.5 ? -((float) (angle / 360.0)) : (float) (angle / 360.0), 0.5F, 1.0F);
    }

    public static Color fade(int speed, int index, Color color, float alpha) {
        float[] hsb = Color.RGBtoHSB(color.getRed(), color.getGreen(), color.getBlue(), null);
        int angle = (int) ((System.currentTimeMillis() / speed + index) % 360);
        angle = (angle > 180 ? 360 - angle : angle) + 180;

        Color colorHSB = new Color(Color.HSBtoRGB(hsb[0], hsb[1], angle / 360f));

        return new Color(colorHSB.getRed(), colorHSB.getGreen(), colorHSB.getBlue(), Math.max(0, Math.min(255, (int) (alpha * 255))));
    }

    public static Color getAnalogousColor(Color color) {
        float[] hsb = Color.RGBtoHSB(color.getRed(), color.getGreen(), color.getBlue(), null);
        float degree = 0.84f;
        float newHueSubtracted = hsb[0] - degree;
        return new Color(Color.HSBtoRGB(newHueSubtracted, hsb[1], hsb[2]));
    }

    public static Color applyOpacity(Color color, float opacity) {
        opacity = Math.min(1, Math.max(0, opacity));
        return new Color(color.getRed(), color.getGreen(), color.getBlue(), (int) (color.getAlpha() * opacity));
    }

    public static int applyOpacity(int color_int, float opacity) {
        opacity = Math.min(1, Math.max(0, opacity));
        Color color = new Color(color_int);
        return new Color(color.getRed(), color.getGreen(), color.getBlue(), (int) (color.getAlpha() * opacity)).getRGB();
    }

    /** Scales a colour up towards white, keeping its alpha (companion to {@link #darker}). */
    public static Color brighter(Color color, float factor) {
        return new Color(
                (int) MathHelper.clamp(color.getRed() * factor, 0f, 255f),
                (int) MathHelper.clamp(color.getGreen() * factor, 0f, 255f),
                (int) MathHelper.clamp(color.getBlue() * factor, 0f, 255f),
                color.getAlpha());
    }

    public static Color darker(Color color, float factor) {
        return new Color(Math.max((int) (color.getRed() * factor), 0), Math.max((int) (color.getGreen() * factor), 0), Math.max((int) (color.getBlue() * factor), 0), color.getAlpha());
    }

    public static Color rainbow(int speed, int index, float saturation, float brightness, float opacity) {
        int angle = (int) ((System.currentTimeMillis() / speed + index) % 360);
        float hue = angle / 360f;
        Color color = new Color(Color.HSBtoRGB(hue, saturation, brightness));
        return new Color(color.getRed(), color.getGreen(), color.getBlue(), Math.max(0, Math.min(255, (int) (opacity * 255))));
    }

    public static Color interpolateColorsBackAndForth(int speed, int index, Color start, Color end, boolean trueColor) {
        int angle = (int) (((System.currentTimeMillis()) / speed + index) % 360);
        angle = (angle >= 180 ? 360 - angle : angle) * 2;
        return trueColor ? interpolateColorHue(start, end, angle / 360f) : interpolateColorC(start, end, angle / 360f);
    }

    public static Color interpolateColorC(Color color1, Color color2, float amount) {
        amount = Math.min(1, Math.max(0, amount));
        return new Color(interpolateInt(color1.getRed(), color2.getRed(), amount), interpolateInt(color1.getGreen(), color2.getGreen(), amount), interpolateInt(color1.getBlue(), color2.getBlue(), amount), interpolateInt(color1.getAlpha(), color2.getAlpha(), amount));
    }

    public static Color interpolateColorHue(Color color1, Color color2, float amount) {
        amount = Math.min(1, Math.max(0, amount));

        float[] color1HSB = Color.RGBtoHSB(color1.getRed(), color1.getGreen(), color1.getBlue(), null);
        float[] color2HSB = Color.RGBtoHSB(color2.getRed(), color2.getGreen(), color2.getBlue(), null);

        Color resultColor = Color.getHSBColor(interpolateFloat(color1HSB[0], color2HSB[0], amount), interpolateFloat(color1HSB[1], color2HSB[1], amount), interpolateFloat(color1HSB[2], color2HSB[2], amount));

        return new Color(resultColor.getRed(), resultColor.getGreen(), resultColor.getBlue(), interpolateInt(color1.getAlpha(), color2.getAlpha(), amount));
    }

    public static double interpolate(double oldValue, double newValue, double interpolationValue) {
        return (oldValue + (newValue - oldValue) * interpolationValue);
    }

    public static float interpolateFloat(float oldValue, float newValue, double interpolationValue) {
        return (float) interpolate(oldValue, newValue, (float) interpolationValue);
    }

    public static int interpolateInt(int oldValue, int newValue, double interpolationValue) {
        return (int) interpolate(oldValue, newValue, (float) interpolationValue);
    }

    /* ------------------------------------------------------------------ */
    /* Custom-shader draws (INTERIM approximations)                        */
    /* The satin ManagedCoreShader framework was removed in 1.21.6; until  */
    /* the shaders are reimplemented on RenderPipeline+UBO these render as  */
    /* rounded fills so layout/behaviour is preserved.                     */
    /* ------------------------------------------------------------------ */

    /**
     * A real ring segment, centred in the given box.
     *
     * <p>This used to be a stub that drew a rounded QUAD and ignored the angles entirely - which is why
     * the circle crosshair was a coloured square sitting up and to the left of the cursor instead of a
     * cooldown ring. It is built out of triangles between an inner and an outer arc, with the colour
     * walked from {@code c1} to {@code c2} along the sweep.
     *
     * @param radius    outer radius, in pixels
     * @param thickness how thick the band is, in pixels; at or beyond the radius it becomes a full disc
     * @param start     where the sweep begins, in degrees, 0 = twelve o'clock, going clockwise
     * @param end       where it ends, in degrees
     */
    public static void drawArc(DrawContext context, float x, float y, float width, float height, float radius, float thickness, float start, float end, Color c1, Color c2) {
        float cx = x + width / 2f;
        float cy = y + height / 2f;
        float outer = radius > 0 ? radius : Math.min(width, height) / 2f;
        float inner = Math.max(0f, outer - Math.max(0.5f, thickness));

        float sweep = end - start;
        if (Math.abs(sweep) < 0.01f) return;

        // One segment per ~4 degrees: past that a ring this size starts to show its corners, and below
        // it we would be paying for triangles nobody can see.
        int segments = Math.max(3, Math.round(Math.abs(sweep) / 4f));
        Matrix3x2f pose = GuiRender.pose(context);

        GuiRender.quads(context, currentClip(), vc -> {
            for (int i = 0; i < segments; i++) {
                float t0 = i / (float) segments;
                float t1 = (i + 1) / (float) segments;

                double a0 = Math.toRadians(start + sweep * t0 - 90);
                double a1 = Math.toRadians(start + sweep * t1 - 90);

                float ox0 = cx + (float) Math.cos(a0) * outer, oy0 = cy + (float) Math.sin(a0) * outer;
                float ox1 = cx + (float) Math.cos(a1) * outer, oy1 = cy + (float) Math.sin(a1) * outer;
                float ix0 = cx + (float) Math.cos(a0) * inner, iy0 = cy + (float) Math.sin(a0) * inner;
                float ix1 = cx + (float) Math.cos(a1) * inner, iy1 = cy + (float) Math.sin(a1) * inner;

                int argb0 = lerpColor(c1, c2, t0).getRGB();
                int argb1 = lerpColor(c1, c2, t1).getRGB();

                GuiRender.tri(vc, pose, ix0, iy0, ox0, oy0, ox1, oy1, argb0);
                GuiRender.tri(vc, pose, ix0, iy0, ox1, oy1, ix1, iy1, argb1);
                // the GUI pipeline culls back faces and a clockwise sweep winds the other way - draw both sides
                GuiRender.tri(vc, pose, ix0, iy0, ox1, oy1, ox0, oy0, argb0);
                GuiRender.tri(vc, pose, ix0, iy0, ix1, iy1, ox1, oy1, argb1);
            }
        });
    }

    /** Straight component-wise blend, alpha included. */
    public static Color lerpColor(Color a, Color b, float t) {
        t = Math.max(0f, Math.min(1f, t));
        return new Color(
                Math.round(a.getRed() + (b.getRed() - a.getRed()) * t),
                Math.round(a.getGreen() + (b.getGreen() - a.getGreen()) * t),
                Math.round(a.getBlue() + (b.getBlue() - a.getBlue()) * t),
                Math.round(a.getAlpha() + (b.getAlpha() - a.getAlpha()) * t));
    }

    public static void drawRect(DrawContext context, float x, float y, float width, float height, float radius, float alpha) {
        // INTERIM: RECTANGLE_SHADER pending migration; use the HUD gradient colors.
        drawRect(context, x, y, width, height, radius, alpha,
                HudEditor.getColor(270), HudEditor.getColor(0), HudEditor.getColor(180), HudEditor.getColor(90));
    }

    public static void drawRect(DrawContext context, float x, float y, float width, float height, float radius, float alpha, Color c1, Color c2, Color c3, Color c4) {
        renderRoundedQuad2(context, applyOpacity(c1, alpha), applyOpacity(c2, alpha), applyOpacity(c3, alpha), applyOpacity(c4, alpha), x, y, x + width, y + height, radius);
    }

    private static void hudBaseFill(DrawContext context, float x, float y, float width, float height, float radius, float externalAlpha) {
        renderRoundedQuad2(context,
                applyOpacity(HudEditor.getColor(270), externalAlpha),
                applyOpacity(HudEditor.getColor(0), externalAlpha),
                applyOpacity(HudEditor.getColor(180), externalAlpha),
                applyOpacity(HudEditor.getColor(90), externalAlpha),
                x, y, x + width, y + height, radius);
    }

    public static void drawHudBase(DrawContext context, float x, float y, float width, float height, float radius) {
        if (HudEditor.hudStyle.is(HudEditor.HudStyle.Blurry)) {
            drawRoundedBlur(context, x, y, width, height, radius, HudEditor.blurColor.getValue().getColorObject());
        } else {
            hudBaseFill(context, x, y, width, height, radius, HudEditor.alpha.getValue());
        }
    }

    public static void drawHudBase2(DrawContext context, float x, float y, float width, float height, float radius, float blurStrenth, float blurOpacity, float animationFactor) {
        if (HudEditor.hudStyle.is(HudEditor.HudStyle.Blurry)) {
            blurStrenth *= animationFactor;
            blurOpacity = (float) interpolate(1f, blurOpacity, animationFactor);
            Color c = interpolateColorC(Color.BLACK, HudEditor.blurColor.getValue().getColorObject(), animationFactor);
            drawRoundedBlur(context, x, y, width, height, radius, c, blurStrenth, blurOpacity);
        } else {
            hudBaseFill(context, x, y, width, height, radius, HudEditor.alpha.getValue());
        }
    }

    public static void drawHudBase(DrawContext context, float x, float y, float width, float height, float radius, boolean hud) {
        hudBaseFill(context, x, y, width, height, radius, HudEditor.alpha.getValue());
    }

    /**
     * A top-lit sheen that follows the pane's shape: a few stacked translucent copies of the pane, each
     * shorter than the last, so it fades downwards like a gradient but keeps the rounded (or circular)
     * outline. A rectangular gradient here poked out past the curve as a visible box.
     *
     * @param alpha the brightest the sheen gets at the top edge, 0..255
     */
    public static void drawRoundedSheen(DrawContext context, float x, float y, float width, float height, float radius, float alpha) {
        if (alpha < 1f || width <= 2f || height <= 2f) return;
        // Circles get no sheen. Every way of shaping one onto a disc showed: a rectangle stuck out of the top,
        // stacked discs banded, and a 4-corner gradient fill drew a dark "V" wedge (the fan interpolates from
        // the corners). Even milky glass reads fine on a round button.
        if (radius >= Math.min(width, height) / 2f - 0.5f) return;
        // Rounded panes: a plain vertical gradient over the top half, kept to the straight middle band between
        // the corner curves - inside the outline by construction, so no box, no bands, no wedge.
        float inset = Math.max(1f, radius);
        if (width - inset * 2f <= 1f) return;
        verticalGradient(context, x + inset, y + 1f, x + width - inset, y + height * 0.5f,
                injectAlpha(Color.WHITE, Math.min(255, Math.round(alpha))), injectAlpha(Color.WHITE, 0));
    }

    /**
     * A soft shadow that keeps the shape's rounding: stacked, slightly larger rounded copies at falling alpha.
     *
     * @param spread how far the shadow reaches past the shape
     * @param alpha  darkness right at the edge, 0..255
     */
    public static void drawRoundedShadow(DrawContext context, float x, float y, float width, float height, float radius, float spread, int alpha) {
        if (alpha <= 0 || width <= 0f || height <= 0f) return;
        int rings = 5;
        for (int i = rings; i >= 1; i--) {
            float g = spread * i / rings;
            int a = Math.round(alpha * 0.22f * (1f - i / (rings + 1f)));
            if (a <= 0) continue;
            drawRound(context, x - g, y - g + 1.5f, width + g * 2f, height + g * 2f, radius + g, new Color(0, 0, 0, a));
        }
    }

    public static void drawRoundedBlur(DrawContext context, float x, float y, float width, float height, float radius, Color c1) {
        drawRoundedBlur(context, x, y, width, height, radius, c1, HudEditor.blurStrength.getValue(), HudEditor.blurOpacity.getValue());
    }

    public static void drawRoundedBlur(DrawContext context, float x, float y, float width, float height, float radius, Color c1, float blurStrenth, float blurOpacity) {
        drawRoundedBlur(context, x, y, width, height, radius, c1, blurStrenth, blurOpacity, 1f);
    }

    /** The liquid glass with the default HUD strength, faded as a whole by {@code fade} (0..1) - for panels animating in or out. */
    public static void drawRoundedBlurFaded(DrawContext context, float x, float y, float width, float height, float radius, Color c1, float fade) {
        drawRoundedBlur(context, x, y, width, height, radius, c1, HudEditor.blurStrength.getValue(), HudEditor.blurOpacity.getValue(), fade);
    }

    /** @param fade the whole pane's opacity, 0..1 - the backdrop blur fades with it, not just the tint */
    public static void drawRoundedBlur(DrawContext context, float x, float y, float width, float height, float radius, Color c1, float blurStrenth, float blurOpacity, float fade) {
        // INTERIM: BLUR_PROGRAM is still pending migration to RenderPipeline, so there is no way to sample
        // and blur what is actually behind the panel. The glass look is built out of geometry instead: soft
        // drop shadow, translucent tinted body with a top-down sheen, a bright top hairline and faint side
        // edges — which is what reads as frosted glass on a HUD even without a real backdrop blur.
        fade = MathHelper.clamp(fade, 0f, 1f);
        float op = MathHelper.clamp(blurOpacity, 0f, 1f) * fade;
        if (op <= 0.01f || width <= 0f || height <= 0f) return;

        // BlurStrength drives how far the panel lifts off the background, instead of doing nothing at all.
        int shadowRadius = (int) MathHelper.clamp(blurStrenth / 4f, 2f, 14f);
        drawBlurredShadow(context, x, y, width, height, shadowRadius, injectAlpha(Color.BLACK, (int) (165 * op)));

        // ---- iOS-style glass: real blurred backdrop + one flat, uniform tint on top ----
        if (drawBackdropBlur(context, x, y, width, height, radius, blurStrenth / 2.5f, 1f, fade)) {
            // Vibrancy: lift the blurred image a touch so the glass looks bright and alive, not grey.
            drawBackdropBoost(context, x, y, width, height, radius, 0.07f * op);

            // even veil that darkens and slightly cools the blurred image (amount = HudEditor GlassTint)
            int tintA = (int) (255 * MathHelper.clamp(HudEditor.glassTint.getValue(), 0f, 0.8f) * op);
            // liquid glass (the look of the Effects / Binds panels, used everywhere): the veil follows the GUI colour
            Color guiColor = HudEditor.getColor(0);
            Color veil = injectAlpha(interpolateColorC(guiColor, new Color(18, 18, 24), 0.55f), tintA);
            renderRoundedQuad(context, veil, x, y, x + width, y + height, radius, 6);

            // soft specular sheen falling from the top edge
            float ins = Math.min(radius, width / 2f) * 0.6f;
            float sheen = Math.min(height * 0.45f, height - 1f);
            if (sheen > 0.5f && width - ins * 2f > 0f)
                verticalGradient(context, x + ins, y + 0.8f, x + width - ins, y + sheen,
                        injectAlpha(Color.WHITE, (int) (30 * op)), injectAlpha(Color.WHITE, 0));

            // a faint wash of the GUI colour and a light milky veil
            renderRoundedQuad(context, injectAlpha(guiColor, (int) (22 * op)), x, y, x + width, y + height, radius, 6);
            renderRoundedQuad(context, injectAlpha(Color.WHITE, (int) (14 * op)), x, y, x + width, y + height, radius, 6);
            // light-catching rim instead of flat hairlines
            drawGlassRim(context, x, y, width, height, radius, Math.min(1.3f, Math.min(width, height) / 6f), op);
            return;
        }

        Color base = applyOpacity(c1, op);
        int a = base.getAlpha();
        // Everything below scales with the panel's own alpha, so a near-transparent overlay (the hotbar
        // slots pass alpha 16) stays near-transparent instead of turning into a lit glass slab.
        float strength = a / 255f;
        Color tinted = base;

        Color topLeft = new Color(
                MathHelper.clamp(tinted.getRed() + 34, 0, 255),
                MathHelper.clamp(tinted.getGreen() + 38, 0, 255),
                MathHelper.clamp(tinted.getBlue() + 46, 0, 255), a);
        Color topRight = new Color(
                MathHelper.clamp(tinted.getRed() + 14, 0, 255),
                MathHelper.clamp(tinted.getGreen() + 18, 0, 255),
                MathHelper.clamp(tinted.getBlue() + 26, 0, 255), a);
        Color bottomLeft = new Color(
                (int) (tinted.getRed() * 0.86f),
                (int) (tinted.getGreen() * 0.86f),
                (int) (tinted.getBlue() * 0.88f), a);
        Color bottomRight = new Color(
                (int) (tinted.getRed() * 0.7f),
                (int) (tinted.getGreen() * 0.7f),
                (int) (tinted.getBlue() * 0.74f), a);

        // renderRoundedQuad2 corner order is (top-right, bottom-right, top-left, bottom-left)
        renderRoundedQuad2(context, topRight, bottomRight, topLeft, bottomLeft, x, y, x + width, y + height, radius);

        // milky frost veil over the whole panel — the thing that makes it read as glass and not as paint
        int frost = (int) (26 * op * strength);
        renderRoundedQuad2(context,
                injectAlpha(Color.WHITE, frost), injectAlpha(Color.WHITE, (int) (frost * 0.35f)),
                injectAlpha(Color.WHITE, frost), injectAlpha(Color.WHITE, (int) (frost * 0.35f)),
                x, y, x + width, y + height, radius);

        float inset = Math.min(radius, width / 2f);

        // broad sheen coming down from the top, plus a softer one sliding in from the left edge
        float sheen = Math.min(height * 0.55f, height - 1f);
        if (sheen > 0.5f)
            verticalGradient(context, x + inset * 0.5f, y + 0.5f, x + width - inset * 0.5f, y + sheen,
                    injectAlpha(Color.WHITE, (int) (48 * op * strength)), injectAlpha(Color.WHITE, 0));

        float wash = Math.min(width * 0.45f, width - 1f);
        if (wash > 0.5f)
            horizontalGradient(context, x + 0.5f, y + inset * 0.5f, x + wash, y + height - inset * 0.5f,
                    injectAlpha(Color.WHITE, (int) (20 * op * strength)), injectAlpha(Color.WHITE, 0));

        // hairline rim: bright on top, visible on the sides, faint underneath
        float lineWidth = width - inset * 2f;
        if (lineWidth > 0f) {
            drawRect(context, x + inset, y, lineWidth, 0.5f, injectAlpha(Color.WHITE, (int) (110 * op * strength)));
            drawRect(context, x + inset, y + height - 0.5f, lineWidth, 0.5f, injectAlpha(Color.WHITE, (int) (26 * op * strength)));
        }
        float vInset = Math.min(radius, height / 2f);
        float sideHeight = height - vInset * 2f;
        if (sideHeight > 0f) {
            drawRect(context, x, y + vInset, 0.5f, sideHeight, injectAlpha(Color.WHITE, (int) (46 * op * strength)));
            drawRect(context, x + width - 0.5f, y + vInset, 0.5f, sideHeight, injectAlpha(Color.WHITE, (int) (26 * op * strength)));
        }
    }

    public static void drawHudBase(DrawContext context, float x, float y, float width, float height, float radius, float alpha) {
        hudBaseFill(context, x, y, width, height, radius, alpha);
    }

    public static void drawGuiBase(DrawContext context, float x, float y, float width, float height, float radius, float opacity) {
        hudBaseFill(context, x, y, width, height, radius, opacity);
    }

    public static void drawMainMenuShader(DrawContext context, float x, float y, float width, float height) {
        // INTERIM: MAIN_MENU_PROGRAM pending migration; flat dark backdrop.
        drawRect(context, x, y, width, height, new Color(18, 18, 22, 255));
    }

    /* ------------------------------------------------------------------ */
    /* Misc shapes                                                         */
    /* ------------------------------------------------------------------ */

    public static void drawOrbiz(DrawContext context, float z, final double r, Color c) {
        Matrix3x2f pose = GuiRender.pose(context);
        int argb = new Color(c.getRed() / 255f, c.getGreen() / 255f, c.getBlue() / 255f, 0.4f).getRGB();
        List<double[]> pts = new ArrayList<>();
        for (int i = 0; i <= 20; i++) {
            pts.add(new double[]{Math.sin(((i * 56.548656f) / 180f)) * r, Math.cos(((i * 56.548656f) / 180f)) * r});
        }
        GuiRender.quads(context, currentClip(), vc -> {
            double[] o = pts.get(0);
            for (int i = 1; i < pts.size() - 1; i++) {
                double[] a = pts.get(i);
                double[] b = pts.get(i + 1);
                GuiRender.tri(vc, pose, (float) o[0], (float) o[1], (float) a[0], (float) a[1], (float) b[0], (float) b[1], argb);
            }
        });
    }

    public static void drawStar(DrawContext context, Color c, float scale) {
        renderGradientTexture(context, TextureStorage.star, 0, 0, scale, scale, 0, 0, 128, 128, 128, 128, c, c, c, c, true);
    }

    public static void drawHeart(DrawContext context, Color c, float scale) {
        renderGradientTexture(context, TextureStorage.heart, 0, 0, scale, scale, 0, 0, 128, 128, 128, 128, c, c, c, c, true);
    }

    public static void drawBloom(DrawContext context, Color c, float scale) {
        renderGradientTexture(context, TextureStorage.firefly, 0, 0, scale, scale, 0, 0, 128, 128, 128, 128, c, c, c, c, true);
    }

    public static void drawBubble(DrawContext context, float angle, float factor) {
        Matrix3x2fStack ms = context.getMatrices();
        ms.pushMatrix();
        ms.rotate((float) Math.toRadians(angle));
        float scale = factor * 2f;
        renderGradientTexture(context, TextureStorage.bubble, -scale / 2, -scale / 2, scale, scale, 0, 0, 128, 128, 128, 128,
                applyOpacity(HudEditor.getColor(270), 1f - factor), applyOpacity(HudEditor.getColor(0), 1f - factor),
                applyOpacity(HudEditor.getColor(180), 1f - factor), applyOpacity(HudEditor.getColor(90), 1f - factor));
        ms.popMatrix();
    }

    public static void drawLine(DrawContext context, float x, float y, float x1, float y1, int color) {
        Matrix3x2f pose = GuiRender.pose(context);
        // 1px line as a thin quad (the old DEBUG_LINE_STRIP path is gone).
        float dx = x1 - x;
        float dy = y1 - y;
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len == 0) return;
        float nx = -dy / len * 0.5f;
        float ny = dx / len * 0.5f;
        GuiRender.quads(context, currentClip(), vc ->
                GuiRender.quad(vc, pose, x + nx, y + ny, x1 + nx, y1 + ny, x1 - nx, y1 - ny, x - nx, y - ny, color, color, color, color));
    }

    /** Solid line of arbitrary width between two SCREEN points, drawn as a single quad. */
    public static void drawThickLine(DrawContext context, float x, float y, float x1, float y1, int color, float width) {
        Matrix3x2f pose = GuiRender.pose(context);
        float dx = x1 - x, dy = y1 - y;
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len == 0) return;
        float half = Math.max(0.5f, width) / 2f;
        float nx = -dy / len * half, ny = dx / len * half;
        GuiRender.quads(context, currentClip(), vc ->
                GuiRender.quad(vc, pose, x + nx, y + ny, x1 + nx, y1 + ny, x1 - nx, y1 - ny, x - nx, y - ny, color, color, color, color));
    }

    /**
     * Glowing line between two SCREEN points. Drawn as parallel bands whose alpha peaks in the middle and
     * falls off quadratically towards the edges (so it reads as a beam rather than a hard bar), with an
     * independent colour at each end for a gradient along its length.
     *
     * @param width total beam width in scaled screen pixels
     */
    public static void drawGlowLine(DrawContext context, float ax, float ay, float bx, float by, Color start, Color end, float width) {
        float dx = bx - ax, dy = by - ay;
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len < 0.01f || width <= 0f) return;
        float px = -dy / len, py = dx / len; // perpendicular unit vector

        Matrix3x2f pose = GuiRender.pose(context);
        final int bands = (int) MathHelper.clamp(width * 2f, 2f, 48f);
        final float w = width;
        GuiRender.quads(context, currentClip(), vc -> {
            for (int i = 0; i < bands; i++) {
                float o0 = (i / (float) bands - 0.5f) * w;
                float o1 = ((i + 1) / (float) bands - 0.5f) * w;
                float edge = Math.abs((i + 0.5f) / bands - 0.5f) * 2f; // 0 at centre, 1 at the edges
                float f = (1f - edge) * (1f - edge);
                int s = injectAlpha(start, (int) (start.getAlpha() * f)).getRGB();
                int e = injectAlpha(end, (int) (end.getAlpha() * f)).getRGB();
                GuiRender.quad(vc, pose,
                        ax + px * o0, ay + py * o0,
                        bx + px * o0, by + py * o0,
                        bx + px * o1, by + py * o1,
                        ax + px * o1, ay + py * o1,
                        s, e, e, s);
                // and the opposite winding: the GUI pipeline culls back faces, and which way round these quads
                // wind depends on the line's direction - so one way of the two simply vanished (Tracers)
                GuiRender.quad(vc, pose,
                        ax + px * o1, ay + py * o1,
                        bx + px * o1, by + py * o1,
                        bx + px * o0, by + py * o0,
                        ax + px * o0, ay + py * o0,
                        s, e, e, s);
            }
        });
    }

    //http://www.java2s.com/example/java/2d-graphics/check-if-a-color-is-more-dark-than-light.html
    public static boolean isDark(Color color) {
        return isDark(color.getRed() / 255.0f, color.getGreen() / 255.0f, color.getBlue() / 255.0f);
    }

    public static boolean isDark(float r, float g, float b) {
        return colorDistance(r, g, b, 0f, 0f, 0f) < colorDistance(r, g, b, 1f, 1f, 1f);
    }

    public static float colorDistance(float r1, float g1, float b1, float r2, float g2, float b2) {
        float a = r2 - r1;
        float b = g2 - g1;
        float c = b2 - b1;
        return (float) Math.sqrt(a * a + b * b + c * c);
    }

    public static void initShaders() {
    }

    public static @NotNull Color getColor(@NotNull Color start, @NotNull Color end, float progress, boolean smooth) {
        if (!smooth)
            return progress >= 0.95 ? end : start;

        final int rDiff = end.getRed() - start.getRed();
        final int gDiff = end.getGreen() - start.getGreen();
        final int bDiff = end.getBlue() - start.getBlue();
        final int aDiff = end.getAlpha() - start.getAlpha();

        return new Color(
                fixColorValue(start.getRed() + (int) (rDiff * progress)),
                fixColorValue(start.getGreen() + (int) (gDiff * progress)),
                fixColorValue(start.getBlue() + (int) (bDiff * progress)),
                fixColorValue(start.getAlpha() + (int) (aDiff * progress)));
    }

    private static int fixColorValue(int colorVal) {
        return colorVal > 255 ? 255 : Math.max(colorVal, 0);
    }

    public static class BlurredShadow {
        Texture id;

        public BlurredShadow(BufferedImage bufferedImage) {
            this.id = new Texture("texture/remote/" + RandomStringUtils.randomAlphanumeric(16));
            registerBufferedImageTexture(id, bufferedImage);
        }

        public Identifier getId() {
            return id.getId();
        }

        public void bind() {
            // retained for source compatibility; texture binding is now handled at submit time.
        }
    }

    public record Rectangle(float x, float y, float x1, float y1) {
        public boolean contains(double x, double y) {
            return x >= this.x && x <= x1 && y >= this.y && y <= y1;
        }
    }

    /* 1.21.6+ stub overloads for removed immediate-mode helpers (geometry disabled until RenderPipeline reimpl) */
    public static void endBuilding(Object... a) {}
    public static void setRectPoints(Object... a) {}
    public static void renderGradientTextureInternal(Object... a) {}
    public static void renderRoundedQuadInternal(Object... a) {}
    public static void renderTexture(Object... a) {}
    public static void renderGradientTexture(Object... a) {}
}
