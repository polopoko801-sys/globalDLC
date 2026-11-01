package thunder.hack.features.hud;

import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ChatScreen;
import org.jetbrains.annotations.NotNull;
import org.lwjgl.glfw.GLFW;
import thunder.hack.core.Managers;
import thunder.hack.events.impl.EventMouse;
import thunder.hack.features.modules.Module;
import thunder.hack.features.modules.client.HudEditor;
import thunder.hack.gui.hud.HudEditorGui;
import thunder.hack.setting.Setting;
import thunder.hack.setting.impl.PositionSetting;
import thunder.hack.utility.render.Render2DEngine;
import thunder.hack.utility.render.Render3DEngine;

import java.awt.*;

public class HudElement extends Module {
    private final Setting<PositionSetting> pos = new Setting<>("Position", new PositionSetting(0.5f, 0.5f));
    private boolean mouseState = false, mouseButton = false;
    private float x, y, dragX, dragY, hitX, hitY;
    private float height, width;
    public static boolean anyHovered = false;

    public HudElement(String name, int width, int height) {
        super(name, Category.HUD);
        this.height = height;
        this.width = width;
    }

    /**
     * Same as above but with a custom default screen anchor (0..1 of the window), so an element can start
     * in a corner instead of the middle of the screen. Saved configs still override this.
     */
    public HudElement(String name, int width, int height, float defaultX, float defaultY) {
        this(name, width, height);
        pos.getValue().setX(defaultX);
        pos.getValue().setY(defaultY);
    }

    /** Whether the element set its bounds last frame, and whether those bounds are the placeholder. */
    private boolean boundsSet, placeholder;

    private boolean editing() {
        return draggable() && (mc.currentScreen instanceof ChatScreen || mc.currentScreen instanceof HudEditorGui);
    }

    /** False for elements that ignore their HUD position (anchored to the crosshair / hotbar): no placeholder plate. */
    protected boolean draggable() {
        return true;
    }

    /**
     * An element with nothing to show (Potions without effects, ArmorHud without armor...) still gets a plate with
     * its name while chat / the HUD editor is open, so it can be found and dragged. Without this its hit box was
     * 0x0, or - when it never set bounds - stuck at the top-left corner of the screen.
     */
    private void applyPlaceholder(float px, float py) {
        hitX = px;
        hitY = py;
        width = thunder.hack.gui.font.FontRenderers.sf_bold_mini.getStringWidth(getName()) + 10;
        height = 14;
        placeholder = true;
    }

    @Override
    public void onRender2D(DrawContext context) {
        y = mc.getWindow().getScaledHeight() * pos.getValue().getY();
        x = mc.getWindow().getScaledWidth() * pos.getValue().getX();

        if (editing() && (!boundsSet || placeholder)) {
            applyPlaceholder(x, y);
            Render2DEngine.drawRound(context, x, y, width, height, 3, new Color(20, 20, 25, 170));
            thunder.hack.gui.font.FontRenderers.sf_bold_mini.drawString(context, getName(), x + 5, y + 4.5f, 0xFFB4B4BE);
        } else if (!editing()) {
            placeholder = false;
        }
        boundsSet = false;

        if (mc.currentScreen instanceof ChatScreen || mc.currentScreen instanceof HudEditorGui) {
            if (mouseButton && mouseState) {
                pos.getValue().setX(Math.clamp(thunder.hack.utility.render.animation.AnimationUtility.fast(pos.getValue().getX(), (normaliseX() - dragX) / mc.getWindow().getScaledWidth(), 6.3f),
                        0, 1f));
                pos.getValue().setY(Math.clamp(thunder.hack.utility.render.animation.AnimationUtility.fast(pos.getValue().getY(), (normaliseY() - dragY) / mc.getWindow().getScaledHeight(), 6.3f),
                        0, 1f - (height / mc.getWindow().getScaledHeight())));

                float finalX = 0;
                float finalY = 0;

                if (HudEditor.sticky.getValue())
                    for (Module m : Managers.MODULE.getEnabledModules())
                        if (m instanceof HudElement hudElement && hudElement != this && !(hudElement.getPosX() == 0 && hudElement.getPosY() == 0)) {
                            if (getPosX() > mc.getWindow().getScaledWidth() / 2f) {
                                if (isNear(hudElement.getHitX() + hudElement.getWidth(), getHitX() + getWidth()))
                                    finalX = hudElement.getHitX() + hudElement.getWidth() - getWidth();

                            } else {
                                if (isNear(hudElement.getHitX(), getHitX()))
                                    finalX = hudElement.getHitX();

                            }
                            if (isNear(hudElement.getHitY(), getHitY()))
                                finalY = hudElement.getHitY();
                        }

                if (finalX != 0 || finalY != 0)
                    Render2DEngine.drawRound(context, finalX == 0 ? getHitX() : finalX, finalY == 0 ? getHitY() : finalY, width, height, 3, new Color(0x7B2F2F2F, true));

                if (finalX != 0)
                    Render2DEngine.drawLine(context, finalX, 0, finalX, mc.getWindow().getScaledHeight(), -1);

                if (finalY != 0)
                    Render2DEngine.drawLine(context, 0, finalY, mc.getWindow().getScaledWidth(), finalY, -1);
            }
        }

        if (mouseButton) {
            if (!mouseState && isHovering()) {
                dragX = (int) (normaliseX() - (pos.getValue().getX() * mc.getWindow().getScaledWidth()));
                dragY = (int) (normaliseY() - (pos.getValue().getY() * mc.getWindow().getScaledHeight()));
                mouseState = true;
            }
        } else {
            mouseState = false;
        }

        if (isHovering() && (mc.currentScreen instanceof ChatScreen || mc.currentScreen instanceof HudEditorGui)) {
            if (GLFW.glfwGetPlatform() != GLFW.GLFW_PLATFORM_WAYLAND) {
                GLFW.glfwSetCursor(mc.getWindow().getHandle(), mouseState ? thunder.hack.utility.render.Cursors.get(GLFW.GLFW_CROSSHAIR_CURSOR) : thunder.hack.utility.render.Cursors.get(GLFW.GLFW_HAND_CURSOR));
            }
            anyHovered = true;
        }
        // Render2DEngine.drawRect(context,getPosX(), getPosY(), width, height, Color.RED);
    }

    @EventHandler
    @SuppressWarnings("unused")
    public void onMouse(@NotNull EventMouse event) {
        if (event.getAction() == 0 && event.getButton() == 1 && isHovering() && mc.currentScreen instanceof HudEditorGui)
            HudEditorGui.getHudGui().hudClicked(this);

        if (event.getAction() == 0) {

            if (mouseButton && mouseState)
                if (HudEditor.sticky.getValue())
                    for (Module m : Managers.MODULE.getEnabledModules()) {
                        if (m instanceof HudElement hudElement && hudElement != this && !(hudElement.getHitX() == 0 && hudElement.getHitY() == 0)) {

                            float hitDifX = getPosX() - getHitX();
                            float hitDifY = getPosY() - getHitY();

                            if (getPosX() > mc.getWindow().getScaledWidth() / 2f) {
                                if (isNear(hudElement.getHitX() + hudElement.getWidth(), getHitX() + getWidth()))
                                    pos.getValue().setX((hudElement.getHitX() + hitDifX + hudElement.getWidth() - getWidth()) / mc.getWindow().getScaledWidth());
                            } else {
                                if (isNear(hudElement.getHitX(), getHitX()))
                                    pos.getValue().setX((hudElement.getHitX() + hitDifX) / mc.getWindow().getScaledWidth());
                            }
                            if (isNear(hudElement.getHitY(), getHitY()))
                                pos.getValue().setY((hudElement.getHitY() + hitDifY) / mc.getWindow().getScaledHeight());
                        }
                    }

            HudEditorGui.currentlyDragging = null;
            mouseButton = false;
        }
        if (event.getAction() == 1 && isHovering() && HudEditorGui.currentlyDragging == null) {
            HudEditorGui.currentlyDragging = this;
            mouseButton = true;
        }
    }

    public int normaliseX() {
        return (int) (mc.mouse.getX() / Render3DEngine.getScaleFactor());
    }

    public int normaliseY() {
        return (int) (mc.mouse.getY() / Render3DEngine.getScaleFactor());
    }

    public boolean isHovering() {
        return normaliseX() > Math.min(hitX, hitX + width) && normaliseX() < Math.max(hitX, hitX + width) && normaliseY() > Math.min(hitY, hitY + height) && normaliseY() < Math.max(hitY, hitY + height);
    }

    public void setWidth(float width) {
        this.width = width;
    }

    public void setHeight(float height) {
        this.height = height;
    }

    public void setHitX(float hitX) {
        this.hitX = hitX;
    }

    public void setHitY(float hitY) {
        this.hitY = hitY;
    }

    public void setBounds(float x, float y, float w, float h) {
        boundsSet = true;
        if (editing() && (Math.abs(w) < 2 || Math.abs(h) < 2)) {
            applyPlaceholder(x, y);
            return;
        }
        placeholder = false;
        setHitX(x);
        setHitY(y);
        setWidth(w);
        setHeight(h);
    }

    public float getPosX() {
        return x;
    }

    public float getHitX() {
        return hitX;
    }

    public float getHitY() {
        return hitY;
    }

    public float getPosY() {
        return y;
    }

    public float getX() {
        return pos.getValue().x;
    }

    public float getY() {
        return pos.getValue().y;
    }

    public float getHeight() {
        return height;
    }

    public float getWidth() {
        return width;
    }

    private boolean isNear(float n1, float n2) {
        return Math.abs(n1 - n2) < 10;
    }
}
