package br.com.angatusistemas.voice.routes;

import br.com.angatusistemas.lib.gson.GsonAPI;
import br.com.angatusistemas.lib.javalin.routes.Route;
import br.com.angatusistemas.lib.javalin.routes.RouteType;
import br.com.angatusistemas.voice.updater.UpdateService;
import io.javalin.http.Context;
import java.util.Map;

/**
 * GET /api/update: the updater state, plus its history.
 *
 * @author Angatu Sistemas
 */
public final class UpdateStatusRoute extends Route {

    public UpdateStatusRoute() {
        super("/api/update", RouteType.GET, UpdateStatusRoute::handle);
    }

    private static void handle(Context ctx) {
        Guard.requireSession(ctx);
        ctx.json(GsonAPI.get().toJson(Map.of("status", UpdateService.status(), "history", UpdateService.history())));
    }
}
