package br.com.angatusistemas.voice.services;

import br.com.angatusistemas.voice.utils.Crypto;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The one session of the local interface: a single-use boot token is traded for the cookie the WebView then carries.
 *
 * @author Angatu Sistemas
 */
public final class SessionService {

    public static final String COOKIE = "av_session";
    private static final long BOOT_VALIDITY_MS = 60_000;
    private static final String SESSION = Crypto.b64url(Crypto.random(32));
    private static final AtomicReference<Boot> BOOT = new AtomicReference<>();
    private static volatile Set<String> hosts = Set.of();

    private SessionService() {
    }

    public static void init(int port) {
        hosts = Set.of("127.0.0.1:" + port, "localhost:" + port);
    }

    public static String newBootToken() {
        String token = Crypto.b64url(Crypto.random(24));
        BOOT.set(new Boot(token, System.currentTimeMillis() + BOOT_VALIDITY_MS));
        return token;
    }

    public static String exchange(String token) {
        Boot boot = BOOT.getAndSet(null);
        boolean ok = boot != null && token != null && System.currentTimeMillis() < boot.expiresAt
                && Crypto.equal(boot.token.getBytes(StandardCharsets.UTF_8), token.getBytes(StandardCharsets.UTF_8));
        return ok ? SESSION : null;
    }

    public static boolean valid(String cookie, String host) {
        return host != null && hosts.contains(host.toLowerCase()) && cookie != null
                && Crypto.equal(SESSION.getBytes(StandardCharsets.UTF_8), cookie.getBytes(StandardCharsets.UTF_8));
    }

    public static boolean validHost(String host) {
        return host != null && hosts.contains(host.toLowerCase());
    }

    private record Boot(String token, long expiresAt) {
    }
}
