package br.com.angatusistemas.voice.routes;

import br.com.angatusistemas.lib.connection.StatusCode;
import br.com.angatusistemas.lib.javalin.routes.Route;
import br.com.angatusistemas.lib.javalin.routes.RouteType;
import br.com.angatusistemas.voice.updater.UpdateService;
import io.javalin.http.Context;

/**
 * POST /api/update/cancel: stops a running download; the partial file is kept to resume.
 *
 * @author Angatu Sistemas
 */
public final class UpdateCancelRoute extends Route {

    public UpdateCancelRoute() {
        super("/api/update/cancel", RouteType.POST, UpdateCancelRoute::handle);
    }

    private static void handle(Context ctx) {
        Guard.requireSession(ctx);
        UpdateService.cancel();
        ctx.status(StatusCode.NO_CONTENT.code());
    }
}
