package br.com.angatusistemas.voice.routes;

import br.com.angatusistemas.lib.connection.StatusCode;
import br.com.angatusistemas.lib.javalin.routes.Route;
import br.com.angatusistemas.lib.javalin.routes.RouteType;
import br.com.angatusistemas.voice.services.GroupService;
import br.com.angatusistemas.voice.services.LiveHub;
import br.com.angatusistemas.voice.services.MessageService;
import io.javalin.http.BadRequestResponse;
import io.javalin.http.Context;
import java.util.Map;

/**
 * DELETE /api/messages?chat=...: clears all messages in a group or direct conversation.
 *
 * @author Angatu Sistemas
 */
public final class ClearMessagesRoute extends Route {

    public ClearMessagesRoute() {
        super("/api/messages", RouteType.DELETE, ClearMessagesRoute::handle);
    }

    private static void handle(Context ctx) {
        Guard.requireSession(ctx);
        String chat = ctx.queryParam("chat");
        if (chat == null || chat.length() > 60 || !(chat.startsWith("dm:") || GroupService.find(chat) != null)) {
            throw new BadRequestResponse("Conversa inválida.");
        }
        MessageService.clear(chat);
        LiveHub.emit("messages.cleared", Map.of("chat", chat));
        ctx.status(StatusCode.NO_CONTENT.code());
    }
}
