package br.com.angatusistemas.voicerelay.routes;

import br.com.angatusistemas.lib.javalin.routes.Route;
import br.com.angatusistemas.lib.javalin.routes.RouteType;

/**
 * GET /health: what Coolify polls to know the relay is up.
 *
 * @author Angatu Sistemas
 */
public final class HealthRoute extends Route {

    public HealthRoute() {
        super("/health", RouteType.GET, ctx -> ctx.json("{\"status\":\"ok\"}"));
    }
}
