package thunder.hack.utility.render;

import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.DepthTestFunction;
import com.mojang.blaze3d.platform.DestFactor;
import com.mojang.blaze3d.platform.SourceFactor;
import com.mojang.blaze3d.vertex.VertexFormat;

import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.RenderLayers;
import net.minecraft.client.render.RenderSetup;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.util.Identifier;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.HashMap;
import java.util.Map;

import static thunder.hack.features.modules.Module.mc;

/**
 * 1.21.11 world-space rendering foundation.
 *
 * The old immediate path (thunder.hack.utility.render.RSCompat.setShader + Tessellator + BufferRenderer.drawWithGlobalProgram)
 * was removed in 1.21.6+. We route geometry through vanilla world {@link RenderLayer}s using the shared
 * entity {@link VertexConsumerProvider.Immediate}, which binds the pipeline, default uniforms
 * (modelview / projection) and flushes on {@link VertexConsumerProvider.Immediate#draw(RenderLayer)}.
 *
 * Only three off-the-shelf world layers expose usable POSITION_COLOR formats, so callers map their
 * draw mode onto them:
 * <ul>
 *   <li>QUADS         &rarr; {@link #QUADS} ({@code RenderLayers.debugQuads()})</li>
 *   <li>LINES         &rarr; {@link #LINES} ({@code RenderLayers.lines()}, POSITION_COLOR_NORMAL)</li>
 *   <li>TRIANGLE_FAN  &rarr; {@link #TRIANGLE_FAN} ({@code RenderLayers.debugTriangleFan()})</li>
 * </ul>
 * Strips have no vanilla world layer, so callers convert: TRIANGLE_STRIP &rarr; quads, and
 * DEBUG_LINE_STRIP &rarr; consecutive line segments.
 *
 * Since {@code VertexConsumer.vertex(Matrix4f, ...)} was removed, the position matrix is baked into
 * vertex coordinates here via {@link Matrix4f#transformPosition}.
 */
public final class Render3DUtil {
    private Render3DUtil() {
    }

    // vanilla debugQuads (DEBUG_QUADS pipeline) has NO alpha blending, so our translucent fill colours
    // (e.g. StorageEsp 0x8800FF00) rendered as fully SOLID boxes. Build a POSITION_COLOR/QUADS pipeline
    // that keeps debugQuads' vertex format but adds TRANSLUCENT blending, so fill alpha is respected.
    private static final RenderPipeline TH_QUADS_PIPELINE = RenderPipeline.builder(RenderPipelines.POSITION_COLOR_SNIPPET)
            .withLocation(Identifier.of("gombal", "pipeline/quads_translucent"))
            .withVertexFormat(RenderPipelines.DEBUG_QUADS.getVertexFormat(), RenderPipelines.DEBUG_QUADS.getVertexFormatMode())
            .withBlend(BlendFunction.TRANSLUCENT)
            .withCull(false)
            .build();

    public static final RenderLayer QUADS = RenderLayer.of("th_quads_translucent", RenderSetup.builder(TH_QUADS_PIPELINE).build());

    /** As {@link #QUADS}, with the depth test off so the geometry draws through what is in front of it. */
    private static final RenderPipeline TH_QUADS_PIPELINE_NO_DEPTH = RenderPipeline.builder(RenderPipelines.POSITION_COLOR_SNIPPET)
            .withLocation(Identifier.of("gombal", "pipeline/quads_translucent_nodepth"))
            .withVertexFormat(RenderPipelines.DEBUG_QUADS.getVertexFormat(), RenderPipelines.DEBUG_QUADS.getVertexFormatMode())
            .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
            .withDepthWrite(false)
            .withBlend(BlendFunction.TRANSLUCENT)
            .withCull(false)
            .build();

    public static final RenderLayer QUADS_NO_DEPTH = RenderLayer.of("th_quads_translucent_nd",
            RenderSetup.builder(TH_QUADS_PIPELINE_NO_DEPTH).build());
    public static final RenderLayer LINES = RenderLayers.lines();
    public static final RenderLayer TRIANGLE_FAN = RenderLayers.debugTriangleFan();

    private static final Map<Identifier, RenderLayer> TEXTURED_LAYERS = new HashMap<>();

    // Custom POSITION_TEX_COLOR pipeline WITH depth testing. The stock POSITION_TEX_COLOR_CELESTIAL
    // (sun/moon) has NO_DEPTH_TEST, so world billboards drawn with it ignore depth and paint over the
    // first-person hand item (e.g. JumpCircle on jump turned the held sword black). LEQUAL depth test +
    // no depth write makes these billboards respect terrain/hand occlusion while still blending as
    // translucent sprites. Vertex format/shader are inherited from the celestial pipeline's snippet.
    private static final RenderPipeline TH_TEXTURED_PIPELINE = spritePipeline(
            "pipeline/textured_billboard", DepthTestFunction.LEQUAL_DEPTH_TEST);

    /**
     * The through-walls twin, built the SAME way as the depth-tested one - only the depth function
     * differs.
     *
     * <p>It used to be the stock {@code POSITION_TEX_COLOR_CELESTIAL} pipeline instead, which made
     * {@code throughWalls} quietly change the blend mode and the vertex format as well as the depth test:
     * the two halves of {@link #spriteLayer} were not the same renderer with one switch flipped, they
     * were two different renderers, and a sprite could take its tint through one and ignore it through
     * the other. Sharing one builder is what makes the switch mean only what it says.
     */
    private static final RenderPipeline TH_TEXTURED_PIPELINE_NODEPTH = spritePipeline(
            "pipeline/textured_billboard_nodepth", DepthTestFunction.NO_DEPTH_TEST);

    /**
     * The one sprite pipeline, in its depth-tested and through-walls forms.
     *
     * <p>The vertex format is stated outright rather than copied off another pipeline. Copying is how a
     * sprite ends up silently untinted: if the format it inherits has no colour element the
     * {@code .color()} call is dropped on the floor, the shader's {@code vertexColor} stays at its
     * default white, and the picture renders perfectly - just always white, whatever colour was asked
     * for. POSITION_TEXTURE_COLOR is what {@code core/position_tex_color} reads, so that is what is
     * declared here.
     *
     * <p>Blending is TRANSLUCENT, which is why every sprite drawn through this carries a real alpha
     * channel instead of being white on black and relying on additive to hide the black.
     */
    private static RenderPipeline spritePipeline(String path, DepthTestFunction depth) {
        return RenderPipeline.builder(RenderPipelines.POSITION_TEX_COLOR_SNIPPET)
                .withLocation(Identifier.of("gombal", path))
                .withVertexFormat(VertexFormats.POSITION_TEXTURE_COLOR, VertexFormat.DrawMode.QUADS)
                .withDepthTestFunction(depth)
                .withDepthWrite(false)
                .withCull(false)
                .withBlend(BlendFunction.TRANSLUCENT)
                .build();
    }

    private static final Map<Identifier, RenderLayer> TEXTURED_LAYERS_NODEPTH = new HashMap<>();

    /** Depth-tested textured billboard layer wrapping {@code core/position_tex_color}; cached per texture. */
    private static net.minecraft.client.gl.GpuSampler smoothSampler;

    /**
     * Bilinear, repeating along U. Every sprite we draw in the world is painted art (sky, clouds, wings,
     * trails), and the textures' own NEAREST sampler turned each texel into a hard square once magnified -
     * a 2048px sky across the whole dome was visibly pixelated. Repeat on U lets the sky's left and right
     * edges filter into each other (no seam) and lets trails tile along their length.
     */
    private static net.minecraft.client.gl.GpuSampler smoothSampler() {
        if (smoothSampler == null)
            smoothSampler = com.mojang.blaze3d.systems.RenderSystem.getDevice().createSampler(
                    com.mojang.blaze3d.textures.AddressMode.REPEAT, com.mojang.blaze3d.textures.AddressMode.CLAMP_TO_EDGE,
                    com.mojang.blaze3d.textures.FilterMode.LINEAR, com.mojang.blaze3d.textures.FilterMode.LINEAR,
                    1, java.util.OptionalDouble.empty());
        return smoothSampler;
    }

    public static RenderLayer texturedLayer(Identifier texture) {
        return TEXTURED_LAYERS.computeIfAbsent(texture, id -> RenderLayer.of("th_textured_" + id.getPath(),
                RenderSetup.builder(TH_TEXTURED_PIPELINE)
                        .texture("Sampler0", id, Render3DUtil::smoothSampler)
                        .build()));
    }

    /** As {@link #texturedLayer}, with the depth test off so the sprite draws through what is in front. */
    public static RenderLayer texturedLayerNoDepth(Identifier texture) {
        return TEXTURED_LAYERS_NODEPTH.computeIfAbsent(texture, id -> RenderLayer.of("th_textured_nd_" + id.getPath(),
                RenderSetup.builder(TH_TEXTURED_PIPELINE_NODEPTH)
                        .texture("Sampler0", id, Render3DUtil::smoothSampler)
                        .build()));
    }

    /**
     * Additive textured world geometry, no depth test, no culling.
     *
     * <p>This is the layer for anything drawn from an IMAGE in the world - glow rings, trail ribbons,
     * sparks. Our sprite PNGs are white-on-black with no alpha channel, so additive is what makes the
     * black backdrop disappear; the {@code SRC_ALPHA} source factor keeps the vertex alpha meaningful, so
     * a sprite can still fade out. Culling is off because a flat quad lying on the ground is seen from
     * both sides, and depth is off because these are meant to read through what is in front of them.
     */
    /**
     * Layer for an image drawn in the world. {@code throughWalls} picks the no-depth variant.
     *
     * <p>Both of these are the pipelines this client already draws textures with and is known to draw
     * them correctly: the no-depth one is what the target-ESP fireflies use, the depth-tested one is what
     * JumpCircle used before. A pair of bespoke additive pipelines built here instead rendered nothing at
     * all, and chasing why was not worth it when two proven ones were already sitting in this file.
     *
     * <p>That means ordinary translucent blending rather than additive, which is why the sprites carry a
     * real alpha channel instead of being white-on-black and relying on adding the black away. Alpha is
     * the better answer anyway: it composites correctly over anything, additive only over dark.
     */
    /**
     * Solid skin geometry (KillEffect's player figures): depth-tested AND depth-writing, so the near faces of a
     * cuboid hide its far faces like a real model. The sprite pipelines below never write depth - fine for flat
     * glows, but a box drawn through them shows its back faces through the front (an inside-out head).
     */
    private static final RenderPipeline TH_SKIN_PIPELINE = RenderPipeline.builder(RenderPipelines.POSITION_TEX_COLOR_SNIPPET)
            .withLocation(Identifier.of("gombal", "pipeline/skin_solid"))
            .withVertexFormat(VertexFormats.POSITION_TEXTURE_COLOR, VertexFormat.DrawMode.QUADS)
            .withDepthTestFunction(DepthTestFunction.LEQUAL_DEPTH_TEST)
            .withDepthWrite(true)
            .withCull(false)
            .withBlend(BlendFunction.TRANSLUCENT)
            .build();

    private static final Map<Identifier, RenderLayer> SKIN_LAYERS = new HashMap<>();

    /** A skin (or any pixel-art texture) on solid, depth-writing world geometry, with the texture's own sampler. */
    public static RenderLayer skinLayer(Identifier texture) {
        return SKIN_LAYERS.computeIfAbsent(texture, id -> RenderLayer.of("th_skin_" + id.getPath(),
                RenderSetup.builder(TH_SKIN_PIPELINE)
                        .texture("Sampler0", id)
                        .build()));
    }

    private static final Map<Identifier, RenderLayer> PIXEL_LAYERS = new HashMap<>();

    /** As {@link #texturedLayer}, but with the texture's own (nearest) sampler - for pixel art such as skins. */
    public static RenderLayer pixelLayer(Identifier texture) {
        return PIXEL_LAYERS.computeIfAbsent(texture, id -> RenderLayer.of("th_textured_px_" + id.getPath(),
                RenderSetup.builder(TH_TEXTURED_PIPELINE)
                        .texture("Sampler0", id)
                        .build()));
    }

    public static RenderLayer spriteLayer(Identifier texture, boolean throughWalls) {
        return throughWalls ? texturedLayerNoDepth(texture) : texturedLayer(texture);
    }

    public static VertexConsumerProvider.Immediate provider() {
        return mc.getBufferBuilders().getEntityVertexConsumers();
    }

    public static VertexConsumer buffer(RenderLayer layer) {
        return provider().getBuffer(layer);
    }

    public static void flush(RenderLayer layer) {
        provider().draw(layer);
    }

    public static void flush() {
        provider().draw();
    }

    /** Emit one matrix-baked POSITION_COLOR vertex (ARGB). */
    public static void vertex(VertexConsumer vc, Matrix4f m, float x, float y, float z, int argb) {
        Vector3f v = m.transformPosition(x, y, z, new Vector3f());
        vc.vertex(v.x, v.y, v.z).color(argb);
    }

    /** Emit one matrix-baked POSITION_TEXTURE_COLOR vertex (ARGB). */
    public static void vertexTex(VertexConsumer vc, Matrix4f m, float x, float y, float z, float u, float texV, int argb) {
        Vector3f v = m.transformPosition(x, y, z, new Vector3f());
        vc.vertex(v.x, v.y, v.z).texture(u, texV).color(argb);
    }

    /** Emit a 4-corner quad with a single color (for the {@link #QUADS} layer). */
    public static void quad(VertexConsumer vc, Matrix4f m,
                            float x1, float y1, float z1, float x2, float y2, float z2,
                            float x3, float y3, float z3, float x4, float y4, float z4, int argb) {
        vertex(vc, m, x1, y1, z1, argb);
        vertex(vc, m, x2, y2, z2, argb);
        vertex(vc, m, x3, y3, z3, argb);
        vertex(vc, m, x4, y4, z4, argb);
    }

    /** Emit one line segment for the {@link #LINES} layer (POSITION_COLOR_NORMAL) at the default width. */
    public static void line(VertexConsumer vc, Matrix4f m,
                            float x1, float y1, float z1, float x2, float y2, float z2, int argb) {
        line(vc, m, x1, y1, z1, x2, y2, z2, argb, LINE_WIDTH);
    }

    /** Emit one line segment with an explicit per-vertex width. */
    public static void line(VertexConsumer vc, Matrix4f m,
                            float x1, float y1, float z1, float x2, float y2, float z2, int argb, float width) {
        Vector3f a = m.transformPosition(x1, y1, z1, new Vector3f());
        Vector3f b = m.transformPosition(x2, y2, z2, new Vector3f());
        float nx = b.x - a.x, ny = b.y - a.y, nz = b.z - a.z;
        float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (len == 0f) len = 1f;
        nx /= len;
        ny /= len;
        nz /= len;
        // 1.21.11: RenderLayers.lines() format is POSITION_COLOR_NORMAL_LINE_WIDTH — the new per-vertex
        // LINE_WIDTH element is mandatory (replaces the removed RenderSystem.lineWidth()). Omitting it
        // throws "Missing elements in vertex: LineWidth".
        vc.vertex(a.x, a.y, a.z).color(argb).normal(nx, ny, nz).lineWidth(width);
        vc.vertex(b.x, b.y, b.z).color(argb).normal(nx, ny, nz).lineWidth(width);
    }

    /** Per-vertex line width for the 1.21.11 LINES pipeline (1.0 = default thin GL line). */
    public static final float LINE_WIDTH = 1.5f;
}