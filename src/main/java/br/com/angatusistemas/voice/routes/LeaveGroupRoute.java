package br.com.angatusistemas.voice.routes;

import br.com.angatusistemas.lib.connection.StatusCode;
import br.com.angatusistemas.lib.javalin.routes.Route;
import br.com.angatusistemas.lib.javalin.routes.RouteType;
import br.com.angatusistemas.voice.services.PeerNetwork;
import br.com.angatusistemas.voice.services.GroupService;
import br.com.angatusistemas.voice.services.LiveHub;
import io.javalin.http.Context;
import io.javalin.http.NotFoundResponse;
import java.util.Map;

/**
 * DELETE /api/groups/{id}: leaves a private group and forgets its secret.
 *
 * @author Angatu Sistemas
 */
public final class LeaveGroupRoute extends Route {

    public LeaveGroupRoute() {
        super("/api/groups/{id}", RouteType.DELETE, LeaveGroupRoute::handle);
    }

    private static void handle(Context ctx) {
        Guard.requireSession(ctx);
        try {
            GroupService.leave(ctx.pathParam("id"));
            PeerNetwork.groupsChanged();
        } catch (IllegalArgumentException e) {
            throw new NotFoundResponse("Grupo não encontrado.");
        }
        LiveHub.emit("group.changed", Map.of("id", ctx.pathParam("id")));
        ctx.status(StatusCode.NO_CONTENT.code());
    }
}
