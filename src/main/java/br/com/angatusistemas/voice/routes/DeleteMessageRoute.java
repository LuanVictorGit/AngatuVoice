package br.com.angatusistemas.voice.routes;

import br.com.angatusistemas.lib.connection.StatusCode;
import br.com.angatusistemas.lib.javalin.routes.Route;
import br.com.angatusistemas.lib.javalin.routes.RouteType;
import br.com.angatusistemas.voice.services.ChatService;
import io.javalin.http.BadRequestResponse;
import io.javalin.http.Context;

/**
 * DELETE /api/messages/{id}: asks the server to delete a message; it accepts the author, or an administrator in admin mode.
 *
 * @author Angatu Sistemas
 */
public final class DeleteMessageRoute extends Route {

    public DeleteMessageRoute() {
        super("/api/messages/{id}", RouteType.DELETE, DeleteMessageRoute::handle);
    }

    private static void handle(Context ctx) {
        Guard.requireSession(ctx);
        String id = ctx.pathParam("id");
        if (!ChatService.validMessageId(id)) {
            throw new BadRequestResponse("Mensagem inválida.");
        }
        ServerCall.run(() -> ChatService.delete(id));
        ctx.status(StatusCode.NO_CONTENT.code());
    }
}
