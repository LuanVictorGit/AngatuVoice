package br.com.angatusistemas.voice.routes;

import br.com.angatusistemas.lib.connection.StatusCode;
import br.com.angatusistemas.lib.javalin.routes.Route;
import br.com.angatusistemas.lib.javalin.routes.RouteType;
import br.com.angatusistemas.voice.desktop.Overlay;
import br.com.angatusistemas.voice.services.IdentityService;
import io.javalin.http.BadRequestResponse;
import io.javalin.http.Context;
import java.util.List;

/**
 * POST /api/overlay: tells the corner popup who is speaking right now in the current call.
 *
 * @author Angatu Sistemas
 */
public final class UpdateOverlayRoute extends Route {

    private static final int MAX_SPEAKERS = 16;

    public UpdateOverlayRoute() {
        super("/api/overlay", RouteType.POST, UpdateOverlayRoute::handle);
    }

    private static void handle(Context ctx) {
        Guard.requireSession(ctx);
        Body body = JsonBody.read(ctx, Body.class);
        List<Speaker> speaking = body.speaking() == null ? List.of() : body.speaking();
        if (speaking.size() > MAX_SPEAKERS) {
            throw new BadRequestResponse("Lista inválida.");
        }
        Overlay.update(speaking.stream()
                .filter(s -> s != null && s.id() != null && s.id().matches("[0-9a-f]{20}"))
                .map(s -> new Overlay.Speaker(s.id(), IdentityService.clean(s.name())))
                .toList());
        ctx.status(StatusCode.NO_CONTENT.code());
    }

    private record Body(List<Speaker> speaking) {
    }

    private record Speaker(String id, String name) {
    }
}