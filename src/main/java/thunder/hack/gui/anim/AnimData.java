package thunder.hack.gui.anim;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The data behind the animation editor: a set of named presets, each a START pose and an END pose plus a cubic
 * easing curve, a swing speed and a "return" flag. {@link thunder.hack.features.modules.render.Animations} reads the
 * active preset every frame to transform the held item, so the editor and the in-game look share one source.
 */
public final class AnimData {

    /** A single keyframe pose: translation (blocks) + rotation (degrees) + scale. */
    public static final class Pose {
        public float tx, ty, tz;
        public float rx, ry, rz;
        public float scale = 1f;

        public Pose() {}

        public Pose(float rx, float ry, float rz) {
            this.rx = rx;
            this.ry = ry;
            this.rz = rz;
        }

        public Pose copy() {
            Pose p = new Pose();
            p.tx = tx; p.ty = ty; p.tz = tz;
            p.rx = rx; p.ry = ry; p.rz = rz;
            p.scale = scale;
            return p;
        }
    }

    public static final class Preset {
        public String name;
        public final Pose start;
        public final Pose end;
        // cubic-bezier easing control points (x1,y1,x2,y2) in 0..1 - the curve in the middle panel
        public float e1x = 0.25f, e1y = 0.1f, e2x = 0.25f, e2y = 1f;
        public float swingSpeed = 2f;
        public boolean returnBack = true;

        public Preset(String name, Pose start, Pose end) {
            this.name = name;
            this.start = start;
            this.end = end;
        }
    }

    public static final Map<String, Preset> PRESETS = new LinkedHashMap<>();
    public static String active = "Block Hit";

    static {
        // a few built-ins matching the reference
        add(new Preset("Block Hit", new Pose(0, 0, 0), new Pose(-55, 10, -15)));
        add(new Preset("Bonk", new Pose(-20, 0, 20), new Pose(40, -10, -30)));
        add(new Preset("360 Rotation", new Pose(0, 0, 0), new Pose(0, 360, 0)));
        add(new Preset("Push", new Pose(0, 0, 0), new Pose(-10, 0, 0)));
    }

    private static void add(Preset p) {
        PRESETS.put(p.name, p);
    }

    public static Preset current() {
        Preset p = PRESETS.get(active);
        if (p == null) {
            p = PRESETS.values().iterator().next();
            active = p.name;
        }
        return p;
    }

    public static List<String> names() {
        return new ArrayList<>(PRESETS.keySet());
    }

    /** Save/overwrite a preset by name, cloning the current one's poses/curve. */
    public static void saveAs(String name) {
        if (name == null || name.isBlank()) return;
        Preset c = current();
        Preset np = new Preset(name, c.start.copy(), c.end.copy());
        np.e1x = c.e1x; np.e1y = c.e1y; np.e2x = c.e2x; np.e2y = c.e2y;
        np.swingSpeed = c.swingSpeed; np.returnBack = c.returnBack;
        PRESETS.put(name, np);
        active = name;
    }

    /** Cubic-bezier ease of the active preset, y for a given x in 0..1. */
    public static float ease(float t) {
        Preset p = current();
        t = Math.max(0f, Math.min(1f, t));
        // sample the bezier by its parameter ~ t (good enough for a swing curve)
        float u = 1 - t;
        float y = 3 * u * u * t * p.e1y + 3 * u * t * t * p.e2y + t * t * t;
        return Math.max(0f, Math.min(1f, y));
    }

    public static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }
}
