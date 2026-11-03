package br.com.angatusistemas.voice.routes;

import br.com.angatusistemas.lib.javalin.routes.Route;
import br.com.angatusistemas.lib.javalin.routes.RouteType;

/**
 * GET /health: tells a probe that the local server is answering.
 *
 * @author Angatu Sistemas
 */
public final class HealthRoute extends Route {

    public HealthRoute() {
        super("/health", RouteType.GET, ctx -> ctx.json("{\"status\":\"ok\"}"));
    }
}
