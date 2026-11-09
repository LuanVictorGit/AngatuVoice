package br.com.angatusistemas.voicerelay;

import br.com.angatusistemas.lib.gson.GsonAPI;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingDeque;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A real app connection for tests: an Ed25519 identity, the challenge signed like the app does, and every frame it
 * receives kept in order. Also starts the real server on a free port with a database of its own.
 *
 * @author Angatu Sistemas
 */
final class TestClient implements WebSocket.Listener {

    static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final AtomicInteger REQUESTS = new AtomicInteger();
    static String base;

    final BlockingDeque<JsonObject> frames = new LinkedBlockingDeque<>();
    final CompletableFuture<Integer> closed = new CompletableFuture<>();
    final StringBuilder partial = new StringBuilder();
    final KeyPair keys;
    final String id;
    final String device;
    WebSocket socket;
    JsonObject welcome;

    /* One fresh database and attachment folder per test class: surefire gives each class its own JVM. */
    static void startServer() throws IOException {
        Path dir = Files.createTempDirectory("relay-test");
        System.setProperty("angatu.db", dir.resolve("database.db").toString());
        System.setProperty("angatu.attachments", dir.resolve("attachments").toString());
        System.setProperty("angatu.offline.grace", "200");
        System.setProperty("angatu.max.per.address", "512");
        int port;
        try (ServerSocket probe = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            port = probe.getLocalPort();
        }
        Main.start(port, 0);
        base = "127.0.0.1:" + port;
    }

    TestClient() throws Exception {
        this(KeyPairGenerator.getInstance("Ed25519").generateKeyPair(), HexFormat.of().formatHex(UUID.randomUUID().toString().getBytes(), 0, 8));
    }

    TestClient(KeyPair keys, String device) throws Exception {
        this.keys = keys;
        this.device = device;
        id = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(keys.getPublic().getEncoded()), 0, 10);
    }

    static TestClient modern(String name, String groups) throws Exception {
        TestClient c = new TestClient();
        c.connect(name, groups, true, true);
        return c;
    }

    static TestClient legacy(String name, String groups) throws Exception {
        TestClient c = new TestClient();
        c.connect(name, groups, true, false);
        return c;
    }

    TestClient secondDevice() throws Exception {
        return new TestClient(keys, HexFormat.of().formatHex(UUID.randomUUID().toString().getBytes(), 0, 8));
    }

    TestClient connect(String name, String groups, boolean goodSignature, boolean modern) throws Exception {
        return connect(name, groups, goodSignature, modern, null);
    }

    TestClient connect(String name, String groups, boolean goodSignature, boolean modern, String forwardedFor) throws Exception {
        WebSocket.Builder builder = HTTP.newWebSocketBuilder();
        if (forwardedFor != null) {
            builder.header("X-Forwarded-For", forwardedFor);
        }
        socket = builder.buildAsync(URI.create("ws://" + base + "/ws/net"), this).get(5, TimeUnit.SECONDS);
        JsonObject challenge = next("challenge");
        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(keys.getPrivate());
        signer.update(("AV3|auth|" + (goodSignature ? challenge.get("n").getAsString() : "other")).getBytes(StandardCharsets.UTF_8));
        String pub = Base64.getEncoder().encodeToString(keys.getPublic().getEncoded());
        String sig = Base64.getEncoder().encodeToString(signer.sign());
        String extra = modern ? ",\"proto\":2,\"device\":\"" + device + "\"" : "";
        socket.sendText("{\"t\":\"hello\",\"pub\":\"" + pub + "\",\"sig\":\"" + sig + "\",\"name\":\"" + name + "\",\"groups\":" + groups
                + extra + "}", true).join();
        return this;
    }

    TestClient ready() throws InterruptedException {
        welcome = next("welcome");
        return this;
    }

    void send(String json) {
        socket.sendText(json, true).join();
    }

    /* Sends a request and waits for its own answer, leaving every other frame in the queue. */
    JsonObject ask(String type, String fields) throws InterruptedException {
        String r = "r" + REQUESTS.incrementAndGet();
        send("{\"t\":\"" + type + "\",\"r\":\"" + r + "\"" + (fields.isEmpty() ? "" : "," + fields) + "}");
        List<JsonObject> skipped = new ArrayList<>();
        try {
            long deadline = System.currentTimeMillis() + 10_000;
            while (System.currentTimeMillis() < deadline) {
                JsonObject f = frames.poll(100, TimeUnit.MILLISECONDS);
                if (f == null) {
                    continue;
                }
                if ("res".equals(f.get("t").getAsString()) && r.equals(f.get("r").getAsString())) {
                    return f;
                }
                skipped.add(f);
            }
            throw new AssertionError("no answer to " + type);
        } finally {
            for (int i = skipped.size() - 1; i >= 0; i--) {
                frames.addFirst(skipped.get(i));
            }
        }
    }

    JsonObject next(String type) throws InterruptedException {
        for (int i = 0; i < 50; i++) {
            JsonObject f = frames.poll(200, TimeUnit.MILLISECONDS);
            if (f != null && type.equals(f.get("t").getAsString())) {
                return f;
            }
        }
        throw new AssertionError("no frame of type " + type);
    }

    List<JsonObject> until(String type) throws InterruptedException {
        List<JsonObject> seen = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            JsonObject f = frames.poll(200, TimeUnit.MILLISECONDS);
            if (f != null) {
                seen.add(f);
                if (type.equals(f.get("t").getAsString())) {
                    return seen;
                }
            }
        }
        throw new AssertionError("no frame of type " + type);
    }

    boolean received(String type, long waitMs) throws InterruptedException {
        Thread.sleep(waitMs);
        return frames.stream().anyMatch(f -> type.equals(f.get("t").getAsString()));
    }

    HttpResponse<byte[]> upload(String name, String type, byte[] body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://" + base + "/api/upload"))
                .header("X-AV-Token", welcome.get("http").getAsString())
                .header("X-AV-Name", java.net.URLEncoder.encode(name, StandardCharsets.UTF_8))
                .header("Content-Type", type)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
        return HTTP.send(request, HttpResponse.BodyHandlers.ofByteArray());
    }

    /* As the proxy in production asks: a compressed answer must still be complete. */
    HttpResponse<byte[]> getGzip(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://" + base + path))
                .header("X-AV-Token", welcome.get("http").getAsString()).header("Accept-Encoding", "gzip").GET().build();
        return HTTP.send(request, HttpResponse.BodyHandlers.ofByteArray());
    }

    HttpResponse<byte[]> get(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://" + base + path))
                .header("X-AV-Token", welcome.get("http").getAsString()).GET().build();
        return HTTP.send(request, HttpResponse.BodyHandlers.ofByteArray());
    }

    static JsonObject json(byte[] body) {
        return GsonAPI.get().fromJson(new String(body, StandardCharsets.UTF_8), JsonObject.class);
    }

    @Override
    public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
        partial.append(data);
        if (last) {
            frames.add(GsonAPI.get().fromJson(partial.toString(), JsonObject.class));
            partial.setLength(0);
        }
        ws.request(1);
        return null;
    }

    @Override
    public CompletionStage<?> onClose(WebSocket ws, int code, String reason) {
        closed.complete(code);
        return null;
    }
}
