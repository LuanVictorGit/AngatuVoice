package br.com.angatusistemas.voice.routes;

import br.com.angatusistemas.lib.connection.StatusCode;
import br.com.angatusistemas.lib.javalin.routes.Route;
import br.com.angatusistemas.lib.javalin.routes.RouteType;
import br.com.angatusistemas.voice.services.PeerNetwork;
import io.javalin.http.BadRequestResponse;
import io.javalin.http.Context;

/**
 * POST /api/call/state: joins or leaves a call room and tells the members.
 *
 * @author Angatu Sistemas
 */
public final class CallStateRoute extends Route {

    public CallStateRoute() {
        super("/api/call/state", RouteType.POST, CallStateRoute::handle);
    }

    private static void handle(Context ctx) {
        Guard.requireSession(ctx);
        Body body = JsonBody.read(ctx, Body.class);
        try {
            PeerNetwork.setCall(String.valueOf(body.room()), body.joined());
        } catch (IllegalArgumentException e) {
            throw new BadRequestResponse("Sala de chamada inválida.");
        }
        ctx.status(StatusCode.NO_CONTENT.code());
    }

    private record Body(String room, boolean joined) {
    }
}
