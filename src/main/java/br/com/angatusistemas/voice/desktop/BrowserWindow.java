package br.com.angatusistemas.voice.desktop;

import br.com.angatusistemas.lib.console.Console;
import br.com.angatusistemas.voice.services.SessionService;
import br.com.angatusistemas.voice.services.SettingsService;
import br.com.angatusistemas.voice.updater.UpdateService;
import br.com.angatusistemas.voice.utils.AppDirs;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinUser;
import java.awt.Dimension;
import java.awt.Image;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.io.InputStream;
import javax.imageio.ImageIO;
import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import me.friwi.jcefmaven.CefAppBuilder;
import me.friwi.jcefmaven.CefInitializationException;
import me.friwi.jcefmaven.UnsupportedPlatformException;
import org.cef.CefApp;
import org.cef.CefClient;
import org.cef.browser.CefBrowser;
import org.cef.browser.CefFrame;
import org.cef.callback.CefContextMenuParams;
import org.cef.callback.CefMenuModel;
import org.cef.handler.CefContextMenuHandlerAdapter;
import org.cef.handler.CefLifeSpanHandlerAdapter;
import org.cef.handler.CefLoadHandler;
import org.cef.handler.CefLoadHandlerAdapter;
import org.cef.handler.CefRequestHandler;
import org.cef.handler.CefRequestHandlerAdapter;
import org.cef.network.CefRequest;

/**
 * The host window: a JFrame whose whole surface is one embedded Chromium showing the local interface.
 *
 * @author Angatu Sistemas
 */
final class BrowserWindow {

    private static final String JCEF_VERSION = "152.0.6";
    private static final int RELOAD_LIMIT = 3;
    private static final int PUMP_MS = 8;
    private static final int PM_REMOVE = 1;

    private final JFrame frame = new JFrame("Angatu Voice");
    private final String origin;
    private CefApp cefApp;
    private CefClient client;
    private CefBrowser browser;
    private final SplashPanel splash = new SplashPanel();
    private int reloads;

    BrowserWindow(int port) {
        this.origin = "http://127.0.0.1:" + port;
    }

    void open(boolean startMinimized) {
        frame.setIconImage(icon());
        frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        frame.setMinimumSize(new Dimension(900, 600));
        frame.setSize(1280, 800);
        frame.setLocationRelativeTo(null);
        frame.setExtendedState(JFrame.MAXIMIZED_BOTH);
        frame.getContentPane().setBackground(SplashPanel.BACKGROUND);
        frame.getContentPane().add(splash);
        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                if (SettingsService.flag("closeToTray") && Tray.available()) {
                    frame.setVisible(false);
                } else {
                    AppShell.quit();
                }
            }

            @Override
            public void windowIconified(WindowEvent e) {
                if (SettingsService.flag("minimizeToTray") && Tray.available()) {
                    frame.setVisible(false);
                    frame.setExtendedState(JFrame.MAXIMIZED_BOTH);
                }
            }
        });
        frame.addWindowFocusListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowGainedFocus(WindowEvent e) {
                focusBrowser();
            }
        });
        if (!startMinimized || !Tray.available()) {
            show();
        }
        Thread starter = new Thread(this::startBrowser, "av-chromium-start");
        starter.setDaemon(true);
        starter.start();
    }

    void show() {
        SwingUtilities.invokeLater(() -> {
            frame.setVisible(true);
            frame.setExtendedState(JFrame.MAXIMIZED_BOTH);
            frame.toFront();
            Foreground.raise(frame);
            focusBrowser();
        });
    }

    private void focusBrowser() {
        if (browser != null) {
            browser.getUIComponent().requestFocusInWindow();
            browser.setFocus(true);
        }
    }

    private void startBrowser() {
        try {
            CefAppBuilder builder = new CefAppBuilder();
            builder.setInstallDir(AppDirs.ensure(AppDirs.local().resolve("jcef-" + JCEF_VERSION)).toFile());
            builder.setProgressHandler((state, percent) -> splash.status(switch (state) {
                case LOCATING, EXTRACTING, INSTALL -> "Preparando o aplicativo pela primeira vez…";
                default -> "Iniciando a interface…";
            }));
            builder.getCefSettings().log_severity = org.cef.CefSettings.LogSeverity.LOGSEVERITY_ERROR;
            builder.getCefSettings().log_file = AppDirs.ensure(AppDirs.logs()).resolve("chromium.log").toString();
            builder.getCefSettings().background_color = builder.getCefSettings().new ColorType(255, 15, 14, 13);
            builder.addJcefArgs("--use-fake-ui-for-media-stream", "--enable-usermedia-screen-capturing",
                    "--auto-select-desktop-capture-source=Entire screen", "--disable-features=WebRtcHideLocalIpsWithMdns",
                    "--autoplay-policy=no-user-gesture-required", "--disable-extensions");
            String debugPort = System.getProperty("angatu.debug.port");
            if (debugPort != null && debugPort.matches("\\d{4,5}")) {
                builder.addJcefArgs("--remote-debugging-port=" + debugPort, "--remote-allow-origins=*");
            }
            cefApp = builder.build();
            SwingUtilities.invokeLater(this::startMessagePump);
            client = cefApp.createClient();
            installHandlers();
            browser = client.createBrowser(bootUrl(), false, false);
            SwingUtilities.invokeLater(this::attachBrowser);
        } catch (IOException | UnsupportedPlatformException | CefInitializationException e) {
            Console.error("Chromium could not start", e);
            splash.failed("Não foi possível iniciar a interface. Feche e abra o Angatu Voice de novo.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // Chromium's windows belong to the Swing event thread, which never reads Windows messages by itself: without this
    // loop the page renders but ignores every click and key.
    private void startMessagePump() {
        WinUser.MSG msg = new WinUser.MSG();
        new javax.swing.Timer(PUMP_MS, e -> {
            cefApp.doMessageLoopWork(0);
            User32 user32 = User32.INSTANCE;
            while (user32.PeekMessage(msg, null, 0, 0, PM_REMOVE)) {
                user32.TranslateMessage(msg);
                user32.DispatchMessage(msg);
            }
        }).start();
    }

    private void attachBrowser() {
        frame.getContentPane().remove(splash);
        frame.getContentPane().add(browser.getUIComponent());
        frame.revalidate();
        frame.repaint();
        // The native child window only paints after a real size change, so nudge the frame once it is attached.
        Dimension size = frame.getSize();
        if (frame.getExtendedState() != JFrame.MAXIMIZED_BOTH) {
            frame.setSize(size.width + 1, size.height);
            frame.setSize(size);
        } else {
            frame.setExtendedState(JFrame.NORMAL);
            frame.setExtendedState(JFrame.MAXIMIZED_BOTH);
        }
        if (frame.isVisible()) {
            Foreground.raise(frame);
        }
        focusBrowser();
    }

    void dispose() {
        SwingUtilities.invokeLater(() -> {
            frame.dispose();
            if (client != null) {
                client.dispose();
            }
            if (cefApp != null) {
                cefApp.dispose();
            }
        });
    }

    private String bootUrl() {
        return origin + "/boot?t=" + SessionService.newBootToken();
    }

    private void installHandlers() {
        client.addLifeSpanHandler(new CefLifeSpanHandlerAdapter() {
            @Override
            public boolean onBeforePopup(CefBrowser b, CefFrame f, String url, String name) {
                return true;
            }
        });
        client.addRequestHandler(new CefRequestHandlerAdapter() {
            @Override
            public boolean onBeforeBrowse(CefBrowser b, CefFrame f, CefRequest request, boolean gesture, boolean redirect) {
                return !request.getURL().startsWith(origin + "/");
            }

            @Override
            public void onRenderProcessTerminated(CefBrowser b, CefRequestHandler.TerminationStatus status, int code,
                                                  String message) {
                Console.warn("Chromium render process ended (%s); reloading the interface.", status);
                reload();
            }
        });
        client.addLoadHandler(new CefLoadHandlerAdapter() {
            @Override
            public void onLoadEnd(CefBrowser b, CefFrame f, int httpStatusCode) {
                if (f.isMain() && httpStatusCode == 200 && f.getURL().startsWith(origin + "/app")) {
                    reloads = 0;
                    UpdateService.confirmStartup();
                }
            }

            @Override
            public void onLoadError(CefBrowser b, CefFrame f, CefLoadHandler.ErrorCode code, String text, String url) {
                if (f.isMain() && code != CefLoadHandler.ErrorCode.ERR_ABORTED) {
                    Console.warn("Interface failed to load (%s %s).", code, text);
                    reload();
                }
            }
        });
        client.addContextMenuHandler(new CefContextMenuHandlerAdapter() {
            @Override
            public void onBeforeContextMenu(CefBrowser b, CefFrame f, CefContextMenuParams params, CefMenuModel model) {
                model.clear();
            }
        });
    }

    private void reload() {
        if (++reloads <= RELOAD_LIMIT) {
            browser.loadURL(bootUrl());
        }
    }

    private static Image icon() {
        try (InputStream in = BrowserWindow.class.getResourceAsStream("/public/assets/icon-256.png")) {
            return in == null ? null : ImageIO.read(in);
        } catch (IOException e) {
            return null;
        }
    }
}
