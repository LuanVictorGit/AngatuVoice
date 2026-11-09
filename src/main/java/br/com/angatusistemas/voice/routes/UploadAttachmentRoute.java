package br.com.angatusistemas.voice.routes;

import br.com.angatusistemas.lib.connection.StatusCode;
import br.com.angatusistemas.lib.javalin.routes.Route;
import br.com.angatusistemas.lib.javalin.routes.RouteType;
import br.com.angatusistemas.voice.services.RelayFiles;
import io.javalin.http.BadRequestResponse;
import io.javalin.http.Context;
import io.javalin.http.ServiceUnavailableResponse;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

/**
 * POST /api/attachments: passes one file from the page to the server as it arrives, without keeping it in memory.
 *
 * @author Angatu Sistemas
 */
public final class UploadAttachmentRoute extends Route {

    public UploadAttachmentRoute() {
        super("/api/attachments", RouteType.POST, UploadAttachmentRoute::handle);
    }

    private static void handle(Context ctx) {
        Guard.requireSession(ctx);
        long length = ctx.req().getContentLengthLong();
        String header = ctx.header("X-File-Name");
        String name = header == null ? "" : URLDecoder.decode(header, StandardCharsets.UTF_8).strip();
        String declared = ctx.header("X-File-Type");
        String type = declared == null || declared.isBlank() ? "application/octet-stream" : declared.strip();
        if (length <= 0 || name.isEmpty() || name.length() > 200 || type.length() > 100 || !type.matches("[\\w.+-]+/[\\w.+-]+(;[\\w=. -]*)?")) {
            throw new BadRequestResponse("Arquivo inválido.");
        }
        if (length > RelayFiles.MAX_UPLOAD) {
            throw new BadRequestResponse("O arquivo passa de 25 MB.");
        }
        String stored = ServerCall.ask(() -> {
            try (InputStream body = ctx.req().getInputStream()) {
                return RelayFiles.upload(body, length, name, type).toString();
            } catch (IOException e) {
                throw new ServiceUnavailableResponse("O envio do arquivo foi interrompido.");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ServiceUnavailableResponse("O envio do arquivo foi interrompido.");
            }
        });
        ctx.status(StatusCode.CREATED.code()).contentType("application/json").result(stored);
    }
}
