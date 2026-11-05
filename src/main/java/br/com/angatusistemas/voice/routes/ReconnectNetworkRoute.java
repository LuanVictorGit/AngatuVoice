package br.com.angatusistemas.voice.routes;

import br.com.angatusistemas.lib.gson.GsonAPI;
import br.com.angatusistemas.lib.javalin.routes.Route;
import br.com.angatusistemas.lib.javalin.routes.RouteType;
import br.com.angatusistemas.voice.services.radmin.DefaultNetworkService;
import io.javalin.http.Context;
import java.util.Map;

/**
 * POST /api/networks/reconnect: triggers an immediate reconnection attempt to the default network.
 *
 * @author Angatu Sistemas
 */
public final class ReconnectNetworkRoute extends Route {

    public ReconnectNetworkRoute() {
        super("/api/networks/reconnect", RouteType.POST, ReconnectNetworkRoute::handle);
    }

    private static void handle(Context ctx) {
        Guard.requireSession(ctx);
        DefaultNetworkService.reconnect();
        ctx.json(GsonAPI.get().toJson(Map.of("ok", true, "message", "Tentativa de reconexão disparada.")));
    }
}
