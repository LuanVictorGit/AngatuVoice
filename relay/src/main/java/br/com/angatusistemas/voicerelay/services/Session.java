package br.com.angatusistemas.voicerelay.services;

import io.javalin.websocket.WsContext;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * One open connection of one person. A person may keep several at once, one per device; what they may read is decided
 * here, from the groups this connection proved and the person's own id.
 *
 * @author Angatu Sistemas
 */
public final class Session {

    static final int FLOOD_FRAMES_PER_SECOND = 200;
    static final int SENDS_PER_WINDOW = 10;
    static final long SEND_WINDOW_MS = 10_000;

    final WsContext ctx;
    final String id;
    final String pub;
    final String device;
    final int proto;
    final String address;
    final String http;
    final Set<String> groups = ConcurrentHashMap.newKeySet();
    final Map<String, Long> typing = new ConcurrentHashMap<>();
    volatile String name;
    volatile boolean saidBye;
    private long windowStart;
    private int windowFrames;
    private long sendWindowStart;
    private int sendWindowCount;

    Session(WsContext ctx, String id, String pub, String device, int proto, String address, String http, String name) {
        this.ctx = ctx;
        this.id = id;
        this.pub = pub;
        this.device = device;
        this.proto = proto;
        this.address = address;
        this.http = http;
        this.name = name;
    }

    public String id() {
        return id;
    }

    public String address() {
        return address;
    }

    public boolean canRead(String chat) {
        if (NetHub.PUBLIC_GROUP.equals(chat)) {
            return true;
        }
        if (chat.startsWith("dm:")) {
            int plus = chat.indexOf('+');
            return plus > 0 && (id.equals(chat.substring(3, plus)) || id.equals(chat.substring(plus + 1)));
        }
        return groups.contains(chat);
    }

    boolean open() {
        return ctx.session.isOpen();
    }

    boolean modern() {
        return proto >= 2;
    }

    void send(String text) {
        if (open()) {
            ctx.send(text);
        }
    }

    /* One socket delivers its frames one at a time, so these counters need no lock. */
    boolean allow() {
        long now = System.currentTimeMillis();
        if (now - windowStart >= 1_000) {
            windowStart = now;
            windowFrames = 0;
        }
        return ++windowFrames <= FLOOD_FRAMES_PER_SECOND;
    }

    boolean allowSend() {
        long now = System.currentTimeMillis();
        if (now - sendWindowStart >= SEND_WINDOW_MS) {
            sendWindowStart = now;
            sendWindowCount = 0;
        }
        return ++sendWindowCount <= SENDS_PER_WINDOW;
    }
}
