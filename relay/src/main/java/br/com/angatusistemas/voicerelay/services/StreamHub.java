package br.com.angatusistemas.voicerelay.services;

import br.com.angatusistemas.lib.console.Console;
import br.com.angatusistemas.lib.gson.GsonAPI;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import io.javalin.websocket.WsConfig;
import io.javalin.websocket.WsContext;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import org.eclipse.jetty.websocket.api.Callback;

/**
 * Passes each encoded frame from the person sharing to everyone watching, keeping nothing but a short queue per viewer.
 * A viewer that falls behind loses frames up to the next keyframe, so it never receives one it cannot decode.
 * Frames are opaque here: one flags byte (bit 0 = keyframe), then whatever the sender put behind it.
 *
 * @author Angatu Sistemas
 */
public final class StreamHub {

    public static final int CLOSE_REFUSED = 4403;
    public static final int CLOSE_NO_STREAM = 4404;
    public static final int CLOSE_ID_TAKEN = 4409;
    public static final int CLOSE_TOO_MUCH = 4413;
    public static final int CLOSE_LIMIT = 4429;

    private static final Pattern STREAM_ID = Pattern.compile("[0-9a-f]{32}");
    private static final int MAX_STREAMS = 32;
    private static final int MAX_VIEWERS = 16;
    private static final int MAX_CONNECTIONS_PER_ADDRESS = 32;
    private static final int MAX_FRAME_BYTES = 4 * 1024 * 1024;
    // Well above what the app sends (at most 16 Mbps plus keyframe bursts): this is only here against abuse of the server's bandwidth.
    private static final long MAX_BYTES_PER_SECOND = 5L * 1024 * 1024;
    private static final int VIEWER_QUEUE_FRAMES = 30;
    private static final long HELLO_TIMEOUT_MS = 5_000;
    private static final long SEND_TIMEOUT_SECONDS = 15;
    private static final long NOTICE_EVERY_MS = 1_000;
    private static final long PING_SECONDS = 25;
    private static final long IDLE_MINUTES = 2;

    private static final Map<String, Stream> STREAMS = new ConcurrentHashMap<>();
    private static final Map<String, Member> MEMBERS = new ConcurrentHashMap<>();
    private static final Map<String, AtomicInteger> CONNECTIONS = new ConcurrentHashMap<>();

    private StreamHub() {
    }

    public static void accept(WsConfig ws) {
        ws.onConnect(StreamHub::onConnect);
        ws.onMessage(ctx -> onText(ctx, ctx.message()));
        ws.onBinaryMessage(ctx -> onFrame(ctx, ctx.data()));
        ws.onClose(StreamHub::onGone);
        ws.onError(StreamHub::onGone);
    }

    public static int streamCount() {
        return STREAMS.size();
    }

    private static void onConnect(WsContext ctx) {
        String address = clientAddress(ctx);
        if (IpBlocklist.isBlocked(address)) {
            ctx.closeSession(CLOSE_REFUSED, "refused");
            return;
        }
        ctx.attribute("address", address);
        if (CONNECTIONS.computeIfAbsent(address, a -> new AtomicInteger()).incrementAndGet() > MAX_CONNECTIONS_PER_ADDRESS) {
            ctx.closeSession(CLOSE_LIMIT, "too many connections");
            return;
        }
        ctx.session.setMaxBinaryMessageSize(MAX_FRAME_BYTES);
        ctx.session.setMaxTextMessageSize(256);
        ctx.session.setIdleTimeout(java.time.Duration.ofMinutes(IDLE_MINUTES));
        ctx.enableAutomaticPings(PING_SECONDS, TimeUnit.SECONDS);
        Thread.startVirtualThread(() -> {
            sleep(HELLO_TIMEOUT_MS);
            if (ctx.session.isOpen() && !MEMBERS.containsKey(ctx.sessionId())) {
                ctx.closeSession(CLOSE_REFUSED, "no hello");
            }
        });
    }

    /* Behind Coolify's proxy the socket address is the proxy's, and only the entry the proxy itself appended can be believed. */
    static String clientAddress(WsContext ctx) {
        String forwarded = ctx.header("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.substring(forwarded.lastIndexOf(',') + 1).trim();
        }
        return ctx.session.getRemoteSocketAddress() instanceof java.net.InetSocketAddress socket
                ? socket.getAddress().getHostAddress() : "unknown";
    }

    private static void onText(WsContext ctx, String text) {
        Member member = MEMBERS.get(ctx.sessionId());
        if (member == null) {
            hello(ctx, text);
        } else if (member instanceof Viewer viewer && "key".equals(text)) {
            viewer.stream.notifyPublisher("key");
        }
    }

    private static void hello(WsContext ctx, String text) {
        JsonObject hello;
        try {
            hello = GsonAPI.get().fromJson(text, JsonObject.class);
        } catch (JsonParseException e) {
            ctx.closeSession(CLOSE_REFUSED, "bad hello");
            return;
        }
        String id = hello != null && hello.has("stream") ? hello.get("stream").getAsString() : "";
        String role = hello != null && hello.has("role") ? hello.get("role").getAsString() : "";
        if (!STREAM_ID.matcher(id).matches()) {
            ctx.closeSession(CLOSE_REFUSED, "bad stream id");
        } else if ("publish".equals(role)) {
            publish(ctx, id);
        } else if ("watch".equals(role)) {
            watch(ctx, id);
        } else {
            ctx.closeSession(CLOSE_REFUSED, "bad role");
        }
    }

    private static void publish(WsContext ctx, String id) {
        if (STREAMS.size() >= MAX_STREAMS) {
            ctx.closeSession(CLOSE_LIMIT, "server full");
            return;
        }
        Stream stream = new Stream(id, ctx);
        if (STREAMS.putIfAbsent(id, stream) != null) {
            ctx.closeSession(CLOSE_ID_TAKEN, "id taken");
            return;
        }
        MEMBERS.put(ctx.sessionId(), stream);
        ctx.send("ready");
    }

    private static void watch(WsContext ctx, String id) {
        Stream stream = STREAMS.get(id);
        if (stream == null) {
            ctx.closeSession(CLOSE_NO_STREAM, "no such stream");
            return;
        }
        if (stream.viewers.size() >= MAX_VIEWERS) {
            ctx.closeSession(CLOSE_LIMIT, "too many viewers");
            return;
        }
        Viewer viewer = new Viewer(ctx, stream);
        MEMBERS.put(ctx.sessionId(), viewer);
        stream.viewers.add(viewer);
        viewer.sender = Thread.startVirtualThread(viewer::sendLoop);
        ctx.send("ready");
        stream.notifyPublisher("key");
    }

    private static void onFrame(WsContext ctx, ByteBuffer data) {
        if (!(MEMBERS.get(ctx.sessionId()) instanceof Stream stream) || data.remaining() < 1) {
            return;
        }
        if (!stream.withinRate(data.remaining())) {
            ctx.closeSession(CLOSE_TOO_MUCH, "too much data");
            return;
        }
        byte[] frame = new byte[data.remaining()];
        data.get(frame);
        boolean key = (frame[0] & 1) != 0;
        for (Viewer viewer : stream.viewers) {
            viewer.offer(frame, key);
        }
    }

    private static void onGone(WsContext ctx) {
        String address = ctx.attribute("address");
        if (address != null && ctx.attribute("counted") == null) {
            ctx.attribute("counted", Boolean.TRUE);
            CONNECTIONS.computeIfPresent(address, (a, count) -> count.decrementAndGet() <= 0 ? null : count);
        }
        Member member = MEMBERS.remove(ctx.sessionId());
        if (member instanceof Stream stream) {
            STREAMS.remove(stream.id, stream);
            stream.viewers.forEach(Viewer::ended);
        } else if (member instanceof Viewer viewer) {
            viewer.stream.viewers.remove(viewer);
            viewer.sender.interrupt();
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private sealed interface Member permits Stream, Viewer {
    }

    private static final class Stream implements Member {

        private final String id;
        private final WsContext publisher;
        private final Set<Viewer> viewers = ConcurrentHashMap.newKeySet();
        private final Map<String, Long> noticedAt = new ConcurrentHashMap<>();
        private long windowStart;
        private long windowBytes;

        Stream(String id, WsContext publisher) {
            this.id = id;
            this.publisher = publisher;
        }

        /* One publisher socket delivers its messages one at a time, so this counter needs no lock. */
        boolean withinRate(int bytes) {
            long now = System.currentTimeMillis();
            if (now - windowStart >= 1_000) {
                windowStart = now;
                windowBytes = 0;
            }
            windowBytes += bytes;
            return windowBytes <= MAX_BYTES_PER_SECOND;
        }

        void notifyPublisher(String notice) {
            long now = System.currentTimeMillis();
            Long last = noticedAt.put(notice, now);
            if ((last == null || now - last >= NOTICE_EVERY_MS) && publisher.session.isOpen()) {
                publisher.send(notice);
            }
        }
    }

    private static final class Viewer implements Member {

        private final WsContext ctx;
        private final Stream stream;
        private final BlockingQueue<byte[]> queue = new ArrayBlockingQueue<>(VIEWER_QUEUE_FRAMES);
        private volatile boolean skipping = true;
        private Thread sender;

        Viewer(WsContext ctx, Stream stream) {
            this.ctx = ctx;
            this.stream = stream;
        }

        void offer(byte[] frame, boolean key) {
            if (key) {
                skipping = false;
            } else if (skipping) {
                return;
            }
            if (!queue.offer(frame)) {
                queue.clear();
                skipping = true;
                stream.notifyPublisher("slow");
                stream.notifyPublisher("key");
            }
        }

        /* Waiting for each send is what keeps a slow viewer's backlog in this short queue and not in unbounded socket buffers. */
        void sendLoop() {
            try {
                while (ctx.session.isOpen()) {
                    Callback.Completable sent = new Callback.Completable();
                    ctx.session.sendBinary(ByteBuffer.wrap(queue.take()), sent);
                    sent.get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (ExecutionException | TimeoutException e) {
                Console.debug("viewer dropped: " + e.getMessage());
                ctx.closeSession(CLOSE_LIMIT, "too slow");
            }
        }

        void ended() {
            sender.interrupt();
            if (ctx.session.isOpen()) {
                ctx.closeSession(CLOSE_NO_STREAM, "stream ended");
            }
        }
    }
}
