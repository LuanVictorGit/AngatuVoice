package br.com.angatusistemas.voicerelay.routes;

import br.com.angatusistemas.lib.javalin.routes.Route;
import br.com.angatusistemas.lib.javalin.routes.RouteType;
import br.com.angatusistemas.voicerelay.entities.Attachment;
import br.com.angatusistemas.voicerelay.services.AttachmentStore;
import br.com.angatusistemas.voicerelay.services.NetHub;
import br.com.angatusistemas.voicerelay.services.Session;
import br.com.angatusistemas.voicerelay.utils.MediaInfo;
import io.javalin.http.Context;
import io.javalin.http.NotFoundResponse;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;

/**
 * GET /api/files/{id}: an attachment, only for people who can read the chat it was sent to (or its uploader, before that).
 *
 * @author Angatu Sistemas
 */
public final class FileRoute extends Route {

    public FileRoute() {
        super("/api/files/{id}", RouteType.GET, FileRoute::handle);
    }

    private static void handle(Context ctx) {
        Session session = NetHub.httpSession(ctx);
        Attachment attachment = AttachmentStore.find(ctx.pathParam("id"));
        boolean allowed = attachment != null && (attachment.getChat() == null ? session.id().equals(attachment.getOwner())
                : session.canRead(attachment.getChat()));
        if (!allowed) {
            throw new NotFoundResponse("Anexo não encontrado.");
        }
        Path file = AttachmentStore.path(attachment.getId());
        try {
            long size = Files.size(file);
            String encoded = URLEncoder.encode(attachment.getName(), StandardCharsets.UTF_8).replace("+", "%20");
            String disposition = MediaInfo.inline(attachment.getType()) ? "inline" : "attachment";
            ctx.header("X-Content-Type-Options", "nosniff");
            ctx.header("Cache-Control", "no-store");
            ctx.header("Content-Length", Long.toString(size));
            ctx.header("Content-Disposition", disposition + "; filename*=UTF-8''" + encoded);
            ctx.contentType(attachment.getType());
            ctx.result(Files.newInputStream(file));
        } catch (NoSuchFileException e) {
            throw new NotFoundResponse("Este anexo expirou.");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
