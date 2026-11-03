package br.com.angatusistemas.voice.routes;

import br.com.angatusistemas.lib.gson.GsonAPI;
import br.com.angatusistemas.lib.javalin.routes.Route;
import br.com.angatusistemas.lib.javalin.routes.RouteType;
import br.com.angatusistemas.voice.services.GroupService;
import io.javalin.http.Context;
import io.javalin.http.NotFoundResponse;
import java.util.Map;

/**
 * GET /api/groups/{id}/invite: the invite code of a private group.
 *
 * @author Angatu Sistemas
 */
public final class GroupInviteRoute extends Route {

    public GroupInviteRoute() {
        super("/api/groups/{id}/invite", RouteType.GET, GroupInviteRoute::handle);
    }

    private static void handle(Context ctx) {
        Guard.requireSession(ctx);
        try {
            ctx.json(GsonAPI.get().toJson(Map.of("code", GroupService.invite(ctx.pathParam("id")))));
        } catch (IllegalArgumentException e) {
            throw new NotFoundResponse("Este grupo não tem convite.");
        }
    }
}
