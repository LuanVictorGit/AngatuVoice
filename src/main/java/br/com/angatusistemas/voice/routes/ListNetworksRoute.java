package br.com.angatusistemas.voice.routes;

import br.com.angatusistemas.lib.gson.GsonAPI;
import br.com.angatusistemas.lib.javalin.routes.Route;
import br.com.angatusistemas.lib.javalin.routes.RouteType;
import br.com.angatusistemas.voice.services.radmin.NetworkManager;
import io.javalin.http.Context;

/**
 * GET /api/networks: lists all managed Radmin networks with current statuses and peer metrics.
 *
 * @author Angatu Sistemas
 */
public final class ListNetworksRoute extends Route {

    public ListNetworksRoute() {
        super("/api/networks", RouteType.GET, ListNetworksRoute::handle);
    }

    private static void handle(Context ctx) {
        Guard.requireSession(ctx);
        try {
            ctx.json(GsonAPI.get().toJson(NetworkManager.list()));
        } catch (Exception e) {
            ctx.json("[]");
        }
    }
}
