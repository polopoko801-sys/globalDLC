package thunder.hack.features.modules.render;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.Heightmap;
import net.minecraft.world.biome.Biome;
import org.joml.Matrix3x2f;
import thunder.hack.core.manager.client.ModuleManager;
import thunder.hack.features.modules.Module;
import thunder.hack.setting.Setting;
import thunder.hack.utility.render.GuiRender;
import thunder.hack.utility.render.Render2DEngine;
import thunder.hack.utility.render.Render3DEngine;
import thunder.hack.utility.render.TextureStorage;
import thunder.hack.utility.render.core.CloudanRenderCore;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;

/**
 * Your own weather, only on your screen: a downpour of rain or snow in every biome, the ground under you wet
 * and rippling (Rain) or snowy (Snow) like FloorWeather, and optional wind.
 *
 * <p>The game's own rain/snow curtains and splash particles are switched off ({@code MixinWeatherRendering});
 * the sky, fog and rain sound stay. The drops are our own: thin streaks that fall onto the floor shader's
 * ripple grid and land exactly where and when a ring starts - the ring maths of {@code floor_water.fsh}
 * ({@code ripples}) is replayed here 1:1 (same hash, same cells, same cycle and density roll). Snow is soft
 * flakes drifting down, anchored to the world so they do not follow you.
 */
public class Weather extends Module {
    public Weather() {
        super("Weather", Category.RENDER);
    }

    public enum Mode {Rain, Snow}

    private final Setting<Mode> mode = new Setting<>("Mode", Mode.Rain);
    private final Setting<Boolean> floor = new Setting<>("Floor", true);
    private final Setting<Float> floorRadius = new Setting<>("FloorRadius", 18f, 4f, 48f);
    private final Setting<Float> rainRings = new Setting<>("RainRings", 0.35f, 0.05f, 1f, v -> mode.is(Mode.Rain));
    private final Setting<Integer> rainAmount = new Setting<>("RainAmount", 500, 0, 2000, v -> mode.is(Mode.Rain));
    private final Setting<Integer> snowAmount = new Setting<>("SnowAmount", 450, 50, 1500, v -> mode.is(Mode.Snow));
    private final Setting<Boolean> wind = new Setting<>("Wind", false);
    private final Setting<Float> windStrength = new Setting<>("WindStrength", 0.35f, 0.05f, 1.2f, v -> wind.getValue());
    private final Setting<Boolean> gusts = new Setting<>("Gusts", true, v -> wind.getValue());

    /** Drop fall speed (blocks/s) and how long before impact it becomes visible. */
    private static final float FALL_SPEED = 16f;
    private static final float FALL_TIME = 0.7f;

    /** Our own drops replace the game's rain/snow (MixinWeatherRendering). */
    public boolean customDrops() {
        return true;
    }

    public boolean hdTextures() {
        return true;
    }

    /** FloorWeather's shader mode while this is on: 1 = rain, 2 = snow. */
    public int floorMode() {
        return mode.is(Mode.Snow) ? 2 : 1;
    }

    public float ringDensity() {
        return rainRings.getValue();
    }

    public Biome.Precipitation precipitation() {
        return mode.is(Mode.Snow) ? Biome.Precipitation.SNOW : Biome.Precipitation.RAIN;
    }

    /**
     * Sideways push per block of height ({x, z}), or null without wind. The direction drifts slowly round and,
     * with Gusts, the strength swells and dies down; snow, being light, leans further than rain.
     */
    public float[] lean() {
        if (!wind.getValue()) return null;
        double t = System.currentTimeMillis() / 1000.0;
        double dir = t * 0.03 + Math.sin(t * 0.11) * 0.6;
        double strength = windStrength.getValue() * (gusts.getValue() ? 0.7 + 0.3 * Math.sin(t * 0.7) * Math.sin(t * 0.23 + 1.3) : 1.0);
        if (mode.is(Mode.Snow)) strength *= 1.6;
        return new float[]{(float) (Math.cos(dir) * strength), (float) (Math.sin(dir) * strength)};
    }

    /** The floor, drawn by the same renderer as FloorWeather (skipped while FloorWeather itself is on). */
    @Override
    public void onRender3D(MatrixStack matrices) {
        if (fullNullCheck() || !floor.getValue()) return;
        if (ModuleManager.floorWeather != null && ModuleManager.floorWeather.isEnabled()) return;
        if (thunder.hack.utility.render.FloorWaterRenderer.isBroken()) return;
        Vec3d feet = CloudanRenderCore.interpolated(mc.player);
        boolean snow = mode.is(Mode.Snow);
        Color water = snow ? new Color(235, 242, 250) : new Color(30, 112, 138);
        thunder.hack.utility.render.FloorWaterRenderer.render(feet, floorRadius.getValue(), floorTime(), water, 0.5f,
                1f, 1f, 1f, 215 / 255f, snow ? 2 : 1, rainRings.getValue());
    }

    /** The time the floor shader is fed with (same base and speed as whoever draws the floor). */
    private float floorTime() {
        boolean fw = ModuleManager.floorWeather != null && ModuleManager.floorWeather.isEnabled();
        float speed = fw ? ModuleManager.floorWeather.waveSpeedValue() : (CloudanRenderCore.reduceEffects() ? 0f : 1f);
        return (System.nanoTime() % 3_600_000_000_000L) / 1e9f * speed;
    }

    @Override
    public void onRender2D(DrawContext context) {
        if (fullNullCheck()) return;
        if (mode.is(Mode.Rain)) drawRain(context);
        else drawSnow(context);
    }

    /* ------------------------------------------------------------------ */
    /* Rain - replays floor_water.fsh ripples()                            */
    /* ------------------------------------------------------------------ */

    private static float fract(float x) {
        return x - (float) Math.floor(x);
    }

    /** floor_water.fsh hash12, in float like the GPU. */
    private static float hash12(float px, float py) {
        float x = fract(px * 0.1031f), y = fract(py * 0.1031f), z = fract(px * 0.1031f);
        float d = x * (y + 33.33f) + y * (z + 33.33f) + z * (x + 33.33f);
        x += d;
        y += d;
        z += d;
        return fract((x + y) * z);
    }

    private void drawRain(DrawContext context) {
        boolean fw = ModuleManager.floorWeather != null && ModuleManager.floorWeather.isEnabled();
        float scale = fw ? ModuleManager.floorWeather.waveScaleValue() : 1f;
        float radius = fw ? ModuleManager.floorWeather.radiusValue() : floorRadius.getValue();
        float density = rainRings.getValue();
        float t = floorTime();
        float speed = fw ? ModuleManager.floorWeather.waveSpeedValue() : (CloudanRenderCore.reduceEffects() ? 0f : 1f);
        if (speed <= 0f) return;

        Vec3d cam = mc.gameRenderer.getCamera().getCameraPos();
        double offX = Math.floor(cam.x / 4096.0) * 4096.0, offZ = Math.floor(cam.z / 4096.0) * 4096.0;
        Vec3d feet = CloudanRenderCore.interpolated(mc.player);
        float g = 0.8f * scale;                       // world -> ripple grid
        int x0 = (int) Math.floor((feet.x - radius - offX) * g), x1 = (int) Math.floor((feet.x + radius - offX) * g);
        int z0 = (int) Math.floor((feet.z - radius - offZ) * g), z1 = (int) Math.floor((feet.z + radius - offZ) * g);
        float[] lean = lean();

        List<float[]> streaks = new ArrayList<>();
        for (int ix = x0; ix <= x1; ix++) {
            for (int iz = z0; iz <= z1; iz++) {
                float h = hash12(ix, iz);
                float cyc = t * 0.45f + h;
                float k = (float) Math.floor(cyc);
                float ph = cyc - k;
                // the NEXT ring of this cell starts when cyc reaches k + 1
                float toImpact = (1f - ph) / (0.45f * speed);
                if (toImpact > FALL_TIME) continue;
                float kk = k + 1f;
                if (hash12(ix + kk * 1.37f + 11.1f, iz + kk * 1.37f + 11.1f) > density) continue;
                float cx = ix + 0.2f + 0.6f * hash12(ix + kk + 3.1f, iz + kk + 3.1f);
                float cz = iz + 0.2f + 0.6f * hash12(ix + kk + 7.7f, iz + kk + 7.7f);
                double wx = cx / g + offX, wz = cz / g + offZ;
                double dx = wx - feet.x, dz = wz - feet.z;
                if (dx * dx + dz * dz > radius * radius) continue;

                int gy = mc.world.getTopY(Heightmap.Type.MOTION_BLOCKING, MathHelper.floor(wx), MathHelper.floor(wz));
                double ground = gy;
                double fall = toImpact * FALL_SPEED;
                double headY = ground + fall, tailY = headY + 0.9 + fall * 0.03;
                double lx = lean == null ? 0 : lean[0], lz = lean == null ? 0 : lean[1];
                Vec3d head = new Vec3d(wx + lx * fall, headY, wz + lz * fall);
                Vec3d tail = new Vec3d(wx + lx * (tailY - ground), tailY, wz + lz * (tailY - ground));
                Vec3d sh = Render3DEngine.worldSpaceToScreenSpace(head), st = Render3DEngine.worldSpaceToScreenSpace(tail);
                if (!(sh.z > 0 && sh.z < 1) || !(st.z > 0 && st.z < 1)) continue;
                double dist = head.distanceTo(cam);
                float a = (float) MathHelper.clamp(1.25 - dist / 45.0, 0.35, 0.95);
                streaks.add(new float[]{(float) sh.x, (float) sh.y, (float) st.x, (float) st.y, a});
            }
        }

        // the air itself: drops falling all round you (world-anchored, they do not follow you), the ones that
        // reach the ground simply vanish into it - the synced ones above are what makes the rings
        double tsec = System.nanoTime() / 1e9;
        float span = radius * 2.6f, high = 22f;
        int n = rainAmount.getValue();
        for (int i = 0; i < n; i++) {
            float hx = hash12(i * 1.7f, 3.3f), hz = hash12(i * 2.9f, 7.1f), hy = hash12(i * 0.37f, 1.9f);
            double bx = hx * span, bz = hz * span;
            double x = bx + span * Math.floor((feet.x - bx) / span + 0.5);
            double z = bz + span * Math.floor((feet.z - bz) / span + 0.5);
            double fallen = (tsec * FALL_SPEED * (0.85 + hy * 0.3) + hy * high) % high;
            double headY = feet.y + high * 0.75 - fallen;
            int gy = mc.world.getTopY(Heightmap.Type.MOTION_BLOCKING, MathHelper.floor(x), MathHelper.floor(z));
            if (headY < gy) continue;
            double lx = lean == null ? 0 : lean[0], lz = lean == null ? 0 : lean[1];
            Vec3d head = new Vec3d(x + lx * fallen, headY, z + lz * fallen);
            Vec3d tail = new Vec3d(head.x - lx * 0.9, headY + 0.9, head.z - lz * 0.9);
            Vec3d sh = Render3DEngine.worldSpaceToScreenSpace(head), st = Render3DEngine.worldSpaceToScreenSpace(tail);
            if (!(sh.z > 0 && sh.z < 1) || !(st.z > 0 && st.z < 1)) continue;
            float a = (float) MathHelper.clamp(1.1 - head.distanceTo(cam) / 50.0, 0.2, 0.75);
            streaks.add(new float[]{(float) sh.x, (float) sh.y, (float) st.x, (float) st.y, a});
        }

        Matrix3x2f pose = GuiRender.pose(context);
        GuiRender.quads(context, Render2DEngine.currentClip(), vc -> {
            for (float[] s : streaks) {
                float dx = s[2] - s[0], dy = s[3] - s[1];
                float len = (float) Math.sqrt(dx * dx + dy * dy);
                if (len < 0.5f) continue;
                float nx = -dy / len * 0.8f, ny = dx / len * 0.8f;     // ~1.6 px wide
                int head = ((int) (s[4] * 255) << 24) | 0xDCE8FF;
                int tail = ((int) (s[4] * 40) << 24) | 0xDCE8FF;
                // front-facing order: the GUI pipeline culls back faces, which hid every streak drawn the other way round
                thunder.hack.utility.render.ProjectedGlass.quad(vc, pose,
                        new float[]{s[0] - nx, s[1] - ny, s[2] - nx, s[3] - ny, s[2] + nx, s[3] + ny, s[0] + nx, s[1] + ny},
                        head, tail, tail, head);
            }
        });
    }

    /* ------------------------------------------------------------------ */
    /* Snow                                                                */
    /* ------------------------------------------------------------------ */

    private void drawSnow(DrawContext context) {
        Vec3d feet = CloudanRenderCore.interpolated(mc.player);
        Vec3d cam = mc.gameRenderer.getCamera().getCameraPos();
        float r = floorRadius.getValue(), span = r * 2, height = 18f;
        double t = System.nanoTime() / 1e9;
        float[] lean = lean();
        Vec3d forward = Vec3d.fromPolar(mc.gameRenderer.getCamera().getPitch(), mc.gameRenderer.getCamera().getYaw());
        Vec3d up = forward.crossProduct(new Vec3d(0, 1, 0)).normalize().crossProduct(forward).normalize();

        List<float[]> flakes = new ArrayList<>();
        int n = snowAmount.getValue();
        for (int i = 0; i < n; i++) {
            float hx = hash12(i * 1.7f, 3.3f), hz = hash12(i * 2.9f, 7.1f), hy = hash12(i * 0.37f, 1.9f), hs = hash12(i * 5.3f, 2.2f);
            double fallSpeed = 1.0 + hs * 1.1;
            // world-anchored, wrapped round you in a 2r x 2r tile so the field never runs out or follows you
            double bx = hx * span, bz = hz * span;
            double x = bx + span * Math.floor((feet.x - bx) / span + 0.5);
            double z = bz + span * Math.floor((feet.z - bz) / span + 0.5);
            double fallen = (t * fallSpeed + hy * height) % height;
            double y = feet.y + height * 0.7 - fallen;
            x += Math.sin(t * (0.6 + hs) + i) * 0.35;
            z += Math.cos(t * (0.5 + hx) + i * 1.3) * 0.35;
            if (lean != null) {
                x += lean[0] * fallen;
                z += lean[1] * fallen;
            }
            Vec3d w = new Vec3d(x, y, z);
            Vec3d s = Render3DEngine.worldSpaceToScreenSpace(w);
            if (!(s.z > 0 && s.z < 1)) continue;
            Vec3d e = Render3DEngine.worldSpaceToScreenSpace(w.add(up.multiply(0.05 + hs * 0.05)));
            if (!(e.z > 0 && e.z < 1)) continue;
            float px = (float) Math.min(Math.hypot(e.x - s.x, e.y - s.y), 14);
            if (px < 0.4f) continue;
            float a = (float) MathHelper.clamp(1.1 - w.distanceTo(cam) / (r * 1.3), 0.15, 0.95);
            flakes.add(new float[]{(float) s.x, (float) s.y, px * 2.2f, a});
        }

        Matrix3x2f pose = GuiRender.pose(context);
        GuiRender.textured(context, TextureStorage.ghostGlow, Render2DEngine.currentClip(), vc -> {
            for (float[] f : flakes) {
                int c = ((int) (f[3] * 255) << 24) | 0xFFFFFF;
                float h2 = f[2];
                GuiRender.vertexTex(vc, pose, f[0] - h2, f[1] - h2, 0, 0, c);
                GuiRender.vertexTex(vc, pose, f[0] - h2, f[1] + h2, 0, 1, c);
                GuiRender.vertexTex(vc, pose, f[0] + h2, f[1] + h2, 1, 1, c);
                GuiRender.vertexTex(vc, pose, f[0] + h2, f[1] - h2, 1, 0, c);
            }
        });
    }

    @Override
    public String getDisplayInfo() {
        return mode.getValue().name() + (wind.getValue() ? " Wind" : "");
    }
}
