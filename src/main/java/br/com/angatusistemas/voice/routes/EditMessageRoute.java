package br.com.angatusistemas.voice.routes;

import br.com.angatusistemas.lib.javalin.routes.Route;
import br.com.angatusistemas.lib.javalin.routes.RouteType;
import br.com.angatusistemas.voice.services.ChatService;
import io.javalin.http.BadRequestResponse;
import io.javalin.http.Context;

/**
 * PATCH /api/messages/{id}: asks the server to change the text of one of this person's messages.
 *
 * @author Angatu Sistemas
 */
public final class EditMessageRoute extends Route {

    public EditMessageRoute() {
        super("/api/messages/{id}", RouteType.PATCH, EditMessageRoute::handle);
    }

    private static void handle(Context ctx) {
        Guard.requireSession(ctx);
        String id = ctx.pathParam("id");
        Body body = JsonBody.read(ctx, Body.class);
        if (!ChatService.validMessageId(id) || body.text() == null || body.text().length() > ChatService.MAX_TEXT) {
            throw new BadRequestResponse("Edição inválida.");
        }
        ctx.contentType("application/json").result(ServerCall.ask(() -> ChatService.edit(id, body.text())).toString());
    }

    private record Body(String text) {
    }
}
