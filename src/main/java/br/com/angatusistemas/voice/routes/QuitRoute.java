package br.com.angatusistemas.voice.routes;

import br.com.angatusistemas.lib.connection.StatusCode;
import br.com.angatusistemas.lib.javalin.routes.Route;
import br.com.angatusistemas.lib.javalin.routes.RouteType;
import br.com.angatusistemas.voice.desktop.AppShell;
import io.javalin.http.Context;

/**
 * POST /api/app/quit: closes the application for real, past the minimise-to-tray behaviour.
 *
 * @author Angatu Sistemas
 */
public final class QuitRoute extends Route {

    public QuitRoute() {
        super("/api/app/quit", RouteType.POST, QuitRoute::handle);
    }

    private static void handle(Context ctx) {
        Guard.requireSession(ctx);
        ctx.status(StatusCode.ACCEPTED.code());
        AppShell.quit();
    }
}
