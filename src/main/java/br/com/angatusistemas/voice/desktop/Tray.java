package br.com.angatusistemas.voice.desktop;

import br.com.angatusistemas.voice.services.LiveHub;
import java.awt.AWTException;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import javax.imageio.ImageIO;

/**
 * The notification-area icon: open the window, toggle the microphone, quit, and native notifications.
 *
 * @author Angatu Sistemas
 */
final class Tray {

    private static volatile TrayIcon icon;

    private Tray() {
    }

    static boolean available() {
        return icon != null;
    }

    static void install() {
        if (!SystemTray.isSupported()) {
            return;
        }
        try (InputStream in = Tray.class.getResourceAsStream("/public/assets/icon-64.png")) {
            PopupMenu menu = new PopupMenu();
            menu.add(item("Abrir Angatu Voice", AppShell::show));
            menu.add(item("Silenciar ou ativar microfone", () -> LiveHub.emit("hotkey", Map.of("action", "mic", "state", "down"))));
            menu.addSeparator();
            menu.add(item("Sair", AppShell::quit));
            TrayIcon created = new TrayIcon(ImageIO.read(in), "Angatu Voice", menu);
            created.setImageAutoSize(true);
            created.addActionListener(e -> AppShell.show());
            SystemTray.getSystemTray().add(created);
            icon = created;
        } catch (IOException | AWTException | NullPointerException e) {
            icon = null;
        }
    }

    static void remove() {
        TrayIcon current = icon;
        if (current != null) {
            SystemTray.getSystemTray().remove(current);
            icon = null;
        }
    }

    static void notify(String title, String body) {
        TrayIcon current = icon;
        if (current != null) {
            current.displayMessage(title, body, TrayIcon.MessageType.NONE);
        }
    }

    private static MenuItem item(String label, Runnable action) {
        MenuItem item = new MenuItem(label);
        item.addActionListener(e -> action.run());
        return item;
    }
}
