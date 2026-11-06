package br.com.angatusistemas.voicerelay.services;

import br.com.angatusistemas.lib.database.Saveable;
import br.com.angatusistemas.lib.gson.GsonAPI;
import br.com.angatusistemas.voicerelay.entities.GroupRecord;
import br.com.angatusistemas.voicerelay.entities.StoredMessage;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import io.javalin.websocket.WsConfig;
import io.javalin.websocket.WsContext;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Who is online, and getting frames from one person to another. It reads only what routing needs: who a frame is for.
 * The rest is passed along as it came, and every app still checks the signature on each message it receives.
 *
 * @author Angatu Sistemas
 */
public final class NetHub {

    public static final String PUBLIC_GROUP = "publico";
    public static final int CLOSE_REFUSED = 4403;
    public static final int CLOSE_REPLACED = 4409;
    public static final int CLOSE_FLOOD = 4429;

    private static final String TURN_HOST = "voice.angatusistemas.com.br";
    private static final Pattern PERSON_ID = Pattern.compile("[0-9a-f]{20}");
    private static final Pattern GROUP_ID = Pattern.compile("g_[0-9a-f]{16}");
    private static final Pattern TOKEN = Pattern.compile("[0-9a-f]{64}");
    private static final int MAX_SESSIONS = 512;
    private static final int MAX_CONNECTIONS_PER_ADDRESS = 32;
    private static final int MAX_GROUPS = 64;
    private static final int MAX_TEXT_BYTES = 128 * 1024;
    private static final int MAX_NAME = 32;
    private static final int FLOOD_FRAMES_PER_SECOND = 200;
    private static final long HELLO_TIMEOUT_MS = 10_000;
    private static final long PING_SECONDS = 25;
    private static final long IDLE_SECONDS = 90;
    // A dropped socket usually comes straight back: nobody sees that person leave unless they stay gone.
    private static final long OFFLINE_GRACE_MS = Long.getLong("angatu.offline.grace", 12_000);
    private static final long TURN_TTL_SECONDS = 7 * 86_400;
    private static final SecureRandom RANDOM = new SecureRandom();

    private static final Map<String, Session> ONLINE = new ConcurrentHashMap<>();
    private static final Map<String, Session> BY_SOCKET = new ConcurrentHashMap<>();
    private static final Map<String, Pending> PENDING = new ConcurrentHashMap<>();
    private static final Map<String, Integer> CONNECTIONS = new ConcurrentHashMap<>();

    private NetHub() {
    }

    public static void accept(WsConfig ws) {
        ws.onConnect(NetHub::onConnect);
        ws.onMessage(ctx -> onText(ctx, ctx.message()));
        ws.onClose(NetHub::onGone);
        ws.onError(NetHub::onGone);
    }

    public static int onlineCount() {
        return ONLINE.size();
    }

    private static void onConnect(WsContext ctx) {
        String address = StreamHub.clientAddress(ctx);
        if (IpBlocklist.isBlocked(address)) {
            ctx.closeSession(CLOSE_REFUSED, "refused");
            return;
        }
        ctx.attribute("address", address);
        if (CONNECTIONS.merge(address, 1, Integer::sum) > MAX_CONNECTIONS_PER_ADDRESS || BY_SOCKET.size() >= MAX_SESSIONS) {
            ctx.closeSession(CLOSE_FLOOD, "too many connections");
            return;
        }
        ctx.session.setMaxTextMessageSize(MAX_TEXT_BYTES);
        ctx.session.setIdleTimeout(java.time.Duration.ofSeconds(IDLE_SECONDS));
        ctx.enableAutomaticPings(PING_SECONDS, TimeUnit.SECONDS);
        byte[] nonce = new byte[32];
        RANDOM.nextBytes(nonce);
        String challenge = Base64.getEncoder().encodeToString(nonce);
        PENDING.put(ctx.sessionId(), new Pending(challenge));
        JsonObject hello = new JsonObject();
        hello.addProperty("t", "challenge");
        hello.addProperty("n", challenge);
        ctx.send(hello.toString());
        Thread.startVirtualThread(() -> {
            try {
                Thread.sleep(HELLO_TIMEOUT_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            if (PENDING.remove(ctx.sessionId()) != null && ctx.session.isOpen()) {
                ctx.closeSession(CLOSE_REFUSED, "no hello");
            }
        });
    }

    private static void onText(WsContext ctx, String text) {
        JsonObject frame;
        try {
            frame = GsonAPI.get().fromJson(text, JsonObject.class);
        } catch (JsonParseException e) {
            return;
        }
        if (frame == null || !frame.has("t")) {
            return;
        }
        Session session = BY_SOCKET.get(ctx.sessionId());
        if (session == null) {
            authenticate(ctx, frame);
        } else if (session.allow()) {
            dispatch(session, frame);
        } else {
            ctx.closeSession(CLOSE_FLOOD, "too many frames");
        }
    }

    private static void authenticate(WsContext ctx, JsonObject frame) {
        Pending pending = PENDING.remove(ctx.sessionId());
        if (pending == null || !"hello".equals(string(frame, "t"))) {
            ctx.closeSession(CLOSE_REFUSED, "bad hello");
            return;
        }
        byte[] pub;
        try {
            pub = Base64.getDecoder().decode(string(frame, "pub"));
            String signed = "AV3|auth|" + pending.challenge;
            if (pub.length != 44 || !verify(pub, signed.getBytes(StandardCharsets.UTF_8), Base64.getDecoder().decode(string(frame, "sig")))) {
                ctx.closeSession(CLOSE_REFUSED, "bad signature");
                return;
            }
        } catch (IllegalArgumentException e) {
            ctx.closeSession(CLOSE_REFUSED, "bad key");
            return;
        }
        String id = fingerprint(pub);
        Session session = new Session(ctx, id, string(frame, "pub"), clean(string(frame, "name")));
        session.groups.addAll(provenGroups(frame));
        Session previous = ONLINE.put(id, session);
        BY_SOCKET.put(ctx.sessionId(), session);
        boolean wasGone = previous == null;
        if (previous != null) {
            BY_SOCKET.remove(previous.ctx.sessionId());
            if (previous.ctx.session.isOpen()) {
                previous.ctx.closeSession(CLOSE_REPLACED, "connected elsewhere");
            }
        }
        ctx.send(welcome(session).toString());
        if (wasGone) {
            broadcastPresence(session);
        } else {
            announceChange(session);
        }
    }

    private static JsonObject welcome(Session session) {
        JsonArray peers = new JsonArray();
        ONLINE.values().stream().filter(other -> other != session).forEach(other -> peers.add(presence(other, session)));
        JsonObject welcome = new JsonObject();
        welcome.addProperty("t", "welcome");
        welcome.addProperty("id", session.id);
        welcome.add("peers", peers);
        JsonObject turn = turnCredentials(session.id);
        if (turn != null) {
            welcome.add("turn", turn);
        }
        return welcome;
    }

    /* The standard short-lived TURN credential: the TURN server recomputes this from the same secret, so nothing is stored. */
    private static JsonObject turnCredentials(String id) {
        String secret = System.getenv("TURN_SECRET");
        if (secret == null || secret.isBlank()) {
            return null;
        }
        String username = (System.currentTimeMillis() / 1000 + TURN_TTL_SECONDS) + ":" + id;
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA1"));
            JsonArray urls = new JsonArray();
            urls.add("turn:" + TURN_HOST + ":3479?transport=udp");
            urls.add("turn:" + TURN_HOST + ":3479?transport=tcp");
            JsonObject turn = new JsonObject();
            turn.add("urls", urls);
            turn.addProperty("username", username);
            turn.addProperty("credential", Base64.getEncoder().encodeToString(mac.doFinal(username.getBytes(StandardCharsets.UTF_8))));
            return turn;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Set<String> provenGroups(JsonObject frame) {
        Set<String> groups = new HashSet<>();
        groups.add(PUBLIC_GROUP);
        if (!frame.has("groups") || !frame.get("groups").isJsonArray()) {
            return groups;
        }
        int seen = 0;
        for (JsonElement el : frame.getAsJsonArray("groups")) {
            if (++seen > MAX_GROUPS || !el.isJsonObject()) {
                break;
            }
            String id = string(el.getAsJsonObject(), "id");
            String token = string(el.getAsJsonObject(), "token");
            if (!GROUP_ID.matcher(id).matches() || !TOKEN.matcher(token).matches()) {
                continue;
            }
            GroupRecord record = Saveable.findById(GroupRecord.class, id);
            if (record == null) {
                record = new GroupRecord();
                record.setId(id);
                record.setToken(token);
                record.save();
                groups.add(id);
            } else if (MessageDigest.isEqual(record.getToken().getBytes(StandardCharsets.UTF_8), token.getBytes(StandardCharsets.UTF_8))) {
                groups.add(id);
            }
        }
        return groups;
    }

    private static void dispatch(Session session, JsonObject frame) {
        switch (string(frame, "t")) {
            case "route" -> route(session, frame);
            case "msg" -> message(session, frame);
            case "sync" -> sync(session, frame);
            case "groups" -> {
                session.groups.clear();
                session.groups.addAll(provenGroups(frame));
                announceChange(session);
            }
            case "name" -> {
                session.name = clean(string(frame, "name"));
                announceChange(session);
            }
            case "ping" -> {
                JsonObject pong = new JsonObject();
                pong.addProperty("t", "pong");
                pong.add("n", frame.get("n"));
                session.ctx.send(pong.toString());
            }
            case "bye" -> {
                session.saidBye = true;
                session.ctx.closeSession();
            }
            default -> { }
        }
    }

    private static void route(Session from, JsonObject frame) {
        String to = string(frame, "to");
        JsonElement payload = frame.get("f");
        if (payload == null || !payload.isJsonObject()) {
            return;
        }
        String out = envelope(from.id, payload, 0).toString();
        if (PERSON_ID.matcher(to).matches()) {
            Session target = ONLINE.get(to);
            if (target != null && target.ctx.session.isOpen()) {
                target.ctx.send(out);
            }
        } else if ("*".equals(to) || from.groups.contains(to)) {
            for (Session other : ONLINE.values()) {
                if (other != from && other.ctx.session.isOpen() && ("*".equals(to) || other.groups.contains(to))) {
                    other.ctx.send(out);
                }
            }
        }
    }

    /* The sender's fields are only trusted for routing; the app at the other end verifies the signature over all of them. */
    private static void message(Session from, JsonObject frame) {
        JsonElement payload = frame.get("f");
        if (payload == null || !payload.isJsonObject()) {
            return;
        }
        JsonObject f = payload.getAsJsonObject();
        String chat = string(f, "chat");
        String to = string(f, "to");
        String id = string(f, "id");
        boolean direct = "dm".equals(chat);
        boolean routable = direct ? PERSON_ID.matcher(to).matches() : from.groups.contains(chat);
        if (!routable || !id.startsWith(from.id + ":") || id.length() > 80 || !from.id.equals(string(f, "from")) || !f.has("ts")) {
            return;
        }
        String stored = direct ? "dm:" + (from.id.compareTo(to) < 0 ? from.id + "+" + to : to + "+" + from.id) : chat;
        long seq = MessageStore.store(id, stored, direct ? to : "", from.id, f.get("ts").getAsLong(), f.toString());
        JsonObject confirmation = new JsonObject();
        confirmation.addProperty("t", "stored");
        confirmation.addProperty("id", id);
        from.ctx.send(confirmation.toString());
        String out = envelope(from.id, f, seq).toString();
        if (direct) {
            Session target = ONLINE.get(to);
            if (target != null && target.ctx.session.isOpen()) {
                target.ctx.send(out);
            }
        } else {
            for (Session other : ONLINE.values()) {
                if (other != from && other.ctx.session.isOpen() && other.groups.contains(chat)) {
                    other.ctx.send(out);
                }
            }
        }
    }

    private static void sync(Session session, JsonObject frame) {
        long since = frame.has("since") ? frame.get("since").getAsLong() : 0;
        for (StoredMessage m : MessageStore.since(since, session.id, Set.copyOf(session.groups))) {
            JsonObject f = GsonAPI.get().fromJson(m.getFrame(), JsonObject.class);
            session.ctx.send(envelope(m.getSender(), f, m.getSeq()).toString());
        }
        JsonObject done = new JsonObject();
        done.addProperty("t", "synced");
        session.ctx.send(done.toString());
    }

    private static JsonObject envelope(String from, JsonElement payload, long seq) {
        JsonObject out = new JsonObject();
        out.addProperty("t", "route");
        out.addProperty("from", from);
        if (seq > 0) {
            out.addProperty("seq", seq);
        }
        out.add("f", payload);
        return out;
    }

    private static void onGone(WsContext ctx) {
        String address = ctx.attribute("address");
        if (address != null && ctx.attribute("counted") == null) {
            ctx.attribute("counted", Boolean.TRUE);
            CONNECTIONS.computeIfPresent(address, (a, count) -> count <= 1 ? null : count - 1);
        }
        PENDING.remove(ctx.sessionId());
        Session session = BY_SOCKET.remove(ctx.sessionId());
        if (session == null || ONLINE.get(session.id) != session) {
            return;
        }
        Thread.startVirtualThread(() -> {
            if (!session.saidBye) {
                try {
                    Thread.sleep(OFFLINE_GRACE_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            if (ONLINE.remove(session.id, session)) {
                JsonObject offline = new JsonObject();
                offline.addProperty("t", "offline");
                offline.addProperty("id", session.id);
                String out = offline.toString();
                ONLINE.values().stream().filter(s -> s.ctx.session.isOpen()).forEach(s -> s.ctx.send(out));
            }
        });
    }

    private static void broadcastPresence(Session session) {
        for (Session other : ONLINE.values()) {
            if (other != session && other.ctx.session.isOpen()) {
                JsonObject online = new JsonObject();
                online.addProperty("t", "online");
                online.add("peer", presence(session, other));
                other.ctx.send(online.toString());
            }
        }
    }

    private static void announceChange(Session session) {
        for (Session other : ONLINE.values()) {
            if (other != session && other.ctx.session.isOpen()) {
                JsonObject changed = new JsonObject();
                changed.addProperty("t", "peer");
                changed.add("peer", presence(session, other));
                other.ctx.send(changed.toString());
            }
        }
    }

    /* Only the groups the two have in common: being in a private group is nobody else's business. */
    private static JsonObject presence(Session person, Session viewer) {
        JsonObject peer = new JsonObject();
        peer.addProperty("id", person.id);
        peer.addProperty("pub", person.pub);
        peer.addProperty("name", person.name);
        JsonArray shared = new JsonArray();
        person.groups.stream().filter(viewer.groups::contains).forEach(shared::add);
        peer.add("groups", shared);
        return peer;
    }

    private static boolean verify(byte[] pub, byte[] data, byte[] signature) {
        try {
            Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(pub)));
            verifier.update(data);
            return verifier.verify(signature);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            return false;
        }
    }

    static String fingerprint(byte[] pub) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(pub), 0, 10);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String clean(String name) {
        String stripped = name.replaceAll("[\\p{Cntrl}\\u202A-\\u202E\\u2066-\\u2069]", "").strip();
        if (stripped.isEmpty()) {
            return "Sem nome";
        }
        return stripped.length() > MAX_NAME ? stripped.substring(0, MAX_NAME) : stripped;
    }

    private static String string(JsonObject o, String key) {
        JsonElement el = o.get(key);
        return el != null && el.isJsonPrimitive() ? el.getAsString() : "";
    }

    private record Pending(String challenge) {
    }

    private static final class Session {

        private final WsContext ctx;
        private final String id;
        private final String pub;
        private final Set<String> groups = ConcurrentHashMap.newKeySet();
        private volatile String name;
        private volatile boolean saidBye;
        private long windowStart;
        private int windowFrames;

        Session(WsContext ctx, String id, String pub, String name) {
            this.ctx = ctx;
            this.id = id;
            this.pub = pub;
            this.name = name;
        }

        /* One socket delivers its frames one at a time, so this counter needs no lock. */
        boolean allow() {
            long now = System.currentTimeMillis();
            if (now - windowStart >= 1_000) {
                windowStart = now;
                windowFrames = 0;
            }
            return ++windowFrames <= FLOOD_FRAMES_PER_SECOND;
        }
    }
}
