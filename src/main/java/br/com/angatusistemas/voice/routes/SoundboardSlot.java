package br.com.angatusistemas.voice.routes;

import br.com.angatusistemas.voice.services.SoundboardService;
import io.javalin.http.BadRequestResponse;
import io.javalin.http.Context;

/**
 * Reads the {slot} of the sound wheel routes, refusing anything that is not one of the eight.
 *
 * @author Angatu Sistemas
 */
final class SoundboardSlot {

    private SoundboardSlot() {
    }

    static int of(Context ctx) {
        String raw = ctx.pathParam("slot");
        if (!raw.matches("[0-9]")) {
            throw new BadRequestResponse("Espaço de som inválido.");
        }
        int slot = Integer.parseInt(raw);
        if (!SoundboardService.validSlot(slot)) {
            throw new BadRequestResponse("Espaço de som inválido.");
        }
        return slot;
    }
}
