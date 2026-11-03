package br.com.angatusistemas.voice.routes;

import br.com.angatusistemas.lib.javalin.JavalinAPI;
import br.com.angatusistemas.lib.javalin.routes.Route;
import br.com.angatusistemas.lib.javalin.routes.RouteType;
import br.com.angatusistemas.voice.services.SessionService;
import io.javalin.http.Context;
import io.javalin.http.ForbiddenResponse;

/**
 * GET /boot: trades the single-use boot token for the session cookie and sends the WebView to the app.
 *
 * @author Angatu Sistemas
 */
public final class BootRoute extends Route {

    public BootRoute() {
        super("/boot", RouteType.GET, BootRoute::handle);
        // Routes are built before the server starts, and the library offers no host option: the interface must never
        // listen beyond the loopback address, so the Jetty host is fixed here, before the first connection.
        JavalinAPI.get().unsafe.jetty.host = "127.0.0.1";
    }

    private static void handle(Context ctx) {
        if (!SessionService.validHost(ctx.header("Host"))) {
            throw new ForbiddenResponse("Host não permitido.");
        }
        String session = SessionService.exchange(ctx.queryParam("t"));
        if (session == null) {
            throw new ForbiddenResponse("Acesso negado.");
        }
        ctx.header("Set-Cookie", SessionService.COOKIE + "=" + session + "; Path=/; HttpOnly; SameSite=Strict");
        ctx.redirect("/app");
    }
}