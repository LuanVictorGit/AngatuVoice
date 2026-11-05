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
 * POST /api/networks/create: creates a new Radmin VPN network.
 *
 * @author Angatu Sistemas
 */
public final class CreateNetworkRoute extends Route {

    public CreateNetworkRoute() {
        super("/api/networks/create", RouteType.POST, CreateNetworkRoute::handle);
    }

    private static void handle(Context ctx) {
        Guard.requireSession(ctx);
        Body body = JsonBody.read(ctx, Body.class);
        if (body.name() == null || body.name().isBlank()) {
            throw new BadRequestResponse("Nome da rede obrigatório.");
        }
        if (body.password() == null || body.password().length() < 6) {
            throw new BadRequestResponse("A senha deve ter no mínimo 6 caracteres.");
        }
        try {
            RadminAutomation.OperationResult res = NetworkManager.create(body.name(), body.password());
            ctx.json(GsonAPI.get().toJson(Map.of("ok", res.ok(), "message", res.message())));
        } catch (UnsupportedOperationException e) {
            ctx.json(GsonAPI.get().toJson(Map.of("ok", false, "message", e.getMessage())));
        }
    }

    private record Body(String name, String password) {
    }
}
