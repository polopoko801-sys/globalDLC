package thunder.hack.core.manager.client;

import com.google.common.collect.Lists;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.util.Formatting;
import thunder.hack.core.manager.IManager;
import thunder.hack.features.cmd.Command;
import thunder.hack.gui.notification.Notification;
import thunder.hack.features.modules.client.Notifications;
import org.apache.commons.lang3.SystemUtils;

import java.awt.*;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static thunder.hack.ThunderHack.LOGGER;

public class NotificationManager implements IManager {
    private final List<Notification> notifications = new ArrayList<>();
    private TrayIcon trayIcon;

    public void publicity(String title, String content, int second, Notification.Type type) {
        if (ModuleManager.notifications.mode.getValue() == Notifications.Mode.Text)
            Command.sendMessage(Formatting.GRAY + "[" + Formatting.DARK_PURPLE + title + Formatting.GRAY + "] " + type.getColor() + content);

        if (!mc.isWindowFocused())
            nativeNotification(content, title);

        notifications.add(new Notification(title, content, type, second * 1000));
    }

    public void onRender2D(DrawContext context) {
        if (!ModuleManager.notifications.isEnabled()) return;

        if (notifications.size() > 8)
            notifications.removeFirst();

        notifications.removeIf(Notification::shouldDelete);

        // BKClient's toasts: centred at the bottom (90 px up), each one 30 px above the previous, growing upwards.
        // With NotifyHud enabled the stack sits where the user dragged it instead.
        float centreX = mc.getWindow().getScaledWidth() / 2f;
        float baseY = mc.getWindow().getScaledHeight() - 90f;
        if (ModuleManager.notifyHud.isEnabled()) {
            centreX = ModuleManager.notifyHud.getPosX() + 80f;
            baseY = ModuleManager.notifyHud.getPosY();
        }
        // drawn with no clip window open: a HUD element that forgot to close one would cut the toasts away
        thunder.hack.utility.render.Render2DEngine.restoreClip(0);
        float off = 0f;
        for (Notification n : Lists.newArrayList(notifications)) {
            n.draw(context, centreX, baseY, off);
            if (n.holdsPlace()) off += 30f;
        }
    }

    public void onUpdate() {
        if (!ModuleManager.notifications.isEnabled()) return;
        notifications.forEach(Notification::onUpdate);
    }

    public static boolean isDefault() {
        return ModuleManager.notifications.mode.getValue() == Notifications.Mode.Default;
    }

    private void nativeNotification(String message, String title) {
        if (SystemUtils.IS_OS_WINDOWS) {
            windows(message, title);
        } else if (SystemUtils.IS_OS_LINUX) {
            linux(message);
        } else if (SystemUtils.IS_OS_MAC) {
            mac(message);
        } else {
            LOGGER.error("Unsupported OS: {}", SystemUtils.OS_NAME);
        }
    }

    private void windows(final String message, final String title) {
        if (SystemTray.isSupported()) {
            try {
                if (trayIcon == null) {
                    final SystemTray tray = SystemTray.getSystemTray();
                    final Image image = Toolkit.getDefaultToolkit().createImage("resources/icon.png");

                    trayIcon = new TrayIcon(image, "gombal");
                    trayIcon.setImageAutoSize(true);
                    trayIcon.setToolTip("gombal");
                    tray.add(trayIcon);
                }

                trayIcon.displayMessage(title, message, TrayIcon.MessageType.INFO);
            } catch (Exception e) {
                LOGGER.error(e.getMessage());
            }
        } else {
            LOGGER.error("SystemTray is not supported");
        }
    }

    private void mac(final String message) {
        final ProcessBuilder processBuilder = new ProcessBuilder();
        processBuilder.command("osascript", "-e", "display notification \"" + message + "\" with title \"gombal\"");
        try {
            processBuilder.start();
        } catch (IOException e) {
            LOGGER.error(e.getMessage());
        }
    }

    private void linux(final String message) {
        final ProcessBuilder processBuilder = new ProcessBuilder();
        processBuilder.command("notify-send", "-a", "gombal", message);

        try {
            processBuilder.start();
        } catch (IOException e) {
            LOGGER.error(e.getMessage());
        }
    }
}