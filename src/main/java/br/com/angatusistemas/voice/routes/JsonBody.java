package br.com.angatusistemas.voice.routes;

import br.com.angatusistemas.lib.gson.GsonAPI;
import com.google.gson.JsonParseException;
import io.javalin.http.BadRequestResponse;
import io.javalin.http.Context;

/**
 * Reads a JSON request body into a record, turning malformed or missing input into a 400.
 *
 * @author Angatu Sistemas
 */
public final class JsonBody {

    private JsonBody() {
    }

    public static <T> T read(Context ctx, Class<T> type) {
        T body;
        try {
            body = GsonAPI.get().fromJson(ctx.body(), type);
        } catch (JsonParseException e) {
            throw new BadRequestResponse("Os dados enviados estão em um formato inválido.");
        }
        if (body == null) {
            throw new BadRequestResponse("Nenhum dado foi enviado.");
        }
        return body;
    }
}