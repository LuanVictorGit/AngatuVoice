package br.com.angatusistemas.voice.routes;

import br.com.angatusistemas.lib.gson.GsonAPI;
import br.com.angatusistemas.lib.javalin.routes.Route;
import br.com.angatusistemas.lib.javalin.routes.RouteType;
import br.com.angatusistemas.voice.services.GroupService;
import br.com.angatusistemas.voice.services.MessageService;
import br.com.angatusistemas.voice.services.PeerNetwork;
import io.javalin.http.BadRequestResponse;
import io.javalin.http.Context;
import io.javalin.http.NotFoundResponse;

/**
 * GET /api/messages: one page of a conversation, oldest first.
 *
 * @author Angatu Sistemas
 */
public final class ListMessagesRoute extends Route {

    public ListMessagesRoute() {
        super("/api/messages", RouteType.GET, ListMessagesRoute::handle);
    }

    private static void handle(Context ctx) {
        Guard.requireSession(ctx);
        String chat = ctx.queryParam("chat");
        if (chat == null || chat.length() > 60 || !(chat.startsWith("dm:") || GroupService.find(chat) != null)) {
            throw new NotFoundResponse("Conversa não encontrada.");
        }
        long before = parse(ctx.queryParam("before"), Long.MAX_VALUE);
        int limit = (int) parse(ctx.queryParam("limit"), 50);
        String me = PeerNetwork.me().id();
        ctx.json(GsonAPI.get().toJson(MessageService.history(chat, before, limit).stream()
                .map(m -> MessageService.view(m, me)).toList()));
    }

    private static long parse(String value, long fallback) {
        try {
            return value == null ? fallback : Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw new BadRequestResponse("Parâmetro inválido.");
        }
    }
}
