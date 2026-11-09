package br.com.angatusistemas.voice.routes;

import br.com.angatusistemas.lib.javalin.routes.Route;
import br.com.angatusistemas.lib.javalin.routes.RouteType;
import io.javalin.http.Context;
import io.javalin.http.NotFoundResponse;

/**
 * GET /api/files/{id}: an attachment from the server, for whoever is in its conversation.
 *
 * @author Angatu Sistemas
 */
public final class GetFileRoute extends Route {

    public GetFileRoute() {
        super("/api/files/{id}", RouteType.GET, GetFileRoute::handle);
    }

    private static void handle(Context ctx) {
        Guard.requireSession(ctx);
        String id = ctx.pathParam("id");
        if (!id.matches("[0-9a-zA-Z_-]{1,64}")) {
            throw new NotFoundResponse("Arquivo indisponível.");
        }
        ServerCall.stream(ctx, "/api/files/" + id);
    }
}
