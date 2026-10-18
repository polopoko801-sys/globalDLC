package thunder.hack.features.hud.impl;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ChatScreen;
import thunder.hack.features.hud.HudElement;
import thunder.hack.gui.hud.HudEditorGui;
import thunder.hack.gui.notification.Notification;

/**
 * Draggable anchor for the notification stack. While it is disabled the toasts keep their old fixed spot
 * (bottom-right / under the crosshair); enable it and the stack starts at this element and grows downwards.
 */
public class NotifyHud extends HudElement {
    private static final float W = 150f, H = 36f;

    public NotifyHud() {
        super("NotifyHud", (int) W, (int) H, 0.72f, 0.84f);
        setEnabled(true); // on by default so the toasts are movable right away
    }

    @Override
    public void onRender2D(DrawContext context) {
        super.onRender2D(context);

        float x = getPosX() > mc.getWindow().getScaledWidth() / 2f ? getPosX() - W : getPosX();
        setBounds(x, getPosY(), W, H);

        // the real toasts are drawn by NotificationManager; show a mock one while positioning
        if (mc.currentScreen instanceof HudEditorGui || mc.currentScreen instanceof ChatScreen)
            Notification.drawPreview(context, x, getPosY(), W, H);
    }
}
