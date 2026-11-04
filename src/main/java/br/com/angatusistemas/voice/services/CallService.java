package br.com.angatusistemas.voice.services;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks who is in which call room and which calls are ringing here; the media itself is peer-to-peer WebRTC, so this
 * only keeps presence, room names and the incoming-call state the interface and the popup render.
 *
 * @author Angatu Sistemas
 */
public final class CallService {

    public static final long RING_MS = 45_000;
    private static final long MIN_RING_MS = 15_000;
    private static final Map<String, Set<String>> ROOMS = new ConcurrentHashMap<>();
    private static final Map<String, OwnCall> OWN = new ConcurrentHashMap<>();
    private static final Map<String, Incoming> RINGING = new ConcurrentHashMap<>();
    private static final Set<String> DECLINED = ConcurrentHashMap.newKeySet();

    private CallService() {
    }

    public static String dmRoom(String a, String b) {
        return a.compareTo(b) < 0 ? "dm:" + a + "+" + b : "dm:" + b + "+" + a;
    }

    public static boolean isDmRoom(String room, String first, String second) {
        return room.equals(dmRoom(first, second));
    }

    public static boolean set(String room, String peer, boolean joined) {
        boolean[] changed = {false};
        ROOMS.compute(room, (name, members) -> {
            Set<String> current = members == null ? ConcurrentHashMap.newKeySet() : members;
            changed[0] = joined ? current.add(peer) : current.remove(peer);
            return current.isEmpty() ? null : current;
        });
        return changed[0];
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

    public static List<String> roomsOf(String peer) {
        List<String> rooms = new ArrayList<>();
        ROOMS.forEach((room, members) -> {
            if (members.contains(peer)) {
                rooms.add(room);
            }
        });
        return rooms;
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

    public static void enter(String room, boolean caller, long now) {
        OWN.put(room, new OwnCall(room, caller, now));
    }

    public static void exit(String room) {
        OWN.remove(room);
    }

    public static List<OwnCall> own() {
        return List.copyOf(OWN.values());
    }

    public static boolean ring(String room, String callerId, String callerName, long ageMs, long now) {
        if (DECLINED.contains(room) || RINGING.containsKey(room)) {
            return false;
        }
        long remaining = Math.max(RING_MS - ageMs, MIN_RING_MS);
        return RINGING.putIfAbsent(room, new Incoming(room, callerId, callerName, now + remaining)) == null;
    }

    public static boolean decline(String room) {
        boolean ringing = RINGING.remove(room) != null;
        if (ringing) {
            DECLINED.add(room);
        }
        return ringing;
    }

    public static List<Incoming> ringing() {
        return List.copyOf(RINGING.values());
    }

    public static Settled settle(String room, String me) {
        if (isIn(room, me)) {
            DECLINED.remove(room);
            return RINGING.remove(room) != null ? Settled.ANSWERED : Settled.UNCHANGED;
        }
        if (members(room).isEmpty()) {
            DECLINED.remove(room);
            Incoming cancelled = RINGING.remove(room);
            return cancelled != null ? new Settled(cancelled) : Settled.UNCHANGED;
        }
        return Settled.UNCHANGED;
    }

    public static List<Incoming> expire(long now) {
        List<Incoming> missed = new ArrayList<>();
        for (Incoming call : RINGING.values()) {
            if (now >= call.until() && RINGING.remove(call.room(), call)) {
                missed.add(call);
            }
        }
        return missed;
    }

    public record OwnCall(String room, boolean caller, long since) {

        public boolean stillRinging(long now) {
            return caller && now - since < RING_MS;
        }
    }

    public record Incoming(String room, String callerId, String callerName, long until) {
    }

    public record Settled(boolean changed, Incoming missed) {

        static final Settled UNCHANGED = new Settled(false, null);
        static final Settled ANSWERED = new Settled(true, null);

        Settled(Incoming missed) {
            this(true, missed);
        }
    }
}
