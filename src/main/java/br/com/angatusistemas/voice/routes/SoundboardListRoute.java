package br.com.angatusistemas.voice.routes;

import br.com.angatusistemas.lib.gson.GsonAPI;
import br.com.angatusistemas.lib.javalin.routes.Route;
import br.com.angatusistemas.lib.javalin.routes.RouteType;
import br.com.angatusistemas.voice.services.SoundboardService;
import io.javalin.http.Context;
import java.util.Map;

/**
 * GET /api/soundboard: which slots of the sound wheel hold a file of the person's own.
 *
 * @author Angatu Sistemas
 */
public final class SoundboardListRoute extends Route {

    public SoundboardListRoute() {
        super("/api/soundboard", RouteType.GET, SoundboardListRoute::handle);
    }

    private static void handle(Context ctx) {
        Guard.requireSession(ctx);
        ctx.contentType("application/json").result(GsonAPI.get().toJson(Map.of("custom", SoundboardService.custom())));
    }
}
