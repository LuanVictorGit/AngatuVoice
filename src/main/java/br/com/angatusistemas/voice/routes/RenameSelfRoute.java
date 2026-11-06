package br.com.angatusistemas.voice.routes;

import br.com.angatusistemas.lib.connection.StatusCode;
import br.com.angatusistemas.lib.javalin.routes.Route;
import br.com.angatusistemas.lib.javalin.routes.RouteType;
import br.com.angatusistemas.voice.services.PeerNetwork;
import br.com.angatusistemas.voice.services.IdentityService;
import io.javalin.http.BadRequestResponse;
import io.javalin.http.Context;

/**
 * PUT /api/me: changes the display name shown to other participants.
 *
 * @author Angatu Sistemas
 */
public final class RenameSelfRoute extends Route {

    public RenameSelfRoute() {
        super("/api/me", RouteType.PUT, RenameSelfRoute::handle);
    }

    private static void handle(Context ctx) {
        Guard.requireSession(ctx);
        Body body = JsonBody.read(ctx, Body.class);
        if (IdentityService.clean(body.name()).isEmpty()) {
            throw new BadRequestResponse("Informe um nome com até 32 caracteres.");
        }
        PeerNetwork.renamed(IdentityService.rename(body.name()));
        ctx.status(StatusCode.NO_CONTENT.code());
    }

    private record Body(String name) {
    }
}
