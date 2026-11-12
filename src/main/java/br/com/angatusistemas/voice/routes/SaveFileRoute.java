package br.com.angatusistemas.voice.routes;

import br.com.angatusistemas.lib.gson.GsonAPI;
import br.com.angatusistemas.lib.javalin.routes.Route;
import br.com.angatusistemas.lib.javalin.routes.RouteType;
import br.com.angatusistemas.voice.services.RelayFiles;
import io.javalin.http.BadRequestResponse;
import io.javalin.http.Context;
import io.javalin.http.NotFoundResponse;
import io.javalin.http.ServiceUnavailableResponse;
import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;

/**
 * POST /api/files/{id}/save: downloads an attachment into the person's Downloads folder and shows it in Explorer. The
 * Chromium inside the app has no download bar, so saving is this app's job.
 *
 * @author Angatu Sistemas
 */
public final class SaveFileRoute extends Route {

    private static final int MAX_COPIES = 100;

    public SaveFileRoute() {
        super("/api/files/{id}/save", RouteType.POST, SaveFileRoute::handle);
    }

    private static void handle(Context ctx) {
        Guard.requireSession(ctx);
        String id = ctx.pathParam("id");
        Body body = JsonBody.read(ctx, Body.class);
        if (!id.matches("[0-9a-zA-Z_-]{1,64}") || body.name() == null) {
            throw new BadRequestResponse("Arquivo inválido.");
        }
        Path target = ServerCall.ask(() -> {
            try {
                HttpResponse<InputStream> response = RelayFiles.open("/api/files/" + id);
                try (InputStream in = response.body()) {
                    if (response.statusCode() != 200) {
                        throw new NotFoundResponse("Arquivo indisponível.");
                    }
                    Path file = freeName(downloads(), safeName(body.name()));
                    Path partial = file.resolveSibling(file.getFileName() + ".part");
                    Files.copy(in, partial, StandardCopyOption.REPLACE_EXISTING);
                    Files.move(partial, file, StandardCopyOption.REPLACE_EXISTING);
                    return file;
                }
            } catch (IOException e) {
                throw new ServiceUnavailableResponse("Não foi possível salvar o arquivo.");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ServiceUnavailableResponse("Não foi possível salvar o arquivo.");
            }
        });
        try {
            new ProcessBuilder("explorer.exe", "/select,", target.toString()).start();
        } catch (IOException e) {
            br.com.angatusistemas.lib.console.Console.warn("Could not show the saved file: %s", e.getMessage());
        }
        ctx.contentType("application/json").result(GsonAPI.get().toJson(Map.of("path", target.toString())));
    }

    private static Path downloads() throws IOException {
        Path folder = Path.of(System.getProperty("user.home"), "Downloads");
        Files.createDirectories(folder);
        return folder;
    }

    /* The name comes from someone else: no folders, no reserved characters, never hidden or empty. */
    private static String safeName(String name) {
        String clean = name.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").replaceAll("^[.\\s]+|[.\\s]+$", "").strip();
        if (clean.isEmpty()) {
            clean = "arquivo";
        }
        return clean.length() > 150 ? clean.substring(clean.length() - 150) : clean;
    }

    private static Path freeName(Path folder, String name) {
        Path candidate = folder.resolve(name);
        int dot = name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        for (int i = 1; Files.exists(candidate) && i <= MAX_COPIES; i++) {
            candidate = folder.resolve(stem + " (" + i + ")" + ext);
        }
        if (Files.exists(candidate)) {
            throw new BadRequestResponse("Já existem muitos arquivos com esse nome em Downloads.");
        }
        return candidate;
    }

    private record Body(String name) {
    }
}
