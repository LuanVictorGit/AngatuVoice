package br.com.angatusistemas.voice.services;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Keeps a peer whose connection just dropped "online but unstable" for a short grace, so a network hiccup never reads
 * as leaving and coming back. A peer that refuses new connections is really gone, and that ends the grace early.
 *
 * @author Angatu Sistemas
 */
final class PeerPresence {

    static final long GRACE_MS = 20_000;
    private static final int REFUSALS_TO_LEAVE = 2;
    private static final Map<String, Lost> LOST = new ConcurrentHashMap<>();

    private PeerPresence() {
    }

    static void linkLost(String id, String name, String ip, int port, long now) {
        LOST.putIfAbsent(id, new Lost(id, name, ip, port, now, 0));
    }

    static boolean linkUp(String id) {
        return LOST.remove(id) != null;
    }

    static boolean isLost(String id) {
        return LOST.containsKey(id);
    }

    static void refused(String ip, int port) {
        LOST.replaceAll((id, lost) -> lost.ip().equals(ip) && lost.port() == port ? lost.refusedOnce() : lost);
    }

    static void forget(String id) {
        LOST.remove(id);
    }

    static List<Lost> lost() {
        return List.copyOf(LOST.values());
    }

    static List<Lost> expired(long now) {
        List<Lost> gone = new ArrayList<>();
        for (Lost lost : LOST.values()) {
            if ((now - lost.lostAt() >= GRACE_MS || lost.refusals() >= REFUSALS_TO_LEAVE) && LOST.remove(lost.id(), lost)) {
                gone.add(lost);
            }
        }
        return gone;
    }

    record Lost(String id, String name, String ip, int port, long lostAt, int refusals) {

        Lost refusedOnce() {
            return new Lost(id, name, ip, port, lostAt, refusals + 1);
        }
    }
}
