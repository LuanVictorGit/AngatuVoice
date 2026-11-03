package br.com.angatusistemas.voice.routes;

import br.com.angatusistemas.lib.connection.StatusCode;
import br.com.angatusistemas.lib.javalin.routes.Route;
import br.com.angatusistemas.lib.javalin.routes.RouteType;
import br.com.angatusistemas.voice.services.LiveHub;
import br.com.angatusistemas.voice.services.MessageService;
import br.com.angatusistemas.voice.services.PeerNetwork;
import io.javalin.http.BadRequestResponse;
import io.javalin.http.Context;
import java.util.Map;

/**
 * DELETE /api/peers/{id}: forgets a participant and removes their direct chat history.
 *
 * @author Angatu Sistemas
 */
public final class DeletePeerRoute extends Route {

    public DeletePeerRoute() {
        super("/api/peers/{id}", RouteType.DELETE, DeletePeerRoute::handle);
    }

    private static void handle(Context ctx) {
        Guard.requireSession(ctx);
        String id = ctx.pathParam("id");
        if (id.isBlank() || id.length() > 64) {
            throw new BadRequestResponse("Participante inválido.");
        }
        MessageService.clear("dm:" + id);
        PeerNetwork.forget(id);
        LiveHub.emit("peer.removed", Map.of("id", id));
        ctx.status(StatusCode.NO_CONTENT.code());
    }
}
