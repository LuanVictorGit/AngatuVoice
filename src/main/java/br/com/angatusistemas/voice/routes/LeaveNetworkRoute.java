package br.com.angatusistemas.voice.routes;

import br.com.angatusistemas.lib.connection.StatusCode;
import br.com.angatusistemas.lib.gson.GsonAPI;
import br.com.angatusistemas.lib.javalin.routes.Route;
import br.com.angatusistemas.lib.javalin.routes.RouteType;
import br.com.angatusistemas.voice.services.radmin.NetworkDefaults;
import br.com.angatusistemas.voice.services.radmin.NetworkManager;
import br.com.angatusistemas.voice.services.radmin.RadminAutomation;
import io.javalin.http.BadRequestResponse;
import io.javalin.http.Context;
import io.javalin.http.ForbiddenResponse;
import java.util.Map;

/**
 * POST /api/networks/leave: leaves a custom Radmin VPN network. Blocked for the default network.
 *
 * @author Angatu Sistemas
 */
public final class LeaveNetworkRoute extends Route {

    public LeaveNetworkRoute() {
        super("/api/networks/leave", RouteType.POST, LeaveNetworkRoute::handle);
    }

    private static void handle(Context ctx) {
        Guard.requireSession(ctx);
        Body body = JsonBody.read(ctx, Body.class);
        if (body.name() == null || body.name().isBlank()) {
            throw new BadRequestResponse("Nome da rede obrigatório.");
        }
        if (NetworkDefaults.isProtected(body.name())) {
            throw new ForbiddenResponse("A rede padrão AngatuVoice é obrigatória e protegida contra saída.");
        }
        try {
            RadminAutomation.OperationResult res = NetworkManager.leave(body.name());
            ctx.json(GsonAPI.get().toJson(Map.of("ok", res.ok(), "message", res.message())));
        } catch (UnsupportedOperationException e) {
            ctx.json(GsonAPI.get().toJson(Map.of("ok", false, "message", e.getMessage())));
        }
    }

    private record Body(String name) {
    }
}
