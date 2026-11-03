package br.com.angatusistemas.voice.routes;

import br.com.angatusistemas.lib.connection.StatusCode;
import br.com.angatusistemas.lib.javalin.routes.Route;
import br.com.angatusistemas.lib.javalin.routes.RouteType;
import br.com.angatusistemas.voice.services.PeerNetwork;
import com.google.gson.JsonElement;
import io.javalin.http.BadRequestResponse;
import io.javalin.http.Context;

/**
 * POST /api/call/signal: relays a WebRTC offer, answer or candidate to one participant.
 *
 * @author Angatu Sistemas
 */
public final class CallSignalRoute extends Route {

    public CallSignalRoute() {
        super("/api/call/signal", RouteType.POST, CallSignalRoute::handle);
    }

    private static void handle(Context ctx) {
        Guard.requireSession(ctx);
        Body body = JsonBody.read(ctx, Body.class);
        try {
            PeerNetwork.signal(String.valueOf(body.room()), String.valueOf(body.to()), String.valueOf(body.kind()), body.data());
        } catch (IllegalArgumentException e) {
            throw new BadRequestResponse("Não foi possível entregar o sinal da chamada.");
        }
        ctx.status(StatusCode.NO_CONTENT.code());
    }

    private record Body(String room, String to, String kind, JsonElement data) {
    }
}
