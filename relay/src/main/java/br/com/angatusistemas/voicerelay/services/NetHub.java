package br.com.angatusistemas.voicerelay.services;

import br.com.angatusistemas.lib.console.Console;
import br.com.angatusistemas.lib.database.Saveable;
import br.com.angatusistemas.lib.gson.GsonAPI;
import br.com.angatusistemas.lib.javalin.IP;
import br.com.angatusistemas.voicerelay.entities.GroupRecord;
import br.com.angatusistemas.voicerelay.utils.TextRules;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import io.javalin.http.Context;
import io.javalin.http.ForbiddenResponse;
import io.javalin.http.UnauthorizedResponse;
import io.javalin.websocket.WsConfig;
import io.javalin.websocket.WsContext;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Who is online and on which devices, and getting frames to them. Chat requests are answered by the chat and admin
 * services; call signalling is passed along as it came to the person's most recent connection, and every app still
 * checks the signature on what it receives.
 *
 * @author Angatu Sistemas
 */
public final class NetHub {

    public static final String PUBLIC_GROUP = "publico";
    public static final int CLOSE_REFUSED = 4403;
    public static final int CLOSE_REPLACED = 4409;
    public static final int CLOSE_KICKED = 4410;
    public static final int CLOSE_FLOOD = 4429;

    static final Pattern PERSON_ID = Pattern.compile("[0-9a-f]{20}");
    static final Pattern GROUP_ID = Pattern.compile("g_[0-9a-f]{16}");

    private static final String TURN_HOST = "voice.angatusistemas.com.br";
    private static final Pattern TOKEN = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern DEVICE = Pattern.compile("[0-9a-f]{16}");
    private static final int MAX_SESSIONS = 512;
    // One office or home behind one address is a handful of people; the tests open many more from loopback, hence the property.
    private static final int MAX_CONNECTIONS_PER_ADDRESS = Integer.getInteger("angatu.max.per.address", 32);
    private static final int MAX_GROUPS = 64;
    private static final int MAX_TEXT_BYTES = 128 * 1024;
    private static final int MAX_REQUEST_ID = 64;
    private static final long HELLO_TIMEOUT_MS = 10_000;
    private static final long PING_SECONDS = 25;
    private static final long IDLE_SECONDS = 90;
    // A dropped socket usually comes straight back: nobody sees that person leave unless they stay gone.
    private static final long OFFLINE_GRACE_MS = Long.getLong("angatu.offline.grace", 12_000);
    private static final long TURN_TTL_SECONDS = 7 * 86_400;
    private static final SecureRandom RANDOM = new SecureRandom();

    // Each person's connections, oldest first, as immutable lists replaced whole so readers never see a half-made change.
    private static final Map<String, List<Session>> PEOPLE = new ConcurrentHashMap<>();
    private static final Map<String, Session> BY_SOCKET = new ConcurrentHashMap<>();
    private static final Map<String, Session> BY_HTTP = new ConcurrentHashMap<>();
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
        return PEOPLE.size();
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
        ctx.session.setIdleTimeout(Duration.ofSeconds(IDLE_SECONDS));
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
        String address = ctx.attribute("address");
        if (AdminService.banned(id, address)) {
            ctx.send("{\"t\":\"banned\"}");
            ctx.closeSession(CLOSE_REFUSED, "banned");
            return;
        }
        int proto = frame.has("proto") && frame.get("proto").isJsonPrimitive() && frame.get("proto").getAsJsonPrimitive().isNumber()
                ? frame.get("proto").getAsInt() : 1;
        String device = DEVICE.matcher(string(frame, "device")).matches() ? string(frame, "device") : "";
        String http = proto >= 2 ? randomHex(32) : null;
        Session session = new Session(ctx, id, string(frame, "pub"), device, proto, address, http, TextRules.name(string(frame, "name")));
        session.groups.addAll(provenGroups(frame));
        List<Session> replaced = new ArrayList<>();
        boolean[] first = new boolean[1];
        PEOPLE.compute(id, (key, list) -> {
            List<Session> next = new ArrayList<>();
            first[0] = list == null || list.isEmpty();
            if (list != null) {
                for (Session old : list) {
                    (old.device.equals(device) ? replaced : next).add(old);
                }
            }
            next.add(session);
            return List.copyOf(next);
        });
        BY_SOCKET.put(ctx.sessionId(), session);
        if (http != null) {
            BY_HTTP.put(http, session);
        }
        for (Session old : replaced) {
            forget(old);
            if (old.open()) {
                old.ctx.closeSession(CLOSE_REPLACED, "connected elsewhere");
            }
        }
        ctx.send(welcome(session).toString());
        Console.log("%s connected, version %s, proto %d, %d online", id.substring(0, 8), TextRules.name(string(frame, "version")), proto,
                PEOPLE.size());
        if (first[0]) {
            broadcastPresence(session);
        } else {
            announceChange(session);
        }
    }

    private static JsonObject welcome(Session session) {
        JsonArray peers = new JsonArray();
        for (String other : PEOPLE.keySet()) {
            Session latest = latest(other);
            if (!other.equals(session.id) && latest != null) {
                peers.add(presence(latest, session));
            }
        }
        JsonObject welcome = new JsonObject();
        welcome.addProperty("t", "welcome");
        welcome.addProperty("id", session.id);
        welcome.add("peers", peers);
        JsonObject turn = turnCredentials(session.id);
        if (turn != null) {
            welcome.add("turn", turn);
        }
        if (session.modern()) {
            welcome.addProperty("proto", 2);
            welcome.addProperty("http", session.http);
            welcome.add("admin", AdminService.status(session.id));
            welcome.addProperty("mutedUntil", AdminService.mutedUntil(session.id));
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
        String type = string(frame, "t");
        if (type.startsWith("chat.") || type.startsWith("admin.")) {
            request(session, type, frame);
            return;
        }
        switch (type) {
            case "route" -> route(session, frame);
            case "msg" -> ChatService.legacyMessage(session, frame);
            case "sync" -> ChatService.legacySync(session, frame);
            case "groups" -> {
                session.groups.clear();
                session.groups.addAll(provenGroups(frame));
                announceChange(session);
            }
            case "name" -> {
                session.name = TextRules.name(string(frame, "name"));
                announceChange(session);
            }
            case "ping" -> {
                JsonObject pong = new JsonObject();
                pong.addProperty("t", "pong");
                pong.add("n", frame.get("n"));
                session.send(pong.toString());
            }
            case "bye" -> {
                session.saidBye = true;
                session.ctx.closeSession();
            }
            default -> { }
        }
    }

    /* Every request is answered with the same shape, and a refusal carries the Portuguese text the app shows as it is. */
    private static void request(Session session, String type, JsonObject frame) {
        if (!session.modern()) {
            return;
        }
        String r = string(frame, "r");
        if (r.length() > MAX_REQUEST_ID) {
            r = r.substring(0, MAX_REQUEST_ID);
        }
        JsonObject answer;
        try {
            answer = type.startsWith("chat.") ? ChatService.handle(session, type, frame) : AdminService.handle(session, type, frame);
        } catch (Refusal refusal) {
            answer = failure(refusal.getMessage());
        } catch (RuntimeException e) {
            Console.error("Request " + type + " from " + session.id.substring(0, 8) + " failed: " + e);
            answer = failure("Não foi possível concluir o pedido. Tente de novo.");
        }
        if (answer == null || r.isEmpty()) {
            return;
        }
        JsonObject res = new JsonObject();
        res.addProperty("t", "res");
        res.addProperty("r", r);
        if (!answer.has("ok")) {
            res.addProperty("ok", true);
        }
        answer.entrySet().forEach(entry -> res.add(entry.getKey(), entry.getValue()));
        session.send(res.toString());
    }

    private static JsonObject failure(String message) {
        JsonObject failure = new JsonObject();
        failure.addProperty("ok", false);
        failure.addProperty("error", message);
        return failure;
    }

    private static void route(Session from, JsonObject frame) {
        String to = string(frame, "to");
        JsonElement payload = frame.get("f");
        if (payload == null || !payload.isJsonObject()) {
            return;
        }
        String out = envelope(from.id, payload, 0).toString();
        if (PERSON_ID.matcher(to).matches()) {
            Session target = latest(to);
            if (target != null) {
                target.send(out);
            }
        } else if ("*".equals(to) || from.groups.contains(to)) {
            for (String person : PEOPLE.keySet()) {
                Session target = latest(person);
                if (target != null && !person.equals(from.id) && ("*".equals(to) || target.groups.contains(to))) {
                    target.send(out);
                }
            }
        }
    }

    static JsonObject envelope(String from, JsonElement payload, long seq) {
        JsonObject out = new JsonObject();
        out.addProperty("t", "route");
        out.addProperty("from", from);
        if (seq > 0) {
            out.addProperty("seq", seq);
        }
        out.add("f", payload);
        return out;
    }

    /* Every open connection that may read the chat, legacy ones included: callers decide what each kind receives. */
    static void forEachMember(String chat, Consumer<Session> action) {
        if (chat.startsWith("dm:")) {
            int plus = chat.indexOf('+');
            if (plus < 0) {
                return;
            }
            String a = chat.substring(3, plus);
            String b = chat.substring(plus + 1);
            sessionsOf(a).stream().filter(Session::open).forEach(action);
            if (!a.equals(b)) {
                sessionsOf(b).stream().filter(Session::open).forEach(action);
            }
            return;
        }
        for (List<Session> sessions : PEOPLE.values()) {
            for (Session session : sessions) {
                if (session.open() && session.canRead(chat)) {
                    action.accept(session);
                }
            }
        }
    }

    static void toChat(String chat, String json, String exceptPerson) {
        forEachMember(chat, session -> {
            if (session.modern() && !session.id.equals(exceptPerson)) {
                session.send(json);
            }
        });
    }

    static void toEveryone(String json) {
        for (List<Session> sessions : PEOPLE.values()) {
            for (Session session : sessions) {
                if (session.modern()) {
                    session.send(json);
                }
            }
        }
    }

    static void toPerson(String person, String json, Session except) {
        for (Session session : sessionsOf(person)) {
            if (session != except && session.modern()) {
                session.send(json);
            }
        }
    }

    static List<Session> sessionsOf(String person) {
        return PEOPLE.getOrDefault(person, List.of());
    }

    static Session latest(String person) {
        List<Session> sessions = sessionsOf(person);
        for (int i = sessions.size() - 1; i >= 0; i--) {
            if (sessions.get(i).open()) {
                return sessions.get(i);
            }
        }
        return null;
    }

    /* Ends every connection of a person at once, telling each why first; nobody waits out the grace to see them gone. */
    static void dismiss(String person, String notice, int code, String reason) {
        for (Session session : sessionsOf(person)) {
            session.saidBye = true;
            session.send(notice);
            if (session.open()) {
                session.ctx.closeSession(code, reason);
            }
        }
    }

    /* The gate of the relay's HTTP routes: the token a live protocol-2 connection received in its welcome, never in the URL. */
    public static Session httpSession(Context ctx) {
        if (IpBlocklist.isBlocked(IP.get(ctx))) {
            throw new ForbiddenResponse();
        }
        String token = ctx.header("X-AV-Token");
        Session session = token == null || !TOKEN.matcher(token).matches() ? null : BY_HTTP.get(token);
        if (session == null || !session.open()) {
            throw new UnauthorizedResponse("Sua conexão com o servidor expirou. Aguarde o app reconectar.");
        }
        return session;
    }

    private static void onGone(WsContext ctx) {
        String address = ctx.attribute("address");
        if (address != null && ctx.attribute("counted") == null) {
            ctx.attribute("counted", Boolean.TRUE);
            CONNECTIONS.computeIfPresent(address, (a, count) -> count <= 1 ? null : count - 1);
        }
        PENDING.remove(ctx.sessionId());
        Session session = BY_SOCKET.get(ctx.sessionId());
        if (session == null) {
            return;
        }
        forget(session);
        Thread.startVirtualThread(() -> {
            if (!session.saidBye) {
                try {
                    Thread.sleep(OFFLINE_GRACE_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            leave(session);
        });
    }

    private static void forget(Session session) {
        BY_SOCKET.remove(session.ctx.sessionId(), session);
        if (session.http != null) {
            BY_HTTP.remove(session.http, session);
        }
    }

    private static void leave(Session session) {
        boolean[] last = new boolean[1];
        PEOPLE.computeIfPresent(session.id, (key, list) -> {
            if (!list.contains(session)) {
                return list;
            }
            List<Session> next = new ArrayList<>(list);
            next.remove(session);
            last[0] = next.isEmpty();
            return next.isEmpty() ? null : List.copyOf(next);
        });
        if (last[0]) {
            JsonObject offline = new JsonObject();
            offline.addProperty("t", "offline");
            offline.addProperty("id", session.id);
            everyoneBut(session.id, offline.toString());
        }
    }

    private static void everyoneBut(String person, String json) {
        for (List<Session> sessions : PEOPLE.values()) {
            for (Session other : sessions) {
                if (!other.id.equals(person)) {
                    other.send(json);
                }
            }
        }
    }

    private static void broadcastPresence(Session session) {
        for (List<Session> sessions : PEOPLE.values()) {
            for (Session other : sessions) {
                if (!other.id.equals(session.id) && other.open()) {
                    JsonObject online = new JsonObject();
                    online.addProperty("t", "online");
                    online.add("peer", presence(session, other));
                    other.send(online.toString());
                }
            }
        }
    }

    private static void announceChange(Session session) {
        for (List<Session> sessions : PEOPLE.values()) {
            for (Session other : sessions) {
                if (!other.id.equals(session.id) && other.open()) {
                    JsonObject changed = new JsonObject();
                    changed.addProperty("t", "peer");
                    changed.add("peer", presence(session, other));
                    other.send(changed.toString());
                }
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

    static String randomHex(int bytes) {
        byte[] random = new byte[bytes];
        RANDOM.nextBytes(random);
        return HexFormat.of().formatHex(random);
    }

    static String string(JsonObject o, String key) {
        return TextRules.string(o, key);
    }

    private record Pending(String challenge) {
    }
}
