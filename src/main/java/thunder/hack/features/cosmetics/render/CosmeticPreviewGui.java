package thunder.hack.features.cosmetics.render;

import net.fabricmc.fabric.api.client.rendering.v1.SpecialGuiElementRegistry;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.ScreenRect;
import net.minecraft.client.gui.render.SpecialGuiElementRenderer;
import net.minecraft.client.gui.render.state.special.SpecialGuiElementRenderState;
import net.minecraft.client.render.DiffuseLighting;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.math.MatrixStack;
import org.joml.Matrix3x2f;
import org.joml.Matrix4f;
import thunder.hack.features.cosmetics.CosmeticEntry;

import java.util.List;

/**
 * 3D cosmetic previews inside a GUI on 1.21.11.
 *
 * <p>BKClient drew previews straight into the 1.21.4 GUI with raw GL state. 1.21.11 GUIs are recorded and drawn
 * later, and 3D content goes through a {@link SpecialGuiElementRenderer} that renders into its own off-screen
 * texture. Such a renderer owns a single texture per frame, so every card of a page is one element: the
 * element covers the whole card grid (which also clips the models to it), and each card is framed inside it
 * with BK's own preview code.
 */
public final class CosmeticPreviewGui {
    public record Card(CosmeticEntry entry, float x, float y, float width, float height, float alpha) {}

    public record State(List<Card> cards, int x1, int y1, int x2, int y2, Matrix3x2f pose, ScreenRect scissorArea,
                        ScreenRect bounds) implements SpecialGuiElementRenderState {
        @Override public float scale() { return 1f; }
    }

    private CosmeticPreviewGui() {}

    public static void register() {
        CosmeticRenderer.init();
        MeleeCosmeticRenderer.init();
        SpecialGuiElementRegistry.register(context -> new Renderer(context.vertexConsumers()));
    }

    /** Queues one element covering {@code x1..x2, y1..y2} (GUI units) that draws all {@code cards}. */
    public static void submit(DrawContext context, List<Card> cards, int x1, int y1, int x2, int y2) {
        if (cards.isEmpty() || x2 <= x1 || y2 <= y1) return;
        ScreenRect bounds = SpecialGuiElementRenderState.createBounds(x1, y1, x2, y2, null);
        context.state.addSpecialElement(new State(List.copyOf(cards), x1, y1, x2, y2,
                new Matrix3x2f(context.getMatrices()), null, bounds));
    }

    private static final class Renderer extends SpecialGuiElementRenderer<State> {
        private final VertexConsumerProvider.Immediate buffers;

        Renderer(VertexConsumerProvider.Immediate buffers) {
            super(buffers);
            this.buffers = buffers;
        }

        @Override public Class<State> getElementClass() { return State.class; }
        @Override protected String getName() { return "gombal_cosmetic_previews"; }

        /** Origin in the middle of the element, like vanilla's entity previews. */
        @Override protected float getYOffset(int height, int windowScale) { return height / 2f; }

        @Override
        protected void render(State state, MatrixStack matrices) {
            MinecraftClient.getInstance().gameRenderer.getDiffuseLighting().setShaderLights(DiffuseLighting.Type.ENTITY_IN_UI);
            // The stack is centred on the element and scaled to GUI units with +Y down, so moving it back to the
            // element's top-left lets every card use its plain GUI coordinates.
            float cx = (state.x1() + state.x2()) / 2f, cy = (state.y1() + state.y2()) / 2f;
            Matrix4f base = new Matrix4f(matrices.peek().getPositionMatrix()).translate(-cx, -cy, 0);
            CosmeticSink sink = CosmeticSink.of(buffers);
            for (Card card : state.cards())
                CosmeticRenderer.renderPreview(card.entry(), base, card.x(), card.y(), card.width(), card.height(), card.alpha(), sink);
        }
    }
}
