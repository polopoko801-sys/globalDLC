package thunder.hack.features.modules.render;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Vec3d;
import thunder.hack.features.modules.Module;
import thunder.hack.features.modules.client.HudEditor;
import thunder.hack.setting.Setting;
import thunder.hack.utility.ThunderUtility;
import thunder.hack.utility.Timer;
import thunder.hack.utility.render.Render3DEngine;
import thunder.hack.utility.render.GuiRender;
import thunder.hack.utility.render.ProjectedGlass;
import thunder.hack.utility.render.Render2DEngine;
import org.joml.Matrix3x2f;
import thunder.hack.utility.render.TextureStorage;
import thunder.hack.utility.render.animation.Easing;
import thunder.hack.utility.render.core.CloudanBatchRenderer;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static thunder.hack.utility.render.Render2DEngine.applyOpacity;

public class JumpCircle extends Module {
    public JumpCircle() {
        super("JumpCircle", Category.RENDER);
    }

    private final Setting<Style> style = new Setting<>("Style", Style.Circle1);
    private final Setting<Boolean> easeOut = new Setting<>("EaseOut", true);
    private final Setting<Float> rotateSpeed = new Setting<>("RotateSpeed", 2f, 0.5f, 5f);
    private final Setting<Float> circleScale = new Setting<>("CircleScale", 1f, 0.5f, 5f);
    private final Setting<Boolean> onlySelf = new Setting<>("OnlySelf", false);
    private final Setting<Boolean> throughWalls = new Setting<>("ThroughWalls", false);
    private final Setting<Float> waveStrength = new Setting<>("WaveStrength", 4f, 0f, 10f, v -> style.is(Style.Water));
    /** How far above the ground the water ripple floats (it used to sit almost in the grass). */
    private final Setting<Float> waterLift = new Setting<>("WaterHeight", 0.12f, 0f, 1f, v -> style.is(Style.Water));
    private final Setting<Float> waterTint = new Setting<>("WaterTint", 0.18f, 0f, 1f, v -> style.is(Style.Water));
    /** Own colour for the water ripple - it used to take the GUI colour, so a blue GUI made blue water. White = clear. */
    private final Setting<thunder.hack.setting.impl.ColorSetting> waterColor = new Setting<>("WaterColor", new thunder.hack.setting.impl.ColorSetting(0xFFFFFFFF), v -> style.is(Style.Water));

    /** How long one ring lives, in ms. */
    private static final long LIFETIME_MS = 1100L;

    private final List<Circle> circles = new CopyOnWriteArrayList<>();
    private final List<PlayerEntity> cache = new CopyOnWriteArrayList<>();
    private Identifier custom;

    public enum Style {
        Circle1, Circle2, Circle3, Circle4, Circle5, Circle6, Custom, Water
    }

    @Override
    public void onEnable() {
        // Only the Custom style needs a file off disk. Loading it unconditionally is why enabling the
        // module complained about a missing circle.png even when it was about to draw a built-in ring.
        if (!style.is(Style.Custom)) return;

        try {
            custom = ThunderUtility.getCustomImg("circle");
        } catch (Exception e) {
            sendMessage(e.getMessage());
        }
    }

    @Override
    public void onUpdate() {
        if (style.is(Style.Custom) && custom == null) {
            try {
                custom = ThunderUtility.getCustomImg("circle");
            } catch (Exception e) {
                sendMessage(".minecraft -> " + thunder.hack.core.manager.client.ConfigManager.CONFIG_FOLDER_NAME + " -> misc -> images -> circle.png");
            }
        }

        for (PlayerEntity pl : mc.world.getPlayers())
            if (!cache.contains(pl) && pl.isOnGround() && (mc.player == pl || !onlySelf.getValue()))
                cache.add(pl);

        cache.forEach(pl -> {
            if (pl != null && !pl.isOnGround()) {
                circles.add(new Circle(new Vec3d(pl.getX(), Math.floor(pl.getY()) + 0.03, pl.getZ()), new Timer()));
                cache.remove(pl);
            }
        });

        circles.removeIf(c -> c.timer.passedMs(LIFETIME_MS));
    }

    private Identifier getTexture() {
        if (style.is(Style.Custom))
            return custom;
        return TextureStorage.jumpCircles[style.getValue().ordinal()];
    }

    /**
     * The rings are real world geometry: one textured quad lying flat on the ground per ring, drawn on the
     * 3D pass.
     *
     * <p>They used to be screen-projected - the square was cut into a 12x12 mesh and every corner was
     * projected by hand, with whole cells dropped whenever one corner crossed the near plane. That is what
     * made them flicker and vanish. A quad in the world has none of those problems and the GPU does the
     * perspective properly.
     */
    @Override
    public void onRender3D(MatrixStack matrices) {
        if (circles.isEmpty() || fullNullCheck() || style.is(Style.Water)) return;

        final Identifier texture = getTexture();
        if (texture == null) return;

        List<Render3DEngine.GroundSprite> sprites = new ArrayList<>(circles.size());

        for (Circle c : circles) {
            long passed = c.timer.getPassedTimeMs();

            // one short pop instead of the old curve, which started at radius 0, needed 2.5s to reach
            // full size and hung around for six seconds - it read as "nothing is happening".
            float t = passed / (float) LIFETIME_MS;
            if (t < 0f || t >= 1f) continue;

            float grow = easeOut.getValue() ? Easing.CubicOut.applyClamped(t) : t;
            float radius = circleScale.getValue() * (0.4f + grow * 1.6f);
            float fade = 1f - t * t;

            Color color = applyOpacity(HudEditor.getColor(0), fade);
            double angle = passed / 1000.0 * rotateSpeed.getValue();
            sprites.add(new Render3DEngine.GroundSprite(c.pos(), radius, angle, color));
        }

        // Through the core batcher: the rings join whatever else is drawing on this texture this frame
        // and go out as one draw call, and get culled without this module writing its own check.
        for (Render3DEngine.GroundSprite s : sprites)
            CloudanBatchRenderer.groundSprite(texture, s.center(), s.radius(), s.angle(), s.tint(), throughWalls.getValue());
    }

    /**
     * Water style: ONE ripple spreading over the ground from where you jumped, like a rain ring in FloorWeather.
     * Drawn with the per-pixel water shader in ring mode: the crest across the band bends the ground under it
     * (refraction) and catches a few glints - no white rim, no stacked copies.
     */
    @Override
    public void onRender2D(DrawContext context) {
        if (!style.is(Style.Water) || circles.isEmpty() || fullNullCheck()) return;
        final int seg = 72;
        int rgb = waterColor.getValue().getColor();

        for (Circle c : circles) {
            float t = c.timer.getPassedTimeMs() / (float) LIFETIME_MS;
            if (t < 0f || t >= 1f) continue;
            float grow = easeOut.getValue() ? Easing.CubicOut.applyClamped(t) : t;
            float r = circleScale.getValue() * (0.15f + grow * 1.8f);
            float w = circleScale.getValue() * (0.16f + 0.1f * (1f - grow));
            float fade = (float) Math.pow(1f - t, 1.3);
            float inner = Math.max(0.01f, r - w), outer = r + w;

            float[][] in = new float[seg + 1][], out = new float[seg + 1][];
            for (int i = 0; i <= seg; i++) {
                double a = Math.PI * 2 * i / seg;
                in[i] = project(c.pos().add(0, waterLift.getValue(), 0), Math.cos(a) * inner, Math.sin(a) * inner);
                out[i] = project(c.pos().add(0, waterLift.getValue(), 0), Math.cos(a) * outer, Math.sin(a) * outer);
            }
            List<thunder.hack.utility.render.FxSurface.V> verts = new ArrayList<>();
            for (int i = 0; i < seg; i++) {
                if (in[i] == null || in[i + 1] == null || out[i] == null || out[i + 1] == null) continue;
                float u0 = (float) (Math.PI * 2 * i / seg * r), u1 = (float) (Math.PI * 2 * (i + 1) / seg * r);
                verts.add(new thunder.hack.utility.render.FxSurface.V(in[i][0], in[i][1], u0, 0f));
                verts.add(new thunder.hack.utility.render.FxSurface.V(out[i][0], out[i][1], u0, 1f));
                verts.add(new thunder.hack.utility.render.FxSurface.V(out[i + 1][0], out[i + 1][1], u1, 1f));
                verts.add(new thunder.hack.utility.render.FxSurface.V(in[i + 1][0], in[i + 1][1], u1, 0f));
            }
            float[] op = new float[verts.size()];
            java.util.Arrays.fill(op, Math.min(1f, fade * waveStrength.getValue() / 4f));
            thunder.hack.utility.render.FxSurface.draw(context, thunder.hack.utility.render.FxSurface.Kind.Water, verts, op, rgb, waterTint.getValue(), true);
        }
    }


    private static float[] project(Vec3d centre, double dx, double dz) {
        Vec3d s = Render3DEngine.worldSpaceToScreenSpace(centre.add(dx, 0, dz));
        return s.z > 0 && s.z < 1 ? new float[]{(float) s.x, (float) s.y} : null;
    }

    /** Component {@code axis} of the unit screen vector from a to b. */
    private static float dir(float[] a, float[] b, int axis) {
        float dx = b[0] - a[0], dy = b[1] - a[1];
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len < 1e-3f) return 0f;
        return (axis == 0 ? dx : dy) / len;
    }

    private static float[] lerp(float[] a, float[] b, float t) {
        return new float[]{a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t};
    }

    public record Circle(Vec3d pos, Timer timer) {
    }
}
