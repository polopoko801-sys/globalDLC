package thunder.hack.features.modules.render;

import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.particle.DustParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.sound.SoundEvent;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import thunder.hack.features.modules.client.HudEditor;
import thunder.hack.utility.render.Render2DEngine;
import thunder.hack.utility.render.Render3DEngine;
import thunder.hack.utility.render.TextureStorage;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import static thunder.hack.features.modules.Module.mc;

/**
 * KillEffect "Ninja": the moment you kill someone you turn into a ninja (puff of smoke, a black headband with
 * red tails), draw a katana, cut the body three times - glowing slash lines across it - and it falls apart
 * into pieces that fly, tumble, bounce and fade. Then the katana is sheathed and the ninja vanishes in smoke.
 *
 * <p>Everything is client-side real 3D geometry (oriented boxes and world-space beams); nothing is sent to
 * the server and nothing in your inventory changes.
 */
final class NinjaKill {
    private NinjaKill() {
    }

    // timeline, ms from the kill
    private static final long[] SLASH_AT = {350, 950, 1550};
    /** How long one pass of the ninja through the body takes; the cut opens as it crosses the middle. */
    private static final long DASH_MS = 420;
    private static final long SLASH_MS = 150;
    private static final long SPLIT_AT = 2150;
    private static final long SHEATHE_AT = 4300, SHEATHE_MS = 500;
    private static final long END = 6800;
    private static final float GRAVITY = 9f;

    /** Entities currently sliced - hidden from the normal renderer so only the pieces show. */
    private static final Set<Integer> HIDDEN = ConcurrentHashMap.newKeySet();
    private static final List<Slice> ACTIVE = new CopyOnWriteArrayList<>();
    private static final Random RANDOM = new Random();

    static boolean isHidden(Entity e) {
        return !HIDDEN.isEmpty() && HIDDEN.contains(e.getId());
    }

    static void clear() {
        ACTIVE.clear();
        HIDDEN.clear();
    }

    static boolean active() {
        return !ACTIVE.isEmpty();
    }

    /* ------------------------------------------------------------------ model */

    private static final class Piece {
        Vec3d pos, vel;
        final Vec3d size;
        float yaw, pitch, spinYaw, spinPitch;
        final boolean head;
        /** With a skin: which part of the body this is and which of its pixel rows (a half of a limb, say). */
        SkinFigure.Part part;
        int y0, y1;

        Piece(Vec3d pos, Vec3d vel, Vec3d size, float yaw, boolean head) {
            this.pos = pos;
            this.vel = vel;
            this.size = size;
            this.yaw = yaw;
            this.head = head;
            this.spinYaw = (RANDOM.nextFloat() - 0.5f) * 420f;
            this.spinPitch = (RANDOM.nextFloat() - 0.5f) * 520f;
        }
    }

    /** One slash line across the body: a direction in the body plane, with a little curve. */
    private record Cut(Vec3d dir, Vec3d bow) {
    }

    private static final class Slice {
        final int id;
        Vec3d origin;           // victim feet (moves if the server drops you into another world mid-scene)
        net.minecraft.world.World world;
        Vec3d fromPlayer;       // where the victim stood relative to you - kept when the world changes
        final boolean cinematic;
        /** The beaten player's skin (null for a mob) - their body stays on screen in it until it is cut. */
        final net.minecraft.util.Identifier victimSkin;
        final float bodyYaw, scale;
        final long start;
        final Cut[] cuts = new Cut[SLASH_AT.length];
        final Vec3d[] dash = new Vec3d[SLASH_AT.length];   // direction of each pass through the body
        final List<Piece> pieces = new ArrayList<>();
        final boolean blood, smoke;
        long lastFrame = System.nanoTime();
        final boolean[] slashPlayed = new boolean[SLASH_AT.length];
        boolean split, sheathed, appeared;

        Slice(LivingEntity victim, boolean blood, boolean smoke, boolean cinematic) {
            this.id = victim.getId();
            this.origin = victim.getEntityPos();
            this.world = mc.world;
            this.fromPlayer = origin.subtract(mc.player.getEntityPos());
            this.cinematic = cinematic;
            this.victimSkin = SkinFigure.skinOf(victim);
            this.bodyYaw = victim.getBodyYaw();
            this.scale = Math.max(0.4f, victim.getHeight() / 1.8f);
            this.start = System.currentTimeMillis();
            this.blood = blood;
            this.smoke = smoke;

            // cut lines: across, from you to it - diagonal one way, the other way, then flat
            Vec3d toVictim = origin.subtract(mc.player.getEntityPos()).multiply(1, 0, 1);
            Vec3d f = toVictim.lengthSquared() < 1e-4 ? new Vec3d(0, 0, 1) : toVictim.normalize();
            Vec3d right = new Vec3d(-f.z, 0, f.x);
            double[] tilt = {Math.toRadians(38), Math.toRadians(-38), Math.toRadians(4)};
            for (int i = 0; i < cuts.length; i++) {
                Vec3d dir = right.multiply(Math.cos(tilt[i])).add(0, Math.sin(tilt[i]), 0).normalize();
                cuts[i] = new Cut(dir, f.multiply(-0.18));
            }
            // the three passes of the ninja: from the left, back from the right, then straight through from you
            double[] turn = {Math.toRadians(65), Math.toRadians(-65 + 180), 0};
            for (int i = 0; i < dash.length; i++) {
                double c = Math.cos(turn[i]), sn = Math.sin(turn[i]);
                dash[i] = new Vec3d(f.x * c - f.z * sn, 0, f.x * sn + f.z * c).normalize();
            }
        }

        Vec3d centre() {
            return origin.add(0, 1.0 * scale, 0);
        }
    }

    /* ------------------------------------------------------------------ start */

    static void start(LivingEntity victim, boolean blood, boolean smoke, boolean cinematic) {
        if (mc.player == null || mc.world == null) return;
        for (Slice s : ACTIVE) if (s.id == victim.getId()) return;
        Slice slice = new Slice(victim, blood, smoke, cinematic);
        ACTIVE.add(slice);
        // a player is drawn by us from now on (in their skin) - so it stays to be cut even if the server removes it
        if (slice.victimSkin != null) HIDDEN.add(slice.id);
        if (smoke) puff(mc.player.getEntityPos(), 18);
        sound(SoundEvents.ENTITY_ILLUSIONER_MIRROR_MOVE, 1.25f, 0.8f);
    }

    /* ------------------------------------------------------------------ frame */

    static void render() {
        if (mc.player == null || mc.world == null) {
            clear();
            return;
        }
        long now = System.currentTimeMillis();
        for (Slice s : ACTIVE) {
            long t = now - s.start;
            float dt = Math.min(0.05f, (System.nanoTime() - s.lastFrame) / 1e9f);
            s.lastFrame = System.nanoTime();
            if (t > END) {
                ACTIVE.remove(s);
                HIDDEN.remove(s.id);
                continue;
            }
            // the duel ended and the server moved you to the lobby: carry the scene along, same spot next to you
            if (s.world != mc.world) {
                Vec3d moved = mc.player.getEntityPos().add(s.fromPlayer).subtract(s.origin);
                s.origin = s.origin.add(moved);
                for (Piece p : s.pieces) p.pos = p.pos.add(moved);
                s.world = mc.world;
            }

            // slashes: sound + sweep particle the moment each one starts
            for (int i = 0; i < SLASH_AT.length; i++) {
                if (!s.slashPlayed[i] && t >= SLASH_AT[i]) {
                    s.slashPlayed[i] = true;
                    sound(SoundEvents.ENTITY_PLAYER_ATTACK_SWEEP, 0.9f + i * 0.12f, 0.9f);
                    Vec3d c = s.centre();
                    mc.world.addParticleClient(ParticleTypes.SWEEP_ATTACK, c.x, c.y, c.z, 0, 0, 0);
                }
                drawCut(s, i, t);
            }

            drawNinja(s, t);
            if (!s.split && s.victimSkin != null) drawVictim(s, t);

            if (!s.split && t >= SPLIT_AT) split(s);
            if (s.split) stepAndDrawPieces(s, t, dt);

            if (!s.sheathed && t >= SHEATHE_AT + SHEATHE_MS) {
                s.sheathed = true;
                if (s.smoke) puff(mc.player.getEntityPos(), 14);
            }
        }

        // the ninja itself: katana in hand + headband, while the newest kill is still playing
        Slice newest = ACTIVE.isEmpty() ? null : ACTIVE.get(ACTIVE.size() - 1);
        if (newest != null) {
            long t = now - newest.start;
            if (t < SHEATHE_AT + SHEATHE_MS) {
                // with the cinematic the dashing ninja IS you (your own body is hidden), so no second katana/band
                if (!newest.cinematic) drawKatana(t);
                if (!newest.cinematic && !mc.options.getPerspective().isFirstPerson()) drawHeadband(t);
            }
        }
    }

    /* ------------------------------------------------------------------ cinematic camera */

    private static final long CAM_END = SHEATHE_AT + SHEATHE_MS, CAM_IN = 450, CAM_OUT = 650;

    /** The newest cinematic scene still inside its camera window, or null. */
    private static Slice cinematicSlice() {
        if (ACTIVE.isEmpty()) return null;
        Slice s = ACTIVE.get(ACTIVE.size() - 1);
        if (!s.cinematic || System.currentTimeMillis() - s.start >= CAM_END) return null;
        return s;
    }

    /** True while the camera is out of your head - the first-person hand and katana are hidden then. */
    static boolean cinematicActive() {
        return cinematicSlice() != null;
    }

    /**
     * Where the camera is during the scene, eased in from (and back out to) where it really is: it leaves your
     * head, swings out to the side and slowly orbits the cut, always looking at it - like a free cam shot.
     *
     * @return {x, y, z, yaw, pitch}, or null when no cinematic is playing
     */
    static double[] cinematicPose(Vec3d cur, float curYaw, float curPitch) {
        Slice s = cinematicSlice();
        if (s == null || mc.player == null) return null;
        long t = System.currentTimeMillis() - s.start;
        float k = easeInOut(Math.min(1f, t / (float) CAM_IN));
        if (t > CAM_END - CAM_OUT) k *= easeInOut((CAM_END - t) / (float) CAM_OUT);

        Vec3d focus = s.centre().add(0, -0.15, 0);
        Vec3d me = mc.player.getEntityPos();
        double a0 = Math.atan2(me.z - focus.z, me.x - focus.x) + Math.toRadians(40);
        double a = a0 + Math.toRadians(85) * easeInOut(t / (float) CAM_END);
        double r = 4.3 * s.scale + 0.6;
        Vec3d cam = focus.add(Math.cos(a) * r, 1.25 * s.scale + 0.3, Math.sin(a) * r);

        Vec3d d = focus.subtract(cam);
        float yaw = (float) Math.toDegrees(Math.atan2(-d.x, d.z));
        float pitch = (float) -Math.toDegrees(Math.atan2(d.y, Math.sqrt(d.x * d.x + d.z * d.z)));

        Vec3d pos = cur.lerp(cam, k).add(shake(t).multiply(k));
        float outYaw = curYaw + MathHelper.wrapDegrees(yaw - curYaw) * k;
        float outPitch = curPitch + (pitch - curPitch) * k;
        return new double[]{pos.x, pos.y, pos.z, outYaw, outPitch};
    }

    /* ------------------------------------------------------------------ cuts */

    /* ------------------------------------------------------------------ the dashing ninja */

    private static final long NINJA_IN = 150;
    private static final double DASH_LEN = 2.8;

    /** True while the ninja figure is on stage - then your own body is hidden (the figure is you). */
    static boolean figureActive() {
        if (ACTIVE.isEmpty()) return false;
        Slice s = ACTIVE.get(ACTIVE.size() - 1);
        long t = System.currentTimeMillis() - s.start;
        return s.cinematic && t >= NINJA_IN && t < SHEATHE_AT + SHEATHE_MS;
    }

    /** Where the ninja's feet are at time t, which way it faces, and how far into a dash it is (-1 = standing). */
    private record Pose(Vec3d feet, float yaw, float dash) {
    }

    private static Pose poseAt(Slice s, long t) {
        Vec3d c = s.origin;
        for (int i = 0; i < SLASH_AT.length; i++) {
            long a = SLASH_AT[i];
            Vec3d d = s.dash[i];
            float yaw = (float) Math.toDegrees(Math.atan2(-d.x, d.z));
            if (t < a) {
                // waiting for this pass: standing where it will start (after the previous pass: where that ended)
                Vec3d at = i == 0 ? c.subtract(d.multiply(DASH_LEN * s.scale)) : c.add(s.dash[i - 1].multiply(DASH_LEN * s.scale));
                Vec3d look = c.subtract(at);
                return new Pose(at, (float) Math.toDegrees(Math.atan2(-look.x, look.z)), -1f);
            }
            if (t < a + DASH_MS) {
                float k = (t - a) / (float) DASH_MS;
                // fast through the middle, a short brake at both ends
                float e = (float) (0.5 - 0.5 * Math.cos(Math.PI * k));
                Vec3d from = c.subtract(d.multiply(DASH_LEN * s.scale));
                Vec3d to = c.add(d.multiply(DASH_LEN * s.scale));
                return new Pose(from.lerp(to, e), yaw, k);
            }
        }
        // after the last pass: stands where it stopped, turned back towards the body
        Vec3d at = c.add(s.dash[SLASH_AT.length - 1].multiply(DASH_LEN * s.scale));
        Vec3d look = c.subtract(at);
        return new Pose(at, (float) Math.toDegrees(Math.atan2(-look.x, look.z)), -1f);
    }

    private static void drawNinja(Slice s, long t) {
        if (t < NINJA_IN || t >= SHEATHE_AT + SHEATHE_MS) return;
        float alpha = Math.min(1f, (t - NINJA_IN) / 120f);
        if (t > SHEATHE_AT) alpha *= 1f - (t - SHEATHE_AT) / (float) SHEATHE_MS;
        Pose p = poseAt(s, t);
        if (!s.appeared) {
            s.appeared = true;
            if (s.smoke) puff(p.feet(), 16);
        }
        // afterimages while dashing: earlier positions, fading out behind it in the accent colour
        if (p.dash() >= 0f) {
            for (int k = 4; k >= 1; k--) {
                Pose ghost = poseAt(s, t - k * 28L);
                if (ghost.dash() < 0f) continue;
                drawFigure(ghost.feet(), ghost.yaw(), ghost.dash(), alpha * (0.42f - k * 0.08f), true, s.scale);
            }
        }
        net.minecraft.util.Identifier mine = SkinFigure.skinOf(mc.player);
        if (mine != null) drawSkinNinja(mine, p.feet(), p.yaw(), p.dash(), alpha, s.scale);
        else drawFigure(p.feet(), p.yaw(), p.dash(), alpha, false, s.scale);
        if (p.dash() >= 0f) drawBladeTrail(s, t, alpha);
    }

    /* ------------------------------------------------------------------ bodies in skins */

    /**
     * A whole player body in a skin. {@code lean} tips the torso and head about the hips, {@code stride} swings the
     * legs, the arm pitches swing each arm about its shoulder (0 = hanging, -90 = straight forward) - and the
     * shoulders travel with the lean, so the arms stay attached.
     *
     * @return the world origin the right arm was drawn from (the sword hangs off it)
     */
    private static Vec3d drawSkinBody(net.minecraft.util.Identifier skin, Vec3d feet, float yaw, float lean, float stride,
                                      float rightArm, float leftArm, float sc, int a) {
        float px = SkinFigure.PX * sc;
        Vec3d hip = new Vec3d(0, 12 * px, 0);
        SkinFigure.part(skin, feet, yaw, stride, hip, new Vec3d(-4 * px, 0, -2 * px), SkinFigure.Part.RIGHT_LEG, 0, 12, sc, a);
        SkinFigure.part(skin, feet, yaw, -stride, hip, new Vec3d(0, 0, -2 * px), SkinFigure.Part.LEFT_LEG, 0, 12, sc, a);
        SkinFigure.part(skin, feet, yaw, lean, hip, new Vec3d(-4 * px, 12 * px, -2 * px), SkinFigure.Part.BODY, 0, 12, sc, a);
        SkinFigure.part(skin, feet, yaw, lean, hip, new Vec3d(-4 * px, 24 * px, -4 * px), SkinFigure.Part.HEAD, 0, 8, sc, a);

        Vec3d rSh = new Vec3d(-6 * px, 22 * px, 0), lSh = new Vec3d(6 * px, 22 * px, 0);
        Vec3d rOrigin = feet.add(toWorld(feet, yaw, lean, hip, rSh).subtract(toWorld(feet, yaw, 0f, hip, rSh)));
        Vec3d lOrigin = feet.add(toWorld(feet, yaw, lean, hip, lSh).subtract(toWorld(feet, yaw, 0f, hip, lSh)));
        SkinFigure.part(skin, rOrigin, yaw, rightArm, rSh, new Vec3d(-8 * px, 12 * px, -2 * px), SkinFigure.Part.RIGHT_ARM, 0, 12, sc, a);
        SkinFigure.part(skin, lOrigin, yaw, leftArm, lSh, new Vec3d(4 * px, 12 * px, -2 * px), SkinFigure.Part.LEFT_ARM, 0, 12, sc, a);
        return rOrigin;
    }

    /** You, in your own skin, as the ninja: red headband, katana held forward, leaning into the dash. */
    private static void drawSkinNinja(net.minecraft.util.Identifier skin, Vec3d feet, float yaw, float dash, float alpha, float sc) {
        boolean moving = dash >= 0f;
        float lean = moving ? 24f : 6f;
        float stride = moving ? (float) Math.sin(dash * Math.PI * 4) * 38f : 0f;
        float sword = swingAt(dash);
        int a = Math.round(255 * alpha);
        Vec3d rOrigin = drawSkinBody(skin, feet, yaw, lean, stride, sword, moving ? 55f + lean : 15f, sc, a);

        // headband round the head (turns with the lean), two tails behind
        Vec3d hip = new Vec3d(0, 0.675 * sc, 0);
        box(feet, yaw, lean, hip, -0.235, 1.6, -0.235, 0.235, 1.67, 0.235, sc,
                new Color(190, 20, 35, a), new Color(110, 8, 18, a), 0.006f * sc);
        box(feet, yaw, lean + 25f + (float) Math.sin(System.currentTimeMillis() / 70.0) * 10f, new Vec3d(0, 1.635 * sc, -0.235 * sc),
                -0.04, 1.6, -0.6, 0.04, 1.67, -0.235, sc, new Color(175, 22, 34, a), new Color(110, 8, 18, a), 0.004f * sc);

        // katana off the right hand, along the arm: handle, guard, blade
        Vec3d sh = new Vec3d(-0.3375 * sc, 1.2375 * sc, 0);
        box(rOrigin, yaw, sword, sh, -0.36, 0.415, -0.022, -0.315, 0.675, 0.022, sc,
                new Color(22, 22, 26, a), new Color(160, 20, 32, a), 0.006f * sc);
        box(rOrigin, yaw, sword, sh, -0.395, 0.395, -0.055, -0.28, 0.415, 0.055, sc,
                new Color(205, 165, 60, a), new Color(120, 90, 30, a), 0.004f * sc);
        box(rOrigin, yaw, sword, sh, -0.345, -0.505, -0.02, -0.33, 0.395, 0.028, sc,
                new Color(210, 216, 226, a), new Color(255, 255, 255, Math.round(230 * alpha)), 0.004f * sc);
    }

    /** The beaten player, in their skin, standing where they fell - jolting back at every cut - until it splits. */
    private static void drawVictim(Slice s, long t) {
        float lean = 0f;
        for (int i = 0; i < SLASH_AT.length; i++) {
            long d = t - cutMoment(i);
            if (d >= 0) lean -= 11f * (float) Math.exp(-d / 220.0);
        }
        float twitch = t > cutMoment(0) ? (float) Math.sin(t / 45.0) * 3f * (float) Math.exp(-(t - cutMoment(0)) / 900.0) : 0f;
        drawSkinBody(s.victimSkin, s.origin, s.bodyYaw + twitch, lean, 0f, -8f, -8f, s.scale, 255);
    }

    /** Sword-arm pitch for a point in a dash (the same formula the figure uses). */
    private static float swingAt(float dash) {
        return dash >= 0f ? -95f + 30f * (float) Math.sin(dash * Math.PI) : -70f;
    }

    /** A point given in the figure's own coordinates, turned exactly as drawOrientedBox3D turns its boxes. */
    private static Vec3d toWorld(Vec3d feet, float yaw, float pitch, Vec3d pivot, Vec3d local) {
        Vec3d p = local.subtract(pivot);
        double pr = Math.toRadians(pitch);
        double y = p.y * Math.cos(pr) - p.z * Math.sin(pr);
        double z = p.y * Math.sin(pr) + p.z * Math.cos(pr);
        p = new Vec3d(p.x, y, z).add(pivot);
        double yr = Math.toRadians(-yaw);
        return feet.add(p.x * Math.cos(yr) + p.z * Math.sin(yr), p.y, -p.x * Math.sin(yr) + p.z * Math.cos(yr));
    }

    /** The glowing arc the blade's tip leaves behind it while the ninja dashes through. */
    private static void drawBladeTrail(Slice s, long t, float alpha) {
        float sc = s.scale;
        // the blade tip of the skin ninja (shoulder pivot, tip of the blade along the arm)
        Vec3d pivot = new Vec3d(-0.3375 * sc, 1.2375 * sc, 0), tip = new Vec3d(-0.3375 * sc, -0.505 * sc, 0);
        List<Vec3d> pts = new ArrayList<>();
        for (int k = 0; k <= 12; k++) {
            Pose p = poseAt(s, t - k * 14L);
            if (p.dash() < 0f) break;
            pts.add(toWorld(p.feet(), p.yaw(), swingAt(p.dash()), pivot, tip));
        }
        if (pts.size() < 2) return;
        List<Vec3d> segs = new ArrayList<>();
        for (int i = 0; i + 1 < pts.size(); i++) {
            segs.add(pts.get(i));
            segs.add(pts.get(i + 1));
        }
        Color acc = HudEditor.getColor(0);
        Render3DEngine.drawBeamSegments3D(TextureStorage.fxLine, segs, List.of(Render2DEngine.injectAlpha(acc, Math.round(170 * alpha))), 0.32 * sc, false);
        Render3DEngine.drawBeamSegments3D(TextureStorage.fxLine, segs, List.of(new Color(255, 255, 255, Math.round(230 * alpha))), 0.06 * sc, false);
    }

    /* ------------------------------------------------------------------ flash + shake */

    /** Moments the screen flashes and the camera kicks: each cut as it opens, and the split. */
    private static long cutMoment(int i) {
        return SLASH_AT[i] + DASH_MS / 2 - 40;
    }

    /** A short full-screen flash: white on every cut, red when the body falls apart. */
    static void render2D(net.minecraft.client.gui.DrawContext ctx) {
        if (ACTIVE.isEmpty()) return;
        Slice s = ACTIVE.get(ACTIVE.size() - 1);
        long t = System.currentTimeMillis() - s.start;
        float white = 0f;
        for (int i = 0; i < SLASH_AT.length; i++) {
            long d = t - cutMoment(i);
            if (d >= 0 && d < 200) white = Math.max(white, 1f - d / 200f);
        }
        long ds = t - SPLIT_AT;
        float red = ds >= 0 && ds < 350 ? 1f - ds / 350f : 0f;
        float w = mc.getWindow().getScaledWidth(), h = mc.getWindow().getScaledHeight();
        if (white > 0.01f) Render2DEngine.drawRect(ctx, 0, 0, w, h, new Color(255, 255, 255, Math.round(70 * white)));
        if (red > 0.01f) Render2DEngine.drawRect(ctx, 0, 0, w, h, new Color(200, 20, 30, Math.round(60 * red)));
    }

    /** Camera kick for the cinematic: a quick decaying shudder after each cut and the split. */
    private static Vec3d shake(long t) {
        double amp = 0;
        for (int i = 0; i < SLASH_AT.length; i++) {
            long d = t - cutMoment(i);
            if (d >= 0) amp += 0.09 * Math.exp(-d / 110.0);
        }
        long ds = t - SPLIT_AT;
        if (ds >= 0) amp += 0.14 * Math.exp(-ds / 160.0);
        if (amp < 0.002) return Vec3d.ZERO;
        return new Vec3d(Math.sin(t * 0.09) * amp, Math.cos(t * 0.113) * amp * 0.7, Math.sin(t * 0.071 + 1.3) * amp);
    }

    /**
     * A ninja made of boxes: black suit, red headband, glowing eye slit, katana held forward in the right hand.
     * Dashing it leans in, legs in a running stride and the free arm swept back.
     */
    private static void drawFigure(Vec3d feet, float yaw, float dash, float alpha, boolean ghost, float sc) {
        if (alpha <= 0.02f) return;
        boolean moving = dash >= 0f;
        float lean = moving ? 24f : 6f;
        float stride = moving ? (float) Math.sin(dash * Math.PI * 4) * 38f : 0f;
        int a = Math.round(255 * alpha);
        Color acc = HudEditor.getColor(0);
        Color suit = ghost ? Render2DEngine.injectAlpha(acc, Math.round(70 * alpha)) : new Color(16, 16, 20, a);
        Color edge = ghost ? Render2DEngine.injectAlpha(acc, Math.round(160 * alpha)) : new Color(120, 14, 24, a);
        float lw = 0.012f * sc;
        Vec3d hip = new Vec3d(0, 0.75 * sc, 0);

        // legs swing about the hips
        box(feet, yaw, stride, hip, -0.24, 0, -0.12, -0.01, 0.75, 0.12, sc, suit, edge, lw);
        box(feet, yaw, -stride, hip, 0.01, 0, -0.12, 0.24, 0.75, 0.12, sc, suit, edge, lw);
        // torso + head lean into the dash (about the hips)
        box(feet, yaw, lean, hip, -0.25, 0.75, -0.13, 0.25, 1.45, 0.13, sc, suit, edge, lw);
        box(feet, yaw, lean, hip, -0.22, 1.45, -0.22, 0.22, 1.89, 0.22, sc, suit, edge, lw);
        if (!ghost) {
            // red headband and a glowing white eye slit on the front of the hood
            box(feet, yaw, lean, hip, -0.235, 1.69, -0.235, 0.235, 1.76, 0.235, sc,
                    new Color(190, 20, 35, a), new Color(110, 8, 18, a), lw * 0.6f);
            box(feet, yaw, lean, hip, -0.14, 1.62, 0.222, 0.14, 1.66, 0.232, sc,
                    new Color(255, 255, 255, a), Render2DEngine.injectAlpha(acc, a), lw * 0.4f);
        }
        // the free (left) arm swept back, the sword arm held forward (pitch -80 turns a hanging arm forward)
        Vec3d lShoulder = new Vec3d(0.31 * sc, 1.4 * sc, 0);
        Vec3d rShoulder = new Vec3d(-0.31 * sc, 1.4 * sc, 0);
        box(feet, yaw, moving ? 55f : 15f, lShoulder, 0.25, 1.0, -0.06, 0.37, 1.42, 0.06, sc, suit, edge, lw);
        float swing = moving ? -95f + 30f * (float) Math.sin(dash * Math.PI) : -70f;
        box(feet, yaw, swing, rShoulder, -0.37, 1.0, -0.06, -0.25, 1.42, 0.06, sc, suit, edge, lw);

        // katana in the right hand, continuing along the arm: handle, guard, blade
        Color steel = ghost ? Render2DEngine.injectAlpha(acc, Math.round(120 * alpha)) : new Color(210, 216, 226, a);
        Color shine = new Color(255, 255, 255, Math.round((ghost ? 120 : 230) * alpha));
        box(feet, yaw, swing, rShoulder, -0.335, 0.74, -0.022, -0.295, 1.0, 0.022, sc,
                new Color(22, 22, 26, a), new Color(160, 20, 32, a), lw * 0.5f);
        box(feet, yaw, swing, rShoulder, -0.37, 0.72, -0.055, -0.26, 0.74, 0.055, sc,
                new Color(205, 165, 60, a), new Color(120, 90, 30, a), lw * 0.4f);
        box(feet, yaw, swing, rShoulder, -0.322, -0.18, -0.02, -0.308, 0.72, 0.028, sc, steel, shine, lw * 0.35f);
    }

    /** One part of the figure in its own (unscaled) coordinates, scaled to the victim's size. */
    private static void box(Vec3d feet, float yaw, float pitch, Vec3d pivot,
                            double x1, double y1, double z1, double x2, double y2, double z2, float sc,
                            Color fill, Color edge, float lw) {
        Render3DEngine.drawOrientedBox3D(feet, yaw, pitch, pivot,
                new Box(x1 * sc, y1 * sc, z1 * sc, x2 * sc, y2 * sc, z2 * sc), fill, edge, lw, false);
    }

    private static void drawCut(Slice s, int i, long t) {
        long ts = SLASH_AT[i] + DASH_MS / 2 - 40;   // opens as the ninja passes through the middle
        if (t < ts) return;
        float grow = Math.min(1f, (t - ts) / (float) SLASH_MS);
        float fade = 1f - Math.clamp((t - ts - SLASH_MS) / 900f, 0f, 1f);
        if (fade <= 0f) return;

        Cut cut = s.cuts[i];
        Vec3d c = s.centre();
        double len = 0.95 * s.scale;
        // the visible part of the line runs behind the blade's tip as it sweeps across
        double head = -1 + 2 * easeOut(grow);
        double tail = Math.max(-1, head - 1.4);
        List<Vec3d> segs = new ArrayList<>();
        int steps = 14;
        Vec3d prev = null;
        for (int k = 0; k <= steps; k++) {
            double a = tail + (head - tail) * k / steps;
            Vec3d p = c.add(cut.dir().multiply(a * len)).add(cut.bow().multiply(1 - a * a));
            if (prev != null) {
                segs.add(prev);
                segs.add(p);
            }
            prev = p;
        }
        Color acc = HudEditor.getColor(i * 40);
        Color glow = Render2DEngine.injectAlpha(acc, Math.round(150 * fade));
        Color core = new Color(255, 255, 255, Math.round(240 * fade));
        Render3DEngine.drawBeamSegments3D(TextureStorage.fxLine, segs, List.of(glow), 0.34 * s.scale, false);
        Render3DEngine.drawBeamSegments3D(TextureStorage.fxLine, segs, List.of(core), 0.07 * s.scale, false);
    }

    /* ------------------------------------------------------------------ pieces */

    /** The body as a player-shaped set of parts, arms and legs cut in two - eleven pieces. */
    private static void split(Slice s) {
        s.split = true;
        HIDDEN.add(s.id);
        float sc = s.scale;
        double yawRad = Math.toRadians(s.bodyYaw);
        Vec3d fwd = new Vec3d(-Math.sin(yawRad), 0, Math.cos(yawRad));
        Vec3d right = new Vec3d(-fwd.z, 0, fwd.x);

        // {x (right), y (up), z (forward), sizeX, sizeY, sizeZ}
        double[][] parts = {
                {0, 1.75, 0, 0.5, 0.5, 0.5},              // head
                {0, 1.33, 0, 0.5, 0.36, 0.25},            // chest
                {0, 0.94, 0, 0.5, 0.38, 0.25},            // belly
                {0.37, 1.31, 0, 0.25, 0.38, 0.25}, {0.37, 0.94, 0, 0.25, 0.36, 0.25},     // arm
                {-0.37, 1.31, 0, 0.25, 0.38, 0.25}, {-0.37, 0.94, 0, 0.25, 0.36, 0.25},   // arm
                {0.125, 0.56, 0, 0.25, 0.37, 0.25}, {0.125, 0.19, 0, 0.25, 0.37, 0.25},   // leg
                {-0.125, 0.56, 0, 0.25, 0.37, 0.25}, {-0.125, 0.19, 0, 0.25, 0.37, 0.25}, // leg
        };
        Vec3d centre = s.origin.add(0, 1.0 * sc, 0);
        if (s.victimSkin != null) {
            splitSkin(s, fwd, right, centre);
            sound(SoundEvents.ENTITY_PLAYER_ATTACK_SWEEP, 0.6f, 1f);
            return;
        }
        for (int i = 0; i < parts.length; i++) {
            double[] p = parts[i];
            Vec3d at = s.origin.add(right.multiply(p[0] * sc)).add(0, p[1] * sc, 0).add(fwd.multiply(p[2] * sc));
            Vec3d out = at.subtract(centre);
            out = out.lengthSquared() < 1e-4 ? new Vec3d(RANDOM.nextGaussian(), 0, RANDOM.nextGaussian()).normalize() : out.normalize();
            double speed = 1.1 + RANDOM.nextDouble() * 1.2;
            Vec3d vel = out.multiply(speed).add(RANDOM.nextGaussian() * 0.35, 1.8 + RANDOM.nextDouble() * 1.4, RANDOM.nextGaussian() * 0.35);
            s.pieces.add(new Piece(at, vel, new Vec3d(p[3] * sc, p[4] * sc, p[5] * sc), s.bodyYaw, i == 0));

            if (s.blood && mc.world != null)
                for (int k = 0; k < 4; k++)
                    mc.world.addParticleClient(new DustParticleEffect(0x9E0B12, 1.3f), at.x, at.y, at.z,
                            vel.x * 0.05, vel.y * 0.05, vel.z * 0.05);
        }
        sound(SoundEvents.ENTITY_PLAYER_ATTACK_SWEEP, 0.6f, 1f);
    }

    /**
     * The beaten player's own body cut into eleven pieces of their skin: head, chest, belly, and each arm and leg
     * cut in half - every piece shows the skin it had, the cut faces are raw red.
     */
    private static void splitSkin(Slice s, Vec3d fwd, Vec3d right, Vec3d centre) {
        float sc = s.scale, px = SkinFigure.PX * sc;
        // part, rows, centre in skin pixels: x (+ = the figure's left), y (up from the feet)
        Object[][] cuts = {
                {SkinFigure.Part.HEAD, 0, 8, 0, 28},
                {SkinFigure.Part.BODY, 0, 6, 0, 21}, {SkinFigure.Part.BODY, 6, 12, 0, 15},
                {SkinFigure.Part.RIGHT_ARM, 0, 6, -6, 21}, {SkinFigure.Part.RIGHT_ARM, 6, 12, -6, 15},
                {SkinFigure.Part.LEFT_ARM, 0, 6, 6, 21}, {SkinFigure.Part.LEFT_ARM, 6, 12, 6, 15},
                {SkinFigure.Part.RIGHT_LEG, 0, 6, -2, 9}, {SkinFigure.Part.RIGHT_LEG, 6, 12, -2, 3},
                {SkinFigure.Part.LEFT_LEG, 0, 6, 2, 9}, {SkinFigure.Part.LEFT_LEG, 6, 12, 2, 3},
        };
        for (Object[] c : cuts) {
            SkinFigure.Part part = (SkinFigure.Part) c[0];
            int y0 = (int) c[1], y1 = (int) c[2];
            double lx = (int) c[3] * px, ly = (int) c[4] * px;
            // local +x is the figure's left = -right
            Vec3d at = s.origin.add(right.multiply(-lx)).add(0, ly, 0);
            Vec3d out = at.subtract(centre);
            out = out.lengthSquared() < 1e-4 ? new Vec3d(RANDOM.nextGaussian(), 0, RANDOM.nextGaussian()).normalize() : out.normalize();
            double speed = 1.1 + RANDOM.nextDouble() * 1.2;
            Vec3d vel = out.multiply(speed).add(RANDOM.nextGaussian() * 0.35, 1.8 + RANDOM.nextDouble() * 1.4, RANDOM.nextGaussian() * 0.35);
            Piece p = new Piece(at, vel, new Vec3d(part.w * px, (y1 - y0) * px, part.d * px), s.bodyYaw, part == SkinFigure.Part.HEAD);
            p.part = part;
            p.y0 = y0;
            p.y1 = y1;
            s.pieces.add(p);
            if (s.blood && mc.world != null)
                for (int k = 0; k < 5; k++)
                    mc.world.addParticleClient(new DustParticleEffect(0x9E0B12, 1.4f), at.x, at.y, at.z,
                            vel.x * 0.05, vel.y * 0.05, vel.z * 0.05);
        }
    }

    private static void stepAndDrawPieces(Slice s, long t, float dt) {
        float alpha = 1f - Math.clamp((t - (END - 1200)) / 1200f, 0f, 1f);
        Color acc = HudEditor.getColor(0);
        for (Piece p : s.pieces) {
            // physics: gravity, a floor, a bounce that loses most of its energy, ground friction
            p.vel = p.vel.add(0, -GRAVITY * dt, 0);
            Vec3d next = p.pos.add(p.vel.multiply(dt));
            double floor = s.origin.y + p.size.y / 2;
            if (next.y < floor) {
                next = new Vec3d(next.x, floor, next.z);
                p.vel = new Vec3d(p.vel.x * 0.6, Math.abs(p.vel.y) > 1.2 ? -p.vel.y * 0.3 : 0, p.vel.z * 0.6);
                p.spinYaw *= 0.6f;
                p.spinPitch *= 0.55f;
            }
            p.pos = next;
            p.yaw += p.spinYaw * dt;
            p.pitch += p.spinPitch * dt;

            if (p.part != null && s.victimSkin != null) {
                // a piece of their skin, turning about its own centre
                float px = SkinFigure.PX * s.scale;
                float topY = (p.y0 + p.y1) / 2f * px;
                Vec3d min = new Vec3d(-p.part.w * px / 2f, topY - p.part.h * px, -p.part.d * px / 2f);
                SkinFigure.part(s.victimSkin, p.pos, p.yaw, p.pitch, Vec3d.ZERO, min, p.part, p.y0, p.y1, s.scale, Math.round(255 * alpha));
                continue;
            }
            Vec3d h = p.size.multiply(0.5);
            Box local = new Box(-h.x, -h.y, -h.z, h.x, h.y, h.z);
            Color fill = p.head ? new Color(196, 150, 112, Math.round(250 * alpha)) : new Color(70, 76, 98, Math.round(250 * alpha));
            // the cut faces glow: an outline in the accent colour, hot near the moment of the split
            float heat = Math.clamp(1f - (t - SPLIT_AT) / 1800f, 0.45f, 1f);
            Color edge = Render2DEngine.injectAlpha(Render2DEngine.interpolateColorC(acc, new Color(255, 70, 60), heat), Math.round(235 * alpha));
            Render3DEngine.drawOrientedBox3D(p.pos, p.yaw, p.pitch, Vec3d.ZERO, local, fill, edge, 0.03f * s.scale, false);
        }
    }

    /* ------------------------------------------------------------------ the ninja */

    /** Katana pose over time: drawn, three cuts (raise -> strike), rest, then sheathed away. */
    private static void drawKatana(long t) {
        float pitch, yawOff, scale = 1f, alpha = 1f;
        float rest = -55f;
        int active = -1;
        for (int i = 0; i < SLASH_AT.length; i++)
            if (t >= SLASH_AT[i] && t < SLASH_AT[i] + SLASH_MS) active = i;
        if (t < SLASH_AT[0]) {                       // drawing it: rises out of the sheath
            float k = easeOut(t / (float) SLASH_AT[0]);
            pitch = 25f + (rest - 25f) * k;
            yawOff = 0;
            scale = 0.6f + 0.4f * k;
            alpha = k;
        } else if (active >= 0) {                    // a cut: from raised back to struck through
            float k = easeInOut((t - SLASH_AT[active]) / (float) SLASH_MS);
            pitch = -120f + 190f * k;
            float side = active == 1 ? -1f : 1f;
            yawOff = active == 2 ? 50f - 100f * k : side * (28f - 56f * k);
        } else if (t >= SHEATHE_AT) {                // sheathing: drops and shrinks away
            float k = easeInOut(Math.min(1f, (t - SHEATHE_AT) / (float) SHEATHE_MS));
            pitch = rest + (35f - rest) * k;
            yawOff = 0;
            scale = 1f - 0.5f * k;
            alpha = 1f - k;
        } else {                                     // between and after the cuts: settles back to rest
            pitch = rest + (float) Math.sin(t / 220.0) * 3f;
            yawOff = 0;
        }
        if (alpha <= 0.02f) return;

        float td = Render3DEngine.getTickDelta();
        Vec3d origin;
        float yaw, basePitch;
        if (mc.options.getPerspective().isFirstPerson() && !cinematicActive()) {
            var cam = mc.gameRenderer.getCamera();
            Vec3d fwd = Vec3d.fromPolar(cam.getPitch(), cam.getYaw());
            double yr = Math.toRadians(cam.getYaw());
            Vec3d right = new Vec3d(-Math.cos(yr), 0, -Math.sin(yr));
            origin = cam.getCameraPos().add(fwd.multiply(0.6)).add(right.multiply(0.33)).add(0, -0.33, 0);
            yaw = cam.getYaw() + yawOff;
            basePitch = cam.getPitch() * 0.6f;
        } else {
            float by = MathHelper.lerpAngleDegrees(td, mc.player.lastBodyYaw, mc.player.bodyYaw);
            double br = Math.toRadians(by);
            Vec3d fwd = new Vec3d(-Math.sin(br), 0, Math.cos(br));
            Vec3d right = new Vec3d(-fwd.z, 0, fwd.x);
            origin = mc.player.getLerpedPos(td).add(right.multiply(0.37)).add(0, mc.player.isSneaking() ? 0.5 : 0.72, 0).add(fwd.multiply(0.12));
            yaw = mc.player.getHeadYaw() + yawOff;
            basePitch = 0f;
        }
        float p = MathHelper.clamp(basePitch + pitch, -150f, 90f);
        int a = Math.round(255 * alpha);
        float s = scale;

        // handle (black with a red wrap), guard (gold), blade (steel, white edge), tip
        Render3DEngine.drawOrientedBox3D(origin, yaw, p, Vec3d.ZERO, new Box(-0.022 * s, -0.022 * s, -0.05 * s, 0.022 * s, 0.022 * s, 0.2 * s),
                new Color(22, 22, 26, a), new Color(160, 20, 32, a), 0.006f * s, false);
        Render3DEngine.drawOrientedBox3D(origin, yaw, p, Vec3d.ZERO, new Box(-0.06 * s, -0.06 * s, 0.2 * s, 0.06 * s, 0.06 * s, 0.215 * s),
                new Color(205, 165, 60, a), new Color(120, 90, 30, a), 0.004f * s, false);
        Render3DEngine.drawOrientedBox3D(origin, yaw, p, Vec3d.ZERO, new Box(-0.008 * s, -0.03 * s, 0.215 * s, 0.008 * s, 0.022 * s, 0.95 * s),
                new Color(206, 212, 222, a), new Color(255, 255, 255, Math.round(a * 0.85f)), 0.004f * s, false);
        Render3DEngine.drawOrientedBox3D(origin, yaw, p, Vec3d.ZERO, new Box(-0.006 * s, -0.016 * s, 0.95 * s, 0.006 * s, 0.012 * s, 1.0 * s),
                new Color(225, 230, 238, a), new Color(255, 255, 255, Math.round(a * 0.85f)), 0.003f * s, false);
    }

    /** Black headband with two red tails fluttering behind the head (third person). */
    private static void drawHeadband(long t) {
        float td = Render3DEngine.getTickDelta();
        Vec3d at = mc.player.getLerpedPos(td);
        float yaw = mc.player.getHeadYaw();
        double y = mc.player.isSneaking() ? 1.42 : 1.72;
        int a = t < 200 ? Math.round(255 * t / 200f) : 255;
        Render3DEngine.drawOrientedBox3D(at, yaw, 0f, Vec3d.ZERO, new Box(-0.27, y, -0.27, 0.27, y + 0.08, 0.27),
                new Color(18, 18, 22, a), new Color(170, 20, 32, a), 0.008f, false);
        float flutter = 18f + 10f * (float) Math.sin(t / 70.0);
        Vec3d knot = new Vec3d(0, y + 0.04, -0.27);
        for (int side = -1; side <= 1; side += 2) {
            Render3DEngine.drawOrientedBox3D(at, yaw + side * 8f, flutter + side * 4f, knot,
                    new Box(side * 0.02 - 0.02, y + 0.02, -0.27 - 0.38, side * 0.02 + 0.02, y + 0.06, -0.27),
                    new Color(175, 22, 34, a), new Color(110, 10, 20, a), 0.005f, false);
        }
    }

    /* ------------------------------------------------------------------ helpers */

    private static void puff(Vec3d at, int n) {
        if (mc.world == null) return;
        for (int i = 0; i < n; i++) {
            double a = Math.PI * 2 * i / n;
            mc.world.addParticleClient(ParticleTypes.POOF, at.x + Math.cos(a) * 0.5, at.y + 0.2 + RANDOM.nextDouble() * 1.6,
                    at.z + Math.sin(a) * 0.5, Math.cos(a) * 0.05, 0.02, Math.sin(a) * 0.05);
        }
        for (int i = 0; i < 4; i++)
            mc.world.addParticleClient(ParticleTypes.CAMPFIRE_COSY_SMOKE, at.x + RANDOM.nextGaussian() * 0.3, at.y + 0.3,
                    at.z + RANDOM.nextGaussian() * 0.3, 0, 0.02, 0);
    }

    private static void sound(SoundEvent event, float pitch, float volume) {
        try {
            float v = volume * ModuleManagerVolume.get();
            if (v <= 0.01f) return;
            mc.getSoundManager().play(PositionedSoundInstance.ui(event, pitch, v));
        } catch (Throwable ignored) {
        }
    }

    /** KillEffect's Volume slider, read lazily so this class has no hard dependency on the module order. */
    private static final class ModuleManagerVolume {
        static float get() {
            var ke = thunder.hack.core.manager.client.ModuleManager.killEffect;
            return ke == null ? 1f : ke.volume.getValue() / 100f;
        }
    }

    private static float easeOut(float x) {
        x = Math.clamp(x, 0f, 1f);
        return 1f - (1f - x) * (1f - x) * (1f - x);
    }

    private static float easeInOut(float x) {
        x = Math.clamp(x, 0f, 1f);
        return x < 0.5f ? 4f * x * x * x : 1f - (float) Math.pow(-2f * x + 2f, 3) / 2f;
    }
}
