package br.com.angatusistemas.voice.routes;

import br.com.angatusistemas.lib.connection.StatusCode;
import br.com.angatusistemas.lib.javalin.routes.Route;
import br.com.angatusistemas.lib.javalin.routes.RouteType;
import br.com.angatusistemas.voice.services.MessageService;
import io.javalin.http.BadRequestResponse;
import io.javalin.http.Context;

/**
 * POST /api/messages/read: marks a conversation as read.
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
        if (body.chat() == null || body.chat().length() > 60) {
            throw new BadRequestResponse("Conversa inválida.");
        }
        MessageService.markRead(body.chat());
        ctx.status(StatusCode.NO_CONTENT.code());
    }

    private record Body(String chat) {
    }
}
