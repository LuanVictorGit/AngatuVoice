package br.com.angatusistemas.voice.routes;

import br.com.angatusistemas.voice.services.SessionService;
import io.javalin.http.Context;
import io.javalin.http.ForbiddenResponse;
import io.javalin.http.UnauthorizedResponse;
import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * The gate every interface route passes: loopback peer, our own Host header, the session cookie and a same-origin write.
 *
 * @author Angatu Sistemas
 */
public final class Guard {

    private Guard() {
    }

    public static void requireSession(Context ctx) {
        if (!isLoopback(ctx.req().getRemoteAddr())
                || !SessionService.valid(ctx.cookie(SessionService.COOKIE), ctx.header("Host"))) {
            throw new UnauthorizedResponse("Sessão inválida.");
        }
        String origin = ctx.header("Origin");
        if (!"GET".equals(ctx.req().getMethod()) && origin != null && !origin.equals("http://" + ctx.header("Host"))) {
            throw new ForbiddenResponse("Origem não permitida.");
        }
    }

    private static boolean isLoopback(String address) {
        try {
            return InetAddress.getByName(address).isLoopbackAddress();
        } catch (UnknownHostException e) {
            return false;
        }
    }
}