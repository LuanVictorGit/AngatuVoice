package br.com.angatusistemas.voice.routes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.angatusistemas.lib.AngatuLib;
import br.com.angatusistemas.lib.gson.GsonAPI;
import br.com.angatusistemas.voice.Main;
import br.com.angatusistemas.voice.services.Migrations;
import br.com.angatusistemas.voice.services.PeerNetwork;
import br.com.angatusistemas.voice.services.SessionService;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The call and settings routes against the real server: the session gate, the decline route, and the state and settings
 * contract the interface depends on.
 *
 * @author Angatu Sistemas
 */
class CallRoutesTest {

    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static int port;
    private static String cookie;

    @BeforeAll
    static void boot() throws Exception {
        System.setProperty("angatu.radmin.ip", "127.0.0.98");
        System.setProperty("angatu.radmin.alias", "teste");
        try (ServerSocket probe = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            port = probe.getLocalPort();
        }
        Main.configure(port);
        Migrations.run();
        Migrations.indexes();
        new AngatuLib("localhost", port, false);
        PeerNetwork.start();
        HttpResponse<String> boot = HTTP.send(HttpRequest.newBuilder(uri("/boot?t=" + SessionService.newBootToken())).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        cookie = boot.headers().firstValue("Set-Cookie").orElseThrow().split(";")[0];
    }

    @Test
    void decliningRequiresASession() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(uri("/api/call/decline"))
                .POST(HttpRequest.BodyPublishers.ofString("{\"room\":\"radmin\"}")).build();
        assertEquals(401, HTTP.send(request, HttpResponse.BodyHandlers.ofString()).statusCode());
    }

    @Test
    void decliningAnInventedRoomIsRefused() throws Exception {
        assertEquals(400, post("/api/call/decline", "{\"room\":\"dm:nada\"}").statusCode());
        assertEquals(400, post("/api/call/decline", "{}").statusCode());
    }

    @Test
    void decliningARoomThatIsNotRingingChangesNothing() throws Exception {
        assertEquals(204, post("/api/call/decline", "{\"room\":\"radmin\"}").statusCode());
    }

    @Test
    void theStateCarriesWhatTheCallScreenRenders() throws Exception {
        JsonObject state = GsonAPI.get().fromJson(get("/api/state").body(), JsonObject.class);
        assertTrue(state.get("incoming").isJsonArray());
        assertTrue(state.get("monitors").isJsonArray());
        assertTrue(state.get("peers").isJsonArray());
    }

    @Test
    void minimizingStaysOnTheTaskbarAndTheWindowOpensByDefault() throws Exception {
        JsonObject settings = GsonAPI.get().fromJson(get("/api/state").body(), JsonObject.class).getAsJsonObject("settings");
        assertEquals("false", settings.get("minimizeToTray").getAsString());
        assertEquals("true", settings.get("closeToTray").getAsString());
        assertEquals("false", settings.get("startMinimized").getAsString());
        assertEquals("true", settings.get("sounds.ring").getAsString());
    }

    @Test
    void theNewVideoAndMuteSettingsAreValidated() throws Exception {
        assertEquals(200, put("/api/settings", "{\"video.resolution\":\"native\"}").statusCode());
        assertEquals(200, put("/api/settings", "{\"video.priority\":\"motion\"}").statusCode());
        assertEquals(200, put("/api/settings", "{\"audio.peerMutes\":\"[\\\"abc\\\"]\"}").statusCode());
        assertEquals(400, put("/api/settings", "{\"video.resolution\":\"9000\"}").statusCode());
        assertEquals(400, put("/api/settings", "{\"video.priority\":\"tudo\"}").statusCode());
        assertEquals(400, put("/api/settings", "{\"audio.peerMutes\":\"nada\"}").statusCode());
    }

    private static HttpResponse<String> get(String path) throws IOException, InterruptedException {
        return HTTP.send(HttpRequest.newBuilder(uri(path)).header("Cookie", cookie).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> post(String path, String body) throws IOException, InterruptedException {
        return HTTP.send(HttpRequest.newBuilder(uri(path)).header("Cookie", cookie).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> put(String path, String body) throws IOException, InterruptedException {
        return HTTP.send(HttpRequest.newBuilder(uri(path)).header("Cookie", cookie).header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private static URI uri(String path) {
        return URI.create("http://127.0.0.1:" + port + path);
    }
}
