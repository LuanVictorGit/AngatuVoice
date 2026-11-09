package br.com.angatusistemas.voice.routes;

import br.com.angatusistemas.lib.connection.StatusCode;
import br.com.angatusistemas.lib.javalin.routes.Route;
import br.com.angatusistemas.lib.javalin.routes.RouteType;
import br.com.angatusistemas.voice.services.ChatService;
import io.javalin.http.BadRequestResponse;
import io.javalin.http.Context;

/**
 * POST /api/messages/read: tells the server how far this person has read a conversation; their other devices follow.
 *
 * @author Angatu Sistemas
 */
public final class ReadMessagesRoute extends Route {

    public ReadMessagesRoute() {
        super("/api/messages/read", RouteType.POST, ReadMessagesRoute::handle);
    }

    private static void handle(Context ctx) {
        Guard.requireSession(ctx);
        Body body = JsonBody.read(ctx, Body.class);
        if (!ChatService.validChat(body.chat()) || body.seq() == null || body.seq() < 0) {
            throw new BadRequestResponse("Conversa inválida.");
        }
        ChatService.read(body.chat(), body.seq());
        ctx.status(StatusCode.NO_CONTENT.code());
    }

    private record Body(String chat, Long seq) {
    }
}
