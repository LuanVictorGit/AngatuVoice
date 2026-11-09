package br.com.angatusistemas.voice.services;

import br.com.angatusistemas.lib.gson.GsonAPI;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Files that live on the server: attachments going up, and attachments and link-preview images coming down. The page never
 * talks to the server itself; it asks this app, which adds the session token in a header and passes the bytes through.
 *
 * @author Angatu Sistemas
 */
public final class RelayFiles {

    public static final long MAX_UPLOAD = 25L * 1024 * 1024;
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER).build();

    private RelayFiles() {
    }

    public static JsonObject upload(InputStream body, long length, String name, String type) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(PeerNetwork.httpBase() + "/api/upload"))
                .timeout(Duration.ofMinutes(5))
                .header("X-AV-Token", token())
                .header("X-AV-Name", URLEncoder.encode(name, StandardCharsets.UTF_8))
                .header("Content-Type", "application/octet-stream")
                .header("X-AV-Type", type)
                .POST(HttpRequest.BodyPublishers.fromPublisher(HttpRequest.BodyPublishers.ofInputStream(() -> body), length))
                .build();
        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        JsonObject json;
        try {
            json = GsonAPI.get().fromJson(response.body(), JsonObject.class);
        } catch (JsonParseException e) {
            json = null;
        }
        if (response.statusCode() / 100 != 2 || json == null) {
            // The server refuses in plain PT-BR text; anything longer or odd is not shown as is.
            String said = response.body() == null ? "" : response.body().strip();
            boolean readable = !said.isEmpty() && said.length() <= 200 && !said.startsWith("{") && !said.startsWith("<");
            throw new PeerNetwork.Refused(readable ? said : "Não foi possível enviar o arquivo.");
        }
        return json;
    }

    /* The caller copies status, type and disposition, then streams the body; the body is closed by whoever writes it out. */
    public static HttpResponse<InputStream> open(String path) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(PeerNetwork.httpBase() + path))
                .timeout(Duration.ofMinutes(5))
                .header("X-AV-Token", token())
                .GET().build();
        return HTTP.send(request, HttpResponse.BodyHandlers.ofInputStream());
    }

    private static String token() {
        String token = ChatService.httpToken();
        if (token.isEmpty() || !PeerNetwork.connected()) {
            throw new PeerNetwork.Offline();
        }
        return token;
    }
}
