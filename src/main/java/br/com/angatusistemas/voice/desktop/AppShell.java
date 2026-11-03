package br.com.angatusistemas.voice.desktop;

import br.com.angatusistemas.lib.console.Console;
import br.com.angatusistemas.voice.services.Notifications;
import br.com.angatusistemas.voice.services.PeerNetwork;
import br.com.angatusistemas.voice.services.SettingsService;
import java.awt.Desktop;
import java.io.IOException;
import java.net.URI;

/**
 * The desktop shell facade the rest of the app talks to: window, tray, shortcuts, links and an orderly exit.
 *
 * @author Angatu Sistemas
 */
public final class AppShell {

    private static volatile BrowserWindow window;

    private AppShell() {
    }

    public static boolean claimInstance() throws IOException {
        return SingleInstance.acquire(AppShell::show);
    }

    public static void open(int port, boolean startMinimized) throws Exception {
        Tray.install();
        Notifications.bind(Tray::notify);
        Hotkeys.start();
        BrowserWindow created = new BrowserWindow(port);
        window = created;
        created.open(startMinimized || SettingsService.flag("startMinimized"));
    }

    public static void show() {
        BrowserWindow current = window;
        if (current != null) {
            current.show();
        }
    }

    public static void browse(URI uri) {
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(uri);
            }
        } catch (IOException e) {
            Console.warn("Não foi possível abrir o link: %s", e.getMessage());
        }
    }

    public static void quit() {
        Thread.ofVirtual().start(() -> {
            Hotkeys.stop();
            Tray.remove();
            PeerNetwork.stop();
            BrowserWindow current = window;
            if (current != null) {
                current.dispose();
            }
            System.exit(0);
        });
    }
}
