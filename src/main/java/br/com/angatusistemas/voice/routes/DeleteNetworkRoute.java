package br.com.angatusistemas.voice.routes;

import br.com.angatusistemas.lib.gson.GsonAPI;
import br.com.angatusistemas.lib.javalin.routes.Route;
import br.com.angatusistemas.lib.javalin.routes.RouteType;
import br.com.angatusistemas.voice.services.radmin.NetworkDefaults;
import br.com.angatusistemas.voice.services.radmin.NetworkManager;
import io.javalin.http.BadRequestResponse;
import io.javalin.http.Context;
import io.javalin.http.ForbiddenResponse;
import java.util.Map;

/**
 * DELETE /api/networks/{name}: removes a custom network from the local registry. Blocked for the default network.
 *
 * @author Angatu Sistemas
 */
public final class DeleteNetworkRoute extends Route {

    public DeleteNetworkRoute() {
        super("/api/networks/{name}", RouteType.DELETE, DeleteNetworkRoute::handle);
    }

    private static void handle(Context ctx) {
        Guard.requireSession(ctx);
        String name = ctx.pathParam("name");
        if (name.isBlank()) {
            throw new BadRequestResponse("Nome da rede obrigatório.");
        }
        if (NetworkDefaults.isProtected(name)) {
            throw new ForbiddenResponse("A rede padrão AngatuVoice é obrigatória e protegida contra exclusão.");
        }
        try {
            NetworkManager.delete(name);
            ctx.json(GsonAPI.get().toJson(Map.of("ok", true)));
        } catch (UnsupportedOperationException e) {
            throw new ForbiddenResponse(e.getMessage());
        }
    }
}
