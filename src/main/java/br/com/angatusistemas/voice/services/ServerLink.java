package br.com.angatusistemas.voice.services;

import br.com.angatusistemas.lib.console.Console;
import br.com.angatusistemas.lib.gson.GsonAPI;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The one connection to the Angatu Voice server: proves this person's identity, keeps itself alive through network
 * hiccups and hands every frame to {@link PeerNetwork}. Frames are sent strictly one after another.
 *
 * @author Angatu Sistemas
 */
final class ServerLink implements WebSocket.Listener {

    static final int NO_RTT = -1;
    private static final long DEAD_AFTER_MS = 20_000;
    private static final long FIRST_RETRY_MS = 1_000;
    private static final long MAX_RETRY_MS = 15_000;

    private final URI address;
    private final Supplier<JsonObject> hello;
    private final IdentityService.Self self;
    private final Consumer<JsonObject> onFrame;
    private final Runnable onLost;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final StringBuilder partial = new StringBuilder();

    private volatile WebSocket socket;
    private volatile boolean ready;
    private volatile boolean stopping;
    private volatile long lastRx;
    private volatile String lastFailure = "";
    private volatile int rtt = NO_RTT;
    private volatile long holdUntil;
    private CompletableFuture<?> tail = CompletableFuture.completedFuture(null);
    private CompletableFuture<Void> finished = new CompletableFuture<>();

    ServerLink(String address, IdentityService.Self self, Supplier<JsonObject> hello, Consumer<JsonObject> onFrame, Runnable onLost) {
        this.address = URI.create(address);
        this.self = self;
        this.hello = hello;
        this.onFrame = onFrame;
        this.onLost = onLost;
    }

    void start() {
        Thread.startVirtualThread(this::connectLoop);
    }

    boolean isReady() {
        return ready;
    }

    int rtt() {
        return rtt;
    }

    private void connectLoop() {
        long wait = FIRST_RETRY_MS;
        while (!stopping) {
            try {
                long hold = holdUntil - System.currentTimeMillis();
                if (hold > 0) {
                    Thread.sleep(hold);
                }
                finished = new CompletableFuture<>();
                tail = CompletableFuture.completedFuture(null);
                lastRx = System.currentTimeMillis();
                http.newWebSocketBuilder().buildAsync(address, this).get();
                finished.get();
                wait = FIRST_RETRY_MS;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (java.util.concurrent.ExecutionException e) {
                noteFailure("server not reached: " + rootCause(e));
            }
            lost();
            if (stopping) {
                return;
            }
            try {
                Thread.sleep(wait + ThreadLocalRandom.current().nextLong(wait / 2 + 1));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            wait = Math.min(wait * 2, MAX_RETRY_MS);
        }
    }

    private void lost() {
        boolean was = ready;
        ready = false;
        rtt = NO_RTT;
        if (was) {
            onLost.run();
        }
    }

    /* After being kicked or banned, coming straight back would only be refused again. */
    void holdOff(long ms) {
        holdUntil = System.currentTimeMillis() + ms;
    }

    synchronized void send(JsonObject frame) {
        WebSocket target = socket;
        if (target == null || target.isOutputClosed()) {
            return;
        }
        String text = frame.toString();
        tail = tail.exceptionally(e -> null).thenCompose(ignored -> target.sendText(text, true));
    }

    /* Called every few seconds. A connection that went silent is dropped here: the operating system can take minutes to notice. */
    void keepAlive() {
        WebSocket target = socket;
        if (target == null || !ready) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastRx > DEAD_AFTER_MS) {
            target.abort();
            finished.complete(null);
            return;
        }
        JsonObject ping = new JsonObject();
        ping.addProperty("t", "ping");
        ping.addProperty("n", now);
        send(ping);
    }

    void stop() {
        stopping = true;
        WebSocket target = socket;
        if (target != null && ready) {
            JsonObject bye = new JsonObject();
            bye.addProperty("t", "bye");
            send(bye);
        }
    }

    /* The server's challenge can arrive before buildAsync returns, so the socket has to be known from here: a reply sent to nothing never comes back. */
    @Override
    public void onOpen(WebSocket ws) {
        socket = ws;
        ws.request(1);
    }

    @Override
    public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
        partial.append(data);
        if (last) {
            String text = partial.toString();
            partial.setLength(0);
            lastRx = System.currentTimeMillis();
            try {
                handle(GsonAPI.get().fromJson(text, JsonObject.class));
            } catch (JsonParseException | IllegalStateException | ClassCastException | IllegalArgumentException e) {
                Console.debug("frame ignored: " + e.getClass().getSimpleName());
            } catch (RuntimeException e) {
                Console.error("frame failed", e);
            }
        }
        ws.request(1);
        return null;
    }

    private void handle(JsonObject frame) {
        if (frame == null || !frame.has("t")) {
            return;
        }
        switch (frame.get("t").getAsString()) {
            case "challenge" -> {
                String signed = "AV3|auth|" + frame.get("n").getAsString();
                JsonObject out = hello.get();
                out.addProperty("t", "hello");
                out.addProperty("sig", br.com.angatusistemas.voice.utils.Crypto.b64(self.sign(signed.getBytes(StandardCharsets.UTF_8))));
                send(out);
            }
            case "welcome" -> {
                ready = true;
                lastFailure = "";
                onFrame.accept(frame);
            }
            case "pong" -> rtt = (int) Math.max(0, System.currentTimeMillis() - frame.get("n").getAsLong());
            default -> onFrame.accept(frame);
        }
    }

    @Override
    public CompletionStage<?> onClose(WebSocket ws, int code, String reason) {
        noteFailure("server closed the connection: " + code + " " + reason);
        finished.complete(null);
        return null;
    }

    @Override
    public void onError(WebSocket ws, Throwable error) {
        noteFailure("server link error: " + rootCause(error));
        finished.complete(null);
    }

    /* Retries repeat every few seconds for as long as the outage lasts: each different reason is logged once. */
    private void noteFailure(String reason) {
        if (!reason.equals(lastFailure)) {
            lastFailure = reason;
            Console.warn("%s", reason);
        }
    }

    private static String rootCause(Throwable error) {
        Throwable root = error;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root.getClass().getSimpleName() + ": " + root.getMessage();
    }
}
