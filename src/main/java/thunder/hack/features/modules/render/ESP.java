package thunder.hack.features.modules.render;

import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.DepthTestFunction;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.BeaconBlockEntity;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.*;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.AreaEffectCloudEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.TntEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.passive.AnimalEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.projectile.thrown.EnderPearlEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.item.SpawnEggItem;
import net.minecraft.particle.ParticleEffect;
import net.minecraft.particle.TintedParticleEffect;
import net.minecraft.scoreboard.Team;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.*;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import thunder.hack.core.Managers;
import thunder.hack.features.modules.Module;
import thunder.hack.features.modules.client.HudEditor;
import thunder.hack.features.modules.combat.AntiBot;
import thunder.hack.features.modules.misc.FakePlayer;
import thunder.hack.gui.font.BkFonts;
import thunder.hack.gui.font.FontRenderer;
import thunder.hack.gui.font.FontRenderers;
import thunder.hack.injection.accesors.IAreaEffectCloudEntity;
import thunder.hack.injection.accesors.IBeaconBlockEntity;
import thunder.hack.setting.Setting;
import thunder.hack.setting.impl.ColorSetting;
import thunder.hack.setting.impl.SettingGroup;
import thunder.hack.utility.render.Render2DEngine;
import thunder.hack.utility.render.Render3DEngine;
import thunder.hack.utility.render.Render3DUtil;

import java.awt.*;
import java.util.List;
import java.util.*;

import static thunder.hack.utility.render.animation.AnimationUtility.fast;

/**
 * BKClient's ESP ({@code systems/modules/modules/visuals/ESP}, MIT, bkubiakdev), ported 1:1:
 * additive bloom + bottom-to-top fading 3D boxes per category (players / mobs / animals / items),
 * BK's Detailed player nameplate (team dot, name, red HP pill), the side health bar, the equipment
 * strip (enchantments, durability, counts), spawn-egg labels for mobs and animals, item labels with
 * BK's three grouping modes, and the vanilla-outline glow. ThunderHack's extras (TNT, beacons,
 * burrow, pearls, lingering potions, disorient radius) are kept below the BK part.
 */
public class ESP extends Module {
    public ESP() {
        super("ESP", Category.RENDER);
        instance = this;
    }

    private static ESP instance;

    /* ------------------------------------------------------------------ */
    /* BK settings                                                         */
    /* ------------------------------------------------------------------ */

    // Players (BK's main settings)
    private final Setting<SettingGroup> playersGroup = new Setting<>("Players", new SettingGroup(false, 0));
    private final Setting<Boolean> targetPlayers = new Setting<>("TargetPlayers", true).addToGroup(playersGroup);
    private final Setting<Boolean> box = new Setting<>("Box", true).addToGroup(playersGroup);
    private final Setting<Boolean> boxThemeSync = new Setting<>("BoxThemeSync", true, v -> box.getValue()).addToGroup(playersGroup);
    private final Setting<ColorSetting> boxColor = new Setting<>("BoxColor", new ColorSetting(new Color(154, 93, 255)), v -> box.getValue() && !boxThemeSync.getValue()).addToGroup(playersGroup);
    private final Setting<Boolean> gradientFill = new Setting<>("GradientFill", true, v -> box.getValue()).addToGroup(playersGroup);
    private final Setting<Boolean> gradientOutline = new Setting<>("GradientOutline", true, v -> box.getValue()).addToGroup(playersGroup);
    private final Setting<Float> espScale = new Setting<>("Scale", 1.0f, 0.5f, 2.0f).addToGroup(playersGroup);
    private final Setting<Boolean> name = new Setting<>("Name", true).addToGroup(playersGroup);
    private final Setting<Float> nameSize = new Setting<>("NameSize", 0.10f, 0.03f, 0.30f, v -> name.getValue()).addToGroup(playersGroup);
    private final Setting<Boolean> hp = new Setting<>("HP", true).addToGroup(playersGroup);
    private final Setting<Boolean> items = new Setting<>("Items", false).addToGroup(playersGroup);
    private final Setting<Boolean> armor = new Setting<>("Armor", false).addToGroup(playersGroup);
    private final Setting<Boolean> itemCountLabel = new Setting<>("ItemCount", true, v -> armor.getValue() || items.getValue()).addToGroup(playersGroup);
    private final Setting<Boolean> durability = new Setting<>("Durability", false, v -> armor.getValue() || items.getValue()).addToGroup(playersGroup);
    private final Setting<DurabilityStyle> durabilityStyle = new Setting<>("DurabilityStyle", DurabilityStyle.Percent, v -> durability.getValue() && (armor.getValue() || items.getValue())).addToGroup(playersGroup);
    private final Setting<Boolean> enchantments = new Setting<>("Enchantments", false, v -> armor.getValue() || items.getValue()).addToGroup(playersGroup);
    private final Setting<Boolean> glow = new Setting<>("Glow", false).addToGroup(playersGroup);
    private final Setting<ColorSetting> glowColor = new Setting<>("GlowColor", new ColorSetting(new Color(0, 255, 0)), v -> glow.getValue()).addToGroup(playersGroup);
    private final Setting<Float> maxDistance = new Setting<>("MaxDistance", 96f, 8f, 192f).addToGroup(playersGroup);

    // Mobs
    private final Setting<SettingGroup> mobsGroup = new Setting<>("Mobs", new SettingGroup(false, 0));
    private final Setting<Boolean> targetMobs = new Setting<>("TargetMobs", true).addToGroup(mobsGroup);
    private final Setting<Boolean> mobBox = new Setting<>("MobBox", true).addToGroup(mobsGroup);
    private final Setting<Boolean> mobTheme = new Setting<>("MobThemeSync", true, v -> mobBox.getValue()).addToGroup(mobsGroup);
    private final Setting<ColorSetting> mobBoxColor = new Setting<>("MobBoxColor", new ColorSetting(new Color(154, 93, 255)), v -> mobBox.getValue() && !mobTheme.getValue()).addToGroup(mobsGroup);
    private final Setting<Boolean> mobFill = new Setting<>("MobGradientFill", true, v -> mobBox.getValue()).addToGroup(mobsGroup);
    private final Setting<Boolean> mobOutline = new Setting<>("MobGradientOutline", true, v -> mobBox.getValue()).addToGroup(mobsGroup);
    private final Setting<Boolean> mobGlow = new Setting<>("MobGlow", false).addToGroup(mobsGroup);
    private final Setting<ColorSetting> mobGlowColor = new Setting<>("MobGlowColor", new ColorSetting(new Color(0, 255, 0)), v -> mobGlow.getValue()).addToGroup(mobsGroup);
    private final Setting<Float> mobRange = new Setting<>("MobMaxDistance", 96f, 8f, 192f).addToGroup(mobsGroup);
    private final Setting<Boolean> mobName = new Setting<>("MobName", true).addToGroup(mobsGroup);
    private final Setting<Float> mobNameSize = new Setting<>("MobNameSize", 0.10f, 0.03f, 0.30f, v -> mobName.getValue()).addToGroup(mobsGroup);
    private final Setting<Float> mobScale = new Setting<>("MobScale", 1.0f, 0.5f, 2.0f).addToGroup(mobsGroup);

    // Animals
    private final Setting<SettingGroup> animalsGroup = new Setting<>("Animals", new SettingGroup(false, 0));
    private final Setting<Boolean> targetAnimals = new Setting<>("TargetAnimals", false).addToGroup(animalsGroup);
    private final Setting<Boolean> animalBox = new Setting<>("AnimalBox", true).addToGroup(animalsGroup);
    private final Setting<Boolean> animalTheme = new Setting<>("AnimalThemeSync", true, v -> animalBox.getValue()).addToGroup(animalsGroup);
    private final Setting<ColorSetting> animalBoxColor = new Setting<>("AnimalBoxColor", new ColorSetting(new Color(154, 93, 255)), v -> animalBox.getValue() && !animalTheme.getValue()).addToGroup(animalsGroup);
    private final Setting<Boolean> animalFill = new Setting<>("AnimalGradientFill", true, v -> animalBox.getValue()).addToGroup(animalsGroup);
    private final Setting<Boolean> animalOutline = new Setting<>("AnimalGradientOutline", true, v -> animalBox.getValue()).addToGroup(animalsGroup);
    private final Setting<Boolean> animalGlow = new Setting<>("AnimalGlow", false).addToGroup(animalsGroup);
    private final Setting<ColorSetting> animalGlowColor = new Setting<>("AnimalGlowColor", new ColorSetting(new Color(0, 255, 0)), v -> animalGlow.getValue()).addToGroup(animalsGroup);
    private final Setting<Float> animalRange = new Setting<>("AnimalMaxDistance", 96f, 8f, 192f).addToGroup(animalsGroup);
    private final Setting<Boolean> animalName = new Setting<>("AnimalName", true).addToGroup(animalsGroup);
    private final Setting<Float> animalNameSize = new Setting<>("AnimalNameSize", 0.10f, 0.03f, 0.30f, v -> animalName.getValue()).addToGroup(animalsGroup);
    private final Setting<Float> animalScale = new Setting<>("AnimalScale", 1.0f, 0.5f, 2.0f).addToGroup(animalsGroup);

    // Dropped items
    private final Setting<SettingGroup> dropsGroup = new Setting<>("Drops", new SettingGroup(false, 0));
    private final Setting<Boolean> targetItems = new Setting<>("TargetItems", false).addToGroup(dropsGroup);
    private final Setting<Boolean> dropBox = new Setting<>("DropBox", true).addToGroup(dropsGroup);
    private final Setting<Boolean> dropTheme = new Setting<>("DropThemeSync", true, v -> dropBox.getValue()).addToGroup(dropsGroup);
    private final Setting<ColorSetting> dropBoxColor = new Setting<>("DropBoxColor", new ColorSetting(new Color(154, 93, 255)), v -> dropBox.getValue() && !dropTheme.getValue()).addToGroup(dropsGroup);
    private final Setting<Boolean> dropFill = new Setting<>("DropGradientFill", true, v -> dropBox.getValue()).addToGroup(dropsGroup);
    private final Setting<Boolean> dropOutline = new Setting<>("DropGradientOutline", true, v -> dropBox.getValue()).addToGroup(dropsGroup);
    private final Setting<Boolean> dropGlow = new Setting<>("DropGlow", false).addToGroup(dropsGroup);
    private final Setting<ColorSetting> dropGlowColor = new Setting<>("DropGlowColor", new ColorSetting(new Color(0, 255, 0)), v -> dropGlow.getValue()).addToGroup(dropsGroup);
    private final Setting<Float> dropRange = new Setting<>("DropMaxDistance", 96f, 8f, 192f).addToGroup(dropsGroup);
    private final Setting<Boolean> dropName = new Setting<>("DropName", true).addToGroup(dropsGroup);
    private final Setting<Float> dropNameSize = new Setting<>("DropNameSize", 0.10f, 0.03f, 0.30f, v -> dropName.getValue()).addToGroup(dropsGroup);
    private final Setting<Float> dropScale = new Setting<>("DropScale", 1.0f, 0.5f, 2.0f).addToGroup(dropsGroup);
    private final Setting<ItemMode> itemDisplayMode = new Setting<>("ItemMode", ItemMode.Individual).addToGroup(dropsGroup);

    /* ------------------------------------------------------------------ */
    /* ThunderHack extras                                                  */
    /* ------------------------------------------------------------------ */

    private final Setting<SettingGroup> extras = new Setting<>("Extras", new SettingGroup(false, 0));
    private final Setting<Boolean> lingeringPotions = new Setting<>("LingeringPotions", false).addToGroup(extras);
    private final Setting<Boolean> tntFuse = new Setting<>("TNTFuse", false).addToGroup(extras);
    private final Setting<Float> tntrange = new Setting<>("TNTRange", 8.0f, 0f, 8f).addToGroup(extras);
    private final Setting<ColorSetting> tntFuseText = new Setting<>("TNTFuseText", new ColorSetting(new Color(-1)), v -> tntFuse.getValue()).addToGroup(extras);
    private final Setting<Boolean> tntRadius = new Setting<>("TNTRadius", false).addToGroup(extras);
    private final Setting<ColorSetting> tntRadiusColor = new Setting<>("TNTSphereColor", new ColorSetting(new Color(-1)), v -> tntRadius.getValue()).addToGroup(extras);
    private final Setting<Boolean> beaconRadius = new Setting<>("BeaconRadius", false).addToGroup(extras);
    private final Setting<Boolean> keepY = new Setting<>("KeepY", false, v -> beaconRadius.getValue()).addToGroup(extras);
    private final Setting<ColorSetting> sphereColor = new Setting<>("SphereColor", new ColorSetting(new Color(-1)), v -> beaconRadius.getValue()).addToGroup(extras);
    private final Setting<ColorSetting> beakonColor = new Setting<>("BeakonColor", new ColorSetting(new Color(-1)), v -> beaconRadius.getValue()).addToGroup(extras);
    private final Setting<Boolean> burrow = new Setting<>("Burrow", false).addToGroup(extras);
    private final Setting<ColorSetting> burrowTextColor = new Setting<>("BurrowTextColor", new ColorSetting(new Color(-1)), v -> burrow.getValue()).addToGroup(extras);
    private final Setting<ColorSetting> burrowColor = new Setting<>("BurrowColor", new ColorSetting(new Color(-1)), v -> burrow.getValue()).addToGroup(extras);
    private final Setting<Boolean> pearls = new Setting<>("Pearls", false).addToGroup(extras);
    private final Setting<Boolean> dizorentRadius = new Setting<>("DizorentRadius", true).addToGroup(extras);
    private final Setting<ColorSetting> dizorentColor = new Setting<>("DizorentColor", new ColorSetting(new Color(0xB300F1CC, true)), v -> dizorentRadius.getValue()).addToGroup(extras);

    private float dizorentAnimation = 0f;

    public enum DurabilityStyle { Percent, Bar }

    public enum ItemMode { Individual, Count, Column }


    /* ------------------------------------------------------------------ */
    /* Per-category profile                                                */
    /* ------------------------------------------------------------------ */

    private static final float BAR_WIDTH = 1.0F;
    private static final float BORDER = 0.3F;
    private static final float BAR_GAP = 1.0F;
    private static final Color BAR_BORDER = new Color(0, 0, 0, 180);
    private static final Color BAR_BG = new Color(0, 0, 0, 120);
    private static final Color BAR_OVERLAY = new Color(0, 0, 0, 210);
    /** BK's default theme is dark; the light-theme colours of BK are not used here. */
    private static final boolean DARK = true;

    private int tab(Entity entity) {
        return entity instanceof PlayerEntity ? 0 : entity instanceof ItemEntity ? 3 : entity instanceof AnimalEntity ? 2 : 1;
    }

    private boolean boxOn(int t) { return switch (t) { case 1 -> mobBox.getValue(); case 2 -> animalBox.getValue(); case 3 -> dropBox.getValue(); default -> box.getValue(); }; }
    private boolean fillOn(int t) { return switch (t) { case 1 -> mobFill.getValue(); case 2 -> animalFill.getValue(); case 3 -> dropFill.getValue(); default -> gradientFill.getValue(); }; }
    private boolean outlineOn(int t) { return switch (t) { case 1 -> mobOutline.getValue(); case 2 -> animalOutline.getValue(); case 3 -> dropOutline.getValue(); default -> gradientOutline.getValue(); }; }
    private boolean glowOn(int t) { return switch (t) { case 1 -> mobGlow.getValue(); case 2 -> animalGlow.getValue(); case 3 -> dropGlow.getValue(); default -> glow.getValue(); }; }
    private Color glowCol(int t) { return (switch (t) { case 1 -> mobGlowColor; case 2 -> animalGlowColor; case 3 -> dropGlowColor; default -> glowColor; }).getValue().getColorObject(); }
    private float range(int t) { return switch (t) { case 1 -> mobRange.getValue(); case 2 -> animalRange.getValue(); case 3 -> dropRange.getValue(); default -> maxDistance.getValue(); }; }

    /** BK: theme accent when synced, the custom colour otherwise. */
    private Color boxCol(int t) {
        boolean theme = switch (t) { case 1 -> mobTheme.getValue(); case 2 -> animalTheme.getValue(); case 3 -> dropTheme.getValue(); default -> boxThemeSync.getValue(); };
        if (theme) return HudEditor.getColor(1);
        return (switch (t) { case 1 -> mobBoxColor; case 2 -> animalBoxColor; case 3 -> dropBoxColor; default -> boxColor; }).getValue().getColorObject();
    }

    private boolean targetOn(int t) { return switch (t) { case 1 -> targetMobs.getValue(); case 2 -> targetAnimals.getValue(); case 3 -> targetItems.getValue(); default -> targetPlayers.getValue(); }; }

    private boolean isTarget(Entity entity) {
        if (mc.player == null || mc.world == null || entity == null || entity == mc.player || entity.isSpectator() || entity.isRemoved()) return false;
        float distance = range(tab(entity));
        if (mc.player.squaredDistanceTo(entity) > distance * distance) return false;
        if (entity instanceof PlayerEntity player) {
            if (!targetPlayers.getValue() || player == FakePlayer.fakePlayer) return false;
            String n = player.getGameProfile().name();
            if (n.startsWith("-") || n.contains("@") || n.contains(".")) return false;
            return !AntiBot.bots.contains(player);
        }
        // MobEntity includes every current animal and mob, without a species whitelist.
        if (entity instanceof MobEntity)
            return entity instanceof AnimalEntity ? targetAnimals.getValue() : targetMobs.getValue();
        // Items stay targets while flying, bouncing or being picked up.
        return entity instanceof ItemEntity && targetItems.getValue();
    }

    private List<Entity> collectTargets() {
        if (mc.player == null || mc.world == null) return List.of();
        List<Entity> list = new ArrayList<>();
        for (Entity entity : mc.world.getEntities()) if (isTarget(entity)) list.add(entity);
        return list;
    }

    /* ------------------------------------------------------------------ */
    /* Glow / vanilla name hooks (mixins)                                  */
    /* ------------------------------------------------------------------ */

    public static boolean shouldGlow(Entity entity) {
        return instance != null && instance.isEnabled() && instance.glowOn(instance.tab(entity)) && instance.isTarget(entity);
    }

    /** Opaque ARGB for the vanilla entity outline. */
    public static int getGlowColor(Entity entity) {
        if (instance == null) return 0xFF00FF00;
        return 0xFF000000 | (instance.glowCol(instance.tab(entity)).getRGB() & 0xFFFFFF);
    }

    /** BK hides the vanilla player label while its own nameplate is drawn for that player. */
    public static boolean hidesVanillaName(int entityId) {
        if (instance == null || !instance.isEnabled() || !instance.name.getValue() || mc.world == null) return false;
        Entity entity = mc.world.getEntityById(entityId);
        return entity instanceof PlayerEntity && instance.isTarget(entity);
    }

    /* ------------------------------------------------------------------ */
    /* World pass: GradientBoxRenderer                                     */
    /* ------------------------------------------------------------------ */

    private static final Identifier BLOOM = Identifier.of("bkclient", "textures/esp/box_bloom.png");
    private static final int[][] FACES = {
            {0, 1, 2, 3}, {4, 5, 6, 7}, {3, 2, 6, 7},
            {0, 1, 5, 4}, {0, 3, 7, 4}, {1, 2, 6, 5}
    };
    private static final int[][] EDGES = {
            {0, 1}, {1, 2}, {2, 3}, {3, 0}, {4, 5}, {5, 6},
            {6, 7}, {7, 4}, {0, 4}, {1, 5}, {2, 6}, {3, 7}
    };

    // BK: setupRender3D(true) = blend(SRC_ALPHA, ONE), no depth test, no depth write, no cull.
    private static final RenderPipeline BLOOM_PIPELINE = RenderPipeline.builder(RenderPipelines.POSITION_TEX_COLOR_SNIPPET)
            .withLocation(Identifier.of("gombal", "pipeline/bk_esp_bloom"))
            .withVertexFormat(VertexFormats.POSITION_TEXTURE_COLOR, VertexFormat.DrawMode.QUADS)
            .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
            .withDepthWrite(false)
            .withCull(false)
            .withBlend(BlendFunction.LIGHTNING)
            .build();
    private static final RenderPipeline FACE_PIPELINE = RenderPipeline.builder(RenderPipelines.POSITION_COLOR_SNIPPET)
            .withLocation(Identifier.of("gombal", "pipeline/bk_esp_faces"))
            .withVertexFormat(RenderPipelines.DEBUG_QUADS.getVertexFormat(), RenderPipelines.DEBUG_QUADS.getVertexFormatMode())
            .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
            .withDepthWrite(false)
            .withCull(false)
            .withBlend(BlendFunction.LIGHTNING)
            .build();
    private static final RenderPipeline EDGE_PIPELINE = RenderPipeline.builder(RenderPipelines.RENDERTYPE_LINES_SNIPPET)
            .withLocation(Identifier.of("gombal", "pipeline/bk_esp_edges"))
            .withVertexFormat(RenderPipelines.LINES.getVertexFormat(), RenderPipelines.LINES.getVertexFormatMode())
            .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
            .withDepthWrite(false)
            .withCull(false)
            .withBlend(BlendFunction.LIGHTNING)
            .build();

    private static RenderLayer bloomLayer, faceLayer, edgeLayer;
    private static net.minecraft.client.gl.GpuSampler sampler;

    private static net.minecraft.client.gl.GpuSampler sampler() {
        if (sampler == null)
            sampler = com.mojang.blaze3d.systems.RenderSystem.getDevice().createSampler(
                    com.mojang.blaze3d.textures.AddressMode.CLAMP_TO_EDGE, com.mojang.blaze3d.textures.AddressMode.CLAMP_TO_EDGE,
                    com.mojang.blaze3d.textures.FilterMode.LINEAR, com.mojang.blaze3d.textures.FilterMode.LINEAR,
                    1, java.util.OptionalDouble.empty());
        return sampler;
    }

    private static void ensureLayers() {
        if (bloomLayer != null) return;
        bloomLayer = RenderLayer.of("bk_esp_bloom", RenderSetup.builder(BLOOM_PIPELINE).texture("Sampler0", BLOOM, ESP::sampler).build());
        faceLayer = RenderLayer.of("bk_esp_faces", RenderSetup.builder(FACE_PIPELINE).build());
        edgeLayer = RenderLayer.of("bk_esp_edges", RenderSetup.builder(EDGE_PIPELINE).build());
    }

    private static Matrix4f viewMatrix() {
        Camera camera = mc.gameRenderer.getCamera();
        MatrixStack matrices = new MatrixStack();
        matrices.multiply(RotationAxis.POSITIVE_X.rotationDegrees(camera.getPitch()));
        matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(camera.getYaw() + 180f));
        return new Matrix4f(matrices.peek().getPositionMatrix());
    }

    private static int argb(Color c, float alphaMul) {
        int a = MathHelper.clamp(Math.round(c.getAlpha() * alphaMul), 0, 255);
        return (a << 24) | (c.getRGB() & 0xFFFFFF);
    }

    @SuppressWarnings("unchecked")
    private final List<Entity>[] categoryTargets = new List[]{new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), new ArrayList<>()};

    private void renderBoxes() {
        List<Entity> targets = collectTargets();
        for (List<Entity> group : categoryTargets) group.clear();
        for (Entity entity : targets) categoryTargets[tab(entity)].add(entity);
        boolean any = false;
        for (int c = 0; c < 4; c++) if (boxOn(c) && !categoryTargets[c].isEmpty()) any = true;
        if (!any) return;

        ensureLayers();
        Matrix4f view = viewMatrix();
        Vec3d camera = mc.gameRenderer.getCamera().getCameraPos();
        Quaternionf rotation = mc.gameRenderer.getCamera().getRotation();
        float tickDelta = Render3DEngine.getTickDelta();

        // Bloom billboards first, then the faces, then the edges (BK's draw order).
        VertexConsumer bloom = Render3DUtil.buffer(bloomLayer);
        for (int c = 0; c < 4; c++) {
            if (!boxOn(c)) continue;
            Color color = boxCol(c);
            boolean fill = fillOn(c);
            for (Entity entity : categoryTargets[c]) {
                Vec3d pos = entity.getLerpedPos(tickDelta).subtract(camera);
                float size = (entity.getWidth() + entity.getHeight()) * 1.2F;
                if (fill) {
                    bloom(bloom, view, rotation, pos, entity.getHeight() / 4.0F, size, color);
                } else {
                    bloom(bloom, view, rotation, pos, entity.getHeight() / 3.0F, size, color);
                    bloom(bloom, view, rotation, pos, entity.getHeight() / 1.5F, size, color);
                }
            }
        }
        Render3DUtil.flush(bloomLayer);

        // The choices control fading, not whether the faces/edges are drawn.
        VertexConsumer faces = Render3DUtil.buffer(faceLayer);
        for (int c = 0; c < 4; c++) {
            if (!boxOn(c)) continue;
            Color color = boxCol(c);
            boolean fill = fillOn(c), outline = outlineOn(c);
            int bottom = argb(color, fill && !outline ? 0.35F : 0.1F);
            int top = fill ? argb(color, 0f) : bottom;
            for (Entity entity : categoryTargets[c]) box(faces, view, entity, camera, tickDelta, bottom, top, FACES, false);
        }
        Render3DUtil.flush(faceLayer);

        VertexConsumer edges = Render3DUtil.buffer(edgeLayer);
        for (int c = 0; c < 4; c++) {
            if (!boxOn(c)) continue;
            Color color = boxCol(c);
            int bottom = argb(color, 1f);
            int top = outlineOn(c) ? argb(color, 0f) : bottom;
            for (Entity entity : categoryTargets[c]) box(edges, view, entity, camera, tickDelta, bottom, top, EDGES, true);
        }
        Render3DUtil.flush(edgeLayer);
    }

    private final Vector3f corner = new Vector3f();

    /** BK's DrawUtility.drawImage on a camera-facing quad: (-s/2,-s/2)..(s/2,s/2) at half alpha. */
    private void bloom(VertexConsumer buffer, Matrix4f view, Quaternionf rotation, Vec3d pos, float height, float size, Color color) {
        int col = argb(color, 0.5F);
        float h = size / 2f;
        float[][] xy = {{-h, h}, {h, h}, {h, -h}, {-h, -h}};
        float[][] uv = {{0, 1}, {1, 1}, {1, 0}, {0, 0}};
        for (int i = 0; i < 4; i++) {
            corner.set(xy[i][0], xy[i][1], 0f);
            rotation.transform(corner);
            Render3DUtil.vertexTex(buffer, view, (float) pos.x + corner.x, (float) (pos.y + height) + corner.y, (float) pos.z + corner.z,
                    uv[i][0], uv[i][1], col);
        }
    }

    private void box(VertexConsumer buffer, Matrix4f view, Entity entity, Vec3d camera, float tickDelta,
                     int bottom, int top, int[][] primitives, boolean lines) {
        Vec3d pos = entity.getLerpedPos(tickDelta).subtract(camera);
        float halfWidth = entity.getWidth() / 2.0F;
        float minX = (float) pos.x - halfWidth, maxX = (float) pos.x + halfWidth;
        float minY = (float) pos.y, maxY = minY + entity.getHeight();
        float minZ = (float) pos.z - halfWidth, maxZ = (float) pos.z + halfWidth;
        for (int[] primitive : primitives) {
            if (lines) {
                Vector3f a = cornerOf(view, primitive[0], minX, maxX, minY, maxY, minZ, maxZ);
                Vector3f b = cornerOf(view, primitive[1], minX, maxX, minY, maxY, minZ, maxZ);
                float nx = b.x - a.x, ny = b.y - a.y, nz = b.z - a.z;
                float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
                if (len == 0f) len = 1f;
                buffer.vertex(a.x, a.y, a.z).color(primitive[0] >= 4 ? top : bottom).normal(nx / len, ny / len, nz / len).lineWidth(1.0f);
                buffer.vertex(b.x, b.y, b.z).color(primitive[1] >= 4 ? top : bottom).normal(nx / len, ny / len, nz / len).lineWidth(1.0f);
            } else {
                for (int vertex : primitive) {
                    Vector3f v = cornerOf(view, vertex, minX, maxX, minY, maxY, minZ, maxZ);
                    buffer.vertex(v.x, v.y, v.z).color(vertex >= 4 ? top : bottom);
                }
            }
        }
    }

    private static Vector3f cornerOf(Matrix4f view, int vertex, float minX, float maxX, float minY, float maxY, float minZ, float maxZ) {
        int c = vertex & 3;
        return view.transformPosition(c == 1 || c == 2 ? maxX : minX, vertex >= 4 ? maxY : minY, c >= 2 ? maxZ : minZ, new Vector3f());
    }

    /* ------------------------------------------------------------------ */
    /* Frame                                                               */
    /* ------------------------------------------------------------------ */

    @Override
    public void onRender3D(MatrixStack stack) {
        if (mc.player == null || mc.world == null || mc.options.hudHidden) return;
        renderBoxes();
        renderExtras(stack);
    }

    @Override
    public void onRender2D(DrawContext context) {
        if (mc.player == null || mc.world == null || mc.options.hudHidden) return;
        renderPearls(context);

        List<Entity> targets = collectTargets();
        List<PlayerEntity> playerTargets = new ArrayList<>();
        List<ItemEntity> itemTargets = new ArrayList<>();
        List<Entity> otherTargets = new ArrayList<>();
        for (Entity entity : targets) {
            if (entity instanceof PlayerEntity player) playerTargets.add(player);
            else if (entity instanceof ItemEntity item) itemTargets.add(item);
            else otherTargets.add(entity);
        }

        for (PlayerEntity player : playerTargets) renderPlayerEsp(context, player);

        for (List<ItemEntity> group : groupEntities(itemTargets, 9.0F)) {
            if (group.size() == 1 || itemDisplayMode.getValue() == ItemMode.Individual) {
                for (ItemEntity item : group) renderItemEsp(context, item);
            } else if (itemDisplayMode.getValue() == ItemMode.Count) {
                renderItemGroupCount(context, group);
            } else {
                renderItemGroupColumn(context, group);
            }
        }

        for (Entity entity : otherTargets) {
            if (entity instanceof AnimalEntity animal) renderLabelEsp(context, animal, true);
            else if (entity instanceof MobEntity mob) renderLabelEsp(context, mob, false);
        }
    }

    @Override
    public void onDisable() {
        for (List<Entity> group : categoryTargets) group.clear();
    }

    /* ------------------------------------------------------------------ */
    /* Screen helpers                                                      */
    /* ------------------------------------------------------------------ */

    /** BK's Utils.worldToScreen: null when the point is behind the camera. */
    private static Vec2f worldToScreen(Vec3d pos) {
        Vec3d s = Render3DEngine.worldSpaceToScreenSpace(pos);
        if (!(s.z > 0 && s.z < 1)) return null;
        return new Vec2f((float) s.x, (float) s.y);
    }

    private float[] computeScreenBounds(Entity entity, float tickDelta) {
        Vec3d p = entity.getLerpedPos(tickDelta);
        float hw = entity.getWidth() * 0.5F, h = entity.getHeight();
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        for (int i = 0; i < 8; i++) {
            Vec2f s = worldToScreen(new Vec3d(p.x + ((i & 1) == 0 ? -hw : hw), p.y + (i < 4 ? 0 : h), p.z + ((i & 2) == 0 ? -hw : hw)));
            if (s == null) continue;
            minX = Math.min(minX, s.x);
            minY = Math.min(minY, s.y);
            maxX = Math.max(maxX, s.x);
            maxY = Math.max(maxY, s.y);
        }
        if (minX >= maxX || minY >= maxY) return null;
        int sw = mc.getWindow().getScaledWidth(), sh = mc.getWindow().getScaledHeight();
        if (maxX < 0 || minX > sw || maxY < 0 || minY > sh) return null;
        return new float[]{minX, minY, maxX, maxY, maxX - minX, maxY - minY};
    }

    private static float fontHeight(FontRenderer font) {
        return font.getFontHeight("Ag");
    }

    private static void rect(DrawContext c, float x, float y, float w, float h, Color color) {
        Render2DEngine.drawRect(c, x, y, w, h, color);
    }

    private static void round(DrawContext c, float x, float y, float w, float h, float r, Color color) {
        Render2DEngine.drawRound(c, x, y, w, h, Math.min(r, Math.min(w, h) / 2f), color);
    }

    private static void drawItem(DrawContext c, ItemStack stack, float x, float y, float scale) {
        c.getMatrices().pushMatrix();
        c.getMatrices().translate(x, y);
        c.getMatrices().scale(scale, scale);
        c.drawItem(stack, 0, 0);
        c.getMatrices().popMatrix();
    }

    private final Map<EntityType<?>, ItemStack> spawnEggs = new IdentityHashMap<>();

    private ItemStack spawnEgg(EntityType<?> type) {
        return spawnEggs.computeIfAbsent(type, t -> {
            SpawnEggItem egg = SpawnEggItem.forEntity(t);
            return egg != null ? new ItemStack(egg) : ItemStack.EMPTY;
        });
    }

    /* ------------------------------------------------------------------ */
    /* Players: BK's Detailed overlay                                      */
    /* ------------------------------------------------------------------ */

    private void renderPlayerEsp(DrawContext context, PlayerEntity player) {
        float tickDelta = Render3DEngine.getTickDelta();
        float[] bounds = computeScreenBounds(player, tickDelta);
        if (bounds == null) return;
        Vec3d pos = player.getLerpedPos(tickDelta);
        drawDetailedOverlay(context, player, bounds, worldToScreen(pos.add(0, player.getHeight() + 0.25, 0)));
    }

    private void drawDetailedOverlay(DrawContext context, PlayerEntity player, float[] bounds, Vec2f headScreen) {
        float boxMinY = bounds[1], boxMaxX = bounds[2], boxH = bounds[5];

        if (hp.getValue()) {
            float scale = espScale.getValue();
            float width = BAR_WIDTH * scale;
            float border = BORDER * scale;
            float barX = boxMaxX + BAR_GAP * scale;
            float barY = boxMinY;
            float barH = boxH;
            float ratio = MathHelper.clamp(player.getHealth() / player.getMaxHealth(), 0.0F, 1.0F);

            rect(context, barX - border, barY - border, width + border * 2, barH + border * 2, BAR_BORDER);
            rect(context, barX, barY, width, barH, BAR_BG);
            if (ratio > 0.001F) {
                // VerticalGradient(green top, red bottom)
                Render2DEngine.draw2DGradientRect(context, barX, barY, barX + width, barY + barH,
                        new Color(255, 0, 0), new Color(0, 255, 0), new Color(255, 0, 0), new Color(0, 255, 0));
                float emptyH = barH * (1.0F - ratio);
                if (emptyH > 0.5F) rect(context, barX, barY, width, emptyH, BAR_OVERLAY);
            }
        }

        if (headScreen == null) return;
        float scale = espScale.getValue();
        var matrices = context.getMatrices();
        matrices.pushMatrix();
        try {
            matrices.translate(headScreen.x, headScreen.y);
            matrices.scale(scale, scale);
            matrices.translate(-headScreen.x, -headScreen.y);
            float iconSize = MathHelper.clamp(boxH * 0.16F, 9.0F, 16.0F);

            float nametagTop = Float.NaN;
            if (name.getValue()) {
                boolean isFriend = Managers.FRIEND.isFriend(player);
                String displayNameStr = player.getDisplayName().getString();

                float fontSize = MathHelper.clamp(boxH * nameSize.getValue(), 5.5F, 12.0F);
                FontRenderer font = BkFonts.MEDIUM.get(fontSize);
                float textWidth = font.getStringWidth(displayNameStr);

                String hpText = String.valueOf(MathHelper.ceil(player.getHealth()));
                float hpFontSize = fontSize * 0.9F;
                FontRenderer hpFont = BkFonts.MEDIUM.get(hpFontSize);
                float hpTextWidth = hpFont.getStringWidth(hpText);

                float hpPadding = fontSize * 0.20F;
                float hpRectWidth = hpTextWidth + hpPadding * 2.0F;
                float hpRectHeight = hpFontSize + fontSize * 0.2F;
                float hpRectRadius = hpRectHeight * 0.25F;

                float padding = fontSize * 0.18F;
                float dotSize = fontSize * 0.5F;
                float gap = fontSize * 0.2F;
                float hpGap = fontSize * 0.25F;

                float bgHeight = fontSize + padding * 2.0F;
                float radius = bgHeight / 2.0F;
                float bgY = headScreen.y - bgHeight - 2.0F;
                nametagTop = bgY;

                Color bgColor = DARK ? new Color(0, 0, 0, 140) : new Color(255, 255, 255, 160);
                Color nameColor = DARK ? Color.WHITE : Color.BLACK;
                Color dotColor = nameColor;
                Team team = player.getScoreboardTeam();
                if (team != null) {
                    Formatting formatting = team.getColor();
                    if (formatting != null && formatting.getColorValue() != null)
                        dotColor = new Color(0xFF000000 | formatting.getColorValue(), true);
                }
                if (isFriend) {
                    dotColor = new Color(50, 220, 100);
                    nameColor = dotColor;
                }

                float totalW = dotSize + gap + textWidth + hpGap + hpRectWidth;
                float dotX = headScreen.x - totalW / 2.0F;
                float textX = dotX + dotSize + gap;
                float hpRectX = textX + textWidth + hpGap;
                float bgX = dotX - padding;
                float bgWidth = totalW + padding * 2.0F;

                round(context, bgX, bgY, bgWidth, bgHeight, radius, bgColor);
                float dotY = bgY + (bgHeight - dotSize) / 2.0F;
                round(context, dotX, dotY, dotSize, dotSize, dotSize / 2.0F, dotColor);
                font.drawString(context, displayNameStr, textX, bgY + (bgHeight - fontHeight(font)) / 2.0F, nameColor);

                float hpRectY = bgY + (bgHeight - hpRectHeight) / 2.0F;
                round(context, hpRectX, hpRectY, hpRectWidth, hpRectHeight, hpRectRadius, new Color(230, 60, 60, 230));
                hpFont.drawString(context, hpText, hpRectX + (hpRectWidth - hpTextWidth) / 2.0F,
                        bgY + (bgHeight - fontHeight(hpFont)) / 2.0F, Color.WHITE);
            }

            float bottom = Float.isNaN(nametagTop) ? headScreen.y - 1 : nametagTop - 3;
            List<ItemStack> equipment = new ArrayList<>();
            if (items.getValue() && !player.getMainHandStack().isEmpty()) equipment.add(player.getMainHandStack());
            if (armor.getValue()) {
                for (EquipmentSlot slot : new EquipmentSlot[]{EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
                    ItemStack stack = player.getEquippedStack(slot);
                    if (!stack.isEmpty()) equipment.add(stack);
                }
            }
            if (items.getValue() && !player.getOffHandStack().isEmpty()) equipment.add(player.getOffHandStack());
            renderDetailedEquipment(context, equipment, headScreen.x, bottom, iconSize);
        } finally {
            matrices.popMatrix();
        }
    }

    private void renderDetailedEquipment(DrawContext c, List<ItemStack> stacks, float cx, float bottom, float size) {
        if (stacks.isEmpty()) return;
        FontRenderer font = BkFonts.MEDIUM.get(6F);
        float line = Math.max(7.5F, fontHeight(font) + 1.5F), cell = size + 4;
        List<List<String>> labels = new ArrayList<>();
        for (ItemStack stack : stacks) {
            List<String> text = new ArrayList<>();
            if (enchantments.getValue()) {
                var entries = new ArrayList<>(stack.getEnchantments().getEnchantmentEntries());
                entries.sort(Comparator.comparing(e -> e.getKey().getKey().map(k -> k.getValue().toString()).orElse("")));
                for (var entry : entries) {
                    String id = entry.getKey().getKey().map(k -> k.getValue().getPath()).orElse("enchant");
                    text.add(shortEnchantment(id) + " " + entry.getIntValue());
                }
                if (text.size() > 4) {
                    int extra = text.size() - 3;
                    text = new ArrayList<>(text.subList(0, 3));
                    text.add("+" + extra);
                }
            }
            for (String label : text) cell = Math.max(cell, font.getStringWidth(label) + 4);
            labels.add(text);
        }
        boolean percent = durabilityStyle.getValue() == DurabilityStyle.Percent;
        boolean info = durability.getValue() && stacks.stream().anyMatch(ItemStack::isDamageable)
                || itemCountLabel.getValue() && stacks.stream().anyMatch(stack -> stack.getCount() > 1);
        float infoH = info ? line + 1 : 0, height = size + infoH + 3, width = stacks.size() * cell + 4;
        float top = bottom - height, left = cx - width / 2;
        Color ink = DARK ? Color.WHITE : new Color(25, 28, 30);
        round(c, left, top, width, height, 3, DARK ? new Color(12, 15, 17, 190) : new Color(245, 247, 249, 190));
        for (int i = 0; i < stacks.size(); i++) {
            ItemStack stack = stacks.get(i);
            float center = left + 2 + (i + .5F) * cell;
            List<String> text = labels.get(i);
            float ty = top - 2 - text.size() * line;
            for (String label : text) {
                font.drawCenteredString(c, label, center, ty, ink);
                ty += line;
            }
            drawItem(c, stack, center - size / 2, top + 1, size / 16);
            float infoY = top + size + 2;
            if (durability.getValue() && stack.isDamageable()) {
                float remaining = MathHelper.clamp(1 - stack.getDamage() / (float) stack.getMaxDamage(), 0, 1);
                if (percent) font.drawCenteredString(c, Math.round(remaining * 100) + "%", center, infoY, ink);
                else {
                    Color color = remaining < .25F ? new Color(240, 85, 90) : remaining < .5F ? new Color(240, 183, 65) : new Color(65, 204, 146);
                    float barWidth = size - 4, barX = center - barWidth / 2, barY = infoY + 1;
                    round(c, barX, barY, barWidth, 1, .25F, new Color(ink.getRed(), ink.getGreen(), ink.getBlue(), Math.round(ink.getAlpha() * .22F)));
                    if (remaining > 0) round(c, barX, barY, barWidth * remaining, 1, Math.min(.25F, barWidth * remaining / 2), color);
                }
            }
            if (itemCountLabel.getValue() && stack.getCount() > 1) {
                String count = Integer.toString(stack.getCount());
                float w = font.getStringWidth(count) + 3;
                float cy = stack.isDamageable() && durability.getValue() ? top + size - line : infoY;
                round(c, center - w / 2, cy - .5F, w, line, 2, new Color(12, 15, 17, 230));
                font.drawCenteredString(c, count, center, cy, Color.WHITE);
            }
        }
    }

    private static String shortEnchantment(String id) {
        return switch (id) {
            case "protection" -> "Prot";
            case "projectile_protection" -> "Proj";
            case "blast_protection" -> "Blast";
            case "fire_protection" -> "FProt";
            case "unbreaking" -> "Unb";
            case "mending" -> "Mend";
            case "sharpness" -> "Sharp";
            case "fire_aspect" -> "Fire";
            case "feather_falling" -> "Fall";
            case "depth_strider" -> "Depth";
            case "binding_curse", "vanishing_curse" -> "Curse";
            default -> {
                String label = id.replace('_', ' ');
                yield Character.toUpperCase(label.charAt(0)) + label.substring(1, Math.min(label.length(), 6));
            }
        };
    }

    /* ------------------------------------------------------------------ */
    /* Mobs and animals: spawn-egg pill label                              */
    /* ------------------------------------------------------------------ */

    private void renderLabelEsp(DrawContext context, MobEntity mob, boolean animal) {
        if (!(animal ? animalName.getValue() : mobName.getValue())) return;
        float tickDelta = Render3DEngine.getTickDelta();
        Vec2f headScreen = worldToScreen(mob.getLerpedPos(tickDelta).add(0, mob.getHeight() + 0.25, 0));
        if (headScreen == null) return;
        float[] bounds = computeScreenBounds(mob, tickDelta);
        if (bounds == null) return;
        float boxH = bounds[5];

        float fontSize = MathHelper.clamp(boxH * (animal ? animalNameSize.getValue() : mobNameSize.getValue()), 5.5F, 12.0F)
                * (animal ? animalScale.getValue() : mobScale.getValue());
        FontRenderer font = BkFonts.MEDIUM.get(fontSize);
        String nameStr = mob.hasCustomName() ? mob.getCustomName().getString() : mob.getDisplayName().getString();
        float textWidth = font.getStringWidth(nameStr);

        float iconSize = fontSize * 1.2F;
        float gap = fontSize * 0.25F;
        float padding = fontSize * 0.35F;
        float bgHeight = fontSize + padding * 2.0F;
        float bgY = headScreen.y - bgHeight - 2.0F;

        Color bgColor = DARK ? new Color(0, 0, 0, 140) : new Color(255, 255, 255, 160);
        Color nameColor = DARK ? Color.WHITE : Color.BLACK;

        float iconX, textX;
        if (animal) {
            // animals: the whole row is centred
            float totalW = iconSize + gap + textWidth;
            iconX = headScreen.x - totalW / 2.0F;
            textX = iconX + iconSize + gap;
        } else {
            // mobs: the name is centred, the egg hangs to its left
            textX = headScreen.x - textWidth / 2.0F;
            iconX = textX - gap - iconSize;
        }
        float bgX = iconX - padding;
        float bgWidth = padding + iconSize + gap + textWidth + padding;

        round(context, bgX, bgY, bgWidth, bgHeight, bgHeight / 2.0F, bgColor);
        ItemStack egg = spawnEgg(mob.getType());
        if (!egg.isEmpty()) drawItem(context, egg, iconX, bgY + (bgHeight - iconSize) / 2.0F, iconSize / 16.0F);
        font.drawString(context, nameStr, textX, bgY + (bgHeight - fontHeight(font)) / 2.0F, nameColor);
    }

    /* ------------------------------------------------------------------ */
    /* Dropped items                                                       */
    /* ------------------------------------------------------------------ */

    private <T extends Entity> List<List<T>> groupEntities(List<T> entities, float squaredDistance) {
        if (entities.isEmpty()) return List.of();
        List<List<T>> groups = new ArrayList<>();
        Set<T> processed = new HashSet<>();
        for (T entity : entities) {
            if (processed.contains(entity)) continue;
            List<T> group = new ArrayList<>();
            group.add(entity);
            processed.add(entity);
            boolean expanded;
            do {
                expanded = false;
                for (T candidate : entities) {
                    if (processed.contains(candidate)) continue;
                    boolean merged = false;
                    for (T member : group) {
                        if (candidate.squaredDistanceTo(member) <= squaredDistance) {
                            group.add(candidate);
                            processed.add(candidate);
                            expanded = true;
                            merged = true;
                            break;
                        }
                    }
                    if (merged) break;
                }
            } while (expanded);
            groups.add(group);
        }
        return groups;
    }

    private Vec3d groupCenter(List<? extends Entity> group) {
        double cx = 0, cy = 0, cz = 0;
        for (Entity e : group) {
            cx += e.getX();
            cy += e.getY() + e.getHeight() / 2.0;
            cz += e.getZ();
        }
        int n = group.size();
        return new Vec3d(cx / n, cy / n, cz / n);
    }

    private float dropFontSize(float boxH) {
        return MathHelper.clamp(boxH * dropNameSize.getValue() * 4.0F, 5.5F, 12.0F) * dropScale.getValue();
    }

    private void renderItemGroupCount(DrawContext context, List<ItemEntity> group) {
        if (!dropName.getValue()) return;
        Vec2f screen = worldToScreen(groupCenter(group));
        if (screen == null) return;
        float[] bounds = computeScreenBounds(group.get(0), Render3DEngine.getTickDelta());
        if (bounds == null) return;

        float fontSize = dropFontSize(bounds[5]);
        FontRenderer font = BkFonts.MEDIUM.get(fontSize);

        int totalCount = group.stream().mapToInt(e -> e.getStack().getCount()).sum();
        String countStr = String.valueOf(totalCount);
        float countTextWidth = font.getStringWidth(countStr);
        float countPadding = fontSize * 0.3F;
        float countRectWidth = countTextWidth + countPadding * 2.0F;
        float countRectHeight = fontSize + fontSize * 0.2F;
        float countRectRadius = countRectHeight * 0.25F;

        String firstName = group.get(0).getStack().getName().getString();
        boolean allSame = group.stream().allMatch(e -> e.getStack().getItem() == group.get(0).getStack().getItem());
        String label = allSame ? firstName : "Przedmioty";
        float textWidth = font.getStringWidth(label);

        float gap = fontSize * 0.4F;
        float padding = fontSize * 0.35F;
        float bgHeight = fontSize + padding * 2.0F;
        float bgY = screen.y - bgHeight - 2.0F;

        Color bgColor = DARK ? new Color(0, 0, 0, 140) : new Color(255, 255, 255, 160);
        Color textColor = DARK ? Color.WHITE : Color.BLACK;

        float totalW = textWidth + gap + countRectWidth;
        float textX = screen.x - totalW / 2.0F;
        float countRectX = textX + textWidth + gap;
        float bgX = textX - padding;
        float bgWidth = padding + totalW + padding;

        round(context, bgX, bgY, bgWidth, bgHeight, bgHeight / 2.0F, bgColor);
        float textY = bgY + (bgHeight - fontHeight(font)) / 2.0F;
        font.drawString(context, label, textX, textY, textColor);
        float countRectY = bgY + (bgHeight - countRectHeight) / 2.0F;
        round(context, countRectX, countRectY, countRectWidth, countRectHeight, countRectRadius, new Color(60, 60, 230, 230));
        font.drawString(context, countStr, countRectX + (countRectWidth - countTextWidth) / 2.0F, textY, Color.WHITE);
    }

    private void renderItemGroupColumn(DrawContext context, List<ItemEntity> group) {
        if (!dropName.getValue()) return;
        Vec2f screen = worldToScreen(groupCenter(group));
        if (screen == null) return;
        float[] bounds = computeScreenBounds(group.get(0), Render3DEngine.getTickDelta());
        if (bounds == null) return;

        float fontSize = dropFontSize(bounds[5]);
        FontRenderer font = BkFonts.MEDIUM.get(fontSize);
        float lineHeight = fontHeight(font) + 2.0F;

        Map<String, Integer> itemCounts = new LinkedHashMap<>();
        for (ItemEntity item : group) itemCounts.merge(item.getStack().getName().getString(), item.getStack().getCount(), Integer::sum);
        float maxWidth = 0;
        List<String> lines = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : itemCounts.entrySet()) {
            String l = entry.getKey() + " " + entry.getValue() + "x";
            lines.add(l);
            maxWidth = Math.max(maxWidth, font.getStringWidth(l));
        }

        float padding = fontSize * 0.35F;
        float bgHeight = lines.size() * lineHeight + padding * 2.0F;
        float bgY = screen.y - bgHeight - 2.0F;
        Color bgColor = DARK ? new Color(0, 0, 0, 160) : new Color(200, 200, 200, 180);
        Color textColor = DARK ? Color.WHITE : Color.BLACK;

        round(context, screen.x - maxWidth / 2.0F - padding, bgY, maxWidth + padding * 2.0F, bgHeight, 4.0F, bgColor);
        float textY = bgY + padding;
        for (String l : lines) {
            font.drawString(context, l, screen.x - font.getStringWidth(l) / 2.0F, textY, textColor);
            textY += lineHeight;
        }
    }

    private void renderItemEsp(DrawContext context, ItemEntity item) {
        if (!dropName.getValue()) return;
        if (shouldSkipItemRendering(item)) return;
        float tickDelta = Render3DEngine.getTickDelta();
        float[] bounds = computeScreenBounds(item, tickDelta);
        if (bounds == null) return;
        Vec2f screen = worldToScreen(item.getLerpedPos(tickDelta).add(0, item.getHeight() + 0.25, 0));
        if (screen == null) return;

        ItemStack stack = item.getStack();
        float fontSize = dropFontSize(bounds[5]);
        FontRenderer font = BkFonts.MEDIUM.get(fontSize);

        String nameStr = stack.getName().getString();
        float textWidth = font.getStringWidth(nameStr);
        String countStr = String.valueOf(stack.getCount());
        float countTextWidth = font.getStringWidth(countStr);

        float iconSize = fontSize * 1.2F;
        float gap = fontSize * 0.25F;
        float countPadding = fontSize * 0.3F;
        float countRectWidth = countTextWidth + countPadding * 2.0F;
        float countRectHeight = fontSize + fontSize * 0.2F;
        float countRectRadius = countRectHeight * 0.25F;
        float padding = fontSize * 0.35F;
        float bgHeight = fontSize + padding * 2.0F;
        float bgY = screen.y - bgHeight - 2.0F;

        Color bgColor = DARK ? new Color(0, 0, 0, 140) : new Color(255, 255, 255, 160);
        Color nameColor = DARK ? Color.WHITE : Color.BLACK;

        float countGap = fontSize * 0.4F;
        float totalW = iconSize + gap + textWidth + countGap + countRectWidth;
        float iconX = screen.x - totalW / 2.0F;
        float nameX = iconX + iconSize + gap;

        round(context, iconX - padding, bgY, totalW + padding * 2.0F, bgHeight, bgHeight / 2.0F, bgColor);
        drawItem(context, stack, iconX, bgY + (bgHeight - iconSize) / 2.0F, iconSize / 16.0F);
        float textY = bgY + (bgHeight - fontHeight(font)) / 2.0F;
        font.drawString(context, nameStr, nameX, textY, nameColor);

        float countRectX = nameX + textWidth + countGap;
        float countRectY = bgY + (bgHeight - countRectHeight) / 2.0F;
        round(context, countRectX, countRectY, countRectWidth, countRectHeight, countRectRadius, new Color(60, 60, 230, 230));
        font.drawString(context, countStr, countRectX + (countRectWidth - countTextWidth) / 2.0F, textY, Color.WHITE);
    }

    private boolean shouldSkipItemRendering(ItemEntity item) {
        if (mc.player == null) return true;
        return item.getStack().getCount() <= 1
                && mc.player.getMainHandStack().getItem() == item.getStack().getItem()
                && mc.player.squaredDistanceTo(item) < 2.0F;
    }

    /* ------------------------------------------------------------------ */
    /* ThunderHack extras                                                  */
    /* ------------------------------------------------------------------ */

    private void renderExtras(MatrixStack stack) {
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

    private void renderPearls(DrawContext context) {
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
}
