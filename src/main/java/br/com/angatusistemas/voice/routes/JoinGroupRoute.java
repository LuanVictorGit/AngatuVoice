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
import io.javalin.http.ConflictResponse;
import io.javalin.http.Context;
import java.util.Map;

/**
 * POST /api/groups/join: joins a private group from an invite code.
 *
 * @author Angatu Sistemas
 */
public final class JoinGroupRoute extends Route {

    public JoinGroupRoute() {
        super("/api/groups/join", RouteType.POST, JoinGroupRoute::handle);
    }

    private static void handle(Context ctx) {
        Guard.requireSession(ctx);
        Body body = JsonBody.read(ctx, Body.class);
        try {
            Group group = GroupService.join(body.code());
            PeerNetwork.groupsChanged();
            LiveHub.emit("group.changed", Map.of("id", group.getId()));
            ctx.status(StatusCode.CREATED.code()).json(GsonAPI.get().toJson(Map.of("id", group.getId())));
        } catch (IllegalStateException e) {
            throw new ConflictResponse("Você já participa deste grupo.");
        } catch (IllegalArgumentException e) {
            throw new BadRequestResponse("Este código de convite não é válido.");
        }
    }

    private record Body(String code) {
    }
}
