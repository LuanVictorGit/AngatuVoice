package br.com.angatusistemas.voice.desktop;

import br.com.angatusistemas.lib.console.Console;
import br.com.angatusistemas.voice.services.Notifications;
import br.com.angatusistemas.voice.services.PeerNetwork;
import br.com.angatusistemas.voice.services.SettingsService;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef;
import com.sun.jna.ptr.IntByReference;
import java.awt.Desktop;
import java.io.IOException;
import java.net.URI;

/**
 * The desktop shell facade the rest of the app talks to: window, tray, shortcuts, links and an orderly exit.
 *
 * @author Angatu Sistemas
 */
public final class AppShell {

    private static final long FOCUS_POLL_MS = 400;
    private static volatile BrowserWindow window;

    private AppShell() {
    }

    public static boolean claimInstance() throws IOException {
        return SingleInstance.acquire(AppShell::show);
    }

    public static void open(int port, boolean startMinimized) throws Exception {
        Tray.install();
        Notifications.bind(Tray::notify, AppShell::focused);
        Hotkeys.start();
        BrowserWindow created = new BrowserWindow(port);
        window = created;
        created.open(startMinimized || SettingsService.flag("startMinimized"));
        Thread watcher = new Thread(AppShell::watchFocus, "av-focus");
        watcher.setDaemon(true);
        watcher.start();
    }

    public static void show() {
        BrowserWindow current = window;
        if (current != null) {
            current.show();
        }
    }

    static boolean focused() {
        // Chromium owns the native focus, so AWT cannot tell; ask Windows whether our process owns the foreground.
        WinDef.HWND foreground = User32.INSTANCE.GetForegroundWindow();
        if (foreground == null) {
            return false;
        }
        IntByReference pid = new IntByReference();
        User32.INSTANCE.GetWindowThreadProcessId(foreground, pid);
        return pid.getValue() == ProcessHandle.current().pid();
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

    private static void watchFocus() {
        boolean last = focused();
        while (!Thread.currentThread().isInterrupted()) {
            boolean now = focused();
            if (now != last) {
                last = now;
                Overlay.refresh();
            }
            try {
                Thread.sleep(FOCUS_POLL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
