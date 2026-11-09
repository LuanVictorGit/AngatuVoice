package br.com.angatusistemas.voice.routes;

import br.com.angatusistemas.lib.javalin.routes.Route;
import br.com.angatusistemas.lib.javalin.routes.RouteType;
import br.com.angatusistemas.voice.services.ChatService;
import io.javalin.http.BadRequestResponse;
import io.javalin.http.Context;
import io.javalin.http.NotFoundResponse;

/**
 * GET /api/messages: one page of a conversation from the server, oldest first, before, after or around a sequence number.
 *
 * @author Angatu Sistemas
 */
public final class ListMessagesRoute extends Route {

    private static final int PAGE = 50;

    public ListMessagesRoute() {
        super("/api/messages", RouteType.GET, ListMessagesRoute::handle);
    }

    private static void handle(Context ctx) {
        Guard.requireSession(ctx);
        String chat = ctx.queryParam("chat");
        if (!ChatService.validChat(chat)) {
            throw new NotFoundResponse("Conversa não encontrada.");
        }
        long before = parse(ctx.queryParam("before"));
        long after = parse(ctx.queryParam("after"));
        long around = parse(ctx.queryParam("around"));
        long limit = parse(ctx.queryParam("limit"));
        int size = limit == 0 ? PAGE : (int) Math.min(limit, 100);
        ctx.contentType("application/json").result(ServerCall.ask(() -> ChatService.history(chat, before, after, around, size)).toString());
    }

    private static long parse(String value) {
        try {
            return value == null || value.isEmpty() ? 0 : Math.max(0, Long.parseLong(value));
        } catch (NumberFormatException e) {
            throw new BadRequestResponse("Parâmetro inválido.");
        }
    }
}
