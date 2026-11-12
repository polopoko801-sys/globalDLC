package thunder.hack.features.modules.render;

import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import thunder.hack.features.modules.Module;
import thunder.hack.features.modules.client.HudEditor;
import thunder.hack.setting.Setting;
import thunder.hack.setting.impl.ColorSetting;
import thunder.hack.utility.render.BkWorldPost;
import thunder.hack.utility.render.Render3DEngine;

import java.awt.Color;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * BKClient's Glass Vapor ({@code systems/modules/modules/visuals/GlassVapor}, MIT, bkubiakdev), 1:1: a trail of
 * glass metaballs behind you that refract the world, with a rim light, tint and chromatic split - BK's shader
 * ({@code bk_glass_vapor.fsh}) fed the same data as BK.
 */
public class GlassVapor extends Module {
    public GlassVapor() {
        super("GlassVapor", Category.RENDER);
    }

    public enum ColorMode {Theme, Rainbow, Custom}

    private final Setting<Float> refrStrength = new Setting<>("Refraction", 0.045f, 0.01f, 0.12f);
    private final Setting<Float> rimStrength = new Setting<>("RimLight", 1.1f, 0.0f, 2.0f);
    private final Setting<Float> tintStrength = new Setting<>("TintStrength", 0.6f, 0.0f, 1.0f);
    private final Setting<Float> noiseStrength = new Setting<>("NoiseWarp", 0.015f, 0.0f, 0.05f);
    private final Setting<Boolean> chromatic = new Setting<>("Chromatic", true);
    private final Setting<ColorMode> colorMode = new Setting<>("ColorMode", ColorMode.Theme);
    private final Setting<ColorSetting> customColor = new Setting<>("Color", new ColorSetting(new Color(120, 200, 255).getRGB()),
            v -> colorMode.is(ColorMode.Custom));
    private final Setting<Float> liveTime = new Setting<>("LiveTime", 1.8f, 0.5f, 4.0f);
    private final Setting<Float> nodeRadius = new Setting<>("VaporSize", 0.7f, 0.2f, 2.0f);

    private static final BkWorldPost POST = new BkWorldPost("bk_glass_vapor", "Vapor", 16 + 64 * 16);

    private final List<VaporNode> nodes = new CopyOnWriteArrayList<>();
    private final float[] uniformScratch = new float[16 + 64 * 16];
    private Vec3d lastPos;

    @Override
    public void onDisable() {
        nodes.clear();
        lastPos = null;
    }

    @Override
    public void onUpdate() {
        if (mc.player == null) return;
        Vec3d curPos = mc.player.getEntityPos().add(0, 0.4, 0);
        if (lastPos == null) {
            lastPos = curPos;
            return;
        }
        double dist = curPos.distanceTo(lastPos);
        if (dist > 0.25 || (mc.player.getVelocity().lengthSquared() > 0.01 && Math.random() < 0.35)) {
            nodes.add(new VaporNode(curPos, (float) Math.random() * 6.28f, getVaporColor()));
            lastPos = curPos;
            if (nodes.size() > 50) nodes.removeFirst();
        }
        nodes.removeIf(VaporNode::isExpired);
    }

    @Override
    public void onRender3D(MatrixStack matrices) {
        if (nodes.isEmpty() || POST.isBroken() || mc.player == null) return;
        var fb = mc.getFramebuffer();
        if (fb == null || fb.getColorAttachment() == null) return;
        int fw = fb.getColorAttachment().getWidth(0), fh = fb.getColorAttachment().getHeight(0);
        if (fw <= 0 || fh <= 0) return;

        float aspect = (float) fw / (float) fh;
        float time = (float) ((System.currentTimeMillis() % 100000L) / 1000.0);
        float near = 0.05f;
        float far = mc.options.getClampedViewDistance() * 16f * 4f;
        Matrix4f viewProj = new Matrix4f(Render3DEngine.lastProjMat).mul(Render3DEngine.lastWorldSpaceMatrix);
        Vec3d cam = mc.gameRenderer.getCamera().getCameraPos();

        float[] u = uniformScratch;
        java.util.Arrays.fill(u, 0f);
        int count = 0;
        int max = Math.min(nodes.size(), 60);
        for (int i = 0; i < max; i++) {
            VaporNode node = nodes.get(i);
            if (node.isExpired()) continue;
            Vec3d rel = node.pos.subtract(cam);
            Vector4f clip = new Vector4f((float) rel.x, (float) rel.y, (float) rel.z, 1.0f);
            viewProj.transform(clip);
            if (clip.w <= 0.01f) continue;
            float uvX = clip.x / clip.w * 0.5f + 0.5f;
            float uvY = clip.y / clip.w * 0.5f + 0.5f;
            if (uvX < -0.2f || uvX > 1.2f || uvY < -0.2f || uvY > 1.2f) continue;

            float age = (System.currentTimeMillis() - node.spawnTime) / 1000.0f;
            float progress = age / liveTime.getValue();
            float env = 1.0f - MathHelper.clamp(progress, 0.0f, 1.0f);
            env = (float) Math.sin(env * Math.PI * 0.5);
            float rWorld = nodeRadius.getValue() * (1.0f + progress * 0.8f);
            float screenR = (rWorld / clip.w) * 0.5f;

            int base = 16 + count * 16;
            u[base] = uvX;
            u[base + 1] = uvY;
            u[base + 2] = clip.w;
            u[base + 3] = env;
            u[base + 4] = screenR;
            u[base + 7] = screenR;
            u[base + 8] = (float) i / (float) max;
            u[base + 9] = node.wobPhase;
            u[base + 10] = 0.35f;
            u[base + 11] = 0.8f;
            u[base + 12] = node.color.getRed() / 255.0f;
            u[base + 13] = node.color.getGreen() / 255.0f;
            u[base + 14] = node.color.getBlue() / 255.0f;
            u[base + 15] = 1.0f;
            count++;
        }
        if (count == 0) return;

        u[0] = count;
        u[1] = aspect;
        u[2] = time;
        u[3] = refrStrength.getValue();
        u[4] = rimStrength.getValue();
        u[5] = tintStrength.getValue();
        u[6] = noiseStrength.getValue();
        u[7] = 1.0f;
        u[8] = near;
        u[9] = far;
        u[10] = chromatic.getValue() ? 0.015f : 0.0f;
        u[11] = colorMode.is(ColorMode.Theme) ? 1.0f : 0.0f;
        Color theme = HudEditor.getColor(0);
        u[12] = theme.getRed() / 255.0f;
        u[13] = theme.getGreen() / 255.0f;
        u[14] = theme.getBlue() / 255.0f;
        u[15] = 1.0f;

        POST.apply(u);
    }

    private Color getVaporColor() {
        if (colorMode.is(ColorMode.Rainbow)) {
            float hue = ((System.currentTimeMillis() % 4000L) / 4000.0f) % 1.0f;
            return new Color(Color.HSBtoRGB(hue, 0.7f, 1.0f));
        }
        if (colorMode.is(ColorMode.Custom)) return customColor.getValue().getColorObject();
        return HudEditor.getColor(0);
    }

    private final class VaporNode {
        final Vec3d pos;
        final float wobPhase;
        final Color color;
        final long spawnTime = System.currentTimeMillis();

        VaporNode(Vec3d pos, float wobPhase, Color color) {
            this.pos = pos;
            this.wobPhase = wobPhase;
            this.color = color;
        }

        boolean isExpired() {
            return (System.currentTimeMillis() - spawnTime) / 1000.0f > liveTime.getValue();
        }
    }
}
