package br.com.angatusistemas.voicerelay.routes;

import br.com.angatusistemas.lib.console.Console;
import br.com.angatusistemas.lib.javalin.routes.Route;
import br.com.angatusistemas.lib.javalin.routes.RouteType;
import br.com.angatusistemas.voicerelay.entities.StoredMessage;
import br.com.angatusistemas.voicerelay.entities.StoredMessage.Embed;
import br.com.angatusistemas.voicerelay.services.MessageStore;
import br.com.angatusistemas.voicerelay.services.NetHub;
import br.com.angatusistemas.voicerelay.services.SafeFetcher;
import br.com.angatusistemas.voicerelay.services.Session;
import io.javalin.http.BadGatewayResponse;
import io.javalin.http.Context;
import io.javalin.http.NotFoundResponse;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/**
 * GET /api/embed/{messageId}/{index}: a link preview's image, fetched by the server and passed straight through, so the
 * site never learns who is reading. Nothing is stored.
 *
 * @author Angatu Sistemas
 */
public final class EmbedRoute extends Route {

    private static final long MAX_IMAGE_BYTES = 8L * 1024 * 1024;
    private static final long TIMEOUT_MS = 15_000;

    public EmbedRoute() {
        super("/api/embed/{messageId}/{index}", RouteType.GET, EmbedRoute::handle);
    }

    private static void handle(Context ctx) {
        Session session = NetHub.httpSession(ctx);
        StoredMessage message = MessageStore.find(ctx.pathParam("messageId"));
        String index = ctx.pathParam("index");
        if (message == null || !session.canRead(message.getChat()) || !index.matches("[0-2]")) {
            throw new NotFoundResponse("Imagem não encontrada.");
        }
        List<Embed> embeds = message.getEmbeds();
        int at = Integer.parseInt(index);
        if (at >= embeds.size() || embeds.get(at).imageUrl() == null) {
            throw new NotFoundResponse("Imagem não encontrada.");
        }
        SafeFetcher.Fetched image;
        try {
            image = SafeFetcher.open(embeds.get(at).imageUrl(), "image/*", TIMEOUT_MS);
        } catch (IOException e) {
            throw new BadGatewayResponse("Não foi possível carregar a imagem da prévia.");
        }
        String type = image.mediaType();
        if (image.status() != 200 || !type.startsWith("image/") || type.contains("svg") || image.length() > MAX_IMAGE_BYTES) {
            close(image);
            throw new BadGatewayResponse("Não foi possível carregar a imagem da prévia.");
        }
        ctx.header("X-Content-Type-Options", "nosniff");
        ctx.header("Cache-Control", "no-store");
        ctx.contentType(type);
        ctx.result(new Capped(image.body(), MAX_IMAGE_BYTES));
    }

    private static void close(SafeFetcher.Fetched image) {
        try {
            image.close();
        } catch (IOException e) {
            Console.error("Could not close a refused preview image connection: " + e.getMessage());
        }
    }

    /* A body that runs past the cap is cut there: the headers are already sent, so ending early is the only refusal left. */
    private static final class Capped extends InputStream {

        private final InputStream in;
        private long left;

        Capped(InputStream in, long max) {
            this.in = in;
            this.left = max;
        }

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            return read(one, 0, 1) == -1 ? -1 : one[0] & 0xFF;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            if (left <= 0) {
                return -1;
            }
            int read = in.read(buffer, offset, (int) Math.min(length, left));
            if (read > 0) {
                left -= read;
            }
            return read;
        }

        @Override
        public void close() throws IOException {
            in.close();
        }
    }
}
