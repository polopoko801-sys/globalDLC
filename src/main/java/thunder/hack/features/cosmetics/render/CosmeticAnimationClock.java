package thunder.hack.features.cosmetics.render;

/** A replay pass (for example Hands Shader Flame) must use the frozen visual pose. */
public final class CosmeticAnimationClock {
    private long frame = Long.MIN_VALUE;
    private long nanos;
    public float advance(long visualFrame, long now, boolean paused) {
        if (frame == visualFrame) return 0;
        if (visualFrame != frame + 1) nanos = 0;
        frame = visualFrame;
        float dt = nanos == 0 || paused ? 0 : Math.clamp((now - nanos) / 1_000_000_000f, 0, .25f);
        nanos = now;
        return dt;
    }
    public void reset() { frame = Long.MIN_VALUE; nanos = 0; }
}
