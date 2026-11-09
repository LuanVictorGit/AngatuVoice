package br.com.angatusistemas.voice.routes;

import br.com.angatusistemas.lib.javalin.routes.Route;
import br.com.angatusistemas.lib.javalin.routes.RouteType;
import br.com.angatusistemas.voice.services.ChatService;
import io.javalin.http.BadRequestResponse;
import io.javalin.http.Context;

/**
 * PUT /api/messages/{id}/pin: pins or unpins a message in its conversation, for everyone in it.
 *
 * @author Angatu Sistemas
 */
public final class PinMessageRoute extends Route {

    public PinMessageRoute() {
        super("/api/messages/{id}/pin", RouteType.PUT, PinMessageRoute::handle);
    }

    private static void handle(Context ctx) {
        Guard.requireSession(ctx);
        String id = ctx.pathParam("id");
        Body body = JsonBody.read(ctx, Body.class);
        if (!ChatService.validMessageId(id) || body.on() == null) {
            throw new BadRequestResponse("Pedido inválido.");
        }
        ctx.contentType("application/json").result(ServerCall.ask(() -> ChatService.pin(id, body.on())).toString());
    }

    private record Body(Boolean on) {
    }
}
