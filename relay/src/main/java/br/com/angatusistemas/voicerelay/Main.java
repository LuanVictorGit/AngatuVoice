package br.com.angatusistemas.voicerelay;

import br.com.angatusistemas.lib.AngatuLib;
import br.com.angatusistemas.lib.javalin.JavalinAPI;
import br.com.angatusistemas.lib.javalin.classes.RequestLogMode;
import br.com.angatusistemas.voicerelay.services.AdminService;
import br.com.angatusistemas.voicerelay.services.AttachmentStore;
import br.com.angatusistemas.voicerelay.services.IpBlocklist;
import br.com.angatusistemas.voicerelay.services.MessageStore;

/**
 * Starts the server every Angatu Voice connects to: who is online, the chat and its files, call signalling and shared screens.
 *
 * @author Angatu Sistemas
 */
public final class Main {

    private static final int DEFAULT_PORT = 8080;
    // Uploads are streamed to disk and stopped at 25 MB by the route; this only keeps Javalin's own ceiling above that.
    private static final long MAX_REQUEST_BYTES = AttachmentStore.MAX_FILE_BYTES + 1024 * 1024;

    private Main() {
    }

    public static void main(String[] args) {
        String port = System.getenv("PORT");
        start(port == null || port.isBlank() ? DEFAULT_PORT : Integer.parseInt(port), 1);
        IpBlocklist.start();
        AttachmentStore.startChecks();
    }

    public static void start(int port, int proxyHops) {
        JavalinAPI.setTrustedProxyHops(proxyHops);
        JavalinAPI.addIgnoredPath("/health");
        JavalinAPI.setRequestLogMode(RequestLogMode.ERRORS);
        JavalinAPI.setMaxRequestSize(MAX_REQUEST_BYTES);
        AttachmentStore.start();
        AdminService.start();
        MessageStore.start();
        new AngatuLib("voice.angatusistemas.com.br", port, false);
    }
}
