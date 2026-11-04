package br.com.angatusistemas.voice.desktop;

import br.com.angatusistemas.voice.services.LiveHub;
import java.awt.AWTException;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import javax.imageio.ImageIO;

/**
 * The notification-area icon: one click restores the window, the menu toggles the microphone and quits for real, and
 * it carries the native notifications.
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
            // AWT only fires the action on a double click, while every other chat app restores on a single one.
            created.addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    if (e.getButton() == MouseEvent.BUTTON1) {
                        AppShell.show();
                    }
                }
            });
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
