package br.com.angatusistemas.voice.routes;

import br.com.angatusistemas.lib.connection.StatusCode;
import br.com.angatusistemas.lib.javalin.routes.Route;
import br.com.angatusistemas.lib.javalin.routes.RouteType;
import br.com.angatusistemas.voice.updater.UpdateService;
import io.javalin.http.ConflictResponse;
import io.javalin.http.Context;

/**
 * POST /api/update/install: applies a downloaded and verified update.
 *
 * @author Angatu Sistemas
 */
public final class UpdateInstallRoute extends Route {

    public UpdateInstallRoute() {
        super("/api/update/install", RouteType.POST, UpdateInstallRoute::handle);
    }

    private static void handle(Context ctx) {
        Guard.requireSession(ctx);
        try {
            UpdateService.install();
        } catch (IllegalStateException e) {
            throw new ConflictResponse("A atualização não pode ser instalada agora. Saia da chamada e tente de novo.");
        }
        ctx.status(StatusCode.ACCEPTED.code());
    }
}
