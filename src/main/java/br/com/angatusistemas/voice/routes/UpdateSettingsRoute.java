package br.com.angatusistemas.voice.routes;

import br.com.angatusistemas.lib.gson.GsonAPI;
import br.com.angatusistemas.lib.javalin.routes.Route;
import br.com.angatusistemas.lib.javalin.routes.RouteType;
import br.com.angatusistemas.voice.services.SettingsService;
import io.javalin.http.BadRequestResponse;
import io.javalin.http.Context;
import java.util.HashMap;
import java.util.Map;

/**
 * PUT /api/settings: validates and stores a batch of preferences from the allow-list.
 *
 * @author Angatu Sistemas
 */
public final class UpdateSettingsRoute extends Route {

    public UpdateSettingsRoute() {
        super("/api/settings", RouteType.PUT, UpdateSettingsRoute::handle);
    }

    private static void handle(Context ctx) {
        Guard.requireSession(ctx);
        Map<String, String> changes = new HashMap<>();
        JsonBody.read(ctx, Map.class).forEach((k, v) -> changes.put(String.valueOf(k), String.valueOf(v)));
        try {
            SettingsService.update(changes);
        } catch (IllegalArgumentException e) {
            throw new BadRequestResponse("Alguma configuração enviada é inválida.");
        }
        ctx.json(GsonAPI.get().toJson(SettingsService.all()));
    }
}
