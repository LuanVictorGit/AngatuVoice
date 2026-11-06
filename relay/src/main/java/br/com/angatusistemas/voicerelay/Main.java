package br.com.angatusistemas.voicerelay;

import br.com.angatusistemas.lib.AngatuLib;
import br.com.angatusistemas.lib.javalin.JavalinAPI;
import br.com.angatusistemas.lib.javalin.classes.RequestLogMode;
import br.com.angatusistemas.voicerelay.services.IpBlocklist;
import br.com.angatusistemas.voicerelay.services.MessageStore;

/**
 * Starts the server every Angatu Voice connects to: who is online, messages, call signalling and shared screens.
 *
 * @author Angatu Sistemas
 */
public final class Main {

    private static final int DEFAULT_PORT = 8080;

    private Main() {
    }

    public static void main(String[] args) {
        String port = System.getenv("PORT");
        start(port == null || port.isBlank() ? DEFAULT_PORT : Integer.parseInt(port), 1);
        IpBlocklist.start();
        MessageStore.startPurge();
    }

    public static void start(int port, int proxyHops) {
        JavalinAPI.setTrustedProxyHops(proxyHops);
        JavalinAPI.addIgnoredPath("/health");
        JavalinAPI.setRequestLogMode(RequestLogMode.ERRORS);
        new AngatuLib("voice.angatusistemas.com.br", port, false);
        MessageStore.start();
    }
}
