package thunder.hack.features.modules.render;

import net.minecraft.client.option.Perspective;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.EntityPose;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.RotationAxis;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;
import thunder.hack.features.modules.Module;
import thunder.hack.features.modules.client.HudEditor;
import thunder.hack.setting.Setting;
import thunder.hack.setting.impl.ColorSetting;
import thunder.hack.utility.render.Render3DUtil;
import thunder.hack.utility.render.core.CloudanRenderCore;

import java.awt.Color;

/**
 * 1:1 port of Rockstar {@code ru.rockstar.client.features.impl.visuals.ChinaHat}.
 *
 * <p>Same settings, same cone (apex 0.3 above the head, rim radius 0.66, 361 rim points), same colours and the
 * same rules: third person only, raised when a helmet is worn, lowered when sneaking, turned with the player's
 * yaw. GL state as the original ends up: blending on, depth test on, culling off, smooth shading.
 *
 * <p>Porting notes (1.12 GL -> 1.21.11):
 * <ul>
 *   <li>GL_TRIANGLE_FAN is emitted as the same triangles on the translucent quad layer (each as a quad with
 *       its last corner repeated) - same picture, there is no immediate-mode fan any more.</li>
 *   <li>The original's second pass ({@code glBegin(GL_LINE_LOOP)}) sets colours but never sends a vertex, so
 *       it draws nothing - left out.</li>
 *   <li>{@code mc.player.height} is always 1.8 on 1.12 (sneaking did not shrink the hitbox), so the standing
 *       height is used here too - the original's sneak offsets were tuned against that.</li>
 *   <li>{@code ClientHelper.getClientColor} read Rockstar's own ClickGui colour; here it is this client's
 *       accent ({@link HudEditor#getColor}).</li>
 * </ul>
 */
public class ChinaHat extends Module {
    public ChinaHat() {
        super("ChinaHat", Category.RENDER);
    }

    public enum ColorMode {Astolfo, Pulse, Custom, Client, Static}

    private final Setting<ColorMode> colorMode = new Setting<>("Trails Color", ColorMode.Astolfo);
    private final Setting<ColorSetting> onecolor = new Setting<>("One Color", new ColorSetting(new Color(255, 255, 255).getRGB()),
            v -> colorMode.getValue() == ColorMode.Static || colorMode.getValue() == ColorMode.Custom);
    private final Setting<ColorSetting> twocolor = new Setting<>("Two Color", new ColorSetting(new Color(255, 255, 255).getRGB()),
            v -> colorMode.getValue() == ColorMode.Custom);
    private final Setting<Float> saturation = new Setting<>("Saturation", 0.7f, 0.1f, 1.0f,
            v -> colorMode.getValue() == ColorMode.Astolfo);

    /** Default = Rockstar's coloured cone; Glass = frosted glass; Water = rippling see-through with a moving glint. */
    public enum Style {Default, Glass, Water}

    private final Setting<Style> style = new Setting<>("Style", Style.Default);
    private final Setting<Float> waves = new Setting<>("Waves", 1f, 0f, 3f, v -> style.is(Style.Water));
    private final Setting<Float> tint = new Setting<>("Tint", 0.3f, 0f, 1f, v -> !style.is(Style.Default));

    /**
     * Glass / Water: the same cone (apex 0.3 above the head, rim 0.66), projected and filled with the frame
     * snapshot - blurred for glass, rippled for water - then tinted with the Trails Color and lit.
     */
    @Override
    public void onRender2D(net.minecraft.client.gui.DrawContext context) {
        if (fullNullCheck() || style.is(Style.Default) || mc.options.getPerspective() == Perspective.FIRST_PERSON) return;

        ItemStack stack = mc.player.getEquippedStack(EquipmentSlot.HEAD);
        double height = !stack.isEmpty() && stack.isIn(ItemTags.HEAD_ARMOR)
                ? (mc.player.isSneaking() ? -0.1D : 0.12D)
                : (mc.player.isSneaking() ? -0.22D : 0.0D);
        Vec3d pos = CloudanRenderCore.interpolated(mc.player);
        double baseY = pos.y + mc.player.getDimensions(EntityPose.STANDING).height() + height;

        float[] apex = proj(pos.x, baseY + 0.3, pos.z);
        if (apex == null) return;
        int seg = 48;
        float[][] rim = new float[seg + 1][];
        for (int i = 0; i <= seg; i++) {
            double a = Math.PI * 2 * i / seg;
            rim[i] = proj(pos.x + Math.cos(a) * 0.66, baseY, pos.z + Math.sin(a) * 0.66);
        }

        // Per-pixel water / frosted glass (fx_water / fx_glass) looking through the cone, tinted with the Trails
        // Color. The old version stacked white sheens and a white brim line on top, which is why it read as white.
        boolean water = style.is(Style.Water);
        java.util.List<thunder.hack.utility.render.FxSurface.V> verts = new java.util.ArrayList<>();
        float freq = water ? Math.max(0.1f, waves.getValue()) * 3f : 3f;
        for (int i = 0; i < seg; i++) {
            if (rim[i] == null || rim[i + 1] == null) continue;
            float u0 = (float) i / seg * freq, u1 = (float) (i + 1) / seg * freq;
            // v: brim (0.1, so the shader's soft edge leaves the brim visible) up to the tip (1)
            verts.add(new thunder.hack.utility.render.FxSurface.V(rim[i][0], rim[i][1], u0, 0.15f));
            verts.add(new thunder.hack.utility.render.FxSurface.V(apex[0], apex[1], u0, 0.9f));
            verts.add(new thunder.hack.utility.render.FxSurface.V(apex[0], apex[1], u1, 0.9f));
            verts.add(new thunder.hack.utility.render.FxSurface.V(rim[i + 1][0], rim[i + 1][1], u1, 0.15f));
        }
        int rgb = rimColor(0f, System.currentTimeMillis()).getRGB();
        thunder.hack.utility.render.FxSurface.draw(context, water ? thunder.hack.utility.render.FxSurface.Kind.Water
                : thunder.hack.utility.render.FxSurface.Kind.Glass, verts, null, rgb, tint.getValue(), false);
    }


    private Color rimColor(float i, long ms) {
        return switch (colorMode.getValue()) {
            case Client -> getClientColor(i / 16.0F, i, 5);
            case Astolfo -> astolfoColors45(1.0F, i, saturation.getValue(), 10.0F);
            case Pulse -> TwoColoreffect(new Color(255, 50, 50), new Color(79, 9, 9), (double) Math.abs(ms / 10L) / 100.0D + (double) (6.0F * (i / 16.0F) / 60.0F));
            case Custom -> TwoColoreffect(onecolor.getValue().getColorObject(), twocolor.getValue().getColorObject(), (double) Math.abs(ms / 10L) / 100.0D + (double) (3.0F * (i / 16.0F) / 60.0F));
            case Static -> onecolor.getValue().getColorObject();
        };
    }

    private static float[] proj(double x, double y, double z) {
        Vec3d s = thunder.hack.utility.render.Render3DEngine.worldSpaceToScreenSpace(new Vec3d(x, y, z));
        return s.z > 0 && s.z < 1 ? new float[]{(float) s.x, (float) s.y} : null;
    }

    @Override
    public void onRender3D(MatrixStack matrices) {
        if (fullNullCheck() || !style.is(Style.Default)) return;

        ItemStack stack = mc.player.getEquippedStack(EquipmentSlot.HEAD);
        double height = !stack.isEmpty() && stack.isIn(ItemTags.HEAD_ARMOR)
                ? (mc.player.isSneaking() ? -0.1D : 0.12D)
                : (mc.player.isSneaking() ? -0.22D : 0.0D);
        if (mc.options.getPerspective() == Perspective.FIRST_PERSON) return;

        Vec3d pos = CloudanRenderCore.interpolated(mc.player);
        Vec3d cam = mc.gameRenderer.getCamera().getCameraPos();
        float playerHeight = mc.player.getDimensions(EntityPose.STANDING).height();

        matrices.push();
        matrices.translate(pos.x - cam.x, pos.y - cam.y, pos.z - cam.z);
        matrices.translate(0.0F, (float) ((double) playerHeight + height), 0.0F);
        matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(-mc.player.getYaw()));
        Matrix4f m = matrices.peek().getPositionMatrix();

        long ms = System.currentTimeMillis();
        Color color2 = Color.WHITE;
        Color firstcolor2 = onecolor.getValue().getColorObject();
        switch (colorMode.getValue()) {
            case Client -> color2 = getClientColor(5.0F, 1.0F, 5);
            case Astolfo -> color2 = astolfoColors45(5.0F, 5.0F, saturation.getValue(), 10.0F);
            case Pulse -> color2 = TwoColoreffect(new Color(255, 50, 50), new Color(79, 9, 9), (double) Math.abs(ms / 10L) / 100.0D + 0.0D);
            case Custom -> color2 = TwoColoreffect(onecolor.getValue().getColorObject(), twocolor.getValue().getColorObject(), (double) Math.abs(ms / 10L) / 100.0D + 0.0D);
            case Static -> color2 = firstcolor2;
        }
        int apex = withAlpha(color2, 255);

        // rim points, for (i = 0; i < 360.5; ++i) - 361 of them
        int n = 361;
        float[] rx = new float[n], rz = new float[n];
        int[] rc = new int[n];
        for (int k = 0; k < n; k++) {
            float i = k;
            Color color = Color.WHITE;
            Color firstcolor = onecolor.getValue().getColorObject();
            switch (colorMode.getValue()) {
                case Client -> color = getClientColor(i / 16.0F, i, 5);
                case Astolfo -> color = astolfoColors45(i - i + 1.0F, i, saturation.getValue(), 10.0F);
                case Pulse -> color = TwoColoreffect(new Color(255, 50, 50), new Color(79, 9, 9), (double) Math.abs(ms / 10L) / 100.0D + (double) (6.0F * (i / 16.0F) / 60.0F));
                case Custom -> color = TwoColoreffect(onecolor.getValue().getColorObject(), twocolor.getValue().getColorObject(), (double) Math.abs(ms / 10L) / 100.0D + (double) (3.0F * (i / 16.0F) / 60.0F));
                case Static -> color = firstcolor;
            }
            rc[k] = withAlpha(color, 255);
            rx[k] = (float) (Math.cos((double) i * 3.141592653589793D / 180.0D) * 0.66D);
            rz[k] = (float) (Math.sin((double) i * 3.141592653589793D / 180.0D) * 0.66D);
        }

        // GL_TRIANGLE_FAN: apex (0, 0.3, 0), rim, apex again
        VertexConsumer buf = Render3DUtil.buffer(Render3DUtil.QUADS);
        for (int k = 0; k < n - 1; k++) {
            Render3DUtil.vertex(buf, m, 0f, 0.3f, 0f, apex);
            Render3DUtil.vertex(buf, m, rx[k], 0f, rz[k], rc[k]);
            Render3DUtil.vertex(buf, m, rx[k + 1], 0f, rz[k + 1], rc[k + 1]);
            Render3DUtil.vertex(buf, m, rx[k + 1], 0f, rz[k + 1], rc[k + 1]);
        }
        Render3DUtil.flush(Render3DUtil.QUADS);

        matrices.pop();
    }

    // ---- Rockstar helpers (DrawHelper / ClientHelper) ----

    /** DrawHelper.astolfoColors45 */
    private static Color astolfoColors45(float yDist, float yTotal, float saturation, float speedt) {
        float speed = 1800f;
        float hue = (float) (System.currentTimeMillis() % (int) speed) + (yTotal - yDist) * speedt;
        while (hue > speed) hue -= speed;
        hue /= speed;
        if (hue > 0.5) hue = 0.5F - (hue - 0.5f);
        hue += 0.5F;
        return Color.getHSBColor(hue, saturation, 1F);
    }

    /** DrawHelper.TwoColoreffect(Color, Color, double) */
    private static Color TwoColoreffect(Color cl1, Color cl2, double speed) {
        double thing = speed / 4.0 % 1.0;
        float val = MathHelper.clamp((float) Math.sin(18.84955592153876 * thing) / 2.0f + 0.5f, 0.0f, 1.0f);
        return new Color(
                lerp(cl1.getRed() / 255.0f, cl2.getRed() / 255.0f, val),
                lerp(cl1.getGreen() / 255.0f, cl2.getGreen() / 255.0f, val),
                lerp(cl1.getBlue() / 255.0f, cl2.getBlue() / 255.0f, val));
    }

    /** ClientHelper.getClientColor - this client's accent instead of Rockstar's ClickGui colour. */
    private static Color getClientColor(float yStep, float yStepFull, int speed) {
        return HudEditor.getColor((int) yStepFull);
    }

    private static float lerp(float a, float b, float f) {
        return a + f * (b - a);
    }

    /** DrawHelper.glColor(color, alpha) */
    private static int withAlpha(Color c, int alpha) {
        return new Color(c.getRed(), c.getGreen(), c.getBlue(), alpha).getRGB();
    }
}
