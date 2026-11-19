package br.com.angatusistemas.voice.routes;

import br.com.angatusistemas.lib.javalin.routes.Route;
import br.com.angatusistemas.lib.javalin.routes.RouteType;
import br.com.angatusistemas.voice.services.SoundboardService;
import io.javalin.http.Context;
import io.javalin.http.NotFoundResponse;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * GET /api/soundboard/{slot}: the person's own audio file for one slot of the sound wheel.
 *
 * @author Angatu Sistemas
 */
public final class SoundboardFileRoute extends Route {

    public SoundboardFileRoute() {
        super("/api/soundboard/{slot}", RouteType.GET, SoundboardFileRoute::handle);
    }

    private static void handle(Context ctx) {
        Guard.requireSession(ctx);
        Path file = SoundboardService.read(SoundboardSlot.of(ctx)).orElseThrow(() -> new NotFoundResponse("Este espaço usa o som original."));
        try {
            ctx.header("X-Content-Type-Options", "nosniff");
            ctx.header("Cache-Control", "no-store");
            ctx.contentType("application/octet-stream").result(Files.readAllBytes(file));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
