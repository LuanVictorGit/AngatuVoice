package br.com.angatusistemas.voice.routes;

import br.com.angatusistemas.lib.connection.StatusCode;
import br.com.angatusistemas.lib.gson.GsonAPI;
import br.com.angatusistemas.lib.javalin.routes.Route;
import br.com.angatusistemas.lib.javalin.routes.RouteType;
import br.com.angatusistemas.voice.entities.Group;
import br.com.angatusistemas.voice.services.PeerNetwork;
import br.com.angatusistemas.voice.services.GroupService;
import br.com.angatusistemas.voice.services.LiveHub;
import io.javalin.http.BadRequestResponse;
import io.javalin.http.Context;
import java.util.Map;

/**
 * POST /api/groups: creates a private group protected by a fresh invite secret.
 *
 * @author Angatu Sistemas
 */
public final class CreateGroupRoute extends Route {

    public CreateGroupRoute() {
        super("/api/groups", RouteType.POST, CreateGroupRoute::handle);
    }

    private static void handle(Context ctx) {
        Guard.requireSession(ctx);
        Body body = JsonBody.read(ctx, Body.class);
        try {
            Group group = GroupService.create(body.name());
            PeerNetwork.groupsChanged();
            LiveHub.emit("group.changed", Map.of("id", group.getId()));
            ctx.status(StatusCode.CREATED.code()).json(GsonAPI.get().toJson(Map.of("id", group.getId())));
        } catch (IllegalArgumentException e) {
            throw new BadRequestResponse("Informe um nome com até 40 caracteres.");
        }
    }

    private record Body(String name) {
    }
}
