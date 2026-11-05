package br.com.angatusistemas.voice.routes;

import br.com.angatusistemas.lib.gson.GsonAPI;
import br.com.angatusistemas.lib.javalin.routes.Route;
import br.com.angatusistemas.lib.javalin.routes.RouteType;
import br.com.angatusistemas.voice.services.radmin.RadminAutomation;
import io.javalin.http.Context;
import java.util.Map;

/**
 * POST /api/networks/open-radmin: launches or brings the Radmin VPN desktop application to the front.
 *
 * @author Angatu Sistemas
 */
public final class OpenRadminRoute extends Route {

    public OpenRadminRoute() {
        super("/api/networks/open-radmin", RouteType.POST, OpenRadminRoute::handle);
    }

    private static void handle(Context ctx) {
        Guard.requireSession(ctx);
        RadminAutomation.show();
        ctx.json(GsonAPI.get().toJson(Map.of("ok", true)));
    }
}
