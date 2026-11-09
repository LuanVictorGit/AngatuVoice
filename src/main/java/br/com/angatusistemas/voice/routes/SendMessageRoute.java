package br.com.angatusistemas.voice.routes;

import br.com.angatusistemas.lib.connection.StatusCode;
import br.com.angatusistemas.lib.javalin.routes.Route;
import br.com.angatusistemas.lib.javalin.routes.RouteType;
import br.com.angatusistemas.voice.services.ChatService;
import io.javalin.http.BadRequestResponse;
import io.javalin.http.Context;
import java.util.List;

/**
 * POST /api/messages: asks the server to publish a message; the answer is the message as the server stored it.
 *
 * @author Angatu Sistemas
 */
public final class SendMessageRoute extends Route {

    public SendMessageRoute() {
        super("/api/messages", RouteType.POST, SendMessageRoute::handle);
    }

    private static void handle(Context ctx) {
        Guard.requireSession(ctx);
        Body body = JsonBody.read(ctx, Body.class);
        List<String> files = body.attachments() == null ? List.of() : body.attachments();
        String text = body.text() == null ? "" : body.text();
        if (!ChatService.validChat(body.chat()) || body.nonce() == null || !body.nonce().matches("[0-9a-zA-Z-]{8,40}")
                || files.size() > ChatService.MAX_ATTACHMENTS || files.stream().anyMatch(f -> f == null || !f.matches("[0-9a-zA-Z_-]{1,64}"))
                || (body.replyTo() != null && !ChatService.validMessageId(body.replyTo()))) {
            throw new BadRequestResponse("Mensagem inválida.");
        }
        if (text.length() > ChatService.MAX_TEXT) {
            throw new BadRequestResponse("A mensagem passou de " + ChatService.MAX_TEXT + " caracteres.");
        }
        if (text.isBlank() && files.isEmpty()) {
            throw new BadRequestResponse("Escreva uma mensagem.");
        }
        ctx.status(StatusCode.CREATED.code()).contentType("application/json")
                .result(ServerCall.ask(() -> ChatService.send(body.chat(), text, body.nonce(), body.replyTo(), files)).toString());
    }

    private record Body(String chat, String text, String nonce, String replyTo, List<String> attachments) {
    }
}
