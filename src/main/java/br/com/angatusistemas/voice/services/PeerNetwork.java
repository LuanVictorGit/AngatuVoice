package br.com.angatusistemas.voice.services;

import br.com.angatusistemas.lib.console.Console;
import br.com.angatusistemas.lib.database.Saveable;
import br.com.angatusistemas.lib.gson.GsonAPI;
import br.com.angatusistemas.lib.task.Task;
import br.com.angatusistemas.voice.entities.Group;
import br.com.angatusistemas.voice.entities.Message;
import br.com.angatusistemas.voice.entities.Peer;
import br.com.angatusistemas.voice.utils.Crypto;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

/**
 * The private network engine: discovers peers over the Radmin adapter, authenticates them, and routes chat and call
 * signalling between them. Everything it listens on is bound to the Radmin address only.
 *
 * @author Angatu Sistemas
 */
public final class PeerNetwork {

    private static final int BASE_PORT = Integer.getInteger("angatu.peer.port", 47600);
    private static final int PORT_SPAN = 10;
    private static final int MAX_LINKS = 64;
    private static final int MAX_DIALS = 8;
    private static final int MAX_LEARNED = 128;
    private static final int SYNC_BATCH = 200;
    private static final int MAX_SIGNAL_BYTES = 32 * 1024;
    private static final long TICK_MS = 5_000;
    private static final long BEACON_EVERY_MS = 15_000;
    private static final long IDLE_PING_MS = 20_000;
    private static final long DEAD_AFTER_MS = 70_000;
    private static final long FUTURE_TOLERANCE_MS = 10 * 60_000L;
    private static final long RECENT_PEER_MS = 7 * 24 * 3_600_000L;
    private static final Pattern PEER_ID = Pattern.compile("[0-9a-f]{20}");
    private static final Pattern GROUP_ID = Pattern.compile("g_[0-9a-f]{16}");
    private static final Set<String> SIGNAL_KINDS = Set.of("offer", "answer", "candidate", "bye");

    private static final Map<String, PeerLink> LINKS = new ConcurrentHashMap<>();
    private static final Map<String, Backoff> BACKOFF = new ConcurrentHashMap<>();
    private static final Map<String, Long> BEACON_SEEN = new ConcurrentHashMap<>();
    private static final Set<String> DIALING = ConcurrentHashMap.newKeySet();
    private static final Set<String> TARGETS = ConcurrentHashMap.newKeySet();
    private static final Semaphore HANDSHAKES = new Semaphore(8);
    private static final AtomicReference<RadminService.Status> LAST_NET = new AtomicReference<>(RadminService.current());

    private static volatile IdentityService.Self self;
    private static volatile Endpoint endpoint;
    private static volatile long lastBeacon;
    private static volatile int misses;

    private PeerNetwork() {
    }

    public static void start() {
        self = IdentityService.load();
        Task.runTimerWithFixedDelay(PeerNetwork::tick, 0, TICK_MS);
    }

    public static void stop() {
        closeEndpoint();
    }

    public static IdentityService.Self me() {
        return self;
    }

    public static boolean isOnline(String peerId) {
        return LINKS.containsKey(peerId);
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
        message.setPending(direct);
        message.setRead(true);
        message.setAuthorKey(Crypto.b64(me.publicKey()));
        message.setSignature(Crypto.b64(me.sign(messageText(id, direct ? "dm" : chat, to, ts, me.name(), clean))));
        message.save();
        JsonObject frame = messageFrame(message);
        if (direct) {
            PeerLink link = LINKS.get(to);
            if (link != null) {
                trySend(link, frame);
            }
        } else {
            broadcast(chat, frame);
        }
        LiveHub.emit("message", MessageService.view(message, me.id()));
        return message;
    }

    public static void setCall(String room, boolean joined) {
        String me = self.id();
        if (!validRoom(room, me)) {
            throw new IllegalArgumentException("invalid room");
        }
        CallService.set(room, me, joined);
        JsonObject frame = new JsonObject();
        frame.addProperty("t", "call");
        frame.addProperty("room", room);
        frame.addProperty("joined", joined);
        for (PeerLink link : LINKS.values()) {
            if (roomAllowed(link, room)) {
                trySend(link, frame);
            }
        }
        emitCall(room);
    }

    public static void signal(String room, String to, String kind, JsonElement data) {
        PeerLink link = LINKS.get(to);
        if (link == null || !roomAllowed(link, room) || !SIGNAL_KINDS.contains(kind) || data == null
                || data.toString().length() > MAX_SIGNAL_BYTES) {
            throw new IllegalArgumentException("signal refused");
        }
        JsonObject frame = new JsonObject();
        frame.addProperty("t", "sig");
        frame.addProperty("room", room);
        frame.addProperty("kind", kind);
        frame.add("data", data);
        trySend(link, frame);
    }

    public static void addManual(String address) {
        int colon = address == null ? -1 : address.lastIndexOf(':');
        String host = colon < 0 ? address : address.substring(0, colon);
        int port = colon < 0 ? BASE_PORT : Integer.parseInt(address.substring(colon + 1));
        Endpoint e = endpoint;
        if (e == null || host == null || !host.matches("\\d{1,3}(\\.\\d{1,3}){3}") || port < 1024 || port > 65535
                || !sameNetwork(host, e.net)) {
            throw new IllegalArgumentException("address outside the Radmin network");
        }
        TARGETS.add(host + ":" + port);
        dial(host, port);
    }

    public static List<PeerView> peers() {
        List<Peer> known = Saveable.query(Peer.class, "SELECT data FROM " + Saveable.tableName(Peer.class)
                + " ORDER BY json_extract(data,'$.lastSeen') DESC LIMIT 200");
        Map<String, Integer> names = new ConcurrentHashMap<>();
        known.forEach(p -> names.merge(p.getName().toLowerCase(), 1, Integer::sum));
        List<PeerView> views = new ArrayList<>(known.size());
        for (Peer p : known) {
            PeerLink link = LINKS.get(p.getId());
            views.add(new PeerView(p.getId(), p.getName(), p.getRadminAlias(), link != null, p.getLastIp(),
                    link != null ? List.copyOf(link.groups) : List.of(), names.get(p.getName().toLowerCase()) > 1,
                    p.getLastSeen()));
        }
        return views;
    }

    private static void tick() {
        try {
            RadminService.Status net = RadminService.refresh();
            Endpoint current = endpoint;
            misses = net.connected() ? 0 : misses + 1;
            if (!net.connected()) {
                if (current != null && misses >= 2) {
                    closeEndpoint();
                }
            } else if (current == null || !current.net.ip().equals(net.ip())) {
                closeEndpoint();
                bind(net);
            }
            RadminService.Status before = LAST_NET.getAndSet(net);
            if (!before.equals(net)) {
                LiveHub.emit("net", net);
            }
            if (endpoint != null) {
                maintain();
            }
        } catch (RuntimeException e) {
            Console.error("peer network tick failed", e);
        }
    }

    private static void maintain() {
        long now = System.currentTimeMillis();
        for (PeerLink link : List.copyOf(LINKS.values())) {
            if (now - link.lastRx > DEAD_AFTER_MS) {
                link.close();
            } else if (now - link.lastRx > IDLE_PING_MS) {
                JsonObject ping = new JsonObject();
                ping.addProperty("t", "ping");
                trySend(link, ping);
            }
        }
        BEACON_SEEN.values().removeIf(seen -> now - seen > 60_000);
        if (now - lastBeacon >= BEACON_EVERY_MS) {
            lastBeacon = now;
            beacon();
            redial();
        }
    }

    private static void bind(RadminService.Status net) {
        InetAddress address;
        try {
            address = InetAddress.getByName(net.ip());
        } catch (IOException e) {
            return;
        }
        for (int port = BASE_PORT; port < BASE_PORT + PORT_SPAN; port++) {
            ServerSocket server = null;
            try {
                server = new ServerSocket(port, 50, address);
                // Windows only delivers broadcasts to a wildcard-bound socket; sources outside the Radmin range are dropped unread.
                DatagramSocket udp = new DatagramSocket(null);
                udp.setBroadcast(true);
                udp.bind(new InetSocketAddress(port));
                Endpoint created = new Endpoint(net, port, server, udp);
                endpoint = created;
                Thread.startVirtualThread(() -> acceptLoop(created));
                Thread.startVirtualThread(() -> beaconLoop(created));
                Console.log("Rede privada pronta em %s:%d", net.ip(), port);
                return;
            } catch (IOException e) {
                closeQuietly(server);
            }
        }
        Console.warn("Nenhuma porta livre entre %d e %d para a rede privada.", BASE_PORT, BASE_PORT + PORT_SPAN - 1);
    }

    private static void closeEndpoint() {
        Endpoint e = endpoint;
        endpoint = null;
        if (e != null) {
            closeQuietly(e.server);
            e.udp.close();
            LINKS.values().forEach(PeerLink::close);
        }
    }

    private static void acceptLoop(Endpoint e) {
        while (!e.server.isClosed()) {
            try {
                Socket socket = e.server.accept();
                boolean admitted = LINKS.size() < MAX_LINKS && sameNetwork(socket.getInetAddress().getHostAddress(), e.net)
                        && HANDSHAKES.tryAcquire();
                if (!admitted) {
                    closeQuietly(socket);
                    continue;
                }
                Thread.startVirtualThread(() -> serve(socket, false, e));
            } catch (IOException ex) {
                return;
            }
        }
    }

    private static void beaconLoop(Endpoint e) {
        byte[] buffer = new byte[1024];
        while (!e.udp.isClosed()) {
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            try {
                e.udp.receive(packet);
            } catch (IOException ex) {
                return;
            }
            String source = packet.getAddress().getHostAddress();
            long now = System.currentTimeMillis();
            Long previous = BEACON_SEEN.put(source + ":" + packet.getPort(), now);
            if (previous != null && now - previous < 2_000 || !sameNetwork(source, e.net)) {
                continue;
            }
            onBeacon(new String(packet.getData(), 0, packet.getLength(), StandardCharsets.UTF_8), source);
        }
    }

    private static void onBeacon(String text, String source) {
        try {
            JsonObject o = GsonAPI.get().fromJson(text, JsonObject.class);
            byte[] pub = Crypto.unb64(o.get("pub").getAsString());
            int port = o.get("port").getAsInt();
            long ts = o.get("ts").getAsLong();
            String id = Crypto.fingerprint(pub);
            String signed = String.join("|", "AV1", "beacon", o.get("pub").getAsString(), o.get("name").getAsString(),
                    String.valueOf(port), String.valueOf(ts));
            boolean valid = pub.length == 44 && port > 1023 && port < 65536 && !id.equals(self.id())
                    && Math.abs(System.currentTimeMillis() - ts) <= FUTURE_TOLERANCE_MS
                    && Crypto.verify(pub, signed.getBytes(StandardCharsets.UTF_8), Crypto.unb64(o.get("sig").getAsString()));
            if (valid && !LINKS.containsKey(id)) {
                dial(source, port);
            }
        } catch (RuntimeException e) {
            Console.debug("beacon ignored: " + e.getClass().getSimpleName());
        }
    }

    private static void beacon() {
        Endpoint e = endpoint;
        if (e == null || e.net.broadcast().isBlank()) {
            return;
        }
        IdentityService.Self me = self;
        long ts = System.currentTimeMillis();
        String pub = Crypto.b64(me.publicKey());
        String signed = String.join("|", "AV1", "beacon", pub, me.name(), String.valueOf(e.port), String.valueOf(ts));
        JsonObject o = new JsonObject();
        o.addProperty("pub", pub);
        o.addProperty("name", me.name());
        o.addProperty("port", e.port);
        o.addProperty("ts", ts);
        o.addProperty("sig", Crypto.b64(me.sign(signed.getBytes(StandardCharsets.UTF_8))));
        byte[] data = GsonAPI.get().toJson(o).getBytes(StandardCharsets.UTF_8);
        try {
            InetAddress target = InetAddress.getByName(e.net.broadcast());
            for (int port = BASE_PORT; port < BASE_PORT + PORT_SPAN; port++) {
                e.udp.send(new DatagramPacket(data, data.length, target, port));
            }
        } catch (IOException ex) {
            Console.debug("beacon not sent: " + ex.getMessage());
        }
    }

    private static void redial() {
        Set<String> linkedHosts = new HashSet<>();
        LINKS.values().forEach(l -> linkedHosts.add(l.socket.getInetAddress().getHostAddress()));
        Set<String> wanted = new HashSet<>(TARGETS);
        long cutoff = System.currentTimeMillis() - RECENT_PEER_MS;
        Saveable.query(Peer.class, "SELECT data FROM " + Saveable.tableName(Peer.class)
                        + " WHERE json_extract(data,'$.lastSeen')>? LIMIT 50", cutoff)
                .stream().filter(p -> p.getLastIp() != null && !LINKS.containsKey(p.getId()))
                .forEach(p -> wanted.add(p.getLastIp() + ":" + p.getLastPort()));
        for (String target : wanted) {
            int colon = target.lastIndexOf(':');
            String host = target.substring(0, colon);
            if (!linkedHosts.contains(host)) {
                dial(host, Integer.parseInt(target.substring(colon + 1)));
            }
        }
    }

    private static void dial(String host, int port) {
        Endpoint e = endpoint;
        String key = host + ":" + port;
        Backoff backoff = BACKOFF.get(key);
        boolean own = e != null && host.equals(e.net.ip()) && port == e.port;
        if (e == null || own || LINKS.size() >= MAX_LINKS || DIALING.size() >= MAX_DIALS
                || (backoff != null && System.currentTimeMillis() < backoff.next) || !DIALING.add(key)) {
            return;
        }
        Thread.startVirtualThread(() -> {
            Socket socket = new Socket();
            try {
                if (!HANDSHAKES.tryAcquire()) {
                    return;
                }
                socket.bind(new InetSocketAddress(InetAddress.getByName(e.net.ip()), 0));
                socket.connect(new InetSocketAddress(host, port), 3_000);
                serve(socket, true, e);
            } catch (IOException ex) {
                BACKOFF.merge(key, new Backoff(0, 1), (old, fresh) -> old.failed());
                BACKOFF.computeIfPresent(key, (k, b) -> b.scheduled());
                closeQuietly(socket);
                HANDSHAKES.release();
            } finally {
                DIALING.remove(key);
            }
        });
    }

    private static void serve(Socket socket, boolean initiator, Endpoint e) {
        PeerLink link = null;
        boolean registered = false;
        boolean handshaking = true;
        try {
            socket.setTcpNoDelay(true);
            socket.setKeepAlive(true);
            socket.setSoTimeout(5_000);
            link = PeerLink.handshake(socket, initiator, self,
                    SettingsService.flag("privacy.shareAlias") ? e.net.alias() : "", e.port);
            link.send(authFrame(link));
            verifyAuth(link, link.receive());
            socket.setSoTimeout((int) DEAD_AFTER_MS);
            HANDSHAKES.release();
            handshaking = false;
            if (!register(link)) {
                return;
            }
            registered = true;
            BACKOFF.remove(socket.getInetAddress().getHostAddress() + ":" + link.peerPort);
            onConnected(link);
            int violations = 0;
            while (!socket.isClosed()) {
                JsonObject frame = link.receive();
                if (!link.allow() && ++violations > 200) {
                    return;
                }
                dispatch(link, frame);
            }
        } catch (IOException | RuntimeException ex) {
            Console.debug("link closed: " + ex.getClass().getSimpleName());
        } finally {
            if (handshaking) {
                HANDSHAKES.release();
            }
            if (link != null && registered) {
                unregister(link);
            }
            closeQuietly(socket);
        }
    }

    private static JsonObject authFrame(PeerLink link) {
        JsonArray tags = new JsonArray();
        for (Group group : GroupService.list()) {
            if (group.getSecret() != null) {
                tags.add(Crypto.b64(link.memberTag(Crypto.unb64url(group.getSecret()), self.id())));
            }
        }
        JsonArray peers = new JsonArray();
        LINKS.values().stream().limit(32).forEach(l -> {
            JsonObject p = new JsonObject();
            p.addProperty("ip", l.socket.getInetAddress().getHostAddress());
            p.addProperty("port", l.peerPort);
            peers.add(p);
        });
        JsonObject frame = new JsonObject();
        frame.addProperty("t", "auth");
        frame.add("tags", tags);
        frame.add("peers", peers);
        return frame;
    }

    private static void verifyAuth(PeerLink link, JsonObject auth) throws IOException {
        if (!"auth".equals(text(auth, "t", 16))) {
            throw new IOException("expected auth");
        }
        link.groups.add(GroupService.RADMIN);
        Set<String> offered = new HashSet<>();
        JsonArray tags = array(auth, "tags", 64);
        tags.forEach(t -> offered.add(t.getAsString()));
        for (Group group : GroupService.list()) {
            if (group.getSecret() != null) {
                byte[] expected = link.memberTag(Crypto.unb64url(group.getSecret()), link.peerId);
                if (offered.contains(Crypto.b64(expected))) {
                    link.groups.add(group.getId());
                }
            }
        }
        Endpoint e = endpoint;
        for (JsonElement el : array(auth, "peers", 32)) {
            JsonObject p = el.getAsJsonObject();
            String ip = p.get("ip").getAsString();
            int port = p.get("port").getAsInt();
            if (e != null && port > 1023 && port < 65536 && ip.matches("\\d{1,3}(\\.\\d{1,3}){3}") && sameNetwork(ip, e.net)
                    && TARGETS.size() < MAX_LEARNED && !ip.equals(e.net.ip())) {
                TARGETS.add(ip + ":" + port);
            }
        }
    }

    private static boolean register(PeerLink link) {
        PeerLink old = LINKS.putIfAbsent(link.peerId, link);
        if (old == null) {
            return true;
        }
        String newInitiator = link.initiator ? self.id() : link.peerId;
        String oldInitiator = old.initiator ? self.id() : old.peerId;
        if (newInitiator.compareTo(oldInitiator) < 0) {
            LINKS.put(link.peerId, link);
            old.close();
            return true;
        }
        return false;
    }

    private static void unregister(PeerLink link) {
        if (!LINKS.remove(link.peerId, link)) {
            return;
        }
        LiveHub.emit("peer.offline", Map.of("id", link.peerId, "name", link.peerName));
        Notifications.notify("Angatu Voice", link.peerName + " saiu da rede.");
        for (String room : CallService.dropPeer(link.peerId)) {
            emitCall(room);
        }
    }

    private static void onConnected(PeerLink link) {
        long now = System.currentTimeMillis();
        Peer peer = Saveable.findById(Peer.class, link.peerId);
        if (peer == null) {
            peer = new Peer();
            peer.setId(link.peerId);
            peer.setFirstSeen(now);
        }
        peer.setName(link.peerName);
        peer.setRadminAlias(link.peerAlias);
        peer.setPublicKey(Crypto.b64(link.peerKey));
        peer.setLastIp(link.socket.getInetAddress().getHostAddress());
        peer.setLastPort(link.peerPort);
        peer.setLastSeen(now);
        peer.setGroups(List.copyOf(link.groups));
        peer.save();
        LiveHub.emit("peer.online", Map.of("id", link.peerId, "name", link.peerName));
        Notifications.notify("Angatu Voice", link.peerName + " entrou na rede.");

        for (Map.Entry<String, List<String>> room : CallService.snapshot().entrySet()) {
            if (room.getValue().contains(self.id()) && roomAllowed(link, room.getKey())) {
                JsonObject call = new JsonObject();
                call.addProperty("t", "call");
                call.addProperty("room", room.getKey());
                call.addProperty("joined", true);
                trySend(link, call);
            }
        }
        for (Message pending : MessageService.pendingFor("dm:" + link.peerId)) {
            trySend(link, messageFrame(pending));
        }
        JsonObject since = new JsonObject();
        for (Group group : GroupService.list()) {
            if (link.groups.contains(group.getId())) {
                since.addProperty(group.getId(), MessageService.newest(group.getId()));
            }
        }
        JsonObject sync = new JsonObject();
        sync.addProperty("t", "sync");
        sync.add("since", since);
        trySend(link, sync);
    }

    private static void dispatch(PeerLink link, JsonObject frame) {
        switch (text(frame, "t", 16)) {
            case "msg" -> onMessage(link, frame);
            case "ack" -> onAck(link, frame);
            case "sync" -> onSync(link, frame);
            case "call" -> onCall(link, frame);
            case "sig" -> onSignal(link, frame);
            default -> { }
        }
    }

    private static void onMessage(PeerLink link, JsonObject f) {
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
            boolean routable = direct ? to.equals(self.id()) && from.equals(link.peerId) : link.groups.contains(chat);
            boolean valid = routable && id.startsWith(from + ":") && Crypto.fingerprint(key).equals(from)
                    && body.equals(MessageService.sanitize(body)) && !body.isEmpty()
                    && ts <= System.currentTimeMillis() + FUTURE_TOLERANCE_MS
                    && Crypto.verify(key, messageText(id, chat, to, ts, name, body),
                            Crypto.unb64(text(f, "sig", 128)));
            if (!valid) {
                return;
            }
            boolean mine = from.equals(self.id());
            Message m = new Message();
            m.setId(id);
            m.setChatId(direct ? "dm:" + from : chat);
            m.setSenderId(from);
            m.setSenderName(IdentityService.clean(name));
            m.setText(body);
            m.setSentAt(ts);
            m.setRead(mine);
            m.setAuthorKey(pub);
            m.setSignature(text(f, "sig", 128));
            if (MessageService.store(m)) {
                LiveHub.emit("message", MessageService.view(m, self.id()));
                if (!mine) {
                    String preview = !SettingsService.flag("privacy.preview") ? "Nova mensagem"
                            : body.length() > 120 ? body.substring(0, 120) + "…" : body;
                    Notifications.notify(m.getSenderName(), preview);
                }
            }
            if (direct) {
                JsonObject ack = new JsonObject();
                ack.addProperty("t", "ack");
                ack.addProperty("id", id);
                trySend(link, ack);
            }
        } catch (RuntimeException e) {
            Console.debug("message refused: " + e.getClass().getSimpleName());
        }
    }

    private static void onAck(PeerLink link, JsonObject f) {
        String id = text(f, "id", 80);
        Message m = id.startsWith(self.id() + ":") ? Saveable.findById(Message.class, id) : null;
        if (m != null && m.isPending() && m.getChatId().equals("dm:" + link.peerId)) {
            MessageService.delivered(id);
            LiveHub.emit("message.delivered", Map.of("id", id, "chat", m.getChatId()));
        }
    }

    private static void onSync(PeerLink link, JsonObject f) {
        if (!f.has("since") || !f.get("since").isJsonObject()) {
            return;
        }
        int chats = 0;
        for (Map.Entry<String, JsonElement> entry : f.getAsJsonObject("since").entrySet()) {
            if (++chats > 16 || !link.groups.contains(entry.getKey())) {
                continue;
            }
            for (Message m : MessageService.since(entry.getKey(), entry.getValue().getAsLong(), SYNC_BATCH)) {
                if (m.getSignature() != null && m.getAuthorKey() != null) {
                    trySend(link, messageFrame(m));
                }
            }
        }
    }

    private static void onCall(PeerLink link, JsonObject f) {
        String room = text(f, "room", 80);
        if (roomAllowed(link, room) && f.has("joined")) {
            CallService.set(room, link.peerId, f.get("joined").getAsBoolean());
            emitCall(room);
        }
    }

    private static void onSignal(PeerLink link, JsonObject f) {
        String room = text(f, "room", 80);
        String kind = text(f, "kind", 16);
        if (!roomAllowed(link, room) || !SIGNAL_KINDS.contains(kind) || !f.has("data")
                || f.get("data").toString().length() > MAX_SIGNAL_BYTES) {
            return;
        }
        LiveHub.emit("call.signal", Map.of("from", link.peerId, "room", room, "kind", kind, "data", f.get("data")));
    }

    private static boolean validRoom(String room, String me) {
        if (room.startsWith("dm:")) {
            return room.contains(me) && room.matches("dm:[0-9a-f]{20}\\+[0-9a-f]{20}");
        }
        return GROUP_ID.matcher(room).matches() ? GroupService.find(room) != null : room.equals(GroupService.RADMIN);
    }

    private static boolean roomAllowed(PeerLink link, String room) {
        String me = self.id();
        return room.startsWith("dm:") ? CallService.isDmRoom(room, me, link.peerId) : link.groups.contains(room)
                && validRoom(room, me);
    }

    private static void emitCall(String room) {
        LiveHub.emit("call.state", Map.of("room", room, "members", CallService.members(room)));
    }

    private static void broadcast(String chat, JsonObject frame) {
        for (PeerLink link : LINKS.values()) {
            if (link.groups.contains(chat)) {
                trySend(link, frame);
            }
        }
    }

    private static void trySend(PeerLink link, JsonObject frame) {
        try {
            link.send(frame);
        } catch (IOException e) {
            link.close();
        }
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

    private static boolean sameNetwork(String candidate, RadminService.Status net) {
        try {
            byte[] a = InetAddress.getByName(candidate).getAddress();
            byte[] b = InetAddress.getByName(net.ip()).getAddress();
            if (a.length != 4 || b.length != 4) {
                return false;
            }
            int bits = net.prefix();
            for (int i = 0; i < 4 && bits > 0; i++, bits -= 8) {
                int mask = bits >= 8 ? 0xFF : (0xFF << (8 - bits)) & 0xFF;
                if ((a[i] & mask) != (b[i] & mask)) {
                    return false;
                }
            }
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static void closeQuietly(AutoCloseable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (Exception ignored) {
            // closing a socket that is already gone has nothing left to report
        }
    }

    public record PeerView(String id, String name, String alias, boolean online, String ip, List<String> groups,
                           boolean duplicateName, long lastSeen) {
    }

    private record Endpoint(RadminService.Status net, int port, ServerSocket server, DatagramSocket udp) {
    }

    private record Backoff(long next, int failures) {

        Backoff failed() {
            return new Backoff(0, Math.min(failures + 1, 6));
        }

        Backoff scheduled() {
            return new Backoff(System.currentTimeMillis() + (5_000L << failures), failures);
        }
    }
}
