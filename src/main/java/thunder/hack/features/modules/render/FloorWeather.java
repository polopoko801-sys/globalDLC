package thunder.hack.features.modules.render;

import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import thunder.hack.features.modules.Module;
import thunder.hack.features.modules.client.ClientSettings.Quality;
import thunder.hack.features.modules.client.HudEditor;
import thunder.hack.setting.Setting;
import thunder.hack.setting.impl.ColorSetting;
import thunder.hack.utility.render.Render2DEngine;
import thunder.hack.utility.render.TextureStorage;
import thunder.hack.utility.render.core.CloudanBatchRenderer;
import thunder.hack.utility.render.core.CloudanCullingSystem;
import thunder.hack.utility.render.core.CloudanRenderCore;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * A thin film of water lying on the ground around you - the floor looks wet, not flooded.
 *
 * <p>The water is three continuous sheets laid over the surface blocks, not a grid of tinted squares. Every
 * quad asks for the WINDOW of the texture its world position falls into, so the picture runs on over the
 * block boundaries: the sheets are tiled in blocks of {@code TileSize} across the whole patch, which is
 * the difference between a water surface and a chessboard.
 *
 * <p>The surface is built from up to five sheets, none of them a simple multiple of another, each drifting
 * at its own rate: light on the bottom (caustics), the body of the water, a fine chop sliding across it,
 * the sky reflected in it, and sparks on the crests. One sheet is never enough - a texture repeated every
 * few blocks is something the eye finds immediately, and the parallax between sheets is what gives the
 * surface thickness. It is also what separates water from a wet floor: chop rushing over a body that
 * creeps is motion at two scales, which is exactly what a real surface does.
 *
 * <p>The look lives in the TEXTURES, not in the tint. {@code fx_water.png} is painted water: navy troughs,
 * cyan crests, foam, with the shading coming from the slope of the waves. That matters because the world
 * sprite path multiplies each sheet by one colour - a white sheet with an alpha gradient can only ever be
 * a flat coat of paint, whatever the tint. The reflection is split the way it behaves in reality: fresnel
 * decides how much of the sky a block mirrors (nothing at your feet, most of it at the rim), the sheen
 * sheet carries the wide bright patches of a reflected sky there, and the glint sheet adds the sparks,
 * which build up with distance through GrazingBoost.
 *
 * <p>Quality trims the sheets rather than the patch: the body is always drawn, Low keeps only that and
 * the caustics, and the reflection appears from High up. So the water changes how rich it is, not how much
 * of the floor it covers.
 *
 * <p>The scan for surfaces runs a few times a second rather than every frame: walking one block is enough
 * to move the disc, and a block grid does not change at 60 fps. Between scans a frame only submits what
 * the cache holds.
 *
 * <p>Cosmetic only: nothing here touches movement, collision or the world.
 */
public class FloorWeather extends Module {
    public FloorWeather() {
        super("FloorWeather", Category.RENDER);
    }

    /** What lies on the floor. Water, Rain and Ice share the reflections; Snow is an opaque coat. */
    private final Setting<Weather> weather = new Setting<>("Weather", Weather.Water);
    /** How many drops land at once. Low leaves a ring here and there, high is a downpour. */
    private final Setting<Float> rainDensity = new Setting<>("RainDensity", 0.25f, 0.05f, 1f, v -> weather.getValue() == Weather.Rain);
    /** How far the wet patch reaches, in blocks. */
    private final Setting<Float> radius = new Setting<>("Radius", 18f, 2f, 48f);
    /** Overall strength of the water. */
    private final Setting<Integer> opacity = new Setting<>("Opacity", 215, 10, 255);
    /** Blocks per texture repeat. Larger means wider swells, smaller means tighter ripples. */
    private final Setting<Float> tileSize = new Setting<>("TileSize", 4f, 1.5f, 32f);
    /** Strength of the caustics on the bottom and of the fine chop sliding over the body. 0 leaves the plain sheet. */
    private final Setting<Float> detail = new Setting<>("Detail", 0.6f, 0f, 1f);
    /** How fast the surface flows. 0 freezes the pattern. */
    private final Setting<Float> waveSpeed = new Setting<>("WaveSpeed", 1f, 0f, 4f);
    /** The sparks on the crests. Off leaves the sheen as the only reflection. */
    private final Setting<Boolean> glint = new Setting<>("Glint", true);
    /** How strongly the sparks build up towards the horizon of the patch. */
    private final Setting<Float> grazing = new Setting<>("GrazingBoost", 0.5f, 0f, 1.5f, v -> glint.getValue());
    private final Setting<Mode> colorMode = new Setting<>("ColorMode", Mode.Water);
    private final Setting<ColorSetting> color = new Setting<>("Color", new ColorSetting(0xFF18475C), v -> colorMode.getValue() == Mode.Custom);
    /** Draws through terrain, for seeing the wet floor behind a wall. */
    private final Setting<Boolean> throughWalls = new Setting<>("ThroughWalls", false);
    private final Setting<Integer> renderDistance = new Setting<>("RenderDistance", 48, 8, 128);
    /** How much of the sky and surroundings the surface mirrors (Fresnel multiplier). */
    private final Setting<Float> reflectivity = new Setting<>("Reflectivity", 1f, 0f, 2f);
    /** Slope of the waves - how much they bend the reflection and the floor below. */
    private final Setting<Float> waves = new Setting<>("Waves", 1f, 0f, 2.5f);
    private final Setting<Float> waveScale = new Setting<>("WaveScale", 1f, 0.3f, 3f);
    /** How much of the water's own colour shows over the floor: 0 = crystal clear. */
    private final Setting<Float> depth = new Setting<>("Depth", 0.5f, 0f, 1f);

    /** The order is the shader's mode index - append only. */
    private enum Weather {
        Water, Rain, Snow, Ice
    }

    private enum Mode {
        /** Realistic water: the texture's own deep teal, crests and foam, tinted white. */
        Water,
        /** The client accent, with its reflection lifted towards white. */
        Sync,
        Custom
    }
    /** The scan is a few hundred block lookups, so it runs on a timer instead of every frame. */
    private static final long CACHE_MS = 350;
    /** Half a block: one quad per surface block, abutting its neighbours exactly. */
    private static final double HALF = 0.5;
    /**
     * The sheets must not share a plane, or they z-fight - and they must be drawn in this order.
     *
     * <p>Eight millimetres apart, starting one centimetre above the floor: closer than that and the depth
     * buffer can no longer tell the caustics from the block they are lying on at the far end of the patch.
     */
    private static final double CAUSTIC_Y = 0.010;
    private static final double BODY_Y = 0.022;
    private static final double RIPPLE_Y = 0.030;
    private static final double SHEEN_Y = 0.038;
    private static final double GLINT_Y = 0.046;
    private static final double TWINKLE_Y = 0.050;
    /**
     * How far the pattern bends, in sheet units, as the waves push the surface around.
     *
     * <p>Without this the layers are rigid pictures sliding over a floor, and the eye catches it: real water
     * moves as one body, with the fine chop thrown further than the swell.
     */
    private static final double WOBBLE = 0.035;
    /**
     * The reflection sheets are left WHITE on purpose in the default mode.
     *
     * <p>They no longer have one colour to tint: the sheen texture carries the browns, olives and muted
     * greys of the banks and reeds it is supposed to be reflecting, and the glint sheet carries warm
     * sunlight. Multiplying that by a tint would flatten exactly the variety it was painted for.
     */
    private static final Color CAUSTIC_TINT = Color.WHITE;
    private static final Color SHEEN_TINT = Color.WHITE;

    /** One column of ground the water can sit on, and how far it is from the player. */
    private record Column(BlockPos surface, double distance) {
    }

    private final List<Column> columns = new ArrayList<>();
    private BlockPos cachedOrigin;
    private float cachedRadius = -1f;
    private long cachedAt;

    /**
     * The shader mode actually drawn: while the Weather module is on, its rain or snow wins - otherwise you
     * would stand in a downpour on a calm water floor with no drops landing on it.
     */
    /** For Weather's drops, which must land exactly where this floor's rings start. */
    public float waveSpeedValue() {
        return CloudanRenderCore.reduceEffects() ? 0f : waveSpeed.getValue();
    }

    public float waveScaleValue() {
        return waveScale.getValue();
    }

    public float radiusValue() {
        return radius.getValue();
    }

    private int weatherMode() {
        var w = thunder.hack.core.manager.client.ModuleManager.weather;
        return w != null && w.isEnabled() ? w.floorMode() : weather.getValue().ordinal();
    }

    @Override
    public void onEnable() {
        invalidate();
    }

    @Override
    public void onDisable() {
        invalidate();
    }

    private void invalidate() {
        columns.clear();
        cachedOrigin = null;
        cachedRadius = -1f;
        cachedAt = 0;
    }

    @Override
    public void onRender3D(MatrixStack matrices) {
        if (fullNullCheck()) return;

        Vec3d feet = CloudanRenderCore.interpolated(mc.player);
        // Real water: one shader pass with waves, refraction and screen-space reflections of the surroundings.
        // The textured sheets below stay only as the fallback for a GPU that cannot compile it.
        if (!thunder.hack.utility.render.FloorWaterRenderer.isBroken()) {
            float t = (System.nanoTime() % 3_600_000_000_000L) / 1e9f * (CloudanRenderCore.reduceEffects() ? 0f : waveSpeed.getValue());
            Color water = switch (colorMode.getValue()) {
                case Water -> weatherMode() == 3 ? new Color(150, 200, 228) : weatherMode() == 2 ? new Color(235, 242, 250) : new Color(30, 112, 138);
                case Sync -> HudEditor.getColor(0);
                case Custom -> color.getValue().getColorObject();
            };
            thunder.hack.utility.render.FloorWaterRenderer.render(feet, radius.getValue(), t, water, depth.getValue(),
                    waves.getValue(), waveScale.getValue(), reflectivity.getValue(), opacity.getValue() / 255f,
                    weatherMode(), thunder.hack.core.manager.client.ModuleManager.weather.isEnabled() ? thunder.hack.core.manager.client.ModuleManager.weather.ringDensity() : rainDensity.getValue());
            if (!thunder.hack.utility.render.FloorWaterRenderer.isBroken()) return;
        }
        // the textured fallback below only knows how to paint water
        if (weather.getValue() != Weather.Water && weather.getValue() != Weather.Rain) return;
        if (!CloudanCullingSystem.inRange(feet, renderDistance.getValue())) return;
        if (needsRescan(feet)) rescan(feet);
        if (columns.isEmpty()) return;

        float time = (System.currentTimeMillis() % 600_000L) / 1000f;
        // ReducedMotion (and the Low preset) leave the surface still, but it stays water
        double speed = CloudanRenderCore.reduceEffects() ? 0 : waveSpeed.getValue();

        double tile = Math.max(1.5, tileSize.getValue());
        // Four sheets over the body, every one of them at a size that is NOT a simple multiple of the
        // others, all drifting at their own rate and mostly against each other. That is the whole trick
        // against tiling: repetitions that never agree on a rhythm cannot be spotted, and the parallax
        // between the sheets is what gives the surface depth - the fine ripple slides fast over a body that
        // creeps, exactly like chop riding a swell.
        double causticTile = tile * 0.85;
        double rippleTile = tile * 0.37;
        double sheenTile = tile * 2.4;
        double glintTile = tile * 1.6;

        // sheet units per second, so the speed in blocks follows TileSize
        double flow = time * speed;
        double causticU = flow * 0.030, causticV = flow * 0.019;
        double flowU = flow * 0.075, flowV = flow * 0.052;
        double rippleU = -flow * 0.165, rippleV = flow * 0.115;
        double sheenU = flow * 0.021, sheenV = -flow * 0.013;
        double glintU = -flow * 0.058, glintV = flow * 0.087;

        Color bodyColor = bodyColor();
        Color glintColor = glintColor();

        // Quality decides how many sheets the surface is made of. The body is never dropped - without it
        // there is no water at all - and every extra sheet costs one more quad per column, which is why the
        // allowance below is counted per sheet.
        Quality quality = CloudanRenderCore.quality();
        boolean normal = quality != Quality.Low;
        boolean rich = quality == Quality.High || quality == Quality.Ultra;
        float detailAmount = detail.getValue();
        boolean doCaustic = normal && detailAmount > 0.01f;
        boolean doRipple = normal && detailAmount > 0.01f;
        boolean doSheen = rich;
        boolean doGlint = rich && glint.getValue();
        // the sparks are drawn twice, at two unrelated scales and drifting against each other, so a facet
        // lights up and goes out instead of every spark moving in step
        double twinkleTile = glintTile * 0.61;

        double reach = Math.max(1.0, radius.getValue());
        float du = (float) (1.0 / tile);
        float causticDu = (float) (1.0 / causticTile);
        float rippleDu = (float) (1.0 / rippleTile);
        float sheenDu = (float) (1.0 / sheenTile);
        float glintDu = (float) (1.0 / glintTile);
        float twinkleDu = (float) (1.0 / twinkleTile);

        for (Column column : columns) {
            BlockPos pos = column.surface();

            // Flat almost everywhere and tapering off only at the rim: fading from the first block outwards
            // left the floor dry at arm's length, which is the one place you actually look at. The taper is
            // what keeps the patch from ending on a visible circle.
            double edgeness = Math.min(1.0, column.distance() / (reach * 0.82));
            float strength = (float) (1.0 - edgeness * edgeness * (3 - 2 * edgeness));
            if (strength <= 0.04f) continue;

            Vec3d centre = new Vec3d(pos.getX() + 0.5, pos.getY() + 1, pos.getZ() + 0.5);
            if (!CloudanCullingSystem.isVisible(centre, HALF)) continue;

            // Fresnel: looking down at your feet you see into the water, looking along it you see the sky in
            // it. Without this the sheet is one even coat of paint; with it the surface brightens towards
            // the far edge, which is the single strongest cue that it is water and not a decal.
            double facing = Math.min(1.0, column.distance() / reach);
            float fresnel = (float) (0.86 + 0.42 * Math.pow(facing, 1.4));
            // how much of the sky this block mirrors; nothing at your feet, most of it at the rim
            float mirror = (float) Math.pow(facing, 1.15);
            // ...and the same for the sparks. Without this the facets at your own feet are magnified until
            // they read as spray or snow rather than as light - reflection belongs to grazing angles.
            float sparkle = 0.15f + 0.85f * mirror;
            // Some patches of a calm surface are glossy and some are matte - the reflection is broken up by
            // whatever film, foam or weed sits on the water. A smooth low-frequency field does that without
            // another texture, and because it is smooth the patches have soft edges.
            float gloss = 0.55f + 0.45f * (float) Math.sin(pos.getX() * 0.11 + pos.getZ() * 0.07
                    + Math.sin(pos.getZ() * 0.05) * 2.1);

            int level = Math.round(opacity.getValue() * strength * fresnel);

            // The surface is not a rigid sheet: the whole pattern bends as the waves push it around. One
            // block of resolution is coarse for this, but at this amplitude what reads is that the water
            // moves as one body - and the fine chop is thrown much further than the swell, which is right.
            double wobbleU = Math.sin(pos.getX() * 0.37 + pos.getZ() * 0.11 + flow * 1.05);
            double wobbleV = Math.cos(pos.getX() * 0.13 - pos.getZ() * 0.41 - flow * 0.87);

            // Sheets are emitted in ASCENDING height: the sprite path blends in submission order, so a sheet
            // drawn after a higher one would paint over it no matter what its own Y says.

            // 1. the light the surface focuses onto the bottom, under the water and on the floor itself
            if (doCaustic) {
                int alpha = Math.round(level * 0.34f * detailAmount * (1f - 0.40f * (float) facing));
                if (alpha > 3) {
                    double w = WOBBLE * 0.7;
                    float u = (float) frac(pos.getX() / causticTile + causticU + wobbleU * w);
                    float v = (float) frac(pos.getZ() / causticTile + causticV + wobbleV * w);
                    CloudanBatchRenderer.groundSprite(TextureStorage.fxWaterCaustic, centre.add(0, CAUSTIC_Y, 0),
                            HALF, 0.0, u, v, u + causticDu, v + causticDu,
                            Render2DEngine.injectAlpha(waterTint(CAUSTIC_TINT), alpha), throughWalls.getValue());
                }
            }

            // 2. the body: the colour of the water itself
            int bodyAlpha = Math.round(level * 1.0f);
            if (bodyAlpha > 3) {
                float u = (float) frac(pos.getX() / tile + flowU + wobbleU * WOBBLE);
                float v = (float) frac(pos.getZ() / tile + flowV + wobbleV * WOBBLE);
                CloudanBatchRenderer.groundSprite(TextureStorage.fxWater, centre.add(0, BODY_Y, 0),
                        HALF, 0.0, u, v, u + du, v + du,
                        Render2DEngine.injectAlpha(bodyColor, bodyAlpha), throughWalls.getValue());
            }

            // 3. the fine chop running across the body, which is what makes the two look like one moving
            // surface instead of two stacked pictures
            if (doRipple) {
                int alpha = Math.round(level * 0.30f * detailAmount);
                if (alpha > 3) {
                    float u = (float) frac(pos.getX() / rippleTile + rippleU + wobbleU * 1.7);
                    float v = (float) frac(pos.getZ() / rippleTile + rippleV + wobbleV * 1.7);
                    CloudanBatchRenderer.groundSprite(TextureStorage.fxWater, centre.add(0, RIPPLE_Y, 0),
                            HALF, 0.0, u, v, u + rippleDu, v + rippleDu,
                            Render2DEngine.injectAlpha(bodyColor, alpha), throughWalls.getValue());
                }
            }

            // 4. the sky, only where you are looking along the surface rather than into it
            if (doSheen) {
                int alpha = Math.round(level * 0.72f * mirror * gloss);
                if (alpha > 3) {
                    double w = WOBBLE * 0.5;
                    float u = (float) frac(pos.getX() / sheenTile + sheenU + wobbleU * w);
                    float v = (float) frac(pos.getZ() / sheenTile + sheenV + wobbleV * w);
                    CloudanBatchRenderer.groundSprite(TextureStorage.fxWaterSheen, centre.add(0, SHEEN_Y, 0),
                            HALF, 0.0, u, v, u + sheenDu, v + sheenDu,
                            Render2DEngine.injectAlpha(waterTint(SHEEN_TINT), alpha), throughWalls.getValue());
                }
            }

            // 5 and 6. the sparks, on top of everything and strongest towards the far edge. The second
            // sheet is what turns a field of dots into glitter: two grids of facets passing through each
            // other make sparks appear and vanish the way sun on water does.
            if (doGlint) {
                float grazingBoost = (1f + grazing.getValue() * (float) facing) * sparkle;
                int glintAlpha = Math.round(level * 0.7f * grazingBoost);
                if (glintAlpha > 3) {
                    float u = (float) frac(pos.getX() / glintTile + glintU + wobbleU * 1.4);
                    float v = (float) frac(pos.getZ() / glintTile + glintV + wobbleV * 1.4);
                    CloudanBatchRenderer.groundSprite(TextureStorage.fxWaterGlint, centre.add(0, GLINT_Y, 0),
                            HALF, 0.0, u, v, u + glintDu, v + glintDu,
                            Render2DEngine.injectAlpha(glintColor, Math.min(255, glintAlpha)), throughWalls.getValue());
                }

                int twinkleAlpha = Math.round(level * 0.42f * grazingBoost);
                if (twinkleAlpha > 3) {
                    float u = (float) frac(pos.getX() / twinkleTile - glintU * 1.6 + wobbleU * 2.1);
                    float v = (float) frac(pos.getZ() / twinkleTile - glintV * 1.3 + wobbleV * 2.1);
                    CloudanBatchRenderer.groundSprite(TextureStorage.fxWaterGlint, centre.add(0, TWINKLE_Y, 0),
                            HALF, 0.0, u, v, u + twinkleDu, v + twinkleDu,
                            Render2DEngine.injectAlpha(glintColor, Math.min(255, twinkleAlpha)), throughWalls.getValue());
                }
            }
        }
    }

    /**
     * The tint for the sky sheets.
     *
     * <p>They carry their own pale blue, so the default mode leaves them alone; a colour mode multiplies
     * through so a custom water colour still has a reflection that belongs to it.
     */
    private Color waterTint(Color sky) {
        return switch (colorMode.getValue()) {
            case Water -> sky;
            case Sync -> lifted(HudEditor.getColor(0));
            case Custom -> lifted(color.getValue().getColorObject());
        };
    }

    /**
     * The tint the sheet is multiplied by.
     *
     * <p>White in the default mode, on purpose: the water texture already carries its own colours - deep
     * navy troughs, cyan crests, foam - and multiplying that by a colour would crush the range back into a
     * single flat tone, which is exactly what the old white-with-alpha sheet looked like.
     */
    private Color bodyColor() {
        return switch (colorMode.getValue()) {
            case Sync -> HudEditor.getColor(0);
            case Custom -> color.getValue().getColorObject();
            default -> Color.WHITE;
        };
    }

    /** The reflection tint: pale sky on the default mode, the body colour lifted to white on the others. */
    private Color glintColor() {
        if (colorMode.getValue() == Mode.Water) return new Color(226, 244, 255);
        return lifted(bodyColor());
    }


    /** The reflection of a body colour: the same hue, lifted most of the way to white like a sky glint. */
    private static Color lifted(Color body) {
        return new Color(
                Math.min(255, Math.round(body.getRed() + (255 - body.getRed()) * 0.65f)),
                Math.min(255, Math.round(body.getGreen() + (255 - body.getGreen()) * 0.65f)),
                Math.min(255, Math.round(body.getBlue() + (255 - body.getBlue()) * 0.65f)));
    }

    /** Positive fractional part - {@code %} alone gives a negative window west of the origin. */
    private static double frac(double v) {
        return v - Math.floor(v);
    }

    /** True when the cached disc no longer covers the ground under the player. */
    private boolean needsRescan(Vec3d feet) {
        if (cachedOrigin == null || cachedRadius != radius.getValue()) return true;
        if (System.currentTimeMillis() - cachedAt > CACHE_MS) return true;

        BlockPos now = BlockPos.ofFloored(feet);
        return Math.abs(now.getX() - cachedOrigin.getX()) > 1
                || Math.abs(now.getZ() - cachedOrigin.getZ()) > 1
                || Math.abs(now.getY() - cachedOrigin.getY()) > 2;
    }

    /**
     * Finds the surface under every column in range and keeps the nearest ones.
     *
     * <p>Closest first, so that when the budget (or the quality preset) trims the list, what gets dropped
     * is the ground at the far edge of the patch - which is fading out anyway - and never the floor you
     * are standing on.
     */
    private void rescan(Vec3d feet) {
        invalidate();
        cachedOrigin = BlockPos.ofFloored(feet);
        cachedRadius = radius.getValue();
        cachedAt = System.currentTimeMillis();

        // a lower quality preset shrinks the patch rather than switching the effect off entirely
        int reach = Math.max(1, Math.round(radius.getValue() * Math.min(1f, Math.max(0.35f, CloudanRenderCore.qualityScale()))));

        // The budget is spent on QUADS, not on columns: every surface column draws one quad per sheet, so a
        // column costs more the more sheets are switched on. Dividing a fixed quad allowance by the number of
        // sheets keeps the frame cost where it was when the second body sheet was added - the patch only gets
        // smaller, it does not get heavier.
        // The whole surface is up to five sheets per column, and the same quality gates as the render use
        // them, so the allowance is counted per sheet rather than per column. It used to be a fixed 1800
        // quads with the count divided by the sheets, which cut the disc down to two hundred columns - a
        // puddle a few blocks wide that faded out before it reached anything.
        int sheets = 1 + (detail.getValue() > 0.01f ? 2 : 0) + (glint.getValue() ? 3 : 0);
        int budget = CloudanRenderCore.budget(1500 * sheets);
        BlockPos origin = BlockPos.ofFloored(feet);

        for (int dx = -reach; dx <= reach; dx++)
            for (int dz = -reach; dz <= reach; dz++) {
                if (dx * dx + dz * dz > reach * reach) continue;

                BlockPos surface = surfaceUnder(origin.add(dx, 0, dz));
                if (surface == null) continue;

                columns.add(new Column(surface, Math.sqrt(dx * dx + dz * dz)));
            }

        columns.sort(Comparator.comparingDouble(Column::distance));
        if (columns.size() > budget) columns.subList(budget, columns.size()).clear();
    }

    /**
     * The first block you could stand on in this column.
     *
     * <p>Searching a short way up as well as down is what lets the water climb onto a block you are next
     * to instead of vanishing under it, and the fluid check keeps it off ground that already has real
     * water on it.
     */
    private BlockPos surfaceUnder(BlockPos column) {
        for (int dy = 1; dy >= -3; dy--) {
            BlockPos pos = column.add(0, dy, 0);
            if (!mc.world.getBlockState(pos).isSolidBlock(mc.world, pos)) continue;
            BlockPos above = pos.up();
            // only a face with open air above it is a surface you can see, and real water needs no film
            if (mc.world.getBlockState(above).isSolidBlock(mc.world, above)) continue;
            if (!mc.world.getFluidState(above).isEmpty()) continue;
            return pos;
        }
        return null;
    }

    @Override
    public String getDisplayInfo() {
        return weather.getValue().name();
    }
}
