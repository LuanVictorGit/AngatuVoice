package br.com.angatusistemas.voice.routes;

import br.com.angatusistemas.lib.javalin.routes.Route;
import br.com.angatusistemas.lib.javalin.routes.RouteType;
import br.com.angatusistemas.voice.services.ChatService;
import io.javalin.http.Context;
import io.javalin.http.NotFoundResponse;

/**
 * GET /api/embeds/{id}/{n}: the image of a link preview, fetched by the server so the site never sees who is reading.
 *
 * @author Angatu Sistemas
 */
public final class EmbedImageRoute extends Route {

    public EmbedImageRoute() {
        super("/api/embeds/{id}/{n}", RouteType.GET, EmbedImageRoute::handle);
    }

    private static void handle(Context ctx) {
        Guard.requireSession(ctx);
        String id = ctx.pathParam("id");
        String n = ctx.pathParam("n");
        if (!ChatService.validMessageId(id) || !n.matches("[0-2]")) {
            throw new NotFoundResponse("Imagem indisponível.");
        }
        ServerCall.stream(ctx, "/api/embed/" + id + "/" + n);
    }
}
