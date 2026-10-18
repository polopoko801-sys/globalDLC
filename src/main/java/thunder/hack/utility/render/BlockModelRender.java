package thunder.hack.utility.render;

import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.DepthTestFunction;
import net.minecraft.block.BedBlock;
import net.minecraft.block.BlockState;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.EnderChestBlock;
import net.minecraft.block.ShulkerBoxBlock;
import net.minecraft.block.enums.BedPart;
import net.minecraft.block.enums.ChestType;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.model.ModelPart;
import net.minecraft.client.render.LightmapTextureManager;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.RenderLayers;
import net.minecraft.client.render.RenderSetup;
import net.minecraft.client.render.TexturedRenderLayers;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.block.BlockModelRenderer;
import net.minecraft.client.render.entity.model.EntityModelLayer;
import net.minecraft.client.render.entity.model.EntityModelLayers;
import net.minecraft.client.texture.SpriteAtlasTexture;
import net.minecraft.client.texture.SpriteHolder;
import net.minecraft.client.util.SpriteIdentifier;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.state.property.Properties;
import net.minecraft.util.DyeColor;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.RotationAxis;
import net.minecraft.util.math.Vec3d;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import thunder.hack.injection.accesors.IBakedModelManager;

import java.util.HashMap;
import java.util.Map;

import static thunder.hack.features.modules.Module.mc;

/**
 * The client's one real-block-model renderer: draws a {@link BlockState} with its actual Minecraft model
 * and texture in world space - stairs look like stairs, a chest looks like a chest, dirt looks like dirt.
 *
 * <p>Everything that wants a block drawn somewhere - the AutoBuild preview, Block ESP, Anchor ESP - goes
 * through here instead of growing its own copy.
 *
 * <p>It runs off the MatrixStack the 3D render hook hands out, the one that carries the camera rotation
 * exactly once, and only adds the camera-relative translation on top - the same thing every entity render
 * does. The model renderer takes a multiplicative tint but no alpha, so the vertex consumer is wrapped:
 * {@link AlphaConsumer} forces the alpha of every vertex colour and the translucent layer blends it.
 *
 * <p>Blocks whose looks live in a block <em>entity</em> (chests, shulkers, beds) have no baked model at
 * all - vanilla draws them from entity models - so those are handled explicitly below. Everything else is
 * a baked model, which is the overwhelming majority of the block list.
 */
public final class BlockModelRender {
    private BlockModelRender() {
    }

    /* ------------------------------------------------------------------ */
    /* Layers                                                              */
    /* ------------------------------------------------------------------ */

    /**
     * Same shader and vertex format as the stock entity-translucent pipeline, with the depth test turned
     * off - this is what makes a preview readable through a wall without turning it into a neon box.
     */
    private static final RenderPipeline THROUGH_WALLS_PIPELINE = RenderPipeline.builder(RenderPipelines.ENTITY_SNIPPET)
            .withLocation(Identifier.of("gombal", "pipeline/block_model_through_walls"))
            .withVertexFormat(RenderPipelines.ENTITY_TRANSLUCENT.getVertexFormat(),
                    RenderPipelines.ENTITY_TRANSLUCENT.getVertexFormatMode())
            .withBlend(BlendFunction.TRANSLUCENT)
            .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
            .withDepthWrite(false)
            .withCull(true)
            .build();

    private static final Map<Identifier, RenderLayer> THROUGH_WALLS_LAYERS = new HashMap<>();

    private static RenderLayer throughWallsLayer(Identifier texture) {
        return THROUGH_WALLS_LAYERS.computeIfAbsent(texture, id ->
                RenderLayer.of("th_block_model_tw_" + id.toUnderscoreSeparatedString(),
                        RenderSetup.builder(THROUGH_WALLS_PIPELINE)
                                .texture("Sampler0", id)
                                .useLightmap()
                                .useOverlay()
                                .translucent()
                                .build()));
    }

    private static RenderLayer layer(Identifier texture, boolean throughWalls) {
        return throughWalls ? throughWallsLayer(texture) : RenderLayers.entityTranslucent(texture);
    }

    /**
     * The atlas manager is the only {@link SpriteHolder} the client keeps, and an entity-model texture
     * cannot be resolved into a vertex consumer without one.
     */
    private static @Nullable SpriteHolder spriteHolder() {
        try {
            return ((IBakedModelManager) mc.getBakedModelManager()).thAtlasManager();
        } catch (Throwable ignored) {
            return null;
        }
    }

    /* ------------------------------------------------------------------ */
    /* Public API                                                          */
    /* ------------------------------------------------------------------ */

    /** How one block should be drawn. */
    public record Spec(@NotNull BlockState state, @NotNull BlockPos pos, int alpha, float scale,
                       float tintR, float tintG, float tintB, boolean throughWalls) {
        public Spec(@NotNull BlockState state, @NotNull BlockPos pos, int alpha) {
            this(state, pos, alpha, 1f, 1f, 1f, 1f, false);
        }
    }

    /**
     * Queues one block. Buffered - call {@link #flush()} once the batch is done or nothing shows up.
     *
     * @param alpha 0-255
     * @param scale 1 = the full block; slightly under keeps a preview off the faces of real blocks
     */
    public static void render(@NotNull MatrixStack matrices, @NotNull BlockState state, @NotNull BlockPos pos,
                              int alpha, float scale, float tintR, float tintG, float tintB, boolean throughWalls) {
        Vec3d cam = mc.getEntityRenderDispatcher().camera.getCameraPos();

        matrices.push();
        matrices.translate(pos.getX() - cam.x, pos.getY() - cam.y, pos.getZ() - cam.z);
        if (scale != 1f) {
            matrices.translate(0.5, 0.5, 0.5);
            matrices.scale(scale, scale, scale);
            matrices.translate(-0.5, -0.5, -0.5);
        }

        try {
            if (!renderBlockEntityModel(matrices, state, alpha, tintR, tintG, tintB, throughWalls))
                renderBakedModel(matrices, state, alpha, tintR, tintG, tintB, throughWalls);
        } catch (Throwable ignored) {
            // one unhappy model must never take the frame down with it
        }

        matrices.pop();
    }

    public static void render(@NotNull MatrixStack matrices, @NotNull Spec spec) {
        render(matrices, spec.state(), spec.pos(), spec.alpha(), spec.scale(),
                spec.tintR(), spec.tintG(), spec.tintB(), spec.throughWalls());
    }

    /** Pushes the batch to the GPU. Cheap when nothing was queued. */
    public static void flush() {
        provider().draw();
    }

    /** True when this state has something worth drawing - i.e. the preview will not be empty. */
    public static boolean hasModel(@NotNull BlockState state) {
        if (state.isAir()) return false;
        return true;
    }

    /* ------------------------------------------------------------------ */
    /* Baked models - stairs, slabs, doors, torches, dirt, stone, ...      */
    /* ------------------------------------------------------------------ */

    private static void renderBakedModel(MatrixStack matrices, BlockState state, int alpha,
                                         float r, float g, float b, boolean throughWalls) {
        VertexConsumer buffer = provider().getBuffer(layer(SpriteAtlasTexture.BLOCK_ATLAS_TEXTURE, throughWalls));

        BlockModelRenderer.render(matrices.peek(), new AlphaConsumer(buffer, alpha),
                mc.getBlockRenderManager().getModel(state), r, g, b,
                LightmapTextureManager.MAX_LIGHT_COORDINATE, OverlayTexture.DEFAULT_UV);
    }

    /* ------------------------------------------------------------------ */
    /* Block entity models - chest, ender chest, shulker, bed              */
    /* ------------------------------------------------------------------ */

    /** True for the handful of blocks vanilla draws from an entity model instead of a baked one. */
    public static boolean isBlockEntityModel(@NotNull BlockState state) {
        return state.getBlock() instanceof ChestBlock
                || state.getBlock() instanceof EnderChestBlock
                || state.getBlock() instanceof ShulkerBoxBlock
                || state.getBlock() instanceof BedBlock;
    }

    private static boolean renderBlockEntityModel(MatrixStack matrices, BlockState state, int alpha,
                                                  float r, float g, float b, boolean throughWalls) {
        if (!isBlockEntityModel(state)) return false;

        SpriteHolder holder = spriteHolder();
        if (holder == null) return false;

        if (state.getBlock() instanceof ChestBlock || state.getBlock() instanceof EnderChestBlock)
            return renderChest(matrices, state, alpha, r, g, b, throughWalls, holder);

        if (state.getBlock() instanceof ShulkerBoxBlock shulker)
            return renderShulker(matrices, state, shulker, alpha, r, g, b, throughWalls, holder);

        if (state.getBlock() instanceof BedBlock bed)
            return renderBed(matrices, state, bed, alpha, r, g, b, throughWalls, holder);

        return false;
    }

    /**
     * A chest is an entity model on its own atlas, drawn lid-down and turned to face the way the block
     * state says. Double chests use the left/right halves, so the two blocks meet instead of two singles
     * overlapping in the middle.
     */
    private static boolean renderChest(MatrixStack matrices, BlockState state, int alpha,
                                       float r, float g, float b, boolean throughWalls, SpriteHolder holder) {
        ChestType type = state.contains(ChestBlock.CHEST_TYPE) ? state.get(ChestBlock.CHEST_TYPE) : ChestType.SINGLE;

        ModelPart root = modelPart(switch (type) {
            case LEFT -> EntityModelLayers.DOUBLE_CHEST_LEFT;
            case RIGHT -> EntityModelLayers.DOUBLE_CHEST_RIGHT;
            default -> EntityModelLayers.CHEST;
        });
        if (root == null) return false;

        VertexConsumer vc = consumer(chestSprite(state, type), holder, throughWalls);
        if (vc == null) return false;

        matrices.push();
        orientBlockEntity(matrices, facingOf(state));
        renderPart(root, matrices, new AlphaConsumer(vc, alpha), tint(r, g, b));
        matrices.pop();
        return true;
    }

    private static SpriteIdentifier chestSprite(BlockState state, ChestType type) {
        if (state.getBlock() instanceof EnderChestBlock) return TexturedRenderLayers.ENDER_CHEST;
        return switch (type) {
            case LEFT -> TexturedRenderLayers.CHEST_LEFT;
            case RIGHT -> TexturedRenderLayers.CHEST_RIGHT;
            default -> TexturedRenderLayers.CHEST;
        };
    }

    private static boolean renderShulker(MatrixStack matrices, BlockState state, ShulkerBoxBlock block, int alpha,
                                         float r, float g, float b, boolean throughWalls, SpriteHolder holder) {
        ModelPart root = modelPart(EntityModelLayers.SHULKER_BOX);
        if (root == null) return false;

        DyeColor color = block.getColor();
        SpriteIdentifier sprite = color == null
                ? TexturedRenderLayers.SHULKER_TEXTURE_ID
                : TexturedRenderLayers.COLORED_SHULKER_BOXES_TEXTURES.get(color.getIndex());

        VertexConsumer vc = consumer(sprite, holder, throughWalls);
        if (vc == null) return false;

        Direction facing = state.contains(Properties.FACING) ? state.get(Properties.FACING) : Direction.UP;

        // The shulker model is authored upside down and hanging off the block origin; this is the same
        // setup dance the vanilla renderer does before it hands the model to the lid animation.
        matrices.push();
        matrices.translate(0.5f, 0.5f, 0.5f);
        matrices.scale(0.9995f, 0.9995f, 0.9995f);
        matrices.multiply(facing.getRotationQuaternion());
        matrices.scale(1f, -1f, -1f);
        matrices.translate(0f, -1f, 0f);
        renderPart(root, matrices, new AlphaConsumer(vc, alpha), tint(r, g, b));
        matrices.pop();
        return true;
    }

    private static boolean renderBed(MatrixStack matrices, BlockState state, BedBlock block, int alpha,
                                     float r, float g, float b, boolean throughWalls, SpriteHolder holder) {
        BedPart part = state.contains(BedBlock.PART) ? state.get(BedBlock.PART) : BedPart.FOOT;
        ModelPart root = modelPart(part == BedPart.HEAD ? EntityModelLayers.BED_HEAD : EntityModelLayers.BED_FOOT);
        if (root == null) return false;

        VertexConsumer vc = consumer(TexturedRenderLayers.getBedTextureId(block.getColor()), holder, throughWalls);
        if (vc == null) return false;

        // vanilla draws a bed lying down, rotated by facing, from the block's own corner
        matrices.push();
        matrices.translate(0f, 0.5625f, 0f);
        matrices.multiply(RotationAxis.POSITIVE_X.rotationDegrees(90f));
        matrices.translate(0.5f, 0.5f, 0.5f);
        matrices.multiply(RotationAxis.POSITIVE_Z.rotationDegrees(180f + facingOf(state).getPositiveHorizontalDegrees()));
        matrices.translate(-0.5f, -0.5f, -0.5f);
        renderPart(root, matrices, new AlphaConsumer(vc, alpha), tint(r, g, b));
        matrices.pop();
        return true;
    }

    /* ------------------------------------------------------------------ */
    /* Helpers                                                             */
    /* ------------------------------------------------------------------ */

    private static void orientBlockEntity(MatrixStack matrices, Direction facing) {
        matrices.translate(0.5f, 0.5f, 0.5f);
        matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(-facing.getPositiveHorizontalDegrees()));
        matrices.translate(-0.5f, -0.5f, -0.5f);
    }

    private static Direction facingOf(BlockState state) {
        if (state.contains(Properties.HORIZONTAL_FACING)) return state.get(Properties.HORIZONTAL_FACING);
        if (state.contains(Properties.FACING)) {
            Direction d = state.get(Properties.FACING);
            if (d.getAxis().isHorizontal()) return d;
        }
        return Direction.NORTH;
    }

    private static void renderPart(ModelPart root, MatrixStack matrices, VertexConsumer vc, int tint) {
        root.render(matrices, vc, LightmapTextureManager.MAX_LIGHT_COORDINATE, OverlayTexture.DEFAULT_UV, tint);
    }

    /** ARGB white-with-tint; the alpha byte is ignored, {@link AlphaConsumer} owns that. */
    private static int tint(float r, float g, float b) {
        return 0xFF000000
                | (Math.round(clamp01(r) * 255f) << 16)
                | (Math.round(clamp01(g) * 255f) << 8)
                | Math.round(clamp01(b) * 255f);
    }

    private static float clamp01(float v) {
        return v < 0f ? 0f : Math.min(v, 1f);
    }

    private static @Nullable VertexConsumer consumer(@Nullable SpriteIdentifier sprite, SpriteHolder holder, boolean throughWalls) {
        if (sprite == null) return null;
        return sprite.getVertexConsumer(holder, provider(), id -> layer(id, throughWalls));
    }

    private static @Nullable ModelPart modelPart(EntityModelLayer layer) {
        try {
            return mc.getLoadedEntityModels().getModelPart(layer);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static VertexConsumerProvider.Immediate provider() {
        return mc.getBufferBuilders().getEntityVertexConsumers();
    }

    /**
     * Passes every vertex straight through but overrides the alpha. Block and entity models emit opaque
     * colours, so this is the only place translucency can come from without touching the models.
     */
    private record AlphaConsumer(VertexConsumer parent, int alpha) implements VertexConsumer {
        @Override
        public VertexConsumer vertex(float x, float y, float z) {
            parent.vertex(x, y, z);
            return this;
        }

        @Override
        public VertexConsumer color(int red, int green, int blue, int a) {
            parent.color(red, green, blue, alpha);
            return this;
        }

        @Override
        public VertexConsumer color(int argb) {
            parent.color((argb & 0x00FFFFFF) | (alpha << 24));
            return this;
        }

        @Override
        public VertexConsumer texture(float u, float v) {
            parent.texture(u, v);
            return this;
        }

        @Override
        public VertexConsumer overlay(int u, int v) {
            parent.overlay(u, v);
            return this;
        }

        @Override
        public VertexConsumer light(int u, int v) {
            parent.light(u, v);
            return this;
        }

        @Override
        public VertexConsumer normal(float x, float y, float z) {
            parent.normal(x, y, z);
            return this;
        }

        @Override
        public VertexConsumer lineWidth(float width) {
            parent.lineWidth(width);
            return this;
        }
    }
}
