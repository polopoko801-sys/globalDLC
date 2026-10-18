package thunder.hack.features.modules.render;

import net.minecraft.block.AirBlock;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix3x2fStack;
import thunder.hack.gui.font.FontRenderers;
import thunder.hack.features.modules.Module;
import thunder.hack.features.modules.client.HudEditor;
import thunder.hack.setting.Setting;
import thunder.hack.setting.impl.ColorSetting;
import thunder.hack.utility.math.MathUtility;
import thunder.hack.utility.render.Render2DEngine;
import thunder.hack.utility.render.Render3DEngine;
import thunder.hack.utility.render.animation.AnimationUtility;

import java.awt.*;
import java.util.HashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import static thunder.hack.utility.render.Render2DEngine.*;

public class HitParticles extends Module {
    public HitParticles() {
        super("HitParticles", Category.RENDER);
    }

    private final Setting<Mode> mode = new Setting<>("Mode", Mode.Stars);
    private final Setting<Physics> physics = new Setting<>("Physics", Physics.Fall);
    private final Setting<ColorSetting> colorrr = new Setting<>("Color", new ColorSetting(0x8800FF00));
    private final Setting<Boolean> onlySelf = new Setting<>("Self", false);
    private final Setting<Integer> amount = new Setting<>("Amount", 2, 1, 5);
    private final Setting<Integer> lifeTime = new Setting<>("LifeTime", 2, 1, 10);
    private final Setting<Integer> speed = new Setting<>("Speed", 2, 1, 20);
    private final Setting<Float> starsScale = new Setting<>("Scale", 3f, 1f, 10f, v -> mode.getValue() != Mode.Orbiz);
    private final Setting<ColorMode> colorMode = new Setting<>("ColorMode", ColorMode.Sync);
    private final Setting<ColorSetting> colorH = new Setting<>("HealColor", new ColorSetting(3142544), v -> mode.is(Mode.Text));
    private final Setting<ColorSetting> colorD = new Setting<>("DamageColor", new ColorSetting(15811379), v -> mode.is(Mode.Text));

    private final HashMap<Integer, Float> healthMap = new HashMap<>();
    private final CopyOnWriteArrayList<Particle> particles = new CopyOnWriteArrayList<>();

    @Override
    public void onUpdate() {
        particles.removeIf(Particle::update);

        if (mode.is(Mode.Text)) {
            for (Entity entity : mc.world.getEntities()) {
                if (entity == null || mc.player.squaredDistanceTo(entity) > 256f || !entity.isAlive() || !(entity instanceof LivingEntity lent))
                    continue;

                Color c = colorMode.getValue() == ColorMode.Sync ? HudEditor.getColor((int) MathUtility.random(1, 228)) : colorrr.getValue().getColorObject();
                float health = lent.getHealth() + lent.getAbsorptionAmount();
                float lastHealth = healthMap.getOrDefault(entity.getId(), health);
                healthMap.put(entity.getId(), health);
                if (lastHealth == health)
                    continue;

                particles.add(new Particle((float) lent.getX(), MathUtility.random((float) (lent.getY() + lent.getHeight()), (float) lent.getY()), (float) lent.getZ(), c,
                        MathUtility.random(0, 180), MathUtility.random(10f, 60f), health - lastHealth));
            }
            return;
        }

        for (PlayerEntity player : mc.world.getPlayers()) {
            if (onlySelf.getValue() && player != mc.player)
                continue;
            if (player.hurtTime > 0) {
                Color c = colorMode.getValue() == ColorMode.Sync ? HudEditor.getColor((int) MathUtility.random(1, 228)) : colorrr.getValue().getColorObject();
                for (int i = 0; i < amount.getValue(); i++) {
                    particles.add(new Particle((float) player.getX(), MathUtility.random((float) (player.getY() + player.getHeight()), (float) player.getY()), (float) player.getZ(), c, MathUtility.random(0, 180), MathUtility.random(10f, 60f), 0));
                }
            }
        }
    }

    public void onRender2D(DrawContext context) {
        if (mc.player == null || mc.world == null) return;
        for (Particle particle : particles)
            particle.render(context);
    }

    public class Particle {
        float x;
        float y;
        float z;

        float px;
        float py;
        float pz;

        float motionX;
        float motionY;
        float motionZ;

        float rotationAngle;
        float rotationSpeed;
        float health;

        long time;
        Color color;

        public Particle(float x, float y, float z, Color color, float rotationAngle, float rotationSpeed, float health) {
            this.x = x;
            this.y = y;
            this.z = z;
            px = x;
            py = y;
            pz = z;
            motionX = MathUtility.random(-(float) speed.getValue() / 50f, (float) speed.getValue() / 50f);
            motionY = MathUtility.random(-(float) speed.getValue() / 50f, (float) speed.getValue() / 50f);
            motionZ = MathUtility.random(-(float) speed.getValue() / 50f, (float) speed.getValue() / 50f);
            time = System.currentTimeMillis();
            this.color = color;
            this.rotationAngle = rotationAngle;
            this.rotationSpeed = rotationSpeed;
            this.health = health;
        }

        public long getTime() {
            return time;
        }

        public boolean update() {
            double sp = Math.sqrt(motionX * motionX + motionZ * motionZ);
            px = x;
            py = y;
            pz = z;

            x += motionX;
            y += motionY;
            z += motionZ;

            if (posBlock(x, y - starsScale.getValue() / 10f, z)) {
                motionY = -motionY / 1.1f;
                motionX = motionX / 1.1f;
                motionZ = motionZ / 1.1f;
            } else {
                if (posBlock(x - sp, y, z - sp)
                        || posBlock(x + sp, y, z + sp)
                        || posBlock(x + sp, y, z - sp)
                        || posBlock(x - sp, y, z + sp)
                        || posBlock(x + sp, y, z)
                        || posBlock(x - sp, y, z)
                        || posBlock(x, y, z + sp)
                        || posBlock(x, y, z - sp)
                ) {
                    motionX = -motionX;
                    motionZ = -motionZ;
                }
            }

            if (physics.getValue() == Physics.Fall)
                motionY -= 0.035f;

            motionX /= 1.005f;
            motionZ /= 1.005f;
            motionY /= 1.005f;

            return System.currentTimeMillis() - getTime() > lifeTime.getValue() * 1000;
        }

        public void render(DrawContext context) {
            // 1.21.11: the drawOrbiz/drawStar/drawHeart/drawBloom helpers now draw through the 2D
            // DrawContext (Matrix3x2f) pipeline. Project the particle's interpolated world position to
            // screen space, size it inversely with camera distance (perspective-ish), then draw the
            // billboarded 2D shape at that point.
            double delta = Render3DEngine.getTickDelta();
            double wx = Render2DEngine.interpolate(px, x, delta);
            double wy = Render2DEngine.interpolate(py, y, delta) + 0.1;
            double wz = Render2DEngine.interpolate(pz, z, delta);

            Vec3d screen = Render3DEngine.worldSpaceToScreenSpace(new Vec3d(wx, wy, wz));
            if (!(screen.z > 0 && screen.z < 1)) return;

            double dist = mc.getEntityRenderDispatcher().camera.getCameraPos().distanceTo(new Vec3d(wx, wy, wz));
            float pixel = (float) (starsScale.getValue() * 18.0 / Math.max(1.0, dist));

            Matrix3x2fStack ms = context.getMatrices();
            ms.pushMatrix();
            ms.translate((float) screen.x, (float) screen.y);

            if (mode.is(Mode.Text)) {
                String txt = (health >= 0 ? "+" : "") + String.format("%.1f", health);
                Color tc = health >= 0 ? colorH.getValue().getColorObject() : colorD.getValue().getColorObject();
                float w = FontRenderers.sf_bold_mini.getStringWidth(txt);
                FontRenderers.sf_bold_mini.drawString(context, txt, -w / 2f, -4f, tc.getRGB());
            } else {
                rotationAngle += (float) (AnimationUtility.deltaTime() * rotationSpeed);
                ms.rotate((float) Math.toRadians(rotationAngle));
                switch (mode.getValue()) {
                    case Orbiz -> drawOrbiz(context, 0, pixel, color);
                    case Stars -> {
                        ms.translate(-pixel / 2f, -pixel / 2f);
                        drawStar(context, color, pixel);
                    }
                    case Hearts -> {
                        ms.translate(-pixel / 2f, -pixel / 2f);
                        drawHeart(context, color, pixel);
                    }
                    case Bloom -> {
                        ms.translate(-pixel / 2f, -pixel / 2f);
                        drawBloom(context, color, pixel);
                    }
                }
            }
            ms.popMatrix();
        }

        private boolean posBlock(double x, double y, double z) {
            Block b = mc.world.getBlockState(BlockPos.ofFloored(x, y, z)).getBlock();
            return (!(b instanceof AirBlock) && b != Blocks.WATER && b != Blocks.LAVA);
        }
    }

    public enum Physics {
        Fall, Fly
    }

    public enum Mode {
        Orbiz, Stars, Hearts, Bloom, Text
    }

    public enum ColorMode {
        Custom, Sync
    }
}
