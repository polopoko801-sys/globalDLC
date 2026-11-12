package thunder.hack.features.cosmetics.render;

import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.command.OrderedRenderCommandQueue;
import net.minecraft.client.util.math.MatrixStack;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Where cosmetic geometry goes.
 *
 * <p>BKClient (1.21.4) wrote straight into a {@code VertexConsumerProvider}. 1.21.11 no longer hands entity
 * features a provider - they submit commands to an {@link OrderedRenderCommandQueue} that is drawn later. The
 * cosmetic code still emits vertices exactly as BK did (already transformed, layer by layer); {@link #queued()}
 * records them per layer and {@link Recorder#flush} submits one custom command per layer that replays them.
 * GUI previews still own an immediate provider, so {@link #of} passes it straight through.
 */
@FunctionalInterface
public interface CosmeticSink {
    VertexConsumer buffer(RenderLayer layer);

    static CosmeticSink of(VertexConsumerProvider provider) {
        return provider::getBuffer;
    }

    static Recorder queued() {
        return new Recorder();
    }

    final class Recorder implements CosmeticSink {
        private final Map<RenderLayer, RecordingConsumer> layers = new LinkedHashMap<>();

        @Override
        public VertexConsumer buffer(RenderLayer layer) {
            return layers.computeIfAbsent(layer, ignored -> new RecordingConsumer());
        }

        public boolean isEmpty() {
            return layers.values().stream().allMatch(RecordingConsumer::isEmpty);
        }

        /** Positions are already final, so the commands are submitted with an identity pose. */
        public void flush(OrderedRenderCommandQueue queue) {
            MatrixStack identity = new MatrixStack();
            layers.forEach((layer, recorded) -> {
                if (!recorded.isEmpty()) queue.submitCustom(identity, layer, (entry, consumer) -> recorded.replay(consumer));
            });
            layers.clear();
        }
    }

    /** Captures the vertex calls cosmetics make; one vertex = x,y,z,u,v,nx,ny,nz + color, overlay, light. */
    final class RecordingConsumer implements VertexConsumer {
        private final List<float[]> floats = new ArrayList<>();
        private final List<int[]> ints = new ArrayList<>();
        private float[] f;
        private int[] i;

        boolean isEmpty() { return floats.isEmpty(); }

        void replay(VertexConsumer out) {
            for (int v = 0; v < floats.size(); v++) {
                float[] a = floats.get(v);
                int[] b = ints.get(v);
                out.vertex(a[0], a[1], a[2]).color(b[0]).texture(a[3], a[4])
                        .overlay(b[1], b[2]).light(b[3], b[4]).normal(a[5], a[6], a[7]);
            }
        }

        @Override public VertexConsumer vertex(float x, float y, float z) {
            f = new float[]{x, y, z, 0, 0, 0, 1, 0};
            i = new int[]{0xFFFFFFFF, 0, 10, 0xF0, 0xF0};
            floats.add(f);
            ints.add(i);
            return this;
        }
        @Override public VertexConsumer color(int red, int green, int blue, int alpha) {
            i[0] = (alpha & 255) << 24 | (red & 255) << 16 | (green & 255) << 8 | (blue & 255);
            return this;
        }
        @Override public VertexConsumer color(int argb) { i[0] = argb; return this; }
        @Override public VertexConsumer texture(float u, float v) { f[3] = u; f[4] = v; return this; }
        @Override public VertexConsumer overlay(int u, int v) { i[1] = u; i[2] = v; return this; }
        @Override public VertexConsumer light(int u, int v) { i[3] = u; i[4] = v; return this; }
        @Override public VertexConsumer normal(float x, float y, float z) { f[5] = x; f[6] = y; f[7] = z; return this; }
        @Override public VertexConsumer lineWidth(float width) { return this; }
    }
}
