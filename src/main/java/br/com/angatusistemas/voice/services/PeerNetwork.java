package br.com.angatusistemas.voice.services;

import br.com.angatusistemas.lib.console.Console;
import br.com.angatusistemas.lib.database.Saveable;
import br.com.angatusistemas.lib.task.Task;
import br.com.angatusistemas.voice.entities.Group;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
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
    private static final String DEVICE_KEY = "_net.device";
    private static final int PROTOCOL = 2;
    private static final long REQUEST_TIMEOUT_MS = 15_000;
    private static final long KICK_WAIT_MS = 60_000;
    private static final long BAN_WAIT_MS = 600_000;
    private static final int MAX_SIGNAL_BYTES = 64 * 1024;
    private static final int MAX_ROOMS = 128;
    private static final long TICK_MS = 1_000;
    private static final long PING_EVERY_MS = 3_000;
    static final Pattern PEER_ID = Pattern.compile("[0-9a-f]{20}");
    private static final Pattern GROUP_ID = Pattern.compile("g_[0-9a-f]{16}");
    private static final Set<String> SIGNAL_KINDS = Set.of("offer", "answer", "candidate", "bye", "state");

    private static final Map<String, Person> ONLINE = new ConcurrentHashMap<>();
    private static final Set<String> AVATAR_ASKED = ConcurrentHashMap.newKeySet();
    private static final Map<String, CompletableFuture<JsonObject>> WAITING = new ConcurrentHashMap<>();
    private static final AtomicLong REQUESTS = new AtomicLong();

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

    public static String httpBase() {
        return SERVER.replaceFirst("^ws", "http");
    }

    public static String streamUrl() {
        return SERVER + "/ws/stream";
    }

    public static JsonObject turn() {
        return turn;
    }

    /* One question to the server and its answer. The server is the authority, so a refusal comes back as its own words. */
    public static JsonObject request(JsonObject frame) {
        ServerLink current = link;
        if (current == null || !current.isReady()) {
            throw new Offline();
        }
        String r = Long.toString(REQUESTS.incrementAndGet(), 36);
        CompletableFuture<JsonObject> answer = new CompletableFuture<>();
        WAITING.put(r, answer);
        frame.addProperty("r", r);
        current.send(frame);
        try {
            JsonObject res = answer.get(REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            if (!res.has("ok") || !res.get("ok").getAsBoolean()) {
                throw new Refused(res.has("error") ? text(res, "error", 300) : "O servidor recusou o pedido.");
            }
            return res;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new Offline();
        } catch (ExecutionException | TimeoutException e) {
            throw new Offline();
        } finally {
            WAITING.remove(r);
        }
    }

    /* Fire and forget: typing and read marks have no answer worth waiting for. */
    public static void tell(JsonObject frame) {
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
        hello.addProperty("proto", PROTOCOL);
        hello.addProperty("device", deviceId());
        hello.add("groups", groupProofs());
        return hello;
    }

    /* Fixed per installation: the same person on two computers keeps both connected, while a reconnection replaces itself. */
    private static String deviceId() {
        Setting stored = Saveable.findById(Setting.class, DEVICE_KEY);
        if (stored != null && stored.getValue() != null && stored.getValue().matches("[0-9a-f]{16}")) {
            return stored.getValue();
        }
        byte[] random = new byte[8];
        new java.security.SecureRandom().nextBytes(random);
        Setting device = new Setting();
        device.setId(DEVICE_KEY);
        device.setValue(HexFormat.of().formatHex(random));
        device.save();
        return device.getValue();
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
        WAITING.values().forEach(waiting -> waiting.completeExceptionally(new Offline()));
        WAITING.clear();
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
            case "route" -> onRouted(frame);
            case "res" -> {
                CompletableFuture<JsonObject> waiting = WAITING.get(text(frame, "r", 16));
                if (waiting != null) {
                    waiting.complete(frame);
                }
            }
            case "kicked", "banned" -> {
                ServerLink current = link;
                if (current != null) {
                    current.holdOff("kicked".equals(text(frame, "t", 16)) ? KICK_WAIT_MS : BAN_WAIT_MS);
                }
                LiveHub.emit("net.refused", Map.of("reason", text(frame, "t", 16)));
            }
            default -> ChatService.onServerFrame(frame);
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
        ChatService.onWelcome(frame);
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

    /* Someone who wrote while this app was closed may never have been seen online: the chat needs them listed. */
    static void rememberSender(String id, String name) {
        if (!id.equals(self.id()) && PEER_ID.matcher(id).matches() && !Saveable.exists(Peer.class, id)) {
            storePeer(new Person(id, IdentityService.clean(name), "", Set.of()));
            LiveHub.emit("peer.changed", Map.of("id", id));
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
        Person person = ONLINE.get(from);
        if (person == null) {
            return;
        }
        switch (text(frame, "t", 16)) {
            case "call" -> onCall(person, frame);
            case "rooms" -> onRooms(person, frame);
            case "decline" -> onDecline(person, frame);
            case "sig" -> onSignal(person, frame);
            case "profile" -> onProfile(person, frame);
            case "profile?" -> sendProfile(person.id);
            default -> { }
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

    /** The server could not be asked: not connected, or no answer in time. */
    public static final class Offline extends RuntimeException {

        Offline() {
            super("server unreachable", null, false, false);
        }
    }

    /** The server answered no, with the words to show. */
    public static final class Refused extends RuntimeException {

        Refused(String message) {
            super(message, null, false, false);
        }
    }
}
