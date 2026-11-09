package br.com.angatusistemas.voice.routes;

import br.com.angatusistemas.lib.javalin.routes.Route;
import br.com.angatusistemas.lib.javalin.routes.RouteType;
import br.com.angatusistemas.voice.services.ChatService;
import io.javalin.http.BadRequestResponse;
import io.javalin.http.Context;

/**
 * PUT /api/messages/{id}/files/{file}: swaps a text file already sent for a new version uploaded just before. The server
 * accepts it from the author or from an administrator in admin mode.
 *
 * @author Angatu Sistemas
 */
public final class ReplaceFileRoute extends Route {

    public ReplaceFileRoute() {
        super("/api/messages/{id}/files/{file}", RouteType.PUT, ReplaceFileRoute::handle);
    }

    private static void handle(Context ctx) {
        Guard.requireSession(ctx);
        String id = ctx.pathParam("id");
        String file = ctx.pathParam("file");
        Body body = JsonBody.read(ctx, Body.class);
        if (!ChatService.validMessageId(id) || !file.matches("[0-9a-f]{32}") || body.with() == null || !body.with().matches("[0-9a-f]{32}")) {
            throw new BadRequestResponse("Pedido inválido.");
        }
        ctx.contentType("application/json").result(ServerCall.ask(() -> ChatService.replaceFile(id, file, body.with())).toString());
    }

    private record Body(String with) {
    }
}
