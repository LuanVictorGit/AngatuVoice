package br.com.angatusistemas.voice.routes;

import br.com.angatusistemas.lib.console.Console;
import br.com.angatusistemas.voice.services.PeerNetwork;
import br.com.angatusistemas.voice.services.RelayFiles;
import io.javalin.http.BadRequestResponse;
import io.javalin.http.Context;
import io.javalin.http.NotFoundResponse;
import io.javalin.http.ServiceUnavailableResponse;
import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpResponse;
import java.util.function.Supplier;

/**
 * Turns the two ways a question to the server can end badly into answers the page shows: no connection, or a refusal in
 * the server's own words. Also passes server files through without holding them in memory.
 *
 * @author Angatu Sistemas
 */
final class ServerCall {

    private ServerCall() {
    }

    static <T> T ask(Supplier<T> question) {
        try {
            return question.get();
        } catch (PeerNetwork.Offline e) {
            throw new ServiceUnavailableResponse("Sem conexão com o servidor. Tente de novo em instantes.");
        } catch (PeerNetwork.Refused e) {
            throw new BadRequestResponse(e.getMessage());
        }
    }

    static void run(Runnable action) {
        ask(() -> {
            action.run();
            return null;
        });
    }

    static void stream(Context ctx, String path) {
        HttpResponse<InputStream> response = ask(() -> {
            try {
                return RelayFiles.open(path);
            } catch (IOException e) {
                throw new ServiceUnavailableResponse("Não foi possível falar com o servidor.");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ServiceUnavailableResponse("Não foi possível falar com o servidor.");
            }
        });
        if (response.statusCode() != 200) {
            try (InputStream refused = response.body()) {
                Console.debug("server file refused: " + response.statusCode() + " " + refused.available());
            } catch (IOException e) {
                Console.debug("server file refused: " + e.getMessage());
            }
            throw new NotFoundResponse("Arquivo indisponível.");
        }
        ctx.header("X-Content-Type-Options", "nosniff");
        ctx.header("Cache-Control", "no-store");
        response.headers().firstValue("Content-Type").ifPresent(ctx::contentType);
        response.headers().firstValue("Content-Disposition").ifPresent(value -> ctx.header("Content-Disposition", value));
        ctx.result(response.body());
    }
}
