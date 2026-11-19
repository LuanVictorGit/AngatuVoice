package br.com.angatusistemas.voice.routes;

import br.com.angatusistemas.lib.javalin.routes.Route;
import br.com.angatusistemas.lib.javalin.routes.RouteType;
import br.com.angatusistemas.voice.services.SoundboardService;
import io.javalin.http.BadRequestResponse;
import io.javalin.http.Context;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;

/**
 * PUT /api/soundboard/{slot} puts an audio file of the person's own in a slot; DELETE gives the slot its original sound back.
 *
 * @author Angatu Sistemas
 */
public final class UpdateSoundboardRoute extends Route {

    public UpdateSoundboardRoute() {
        super("/api/soundboard/{slot}", RouteType.PUT, UpdateSoundboardRoute::handle);
    }

    private static void handle(Context ctx) {
        Guard.requireSession(ctx);
        int slot = SoundboardSlot.of(ctx);
        long length = ctx.req().getContentLengthLong();
        if (length <= 0 || length > SoundboardService.MAX_BYTES) {
            throw new BadRequestResponse("Escolha um áudio de até 2 MB.");
        }
        try (InputStream body = ctx.req().getInputStream()) {
            SoundboardService.store(slot, body.readNBytes((int) SoundboardService.MAX_BYTES + 1));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        ctx.status(204);
    }
}
