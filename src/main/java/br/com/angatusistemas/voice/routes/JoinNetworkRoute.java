package br.com.angatusistemas.voice.routes;

import br.com.angatusistemas.lib.connection.StatusCode;
import br.com.angatusistemas.lib.gson.GsonAPI;
import br.com.angatusistemas.lib.javalin.routes.Route;
import br.com.angatusistemas.lib.javalin.routes.RouteType;
import br.com.angatusistemas.voice.services.radmin.NetworkManager;
import br.com.angatusistemas.voice.services.radmin.RadminAutomation;
import io.javalin.http.BadRequestResponse;
import io.javalin.http.Context;
import java.util.Map;

/**
 * POST /api/networks/join: joins an existing Radmin VPN network.
 *
 * @author Angatu Sistemas
 */
public final class JoinNetworkRoute extends Route {

    public JoinNetworkRoute() {
        super("/api/networks/join", RouteType.POST, JoinNetworkRoute::handle);
    }

    private static void handle(Context ctx) {
        Guard.requireSession(ctx);
        Body body = JsonBody.read(ctx, Body.class);
        if (body.name() == null || body.name().isBlank()) {
            throw new BadRequestResponse("Nome da rede obrigatório.");
        }
        RadminAutomation.OperationResult res = NetworkManager.join(body.name(), body.password());
        ctx.json(GsonAPI.get().toJson(Map.of("ok", res.ok(), "message", res.message())));
    }

    private record Body(String name, String password) {
    }
}
