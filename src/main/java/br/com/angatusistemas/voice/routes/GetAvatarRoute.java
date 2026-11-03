package br.com.angatusistemas.voice.routes;

import br.com.angatusistemas.lib.javalin.routes.Route;
import br.com.angatusistemas.lib.javalin.routes.RouteType;
import br.com.angatusistemas.voice.services.AvatarService;
import io.javalin.http.Context;
import io.javalin.http.NotFoundResponse;
import java.util.regex.Pattern;

/**
 * GET /api/avatar/{id}: the stored profile picture of the local user or of a known participant.
 *
 * @author Angatu Sistemas
 */
public final class GetAvatarRoute extends Route {

    private static final Pattern ID = Pattern.compile("[0-9a-f]{20}");

    public GetAvatarRoute() {
        super("/api/avatar/{id}", RouteType.GET, GetAvatarRoute::handle);
    }

    private static void handle(Context ctx) {
        Guard.requireSession(ctx);
        String id = ctx.pathParam("id");
        if (!ID.matcher(id).matches()) {
            throw new NotFoundResponse();
        }
        byte[] image = AvatarService.bytes(id).orElseThrow(NotFoundResponse::new);
        ctx.contentType("image/webp").result(image);
    }
}