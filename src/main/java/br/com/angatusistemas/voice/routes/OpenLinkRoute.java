package br.com.angatusistemas.voice.routes;

import br.com.angatusistemas.lib.connection.StatusCode;
import br.com.angatusistemas.lib.javalin.routes.Route;
import br.com.angatusistemas.lib.javalin.routes.RouteType;
import br.com.angatusistemas.voice.desktop.AppShell;
import io.javalin.http.BadRequestResponse;
import io.javalin.http.Context;
import java.net.URI;

/**
 * POST /api/system/open: opens an http(s) link from a message in the default browser, only on a click.
 *
 * @author Angatu Sistemas
 */
public final class OpenLinkRoute extends Route {

    public OpenLinkRoute() {
        super("/api/system/open", RouteType.POST, OpenLinkRoute::handle);
    }

    private static void handle(Context ctx) {
        Guard.requireSession(ctx);
        Body body = JsonBody.read(ctx, Body.class);
        URI uri;
        try {
            uri = URI.create(String.valueOf(body.url()));
        } catch (IllegalArgumentException e) {
            throw new BadRequestResponse("Endereço inválido.");
        }
        boolean web = uri.getHost() != null && ("https".equals(uri.getScheme()) || "http".equals(uri.getScheme()));
        if (!web || body.url().length() > 2000) {
            throw new BadRequestResponse("Só é possível abrir links http ou https.");
        }
        AppShell.browse(uri);
        ctx.status(StatusCode.NO_CONTENT.code());
    }

    private record Body(String url) {
    }
}
