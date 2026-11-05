package br.com.angatusistemas.voice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.angatusistemas.lib.AngatuLib;
import br.com.angatusistemas.lib.database.Saveable;
import br.com.angatusistemas.voice.entities.RadminNetwork;
import br.com.angatusistemas.voice.services.Migrations;
import br.com.angatusistemas.voice.services.SessionService;
import br.com.angatusistemas.voice.services.radmin.DefaultNetworkService;
import br.com.angatusistemas.voice.services.radmin.NetworkDefaults;
import br.com.angatusistemas.voice.services.radmin.NetworkManager;
import br.com.angatusistemas.voice.utils.AppDirs;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Validates the automatic Radmin VPN network manager: default network constraints,
 * protection against deletion or leaving, persistence in SQLite and REST routes.
 *
 * @author Angatu Sistemas
 */
class RadminNetworkManagerTest {

    private static int port;
    private static HttpClient http;

    @BeforeAll
    static void setup() throws Exception {
        System.setProperty("angatu.radmin.ip", "127.0.0.98");
        System.setProperty("angatu.radmin.alias", "teste");
        System.setProperty("angatu.db", AppDirs.data().resolve("test-radmin-mgr.db").toString());

        try (ServerSocket probe = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            port = probe.getLocalPort();
        }
        Main.configure(port);
        Migrations.run();
        Migrations.indexes();
        new AngatuLib("localhost", port, false);
        http = HttpClient.newHttpClient();
    }

    @Test
    void defaultNetworkConstantsAreMandatoryAndProtected() {
        assertEquals("AngatuVoice", NetworkDefaults.DEFAULT_NETWORK_NAME);
        assertEquals("senha123", NetworkDefaults.DEFAULT_NETWORK_PASSWORD);
        assertTrue(NetworkDefaults.DEFAULT_NETWORK_REQUIRED);
        assertTrue(NetworkDefaults.DEFAULT_NETWORK_PROTECTED);

        assertTrue(NetworkDefaults.isProtected("AngatuVoice"));
        assertTrue(NetworkDefaults.isProtected("angatuvoice"));
        assertTrue(NetworkDefaults.isProtected(" ANGATUVOICE "));
        assertFalse(NetworkDefaults.isProtected("OutraRede"));
    }

    @Test
    void protectedNetworkBlocksForbiddenOperations() {
        assertThrows(UnsupportedOperationException.class, () -> DefaultNetworkService.validateOperation("AngatuVoice"));
        assertThrows(UnsupportedOperationException.class, () -> DefaultNetworkService.validateOperation("angatuvoice"));
        assertThrows(UnsupportedOperationException.class, () -> NetworkManager.leave("AngatuVoice"));
        assertThrows(UnsupportedOperationException.class, () -> NetworkManager.delete("AngatuVoice"));
    }

    @Test
    void defaultNetworkPersistedInDatabase() {
        NetworkManager.ensureDefaultNetwork();
        List<NetworkManager.NetworkView> list = NetworkManager.list();
        assertFalse(list.isEmpty(), "Networks list must not be empty");

        NetworkManager.NetworkView def = list.get(0);
        assertEquals("AngatuVoice", def.name());
        assertTrue(def.isDefault());
        assertEquals("default", def.kind());

        RadminNetwork dbRecord = Saveable.findById(RadminNetwork.class, "net_angatuvoice");
        assertNotNull(dbRecord);
        assertEquals("AngatuVoice", dbRecord.getName());
        assertTrue(dbRecord.isProtectedNetwork());
    }

    @Test
    void restApiProtectsDefaultNetworkAndManagesCustomNetworks() throws Exception {
        // Authenticate with session cookie via /boot
        HttpResponse<String> bootResp = http.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/boot?t=" + SessionService.newBootToken()))
                        .GET().build(),
                HttpResponse.BodyHandlers.ofString());

        String cookie = bootResp.headers().firstValue("Set-Cookie").orElseThrow();

        // 1. GET /api/networks
        HttpResponse<String> listResp = http.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/networks"))
                        .header("Cookie", cookie)
                        .GET().build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(200, listResp.statusCode());
        JsonArray nets = JsonParser.parseString(listResp.body()).getAsJsonArray();
        assertFalse(nets.isEmpty());

        JsonObject defaultNet = nets.get(0).getAsJsonObject();
        assertEquals("AngatuVoice", defaultNet.get("name").getAsString());
        assertTrue(defaultNet.get("isDefault").getAsBoolean());
        assertFalse(defaultNet.has("password"), "Passwords must never be exposed via REST");

        // 2. Attempt POST /api/networks/leave on default network -> must return 403 Forbidden
        HttpResponse<String> leaveForbidden = http.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/networks/leave"))
                        .header("Cookie", cookie)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("{\"name\":\"AngatuVoice\"}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(403, leaveForbidden.statusCode(), "Leaving AngatuVoice must be rejected with 403 Forbidden");

        // 3. Attempt DELETE /api/networks/AngatuVoice -> must return 403 Forbidden
        HttpResponse<String> deleteForbidden = http.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/networks/AngatuVoice"))
                        .header("Cookie", cookie)
                        .DELETE().build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(403, deleteForbidden.statusCode(), "Deleting AngatuVoice must be rejected with 403 Forbidden");

        // 4. POST /api/networks/reconnect -> must succeed
        HttpResponse<String> reconnectResp = http.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/networks/reconnect"))
                        .header("Cookie", cookie)
                        .POST(HttpRequest.BodyPublishers.noBody())
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(200, reconnectResp.statusCode());

        // 5. POST /api/networks/create with password < 6 chars -> must return 400 Bad Request
        HttpResponse<String> invalidCreate = http.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/networks/create"))
                        .header("Cookie", cookie)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("{\"name\":\"MinhaRede\",\"password\":\"123\"}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(400, invalidCreate.statusCode());
    }
}
