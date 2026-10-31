package thunder.hack.gui.windows;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static thunder.hack.core.manager.IManager.mc;

public class WindowsScreen extends Screen {
    private List<WindowBase> windows = new ArrayList<>();
    public static WindowBase lastClickedWindow;
    public static WindowBase draggingWindow;

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

        // The bottom dock (ClickGUI + window buttons) used to hang under every window; it covered the
        // lower edge of the screen and is gone. Windows are still toggled from the ClickGUI sidebar.

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
