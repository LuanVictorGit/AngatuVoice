package br.com.angatusistemas.voice.services;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks who is in which call room; the media itself is peer-to-peer WebRTC, this only keeps presence and room names.
 *
 * @author Angatu Sistemas
 */
public final class CallService {

    private static final Map<String, Set<String>> ROOMS = new ConcurrentHashMap<>();

    private CallService() {
    }

    public static String dmRoom(String a, String b) {
        return a.compareTo(b) < 0 ? "dm:" + a + "+" + b : "dm:" + b + "+" + a;
    }

    public static boolean isDmRoom(String room, String first, String second) {
        return room.equals(dmRoom(first, second));
    }

    public static boolean set(String room, String peer, boolean joined) {
        if (joined) {
            return ROOMS.computeIfAbsent(room, r -> ConcurrentHashMap.newKeySet()).add(peer);
        }
        Set<String> members = ROOMS.get(room);
        boolean removed = members != null && members.remove(peer);
        if (members != null && members.isEmpty()) {
            ROOMS.remove(room, members);
        }
        return removed;
    }

    public static List<String> dropPeer(String peer) {
        List<String> left = new ArrayList<>();
        for (String room : List.copyOf(ROOMS.keySet())) {
            if (set(room, peer, false)) {
                left.add(room);
            }
        }
        return left;
    }

    public static boolean isIn(String room, String peer) {
        Set<String> members = ROOMS.get(room);
        return members != null && members.contains(peer);
    }

    public static List<String> members(String room) {
        Set<String> members = ROOMS.get(room);
        return members == null ? List.of() : List.copyOf(members);
    }

    public static Map<String, List<String>> snapshot() {
        Map<String, List<String>> out = new HashMap<>();
        ROOMS.forEach((room, members) -> out.put(room, List.copyOf(members)));
        return out;
    }
}
