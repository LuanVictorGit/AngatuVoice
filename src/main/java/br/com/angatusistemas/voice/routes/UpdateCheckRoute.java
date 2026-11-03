package br.com.angatusistemas.voice.routes;

import br.com.angatusistemas.lib.connection.StatusCode;
import br.com.angatusistemas.lib.javalin.routes.Route;
import br.com.angatusistemas.lib.javalin.routes.RouteType;
import br.com.angatusistemas.voice.updater.UpdateService;
import io.javalin.http.Context;

/**
 * POST /api/update/check: asks the updater to look for a new release now.
 *
 * @author Angatu Sistemas
 */
public final class UpdateCheckRoute extends Route {

    public UpdateCheckRoute() {
        super("/api/update/check", RouteType.POST, UpdateCheckRoute::handle);
    }

    private static void handle(Context ctx) {
        Guard.requireSession(ctx);
        UpdateService.check();
        ctx.status(StatusCode.ACCEPTED.code());
    }
}
