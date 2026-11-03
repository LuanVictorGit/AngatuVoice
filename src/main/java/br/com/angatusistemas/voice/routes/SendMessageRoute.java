package br.com.angatusistemas.voice.routes;

import br.com.angatusistemas.lib.connection.StatusCode;
import br.com.angatusistemas.lib.gson.GsonAPI;
import br.com.angatusistemas.lib.javalin.routes.Route;
import br.com.angatusistemas.lib.javalin.routes.RouteType;
import br.com.angatusistemas.voice.entities.Message;
import br.com.angatusistemas.voice.services.MessageService;
import br.com.angatusistemas.voice.services.PeerNetwork;
import io.javalin.http.BadRequestResponse;
import io.javalin.http.Context;
import io.javalin.http.NotFoundResponse;

/**
 * POST /api/messages: signs a message and delivers it to a group or a person.
 *
 * @author Angatu Sistemas
 */
public final class SendMessageRoute extends Route {

    public SendMessageRoute() {
        super("/api/messages", RouteType.POST, SendMessageRoute::handle);
    }

    private static void handle(Context ctx) {
        Guard.requireSession(ctx);
        Body body = JsonBody.read(ctx, Body.class);
        if (body.chat() == null || body.text() == null || MessageService.sanitize(body.text()).isEmpty()) {
            throw new BadRequestResponse("Escreva uma mensagem.");
        }
        try {
            Message message = PeerNetwork.send(body.chat(), body.text());
            ctx.status(StatusCode.CREATED.code())
                    .json(GsonAPI.get().toJson(MessageService.view(message, PeerNetwork.me().id())));
        } catch (IllegalArgumentException e) {
            throw new NotFoundResponse("Conversa não encontrada.");
        }
    }

    private record Body(String chat, String text) {
    }
}
