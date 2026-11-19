package br.com.angatusistemas.voice.routes;

import br.com.angatusistemas.lib.javalin.routes.Route;
import br.com.angatusistemas.lib.javalin.routes.RouteType;
import br.com.angatusistemas.voice.services.SoundboardService;
import io.javalin.http.Context;

/**
 * DELETE /api/soundboard/{slot}: drops the person's own file, so the slot plays its original sound again.
 *
 * @author Angatu Sistemas
 */
public final class DeleteSoundboardRoute extends Route {

    public DeleteSoundboardRoute() {
        super("/api/soundboard/{slot}", RouteType.DELETE, DeleteSoundboardRoute::handle);
    }

    private static void handle(Context ctx) {
        Guard.requireSession(ctx);
        SoundboardService.remove(SoundboardSlot.of(ctx));
        ctx.status(204);
    }
}
