package br.com.angatusistemas.voice.routes;

import br.com.angatusistemas.lib.gson.GsonAPI;
import br.com.angatusistemas.lib.javalin.routes.Route;
import br.com.angatusistemas.lib.javalin.routes.RouteType;
import br.com.angatusistemas.voice.services.DisplayService;
import io.javalin.http.Context;

/**
 * GET /api/system/windows: the open apps and games a screen share can show on their own. Window ids go out as text,
 * since a 64-bit handle does not fit a JavaScript number exactly.
 *
 * @author Angatu Sistemas
 */
public final class ShareWindowsRoute extends Route {

    public ShareWindowsRoute() {
        super("/api/system/windows", RouteType.GET, ShareWindowsRoute::handle);
    }

    private static void handle(Context ctx) {
        Guard.requireSession(ctx);
        ctx.contentType("application/json").result(GsonAPI.get().toJson(DisplayService.windows().stream()
                .map(w -> new View(Long.toString(w.id()), w.title(), w.app())).toList()));
    }

    private record View(String id, String title, String app) {
    }
}
