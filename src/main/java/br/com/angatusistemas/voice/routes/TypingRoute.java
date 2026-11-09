package br.com.angatusistemas.voice.routes;

import br.com.angatusistemas.lib.connection.StatusCode;
import br.com.angatusistemas.lib.javalin.routes.Route;
import br.com.angatusistemas.lib.javalin.routes.RouteType;
import br.com.angatusistemas.voice.services.ChatService;
import io.javalin.http.BadRequestResponse;
import io.javalin.http.Context;

/**
 * POST /api/messages/typing: lets the others in a conversation see that this person is typing.
 *
 * @author Angatu Sistemas
 */
public final class TypingRoute extends Route {

    public TypingRoute() {
        super("/api/messages/typing", RouteType.POST, TypingRoute::handle);
    }

    private static void handle(Context ctx) {
        Guard.requireSession(ctx);
        Body body = JsonBody.read(ctx, Body.class);
        if (!ChatService.validChat(body.chat())) {
            throw new BadRequestResponse("Conversa inválida.");
        }
        ChatService.typing(body.chat());
        ctx.status(StatusCode.NO_CONTENT.code());
    }

    private record Body(String chat) {
    }
}
