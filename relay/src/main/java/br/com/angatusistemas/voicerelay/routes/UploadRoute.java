package br.com.angatusistemas.voicerelay.routes;

import br.com.angatusistemas.lib.gson.GsonAPI;
import br.com.angatusistemas.lib.javalin.routes.Route;
import br.com.angatusistemas.lib.javalin.routes.RouteType;
import br.com.angatusistemas.voicerelay.entities.Attachment;
import br.com.angatusistemas.voicerelay.services.AttachmentStore;
import br.com.angatusistemas.voicerelay.services.NetHub;
import br.com.angatusistemas.voicerelay.services.Refusal;
import br.com.angatusistemas.voicerelay.services.Session;
import io.javalin.http.BadRequestResponse;
import io.javalin.http.ContentTooLargeResponse;
import io.javalin.http.Context;
import io.javalin.http.HttpResponseException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PushbackInputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

/**
 * POST /api/upload: one file as the raw request body, streamed to disk. It belongs to the uploader until a message takes it.
 *
 * @author Angatu Sistemas
 */
public final class UploadRoute extends Route {

    private static final int SCANNED_BODY_BYTES = 64 * 1024;

    public UploadRoute() {
        super("/api/upload", RouteType.POST, UploadRoute::handle);
    }

    private static void handle(Context ctx) {
        Session session = NetHub.httpSession(ctx);
        if (ctx.contentLength() > AttachmentStore.MAX_FILE_BYTES) {
            throw new ContentTooLargeResponse("O arquivo passa de 25 MB.");
        }
        String name;
        try {
            String header = ctx.header("X-AV-Name");
            name = header == null ? "" : URLDecoder.decode(header, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw new BadRequestResponse("O nome do arquivo veio em um formato inválido.");
        }
        Attachment attachment;
        try (InputStream body = body(ctx)) {
            // The app sends the bytes as binary and the real type here, so text files are not scanned as if they were form input.
            String declared = ctx.header("X-AV-Type");
            attachment = AttachmentStore.upload(session, name, declared == null || declared.isBlank() ? ctx.contentType() : declared, body);
        } catch (Refusal refusal) {
            throw new HttpResponseException(refusal.status(), refusal.getMessage());
        } catch (IOException e) {
            throw new BadRequestResponse("O envio do arquivo foi interrompido. Tente de novo.");
        }
        ctx.status(201).json(GsonAPI.get().toJson(AttachmentStore.ref(attachment)));
    }

    /* The library's input filter reads small text bodies whole to scan them; then the stream is spent and the copy it kept is used. */
    private static InputStream body(Context ctx) throws IOException {
        PushbackInputStream stream = new PushbackInputStream(ctx.bodyInputStream(), 1);
        int first = stream.read();
        if (first == -1) {
            return ctx.contentLength() > 0 && ctx.contentLength() <= SCANNED_BODY_BYTES ? new ByteArrayInputStream(ctx.bodyAsBytes()) : stream;
        }
        stream.unread(first);
        return stream;
    }
}
