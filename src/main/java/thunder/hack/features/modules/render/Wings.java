package thunder.hack.features.modules.render;

import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import thunder.hack.features.modules.Module;
import thunder.hack.setting.Setting;
import thunder.hack.setting.impl.ColorSetting;
import thunder.hack.utility.render.Render2DEngine;
import thunder.hack.utility.render.Render3DEngine;
import thunder.hack.utility.render.TextureStorage;
import thunder.hack.utility.render.core.CloudanCullingSystem;
import thunder.hack.utility.render.core.CloudanGlowRenderer;
import thunder.hack.utility.render.core.CloudanRenderCore;

import java.awt.Color;

/**
 * Wings behind the player.
 *
 * <p>Cosmetic and nothing else: no movement, no collision, no hitbox, no physics. They are quads in the
 * world at the player's back, and the client would behave identically with them off.
 *
 * <p>ONE quad per wing, showing one painted picture. It used to be several overlapping panels fanned a
 * few degrees apart, on the theory that the outer ones lagging the inner ones would make the wing ripple
 * rather than pivot like a board. What it actually did was print the same picture on top of itself four
 * times at four angles, which smears every feather across its neighbours and turns a painted wing into a
 * bright blur - the exact thing the picture existed to avoid. The ripple is not worth losing the
 * artwork; the wings are held still.
 *
 * <p>The shape is one texture - crystal shards with glowing edges - drawn as a right-hand wing and
 * mirrored for the left. Painting the feathers is both cheaper and far better looking than trying to
 * build them out of geometry.
 */
public class Wings extends Module {
    public Wings() {
        super("Wings", Category.RENDER);
    }

    /**
     * Sync takes the client accent, so setting the client green turns the wings green with everything
     * else. Custom is there for when you want them to differ from the rest of the HUD on purpose.
     */
    private final Setting<ColorMode> colorMode = new Setting<>("ColorMode", ColorMode.Custom);
    private final Setting<ColorSetting> color = new Setting<>("Color", new ColorSetting(0xFFD6FFE4), v -> colorMode.getValue() == ColorMode.Custom);
    private final Setting<ColorSetting> secondary = new Setting<>("SecondaryColor", new ColorSetting(0xFFFFFFFF), v -> colorMode.getValue() == ColorMode.Custom);
    private final Setting<Float> size = new Setting<>("Size", 2.3f, 0.4f, 6f);
    private final Setting<Float> chord = new Setting<>("Height", 1.6f, 0.3f, 4f);
    private final Setting<Float> spread = new Setting<>("Spread", 6f, 0f, 80f);
    private final Setting<Float> tilt = new Setting<>("Tilt", 4f, -60f, 60f);
    private final Setting<Integer> opacity = new Setting<>("Opacity", 210, 30, 255);
    private final Setting<Boolean> glow = new Setting<>("Glow", true);
    private final Setting<Boolean> particles = new Setting<>("Particles", false);
    private final Setting<Integer> renderDistance = new Setting<>("RenderDistance", 48, 8, 128);
    private final Setting<Boolean> hideInFirstPerson = new Setting<>("HideInFirstPerson", true);
    private final Setting<Boolean> throughWalls = new Setting<>("ThroughWalls", false);
    private final Setting<Boolean> self = new Setting<>("Self", true);

    @Override
    public void onRender3D(MatrixStack matrices) {
        if (fullNullCheck() || !self.getValue()) return;

        // In first person the wings would be hanging off the back of a body you cannot see, so all you
        // get is two sheets clipping through the edges of the screen.
        if (hideInFirstPerson.getValue() && mc.options.getPerspective().isFirstPerson()) return;

        Vec3d player = CloudanRenderCore.interpolated(mc.player);
        if (!CloudanCullingSystem.inRange(player, renderDistance.getValue())) return;
        if (!CloudanCullingSystem.isVisible(player, size.getValue() * 2)) return;

        // Wings sit on the body, not on the head - they must not swing round when you look over your
        // shoulder, which is exactly the separation the rotation split exists to give us.
        float bodyYaw = thunder.hack.core.rotation.RotationState.bodyYaw != 0f
                ? thunder.hack.core.rotation.RotationState.bodyYaw
                : mc.player.bodyYaw;

        // Anchored between the shoulder blades, BEHIND the body.
        //
        // The sign here was wrong: the forward vector for a yaw is (-sin, cos), and the old offset was
        // +0.22 of it, which put the wings on the chest. Everything else about the module was fine - the
        // wings were simply on the wrong side of the player the whole time.
        float yawRad = bodyYaw * MathHelper.RADIANS_PER_DEGREE;
        double back = 0.22;
        // The wings hang DOWN from this anchor, so the anchor is the shoulder line, not the mid-back - at 0.72
        // of the height the roots sat at the lower back ("growing from the butt"). Shoulder blades are ~90% up.
        Vec3d anchor = player.add(
                MathHelper.sin(yawRad) * back,
                mc.player.getHeight() * 0.92,
                -MathHelper.cos(yawRad) * back);

        Identifier texture = texture();
        Color primary = Render2DEngine.injectAlpha(primaryColor(), opacity.getValue());
        Color tip = Render2DEngine.injectAlpha(tipColor(), opacity.getValue());

        double span = size.getValue();
        double start = 0.08;
        // Keep the wing in proportion with its texture (wing_shard is 1024x512 = 2:1), so Size scales width and
        // height together and nothing stretches. Height is a gentle shape multiplier around that 2:1 (1.6 = 1x).
        double chordUse = span * 0.5 * (chord.getValue() / 1.6);

        for (int side = 0; side < 2; side++) {
            boolean mirror = side == 1;

            // Held still and open: no flap and no breath - the user wants the wings to stay put. Both are
            // mirror images, so one pair of wings rather than two sheets.
            float yaw = -bodyYaw + (mirror ? -spread.getValue() : spread.getValue());
            float pitch = tilt.getValue();
            float roll = 0f;

            Render3DEngine.drawOrientedSprite3D(texture, anchor, yaw, pitch, roll,
                    mirror ? -start - span : start, span,
                    chordUse, primary, mirror, throughWalls.getValue());
        }

        // The glow sits BEHIND the wings, at the shoulders, and is deliberately weak. A halo over the
        // top washes the feathers out - the gaps between them fill in and the picture goes back to
        // being a bright smear, which is what the painted texture is there to stop.
        if (glow.getValue())
            CloudanGlowRenderer.halo(anchor, size.getValue() * 0.45, tip, 0.22f, throughWalls.getValue());

        if (particles.getValue()) spawnMotes(anchor);
    }

    /** A few slow motes drifting off the wings. Pooled, and capped well below anything noticeable. */
    private void spawnMotes(Vec3d anchor) {
        var spawn = thunder.hack.utility.render.core.CloudanParticleRenderer.spawn("wings", 24);
        if (spawn == null) return;

        double angle = mc.world.random.nextDouble() * Math.PI * 2;
        spawn.at(anchor.add(Math.cos(angle) * size.getValue() * 0.6, mc.world.random.nextDouble() * 0.4 - 0.2,
                        Math.sin(angle) * size.getValue() * 0.6))
                .velocity(0, -0.004, 0)
                .physics(0.0004, 0.02)
                .size(0.09f, 0.3f)
                .life(1400)
                .color(Render2DEngine.injectAlpha(primaryColor(), 180))
                .texture(TextureStorage.fxSpark);
    }

    public enum ColorMode {
        Sync, Custom
    }

    private Color primaryColor() {
        return colorMode.getValue() == ColorMode.Sync
                ? thunder.hack.features.modules.client.HudEditor.getColor(0)
                : color.getValue().getColorObject();
    }

    /** The tip shades towards the far end of the accent gradient, so a synced wing still has depth. */
    private Color tipColor() {
        return colorMode.getValue() == ColorMode.Sync
                ? thunder.hack.features.modules.client.HudEditor.getColor(170)
                : secondary.getValue().getColorObject();
    }

    private static final String WINGS_README = """
            Wlasne skrzydla dla modulu Wings.

              wing.png  - JEDNO skrzydlo (prawe), drugie jest jego lustrzanym odbiciem

            Uklad obrazka: nasada skrzydla (przy plecach) po LEWEJ krawedzi, czubek po PRAWEJ,
            gora skrzydla u gory obrazka. Tlo MUSI byc przezroczyste (PNG z alfa) - biale albo
            czarne tlo bedzie widac jako prostokat.
            Rozmiar dowolny, np. 1024x512. Kolory zostaja takie jak w obrazku (Color z modulu
            go nie przebarwia).

            Plik podmienia wbudowane skrzydlo.
            Zmiany widac w grze po ~2 sekundach. Usun plik = wraca wbudowane.
            """;

    private Identifier texture() {
        Identifier user = thunder.hack.utility.render.UserImages.get("wings", "wing", WINGS_README);
        return user != null ? user : TextureStorage.wingShard;
    }
}
