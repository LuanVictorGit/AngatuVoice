package br.com.angatusistemas.voice.services;

import br.com.angatusistemas.lib.console.Console;
import br.com.angatusistemas.lib.database.Saveable;
import br.com.angatusistemas.lib.task.Task;
import br.com.angatusistemas.voice.entities.Group;
import br.com.angatusistemas.voice.entities.Message;
import br.com.angatusistemas.voice.entities.Peer;
import br.com.angatusistemas.voice.entities.Setting;
import br.com.angatusistemas.voice.utils.Crypto;
import br.com.angatusistemas.voice.utils.Version;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Everything between this person and the others, all of it through the Angatu Voice server: who is online, chat, call
 * state and signalling. The server routes; every message is still signed here and verified by whoever receives it.
 *
 * @author Angatu Sistemas
 */
public final class PeerNetwork {

    private static final String SERVER = System.getProperty("angatu.server", "wss://voice.angatusistemas.com.br");
    private static final String SEQ_KEY = "_net.seq";
    private static final int MAX_SIGNAL_BYTES = 64 * 1024;
    private static final int MAX_ROOMS = 128;
    private static final long TICK_MS = 1_000;
    private static final long PING_EVERY_MS = 3_000;
    private static final long FUTURE_TOLERANCE_MS = 10 * 60_000L;
    private static final Pattern PEER_ID = Pattern.compile("[0-9a-f]{20}");
    private static final Pattern GROUP_ID = Pattern.compile("g_[0-9a-f]{16}");
    private static final Set<String> SIGNAL_KINDS = Set.of("offer", "answer", "candidate", "bye", "state");

    private static final Map<String, Person> ONLINE = new ConcurrentHashMap<>();
    private static final Set<String> AVATAR_ASKED = ConcurrentHashMap.newKeySet();

    private static volatile IdentityService.Self self;
    private static volatile ServerLink link;
    private static volatile JsonObject turn;
    private static volatile long lastPing;

    private PeerNetwork() {
    }

    public static void start() {
        self = IdentityService.load();
        link = new ServerLink(SERVER + "/ws/net", self, PeerNetwork::helloFrame, PeerNetwork::dispatchSafely, PeerNetwork::onServerLost);
        link.start();
        Task.runTimerWithFixedDelay(PeerNetwork::tick, TICK_MS, TICK_MS);
    }

    public static void stop() {
        ServerLink current = link;
        if (current != null) {
            current.stop();
        }
    }

    public static IdentityService.Self me() {
        return self;
    }

    public static boolean connected() {
        ServerLink current = link;
        return current != null && current.isReady();
    }

    public static String streamUrl() {
        return SERVER + "/ws/stream";
    }

    public static JsonObject turn() {
        return turn;
    }

    public static Message send(String chat, String text) {
        String clean = MessageService.sanitize(text);
        boolean direct = chat.startsWith("dm:");
        String to = direct ? chat.substring(3) : "";
        if (clean.isEmpty() || (direct ? !PEER_ID.matcher(to).matches() : GroupService.find(chat) == null)) {
            throw new IllegalArgumentException("invalid message");
        }
        IdentityService.Self me = self;
        long ts = System.currentTimeMillis();
        String id = me.id() + ":" + UUID.randomUUID();
        Message message = new Message();
        message.setId(id);
        message.setChatId(chat);
        message.setSenderId(me.id());
        message.setSenderName(me.name());
        message.setText(clean);
        message.setSentAt(ts);
        message.setPending(true);
        message.setRead(true);
        message.setAuthorKey(Crypto.b64(me.publicKey()));
        message.setSignature(Crypto.b64(me.sign(messageText(id, direct ? "dm" : chat, to, ts, me.name(), clean))));
        message.save();
        sendStored(message);
        LiveHub.emit("message", MessageService.view(message, me.id()));
        return message;
    }

    private static void sendStored(Message message) {
        JsonObject frame = new JsonObject();
        frame.addProperty("t", "msg");
        frame.add("f", messageFrame(message));
        sendToServer(frame);
    }

    private static void sendToServer(JsonObject frame) {
        ServerLink current = link;
        if (current != null) {
            current.send(frame);
        }
    }

    private static void route(String to, JsonObject payload) {
        JsonObject frame = new JsonObject();
        frame.addProperty("t", "route");
        frame.addProperty("to", to);
        frame.add("f", payload);
        sendToServer(frame);
    }

    private static String roomTarget(String room) {
        String me = self.id();
        if (room.startsWith("dm:")) {
            String[] pair = room.substring(3).split("\\+");
            return pair[0].equals(me) ? pair[1] : pair[0];
        }
        return room;
    }

    public static void setCall(String room, boolean joined) {
        String me = self.id();
        if (!validRoom(room, me)) {
            throw new IllegalArgumentException("invalid room");
        }
        boolean already = CallService.isIn(room, me);
        boolean started = joined && !already && CallService.members(room).isEmpty();
        CallService.set(room, me, joined);
        if (!joined) {
            CallService.exit(room);
        } else if (!already) {
            CallService.enter(room, started, System.currentTimeMillis());
        }
        JsonObject frame = new JsonObject();
        frame.addProperty("t", "call");
        frame.addProperty("room", room);
        frame.addProperty("joined", joined);
        frame.addProperty("ring", started);
        route(roomTarget(room), frame);
        afterRoomChange(room);
    }

    public static void declineCall(String room) {
        if (!validRoom(room, self.id())) {
            throw new IllegalArgumentException("invalid room");
        }
        if (!CallService.decline(room)) {
            return;
        }
        JsonObject frame = new JsonObject();
        frame.addProperty("t", "decline");
        frame.addProperty("room", room);
        for (String member : CallService.members(room)) {
            route(member, frame);
        }
        emitIncoming();
    }

    public static void signal(String room, String to, String kind, JsonElement data) {
        Person person = ONLINE.get(to);
        if (person == null || !roomAllowed(person, room) || !SIGNAL_KINDS.contains(kind) || data == null
                || data.toString().length() > MAX_SIGNAL_BYTES) {
            throw new IllegalArgumentException("signal refused");
        }
        JsonObject frame = new JsonObject();
        frame.addProperty("t", "sig");
        frame.addProperty("room", room);
        frame.addProperty("kind", kind);
        frame.add("data", data);
        route(to, frame);
    }

    public static void forget(String id) {
        dropFromRooms(id);
        Peer peer = Saveable.findById(Peer.class, id);
        if (peer != null) {
            peer.delete();
        }
    }

    public static List<PeerView> peers() {
        List<Peer> known = Saveable.query(Peer.class, "SELECT data FROM " + Saveable.tableName(Peer.class)
                + " ORDER BY json_extract(data,'$.lastSeen') DESC LIMIT 200");
        Map<String, Integer> names = HashMap.newHashMap(known.size());
        known.forEach(p -> names.merge(p.getName().toLowerCase(), 1, Integer::sum));
        int rtt = link == null ? ServerLink.NO_RTT : link.rtt();
        List<PeerView> views = new ArrayList<>(known.size());
        for (Peer p : known) {
            Person person = ONLINE.get(p.getId());
            views.add(new PeerView(p.getId(), p.getName(), person != null, person == null ? List.of() : List.copyOf(person.groups),
                    names.get(p.getName().toLowerCase()) > 1, p.getLastSeen(), AvatarService.version(p.getAvatar()),
                    person == null ? ServerLink.NO_RTT : rtt));
        }
        return views;
    }

    public static Map<String, Integer> latency() {
        int rtt = link == null ? ServerLink.NO_RTT : link.rtt();
        Map<String, Integer> all = HashMap.newHashMap(ONLINE.size());
        ONLINE.keySet().forEach(id -> all.put(id, rtt));
        return all;
    }

    /* Groups are proved with a hash of their secret: the server can tell members apart without ever being able to invite anyone. */
    private static JsonObject helloFrame() {
        IdentityService.Self me = self;
        JsonObject hello = new JsonObject();
        hello.addProperty("pub", Crypto.b64(me.publicKey()));
        hello.addProperty("name", me.name());
        hello.addProperty("version", Version.current().toString());
        hello.add("groups", groupProofs());
        return hello;
    }

    private static JsonArray groupProofs() {
        JsonArray groups = new JsonArray();
        for (Group group : GroupService.list()) {
            if (group.getSecret() != null) {
                JsonObject proof = new JsonObject();
                proof.addProperty("id", group.getId());
                proof.addProperty("token", HexFormat.of().formatHex(Crypto.sha256(("AV3|group|" + group.getSecret()).getBytes(StandardCharsets.UTF_8))));
                groups.add(proof);
            }
        }
        return groups;
    }

    public static void groupsChanged() {
        JsonObject frame = new JsonObject();
        frame.addProperty("t", "groups");
        frame.add("groups", groupProofs());
        sendToServer(frame);
    }

    public static void renamed(String name) {
        self = IdentityService.load();
        JsonObject frame = new JsonObject();
        frame.addProperty("t", "name");
        frame.addProperty("name", name);
        sendToServer(frame);
    }

    private static void tick() {
        try {
            long now = System.currentTimeMillis();
            CallService.expire(now).forEach(PeerNetwork::announceMissed);
            if (now - lastPing >= PING_EVERY_MS) {
                lastPing = now;
                ServerLink current = link;
                if (current != null) {
                    current.keepAlive();
                }
                LiveHub.emit("net.latency", Map.of("peers", latency()));
            }
        } catch (RuntimeException e) {
            Console.error("network tick failed", e);
        }
    }

    private static void onServerLost() {
        LiveHub.emit("net", Map.of("connected", false));
    }

    private static void dispatchSafely(JsonObject frame) {
        try {
            dispatch(frame);
        } catch (RuntimeException e) {
            // The other apps may be newer than this one: a frame it cannot digest is not a reason to reconnect.
            Console.debug("frame ignored: " + e.getClass().getSimpleName());
        }
    }

    private static void dispatch(JsonObject frame) {
        switch (text(frame, "t", 16)) {
            case "welcome" -> onWelcome(frame);
            case "online" -> {
                Person person = person(frame.getAsJsonObject("peer"));
                boolean fresh = ONLINE.put(person.id, person) == null;
                onPersonUp(person, fresh);
            }
            case "peer" -> {
                Person person = person(frame.getAsJsonObject("peer"));
                ONLINE.put(person.id, person);
                storePeer(person);
                LiveHub.emit("peer.changed", Map.of("id", person.id));
            }
            case "offline" -> onPersonGone(text(frame, "id", 20));
            case "stored" -> onStored(text(frame, "id", 80));
            case "route" -> onRouted(frame);
            default -> { }
        }
    }

    private static Person person(JsonObject o) {
        Set<String> groups = new HashSet<>();
        array(o, "groups", 128).forEach(g -> groups.add(g.getAsString()));
        return new Person(text(o, "id", 20), IdentityService.clean(text(o, "name", 64)), text(o, "pub", 80), groups);
    }

    /* Also runs after a reconnection: whoever came or went in the meantime is settled here, quietly, from the full list. */
    private static void onWelcome(JsonObject frame) {
        turn = frame.has("turn") ? frame.getAsJsonObject("turn") : null;
        Set<String> present = new HashSet<>();
        for (JsonElement el : array(frame, "peers", 1024)) {
            Person person = person(el.getAsJsonObject());
            present.add(person.id);
            boolean fresh = ONLINE.put(person.id, person) == null;
            onPersonUp(person, false);
            if (!fresh) {
                LiveHub.emit("peer.relinked", Map.of("id", person.id));
            }
        }
        for (String id : List.copyOf(ONLINE.keySet())) {
            if (!present.contains(id)) {
                ONLINE.remove(id);
                dropFromRooms(id);
            }
        }
        JsonObject sync = new JsonObject();
        sync.addProperty("t", "sync");
        sync.addProperty("since", lastSeq());
        sendToServer(sync);
        for (Message pending : MessageService.pending()) {
            sendStored(pending);
        }
        LiveHub.emit("net", Map.of("connected", true));
        Console.log("Conectado ao servidor, %d online", present.size());
    }

    private static void onPersonUp(Person person, boolean announce) {
        storePeer(person);
        if (announce) {
            LiveHub.emit("peer.online", Map.of("id", person.id, "name", person.name));
        } else {
            LiveHub.emit("peer.changed", Map.of("id", person.id));
        }
        JsonObject rooms = roomsFrame(person);
        if (rooms != null) {
            route(person.id, rooms);
        }
        // Lost acknowledgements end here: whatever they have not confirmed is offered again, and they ignore what they already have.
        for (Message pending : MessageService.pendingFor("dm:" + person.id)) {
            sendStored(pending);
        }
        askForAvatar(person);
    }

    private static void askForAvatar(Person person) {
        Peer stored = Saveable.findById(Peer.class, person.id);
        if ((stored == null || stored.getAvatar() == null) && AVATAR_ASKED.add(person.id)) {
            JsonObject ask = new JsonObject();
            ask.addProperty("t", "profile?");
            route(person.id, ask);
        }
    }

    private static void onPersonGone(String id) {
        Person gone = ONLINE.remove(id);
        if (gone != null) {
            LiveHub.emit("peer.offline", Map.of("id", id, "name", gone.name));
        }
        dropFromRooms(id);
    }

    private static void dropFromRooms(String id) {
        for (String room : CallService.dropPeer(id)) {
            afterRoomChange(room);
        }
    }

    private static void storePeer(Person person) {
        long now = System.currentTimeMillis();
        Peer peer = Saveable.findById(Peer.class, person.id);
        if (peer == null) {
            peer = new Peer();
            peer.setId(person.id);
            peer.setFirstSeen(now);
        }
        peer.setName(person.name);
        peer.setPublicKey(person.pub);
        peer.setLastSeen(now);
        peer.setGroups(List.copyOf(person.groups));
        peer.save();
    }

    private static JsonObject roomsFrame(Person person) {
        long now = System.currentTimeMillis();
        JsonArray rooms = new JsonArray();
        for (CallService.OwnCall call : CallService.own()) {
            if (roomAllowed(person, call.room())) {
                JsonObject room = new JsonObject();
                room.addProperty("room", call.room());
                room.addProperty("ring", call.stillRinging(now));
                room.addProperty("age", now - call.since());
                rooms.add(room);
            }
        }
        if (rooms.isEmpty()) {
            return null;
        }
        JsonObject frame = new JsonObject();
        frame.addProperty("t", "rooms");
        frame.add("rooms", rooms);
        return frame;
    }

    private static void onRouted(JsonObject envelope) {
        String from = text(envelope, "from", 20);
        JsonObject frame = envelope.getAsJsonObject("f");
        if (envelope.has("seq")) {
            noteSeq(envelope.get("seq").getAsLong());
        }
        if ("msg".equals(text(frame, "t", 16))) {
            onMessage(from, frame);
            return;
        }
        Person person = ONLINE.get(from);
        if (person == null) {
            return;
        }
        switch (text(frame, "t", 16)) {
            case "ack" -> onAck(person, frame);
            case "call" -> onCall(person, frame);
            case "rooms" -> onRooms(person, frame);
            case "decline" -> onDecline(person, frame);
            case "sig" -> onSignal(person, frame);
            case "profile" -> onProfile(person, frame);
            case "profile?" -> sendProfile(person.id);
            default -> { }
        }
    }

    private static long lastSeq() {
        Setting stored = Saveable.findById(Setting.class, SEQ_KEY);
        return stored == null || stored.getValue() == null ? 0 : Long.parseLong(stored.getValue());
    }

    private static void noteSeq(long seq) {
        if (seq > lastSeq()) {
            Setting setting = new Setting();
            setting.setId(SEQ_KEY);
            setting.setValue(String.valueOf(seq));
            setting.save();
        }
    }

    private static void onMessage(String sender, JsonObject f) {
        try {
            String chat = text(f, "chat", 40);
            String from = text(f, "from", 20);
            String to = text(f, "to", 20);
            String id = text(f, "id", 80);
            String name = text(f, "name", 64);
            String body = text(f, "text", MessageService.MAX_TEXT);
            long ts = f.get("ts").getAsLong();
            String pub = text(f, "pub", 80);
            byte[] key = Crypto.unb64(pub);
            boolean direct = chat.equals("dm");
            boolean routable = from.equals(sender) && (direct ? to.equals(self.id()) : GroupService.find(chat) != null);
            boolean valid = routable && id.startsWith(from + ":") && Crypto.fingerprint(key).equals(from)
                    && body.equals(MessageService.sanitize(body)) && !body.isEmpty()
                    && ts <= System.currentTimeMillis() + FUTURE_TOLERANCE_MS
                    && Crypto.verify(key, messageText(id, chat, to, ts, name, body), Crypto.unb64(text(f, "sig", 128)));
            if (!valid) {
                return;
            }
            String cleanName = IdentityService.clean(name);
            // Someone who wrote while this app was closed may never have been seen online: the chat needs them listed.
            if (!Saveable.exists(Peer.class, from)) {
                storePeer(new Person(from, cleanName, pub, Set.of()));
                LiveHub.emit("peer.changed", Map.of("id", from));
            }
            Message m = new Message();
            m.setId(id);
            m.setChatId(direct ? "dm:" + from : chat);
            m.setSenderId(from);
            m.setSenderName(cleanName);
            m.setText(body);
            m.setSentAt(ts);
            m.setRead(false);
            m.setAuthorKey(pub);
            m.setSignature(text(f, "sig", 128));
            if (MessageService.store(m)) {
                LiveHub.emit("message", MessageService.view(m, self.id()));
                String preview = !SettingsService.flag("privacy.preview") ? "Nova mensagem"
                        : body.length() > 120 ? body.substring(0, 120) + "…" : body;
                Notifications.notify(m.getSenderName(), preview);
            }
            if (direct) {
                JsonObject ack = new JsonObject();
                ack.addProperty("t", "ack");
                ack.addProperty("id", id);
                route(from, ack);
            }
        } catch (RuntimeException e) {
            Console.debug("message refused: " + e.getClass().getSimpleName());
        }
    }

    private static void onAck(Person person, JsonObject f) {
        String id = text(f, "id", 80);
        Message m = id.startsWith(self.id() + ":") ? Saveable.findById(Message.class, id) : null;
        if (m != null && m.isPending() && m.getChatId().equals("dm:" + person.id)) {
            MessageService.delivered(id);
            LiveHub.emit("message.delivered", Map.of("id", id, "chat", m.getChatId()));
        }
    }

    /* In a group nobody in particular confirms: it is delivered once the server holds it for everyone. */
    private static void onStored(String id) {
        Message m = id.startsWith(self.id() + ":") ? Saveable.findById(Message.class, id) : null;
        if (m != null && m.isPending() && !m.getChatId().startsWith("dm:")) {
            MessageService.delivered(id);
            LiveHub.emit("message.delivered", Map.of("id", id, "chat", m.getChatId()));
        }
    }

    private static void onCall(Person person, JsonObject f) {
        String room = text(f, "room", 80);
        if (!roomAllowed(person, room) || !f.has("joined")) {
            return;
        }
        boolean joined = f.get("joined").getAsBoolean();
        CallService.set(room, person.id, joined);
        afterRoomChange(room);
        if (joined && f.has("ring") && f.get("ring").getAsBoolean()) {
            ring(room, person, 0);
        }
    }

    // Unlike "call", this lists every room the person is in right now, so whatever they left while unreachable is cleared.
    private static void onRooms(Person person, JsonObject f) {
        Set<String> listed = new HashSet<>();
        for (JsonElement el : array(f, "rooms", MAX_ROOMS)) {
            JsonObject entry = el.getAsJsonObject();
            String room = text(entry, "room", 80);
            if (!roomAllowed(person, room)) {
                continue;
            }
            listed.add(room);
            if (CallService.set(room, person.id, true)) {
                afterRoomChange(room);
            }
            if (entry.has("ring") && entry.get("ring").getAsBoolean()) {
                ring(room, person, entry.has("age") ? entry.get("age").getAsLong() : 0);
            }
        }
        for (String room : CallService.roomsOf(person.id)) {
            if (!listed.contains(room)) {
                CallService.set(room, person.id, false);
                afterRoomChange(room);
            }
        }
    }

    private static void ring(String room, Person person, long ageMs) {
        if (CallService.isIn(room, self.id()) || !CallService.isIn(room, person.id)) {
            return;
        }
        if (CallService.ring(room, person.id, person.name, ageMs, System.currentTimeMillis())) {
            emitIncoming();
        }
    }

    private static void onDecline(Person person, JsonObject f) {
        String room = text(f, "room", 80);
        if (roomAllowed(person, room) && CallService.isIn(room, self.id())) {
            LiveHub.emit("call.declined", Map.of("room", room, "id", person.id, "name", person.name));
        }
    }

    private static void onSignal(Person person, JsonObject f) {
        String room = text(f, "room", 80);
        String kind = text(f, "kind", 16);
        if (!roomAllowed(person, room) || !SIGNAL_KINDS.contains(kind) || !f.has("data")
                || f.get("data").toString().length() > MAX_SIGNAL_BYTES) {
            return;
        }
        LiveHub.emit("call.signal", Map.of("from", person.id, "room", room, "kind", kind, "data", f.get("data")));
    }

    public static void publishProfile() {
        route("*", profileFrame());
    }

    private static void sendProfile(String to) {
        if (AvatarService.own().isPresent()) {
            route(to, profileFrame());
        }
    }

    private static JsonObject profileFrame() {
        JsonObject frame = new JsonObject();
        frame.addProperty("t", "profile");
        frame.addProperty("avatar", AvatarService.own().orElse(""));
        return frame;
    }

    private static void onProfile(Person person, JsonObject f) {
        try {
            String avatar = text(f, "avatar", AvatarService.MAX_BYTES * 4 / 3 + 8);
            AvatarService.setPeer(person.id, avatar);
            LiveHub.emit("peer.profile", Map.of("id", person.id));
        } catch (IllegalArgumentException e) {
            Console.debug("profile refused: " + e.getMessage());
        }
    }

    private static boolean validRoom(String room, String me) {
        if (room.startsWith("dm:")) {
            return room.contains(me) && room.matches("dm:[0-9a-f]{20}\\+[0-9a-f]{20}");
        }
        return GROUP_ID.matcher(room).matches() ? GroupService.find(room) != null : room.equals(GroupService.PUBLIC);
    }

    private static boolean roomAllowed(Person person, String room) {
        String me = self.id();
        return room.startsWith("dm:") ? CallService.isDmRoom(room, me, person.id) : person.groups.contains(room) && validRoom(room, me);
    }

    private static void afterRoomChange(String room) {
        LiveHub.emit("call.state", Map.of("room", room, "members", CallService.members(room)));
        CallService.Settled settled = CallService.settle(room, self.id());
        if (settled.changed()) {
            emitIncoming();
        }
        if (settled.missed() != null) {
            announceMissed(settled.missed());
        }
    }

    private static void emitIncoming() {
        List<CallService.Incoming> ringing = CallService.ringing();
        LiveHub.emit("call.incoming", Map.of("calls", ringing));
        Notifications.ringing(ringing);
    }

    private static void announceMissed(CallService.Incoming call) {
        emitIncoming();
        LiveHub.emit("call.missed", Map.of("room", call.room(), "id", call.callerId(), "name", call.callerName()));
        Notifications.notify(call.callerName(), "Chamada perdida.");
    }

    private static JsonObject messageFrame(Message m) {
        boolean direct = m.getChatId().startsWith("dm:");
        JsonObject o = new JsonObject();
        o.addProperty("t", "msg");
        o.addProperty("id", m.getId());
        o.addProperty("chat", direct ? "dm" : m.getChatId());
        o.addProperty("to", direct ? m.getChatId().substring(3) : "");
        o.addProperty("from", m.getSenderId());
        o.addProperty("name", m.getSenderName());
        o.addProperty("pub", m.getAuthorKey());
        o.addProperty("ts", m.getSentAt());
        o.addProperty("text", m.getText());
        o.addProperty("sig", m.getSignature());
        return o;
    }

    private static byte[] messageText(String id, String chat, String to, long ts, String name, String body) {
        return String.join("|", "AV1", "msg", id, chat, to, String.valueOf(ts), name, body).getBytes(StandardCharsets.UTF_8);
    }

    private static String text(JsonObject o, String key, int max) {
        JsonElement el = o.get(key);
        if (el == null || !el.isJsonPrimitive()) {
            return "";
        }
        String value = el.getAsString();
        if (value.length() > max) {
            throw new IllegalArgumentException("field too long: " + key);
        }
        return value;
    }

    private static JsonArray array(JsonObject o, String key, int max) {
        JsonElement el = o.get(key);
        if (el == null || !el.isJsonArray() || el.getAsJsonArray().size() > max) {
            return new JsonArray();
        }
        return el.getAsJsonArray();
    }

    public record PeerView(String id, String name, boolean online, List<String> groups, boolean duplicateName, long lastSeen,
                           String avatar, int rtt) {
    }

    private record Person(String id, String name, String pub, Set<String> groups) {
    }
}
