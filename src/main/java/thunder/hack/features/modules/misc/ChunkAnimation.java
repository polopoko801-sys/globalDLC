package thunder.hack.features.modules.misc;

import net.minecraft.client.render.chunk.ChunkBuilder;
import net.minecraft.util.math.BlockPos;
import thunder.hack.features.modules.Module;
import thunder.hack.setting.Setting;

import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 1:1 port of Celestial's {@code ChunkAnimator}: every chunk section slides up into place from Y=0 the first
 * time it is drawn, easing out over {@code Animate Delay} milliseconds.
 *
 * <p>The two Celestial events map onto MixinWorldRendererChunks: {@link #onChunkRender} is EventRenderChunk
 * (section seen) and {@link #chunkOffset} is EventRenderChunkContainer (section about to be drawn), whose
 * {@code GlStateManager.translate(0, -chunkY + offsetY, 0)} becomes a translation of the section's own
 * model-view matrix - the same transform, applied the way 1.21.11 draws sections.
 *
 * <p>One addition the port needs: 1.21.11 keeps a fixed pool of BuiltChunks and moves them to new positions
 * as you walk, where 1.12 rebuilt RenderChunks. Keyed by the object alone, only the first screenful would ever
 * animate - so a section that has been moved to a new origin starts its animation again.
 */
public class ChunkAnimation extends Module {
    public ChunkAnimation() {
        super("ChunkAnimation", Category.MISC);
    }

    private final WeakHashMap<ChunkBuilder.BuiltChunk, Life> lifespans = new WeakHashMap<>();
    private final Setting<Float> animateDelay = new Setting<>("Animate Delay", 1500.0f, 100.0f, 5000.0f);

    private record Life(BlockPos origin, AtomicLong time) {
    }

    @Override
    public void onEnable() {
        lifespans.clear();
    }

    @Override
    public void onDisable() {
        lifespans.clear();
    }

    private double easeOutCubic(double t) {
        return (t -= 1.0) * t * t + 1.0;
    }

    /** EventRenderChunk. */
    public void onChunkRender(ChunkBuilder.BuiltChunk chunk, BlockPos origin) {
        Life life = lifespans.get(chunk);
        if (mc.player != null && (life == null || !life.origin().equals(origin))) {
            lifespans.put(chunk, new Life(origin.toImmutable(), new AtomicLong(-1L)));
        }
    }

    /** EventRenderChunkContainer: the Y translation for this section, 0 when it is done animating. */
    public float chunkOffset(ChunkBuilder.BuiltChunk chunk, BlockPos origin) {
        Life life = lifespans.get(chunk);
        if (life != null) {
            long timeDifference;
            AtomicLong timeAlive = life.time();
            long timeClone = timeAlive.get();
            if (timeClone == -1L) {
                timeClone = System.currentTimeMillis();
                timeAlive.set(timeClone);
            }
            if ((float) (timeDifference = System.currentTimeMillis() - timeClone) <= animateDelay.getValue()) {
                double chunkY = origin.getY();
                double offsetY = chunkY * easeOutCubic((float) timeDifference / animateDelay.getValue());
                return (float) (-chunkY + offsetY);
            }
        }
        return 0f;
    }
}
