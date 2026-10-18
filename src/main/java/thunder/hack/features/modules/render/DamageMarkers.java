package thunder.hack.features.modules.render;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.Camera;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import thunder.hack.features.modules.Module;
import thunder.hack.features.modules.client.HudEditor;
import thunder.hack.setting.Setting;
import thunder.hack.setting.impl.ColorSetting;
import thunder.hack.utility.render.Render3DEngine;

import java.awt.Color;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Random;

/**
 * 1:1 port of Celestial's {@code DamageMarkers}: every living entity within 10 blocks whose health changes
 * spawns a marker with the difference (damage or regeneration), at the middle of its box, randomly shifted by
 * up to 0.4 block. The marker pops up big and shrinks to its minimum over the following ticks, exactly by
 * Celestial's formula, and is removed after "Ticks to remove" ticks.
 *
 * <p>Celestial drew a camera-facing font in the 3D pass; on 1.21.11 the 3D queue double-rotates, so the
 * marker is projected to the screen instead and its world size is converted to pixels at that distance,
 * which looks the same. The "Ban" marker mode was a {@code celestial/ban.png} that is not in the sources, so
 * only "Text" exists.
 */
public class DamageMarkers extends Module {
    public DamageMarkers() {
        super("DamageMarkers", Category.RENDER);
    }

    private enum ColorMode {Damage, Client, Custom}

    private final Setting<ColorMode> markerColorMode = new Setting<>("Marker Color Mode", ColorMode.Damage);
    private final Setting<ColorSetting> markerColor = new Setting<>("Marker Color", new ColorSetting(Color.RED.getRGB()), v -> markerColorMode.is(ColorMode.Custom));
    private final Setting<Float> markerSize = new Setting<>("Marker size", 0.5f, 0.1f, 3.0f);
    private final Setting<Integer> ticksToRemove = new Setting<>("Ticks to remove", 35, 5, 50);
    private final Setting<Boolean> checkYourself = new Setting<>("Check Yourself", true);

    private final HashMap<Integer, Float> healthMap = new HashMap<>();
    private final ArrayList<Marker> particles = new ArrayList<>();

    @Override
    public void onDisable() {
        synchronized (particles) {
            particles.clear();
        }
        healthMap.clear();
    }

    @Override
    public void onUpdate() {
        if (fullNullCheck()) return;
        synchronized (particles) {
            for (Entity entity : mc.world.getEntities()) {
                if (entity == null || mc.player.distanceTo(entity) > 10.0f || entity.isRemoved() || entity == mc.player && checkYourself.getValue() || !(entity instanceof LivingEntity living))
                    continue;
                float lastHealth = healthMap.getOrDefault(entity.getId(), living.getMaxHealth());
                healthMap.put(entity.getId(), living.getHealth());
                if (lastHealth == living.getHealth()) continue;
                particles.add(new Marker(entity, "" + BigDecimal.valueOf(Math.abs(lastHealth - living.getHealth())).setScale(1, 4),
                        entity.getX() - 0.5 + (double) new Random(System.currentTimeMillis()).nextInt(5) * 0.1,
                        entity.getBoundingBox().minY + (entity.getBoundingBox().maxY - entity.getBoundingBox().minY) / 2.0,
                        entity.getZ() - 0.5 + (double) new Random(System.currentTimeMillis() + 1L).nextInt(5) * 0.1));
            }
            particles.removeIf(marker -> ++marker.ticks >= ticksToRemove.getValue() || marker.entity.isRemoved());
        }
    }

    @Override
    public void onRender2D(DrawContext context) {
        if (fullNullCheck()) return;
        Camera camera = mc.gameRenderer.getCamera();
        Vec3d forward = Vec3d.fromPolar(camera.getPitch(), camera.getYaw());
        Vec3d right = forward.crossProduct(new Vec3d(0, 1, 0)).normalize();
        Vec3d up = right.crossProduct(forward).normalize();

        synchronized (particles) {
            for (Marker marker : particles) {
                double size = (double) (markerSize.getValue() / (float) marker.ticks * 2.0f) * 0.1;
                size = MathHelper.clamp(size, 0.03, (double) markerSize.getValue());

                Vec3d pos = new Vec3d(marker.posX, marker.posY, marker.posZ);
                Vec3d screen = Render3DEngine.worldSpaceToScreenSpace(pos);
                if (!(screen.z > 0 && screen.z < 1)) continue;
                // Celestial scaled the font by `size` world units per font pixel: measure how many screen
                // pixels that is at the marker's distance.
                Vec3d screenUp = Render3DEngine.worldSpaceToScreenSpace(pos.add(up.multiply(size)));
                if (!(screenUp.z > 0 && screenUp.z < 1)) continue;
                float scale = (float) Math.hypot(screenUp.x - screen.x, screenUp.y - screen.y);
                if (scale <= 0.01f) continue;

                int color = -1;
                switch (markerColorMode.getValue()) {
                    case Client -> color = HudEditor.getColor(0).getRGB();
                    case Custom -> color = markerColor.getValue().getColor();
                    case Damage -> {
                        String s = marker.str;
                        boolean startWithRed = s.startsWith("5") || s.startsWith("4") || s.startsWith("6") || s.startsWith("7") || s.startsWith("8") || s.startsWith("9");
                        for (int i = 10; i <= 30 && !startWithRed; i++) startWithRed = s.startsWith(String.valueOf(i));
                        boolean startWithYellow = s.startsWith("3");
                        boolean startWithGreen = s.startsWith("1") || s.startsWith("2");
                        color = startWithRed ? Color.RED.getRGB() : (startWithYellow ? Color.YELLOW.getRGB() : (startWithGreen ? Color.GREEN.getRGB() : Color.WHITE.getRGB()));
                    }
                }

                context.getMatrices().pushMatrix();
                context.getMatrices().translate((float) screen.x, (float) screen.y);
                context.getMatrices().scale(scale, scale);
                context.getMatrices().translate(-mc.textRenderer.getWidth(marker.str) / 2f, -(mc.textRenderer.fontHeight - 1));
                context.drawTextWithShadow(mc.textRenderer, marker.str, 0, 0, color);
                context.getMatrices().popMatrix();
            }
        }
    }

    private static class Marker {
        private final Entity entity;
        private final String str;
        private final double posX, posY, posZ;
        private int ticks = 0;

        Marker(Entity entity, String str, double posX, double posY, double posZ) {
            this.entity = entity;
            this.str = str;
            this.posX = posX;
            this.posY = posY;
            this.posZ = posZ;
        }
    }
}
