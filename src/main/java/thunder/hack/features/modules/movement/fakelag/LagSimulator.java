package thunder.hack.features.modules.movement.fakelag;

import net.minecraft.util.math.Vec3d;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * A delay you can watch, that never touches the network.
 *
 * <p>This is the whole of FakeLag's TEST mode. Nothing here sends, holds or cancels a packet; it does not
 * know what a packet is. It takes a copy of where you are, parks it, and hands it back later - so the
 * pipeline is exactly:
 *
 * <pre>
 *   LIVE STATE  →  TEST QUEUE  →  DELAY  →  RELEASE  →  TEST STATE
 *   (your real      (samples,      (held     (oldest     (what a server
 *    position)       bounded)       DelayMs)  first)       lagging by
 *                                                          DelayMs would
 *                                                          be seeing)
 * </pre>
 *
 * <p>The point is calibration. In Live mode a wrong Delay shows up as a setback on a server, after the fact,
 * with no numbers attached. Here the same number is visible as a distance behind you and as a measured
 * statistic, with nothing at stake - and because the released state is kept, the ghost box can be drawn from
 * it, which is what makes "150ms" mean something you can actually look at.
 *
 * <p><b>Measured, not assumed.</b> {@link Stats#averageDelayMs()} is the real age of the samples at the
 * moment they were released, averaged over a sliding window - not the Delay setting echoed back. That is the
 * one number that tells you the simulation is doing what it claims; if the tick rate or a burst interval
 * stretches the real delay past what was asked for, this says so.
 *
 * <p><b>Threading.</b> Main thread only. {@link #sample} and {@link #step} are called from the client tick,
 * and rendering reads {@link #stats()} and {@link #testState()}, which are immutable snapshots published by
 * {@code step} - so a frame can never catch the queue halfway through being drained. Nothing in this class
 * is reachable from the network thread, which is the simplest possible answer to needing it to be safe there.
 */
public final class LagSimulator {

    /** One copy of the live state, and when it was taken. */
    public record Sample(Vec3d pos, float yaw, float pitch, long takenAt) {
    }

    /**
     * Everything the overlay shows, as one immutable object.
     *
     * @param simulatedDelayMs what was asked for
     * @param averageDelayMs   what was actually measured at release, over the recent window
     */
    public record Stats(boolean active, int queued, int peakQueue,
                        long released, long dropped,
                        long simulatedDelayMs, long averageDelayMs,
                        long lastDelayMs, double behindBlocks) {

        public static final Stats IDLE = new Stats(false, 0, 0, 0L, 0L, 0L, 0L, 0L, 0.0);
    }

    /** How many recent releases the average is taken over. */
    private static final int WINDOW = 40;

    private final Deque<Sample> queue = new ArrayDeque<>();

    /** The far end of the pipeline: the most recently released sample. Null until the first release. */
    private Sample testState;

    private long released;
    private long dropped;
    private int peakQueue;

    /** Sliding window of measured delays, as a ring. */
    private final long[] measured = new long[WINDOW];
    private int measuredCount;
    private int measuredIndex;
    private long lastDelay;

    /** When the last burst was let out, for BurstInterval. */
    private long lastRelease;

    private Stats published = Stats.IDLE;

    /* ------------------------------------------------------------------ */
    /* LIVE STATE -> TEST QUEUE                                            */
    /* ------------------------------------------------------------------ */

    /**
     * Takes one copy of the live state into the queue.
     *
     * @param queueLimit the cap. Over it the OLDEST sample is dropped and counted - the opposite of what Live
     *                   mode must do, and deliberately so: here a drop is a measurement, not a desync, and
     *                   seeing the Dropped counter climb is how you learn the delay is too large for the cap.
     */
    public void sample(@NotNull Vec3d pos, float yaw, float pitch, int queueLimit) {
        queue.addLast(new Sample(pos, yaw, pitch, System.currentTimeMillis()));

        int cap = Math.max(1, queueLimit);
        while (queue.size() > cap) {
            queue.removeFirst();
            dropped++;
        }

        if (queue.size() > peakQueue) peakQueue = queue.size();
    }

    /* ------------------------------------------------------------------ */
    /* DELAY -> RELEASE -> TEST STATE                                      */
    /* ------------------------------------------------------------------ */

    /**
     * Releases every sample that has been held long enough, and republishes the statistics.
     *
     * @param delayMs       how long a sample is held. Zero releases on the same tick it was taken, so the
     *                      test state tracks the live state exactly - which is the degenerate case worth
     *                      having work, because it is the baseline you compare the others against.
     * @param burstIntervalMs how often a release may happen. Zero means every tick, which is the smooth case;
     *                      a larger value holds samples back and then lets them all out at once, which is
     *                      what a real burst looks like and why Live mode avoids it.
     * @param livePos       where you are right now, only so "how far behind" can be measured
     */
    public void step(long delayMs, long burstIntervalMs, @Nullable Vec3d livePos) {
        long now = System.currentTimeMillis();

        boolean mayRelease = burstIntervalMs <= 0 || now - lastRelease >= burstIntervalMs;

        if (mayRelease) {
            boolean releasedAny = false;

            // Oldest first, and everything that qualifies: with a burst interval this is the burst, and with
            // none it is simply the one or two samples that came due since the last tick.
            while (!queue.isEmpty() && now - queue.peekFirst().takenAt() >= delayMs) {
                Sample sample = queue.removeFirst();
                testState = sample;

                long actual = now - sample.takenAt();
                record(actual);
                released++;
                releasedAny = true;
            }

            if (releasedAny) lastRelease = now;
        }

        double behind = 0.0;
        if (livePos != null && testState != null) behind = livePos.distanceTo(testState.pos());

        published = new Stats(true, queue.size(), peakQueue, released, dropped,
                delayMs, average(), lastDelay, behind);
    }

    private void record(long actual) {
        lastDelay = actual;
        measured[measuredIndex] = actual;
        measuredIndex = (measuredIndex + 1) % WINDOW;
        if (measuredCount < WINDOW) measuredCount++;
    }

    private long average() {
        if (measuredCount == 0) return 0L;

        long total = 0L;
        for (int i = 0; i < measuredCount; i++) total += measured[i];
        return total / measuredCount;
    }

    /* ------------------------------------------------------------------ */
    /* Reading                                                             */
    /* ------------------------------------------------------------------ */

    /** The far end of the pipeline - what a server lagging by Delay would currently believe. */
    public @Nullable Sample testState() {
        return testState;
    }

    /** An immutable snapshot, safe to read from a frame while the tick owns the queue. */
    public @NotNull Stats stats() {
        return published;
    }

    public int queued() {
        return queue.size();
    }

    /**
     * Throws the whole pipeline away.
     *
     * <p>Called on disable, disconnect, reconnect, world change, death and shutdown. Both halves matter: the
     * queue holds positions from a world that may no longer exist, and the counters describe a run that has
     * ended - leaving either behind means the next run opens with somebody else's statistics.
     */
    public void reset() {
        queue.clear();
        testState = null;
        released = 0L;
        dropped = 0L;
        peakQueue = 0;
        measuredCount = 0;
        measuredIndex = 0;
        lastDelay = 0L;
        lastRelease = 0L;
        published = Stats.IDLE;
    }

    /** True when there is nothing held and nothing released - i.e. a reset really did empty it. */
    public boolean empty() {
        return queue.isEmpty() && testState == null;
    }
}
