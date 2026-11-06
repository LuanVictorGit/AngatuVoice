package br.com.angatusistemas.voicerelay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.angatusistemas.lib.gson.GsonAPI;
import br.com.angatusistemas.voicerelay.services.MessageStore;
import br.com.angatusistemas.voicerelay.services.NetHub;
import com.google.gson.JsonObject;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The network channel on a real server and real sockets: proof of identity, presence, routing, offline delivery and purge.
 *
 * @author Angatu Sistemas
 */
class NetTest {

    private static String url;
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @BeforeAll
    static void start() throws Exception {
        System.setProperty("angatu.offline.grace", "200");
        int port;
        try (ServerSocket probe = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            port = probe.getLocalPort();
        }
        Main.start(port, 0);
        url = "ws://127.0.0.1:" + port + "/ws/net";
    }

    private static final class Client implements WebSocket.Listener {

        final BlockingQueue<JsonObject> frames = new LinkedBlockingQueue<>();
        final CompletableFuture<Integer> closed = new CompletableFuture<>();
        final StringBuilder partial = new StringBuilder();
        final KeyPair keys;
        final String id;
        WebSocket socket;

        Client() throws Exception {
            keys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
            id = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(keys.getPublic().getEncoded()), 0, 10);
        }

        static Client connected(String name, String groups) throws Exception {
            Client c = new Client();
            c.connect(name, groups, true);
            return c;
        }

        void connect(String name, String groups, boolean goodSignature) throws Exception {
            socket = HTTP.newWebSocketBuilder().buildAsync(URI.create(url), this).get(5, TimeUnit.SECONDS);
            JsonObject challenge = next("challenge");
            Signature signer = Signature.getInstance("Ed25519");
            signer.initSign(keys.getPrivate());
            signer.update(("AV3|auth|" + (goodSignature ? challenge.get("n").getAsString() : "other")).getBytes(StandardCharsets.UTF_8));
            String pub = Base64.getEncoder().encodeToString(keys.getPublic().getEncoded());
            String sig = Base64.getEncoder().encodeToString(signer.sign());
            socket.sendText("{\"t\":\"hello\",\"pub\":\"" + pub + "\",\"sig\":\"" + sig + "\",\"name\":\"" + name + "\",\"groups\":" + groups + "}", true).join();
        }

        void send(String json) {
            socket.sendText(json, true).join();
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

    private static String message(Client from, String to, String chat, String text, long ts) {
        String id = from.id + ":" + UUID.randomUUID();
        return "{\"t\":\"msg\",\"f\":{\"t\":\"msg\",\"id\":\"" + id + "\",\"chat\":\"" + chat + "\",\"to\":\"" + to + "\",\"from\":\""
                + from.id + "\",\"ts\":" + ts + ",\"text\":\"" + text + "\"}}";
    }

    @Test
    void aSignatureThatProvesNothingIsRefused() throws Exception {
        Client liar = new Client();
        liar.connect("liar", "[]", false);
        assertEquals(NetHub.CLOSE_REFUSED, liar.closed.get(5, TimeUnit.SECONDS));
    }

    @Test
    void peopleSeeEachOtherComeAndGo() throws Exception {
        Client a = Client.connected("Ana", "[]");
        a.next("welcome");
        Client b = Client.connected("Bia", "[]");
        JsonObject welcome = b.next("welcome");
        assertTrue(welcome.getAsJsonArray("peers").toString().contains(a.id), "the newcomer sees who is here");
        assertEquals(b.id, a.next("online").getAsJsonObject("peer").get("id").getAsString());
        b.send("{\"t\":\"bye\"}");
        assertEquals(b.id, a.next("offline").get("id").getAsString());
    }

    @Test
    void framesGoOnlyToWhoTheyAreForAndCarryTheRealSender() throws Exception {
        Client a = Client.connected("Ana", "[]");
        a.next("welcome");
        Client b = Client.connected("Bia", "[]");
        b.next("welcome");
        Client c = Client.connected("Cid", "[]");
        c.next("welcome");
        a.send("{\"t\":\"route\",\"to\":\"" + b.id + "\",\"f\":{\"t\":\"sig\",\"from\":\"forged\"}}");
        JsonObject got = b.next("route");
        assertEquals(a.id, got.get("from").getAsString());
        assertEquals("sig", got.getAsJsonObject("f").get("t").getAsString());
        assertTrue(c.frames.stream().noneMatch(f -> "route".equals(f.get("t").getAsString())));
    }

    @Test
    void privateGroupsNeedTheRightToken() throws Exception {
        String group = "g_" + HexFormat.of().formatHex(new byte[] {1, 2, 3, 4, 5, 6, 7, (byte) (System.nanoTime() & 0x7f)});
        String good = "[{\"id\":\"" + group + "\",\"token\":\"" + "a".repeat(64) + "\"}]";
        String bad = "[{\"id\":\"" + group + "\",\"token\":\"" + "b".repeat(64) + "\"}]";
        Client member = Client.connected("Ana", good);
        member.next("welcome");
        Client also = Client.connected("Bia", good);
        also.next("welcome");
        Client outsider = Client.connected("Cid", bad);
        outsider.next("welcome");
        member.send("{\"t\":\"route\",\"to\":\"" + group + "\",\"f\":{\"t\":\"call\"}}");
        assertEquals(member.id, also.next("route").get("from").getAsString());
        Thread.sleep(200);
        assertTrue(outsider.frames.stream().noneMatch(f -> "route".equals(f.get("t").getAsString())));
        outsider.send("{\"t\":\"route\",\"to\":\"" + group + "\",\"f\":{\"t\":\"call\"}}");
        Thread.sleep(200);
        assertEquals(0, member.frames.stream().filter(f -> "route".equals(f.get("t").getAsString())).count());
    }

    @Test
    void someoneOfflineReceivesWhatTheyMissed() throws Exception {
        Client a = Client.connected("Ana", "[]");
        a.next("welcome");
        Client b = new Client();
        a.send(message(a, b.id, "dm", "oi", System.currentTimeMillis()));
        Thread.sleep(200);
        b.connect("Bia", "[]", true);
        b.next("welcome");
        b.send("{\"t\":\"sync\",\"since\":0}");
        JsonObject got = b.next("route");
        assertEquals("oi", got.getAsJsonObject("f").get("text").getAsString());
        assertEquals(a.id, got.get("from").getAsString());
        assertTrue(got.get("seq").getAsLong() > 0);
        b.next("synced");
        long seq = got.get("seq").getAsLong();
        b.send("{\"t\":\"sync\",\"since\":" + seq + "}");
        Thread.sleep(200);
        assertFalse(b.frames.stream().anyMatch(f -> "route".equals(f.get("t").getAsString())), "nothing twice");
    }

    @Test
    void theSameMessageSentAgainIsOneRow() {
        long first = MessageStore.store("x:same", "publico", "", "x", 1, "{}");
        long before = MessageStore.count();
        assertEquals(first, MessageStore.store("x:same", "publico", "", "x", 1, "{}"));
        assertEquals(before, MessageStore.count());
    }

    @Test
    void purgeRemovesOldMessagesAndKeepsRecentOnes() {
        long now = System.currentTimeMillis();
        String old = "old:" + UUID.randomUUID();
        String recent = "new:" + UUID.randomUUID();
        MessageStore.store(old, "publico", "", "x", now - 40L * 86_400_000, "{}");
        MessageStore.store(recent, "publico", "", "x", now, "{}");
        MessageStore.purge(now);
        assertFalse(br.com.angatusistemas.lib.database.Saveable.exists(br.com.angatusistemas.voicerelay.entities.StoredMessage.class, old));
        assertTrue(br.com.angatusistemas.lib.database.Saveable.exists(br.com.angatusistemas.voicerelay.entities.StoredMessage.class, recent));
    }

    @Test
    void aChatKeepsOnlyItsNewestMessages() {
        long now = System.currentTimeMillis();
        String chat = "dm:cap" + UUID.randomUUID();
        String first = "cap:" + UUID.randomUUID();
        MessageStore.store(first, chat, "", "x", now, "{}");
        for (int i = 0; i < 1000; i++) {
            MessageStore.store("cap:" + UUID.randomUUID(), chat, "", "x", now, "{}");
        }
        MessageStore.purge(now);
        assertFalse(br.com.angatusistemas.lib.database.Saveable.exists(br.com.angatusistemas.voicerelay.entities.StoredMessage.class, first));
        assertEquals(1000, br.com.angatusistemas.lib.database.Saveable.query(br.com.angatusistemas.voicerelay.entities.StoredMessage.class,
                "SELECT data FROM " + br.com.angatusistemas.lib.database.Saveable.tableName(br.com.angatusistemas.voicerelay.entities.StoredMessage.class)
                        + " WHERE json_extract(data,'$.chat')=?", chat).size());
    }
}
