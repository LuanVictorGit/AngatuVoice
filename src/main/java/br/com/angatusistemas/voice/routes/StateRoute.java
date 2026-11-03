package br.com.angatusistemas.voice.routes;

import br.com.angatusistemas.lib.gson.GsonAPI;
import br.com.angatusistemas.lib.javalin.routes.Route;
import br.com.angatusistemas.lib.javalin.routes.RouteType;
import br.com.angatusistemas.voice.services.StateService;
import io.javalin.http.Context;

/**
 * GET /api/state: the read model the interface renders from.
 *
 * @author Angatu Sistemas
 */
public final class StateRoute extends Route {

    public StateRoute() {
        super("/api/state", RouteType.GET, StateRoute::handle);
    }

    private static void handle(Context ctx) {
        Guard.requireSession(ctx);
        ctx.json(GsonAPI.get().toJson(StateService.snapshot()));
    }
}
