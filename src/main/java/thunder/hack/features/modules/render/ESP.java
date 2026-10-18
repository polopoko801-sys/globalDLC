package thunder.hack.features.modules.render;

import com.mojang.blaze3d.vertex.VertexFormat;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.BeaconBlockEntity;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.*;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.AreaEffectCloudEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.TntEntity;
import net.minecraft.entity.decoration.EndCrystalEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.projectile.thrown.EnderPearlEntity;
import net.minecraft.item.Items;
import net.minecraft.particle.TintedParticleEffect;
import net.minecraft.particle.ParticleEffect;
import net.minecraft.util.math.*;
import org.jetbrains.annotations.NotNull;
import org.joml.Matrix4f;
import org.joml.Vector4d;
import thunder.hack.core.Managers;
import thunder.hack.gui.font.FontRenderers;
import thunder.hack.injection.accesors.IAreaEffectCloudEntity;
import thunder.hack.injection.accesors.IBeaconBlockEntity;
import thunder.hack.features.modules.Module;
import thunder.hack.features.modules.client.HudEditor;
import thunder.hack.setting.Setting;
import thunder.hack.setting.impl.ColorSetting;
import thunder.hack.setting.impl.SettingGroup;
import thunder.hack.utility.render.Render2DEngine;
import thunder.hack.utility.render.Render3DEngine;

import java.awt.*;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import static thunder.hack.utility.render.animation.AnimationUtility.fast;

public class ESP extends Module {
    public ESP() {
        super("ESP", Category.RENDER);
    }

    private final Setting<Boolean> lingeringPotions = new Setting<>("LingeringPotions", false);
    private final Setting<Boolean> tntFuse = new Setting<>("TNTFuse", false);
    private final Setting<Float> tntrange = new Setting<>("TNTRange", 8.0f, 0f, 8f);
    private final Setting<ColorSetting> tntFuseText = new Setting<>("TNTFuseText", new ColorSetting(new Color(-1)), v -> tntFuse.getValue());
    private final Setting<Boolean> tntRadius = new Setting<>("TNTRadius", false);
    private final Setting<ColorSetting> tntRadiusColor = new Setting<>("TNTSphereColor", new ColorSetting(new Color(-1)), v -> tntRadius.getValue());
    private final Setting<Boolean> beaconRadius = new Setting<>("BeaconRadius", false);
    private final Setting<Boolean> keepY = new Setting<>("KeepY", false, v -> beaconRadius.getValue());
    private final Setting<ColorSetting> sphereColor = new Setting<>("SphereColor", new ColorSetting(new Color(-1)), v -> beaconRadius.getValue());
    private final Setting<ColorSetting> beakonColor = new Setting<>("BeakonColor", new ColorSetting(new Color(-1)), v -> beaconRadius.getValue());
    private final Setting<Boolean> burrow = new Setting<>("Burrow", false);
    private final Setting<ColorSetting> burrowTextColor = new Setting<>("BurrowTextColor", new ColorSetting(new Color(-1)), v -> burrow.getValue());
    private final Setting<ColorSetting> burrowColor = new Setting<>("BurrowColor", new ColorSetting(new Color(-1)), v -> burrow.getValue());
    private final Setting<Boolean> pearls = new Setting<>("Pearls", false);
    private final Setting<Boolean> dizorentRadius = new Setting<>("DizorentRadius", true);
    private final Setting<ColorSetting> dizorentColor = new Setting<>("DizorentColor", new ColorSetting(new Color(0xB300F1CC, true)), v -> dizorentRadius.getValue());

    private final Setting<SettingGroup> boxEsp = new Setting<>("Box", new SettingGroup(false, 0));
    private final Setting<Boolean> players = new Setting<>("Players", true).addToGroup(boxEsp);
    private final Setting<Boolean> friends = new Setting<>("Friends", true).addToGroup(boxEsp);
    private final Setting<Boolean> crystals = new Setting<>("Crystals", true).addToGroup(boxEsp);
    private final Setting<Boolean> creatures = new Setting<>("Creatures", false).addToGroup(boxEsp);
    private final Setting<Boolean> monsters = new Setting<>("Monsters", false).addToGroup(boxEsp);
    private final Setting<Boolean> ambients = new Setting<>("Ambients", false).addToGroup(boxEsp);
    private final Setting<Boolean> others = new Setting<>("Others", false).addToGroup(boxEsp);
    private final Setting<Boolean> outline = new Setting<>("Outline", true).addToGroup(boxEsp);
    private final Setting<Colors> colorMode = new Setting<>("ColorMode", Colors.Custom).addToGroup(boxEsp);
    private final Setting<Boolean> renderHealth = new Setting<>("renderHealth", true).addToGroup(boxEsp);
    private final Setting<Boolean> fill = new Setting<>("Fill", false).addToGroup(boxEsp);
    private final Setting<Integer> fillAlpha = new Setting<>("FillAlpha", 45, 0, 160, v -> fill.getValue()).addToGroup(boxEsp);
    private final Setting<Style> style = new Setting<>("Style", Style.Corners).addToGroup(boxEsp);
    private final Setting<Float> thickness = new Setting<>("Thickness", 1f, 0.5f, 3f).addToGroup(boxEsp);
    private final Setting<Float> rounding = new Setting<>("Rounding", 3f, 1f, 8f, v -> style.getValue() == Style.Rounded).addToGroup(boxEsp);
    private final Setting<Boolean> glow = new Setting<>("Glow", true).addToGroup(boxEsp);
    private final Setting<Integer> glowRadius = new Setting<>("GlowRadius", 7, 2, 16, v -> glow.getValue()).addToGroup(boxEsp);
    private final Setting<Integer> glowAlpha = new Setting<>("GlowAlpha", 140, 10, 255, v -> glow.getValue()).addToGroup(boxEsp);
    private final Setting<Boolean> smooth = new Setting<>("Smooth", true).addToGroup(boxEsp);
    private final Setting<Float> smoothSpeed = new Setting<>("SmoothSpeed", 0.5f, 0.05f, 0.95f, v -> smooth.getValue()).addToGroup(boxEsp);
    private final Setting<Boolean> fadeInOut = new Setting<>("FadeInOut", true).addToGroup(boxEsp);
    private final Setting<Boolean> healthText = new Setting<>("HealthText", true, v -> renderHealth.getValue()).addToGroup(boxEsp);

    private final Setting<SettingGroup> boxColors = new Setting<>("BoxColors", new SettingGroup(false, 0));
    private final Setting<ColorSetting> playersC = new Setting<>("PlayersC", new ColorSetting(new Color(0xFF9200))).addToGroup(boxColors);
    private final Setting<ColorSetting> friendsC = new Setting<>("FriendsC", new ColorSetting(new Color(0x30FF00))).addToGroup(boxColors);
    private final Setting<ColorSetting> crystalsC = new Setting<>("CrystalsC", new ColorSetting(new Color(0x00BBFF))).addToGroup(boxColors);
    private final Setting<ColorSetting> creaturesC = new Setting<>("CreaturesC", new ColorSetting(new Color(0xA0A4A6))).addToGroup(boxColors);
    private final Setting<ColorSetting> monstersC = new Setting<>("MonstersC", new ColorSetting(new Color(0xFF0000))).addToGroup(boxColors);
    private final Setting<ColorSetting> ambientsC = new Setting<>("AmbientsC", new ColorSetting(new Color(0x7B00FF))).addToGroup(boxColors);
    private final Setting<ColorSetting> othersC = new Setting<>("OthersC", new ColorSetting(new Color(0xFF0062))).addToGroup(boxColors);
    public final Setting<ColorSetting> healthB = new Setting<>("healthB", new ColorSetting(new Color(0xff1100))).addToGroup(boxColors);
    public final Setting<ColorSetting> healthU = new Setting<>("healthU", new ColorSetting(new Color(0x2fff00))).addToGroup(boxColors);

    private float dizorentAnimation = 0f;

    /** Per-entity screen-rect state, so boxes glide and fade instead of snapping into place. */
    private static final class BoxAnim {
        float x, y, ex, ey, alpha;
        int color;
        boolean seen, init;
    }

    private final Map<Integer, BoxAnim> boxAnims = new HashMap<>();
    private long lastFrameTime = System.currentTimeMillis();
    /** Time since the previous frame expressed in 60fps frames, so animations are framerate independent. */
    private float frameDelta = 1f;

    public void onRender3D(MatrixStack stack) {
        if(mc.options.hudHidden) return;
        if (lingeringPotions.getValue()) {
            for (Entity ent : mc.world.getEntities()) {
                if (ent instanceof AreaEffectCloudEntity aece) {
                    double x = aece.getX() - mc.getEntityRenderDispatcher().camera.getCameraPos().getX();
                    double y = aece.getY() - mc.getEntityRenderDispatcher().camera.getCameraPos().getY();
                    double z = aece.getZ() - mc.getEntityRenderDispatcher().camera.getCameraPos().getZ();

                    float middle = aece.getRadius();

                    stack.push();
                    stack.translate(x, y, z);

                    Render3DEngine.setupRender();
                    thunder.hack.utility.render.RSCompat.disableDepthTest();
                    thunder.hack.utility.render.RSCompat.noop();
                    BufferBuilder bufferBuilder = Tessellator.getInstance().begin(VertexFormat.DrawMode.TRIANGLE_STRIP, VertexFormats.POSITION_COLOR);

                    for (int i = 0; i <= 360; i += 6) {
                        double v = Math.sin(Math.toRadians(i));
                        double u = Math.cos(Math.toRadians(i));
                        bufferBuilder.vertex(stack.peek().getPositionMatrix(), (float) u * middle, (float) 0, (float) v * middle).color(Render2DEngine.injectAlpha(new Color(getAreaCloudColor(aece)), 100).getRGB());
                        bufferBuilder.vertex(stack.peek().getPositionMatrix(), 0, 0, 0).color(Render2DEngine.injectAlpha(new Color(getAreaCloudColor(aece)), 0).getRGB());
                    }
                    Render2DEngine.endBuilding(bufferBuilder);

                    thunder.hack.utility.render.RSCompat.noop();
                    bufferBuilder = Tessellator.getInstance().begin(VertexFormat.DrawMode.TRIANGLE_STRIP, VertexFormats.POSITION_COLOR);
                    for (int i = 0; i <= 360; i += 6) {
                        double v = Math.sin(Math.toRadians(i));
                        double u = Math.cos(Math.toRadians(i));
                        bufferBuilder.vertex(stack.peek().getPositionMatrix(), (float) u * middle, (float) 0, (float) v * middle).color(Render2DEngine.injectAlpha(new Color(getAreaCloudColor(aece)), 255).getRGB());
                        bufferBuilder.vertex(stack.peek().getPositionMatrix(), (float) u * (middle - 0.04f), (float) 0, (float) v * (middle - 0.04f)).color(Render2DEngine.injectAlpha(new Color(getAreaCloudColor(aece)), 255).getRGB());
                    }
                    Render2DEngine.endBuilding(bufferBuilder);

                    Render3DEngine.endRender();
                    thunder.hack.utility.render.RSCompat.enableDepthTest();
                    stack.translate(-x, -y, -z);
                    stack.pop();

                    thunder.hack.utility.render.RSCompat.disableDepthTest();
                    MatrixStack matrices = new MatrixStack();
                    Camera camera = mc.gameRenderer.getCamera();
                    matrices.multiply(RotationAxis.POSITIVE_X.rotationDegrees(camera.getPitch()));
                    matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(camera.getYaw() + 180.0F));
                    matrices.translate(x, y, z);
                    matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(-camera.getYaw()));
                    matrices.multiply(RotationAxis.POSITIVE_X.rotationDegrees(camera.getPitch()));
                    thunder.hack.utility.render.RSCompat.enableBlend();
                    thunder.hack.utility.render.RSCompat.defaultBlendFunc();
                    matrices.translate(0, 0, 0);
                    matrices.scale(-0.05f, -0.05f, 0);
                    FontRenderers.modules.drawCenteredString(matrices, String.format("%.1f", ((aece.getRadius() * 10) - 5f)), 0, -10f, -1);
                    thunder.hack.utility.render.RSCompat.disableBlend();
                    thunder.hack.utility.render.RSCompat.enableDepthTest();
                }
            }
        }

        if (dizorentRadius.getValue()) {
            dizorentAnimation = fast(dizorentAnimation, mc.player.getMainHandStack().getItem() == Items.ENDER_EYE ? 10 : 0, 15f);

            if (mc.player.getMainHandStack().getItem() == Items.ENDER_EYE) {
                double x = Render2DEngine.interpolate(mc.player.lastX, mc.player.getX(), Render3DEngine.getTickDelta()) - mc.getEntityRenderDispatcher().camera.getCameraPos().getX();
                double y = Render2DEngine.interpolate(mc.player.lastY, mc.player.getY(), Render3DEngine.getTickDelta()) - mc.getEntityRenderDispatcher().camera.getCameraPos().getY();
                double z = Render2DEngine.interpolate(mc.player.lastZ, mc.player.getZ(), Render3DEngine.getTickDelta()) - mc.getEntityRenderDispatcher().camera.getCameraPos().getZ();


                stack.push();
                stack.translate(x, y, z);

                Render3DEngine.setupRender();
                thunder.hack.utility.render.RSCompat.disableDepthTest();
                thunder.hack.utility.render.RSCompat.noop();
                BufferBuilder bufferBuilder = Tessellator.getInstance().begin(VertexFormat.DrawMode.TRIANGLE_STRIP, VertexFormats.POSITION_COLOR);

                for (int i = 0; i <= 360; i += 6) {
                    double v = Math.sin(Math.toRadians(i));
                    double u = Math.cos(Math.toRadians(i));
                    bufferBuilder.vertex(stack.peek().getPositionMatrix(), (float) u * dizorentAnimation, (float) 0, (float) v * dizorentAnimation).color(Render2DEngine.injectAlpha(new Color(dizorentColor.getValue().getColor()), 100).getRGB());
                    bufferBuilder.vertex(stack.peek().getPositionMatrix(), 0, 0, 0).color(Render2DEngine.injectAlpha(new Color(dizorentColor.getValue().getColor()), 0).getRGB());
                }
                Render2DEngine.endBuilding(bufferBuilder);

                thunder.hack.utility.render.RSCompat.noop();
                bufferBuilder = Tessellator.getInstance().begin(VertexFormat.DrawMode.TRIANGLE_STRIP, VertexFormats.POSITION_COLOR);
                for (int i = 0; i <= 360; i += 6) {
                    double v = Math.sin(Math.toRadians(i));
                    double u = Math.cos(Math.toRadians(i));
                    bufferBuilder.vertex(stack.peek().getPositionMatrix(), (float) u * dizorentAnimation, (float) 0, (float) v * dizorentAnimation).color(Render2DEngine.injectAlpha(new Color(dizorentColor.getValue().getColor()), 255).getRGB());
                    bufferBuilder.vertex(stack.peek().getPositionMatrix(), (float) u * (dizorentAnimation - 0.04f), (float) 0, (float) v * (dizorentAnimation - 0.04f)).color(Render2DEngine.injectAlpha(new Color(dizorentColor.getValue().getColor()), 255).getRGB());
                }
                Render2DEngine.endBuilding(bufferBuilder);

                Render3DEngine.endRender();
                thunder.hack.utility.render.RSCompat.enableDepthTest();
                stack.translate(-x, -y, -z);
                stack.pop();

                for (PlayerEntity pl : Managers.ASYNC.getAsyncPlayers()) {
                    if (mc.player.squaredDistanceTo(pl.getEntityPos()) > 100 || pl == mc.player)
                        continue;
                    Render3DEngine.drawTargetEsp(stack, pl);
                }
            }
        }

        if (beaconRadius.getValue()) {
            for (BlockEntity be : StorageEsp.getBlockEntities()) {
                if (be instanceof BeaconBlockEntity bbe) {
                    double x = be.getPos().getX() - mc.getEntityRenderDispatcher().camera.getCameraPos().getX();
                    double y = be.getPos().getY() - mc.getEntityRenderDispatcher().camera.getCameraPos().getY();
                    double z = be.getPos().getZ() - mc.getEntityRenderDispatcher().camera.getCameraPos().getZ();

                    Render3DEngine.drawBoxOutline(new Box(be.getPos()), beakonColor.getValue().getColorObject(), 2);
                    float range = ((IBeaconBlockEntity) bbe).getLevel() * 10 + 11;

                    boolean ky = keepY.getValue();

                    stack.push();
                    stack.translate(x,  ky ? -10 : y, z);
                    Render3DEngine.drawCylinder(stack, range, ky ? 20 : 256, 20, ky ? 5 : 20, sphereColor.getValue().getColor());
                    stack.translate(-x, ky ? 10 : y, -z);
                    stack.pop();
                }
            }
        }

        if (burrow.getValue()) {
            for (PlayerEntity pl : mc.world.getPlayers()) {
                BlockPos blockPos = BlockPos.ofFloored(pl.getEntityPos().add(0,0.15f,0));
                Block block = mc.world.getBlockState(blockPos).getBlock();

                double x = blockPos.getX() - mc.getEntityRenderDispatcher().camera.getCameraPos().getX();
                double y = blockPos.getY() - mc.getEntityRenderDispatcher().camera.getCameraPos().getY();
                double z = blockPos.getZ() - mc.getEntityRenderDispatcher().camera.getCameraPos().getZ();

                if (block == Blocks.OBSIDIAN
                        || block == Blocks.CRYING_OBSIDIAN
                        || block == Blocks.ANVIL
                        || block == Blocks.PLAYER_HEAD
                        || block == Blocks.SKELETON_SKULL
                        || block == Blocks.WITHER_SKELETON_SKULL) {
                    Render3DEngine.drawBoxOutline(new Box(blockPos), burrowColor.getValue().getColorObject(), 2);
                    thunder.hack.utility.render.RSCompat.disableDepthTest();
                    MatrixStack matrices = new MatrixStack();
                    Camera camera = mc.gameRenderer.getCamera();
                    matrices.multiply(RotationAxis.POSITIVE_X.rotationDegrees(camera.getPitch()));
                    matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(camera.getYaw() + 180.0F));
                    matrices.translate(x + 0.5f, y + 0.5f, z + 0.5f);
                    matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(-camera.getYaw()));
                    matrices.multiply(RotationAxis.POSITIVE_X.rotationDegrees(camera.getPitch()));
                    thunder.hack.utility.render.RSCompat.enableBlend();
                    thunder.hack.utility.render.RSCompat.defaultBlendFunc();
                    matrices.translate(0, 0, 0);
                    matrices.scale(-0.025f, -0.025f, 0);
                    FontRenderers.modules.drawCenteredString(matrices, "BURROW", 0, -5, burrowTextColor.getValue().getColor());
                    thunder.hack.utility.render.RSCompat.disableBlend();
                    thunder.hack.utility.render.RSCompat.enableDepthTest();
                }
            }
        }

        if (tntFuse.getValue() || tntRadius.getValue()) {
            for (Entity ent : mc.world.getEntities()) {
                if (ent instanceof TntEntity tnt) {
                    double x = tnt.lastX + (tnt.getEntityPos().getX() - tnt.lastX) * Render3DEngine.getTickDelta() - mc.getEntityRenderDispatcher().camera.getCameraPos().getX();
                    double y = tnt.lastY + (tnt.getEntityPos().getY() - tnt.lastY) * Render3DEngine.getTickDelta() - mc.getEntityRenderDispatcher().camera.getCameraPos().getY();
                    double z = tnt.lastZ + (tnt.getEntityPos().getZ() - tnt.lastZ) * Render3DEngine.getTickDelta() - mc.getEntityRenderDispatcher().camera.getCameraPos().getZ();

                    if (tntFuse.getValue()) {
                        thunder.hack.utility.render.RSCompat.disableDepthTest();
                        MatrixStack matrices = new MatrixStack();
                        Camera camera = mc.gameRenderer.getCamera();
                        matrices.multiply(RotationAxis.POSITIVE_X.rotationDegrees(camera.getPitch()));
                        matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(camera.getYaw() + 180.0F));
                        matrices.translate(x, y + 0.5f, z);
                        matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(-camera.getYaw()));
                        matrices.multiply(RotationAxis.POSITIVE_X.rotationDegrees(camera.getPitch()));
                        thunder.hack.utility.render.RSCompat.enableBlend();
                        thunder.hack.utility.render.RSCompat.defaultBlendFunc();
                        matrices.translate(0, 0, 0);
                        matrices.scale(-0.025f, -0.025f, 0);
                        FontRenderers.modules.drawCenteredString(matrices, String.format("%.1f", ((float) tnt.getFuse() / 20f)) + "s", 0, -5, tntFuseText.getValue().getColor());
                        thunder.hack.utility.render.RSCompat.disableBlend();
                        thunder.hack.utility.render.RSCompat.enableDepthTest();
                    }

                    if (tntRadius.getValue()) {
                        stack.push();
                        stack.translate(x, y, z);
                        Render3DEngine.drawSphere(stack, tntrange.getValue(), 20, 20, tntRadiusColor.getValue().getColor());
                        stack.translate(-x, -y, -z);
                        stack.pop();
                    }
                }
            }
        }
    }

    public void onRender2D(DrawContext context) {
        if(mc.options.hudHidden) return;
        if (pearls.getValue()) {
            for (Entity ent : mc.world.getEntities()) {
                if (ent instanceof EnderPearlEntity pearl) {
                    float xOffset = mc.getWindow().getScaledWidth() / 2f;
                    float yOffset = mc.getWindow().getScaledHeight() / 2f;

                    float xPos = (float) (pearl.lastX + (pearl.getEntityPos().getX() - pearl.lastX) * Render3DEngine.getTickDelta());
                    float zPos = (float) (pearl.lastZ + (pearl.getEntityPos().getZ() - pearl.lastZ) * Render3DEngine.getTickDelta());

                    float yaw = getRotations(new Vec2f(xPos, zPos)) - mc.player.getYaw();
                    context.getMatrices().translate((float)(xOffset), (float)( yOffset));
                    context.getMatrices().rotate((float) Math.toRadians(yaw));
                    context.getMatrices().translate((float)(-xOffset), (float)( -yOffset));
                    Render2DEngine.drawTracerPointer(context, xOffset, yOffset - 50, 12.5f, 0.5f, 3.63f, true, true, HudEditor.getColor(1).getRGB());
                    context.getMatrices().translate((float)(xOffset), (float)( yOffset));
                    context.getMatrices().rotate((float) Math.toRadians(-yaw));
                    context.getMatrices().translate((float)(-xOffset), (float)( -yOffset));
                    thunder.hack.utility.render.RSCompat.setShaderColor(1F, 1F, 1F, 1F);
                    FontRenderers.modules.drawCenteredString(context, String.format("%.1f", mc.player.distanceTo(pearl)) + "m", (float) (Math.sin(Math.toRadians(yaw)) * 50f) + xOffset, (float) (yOffset - (Math.cos(Math.toRadians(yaw)) * 50f)) - 20, -1);
                }
            }
        }

        // 1.21.11: the old path went through the no-op Render2DEngine.setRectPoints/endBuilding stubs
        // and drew nothing. Draw the screen-projected 2D box with the working drawRect primitive.
        tickBoxAnimations();
        for (Entity ent : mc.world.getEntities())
            if (shouldRender(ent))
                drawBox(context, ent);
        drawFadeOuts(context);
    }

    private void tickBoxAnimations() {
        long now = System.currentTimeMillis();
        frameDelta = MathHelper.clamp((now - lastFrameTime) / 16.666f, 0.1f, 5f);
        lastFrameTime = now;
        for (BoxAnim a : boxAnims.values())
            a.seen = false;
    }

    /** Framerate independent lerp weight for a per-60fps-frame {@code speed}. */
    private float lerpFactor(float speed) {
        return (float) (1.0 - Math.pow(1.0 - MathHelper.clamp(speed, 0.01f, 0.99f), frameDelta));
    }

    /** Entities that stopped being rendered this frame keep their last box and fade it out. */
    private void drawFadeOuts(DrawContext context) {
        Iterator<Map.Entry<Integer, BoxAnim>> it = boxAnims.entrySet().iterator();
        while (it.hasNext()) {
            BoxAnim a = it.next().getValue();
            if (a.seen) continue;
            if (!a.init || !fadeInOut.getValue()) {
                it.remove();
                continue;
            }
            a.alpha -= frameDelta * 0.14f;
            if (a.alpha <= 0.02f) {
                it.remove();
                continue;
            }
            renderBox(context, a.x, a.y, a.ex, a.ey, new Color(a.color), a.alpha, -1f, 1f);
        }
    }

    public boolean shouldRender(Entity entity) {
        if (entity == null)
            return false;

        if (mc.player == null)
            return false;

        if (entity instanceof PlayerEntity) {
            if (entity == mc.player)
                return false;
            if (Managers.FRIEND.isFriend((PlayerEntity) entity))
                return friends.getValue();
            return players.getValue();
        }

        if (entity instanceof EndCrystalEntity)
            return crystals.getValue();

        return switch (entity.getType().getSpawnGroup()) {
            case CREATURE, WATER_CREATURE -> creatures.getValue();
            case MONSTER -> monsters.getValue();
            case AMBIENT, WATER_AMBIENT -> ambients.getValue();
            default -> others.getValue();
        };
    }

    public Color getEntityColor(Entity entity) {
        if (entity == null)
            return new Color(-1);

        if (entity instanceof PlayerEntity) {
            if (Managers.FRIEND.isFriend((PlayerEntity) entity))
                return friendsC.getValue().getColorObject();
            return syncOr(playersC.getValue().getColorObject());
        }

        if (entity instanceof EndCrystalEntity)
            return syncOr(crystalsC.getValue().getColorObject());

        return syncOr(switch (entity.getType().getSpawnGroup()) {
            case CREATURE, WATER_CREATURE -> creaturesC.getValue().getColorObject();
            case MONSTER -> monstersC.getValue().getColorObject();
            case AMBIENT, WATER_AMBIENT -> ambientsC.getValue().getColorObject();
            default -> othersC.getValue().getColorObject();
        });
    }

    /** In SyncColor mode every non-friend box takes the running client/HUD colour instead of its own. */
    private Color syncOr(Color own) {
        return colorMode.getValue() == Colors.SyncColor ? HudEditor.getColor(1) : own;
    }

    public void drawBox(DrawContext context, @NotNull Entity ent) {
        Vec3d[] vectors = getVectors(ent);
        Color col = getEntityColor(ent);

        Vector4d position = null;
        for (Vec3d vector : vectors) {
            vector = Render3DEngine.worldSpaceToScreenSpace(new Vec3d(vector.x, vector.y, vector.z));
            if (vector.z > 0 && vector.z < 1) {
                if (position == null) position = new Vector4d(vector.x, vector.y, vector.z, 0);
                position.x = Math.min(vector.x, position.x);
                position.y = Math.min(vector.y, position.y);
                position.z = Math.max(vector.x, position.z);
                position.w = Math.max(vector.y, position.w);
            }
        }
        if (position == null) return;

        BoxAnim anim = boxAnims.computeIfAbsent(ent.getId(), i -> new BoxAnim());
        anim.seen = true;
        anim.color = col.getRGB();

        float tx = (float) position.x, ty = (float) position.y, tex = (float) position.z, tey = (float) position.w;
        if (!anim.init || !smooth.getValue()) {
            anim.x = tx;
            anim.y = ty;
            anim.ex = tex;
            anim.ey = tey;
            anim.init = true;
        } else {
            // Snap instead of gliding when the box teleports (respawn, chunk load, camera cut), otherwise the
            // box would visibly fly across the screen.
            float f = Math.abs(tx - anim.x) > 200f || Math.abs(ty - anim.y) > 200f ? 1f : lerpFactor(smoothSpeed.getValue());
            anim.x = MathHelper.lerp(f, anim.x, tx);
            anim.y = MathHelper.lerp(f, anim.y, ty);
            anim.ex = MathHelper.lerp(f, anim.ex, tex);
            anim.ey = MathHelper.lerp(f, anim.ey, tey);
        }

        if (fadeInOut.getValue())
            anim.alpha = Math.min(1f, anim.alpha + frameDelta * 0.12f);
        else
            anim.alpha = 1f;

        float health = -1f, maxHealth = 1f;
        if (ent instanceof LivingEntity lent && lent.getHealth() > 0f) {
            health = lent.getHealth();
            maxHealth = Math.max(1f, lent.getMaxHealth());
        }

        renderBox(context, anim.x, anim.y, anim.ex, anim.ey, col, anim.alpha, health, maxHealth);
    }

    /**
     * Draws one finished box: outer glow, gradient fill, the selected outline style and the health bar,
     * everything scaled by {@code opacity} so a box can fade in and out as a whole.
     */
    private void renderBox(DrawContext context, float bx, float by, float ex, float ey, Color col, float opacity, float health, float maxHealth) {
        if (opacity <= 0.02f) return;

        float bw = ex - bx, bh = ey - by;
        if (bw <= 0.5f || bh <= 0.5f) return;
        // A corner that projects near the near-plane can blow the rect up to cover the screen; drop those.
        if (bw > mc.getWindow().getScaledWidth() * 3f || bh > mc.getWindow().getScaledHeight() * 3f) return;

        float t = thickness.getValue();
        Color main = Render2DEngine.applyOpacity(col, opacity);
        Color shadow = Render2DEngine.applyOpacity(new Color(0, 0, 0, 170), opacity);

        if (glow.getValue())
            drawGlow(context, bx, by, ex, ey, col, opacity);

        if (fill.getValue()) {
            // top->bottom fade so the fill reads as depth rather than a flat sticker
            int a = fillAlpha.getValue();
            Color top = Render2DEngine.applyOpacity(Render2DEngine.injectAlpha(col, (int) (a * 0.25f)), opacity);
            Color bottom = Render2DEngine.applyOpacity(Render2DEngine.injectAlpha(col, a), opacity);
            Render2DEngine.draw2DGradientRect(context, bx, by, ex, ey, bottom, top, bottom, top);
        }

        switch (style.getValue()) {
            case Corners -> {
                float len = MathHelper.clamp(Math.min(bw, bh) / 3.5f, 2.5f, 14f);
                if (outline.getValue()) drawCorners(context, bx - t, by - t, ex + t, ey + t, len + t, t, shadow);
                drawCorners(context, bx, by, ex, ey, len, t, main);
            }
            case Rounded -> {
                float r = Math.min(rounding.getValue(), Math.min(bw, bh) / 2f);
                if (outline.getValue())
                    drawRoundedOutline(context, bx - t, by - t, bw + t * 2, bh + t * 2, r + t, t, shadow);
                drawRoundedOutline(context, bx, by, bw, bh, r, t, main);
            }
            default -> {
                if (outline.getValue()) {
                    Render2DEngine.drawRect(context, bx - t, by - t, bw + t * 2, t, shadow);
                    Render2DEngine.drawRect(context, bx - t, ey, bw + t * 2, t, shadow);
                    Render2DEngine.drawRect(context, bx - t, by - t, t, bh + t * 2, shadow);
                    Render2DEngine.drawRect(context, ex, by - t, t, bh + t * 2, shadow);
                }
                Render2DEngine.drawRect(context, bx, by, bw, t, main);
                Render2DEngine.drawRect(context, bx, ey - t, bw, t, main);
                Render2DEngine.drawRect(context, bx, by, t, bh, main);
                Render2DEngine.drawRect(context, ex - t, by, t, bh, main);
            }
        }

        if (health >= 0f && renderHealth.getValue())
            drawHealthBar(context, bx, by, ey, health, maxHealth, opacity);
    }

    /** Rounded, colour-graded health bar hugging the left edge of the box. */
    private void drawHealthBar(DrawContext context, float bx, float by, float ey, float health, float maxHealth, float opacity) {
        float barW = 2.5f, barX = bx - barW - 3f, barH = ey - by;
        float frac = MathHelper.clamp(health / maxHealth, 0f, 1f);
        Color hc = healthColor(frac);

        Render2DEngine.drawRound(context, barX, by, barW, barH, barW / 2f,
                Render2DEngine.applyOpacity(new Color(0, 0, 0, 160), opacity));

        float fh = barH * frac;
        if (fh > 1f) {
            if (glow.getValue()) {
                // one soft halo pass behind the filled part
                Color halo = Render2DEngine.applyOpacity(Render2DEngine.injectAlpha(hc, 70), opacity);
                Render2DEngine.drawRound(context, barX - 1.5f, ey - fh - 1.5f, barW + 3f, fh + 3f, (barW + 3f) / 2f, halo);
            }
            Render2DEngine.drawRound(context, barX, ey - fh, barW, fh, barW / 2f, Render2DEngine.applyOpacity(hc, opacity));
        }

        if (healthText.getValue() && barH > 16f) {
            String s = String.valueOf((int) Math.ceil(health));
            float w = FontRenderers.sf_bold_micro.getStringWidth(s);
            FontRenderers.sf_bold_micro.drawString(context, s, barX - w - 1.5f, ey - fh - 2.5f,
                    Render2DEngine.applyOpacity(hc, opacity).getRGB());
        }
    }

    /** Red -> amber -> green ramp between the two configured health colours. */
    private Color healthColor(float frac) {
        Color low = healthB.getValue().getColorObject();
        Color high = healthU.getValue().getColorObject();
        Color mid = new Color(0xFFC400);
        return frac < 0.5f
                ? Render2DEngine.interpolateColorC(low, mid, frac * 2f)
                : Render2DEngine.interpolateColorC(mid, high, (frac - 0.5f) * 2f);
    }

    /**
     * Soft outer glow built from concentric 1px rings with a quadratic alpha falloff. Deliberately not
     * {@link Render2DEngine#drawBlurredShadow} — that caches a CPU-blurred texture per box size, and ESP
     * boxes change size every frame, which would register a new texture per frame per entity.
     */
    private void drawGlow(DrawContext context, float bx, float by, float ex, float ey, Color col, float opacity) {
        int r = glowRadius.getValue();
        float base = glowAlpha.getValue() * opacity;
        float w = ex - bx, h = ey - by;
        for (int i = r; i >= 1; i--) {
            float f = 1f - i / (float) (r + 1);
            int a = (int) (base * f * f);
            if (a <= 1) continue;
            Color c = Render2DEngine.injectAlpha(col, Math.min(255, a));
            Render2DEngine.drawRect(context, bx - i, by - i, w + i * 2, 1f, c);
            Render2DEngine.drawRect(context, bx - i, ey + i - 1, w + i * 2, 1f, c);
            Render2DEngine.drawRect(context, bx - i, by - i, 1f, h + i * 2, c);
            Render2DEngine.drawRect(context, ex + i - 1, by - i, 1f, h + i * 2, c);
        }
    }

    /** Draws only the four corner brackets of the box (clean "client" style). */
    private void drawCorners(DrawContext context, float bx, float by, float ex, float ey, float len, float t, Color col) {
        // top-left
        Render2DEngine.drawRect(context, bx, by, len, t, col);
        Render2DEngine.drawRect(context, bx, by, t, len, col);
        // top-right
        Render2DEngine.drawRect(context, ex - len, by, len, t, col);
        Render2DEngine.drawRect(context, ex - t, by, t, len, col);
        // bottom-left
        Render2DEngine.drawRect(context, bx, ey - t, len, t, col);
        Render2DEngine.drawRect(context, bx, ey - len, t, len, col);
        // bottom-right
        Render2DEngine.drawRect(context, ex - len, ey - t, len, t, col);
        Render2DEngine.drawRect(context, ex - t, ey - len, t, len, col);
    }

    /** Rounded-rectangle STROKE: straight edges as quads, corners walked as short line segments. */
    private void drawRoundedOutline(DrawContext context, float x, float y, float w, float h, float r, float t, Color col) {
        r = MathHelper.clamp(r, 0f, Math.min(w, h) / 2f);
        Render2DEngine.drawRect(context, x + r, y, w - r * 2, t, col);
        Render2DEngine.drawRect(context, x + r, y + h - t, w - r * 2, t, col);
        Render2DEngine.drawRect(context, x, y + r, t, h - r * 2, col);
        Render2DEngine.drawRect(context, x + w - t, y + r, t, h - r * 2, col);

        if (r <= 0.5f) return;
        int rgb = col.getRGB();
        float[][] arcs = {
                {x + r, y + r, 180f},               // top-left
                {x + w - r, y + r, 270f},           // top-right
                {x + w - r, y + h - r, 0f},         // bottom-right
                {x + r, y + h - r, 90f}             // bottom-left
        };
        final int seg = 5;
        for (float[] arc : arcs) {
            float px = 0f, py = 0f;
            for (int i = 0; i <= seg; i++) {
                double a = Math.toRadians(arc[2] + i * (90f / seg));
                float nx = arc[0] + (float) Math.cos(a) * r;
                float ny = arc[1] + (float) Math.sin(a) * r;
                if (i > 0) Render2DEngine.drawLine(context, px, py, nx, ny, rgb);
                px = nx;
                py = ny;
            }
        }
    }

    @NotNull
    private static Vec3d[] getVectors(@NotNull Entity ent) {
        double x = ent.lastX + (ent.getX() - ent.lastX) * Render3DEngine.getTickDelta();
        double y = ent.lastY + (ent.getY() - ent.lastY) * Render3DEngine.getTickDelta();
        double z = ent.lastZ + (ent.getZ() - ent.lastZ) * Render3DEngine.getTickDelta();
        Box axisAlignedBB2 = ent.getBoundingBox();
        Box axisAlignedBB = new Box(axisAlignedBB2.minX - ent.getX() + x - 0.05, axisAlignedBB2.minY - ent.getY() + y, axisAlignedBB2.minZ - ent.getZ() + z - 0.05, axisAlignedBB2.maxX - ent.getX() + x + 0.05, axisAlignedBB2.maxY - ent.getY() + y + 0.15, axisAlignedBB2.maxZ - ent.getZ() + z + 0.05);
        return new Vec3d[]{new Vec3d(axisAlignedBB.minX, axisAlignedBB.minY, axisAlignedBB.minZ), new Vec3d(axisAlignedBB.minX, axisAlignedBB.maxY, axisAlignedBB.minZ), new Vec3d(axisAlignedBB.maxX, axisAlignedBB.minY, axisAlignedBB.minZ), new Vec3d(axisAlignedBB.maxX, axisAlignedBB.maxY, axisAlignedBB.minZ), new Vec3d(axisAlignedBB.minX, axisAlignedBB.minY, axisAlignedBB.maxZ), new Vec3d(axisAlignedBB.minX, axisAlignedBB.maxY, axisAlignedBB.maxZ), new Vec3d(axisAlignedBB.maxX, axisAlignedBB.minY, axisAlignedBB.maxZ), new Vec3d(axisAlignedBB.maxX, axisAlignedBB.maxY, axisAlignedBB.maxZ)};
    }

    private int getAreaCloudColor(AreaEffectCloudEntity ent) {
        ParticleEffect particleEffect = ent.getParticleType();
        if (particleEffect instanceof TintedParticleEffect effect) {
            return ((IAreaEffectCloudEntity)ent).getPotionContentsComponent().getColor();
        }
        return -1;
    }

    public static float getRotations(Vec2f vec) {
        if (mc.player == null) return 0;
        double x = vec.x - mc.player.getEntityPos().x;
        double z = vec.y - mc.player.getEntityPos().z;
        return (float) -(Math.atan2(x, z) * (180 / Math.PI));
    }

    public enum Colors {
        SyncColor, Custom
    }

    public enum Style {
        Box, Corners, Rounded
    }
}
