package br.com.angatusistemas.voice.routes;

import br.com.angatusistemas.lib.javalin.routes.Route;
import br.com.angatusistemas.lib.javalin.routes.RouteType;
import br.com.angatusistemas.voice.services.ChatService;
import com.google.gson.JsonObject;
import io.javalin.http.Context;
import io.javalin.http.NotFoundResponse;

/**
 * POST /api/admin/{action}: administrator actions. This app only forwards them; the server checks the password, who is an
 * administrator, whether admin mode is on and whom the action may reach.
 *
 * @author Angatu Sistemas
 */
public final class AdminRoute extends Route {

    public AdminRoute() {
        super("/api/admin/{action}", RouteType.POST, AdminRoute::handle);
    }

    private static void handle(Context ctx) {
        Guard.requireSession(ctx);
        String action = ctx.pathParam("action");
        JsonObject body = ctx.body().isBlank() ? new JsonObject() : JsonBody.read(ctx, JsonObject.class);
        try {
            ctx.contentType("application/json").result(ServerCall.ask(() -> ChatService.admin(action, body)).toString());
        } catch (IllegalArgumentException e) {
            throw new NotFoundResponse("Ação desconhecida.");
        }
    }
}
