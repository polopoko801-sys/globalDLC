package thunder.hack.features.hud;

import java.util.HashMap;
import java.util.Map;

/**
 * Per-row show/hide progress for a HUD list: 0 hidden, 1 fully in. A row that appears runs up to 1 and one that
 * goes away runs back down to 0 before it is dropped, so lists like KeyBinds and ArrayList can slide each entry
 * in from the left and back out to the left instead of popping.
 *
 * <p>Advanced on real elapsed time, so the motion takes the same time at any framerate.
 */
public final class RowAnimator<K> {
    /** Time for one full slide, in ms. */
    private final float durationMs;
    private final Map<K, Float> progress = new HashMap<>();
    private long lastFrame;
    private float step;

    /** Delay between rows that arrive together, as a fraction of one slide. */
    private final float stagger;
    /** Rows that started arriving this frame - each waits one stagger longer than the one before it. */
    private int arrivals;

    public RowAnimator(float durationMs) {
        this(durationMs, 0.35f);
    }

    public RowAnimator(float durationMs, float stagger) {
        this.durationMs = durationMs;
        this.stagger = stagger;
    }

    /** Call once per frame before any {@link #update}. */
    public void frame() {
        long now = System.currentTimeMillis();
        float dt = lastFrame == 0L ? 16f : Math.min(100f, now - lastFrame);
        lastFrame = now;
        step = dt / durationMs;
        arrivals = 0;
    }

    /**
     * Moves this row towards shown or hidden. Rows that appear together come in one after another, top to
     * bottom: a waiting row holds a negative progress that counts up to 0 before it starts to slide.
     *
     * @return the progress, 0..1; 0 means the row is not on screen (gone, or still waiting its turn)
     */
    public float update(K key, boolean shown) {
        Float stored = progress.get(key);
        float p;
        if (stored == null) {
            if (!shown) return 0f;
            p = -stagger * arrivals++;   // queue behind whatever else arrived this frame
        } else {
            p = stored;
        }
        p = shown ? Math.min(1f, p + step) : Math.max(0f, p - step);
        if (p <= 0f && !shown) progress.remove(key);
        else progress.put(key, p);
        return Math.max(0f, p);
    }

    /** Smoothstep-free cubic ease-out: fast start, gentle landing. */
    public static float ease(float t) {
        float inv = 1f - Math.clamp(t, 0f, 1f);
        return 1f - inv * inv * inv;
    }
}
