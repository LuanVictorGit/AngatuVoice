package br.com.angatusistemas.voice.routes;

import br.com.angatusistemas.lib.javalin.routes.Route;
import br.com.angatusistemas.lib.javalin.routes.RouteType;
import br.com.angatusistemas.voice.services.ChatService;
import io.javalin.http.Context;
import io.javalin.http.NotFoundResponse;

/**
 * GET /api/pins?chat=...: the pinned messages of a conversation, most recently pinned first.
 *
 * @author Angatu Sistemas
 */
public final class ListPinsRoute extends Route {

    public ListPinsRoute() {
        super("/api/pins", RouteType.GET, ListPinsRoute::handle);
    }

    private static void handle(Context ctx) {
        Guard.requireSession(ctx);
        String chat = ctx.queryParam("chat");
        if (!ChatService.validChat(chat)) {
            throw new NotFoundResponse("Conversa não encontrada.");
        }
        ctx.contentType("application/json").result(ServerCall.ask(() -> ChatService.pins(chat)).toString());
    }
}
