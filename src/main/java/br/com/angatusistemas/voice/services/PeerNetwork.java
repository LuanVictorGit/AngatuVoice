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
import java.net.ConnectException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

/**
 * The private network engine: discovers peers over the Radmin adapter, authenticates them, keeps the links alive
 * through network hiccups and routes chat, call state and signalling between them. Everything it listens on is bound
 * to the Radmin address only.
 *
 * @author Angatu Sistemas
 */
public final class PeerNetwork {

    private static final int BASE_PORT = Integer.getInteger("angatu.peer.port", 47600);
    private static final int PORT_SPAN = 10;
    private static final int PROTOCOL = 2;
    private static final int MAX_LINKS = 64;
    private static final int MAX_DIALS = 16;
    private static final int MAX_LEARNED = 128;
    private static final int MAX_GOSSIP = 32;
    private static final int FORGET_TARGET_AFTER = 6;
    private static final int SYNC_BATCH = 200;
    private static final int MAX_SIGNAL_BYTES = 64 * 1024;
    private static final int CONNECT_TIMEOUT_MS = 3_000;
    private static final int HANDSHAKE_TIMEOUT_MS = 5_000;
    private static final long TICK_MS = 1_000;
    private static final long NET_PROBE_EVERY_MS = 5_000;
    private static final long ADAPTER_GRACE_MS = 30_000;
    private static final long BEACON_EVERY_MS = 10_000;
    private static final long REDIAL_EVERY_MS = 5_000;
    private static final long GOSSIP_EVERY_MS = 30_000;
    private static final long PING_EVERY_MS = 3_000;
    private static final long SUSPECT_AFTER_MS = 10_000;
    private static final long PROBE_EVERY_MS = 5_000;
    private static final long DEAD_AFTER_MS = 20_000;
    private static final long LEGACY_IDLE_PING_MS = 20_000;
    private static final long LEGACY_DEAD_AFTER_MS = 70_000;
    private static final long CROSSING_WINDOW_MS = 5_000;
    private static final long CROSSING_DELAY_MS = 400;
    private static final long FUTURE_TOLERANCE_MS = 10 * 60_000L;
    private static final long RECENT_PEER_MS = 7 * 24 * 3_600_000L;
    private static final long READ_ERROR_PAUSE_MS = 1_000;
    private static final Pattern PEER_ID = Pattern.compile("[0-9a-f]{20}");
    private static final Pattern GROUP_ID = Pattern.compile("g_[0-9a-f]{16}");
    private static final Pattern IPV4 = Pattern.compile("\\d{1,3}(\\.\\d{1,3}){3}");
    private static final Set<String> SIGNAL_KINDS = Set.of("offer", "answer", "candidate", "bye");

    private static final Map<String, PeerLink> LINKS = new ConcurrentHashMap<>();
    private static final Map<String, Backoff> BACKOFF = new ConcurrentHashMap<>();
    private static final Map<String, Long> BEACON_SEEN = new ConcurrentHashMap<>();
    private static final Set<String> DIALING = ConcurrentHashMap.newKeySet();
    private static final Set<String> TARGETS = ConcurrentHashMap.newKeySet();
    private static final Set<String> MANUAL = ConcurrentHashMap.newKeySet();
    private static final Semaphore HANDSHAKES = new Semaphore(8);
    private static final AtomicBoolean PROBING = new AtomicBoolean();
    private static final AtomicReference<RadminService.Status> LAST_NET = new AtomicReference<>(RadminService.current());

    private static volatile IdentityService.Self self;
    private static volatile Endpoint endpoint;
    private static volatile long lastProbe;
    private static volatile long lastBeacon;
    private static volatile long lastRedial;
    private static volatile long lastGossip;
    private static volatile long lastLatency;
    private static volatile long adapterLostAt;

    private PeerNetwork() {
    }

    public static void start() {
        self = IdentityService.load();
        Task.runTimerWithFixedDelay(PeerNetwork::tick, 0, TICK_MS);
    }

    public static void stop() {
        JsonObject bye = new JsonObject();
        bye.addProperty("t", "bye");
        List<Thread> flushing = new ArrayList<>();
        LINKS.values().forEach(link -> flushing.add(Thread.startVirtualThread(() -> link.closeAfter(bye))));
        for (Thread thread : flushing) {
            try {
                thread.join(1_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        closeEndpoint();
    }

    public static IdentityService.Self me() {
        return self;
    }

    public static int linkCount() {
        return LINKS.size();
    }

    public static int listenPort() {
        Endpoint e = endpoint;
        return e == null ? 0 : e.port;
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
                link.enqueue(frame);
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
        for (PeerLink link : LINKS.values()) {
            if (roomAllowed(link, room)) {
                link.enqueue(frame);
            }
        }
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
            PeerLink link = LINKS.get(member);
            if (link != null && roomAllowed(link, room)) {
                link.enqueue(frame);
            }
        }
        emitIncoming();
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
        link.enqueue(frame);
    }

    public static void addManual(String address) {
        int colon = address == null ? -1 : address.lastIndexOf(':');
        String host = colon < 0 ? address : address.substring(0, colon);
        int port = colon < 0 ? BASE_PORT : Integer.parseInt(address.substring(colon + 1));
        Endpoint e = endpoint;
        if (e == null || host == null || !IPV4.matcher(host).matches() || port < 1024 || port > 65535
                || !sameNetwork(host, e.net)) {
            throw new IllegalArgumentException("address outside the Radmin network");
        }
        String key = host + ":" + port;
        MANUAL.add(key);
        BACKOFF.remove(key);
        dial(host, port);
    }

    public static void forget(String id) {
        PeerLink link = LINKS.remove(id);
        if (link != null) {
            link.close();
        }
        PeerPresence.forget(id);
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
        long now = System.currentTimeMillis();
        List<PeerView> views = new ArrayList<>(known.size());
        for (Peer p : known) {
            PeerLink link = LINKS.get(p.getId());
            boolean unstable = link == null && PeerPresence.isLost(p.getId());
            List<String> groups = link != null ? List.copyOf(link.groups) : unstable ? p.getGroups() : List.of();
            views.add(new PeerView(p.getId(), p.getName(), p.getRadminAlias(), link != null || unstable, p.getLastIp(),
                    groups, names.get(p.getName().toLowerCase()) > 1, p.getLastSeen(), AvatarService.version(p.getAvatar()),
                    unstable, link == null ? PeerLink.NO_RTT : link.rtt(now)));
        }
        return views;
    }

    public static Map<String, Integer> latency() {
        long now = System.currentTimeMillis();
        Map<String, Integer> rtt = HashMap.newHashMap(LINKS.size());
        LINKS.values().forEach(link -> rtt.put(link.peerId, link.rtt(now)));
        PeerPresence.lost().forEach(lost -> rtt.putIfAbsent(lost.id(), PeerLink.NO_RTT));
        return rtt;
    }

    private static void tick() {
        try {
            long now = System.currentTimeMillis();
            if (now - lastProbe >= NET_PROBE_EVERY_MS && PROBING.compareAndSet(false, true)) {
                lastProbe = now;
                Thread.startVirtualThread(PeerNetwork::probeNetwork);
            }
            if (endpoint != null) {
                maintain(now);
            }
            settlePresence(now);
            CallService.expire(now).forEach(PeerNetwork::announceMissed);
            if (now - lastLatency >= PING_EVERY_MS) {
                lastLatency = now;
                LiveHub.emit("net.latency", Map.of("peers", latency()));
            }
        } catch (RuntimeException e) {
            Console.error("peer network tick failed", e);
        }
    }

    private static void probeNetwork() {
        try {
            RadminService.Status net = RadminService.refresh();
            Endpoint current = endpoint;
            long now = System.currentTimeMillis();
            if (net.connected()) {
                adapterLostAt = 0;
                if (current == null || !current.net.ip().equals(net.ip())) {
                    closeEndpoint();
                    bind(net);
                    BACKOFF.clear();
                    lastBeacon = 0;
                    lastRedial = 0;
                }
            } else if (current != null) {
                // Links often survive a short adapter blink, so the listeners and sockets are only dropped once it is really gone.
                if (adapterLostAt == 0) {
                    adapterLostAt = now;
                } else if (now - adapterLostAt >= ADAPTER_GRACE_MS) {
                    closeEndpoint();
                }
            }
            RadminService.Status before = LAST_NET.getAndSet(net);
            if (!before.equals(net)) {
                LiveHub.emit("net", net);
            }
        } catch (RuntimeException e) {
            Console.error("radmin probe failed", e);
        } finally {
            PROBING.set(false);
        }
    }

    private static void maintain(long now) {
        for (PeerLink link : List.copyOf(LINKS.values())) {
            keepAlive(link, now);
        }
        BEACON_SEEN.values().removeIf(seen -> now - seen > 60_000);
        for (PeerPresence.Lost lost : PeerPresence.lost()) {
            dial(lost.ip(), lost.port());
        }
        if (now - lastBeacon >= BEACON_EVERY_MS) {
            lastBeacon = now;
            beacon();
        }
        if (now - lastRedial >= REDIAL_EVERY_MS) {
            lastRedial = now;
            redial(now);
        }
        if (now - lastGossip >= GOSSIP_EVERY_MS) {
            lastGossip = now;
            gossip();
        }
    }

    private static void keepAlive(PeerLink link, long now) {
        long silence = now - link.lastRx;
        if (link.legacy) {
            if (silence > LEGACY_DEAD_AFTER_MS) {
                link.close();
            } else if (silence > LEGACY_IDLE_PING_MS && now - link.lastPing > LEGACY_IDLE_PING_MS) {
                link.lastPing = now;
                link.enqueue(ping());
            }
            return;
        }
        if (silence > DEAD_AFTER_MS) {
            link.close();
            return;
        }
        if (silence > SUSPECT_AFTER_MS && now - link.lastProbe > PROBE_EVERY_MS) {
            // The peer may have lost its end of this socket: a fresh connection replaces it without touching presence.
            link.lastProbe = now;
            dial(link.socket.getInetAddress().getHostAddress(), link.peerPort);
        }
        if (now - link.lastPing >= PING_EVERY_MS) {
            link.lastPing = now;
            link.enqueue(ping());
        }
    }

    private static JsonObject ping() {
        JsonObject ping = new JsonObject();
        ping.addProperty("t", "ping");
        ping.addProperty("n", System.nanoTime());
        return ping;
    }

    private static void settlePresence(long now) {
        for (PeerPresence.Lost lost : PeerPresence.expired(now)) {
            announceOffline(lost.id(), lost.name());
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
                Thread.startVirtualThread(() -> accepted(socket, e));
            } catch (IOException ex) {
                // A transient adapter error must not silently end the listener while the socket is still open.
                if (e.server.isClosed()) {
                    return;
                }
                pauseAfterReadError("accept", ex);
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
                if (e.udp.isClosed()) {
                    return;
                }
                pauseAfterReadError("beacon", ex);
                continue;
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

    private static void pauseAfterReadError(String what, IOException ex) {
        Console.debug(what + " socket error: " + ex.getMessage());
        try {
            Thread.sleep(READ_ERROR_PAUSE_MS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
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
                BACKOFF.remove(source + ":" + port);
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

    private static void redial(long now) {
        Set<String> linkedHosts = linkedHosts();
        TARGETS.removeIf(target -> {
            Backoff backoff = BACKOFF.get(target);
            return backoff != null && backoff.failures() >= FORGET_TARGET_AFTER;
        });
        Set<String> wanted = new HashSet<>(TARGETS);
        wanted.addAll(MANUAL);
        long cutoff = now - RECENT_PEER_MS;
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

    private static Set<String> linkedHosts() {
        Set<String> hosts = new HashSet<>();
        LINKS.values().forEach(l -> hosts.add(l.socket.getInetAddress().getHostAddress()));
        return hosts;
    }

    private static void dial(String host, int port) {
        Endpoint e = endpoint;
        String key = host + ":" + port;
        Backoff backoff = BACKOFF.get(key);
        boolean own = e != null && host.equals(e.net.ip()) && port == e.port;
        if (e == null || own || LINKS.size() >= MAX_LINKS || DIALING.size() >= MAX_DIALS
                || (backoff != null && System.currentTimeMillis() < backoff.next()) || !DIALING.add(key)) {
            return;
        }
        Thread.startVirtualThread(() -> connect(host, port, key, e));
    }

    private static void connect(String host, int port, String key, Endpoint e) {
        Socket socket = new Socket();
        PeerLink link = null;
        try {
            socket.bind(new InetSocketAddress(InetAddress.getByName(e.net.ip()), 0));
            socket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
            if (HANDSHAKES.tryAcquire()) {
                try {
                    link = establish(socket, true, e);
                    BACKOFF.remove(key);
                } finally {
                    HANDSHAKES.release();
                }
            }
        } catch (ConnectException ex) {
            // A refusal, unlike a timeout, proves the machine is reachable and the app is not listening: it left.
            PeerPresence.refused(host, port);
            noteFailure(key);
        } catch (IOException | RuntimeException ex) {
            Console.debug("dial failed: " + ex.getClass().getSimpleName());
            noteFailure(key);
        } finally {
            DIALING.remove(key);
            if (link == null) {
                closeQuietly(socket);
            }
        }
        if (link != null) {
            run(link);
        }
    }

    private static void noteFailure(String key) {
        long now = System.currentTimeMillis();
        BACKOFF.merge(key, Backoff.first(now), (old, fresh) -> old.failedAgain(now));
    }

    private static void accepted(Socket socket, Endpoint e) {
        PeerLink link = null;
        try {
            link = establish(socket, false, e);
        } catch (IOException | RuntimeException ex) {
            Console.debug("handshake failed: " + ex.getClass().getSimpleName());
        } finally {
            HANDSHAKES.release();
            if (link == null) {
                closeQuietly(socket);
            }
        }
        if (link != null) {
            run(link);
        }
    }

    private static PeerLink establish(Socket socket, boolean initiator, Endpoint e) throws IOException {
        socket.setTcpNoDelay(true);
        socket.setKeepAlive(true);
        socket.setSoTimeout(HANDSHAKE_TIMEOUT_MS);
        PeerLink link = PeerLink.handshake(socket, initiator, self,
                SettingsService.flag("privacy.shareAlias") ? e.net.alias() : "", e.port);
        link.send(authFrame(link));
        verifyAuth(link, link.receive());
        socket.setSoTimeout((int) (link.legacy ? LEGACY_DEAD_AFTER_MS : DEAD_AFTER_MS) + HANDSHAKE_TIMEOUT_MS);
        return link;
    }

    private static void run(PeerLink link) {
        if (!register(link)) {
            link.close();
            return;
        }
        link.startWriter();
        try {
            onConnected(link);
            while (!link.isClosed()) {
                JsonObject frame = link.receive();
                if (!link.allow()) {
                    Console.warn("Conexão com %s encerrada por excesso de mensagens.", link.peerName);
                    return;
                }
                dispatchSafely(link, frame);
            }
        } catch (IOException | RuntimeException ex) {
            Console.debug("link closed: " + ex.getClass().getSimpleName());
        } finally {
            unregister(link);
            link.close();
        }
    }

    private static JsonObject authFrame(PeerLink link) {
        JsonArray tags = new JsonArray();
        for (Group group : GroupService.list()) {
            if (group.getSecret() != null) {
                tags.add(Crypto.b64(link.memberTag(Crypto.unb64url(group.getSecret()), self.id())));
            }
        }
        JsonObject frame = new JsonObject();
        frame.addProperty("t", "auth");
        frame.addProperty("proto", PROTOCOL);
        frame.add("tags", tags);
        frame.add("peers", addressesFor(link));
        return frame;
    }

    private static JsonArray addressesFor(PeerLink receiver) {
        JsonArray peers = new JsonArray();
        LINKS.values().stream().filter(l -> l != receiver && !l.peerId.equals(receiver.peerId)).limit(MAX_GOSSIP)
                .forEach(l -> peers.add(address(l)));
        return peers;
    }

    private static JsonObject address(PeerLink link) {
        JsonObject p = new JsonObject();
        p.addProperty("ip", link.socket.getInetAddress().getHostAddress());
        p.addProperty("port", link.peerPort);
        return p;
    }

    private static void verifyAuth(PeerLink link, JsonObject auth) throws IOException {
        if (!"auth".equals(text(auth, "t", 16))) {
            throw new IOException("expected auth");
        }
        link.legacy = !auth.has("proto");
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
        learn(array(auth, "peers", MAX_GOSSIP));
    }

    private static void learn(JsonArray peers) {
        Endpoint e = endpoint;
        if (e == null) {
            return;
        }
        Set<String> linkedHosts = linkedHosts();
        for (JsonElement el : peers) {
            JsonObject p = el.getAsJsonObject();
            String ip = p.get("ip").getAsString();
            int port = p.get("port").getAsInt();
            boolean usable = port > 1023 && port < 65536 && IPV4.matcher(ip).matches() && sameNetwork(ip, e.net)
                    && !ip.equals(e.net.ip());
            if (usable) {
                if (TARGETS.size() < MAX_LEARNED) {
                    TARGETS.add(ip + ":" + port);
                }
                if (!linkedHosts.contains(ip)) {
                    dial(ip, port);
                }
            }
        }
    }

    private static void gossip() {
        LINKS.values().stream().filter(l -> !l.legacy).forEach(l -> {
            JsonObject frame = new JsonObject();
            frame.addProperty("t", "peers");
            frame.add("peers", addressesFor(l));
            l.enqueue(frame);
        });
    }

    private static boolean register(PeerLink link) {
        boolean[] accepted = {false};
        PeerLink[] replaced = {null};
        LINKS.compute(link.peerId, (id, current) -> {
            if (current == null || current.isClosed()) {
                accepted[0] = true;
                return link;
            }
            String freshInitiator = link.initiator ? self.id() : link.peerId;
            String currentInitiator = current.initiator ? self.id() : current.peerId;
            if (freshConnectionWins(freshInitiator, currentInitiator, System.currentTimeMillis() - current.establishedAt)) {
                accepted[0] = true;
                replaced[0] = current;
                return link;
            }
            return current;
        });
        if (replaced[0] != null) {
            link.replacedPrevious = true;
            replaced[0].close();
        }
        return accepted[0];
    }

    // Two dials that cross in flight are settled by the id order so both ends keep the same socket. Later than that, the
    // peer only dials because its end of the old socket died, so refusing it would leave one side offline until the
    // other side's timeout.
    static boolean freshConnectionWins(String freshInitiator, String currentInitiator, long currentAgeMs) {
        return currentAgeMs >= CROSSING_WINDOW_MS || freshInitiator.compareTo(currentInitiator) < 0;
    }

    private static void unregister(PeerLink link) {
        if (!LINKS.remove(link.peerId, link)) {
            return;
        }
        if (link.saidBye) {
            announceOffline(link.peerId, link.peerName);
            return;
        }
        String ip = link.socket.getInetAddress().getHostAddress();
        PeerPresence.linkLost(link.peerId, link.peerName, ip, link.peerPort, System.currentTimeMillis());
        LiveHub.emit("peer.changed", Map.of("id", link.peerId));
        // Both ends notice the drop together: the higher id waits a moment so the dials rarely cross.
        if (self.id().compareTo(link.peerId) < 0) {
            dial(ip, link.peerPort);
        } else {
            Task.runLater(() -> dial(ip, link.peerPort), CROSSING_DELAY_MS);
        }
    }

    private static void announceOffline(String id, String name) {
        LiveHub.emit("peer.offline", Map.of("id", id, "name", name));
        Notifications.notify("Angatu Voice", name + " saiu da rede.");
        dropFromRooms(id);
    }

    private static void dropFromRooms(String id) {
        for (String room : CallService.dropPeer(id)) {
            afterRoomChange(room);
        }
    }

    private static void onConnected(PeerLink link) {
        try {
            boolean recovered = PeerPresence.linkUp(link.peerId) || link.replacedPrevious;
            storePeer(link);
            if (recovered) {
                LiveHub.emit("peer.relinked", Map.of("id", link.peerId));
            } else {
                LiveHub.emit("peer.online", Map.of("id", link.peerId, "name", link.peerName));
                Notifications.notify("Angatu Voice", link.peerName + " entrou na rede.");
            }
            if (!link.legacy) {
                link.enqueue(roomsFrame(link));
            } else {
                announceRoomsToLegacy(link);
            }
            link.enqueue(profileFrame());
            for (Message pending : MessageService.pendingFor("dm:" + link.peerId)) {
                link.enqueue(messageFrame(pending));
            }
            link.enqueue(syncFrame(link));
            introduce(link);
        } catch (RuntimeException e) {
            // A failed catch-up must not cost the connection: the next link-up repeats it.
            Console.error("peer catch-up failed", e);
        }
    }

    private static void storePeer(PeerLink link) {
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
    }

    private static JsonObject roomsFrame(PeerLink link) {
        long now = System.currentTimeMillis();
        JsonArray rooms = new JsonArray();
        for (CallService.OwnCall call : CallService.own()) {
            if (roomAllowed(link, call.room())) {
                JsonObject room = new JsonObject();
                room.addProperty("room", call.room());
                room.addProperty("ring", call.stillRinging(now));
                room.addProperty("age", now - call.since());
                rooms.add(room);
            }
        }
        JsonObject frame = new JsonObject();
        frame.addProperty("t", "rooms");
        frame.add("rooms", rooms);
        return frame;
    }

    private static void announceRoomsToLegacy(PeerLink link) {
        for (CallService.OwnCall call : CallService.own()) {
            if (roomAllowed(link, call.room())) {
                JsonObject frame = new JsonObject();
                frame.addProperty("t", "call");
                frame.addProperty("room", call.room());
                frame.addProperty("joined", true);
                link.enqueue(frame);
            }
        }
    }

    private static JsonObject syncFrame(PeerLink link) {
        JsonObject since = new JsonObject();
        for (Group group : GroupService.list()) {
            if (link.groups.contains(group.getId())) {
                since.addProperty(group.getId(), MessageService.newest(group.getId()));
            }
        }
        JsonObject sync = new JsonObject();
        sync.addProperty("t", "sync");
        sync.add("since", since);
        return sync;
    }

    // Beacons may not cross every Radmin network, so the people already here learn the newcomer's address at once.
    private static void introduce(PeerLink link) {
        JsonArray only = new JsonArray();
        only.add(address(link));
        JsonObject frame = new JsonObject();
        frame.addProperty("t", "peers");
        frame.add("peers", only);
        LINKS.values().stream().filter(l -> l != link && !l.legacy).forEach(l -> l.enqueue(frame));
    }

    private static void dispatchSafely(PeerLink link, JsonObject frame) {
        try {
            dispatch(link, frame);
        } catch (RuntimeException e) {
            // Frames are authenticated, so one this version cannot digest is a bug or an old peer, not a reason to reconnect.
            Console.debug("frame ignored: " + e.getClass().getSimpleName());
        }
    }

    private static void dispatch(PeerLink link, JsonObject frame) {
        switch (text(frame, "t", 16)) {
            case "msg" -> onMessage(link, frame);
            case "ack" -> onAck(link, frame);
            case "sync" -> onSync(link, frame);
            case "call" -> onCall(link, frame);
            case "rooms" -> onRooms(link, frame);
            case "decline" -> onDecline(link, frame);
            case "sig" -> onSignal(link, frame);
            case "profile" -> onProfile(link, frame);
            case "ping" -> onPing(link, frame);
            case "pong" -> link.recordPong(frame.get("n").getAsLong());
            case "peers" -> learn(array(frame, "peers", MAX_GOSSIP));
            case "bye" -> {
                link.saidBye = true;
                link.close();
            }
            default -> { }
        }
    }

    private static void onPing(PeerLink link, JsonObject frame) {
        if (frame.has("n")) {
            JsonObject pong = new JsonObject();
            pong.addProperty("t", "pong");
            pong.add("n", frame.get("n"));
            link.enqueue(pong);
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
                link.enqueue(ack);
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
                    link.enqueue(messageFrame(m));
                }
            }
        }
    }

    private static void onCall(PeerLink link, JsonObject f) {
        String room = text(f, "room", 80);
        if (!roomAllowed(link, room) || !f.has("joined")) {
            return;
        }
        boolean joined = f.get("joined").getAsBoolean();
        boolean firstHere = CallService.members(room).isEmpty();
        CallService.set(room, link.peerId, joined);
        afterRoomChange(room);
        boolean rings = f.has("ring") ? f.get("ring").getAsBoolean() : link.legacy && firstHere;
        if (joined && rings) {
            ring(room, link, 0);
        }
    }

    // Unlike "call", this lists every room the peer is in right now, so whatever it left while unreachable is cleared.
    private static void onRooms(PeerLink link, JsonObject f) {
        Set<String> listed = new HashSet<>();
        for (JsonElement el : array(f, "rooms", MAX_LEARNED)) {
            JsonObject entry = el.getAsJsonObject();
            String room = text(entry, "room", 80);
            if (!roomAllowed(link, room)) {
                continue;
            }
            listed.add(room);
            if (CallService.set(room, link.peerId, true)) {
                afterRoomChange(room);
            }
            if (entry.has("ring") && entry.get("ring").getAsBoolean()) {
                ring(room, link, entry.has("age") ? entry.get("age").getAsLong() : 0);
            }
        }
        for (String room : CallService.roomsOf(link.peerId)) {
            if (!listed.contains(room)) {
                CallService.set(room, link.peerId, false);
                afterRoomChange(room);
            }
        }
    }

    private static void ring(String room, PeerLink link, long ageMs) {
        if (CallService.isIn(room, self.id()) || !CallService.isIn(room, link.peerId)) {
            return;
        }
        if (CallService.ring(room, link.peerId, link.peerName, ageMs, System.currentTimeMillis())) {
            emitIncoming();
        }
    }

    private static void onDecline(PeerLink link, JsonObject f) {
        String room = text(f, "room", 80);
        if (roomAllowed(link, room) && CallService.isIn(room, self.id())) {
            LiveHub.emit("call.declined", Map.of("room", room, "id", link.peerId, "name", link.peerName));
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

    public static void publishProfile() {
        JsonObject frame = profileFrame();
        LINKS.values().forEach(link -> link.enqueue(frame));
    }

    private static JsonObject profileFrame() {
        JsonObject frame = new JsonObject();
        frame.addProperty("t", "profile");
        frame.addProperty("avatar", AvatarService.own().orElse(""));
        return frame;
    }

    private static void onProfile(PeerLink link, JsonObject f) {
        try {
            String avatar = text(f, "avatar", AvatarService.MAX_BYTES * 4 / 3 + 8);
            AvatarService.setPeer(link.peerId, avatar);
            LiveHub.emit("peer.profile", Map.of("id", link.peerId));
        } catch (IllegalArgumentException e) {
            Console.debug("profile refused: " + e.getMessage());
        }
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

    private static void broadcast(String chat, JsonObject frame) {
        for (PeerLink link : LINKS.values()) {
            if (link.groups.contains(chat)) {
                link.enqueue(frame);
            }
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
                           boolean duplicateName, long lastSeen, String avatar, boolean unstable, int rtt) {
    }

    private record Endpoint(RadminService.Status net, int port, ServerSocket server, DatagramSocket udp) {
    }

    private record Backoff(long next, int failures) {

        private static final long BASE_MS = 1_000;
        private static final long MAX_MS = 30_000;

        static Backoff first(long now) {
            return new Backoff(now + delay(0), 1);
        }

        Backoff failedAgain(long now) {
            return new Backoff(now + delay(failures), failures + 1);
        }

        private static long delay(int failures) {
            long wait = Math.min(BASE_MS << Math.min(failures, 8), MAX_MS);
            return wait + ThreadLocalRandom.current().nextLong(wait / 4 + 1);
        }
    }
}
