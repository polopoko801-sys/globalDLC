package thunder.hack.features.modules.render;

import com.mojang.blaze3d.systems.RenderSystem;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.model.ModelPart;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.DepthTestFunction;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.render.*;
import net.minecraft.client.render.entity.state.LivingEntityRenderState;
import net.minecraft.client.render.entity.state.PlayerEntityRenderState;
import net.minecraft.client.render.entity.EndCrystalEntityRenderer;
import net.minecraft.client.render.entity.LivingEntityRenderer;
import net.minecraft.client.render.entity.model.EntityModel;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityPose;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.decoration.EndCrystalEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.RotationAxis;
import net.minecraft.util.math.Vec3d;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaternionf;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import thunder.hack.ThunderHack;
import thunder.hack.core.Managers;
import thunder.hack.events.impl.EventHeldItemRenderer;
import thunder.hack.features.modules.Module;
import thunder.hack.setting.Setting;
import thunder.hack.setting.impl.ColorSetting;
import thunder.hack.utility.render.Render2DEngine;
import thunder.hack.utility.render.TextureStorage;

import java.awt.*;
import java.util.HashMap;
import java.util.Map;

public class Chams extends Module {
    public Chams() {
        super("Chams", Category.RENDER);
    }

    public final Setting<Boolean> handItems = new Setting<>("HandItems", false);
    public final Setting<ColorSetting> handItemsColor = new Setting<>("HandItemsColor", new ColorSetting(new Color(0x9317DE5D, true)), v -> handItems.getValue());

    public final Setting<Boolean> crystals = new Setting<>("Crystals", false);
    private final Setting<ColorSetting> crystalColor = new Setting<>("CrystalColor", new ColorSetting(new Color(0x932DD8E8, true)), v -> crystals.getValue());
    private final Setting<Boolean> staticCrystal = new Setting<>("StaticCrystal", true, v -> crystals.getValue());
    private final Setting<CMode> crystalMode = new Setting<>("CrystalMode", CMode.One, v -> crystals.getValue());

    public final Setting<Boolean> players = new Setting<>("Players", false);
    private final Setting<ColorSetting> playerColor = new Setting<>("PlayerColor", new ColorSetting(new Color(0x932DD8E8, true)), v -> players.getValue());
    private final Setting<ColorSetting> friendColor = new Setting<>("FriendColor", new ColorSetting(new Color(0x932DE830, true)), v -> players.getValue());
    private final Setting<Boolean> playerTexture = new Setting<>("PlayerTexture", true, v -> players.getValue());
    private final Setting<Boolean> simple = new Setting<>("Simple", false, v -> players.getValue());

    /** Draw the tinted model with depth testing off, i.e. through walls - the whole point of chams. */
    public final Setting<Boolean> seeThrough = new Setting<>("SeeThrough", true, v -> players.getValue());

    private final Setting<Boolean> alternativeBlending = new Setting<>("AlternativeBlending", true);

    /**
     * 1.21.11 player chams. The old immediate-mode path is gone, but {@code LivingEntityRenderer} still asks
     * two overridable questions per model - {@code getMixColor} (the colour the model is multiplied by) and
     * {@code getRenderLayer} - and both feed straight into {@code submitModel}. MixinLivingEntityRenderer
     * answers them from here, so the model itself is tinted; nothing has to be re-rendered by hand.
     */
    private static final RenderPipeline CHAMS_PIPELINE = RenderPipeline.builder(RenderPipelines.ENTITY_SNIPPET)
            .withLocation(Identifier.of("gombal", "pipeline/chams"))
            .withVertexFormat(RenderPipelines.ENTITY_TRANSLUCENT.getVertexFormat(),
                    RenderPipelines.ENTITY_TRANSLUCENT.getVertexFormatMode())
            .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
            .withDepthWrite(false)
            .withCull(false)
            .withBlend(BlendFunction.TRANSLUCENT)
            .build();

    private static final Map<Identifier, RenderLayer> CHAMS_LAYERS = new HashMap<>();

    /** ARGB the player model should be multiplied by, or null to leave this model alone. */
    public @Nullable Integer chamsColor(LivingEntityRenderState state) {
        if (!players.getValue() || !(state instanceof PlayerEntityRenderState p)) return null;
        Color c = isFriend(p) ? friendColor.getValue().getColorObject() : playerColor.getValue().getColorObject();
        return c.getRGB();
    }

    /** See-through layer for this player's skin, or null when the model should keep its vanilla layer. */
    public @Nullable RenderLayer chamsLayer(LivingEntityRenderState state) {
        if (!players.getValue() || !seeThrough.getValue()) return null;
        if (!(state instanceof PlayerEntityRenderState p) || p.skinTextures == null) return null;

        Identifier texture = p.skinTextures.body() == null ? null : p.skinTextures.body().texturePath();
        if (texture == null) return null;

        return CHAMS_LAYERS.computeIfAbsent(texture, id -> RenderLayer.of("th_chams_" + id.getPath().replace('/', '_'),
                RenderSetup.builder(CHAMS_PIPELINE)
                        .texture("Sampler0", id)
                        .useLightmap()
                        .useOverlay()
                        .translucent()
                        .build()));
    }

    private static boolean isFriend(@NotNull PlayerEntityRenderState state) {
        return state.playerName != null && Managers.FRIEND.isFriend(state.playerName.getString());
    }

    private enum CMode {
        One, Two, Three
    }

    private final Identifier crystalTexture = Identifier.of("textures/entity/end_crystal/end_crystal.png");
    private static final float SINE_45_DEGREES = (float) Math.sin(0.7853981633974483);

    // 1.21.6+ stub: crystal/player chams used the removed Tessellator/BufferBuilder immediate path,
    // EndCrystalEntityRenderer.getYOffset, the reworked EntityModel field/animateModel/setAngles API and
    // LivingEntityRenderer static helpers. Disabled until reimplemented on RenderPipeline + render-state.
    public void renderCrystal(EndCrystalEntity endCrystalEntity, float f, float g, MatrixStack matrixStack, int i, ModelPart core, ModelPart frame) {
    }

    public void renderPlayer(PlayerEntity pe, float f, float g, MatrixStack matrixStack, int i, EntityModel model, CallbackInfo ci, Runnable post) {
    }

    public void setupTransforms1(PlayerEntity abstractClientPlayerEntity, MatrixStack matrixStack, float f, float g, float h) {
        float j = abstractClientPlayerEntity.getLeaningPitch(h);
        float k = abstractClientPlayerEntity.getPitch(h);
        float l;
        float m;
        if (abstractClientPlayerEntity.isGliding()) {
            setupTransforms(abstractClientPlayerEntity, matrixStack, f, g, h);
            l = (float) abstractClientPlayerEntity.getGlidingTicks() + h;
            m = MathHelper.clamp(l * l / 100.0F, 0.0F, 1.0F);
            if (!abstractClientPlayerEntity.isUsingRiptide()) {
                matrixStack.multiply(RotationAxis.POSITIVE_X.rotationDegrees(m * (-90.0F - k)));
            }

            Vec3d vec3d = abstractClientPlayerEntity.getRotationVec(h);
            Vec3d vec3d2 = abstractClientPlayerEntity.getVelocity();
            double d = vec3d2.horizontalLengthSquared();
            double e = vec3d.horizontalLengthSquared();
            if (d > 0.0 && e > 0.0) {
                double n = (vec3d2.x * vec3d.x + vec3d2.z * vec3d.z) / Math.sqrt(d * e);
                double o = vec3d2.x * vec3d.z - vec3d2.z * vec3d.x;
                matrixStack.multiply(RotationAxis.POSITIVE_Y.rotation((float) (Math.signum(o) * Math.acos(n))));
            }
        } else if (j > 0.0F) {
            setupTransforms(abstractClientPlayerEntity, matrixStack, f, g, h);
            l = abstractClientPlayerEntity.isTouchingWater() ? -90.0F - k : -90.0F;
            m = MathHelper.lerp(j, 0.0F, l);
            matrixStack.multiply(RotationAxis.POSITIVE_X.rotationDegrees(m));
            if (abstractClientPlayerEntity.isInSwimmingPose()) {
                matrixStack.translate(0.0F, -1.0F, 0.3F);
            }
        } else {
            setupTransforms(abstractClientPlayerEntity, matrixStack, f, g, h);
        }
    }

    private void setupTransforms(PlayerEntity entity, MatrixStack matrices, float animationProgress, float bodyYaw, float tickDelta) {
        if (!entity.isInPose(EntityPose.SLEEPING)) {
            matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(180.0F - bodyYaw));
        }

        if (entity.deathTime > 0) {
            float f = ((float) entity.deathTime + tickDelta - 1.0F) / 20.0F * 1.6F;
            f = MathHelper.sqrt(f);
            if (f > 1.0F) {
                f = 1.0F;
            }

            matrices.multiply(RotationAxis.POSITIVE_Z.rotationDegrees(f * 90.0F));
        } else if (entity.isUsingRiptide()) {
            matrices.multiply(RotationAxis.POSITIVE_X.rotationDegrees(-90.0F - entity.getPitch()));
            matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(((float) entity.age + tickDelta) * -75.0F));
        } else if (entity.isInPose(EntityPose.SLEEPING)) {
            Direction direction = entity.getSleepingDirection();
            float g = direction != null ? getYaw(direction) : bodyYaw;
            matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(g));
            matrices.multiply(RotationAxis.POSITIVE_Z.rotationDegrees(90.0F));
            matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(270.0F));
        }
    }

    private static float getYaw(Direction direction) {
        return switch (direction) {
            case NORTH -> 270.0f;
            case SOUTH -> 90.0f;
            case EAST -> 180.0f;
            default -> 0.0f;
        };
    }

    @EventHandler
    public void onRenderHands(EventHeldItemRenderer e) {
        if (handItems.getValue())
            thunder.hack.utility.render.RSCompat.setShaderColor(handItemsColor.getValue().getRed() / 255f, handItemsColor.getValue().getGreen() / 255f, handItemsColor.getValue().getBlue() / 255f, handItemsColor.getValue().getAlpha() / 255f);
    }
}