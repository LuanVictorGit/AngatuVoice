package br.com.angatusistemas.voice.routes;

import br.com.angatusistemas.lib.javalin.routes.Route;
import br.com.angatusistemas.lib.javalin.routes.RouteType;
import br.com.angatusistemas.voice.services.ChatService;
import io.javalin.http.BadRequestResponse;
import io.javalin.http.Context;

/**
 * PUT /api/messages/{id}/reactions: adds or removes this person's reaction with one emoji.
 *
 * @author Angatu Sistemas
 */
public final class ReactMessageRoute extends Route {

    public ReactMessageRoute() {
        super("/api/messages/{id}/reactions", RouteType.PUT, ReactMessageRoute::handle);
    }

    private static void handle(Context ctx) {
        Guard.requireSession(ctx);
        String id = ctx.pathParam("id");
        Body body = JsonBody.read(ctx, Body.class);
        if (!ChatService.validMessageId(id) || body.emoji() == null || body.emoji().isBlank() || body.emoji().length() > 32 || body.on() == null) {
            throw new BadRequestResponse("Reação inválida.");
        }
        ctx.contentType("application/json").result(ServerCall.ask(() -> ChatService.react(id, body.emoji(), body.on())).toString());
    }

    private record Body(String emoji, Boolean on) {
    }
}
