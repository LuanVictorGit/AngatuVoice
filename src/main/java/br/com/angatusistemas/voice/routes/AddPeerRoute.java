package br.com.angatusistemas.voice.routes;

import br.com.angatusistemas.lib.connection.StatusCode;
import br.com.angatusistemas.lib.javalin.routes.Route;
import br.com.angatusistemas.lib.javalin.routes.RouteType;
import br.com.angatusistemas.voice.services.PeerNetwork;
import io.javalin.http.BadRequestResponse;
import io.javalin.http.Context;

/**
 * POST /api/peers: dials a participant by Radmin address when discovery has not found them yet.
 *
 * @author Angatu Sistemas
 */
public final class AddPeerRoute extends Route {

    public AddPeerRoute() {
        super("/api/peers", RouteType.POST, AddPeerRoute::handle);
    }

    private static void handle(Context ctx) {
        Guard.requireSession(ctx);
        Body body = JsonBody.read(ctx, Body.class);
        try {
            PeerNetwork.addManual(body.address());
        } catch (IllegalArgumentException e) {
            throw new BadRequestResponse("Informe um endereço IP da sua rede Radmin, como 26.10.20.30.");
        }
        ctx.status(StatusCode.ACCEPTED.code());
    }

    private record Body(String address) {
    }
}
