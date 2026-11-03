package br.com.angatusistemas.voice.routes;

import br.com.angatusistemas.lib.connection.StatusCode;
import br.com.angatusistemas.lib.javalin.routes.Route;
import br.com.angatusistemas.lib.javalin.routes.RouteType;
import br.com.angatusistemas.voice.services.AvatarService;
import br.com.angatusistemas.voice.services.LiveHub;
import br.com.angatusistemas.voice.services.PeerNetwork;
import io.javalin.http.BadRequestResponse;
import io.javalin.http.Context;
import java.util.Map;

/**
 * PUT /api/me/avatar: stores or clears the profile picture and sends it to everyone connected.
 *
 * @author Angatu Sistemas
 */
public final class UpdateAvatarRoute extends Route {

    public UpdateAvatarRoute() {
        super("/api/me/avatar", RouteType.PUT, UpdateAvatarRoute::handle);
    }

    private static void handle(Context ctx) {
        Guard.requireSession(ctx);
        Body body = JsonBody.read(ctx, Body.class);
        try {
            AvatarService.setOwn(body.data());
        } catch (IllegalArgumentException e) {
            throw new BadRequestResponse("A imagem não pôde ser usada. Escolha uma foto PNG, JPG ou WebP.");
        }
        PeerNetwork.publishProfile();
        LiveHub.emit("peer.profile", Map.of("id", PeerNetwork.me().id()));
        ctx.status(StatusCode.NO_CONTENT.code());
    }

    private record Body(String data) {
    }
}