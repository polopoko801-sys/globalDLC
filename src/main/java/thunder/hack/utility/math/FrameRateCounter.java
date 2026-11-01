package thunder.hack.utility.math;

import java.util.ArrayDeque;

public class FrameRateCounter {
    public static final FrameRateCounter INSTANCE = new FrameRateCounter();
    private final ArrayDeque<Long> records = new ArrayDeque<>();
    private int fps = 5;

    private long lastFrameNs;
    /** Seconds the last frame took, lightly smoothed so one hitch does not fling every animation forward. */
    private float frameDelta = 0.016f;

    public void recordFrame() {
        long nowNs = System.nanoTime();
        if (lastFrameNs != 0) {
            float raw = (nowNs - lastFrameNs) / 1e9f;
            // clamp: a frozen game (alt-tab, loading) must not teleport animations to their end in one frame
            raw = Math.max(0.0005f, Math.min(0.1f, raw));
            frameDelta += (raw - frameDelta) * 0.5f;
        }
        lastFrameNs = nowNs;

        long ms = nowNs / 1_000_000L;
        records.addLast(ms);
        while (!records.isEmpty() && records.peekFirst() + 1000 < ms) records.pollFirst();
        fps = Math.max(records.size(), 4);
    }

    public int getFps() {
        return fps;
    }

    /** Real duration of the last frame in seconds (0.0005 .. 0.1). */
    public float getFrameDelta() {
        return frameDelta;
    }
}
