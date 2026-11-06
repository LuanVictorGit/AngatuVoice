package br.com.angatusistemas.voice;

import br.com.angatusistemas.lib.AngatuLib;
import br.com.angatusistemas.lib.console.Console;
import br.com.angatusistemas.lib.javalin.JavalinAPI;
import br.com.angatusistemas.lib.javalin.classes.RequestLogMode;
import br.com.angatusistemas.voice.desktop.AppShell;
import br.com.angatusistemas.voice.desktop.Overlay;
import br.com.angatusistemas.voice.services.AutostartService;
import br.com.angatusistemas.voice.services.Migrations;
import br.com.angatusistemas.voice.services.PeerNetwork;
import br.com.angatusistemas.voice.services.SessionService;
import br.com.angatusistemas.voice.services.SettingsService;
import br.com.angatusistemas.voice.updater.UpdateService;
import br.com.angatusistemas.voice.utils.AppDirs;
import br.com.angatusistemas.voice.utils.Version;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import javax.swing.JOptionPane;

/**
 * Starts Angatu Voice: data and logs, the single instance, the local loopback server, the peer network, the updater
 * and finally the window that renders the interface.
 *
 * @author Angatu Sistemas
 */
public final class Main {

    private static final long LOG_LIMIT_BYTES = 5L * 1024 * 1024;
    private static final String CSP = "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; "
            + "img-src 'self' data: blob:; media-src 'self' blob:; connect-src 'self' ws://127.0.0.1:* wss://voice.angatusistemas.com.br; "
            + "object-src 'none'; base-uri 'none'; frame-ancestors 'none'";

    private Main() {
    }

    public static void main(String[] args) {
        try {
            redirectLogs();
            System.setProperty("angatu.db", AppDirs.data().resolve("database.db").toString());
            boolean postUpdate = Arrays.asList(args).contains("--post-update");
            if (!AppShell.claimInstance()) {
                return;
            }
            Migrations.run();
            Migrations.indexes();
            int port = freePort();
            configure(port);
            new AngatuLib("localhost", port, false);
            PeerNetwork.start();
            UpdateService.start(postUpdate);
            syncAutostart();
            Console.log("Angatu Voice %s ouvindo em 127.0.0.1:%d", Version.current(), port);
            AppShell.open(port);
        } catch (Migrations.IncompatibleDataException e) {
            fatal("Os dados deste computador foram criados por uma versão mais nova do Angatu Voice. "
                    + "Instale a versão mais recente para continuar.");
        } catch (Exception | LinkageError e) {
            Console.error("Falha ao iniciar o Angatu Voice", e);
            fatal("Não foi possível iniciar o Angatu Voice. Os detalhes foram gravados em " + AppDirs.logs() + ".");
        }
    }

    public static void configure(int port) {
        SessionService.init(port);
        JavalinAPI.setTrustedProxyHops(0);
        JavalinAPI.addIgnoredPath("/health");
        JavalinAPI.setRequestLogMode(RequestLogMode.ERRORS);
        JavalinAPI.setSecurityHeader("Content-Security-Policy", CSP);
        JavalinAPI.setSecurityHeader("Cache-Control", "no-store, no-cache, must-revalidate");
        JavalinAPI.setSecurityHeader("Pragma", "no-cache");
        JavalinAPI.setSecurityHeader("Expires", "0");
    }

    private static void syncAutostart() {
        if (AutostartService.available()) {
            // The installer asks about this first, so until the person changes it here the registry is the answer.
            if (SettingsService.isStored("autostart")) {
                AutostartService.apply(SettingsService.flag("autostart"));
            } else {
                SettingsService.update(Map.of("autostart", String.valueOf(AutostartService.enabled())));
            }
        }
        SettingsService.listen(changes -> {
            if (changes.keySet().stream().anyMatch(k -> k.startsWith("overlay."))) {
                Overlay.refresh();
            }
            if (changes.containsKey("autostart")) {
                AutostartService.apply(Boolean.parseBoolean(changes.get("autostart")));
            }
        });
    }

    private static int freePort() throws IOException {
        try (ServerSocket probe = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return probe.getLocalPort();
        }
    }

    private static void redirectLogs() throws IOException {
        Path log = AppDirs.ensure(AppDirs.logs()).resolve("app.log");
        boolean fresh = Files.exists(log) && Files.size(log) > LOG_LIMIT_BYTES;
        if (fresh) {
            Files.move(log, log.resolveSibling("app.old.log"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        PrintStream out = new PrintStream(new FileOutputStream(log.toFile(), true), true, "UTF-8");
        System.setOut(out);
        System.setErr(out);
    }

    private static void fatal(String message) {
        JOptionPane.showMessageDialog(null, message, "Angatu Voice", JOptionPane.ERROR_MESSAGE);
        System.exit(1);
    }
}
