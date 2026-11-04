package br.com.angatusistemas.voice.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.angatusistemas.voice.utils.Crypto;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.security.KeyPair;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

/**
 * The peer handshake and the encrypted frame channel, over real loopback sockets.
 *
 * @author Angatu Sistemas
 */
class PeerLinkTest {

    private static IdentityService.Self self(String name) {
        KeyPair keys = Crypto.newSigningKeys();
        byte[] pub = keys.getPublic().getEncoded();
        return new IdentityService.Self(Crypto.fingerprint(pub), pub, keys.getPrivate(), name);
    }

    private record Pair(PeerLink client, PeerLink server, IdentityService.Self a, IdentityService.Self b) {
    }

    private static Pair connect() throws Exception {
        IdentityService.Self a = self("Ana");
        IdentityService.Self b = self("Bruno");
        try (ServerSocket listener = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            CompletableFuture<PeerLink> server = CompletableFuture.supplyAsync(() -> {
                try {
                    return PeerLink.handshake(listener.accept(), false, b, "bruno", 47600);
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                }
            });
            Socket socket = new Socket(InetAddress.getLoopbackAddress(), listener.getLocalPort());
            PeerLink client = PeerLink.handshake(socket, true, a, "ana", 47601);
            return new Pair(client, server.get(), a, b);
        }
    }

    @Test
    void peersLearnEachOthersProvenIdentity() throws Exception {
        Pair p = connect();
        assertEquals(p.b.id(), p.client.peerId);
        assertEquals(p.a.id(), p.server.peerId);
        assertEquals("Bruno", p.client.peerName);
        assertEquals(47600, p.client.peerPort);
        assertTrue(Crypto.equal(p.client.transcript, p.server.transcript));
    }

    @Test
    void framesTravelEncryptedAndInOrder() throws Exception {
        Pair p = connect();
        JsonObject frame = new JsonObject();
        frame.addProperty("t", "ping");
        frame.addProperty("n", 1);
        p.client.send(frame);
        frame.addProperty("n", 2);
        p.client.send(frame);
        assertEquals(1, p.server.receive().get("n").getAsInt());
        assertEquals(2, p.server.receive().get("n").getAsInt());
    }

    @Test
    void membershipTagsMatchOnlyForTheSameSecret() throws Exception {
        Pair p = connect();
        byte[] secret = Crypto.random(16);
        assertTrue(Crypto.equal(p.client.memberTag(secret, p.a.id()), p.server.memberTag(secret, p.a.id())));
        assertTrue(!Crypto.equal(p.client.memberTag(secret, p.a.id()), p.server.memberTag(Crypto.random(16), p.a.id())));
    }

    @Test
    void queuedFramesKeepTheirOrderAndAreFlushedBeforeAnOrderlyClose() throws Exception {
        Pair p = connect();
        p.client.startWriter();
        for (int n = 0; n < 50; n++) {
            JsonObject frame = new JsonObject();
            frame.addProperty("n", n);
            assertTrue(p.client.enqueue(frame));
        }
        JsonObject bye = new JsonObject();
        bye.addProperty("t", "bye");
        p.client.closeAfter(bye);
        for (int n = 0; n < 50; n++) {
            assertEquals(n, p.server.receive().get("n").getAsInt());
        }
        assertEquals("bye", p.server.receive().get("t").getAsString());
        assertThrows(IOException.class, p.server::receive);
    }

    @Test
    void aFrameTooLargeIsDroppedWithoutHurtingTheLink() throws Exception {
        Pair p = connect();
        p.client.startWriter();
        JsonObject huge = new JsonObject();
        huge.addProperty("data", "x".repeat(PeerLink.MAX_FRAME + 1));
        assertTrue(!p.client.enqueue(huge));
        assertTrue(!p.client.isClosed());
    }

    @Test
    void latencyComesFromThePongAndExpires() throws Exception {
        Pair p = connect();
        long now = System.currentTimeMillis();
        assertEquals(PeerLink.NO_RTT, p.client.rtt(now), "nothing measured yet");
        p.client.recordPong(System.nanoTime() - 40_000_000L);
        int measured = p.client.rtt(System.currentTimeMillis());
        assertTrue(measured >= 40 && measured < 200, "measured " + measured);
        assertEquals(PeerLink.NO_RTT, p.client.rtt(System.currentTimeMillis() + 60_000), "a stale value is never shown");
    }

    @Test
    void aBurstIsAcceptedButAFloodIsCut() throws Exception {
        Pair p = connect();
        for (int n = 0; n < 3_000; n++) {
            assertTrue(p.server.allow(), "catching up after a reconnect sends a legitimate burst");
        }
        boolean cut = false;
        for (int n = 0; n < 5_000 && !cut; n++) {
            cut = !p.server.allow();
        }
        assertTrue(cut);
    }

    @Test
    void aTamperedFrameKillsTheLink() throws Exception {
        Pair p = connect();
        p.client.socket.getOutputStream().write(new byte[] {0, 0, 0, 20, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20});
        p.client.socket.getOutputStream().flush();
        assertThrows(IOException.class, p.server::receive);
    }
}