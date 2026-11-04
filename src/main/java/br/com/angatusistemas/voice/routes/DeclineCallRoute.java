package br.com.angatusistemas.voice.routes;

import br.com.angatusistemas.lib.connection.StatusCode;
import br.com.angatusistemas.lib.javalin.routes.Route;
import br.com.angatusistemas.lib.javalin.routes.RouteType;
import br.com.angatusistemas.voice.services.PeerNetwork;
import io.javalin.http.BadRequestResponse;
import io.javalin.http.Context;

/**
 * POST /api/call/decline: stops the ringing of an incoming call and tells whoever is calling.
 *
 * @author Angatu Sistemas
 */
public final class DeclineCallRoute extends Route {

    public DeclineCallRoute() {
        super("/api/call/decline", RouteType.POST, DeclineCallRoute::handle);
    }

    private static void handle(Context ctx) {
        Guard.requireSession(ctx);
        Body body = JsonBody.read(ctx, Body.class);
        try {
            PeerNetwork.declineCall(String.valueOf(body.room()));
        } catch (IllegalArgumentException e) {
            throw new BadRequestResponse("Sala de chamada inválida.");
        }
        ctx.status(StatusCode.NO_CONTENT.code());
    }

    private record Body(String room) {
    }
}
