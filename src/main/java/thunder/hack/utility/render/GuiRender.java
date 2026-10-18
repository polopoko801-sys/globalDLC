package thunder.hack.utility.render;

import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.DestFactor;
import com.mojang.blaze3d.platform.SourceFactor;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.ScreenRect;
import net.minecraft.client.gui.render.state.SimpleGuiElementRenderState;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.texture.AbstractTexture;
import net.minecraft.client.texture.TextureSetup;
import net.minecraft.util.Identifier;
import org.joml.Matrix3x2f;
import org.joml.Vector2f;

import java.util.function.Consumer;

/**
 * 1.21.6+ rendering foundation. The old immediate-mode path (thunder.hack.utility.render.RSCompat.setShader +
 * BufferRenderer.drawWithGlobalProgram) was removed. Instead we submit a custom
 * {@link SimpleGuiElementRenderState} to the DrawContext's GuiRenderState; vanilla's
 * GuiRenderer batches elements by pipeline+texture+scissor and binds all UBOs/projection.
 *
 * We reuse vanilla {@link RenderPipelines#GUI} (POSITION_COLOR / QUADS) so the standard GUI
 * uniforms are guaranteed present. Arbitrary triangles are emitted as quads with a degenerate
 * 4th vertex (a,b,c,c); real quads use all four. Triangle lists concatenate within a QUADS
 * batch, so multiple draws sharing the pipeline render correctly.
 *
 * The 2D pose (Matrix3x2f from DrawContext.getMatrices()) is baked into vertex positions here,
 * since the SimpleGuiElementRenderState interface does not expose a pose.
 */
public final class GuiRender {

    public record Element(RenderPipeline pipeline, TextureSetup textureSetup,
                          Consumer<VertexConsumer> builder, ScreenRect bounds,
                          ScreenRect scissor) implements SimpleGuiElementRenderState {
        @Override
        public void setupVertices(VertexConsumer vc) {
            builder.accept(vc);
        }

        @Override
        public ScreenRect scissorArea() {
            return scissor;
        }
    }

    /**
     * Additive twin of {@link RenderPipelines#GUI_TEXTURED}. Our particle sprites are opaque PNGs on a
     * black background (alpha 255 everywhere) — they were drawn with additive blending in the old
     * immediate-mode path, which is what hides the black (adding zero leaves the backdrop alone).
     * Vanilla's GUI_TEXTURED does not blend, so those sprites came out as black squares.
     */
    public static final RenderPipeline GUI_TEXTURED_ADDITIVE = additiveOf(RenderPipelines.GUI_TEXTURED, "gui_textured_additive");

    /** Untextured additive twin of {@link RenderPipelines#GUI} — see {@link #quadsAdditive}. */
    public static final RenderPipeline GUI_ADDITIVE = additiveOf(RenderPipelines.GUI, "gui_additive");

    /**
     * Alpha-blended twin of {@link RenderPipelines#GUI_TEXTURED}. Needed for the backdrop-blur taps, which
     * stack translucent copies of the frame snapshot on top of each other.
     */
    public static final RenderPipeline GUI_TEXTURED_TRANSLUCENT =
            blendOf(RenderPipelines.GUI_TEXTURED, "gui_textured_translucent", BlendFunction.TRANSLUCENT);

    /**
     * Multiply blend: {@code result = dst * src}. With a WHITE quad that squares whatever is already on
     * screen ({@code dst * dst}) - darks deepen and the channels pull apart, which is contrast and
     * saturation without a custom shader (impossible on 1.21.6+). Used by ColorGrading.
     */
    public static final RenderPipeline GUI_MULTIPLY = blendOf(RenderPipelines.GUI, "gui_multiply",
            new BlendFunction(SourceFactor.DST_COLOR, DestFactor.ZERO));

    /** Additive twin of {@link RenderPipelines#GUI_TEXTURED} is above; this is its translucent/textured pair. */
    public static void quadsMultiply(DrawContext ctx, ScreenRect scissor, Consumer<VertexConsumer> builder) {
        ScreenRect bounds = new ScreenRect(0, 0, ctx.getScaledWindowWidth(), ctx.getScaledWindowHeight());
        ctx.state.addSimpleElement(new Element(GUI_MULTIPLY, TextureSetup.empty(), builder, bounds, scissor));
    }

    private static RenderPipeline additiveOf(RenderPipeline base, String name) {
        return blendOf(base, name, BlendFunction.ADDITIVE);
    }

    private static RenderPipeline blendOf(RenderPipeline base, String name, BlendFunction blend) {
        RenderPipeline.Builder builder = RenderPipeline.builder()
                .withLocation(Identifier.of("gombal", "pipeline/" + name))
                .withVertexShader(base.getVertexShader())
                .withFragmentShader(base.getFragmentShader())
                .withVertexFormat(base.getVertexFormat(), base.getVertexFormatMode())
                .withDepthTestFunction(base.getDepthTestFunction())
                .withCull(base.isCull())
                .withDepthWrite(false)
                .withBlend(blend);
        base.getSamplers().forEach(builder::withSampler);
        for (RenderPipeline.UniformDescription uniform : base.getUniforms()) {
            if (uniform.textureFormat() != null) builder.withUniform(uniform.name(), uniform.type(), uniform.textureFormat());
            else builder.withUniform(uniform.name(), uniform.type());
        }
        return builder.build();
    }

    public static void submit(DrawContext ctx, RenderPipeline pipeline, TextureSetup tex, Consumer<VertexConsumer> builder) {
        ScreenRect bounds = new ScreenRect(0, 0, ctx.getScaledWindowWidth(), ctx.getScaledWindowHeight());
        ctx.state.addSimpleElement(new Element(pipeline, tex, builder, bounds, null));
    }

    /** Submit untextured colored geometry (emit quads via the rect/tri helpers). */
    public static void quads(DrawContext ctx, Consumer<VertexConsumer> builder) {
        submit(ctx, RenderPipelines.GUI, TextureSetup.empty(), builder);
    }

    /** Submit untextured colored geometry clipped to the given scissor (nullable = no clip). */
    public static void quads(DrawContext ctx, ScreenRect scissor, Consumer<VertexConsumer> builder) {
        ScreenRect bounds = new ScreenRect(0, 0, ctx.getScaledWindowWidth(), ctx.getScaledWindowHeight());
        ctx.state.addSimpleElement(new Element(RenderPipelines.GUI, TextureSetup.empty(), builder, bounds, scissor));
    }

    /**
     * Same as {@link #quads}, but additively blended. Plain alpha blending can only ever darken towards the
     * geometry's own colour, so anything meant to GLOW (it has to brighten what is behind it, and stack
     * where layers overlap) has to go through here.
     */
    public static void quadsAdditive(DrawContext ctx, ScreenRect scissor, Consumer<VertexConsumer> builder) {
        ScreenRect bounds = new ScreenRect(0, 0, ctx.getScaledWindowWidth(), ctx.getScaledWindowHeight());
        ctx.state.addSimpleElement(new Element(GUI_ADDITIVE, TextureSetup.empty(), builder, bounds, scissor));
    }

    /**
     * Submit textured colored geometry (vanilla GUI_TEXTURED = POSITION_TEX_COLOR). Build vertices
     * with {@link #vertexTex}. The texture is resolved through the texture manager and bound by the
     * vanilla GuiRenderer. {@code scissor} may be null for no clip.
     */
    public static void textured(DrawContext ctx, Identifier texture, ScreenRect scissor, Consumer<VertexConsumer> builder) {
        submitTextured(ctx, RenderPipelines.GUI_TEXTURED, texture, scissor, builder);
    }

    /** Same as {@link #textured}, but additively blended — for sprites that carry a black backdrop. */
    public static void texturedAdditive(DrawContext ctx, Identifier texture, ScreenRect scissor, Consumer<VertexConsumer> builder) {
        submitTextured(ctx, GUI_TEXTURED_ADDITIVE, texture, scissor, builder);
    }

    /**
     * Textured submit against a raw GPU texture instead of a registered Identifier — used for the
     * framebuffer snapshot behind the glass panels, which never lives in the texture manager.
     */
    public static void texturedView(DrawContext ctx, RenderPipeline pipeline,
                                    com.mojang.blaze3d.textures.GpuTextureView view,
                                    net.minecraft.client.gl.GpuSampler sampler,
                                    ScreenRect scissor, Consumer<VertexConsumer> builder) {
        ScreenRect bounds = new ScreenRect(0, 0, ctx.getScaledWindowWidth(), ctx.getScaledWindowHeight());
        ctx.state.addSimpleElement(new Element(pipeline, TextureSetup.of(view, sampler), builder, bounds, scissor));
    }

    private static net.minecraft.client.gl.GpuSampler linearSampler;

    /**
     * Bilinear sampler for everything this class draws. A NativeImageBackedTexture comes with a NEAREST
     * sampler, which is right for vanilla pixel art and wrong for ours: font glyph pages, covers and icons are
     * drawn scaled (the menu clock, matrix-scaled HUD text) and nearest turned every one of them into blocks.
     */
    private static net.minecraft.client.gl.GpuSampler linearSampler(AbstractTexture fallback) {
        if (linearSampler == null) {
            try {
                linearSampler = com.mojang.blaze3d.systems.RenderSystem.getDevice().createSampler(
                        com.mojang.blaze3d.textures.AddressMode.CLAMP_TO_EDGE, com.mojang.blaze3d.textures.AddressMode.CLAMP_TO_EDGE,
                        com.mojang.blaze3d.textures.FilterMode.LINEAR, com.mojang.blaze3d.textures.FilterMode.LINEAR,
                        0, java.util.OptionalDouble.empty());
            } catch (Throwable t) {
                return fallback.getSampler();
            }
        }
        return linearSampler;
    }

    /** Textures that must stay pixel-sharp when scaled (pixel-art wallpapers): drawn with their own NEAREST sampler. */
    private static final java.util.Set<Identifier> NEAREST = java.util.concurrent.ConcurrentHashMap.newKeySet();

    public static void preferNearest(Identifier texture) {
        NEAREST.add(texture);
    }

    private static void submitTextured(DrawContext ctx, RenderPipeline pipeline, Identifier texture, ScreenRect scissor, Consumer<VertexConsumer> builder) {
        AbstractTexture tex = MinecraftClient.getInstance().getTextureManager().getTexture(texture);
        TextureSetup setup = TextureSetup.of(tex.getGlTextureView(),
                NEAREST.contains(texture) ? tex.getSampler() : linearSampler(tex));
        ScreenRect bounds = new ScreenRect(0, 0, ctx.getScaledWindowWidth(), ctx.getScaledWindowHeight());
        ctx.state.addSimpleElement(new Element(pipeline, setup, builder, bounds, scissor));
    }

    /** Emit one pose-transformed textured+colored vertex (POSITION_TEX_COLOR attribute order). */
    public static void vertexTex(VertexConsumer vc, Matrix3x2f pose, float x, float y, float u, float v, int argb) {
        Vector2f p = pose.transformPosition(new Vector2f(x, y));
        vc.vertex(p.x, p.y, 0f).texture(u, v).color(argb);
    }

    /** Current 2D pose (a copy, safe to keep for deferred rendering). */
    public static Matrix3x2f pose(DrawContext ctx) {
        return new Matrix3x2f(ctx.getMatrices());
    }

    /** Emit one pose-transformed colored vertex. */
    public static void vertex(VertexConsumer vc, Matrix3x2f pose, float x, float y, int argb) {
        Vector2f v = pose.transformPosition(new Vector2f(x, y));
        vc.vertex(v.x, v.y, 0f).color(argb);
    }

    /** Emit an axis-aligned colored quad (counter-clockwise). */
    public static void quad(VertexConsumer vc, Matrix3x2f pose, float x1, float y1, float x2, float y2, int argb) {
        vertex(vc, pose, x1, y1, argb);
        vertex(vc, pose, x1, y2, argb);
        vertex(vc, pose, x2, y2, argb);
        vertex(vc, pose, x2, y1, argb);
    }

    /** Emit an arbitrary colored quad from four corners. */
    public static void quad(VertexConsumer vc, Matrix3x2f pose,
                            float ax, float ay, float bx, float by,
                            float cx, float cy, float dx, float dy,
                            int ca, int cb, int cc, int cd) {
        vertex(vc, pose, ax, ay, ca);
        vertex(vc, pose, bx, by, cb);
        vertex(vc, pose, cx, cy, cc);
        vertex(vc, pose, dx, dy, cd);
    }

    /** Emit a triangle as a degenerate quad (a,b,c,c) so it fits the QUADS pipeline. */
    public static void tri(VertexConsumer vc, Matrix3x2f pose,
                           float ax, float ay, float bx, float by, float cx, float cy, int argb) {
        vertex(vc, pose, ax, ay, argb);
        vertex(vc, pose, bx, by, argb);
        vertex(vc, pose, cx, cy, argb);
        vertex(vc, pose, cx, cy, argb);
    }

    private GuiRender() {
    }
}
