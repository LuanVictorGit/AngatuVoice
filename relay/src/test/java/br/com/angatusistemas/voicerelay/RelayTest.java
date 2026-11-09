package br.com.angatusistemas.voicerelay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import br.com.angatusistemas.voicerelay.services.StreamHub;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The relay on its real server and real sockets: frames reach viewers intact, and each refusal closes with its own code.
 *
 * @author Angatu Sistemas
 */
class RelayTest {

    private static String url;
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @BeforeAll
    static void start() throws Exception {
        TestClient.startServer();
        url = "ws://" + TestClient.base + "/ws/stream";
    }

    private static String id() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private static final class Client implements WebSocket.Listener {

        final BlockingQueue<String> texts = new LinkedBlockingQueue<>();
        final BlockingQueue<byte[]> frames = new LinkedBlockingQueue<>();
        final CompletableFuture<Integer> closed = new CompletableFuture<>();
        WebSocket socket;

        static Client open() throws Exception {
            Client c = new Client();
            c.socket = HTTP.newWebSocketBuilder().buildAsync(URI.create(url), c).get(5, TimeUnit.SECONDS);
            return c;
        }

        Client hello(String role, String stream) {
            socket.sendText("{\"role\":\"" + role + "\",\"stream\":\"" + stream + "\"}", true);
            return this;
        }

        void frame(boolean key, String body) {
            byte[] text = body.getBytes();
            ByteBuffer out = ByteBuffer.allocate(text.length + 1);
            out.put((byte) (key ? 1 : 0)).put(text).flip();
            socket.sendBinary(out, true).join();
        }

        String nextText() throws InterruptedException {
            return texts.poll(5, TimeUnit.SECONDS);
        }

        @Override
        public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
            texts.add(data.toString());
            ws.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onBinary(WebSocket ws, ByteBuffer data, boolean last) {
            byte[] copy = new byte[data.remaining()];
            data.get(copy);
            frames.add(copy);
            ws.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket ws, int code, String reason) {
            closed.complete(code);
            return null;
        }
    }

    @Test
    void framesReachEveryViewerFromTheirFirstKeyframe() throws Exception {
        String stream = id();
        Client publisher = Client.open().hello("publish", stream);
        assertEquals("ready", publisher.nextText());
        Client a = Client.open().hello("watch", stream);
        Client b = Client.open().hello("watch", stream);
        assertEquals("ready", a.nextText());
        assertEquals("ready", b.nextText());
        assertEquals("key", publisher.nextText(), "a new viewer asks for a keyframe");

        publisher.frame(false, "cannot be decoded yet");
        publisher.frame(true, "key");
        publisher.frame(false, "delta");

        for (Client viewer : new Client[] {a, b}) {
            byte[] first = viewer.frames.poll(5, TimeUnit.SECONDS);
            assertNotNull(first);
            assertEquals("key", new String(first, 1, first.length - 1), "nothing before the keyframe");
            byte[] second = viewer.frames.poll(5, TimeUnit.SECONDS);
            assertNotNull(second);
            assertEquals("delta", new String(second, 1, second.length - 1));
        }

        Thread.sleep(1_100);
        a.socket.sendText("key", true);
        assertEquals("key", publisher.nextText(), "a viewer can ask for a keyframe");

        publisher.socket.sendClose(WebSocket.NORMAL_CLOSURE, "done");
        assertEquals(StreamHub.CLOSE_NO_STREAM, a.closed.get(5, TimeUnit.SECONDS), "viewers learn the share ended");
    }

    @Test
    void watchingNothingAndTakingAnIdAreRefused() throws Exception {
        assertEquals(StreamHub.CLOSE_NO_STREAM, Client.open().hello("watch", id()).closed.get(5, TimeUnit.SECONDS));
        String stream = id();
        Client first = Client.open().hello("publish", stream);
        assertEquals("ready", first.nextText());
        assertEquals(StreamHub.CLOSE_ID_TAKEN, Client.open().hello("publish", stream).closed.get(5, TimeUnit.SECONDS));
        assertEquals(StreamHub.CLOSE_REFUSED, Client.open().hello("publish", "short").closed.get(5, TimeUnit.SECONDS));
    }

    @Test
    void aSocketThatNeverSaysHelloIsClosed() throws Exception {
        assertEquals(StreamHub.CLOSE_REFUSED, Client.open().closed.get(8, TimeUnit.SECONDS));
    }

    @Test
    void streamsDisappearWithTheirPublisher() throws Exception {
        int before = StreamHub.streamCount();
        Client publisher = Client.open().hello("publish", id());
        assertEquals("ready", publisher.nextText());
        assertEquals(before + 1, StreamHub.streamCount());
        publisher.socket.sendClose(WebSocket.NORMAL_CLOSURE, "done");
        publisher.closed.get(5, TimeUnit.SECONDS);
        for (int i = 0; i < 50 && StreamHub.streamCount() != before; i++) {
            Thread.sleep(100);
        }
        assertEquals(before, StreamHub.streamCount());
    }
}
