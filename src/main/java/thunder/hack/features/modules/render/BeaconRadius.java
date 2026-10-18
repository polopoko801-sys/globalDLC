package thunder.hack.features.modules.render;

import net.minecraft.block.entity.BeaconBlockEntity;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.util.math.Vec3d;
import thunder.hack.features.modules.Module;
import thunder.hack.features.modules.client.HudEditor;
import thunder.hack.injection.accesors.IBeaconBlockEntity;
import thunder.hack.setting.Setting;
import thunder.hack.setting.impl.ColorSetting;
import thunder.hack.utility.render.Render2DEngine;
import thunder.hack.utility.render.Render3DEngine;

import java.awt.Color;

/**
 * 1:1 port of Celestial's {@code BeaconRadius}: a 360-point circle around every beacon showing its effect
 * range (level 1-4 = 21 / 31 / 41 / 51 blocks), with a black outline. Projected and drawn in onRender2D
 * because the 3D queue double-rotates on 1.21.11.
 */
public class BeaconRadius extends Module {
    public BeaconRadius() {
        super("BeaconRadius", Category.RENDER);
    }

    private enum Mode {Astolfo, Rainbow, Client, Custom, Cosmo}

    private final Setting<Mode> colorMode = new Setting<>("Circle Mode", Mode.Custom);
    private final Setting<ColorSetting> customColor = new Setting<>("Custom Color", new ColorSetting(Color.WHITE.getRGB()), v -> colorMode.is(Mode.Custom));
    private final Setting<Boolean> outline = new Setting<>("Outline", true);

    @Override
    public void onRender2D(DrawContext context) {
        if (fullNullCheck()) return;
        // Celestial left "Cosmo" without a case, so it drew with colour 0 (invisible) - kept.
        int color = switch (colorMode.getValue()) {
            case Client -> HudEditor.getColor(0).getRGB();
            case Custom -> customColor.getValue().getColor();
            case Astolfo -> Render2DEngine.astolfo(false, 1).getRGB();
            case Rainbow -> Render2DEngine.rainbow(300, 1.0f, 1.0f).getRGB();
            case Cosmo -> 0;
        };
        for (BlockEntity entity : StorageEsp.getBlockEntities()) {
            if (!(entity instanceof BeaconBlockEntity)) continue;
            int beaconLevel = ((IBeaconBlockEntity) entity).getLevel();
            float beaconRad = beaconLevel == 1 ? 21.0f : (beaconLevel == 2 ? 31.0f : (beaconLevel == 3 ? 41.0f : (beaconLevel == 4 ? 51.0f : 0.0f)));
            if (beaconRad <= 0) continue;
            Vec3d c = Vec3d.of(entity.getPos());
            if (outline.getValue()) {
                drawCircle3D(context, c, beaconRad - 0.006, 6.0f, Color.BLACK.getRGB());
                drawCircle3D(context, c, beaconRad + 0.006, 6.0f, Color.BLACK.getRGB());
            }
            drawCircle3D(context, c, beaconRad, 2.0f, color);
        }
    }

    /** Celestial's {@code drawCircle3D}: a horizontal ring of 360 points at the block's position. */
    private static void drawCircle3D(DrawContext context, Vec3d center, double radius, float width, int color) {
        if (color >>> 24 == 0) return;
        Vec3d prev = null;
        for (int i = 0; i <= 360; i++) {
            double a = Math.toRadians(i);
            Vec3d s = Render3DEngine.worldSpaceToScreenSpace(center.add(Math.cos(a) * radius, 0, Math.sin(a) * radius));
            boolean ok = s.z > 0 && s.z < 1;
            if (ok && prev != null)
                Render2DEngine.drawThickLine(context, (float) prev.x, (float) prev.y, (float) s.x, (float) s.y, color, width / 2f);
            prev = ok ? s : null;
        }
    }
}
