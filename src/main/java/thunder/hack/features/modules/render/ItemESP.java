package thunder.hack.features.modules.render;

import com.mojang.blaze3d.vertex.VertexFormat;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.*;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.Entity;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.entity.ItemEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.client.render.Camera;
import org.jetbrains.annotations.NotNull;
import org.joml.Matrix4f;
import org.joml.Vector4d;
import org.joml.Vector4f;
import org.lwjgl.opengl.GL11;
import thunder.hack.gui.font.FontRenderers;
import thunder.hack.features.modules.Module;
import thunder.hack.features.modules.client.HudEditor;
import thunder.hack.setting.Setting;
import thunder.hack.setting.impl.ColorSetting;
import thunder.hack.utility.render.Render2DEngine;
import thunder.hack.utility.render.Render3DEngine;

import java.awt.*;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;

public class ItemESP extends Module {
    public ItemESP() {
        super("ItemESP", Category.RENDER);
    }

    private final Setting<Boolean> shadow = new Setting<>("Shadow", true);

    /**
     * The tag is deliberately NOT configurable in colour any more. It used to carry ShadowColor/TextColor
     * settings, and with a blue client theme those ended up blue in the saved config - so every dropped item
     * sat in a blue halo. A dropped-item list is something you read: black glow, white text, always.
     */
    private static final Color SHADOW = new Color(0, 0, 0, 220);
    private static final Color TEXT = Color.WHITE;

    private final Setting<ESPMode> espMode = new Setting<>("Mode", ESPMode.Rect);
    private final Setting<ColorSetting> boxColor = new Setting<>("BoxColor", new ColorSetting(new Color(-1).getRGB()), v -> espMode.getValue() == ESPMode.Rect);

    /** Label background. Flat black on purpose - the icon and the white text sit on it. */
    private static final Color PLATE = new Color(0, 0, 0, 200);

    private final Setting<Float> radius = new Setting<>("Radius", 1f, 0.1f, 5f, v -> espMode.getValue() == ESPMode.Circle);
    private final Setting<Boolean> useHudColor = new Setting<>("UseHudColor", true, v -> espMode.getValue() == ESPMode.Circle);
    private final Setting<Integer> cOffset = new Setting<>("ColorOffset", 2, 1, 50, v -> espMode.getValue() == ESPMode.Circle && useHudColor.getValue());
    private final Setting<ColorSetting> circleColor = new Setting<>("CircleColor", new ColorSetting(new Color(-1).getRGB()), v -> espMode.getValue() == ESPMode.Circle && !useHudColor.getValue());
    private final Setting<Integer> cPoints = new Setting<>("CirclePoints", 12, 3, 32, v -> espMode.getValue() == ESPMode.Circle);

    /** Items of the same kind within this many blocks of each other are tagged as one pile. */
    private static final double PILE_CELL = 2.0;

    public void onRender2D(DrawContext context) {
        if (mc.world == null) return;
        boolean rect = espMode.getValue() == ESPMode.Rect;
        Vec3d cam = mc.getEntityRenderDispatcher().camera.getCameraPos();

        // ONE tag per pile, not per entity. 30 stacks of the same thing dropped in one spot used to build a
        // tower of 30 identical labels; they are merged here into a single tag carrying the TOTAL count.
        // The key is item + label text + a 2-block grid cell, so differently renamed stacks never merge.
        Map<String, Pile> piles = new LinkedHashMap<>();

        for (Entity ent : mc.world.getEntities()) {
            if (!(ent instanceof ItemEntity item)) continue;

            // Project the item's box. Uses a front-of-camera check (clip w > 0) instead of the
            // old z<1 far-plane cull, so items far past the render distance still show as long as
            // the server is still sending them.
            Vector4d position = getScreenBounds(ent);
            if (position == null) continue;

            ItemStack stack = item.getStack();
            String key = Registries.ITEM.getId(stack.getItem()) + "|" + englishName(stack) + "|"
                    + MathHelper.floor(ent.getX() / PILE_CELL) + ","
                    + MathHelper.floor(ent.getY() / PILE_CELL) + ","
                    + MathHelper.floor(ent.getZ() / PILE_CELL);

            double dist = ent.squaredDistanceTo(cam);
            Pile pile = piles.get(key);
            if (pile == null) piles.put(key, new Pile(stack, position, dist));
            else pile.add(stack, position, dist);
        }

        // far -> near, so the nearest pile ends up on top rather than buried under the far ones
        List<Pile> sorted = new ArrayList<>(piles.values());
        sorted.sort(Comparator.comparingDouble(p -> -p.distance));

        List<float[]> placed = new ArrayList<>(); // x, y, w, h of every tag already on screen

        for (Pile pile : sorted) {
            Vector4d position = pile.bounds;

            if (rect)
                drawRect(context, (float) position.x, (float) position.y, (float) position.z, (float) position.w);

            float posX = (float) position.x;
            float posY = (float) position.y;
            float endPosX = (float) position.z;

            drawLabel(context, pile, posX + (endPosX - posX) / 2f, posY, placed);
        }
    }

    /**
     * All the dropped items of one kind lying in one spot, collapsed into a single tag: the total item
     * count plus how many separate stacks it came from. Screen box and distance track the CLOSEST stack of
     * the pile, so the tag sits on the one you would actually walk up to.
     */
    private static final class Pile {
        private final ItemStack stack;
        private Vector4d bounds;
        private double distance;
        private int total;
        private int stacks;

        private Pile(ItemStack stack, Vector4d bounds, double distance) {
            this.stack = stack;
            this.bounds = bounds;
            this.distance = distance;
            this.total = stack.getCount();
            this.stacks = 1;
        }

        private void add(@NotNull ItemStack other, Vector4d otherBounds, double otherDistance) {
            total += other.getCount();
            stacks++;
            if (otherDistance < distance) {
                distance = otherDistance;
                bounds = otherBounds;
            }
        }
    }

    /**
     * The tag over an item: a plain black plate with the item's icon and its English name in white.
     * Deliberately not tinted with the client/HUD accent - a dropped-item list is something you read,
     * and it stopped being readable once the accent was set to a dark colour.
     */
    private void drawLabel(DrawContext context, @NotNull Pile pile, float centerX, float topY, List<float[]> placed) {
        ItemStack stack = pile.stack;
        String name = englishName(stack);
        if (pile.total > 1)
            name = name + " x" + pile.total;
        // several stacks merged into this one tag - say how many, so a pile of 30 still reads as 30 stacks
        if (pile.stacks > 1)
            name = name + " (" + pile.stacks + "x)";

        float textWidth = FontRenderers.sf_bold_mini.getStringWidth(name);
        float iconSize = 9f;
        float padX = 3f;
        float gap = 2f;
        float h = 12f;
        float w = padX + iconSize + gap + textWidth + padX;

        float x = centerX - w / 2f;
        float y = topY - h - 2f;

        // Two stacks lying on the same block project to the SAME spot, and one tag drawn straight over the
        // other reads as a single entry - you cannot tell 2x64 from one 128. Push this one above whatever is
        // already there instead, so every stack gets its own readable row.
        while (overlaps(placed, x, y, w, h))
            y -= h + 2f;
        placed.add(new float[]{x, y, w, h});

        if (shadow.getValue())
            Render2DEngine.drawBlurredShadow(context, x, y, w, h, 12, SHADOW);

        Render2DEngine.drawRound(context, x, y, w, h, 2.5f, PLATE);

        // the icon is a 16x16 sprite, scaled down to sit inside the plate
        context.getMatrices().pushMatrix();
        context.getMatrices().translate(x + padX, y + (h - iconSize) / 2f);
        context.getMatrices().scale(iconSize / 16f, iconSize / 16f);
        context.drawItem(stack, 0, 0);
        context.getMatrices().popMatrix();

        FontRenderers.sf_bold_mini.drawString(context, name, x + padX + iconSize + gap, y + 3.5f, TEXT.getRGB());
    }

    /** True when the rect would land on top of one already taken by another tag. */
    private static boolean overlaps(@NotNull List<float[]> placed, float x, float y, float w, float h) {
        for (float[] r : placed)
            if (x < r[0] + r[2] && x + w > r[0] && y < r[1] + r[3] && y + h > r[1])
                return true;
        return false;
    }

    /**
     * English name straight off the registry id ("golden_apple" -> "Golden Apple"). The display name
     * follows the game language, which is how these tags ended up in Polish.
     *
     * <p>A renamed item keeps its own name - that is the whole point of renaming it, and it is the only
     * thing that tells two otherwise identical stacks apart.
     */
    private static String englishName(ItemStack stack) {
        Text custom = stack.get(DataComponentTypes.CUSTOM_NAME);
        if (custom != null) {
            String name = custom.getString();
            if (!name.isBlank()) return name;
        }

        String path = Registries.ITEM.getId(stack.getItem()).getPath();
        StringBuilder sb = new StringBuilder(path.length());
        for (String part : path.split("_")) {
            if (part.isEmpty()) continue;
            if (!sb.isEmpty()) sb.append(' ');
            sb.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return sb.isEmpty() ? path : sb.toString();
    }

    /** Screen-space (minX, minY, maxX, maxY) of the entity's box, or null if it's behind the camera. */
    private static Vector4d getScreenBounds(Entity ent) {
        Vector4d position = null;
        for (Vec3d corner : getPoints(ent)) {
            Vec3d s = projectPoint(corner);
            if (s == null) continue;
            if (position == null) position = new Vector4d(s.x, s.y, s.x, s.y);
            position.x = Math.min(s.x, position.x);
            position.y = Math.min(s.y, position.y);
            position.z = Math.max(s.x, position.z);
            position.w = Math.max(s.y, position.w);
        }
        return position;
    }

    /** Projects a world point to screen with NO far-plane cutoff; null when behind the camera. */
    private static Vec3d projectPoint(Vec3d pos) {
        Camera camera = mc.getEntityRenderDispatcher().camera;
        if (camera == null) return null;
        Vec3d cam = camera.getCameraPos();
        Vector4f tc = new Vector4f((float) (pos.x - cam.x), (float) (pos.y - cam.y), (float) (pos.z - cam.z), 1f)
                .mul(Render3DEngine.lastWorldSpaceMatrix);
        Vector4f clip = new Vector4f(tc.x(), tc.y(), tc.z(), 1f)
                .mul(new Matrix4f(Render3DEngine.lastProjMat).mul(Render3DEngine.lastModMat));
        if (clip.w() <= 1e-4f) return null; // behind camera / degenerate
        int[] viewport = new int[4];
        thunder.hack.utility.render.Render3DEngine.screenViewport(viewport);
        float ndcX = clip.x() / clip.w();
        float ndcY = clip.y() / clip.w();
        float winX = viewport[0] + viewport[2] * (ndcX + 1f) / 2f;
        float winY = viewport[1] + viewport[3] * (ndcY + 1f) / 2f;
        double sf = Render3DEngine.getScaleFactor();
        return new Vec3d(winX / sf, (mc.getWindow().getFramebufferHeight() - winY) / sf, 0);
    }

    public void onRender3D(MatrixStack stack) {
        if (espMode.getValue() == ESPMode.Circle)
            for (Entity ent : mc.world.getEntities())
                if (ent instanceof ItemEntity)
                    Render3DEngine.drawCircle3D(stack, ent, radius.getValue(), circleColor.getValue().getColor(), cPoints.getValue(), useHudColor.getValue(), cOffset.getValue());
    }

    private void drawRect(DrawContext context, float bx, float by, float ex, float ey) {
        float bw = ex - bx, bh = ey - by, t = 1f;
        Color black = Color.BLACK;
        Render2DEngine.drawRect(context, bx - 1, by - 1, bw + 2, t, black);
        Render2DEngine.drawRect(context, bx - 1, ey, bw + 2, t, black);
        Render2DEngine.drawRect(context, bx - 1, by - 1, t, bh + 2, black);
        Render2DEngine.drawRect(context, ex, by - 1, t, bh + 2, black);
        // white, not the HUD accent - same reason as the label
        Color col = boxColor.getValue().getColorObject();
        Render2DEngine.drawRect(context, bx, by, bw, t, col);
        Render2DEngine.drawRect(context, bx, ey - t, bw, t, col);
        Render2DEngine.drawRect(context, bx, by, t, bh, col);
        Render2DEngine.drawRect(context, ex - t, by, t, bh, col);
    }

    @NotNull
    private static Vec3d[] getPoints(Entity ent) {
        Box axisAlignedBB = getBox(ent);
        Vec3d[] vectors = new Vec3d[]{new Vec3d(axisAlignedBB.minX, axisAlignedBB.minY, axisAlignedBB.minZ), new Vec3d(axisAlignedBB.minX, axisAlignedBB.maxY, axisAlignedBB.minZ), new Vec3d(axisAlignedBB.maxX, axisAlignedBB.minY, axisAlignedBB.minZ), new Vec3d(axisAlignedBB.maxX, axisAlignedBB.maxY, axisAlignedBB.minZ), new Vec3d(axisAlignedBB.minX, axisAlignedBB.minY, axisAlignedBB.maxZ), new Vec3d(axisAlignedBB.minX, axisAlignedBB.maxY, axisAlignedBB.maxZ), new Vec3d(axisAlignedBB.maxX, axisAlignedBB.minY, axisAlignedBB.maxZ), new Vec3d(axisAlignedBB.maxX, axisAlignedBB.maxY, axisAlignedBB.maxZ)};
        return vectors;
    }

    @NotNull
    private static Box getBox(Entity ent) {
        double x = ent.lastX + (ent.getX() - ent.lastX) * Render3DEngine.getTickDelta();
        double y = ent.lastY + (ent.getY() - ent.lastY) * Render3DEngine.getTickDelta();
        double z = ent.lastZ + (ent.getZ() - ent.lastZ) * Render3DEngine.getTickDelta();
        Box axisAlignedBB2 = ent.getBoundingBox();
        Box axisAlignedBB = new Box(axisAlignedBB2.minX - ent.getX() + x - 0.05, axisAlignedBB2.minY - ent.getY() + y, axisAlignedBB2.minZ - ent.getZ() + z - 0.05, axisAlignedBB2.maxX - ent.getX() + x + 0.05, axisAlignedBB2.maxY - ent.getY() + y + 0.15, axisAlignedBB2.maxZ - ent.getZ() + z + 0.05);
        return axisAlignedBB;
    }

    private enum ESPMode {
        Rect, Circle, None
    }
}