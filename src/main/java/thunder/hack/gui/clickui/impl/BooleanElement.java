package thunder.hack.gui.clickui.impl;

import net.minecraft.client.gui.DrawContext;
import org.lwjgl.glfw.GLFW;
import thunder.hack.core.Managers;
import thunder.hack.features.modules.client.HudEditor;
import thunder.hack.gui.clickui.AbstractElement;
import thunder.hack.gui.clickui.ClickGUI;
import thunder.hack.gui.font.FontRenderers;
import thunder.hack.setting.Setting;
import thunder.hack.utility.render.Render2DEngine;

import java.awt.*;

import static thunder.hack.core.manager.IManager.mc;
import static thunder.hack.utility.render.animation.AnimationUtility.fast;

public class BooleanElement extends AbstractElement {
    public BooleanElement(Setting setting) {
        super(setting);
    }

    float animation = 0f;
    float animation2 = 0f;

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);

        // Pill track, round knob, nothing else. The control used to be three stacked squares - a nearly
        // square track (radius 1), the knob drawn twice for the animation, and a v / x letter printed on
        // top of the knob - which is what made the module settings look like a wall of little boxes.
        animation = fast(animation, (boolean) setting.getValue() ? 1 : 0, 20f);
        animation2 = fast(animation2, (boolean) setting.getValue() ? 1 : 0, 8f);
        Render2DEngine.drawRound(context, x + width - 21, y + height / 2 - 4, 15, 8, 4f,
                7f * animation > 4 ? HudEditor.getColor(0) : new Color(0x28FFFFFF, true));
        Render2DEngine.drawRound(context, x + width - 20 + 7f * animation2, y + height / 2 - 3, 6, 6, 3f, new Color(-1));

        if (Render2DEngine.isHovered(mouseX, mouseY, x + width - 21, y + height / 2 - 4, 15, 8)) {
            if (GLFW.glfwGetPlatform() != GLFW.GLFW_PLATFORM_WAYLAND) {
                GLFW.glfwSetCursor(mc.getWindow().getHandle(),
                        thunder.hack.utility.render.Cursors.get(GLFW.GLFW_HAND_CURSOR));
            }
            ClickGUI.anyHovered = true;
        }

        FontRenderers.sf_medium_mini.drawString(context, setting.getName(), (setting.group != null ? 2f : 0f) + (x + 6), (y + height / 2 - 3) + 2, new Color(-1).getRGB());
    }

    @Override
    public void mouseClicked(int mouseX, int mouseY, int button) {
        if (hovered && button == 0) {
            setting.setValue(!((Boolean) setting.getValue()));
            Managers.SOUND.playBoolean();
        }

        return;
    }
}