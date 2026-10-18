package thunder.hack.gui.windows;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import thunder.hack.gui.clickui.ClickGUI;
import thunder.hack.features.modules.Module;
import thunder.hack.features.modules.client.HudEditor;
import thunder.hack.utility.render.Render2DEngine;
import thunder.hack.utility.render.TextureStorage;

import java.awt.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static thunder.hack.core.manager.IManager.mc;

public class WindowsScreen extends Screen {
    private List<WindowBase> windows = new ArrayList<>();
    public static WindowBase lastClickedWindow;
    public static WindowBase draggingWindow;
    private static final Identifier clickGuiIcon = Identifier.of("gombal", "textures/gui/elements/clickgui.png");

    public WindowsScreen(WindowBase... windows) {
        super(Text.of("THWindows"));
        this.windows.clear();
        lastClickedWindow = null;
        this.windows = Arrays.stream(windows).toList();
    }


    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        //   super.render(context, mouseX, mouseY, delta);
        // 1.21.11: guarded on world==null (menu), where the game already draws+blurs the backdrop
        // before render(); calling renderBackground again throws "Can only blur once per frame".

        // fresh snapshot of what is behind the screen so the glass windows have a backdrop to blur
        thunder.hack.utility.render.ScreenCapture.captureFrame();

        DrawContext matrices = context;
        int i = mc.getWindow().getScaledWidth() / 2;

        float offset = (windows.size() * 20f) / -2f - 23;

        // the dock is a liquid-glass slab too - same recipe as the windows, its highlight following the
        // cursor, so the whole shell reads as one material
        Render2DEngine.drawBlurredShadow(matrices, i + offset - 1.5f, mc.getWindow().getScaledHeight() - 24, windows.size() * 20f + 23f, 19, 14, new Color(0, 0, 0, 130));
        WindowBase.glassPanel(matrices, i + offset - 1.5f, mc.getWindow().getScaledHeight() - 25, windows.size() * 20f + 23f, 19, 5f, mouseX, mouseY);

        thunder.hack.utility.render.RSCompat.enableBlend();
        thunder.hack.utility.render.RSCompat.defaultBlendFunc();
        thunder.hack.utility.render.RSCompat.setShaderColor(1f, 1f, 1f, Render2DEngine.isHovered(mouseX, mouseY, (i + offset) + 1, mc.getWindow().getScaledHeight() - 23, 15, 15) ? 0.95f : 0.7f);
        context.drawTexture(net.minecraft.client.gl.RenderPipelines.GUI_TEXTURED, clickGuiIcon, (int) (i + offset) + 1, mc.getWindow().getScaledHeight() - 23, 15, 15, 0, 0, 15, 15, 15, 15);
        thunder.hack.utility.render.RSCompat.setShaderColor(1f, 1f, 1f, 1f);
        thunder.hack.utility.render.RSCompat.disableBlend();

        Render2DEngine.drawLine(context, i + offset + 20, mc.getWindow().getScaledHeight() - 23, i + offset + 20, mc.getWindow().getScaledHeight() - 9, Render2DEngine.injectAlpha(WindowBase.TH_ACCENT, 120).getRGB());

        offset += 23;
        for (WindowBase w : windows) {
            Color c = Render2DEngine.isHovered(mouseX, mouseY, i + offset, mc.getWindow().getScaledHeight() - 24, 17, 17) ? WindowBase.TH_HEADER :
                    !w.isVisible() ? new Color(30, 31, 41) : Render2DEngine.injectAlpha(WindowBase.TH_ACCENT, 70);
            Render2DEngine.drawRound(matrices, i + offset, mc.getWindow().getScaledHeight() - 24, 17, 17, 4f, c);
            thunder.hack.utility.render.RSCompat.enableBlend();
            thunder.hack.utility.render.RSCompat.noop();
            thunder.hack.utility.render.RSCompat.setShaderColor(1f, 1f, 1f, Render2DEngine.isHovered(mouseX, mouseY, (i + offset) + 1, mc.getWindow().getScaledHeight() - 23, 15, 15) ? 0.95f : 0.7f);
            context.drawTexture(net.minecraft.client.gl.RenderPipelines.GUI_TEXTURED, w.getIcon() != null ? w.getIcon() : TextureStorage.configIcon, (int) (i + offset) + 3, mc.getWindow().getScaledHeight() - 21, 11, 11, 0, 0, 11, 11, 11, 11);
            thunder.hack.utility.render.RSCompat.setShaderColor(1f, 1f, 1f, 1f);
            thunder.hack.utility.render.RSCompat.disableBlend();
            offset += 20f;
        }

        windows.stream().filter(WindowBase::isVisible).forEach(w -> {
            if (w != lastClickedWindow)
                w.render(context, mouseX, mouseY);
        });

        if (lastClickedWindow != null && lastClickedWindow.isVisible())
            lastClickedWindow.render(context, mouseX, mouseY);
    }

        // 1.21.11: new input signatures (Click / KeyInput / CharInput). Old overrides were dead -> no clicks.
        @Override
        public boolean mouseReleased(net.minecraft.client.gui.Click click) {
        double mouseX = click.x(); double mouseY = click.y(); int button = click.button();
        windows.forEach(w -> w.mouseReleased(mouseX, mouseY, button));
        return false;
    }

        @Override
        public boolean mouseClicked(net.minecraft.client.gui.Click click, boolean doubled) {
        double mouseX = click.x(); double mouseY = click.y(); int button = click.button();

        int i = mc.getWindow().getScaledWidth() / 2;
        float offset = (windows.size() * 20f) / -2f - 23;

        if (Render2DEngine.isHovered(mouseX, mouseY, (i + offset) + 1, mc.getWindow().getScaledHeight() - 23, 15, 15)) {
            mc.setScreen(ClickGUI.getClickGui());
            return true;
        }

        offset += 23;
        for (WindowBase w : windows) {
            if (Render2DEngine.isHovered(mouseX, mouseY, i + offset, mc.getWindow().getScaledHeight() - 24, 17, 17)) {
                w.setVisible(!w.isVisible());
                if (w.isVisible())
                    lastClickedWindow = w;
                return true;
            }
            offset += 20f;
        }

        // only the window under the cursor takes the click. Handing it to every window let one
        // sitting underneath steal the keyboard focus (or start dragging along with the top one).
        WindowBase target = null;
        if (lastClickedWindow != null && lastClickedWindow.isVisible() && lastClickedWindow.contains(mouseX, mouseY)) {
            target = lastClickedWindow;
        } else {
            for (int k = windows.size() - 1; k >= 0; k--) {
                WindowBase w = windows.get(k);
                if (w.isVisible() && w.contains(mouseX, mouseY)) {
                    target = w;
                    break;
                }
            }
        }

        if (target != null) {
            lastClickedWindow = target; // also raises it above the others
            target.mouseClicked(mouseX, mouseY, button);
        }

        return true;
    }

    /** The window that owns the keyboard, i.e. the last one clicked. */
    private WindowBase focused() {
        return lastClickedWindow != null && lastClickedWindow.isVisible() && windows.contains(lastClickedWindow)
                ? lastClickedWindow : null;
    }

        @Override
        public boolean keyPressed(net.minecraft.client.input.KeyInput input) {
        int keyCode = input.key(); int scanCode = input.scancode(); int modifiers = input.modifiers();
        WindowBase focused = focused();
        // checked before the key is delivered - ESC first leaves the text field, only then the screen
        boolean typing = focused != null && focused.isTyping();

        if (focused != null)
            focused.keyPressed(keyCode, scanCode, modifiers);
        else
            windows.stream().filter(WindowBase::isVisible).forEach(w -> w.keyPressed(keyCode, scanCode, modifiers));

        // 1.21.11: active override without super -> ESC no longer auto-closes; close explicitly.
        if (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE && !typing) {
            this.close();
            return true;
        }
        return false;
    }

    @Override
    public boolean charTyped(net.minecraft.client.input.CharInput input) {
        char key = (char) input.codepoint(); int keyCode = input.modifiers();
        WindowBase focused = focused();
        if (focused != null)
            focused.charTyped(key, keyCode);
        else
            windows.stream().filter(WindowBase::isVisible).forEach(w -> w.charTyped(key, keyCode));
        return false;
    }

    /**
     * Puts two windows side by side when their saved positions overlap. The stock offsets stacked
     * every window a few pixels apart, so the one below was buried under the next one's body and
     * looked empty.
     */
    public static void arrange(WindowBase a, WindowBase b) {
        boolean overlap = a.getX() < b.getX() + b.getWidth() + 10 && b.getX() < a.getX() + a.getWidth() + 10
                && a.getY() < b.getY() + b.getHeight() && b.getY() < a.getY() + a.getHeight();
        if (!overlap) return;

        float sw = mc.getWindow().getScaledWidth();
        float sh = mc.getWindow().getScaledHeight();
        float total = a.getWidth() + b.getWidth() + 50;
        float x = Math.max(6f, (sw - total) / 2f);
        float y = Math.max(6f, (sh - Math.max(a.getHeight(), b.getHeight())) / 2f - 14f);

        a.place(x, y);
        b.place(x + a.getWidth() + 40, y);
    }

        public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        windows.stream().filter(WindowBase::isVisible).forEach(w -> w.mouseScrolled((int) (verticalAmount * 5D)));
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }
}
