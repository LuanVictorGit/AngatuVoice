package br.com.angatusistemas.voice.services;

import br.com.angatusistemas.lib.gson.GsonAPI;
import br.com.angatusistemas.voice.utils.Crypto;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.ProtocolException;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * One authenticated, encrypted connection to a peer: a signed X25519 handshake, then AES-GCM frames with strict counters.
 *
 * @author Angatu Sistemas
 */
final class PeerLink implements AutoCloseable {

    static final int MAX_FRAME = 256 * 1024;
    private static final int MAX_HELLO = 2048;
    private static final long CLOCK_SKEW_MS = 10 * 60_000L;
    private static final int KEY_BYTES = 44;
    private static final int RATE_PER_SECOND = 120;

    final Socket socket;
    final boolean initiator;
    final String peerId;
    final byte[] peerKey;
    final String peerName;
    final String peerAlias;
    final int peerPort;
    final byte[] transcript;
    final Set<String> groups = ConcurrentHashMap.newKeySet();
    volatile long lastRx = System.currentTimeMillis();

    private final DataInputStream in;
    private final DataOutputStream out;
    private final byte[] sendKey;
    private final byte[] recvKey;
    private final int sendDirection;
    private final int recvDirection;
    private long sendCounter;
    private long recvCounter;
    private long windowStart;
    private int windowCount;

    private PeerLink(Socket socket, boolean initiator, DataInputStream in, DataOutputStream out, Hello remote,
                     byte[] transcript, byte[] shared) {
        this.socket = socket;
        this.initiator = initiator;
        this.in = in;
        this.out = out;
        this.peerId = remote.id;
        this.peerKey = remote.key;
        this.peerName = remote.name;
        this.peerAlias = remote.alias;
        this.peerPort = remote.port;
        this.transcript = transcript;
        byte[] initToResp = Crypto.sha256("AV1|i2r".getBytes(StandardCharsets.UTF_8), shared, transcript);
        byte[] respToInit = Crypto.sha256("AV1|r2i".getBytes(StandardCharsets.UTF_8), shared, transcript);
        this.sendKey = initiator ? initToResp : respToInit;
        this.recvKey = initiator ? respToInit : initToResp;
        this.sendDirection = initiator ? 0 : 1;
        this.recvDirection = initiator ? 1 : 0;
    }

    static PeerLink handshake(Socket socket, boolean initiator, IdentityService.Self self, String alias, int listenPort)
            throws IOException {
        DataInputStream in = new DataInputStream(socket.getInputStream());
        DataOutputStream out = new DataOutputStream(socket.getOutputStream());
        KeyPair eph = Crypto.newAgreementKeys();
        byte[] ephPublic = eph.getPublic().getEncoded();
        writeRaw(out, hello(self, alias, listenPort, ephPublic));
        Hello remote = verifyHello(readRaw(in, MAX_HELLO), self.id());
        byte[] transcript = initiator ? Crypto.sha256(ephPublic, remote.eph) : Crypto.sha256(remote.eph, ephPublic);
        byte[] shared;
        try {
            shared = Crypto.agree(eph.getPrivate(), remote.eph);
        } catch (IllegalArgumentException e) {
            throw new ProtocolException("key agreement refused");
        }
        return new PeerLink(socket, initiator, in, out, remote, transcript, shared);
    }

    synchronized void send(JsonObject frame) throws IOException {
        byte[] plain = GsonAPI.get().toJson(frame).getBytes(StandardCharsets.UTF_8);
        if (plain.length > MAX_FRAME) {
            throw new IOException("frame too large");
        }
        byte[] sealed = Crypto.seal(sendKey, nonce(sendDirection, sendCounter++), plain);
        out.writeInt(sealed.length);
        out.write(sealed);
        out.flush();
    }

    JsonObject receive() throws IOException {
        byte[] sealed = readRaw(in, MAX_FRAME + 16);
        byte[] plain;
        try {
            plain = Crypto.open(recvKey, nonce(recvDirection, recvCounter++), sealed);
        } catch (IllegalArgumentException e) {
            throw new ProtocolException("frame authentication failed");
        }
        lastRx = System.currentTimeMillis();
        try {
            JsonObject frame = GsonAPI.get().fromJson(new String(plain, StandardCharsets.UTF_8), JsonObject.class);
            if (frame == null) {
                throw new ProtocolException("empty frame");
            }
            return frame;
        } catch (JsonParseException e) {
            throw new ProtocolException("malformed frame");
        }
    }

    boolean allow() {
        long now = System.currentTimeMillis();
        if (now - windowStart >= 1000) {
            windowStart = now;
            windowCount = 0;
        }
        return ++windowCount <= RATE_PER_SECOND;
    }

    byte[] memberTag(byte[] secret, String ownerId) {
        byte[] data = Crypto.sha256("member".getBytes(StandardCharsets.UTF_8), transcript, ownerId.getBytes(StandardCharsets.UTF_8));
        return Crypto.hmac(secret, data);
    }

    @Override
    public void close() {
        try {
            socket.close();
        } catch (IOException ignored) {
            // the peer may already have dropped the connection
        }
    }

    private static byte[] hello(IdentityService.Self self, String alias, int port, byte[] eph) {
        long ts = System.currentTimeMillis();
        String pub = Crypto.b64(self.publicKey());
        String ephB64 = Crypto.b64(eph);
        String nonce = Crypto.b64(Crypto.random(12));
        String signed = signedText(pub, ephB64, self.name(), alias, port, ts, nonce);
        JsonObject o = new JsonObject();
        o.addProperty("v", 1);
        o.addProperty("pub", pub);
        o.addProperty("eph", ephB64);
        o.addProperty("name", self.name());
        o.addProperty("alias", alias);
        o.addProperty("port", port);
        o.addProperty("ts", ts);
        o.addProperty("nonce", nonce);
        o.addProperty("sig", Crypto.b64(self.sign(signed.getBytes(StandardCharsets.UTF_8))));
        return GsonAPI.get().toJson(o).getBytes(StandardCharsets.UTF_8);
    }

    private static Hello verifyHello(byte[] raw, String ownId) throws IOException {
        try {
            JsonObject o = GsonAPI.get().fromJson(new String(raw, StandardCharsets.UTF_8), JsonObject.class);
            byte[] pub = Crypto.unb64(o.get("pub").getAsString());
            byte[] eph = Crypto.unb64(o.get("eph").getAsString());
            String name = IdentityService.clean(o.get("name").getAsString());
            String alias = IdentityService.clean(o.get("alias").getAsString());
            int port = o.get("port").getAsInt();
            long ts = o.get("ts").getAsLong();
            String nonce = o.get("nonce").getAsString();
            String id = Crypto.fingerprint(pub);
            String signed = signedText(o.get("pub").getAsString(), o.get("eph").getAsString(), o.get("name").getAsString(),
                    o.get("alias").getAsString(), port, ts, nonce);
            boolean valid = o.get("v").getAsInt() == 1 && pub.length == KEY_BYTES && eph.length == KEY_BYTES
                    && !name.isEmpty() && port > 0 && port < 65536 && !id.equals(ownId)
                    && Math.abs(System.currentTimeMillis() - ts) <= CLOCK_SKEW_MS
                    && Crypto.verify(pub, signed.getBytes(StandardCharsets.UTF_8), Crypto.unb64(o.get("sig").getAsString()));
            if (!valid) {
                throw new ProtocolException("hello rejected");
            }
            return new Hello(id, pub, eph, name, alias, port);
        } catch (JsonParseException | IllegalArgumentException | NullPointerException | UnsupportedOperationException
                 | IllegalStateException e) {
            throw new ProtocolException("malformed hello");
        }
    }

    static String signedText(String pub, String eph, String name, String alias, int port, long ts, String nonce) {
        return String.join("|", "AV1", "hello", pub, eph, name, alias, String.valueOf(port), String.valueOf(ts), nonce);
    }

    private static byte[] nonce(int direction, long counter) {
        return ByteBuffer.allocate(12).putInt(direction).putLong(counter).array();
    }

    private static void writeRaw(DataOutputStream out, byte[] data) throws IOException {
        out.writeInt(data.length);
        out.write(data);
        out.flush();
    }

    private static byte[] readRaw(DataInputStream in, int max) throws IOException {
        int length = in.readInt();
        if (length < 0 || length > max) {
            throw new ProtocolException("frame length out of bounds");
        }
        byte[] data = new byte[length];
        in.readFully(data);
        return data;
    }

    private record Hello(String id, byte[] key, byte[] eph, String name, String alias, int port) {
    }
}
