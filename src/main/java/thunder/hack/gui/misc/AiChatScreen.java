package thunder.hack.gui.misc;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.input.KeyInput;
import net.minecraft.text.Text;
import org.jetbrains.annotations.NotNull;
import org.lwjgl.glfw.GLFW;
import thunder.hack.core.manager.client.ModuleManager;
import thunder.hack.features.modules.client.HudEditor;
import thunder.hack.features.modules.misc.LearnAI;
import thunder.hack.gui.font.FontRenderer;
import thunder.hack.gui.font.FontRenderers;
import thunder.hack.utility.render.Render2DEngine;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;

/**
 * A little chat window with LearnAI: the conversation as bubbles - your orders on the right, its answers on the
 * left - and a field at the bottom to type the next order. Enter sends, Esc closes. Opened from the "AI" chip
 * in the corner of the chat screen.
 */
public class AiChatScreen extends Screen {
    private static final float W = 300f, H = 220f, PAD = 10f, HEADER = 26f, INPUT_H = 20f;
    private static final float BUBBLE_MAX = W * 0.72f, LINE = 10f;

    private TextFieldWidget input;
    private long openedAt;

    public AiChatScreen() {
        super(Text.of("LearnAI"));
    }

    private float left() {
        return 12f;
    }

    private float top() {
        return 30f;
    }

    @Override
    protected void init() {
        openedAt = System.currentTimeMillis();
        input = new TextFieldWidget(textRenderer, Math.round(left() + PAD + 6), Math.round(top() + H - PAD - INPUT_H + 6),
                Math.round(W - PAD * 2 - 44), 12, Text.of(""));
        input.setDrawsBackground(false);
        input.setMaxLength(160);
        input.setPlaceholder(Text.of("Napisz, co ma zrobić... (np. walcz, stop, statystyki)"));
        addDrawableChild(input);
        setInitialFocus(input);
    }

    @Override
    public void render(@NotNull DrawContext context, int mouseX, int mouseY, float delta) {
        float t = Math.min(1f, (System.currentTimeMillis() - openedAt) / 220f);
        float e = 1f - (1f - t) * (1f - t) * (1f - t);
        float x = left(), y = top() - (1f - e) * 10f;
        Color accent = HudEditor.getColor(0);

        // the window: liquid glass - the world blurred behind it - sliding down into place as it opens
        Render2DEngine.drawRoundSmooth(context, x - 3f, y + 3f, W + 6f, H + 6f, 14f, new Color(0, 0, 0, Math.round(50 * e)));
        Render2DEngine.drawLiquidGlass(context, x, y, W, H, 12f, e);
        // a hairline under the header instead of a band
        Render2DEngine.drawRoundSmooth(context, x + PAD, y + HEADER, W - PAD * 2, 1f, 0.5f, new Color(255, 255, 255, Math.round(28 * e)));

        // header: the AI chip (sparkle + letters) and its state
        Render2DEngine.drawRoundSmooth(context, x + PAD, y + 5f, 34f, 16f, 8f, Render2DEngine.injectAlpha(accent, Math.round(225 * e)));
        sparkle(context, x + PAD + 8.5f, y + 13f, 3.8f, Color.WHITE);
        FontRenderers.sf_bold_mini.drawString(context, "AI", x + PAD + 15f, y + 10.5f, Color.WHITE.getRGB());
        LearnAI ai = ModuleManager.learnAI;
        String state = ai.isEnabled() ? "uczy się · pisz polecenia" : "uśpione · pierwsze polecenie je obudzi";
        FontRenderers.sf_medium_mini.drawString(context, "LearnAI  —  " + state, x + PAD + 40f, y + 10.5f, new Color(225, 225, 235).getRGB());

        drawConversation(context, x, y, ai, e);

        // the input
        float iy = y + H - PAD - INPUT_H;
        Render2DEngine.drawRoundSmooth(context, x + PAD, iy, W - PAD * 2, INPUT_H, 8f,
                new Color(255, 255, 255, input.isFocused() ? 30 : 18));
        float sx = x + W - PAD - 34f;
        boolean sendHover = Render2DEngine.isHovered(mouseX, mouseY, sx, iy + 2f, 32f, INPUT_H - 4f);
        Render2DEngine.drawRoundSmooth(context, sx, iy + 2f, 32f, INPUT_H - 4f, 7f,
                Render2DEngine.injectAlpha(accent, sendHover ? 255 : 200));
        FontRenderers.sf_medium_mini.drawCenteredString(context, "Wyślij", sx + 16f, iy + 7.5f, Color.WHITE.getRGB());
        input.setY(Math.round(iy + 6));

        super.render(context, mouseX, mouseY, delta);
    }

    /** The bubbles, newest at the bottom; older ones scroll off the top. */
    private void drawConversation(DrawContext context, float x, float y, LearnAI ai, float alpha) {
        FontRenderer font = FontRenderers.sf_medium_mini;
        float areaTop = y + HEADER + 4f, areaBottom = y + H - PAD - INPUT_H - 6f;
        Color accent = HudEditor.getColor(0);

        List<LearnAI.Said> said = new ArrayList<>(ai.conversation);
        if (said.isEmpty()) {
            font.drawString(context, "Cześć! Napisz poniżej, co mam zrobić.", x + PAD + 4f, areaTop + 8f, new Color(170, 170, 185).getRGB());
            font.drawString(context, "Na przykład: walcz, stop, klikaj prawym, ucz sie, statystyki,", x + PAD + 4f, areaTop + 20f, new Color(140, 140, 155).getRGB());
            font.drawString(context, "czego sie nauczyles, reset.", x + PAD + 4f, areaTop + 30f, new Color(140, 140, 155).getRGB());
            return;
        }

        float by = areaBottom;
        for (int i = said.size() - 1; i >= 0 && by > areaTop; i--) {
            LearnAI.Said s = said.get(i);
            List<String> lines = wrap(font, s.text(), BUBBLE_MAX - 14f);
            float textW = 0f;
            for (String l : lines) textW = Math.max(textW, font.getStringWidth(l));
            float bw = textW + 14f, bh = lines.size() * LINE + 8f;
            by -= bh;
            if (by < areaTop) break;
            float bx = s.fromPlayer() ? x + W - PAD - bw : x + PAD;
            // a new bubble pops in
            float age = Math.min(1f, (System.currentTimeMillis() - s.at()) / 200f);
            int a = Math.round(255 * alpha * age);
            Color fill = s.fromPlayer() ? Render2DEngine.injectAlpha(accent, Math.round(200 * alpha * age)) : new Color(255, 255, 255, Math.round(22 * alpha * age));
            Render2DEngine.drawRoundSmooth(context, bx, by, bw, bh, 7f, fill);
            for (int k = 0; k < lines.size(); k++)
                font.drawString(context, lines.get(k), bx + 7f, by + 5f + k * LINE, new Color(255, 255, 255, Math.max(5, a)).getRGB());
            by -= 5f;
        }
    }

    /** A four-pointed star centred on (x, y) - the AI mark, as on the chat badge. */
    private static void sparkle(DrawContext context, float x, float y, float r, Color c) {
        float w = r * 0.32f;
        Render2DEngine.drawPolygonSmooth(context, new float[]{x, x + w, x, x - w}, new float[]{y - r, y, y + r, y}, c);
        Render2DEngine.drawPolygonSmooth(context, new float[]{x - r, x, x + r, x}, new float[]{y, y - w, y, y + w}, c);
    }

    private static List<String> wrap(FontRenderer font, String text, float max) {
        List<String> out = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : text.split(" ")) {
            String next = line.isEmpty() ? word : line + " " + word;
            if (font.getStringWidth(next) > max && !line.isEmpty()) {
                out.add(line.toString());
                line = new StringBuilder(word);
            } else line = new StringBuilder(next);
        }
        if (!line.isEmpty()) out.add(line.toString());
        return out;
    }

    private void send() {
        String text = input.getText().strip();
        if (text.isEmpty()) return;
        ModuleManager.learnAI.onChat(text);
        input.setText("");
    }

    @Override
    public boolean keyPressed(KeyInput keyInput) {
        if (keyInput.key() == GLFW.GLFW_KEY_ENTER || keyInput.key() == GLFW.GLFW_KEY_KP_ENTER) {
            send();
            return true;
        }
        return super.keyPressed(keyInput);
    }

    @Override
    public boolean mouseClicked(net.minecraft.client.gui.Click click, boolean doubled) {
        float iy = top() + H - PAD - INPUT_H;
        float sx = left() + W - PAD - 34f;
        if (Render2DEngine.isHovered(click.x(), click.y(), sx, iy + 2f, 32f, INPUT_H - 4f)) {
            send();
            return true;
        }
        return super.mouseClicked(click, doubled);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}
