package br.com.angatusistemas.voice.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * When an incoming call starts ringing and every way it stops: answered, declined, cancelled or simply unanswered.
 *
 * @author Angatu Sistemas
 */
class CallRingTest {

    private static final String ME = "me";
    private static final String CALLER = "caller";

    private static String room(String name) {
        CallService.set(name, CALLER, true);
        return name;
    }

    @Test
    void aFreshCallRingsForTheWholeWindow() {
        String room = room("ring-fresh");
        assertTrue(CallService.ring(room, CALLER, "Ana", 0, 1_000));
        assertEquals(1_000 + CallService.RING_MS, find(room).until());
    }

    @Test
    void aCallJoinedLateStillRingsLongEnoughToBeNoticed() {
        String room = room("ring-late");
        assertTrue(CallService.ring(room, CALLER, "Ana", CallService.RING_MS - 1_000, 1_000));
        assertTrue(find(room).until() >= 1_000 + 15_000);
    }

    @Test
    void theSameCallNeverRingsTwice() {
        String room = room("ring-once");
        assertTrue(CallService.ring(room, CALLER, "Ana", 0, 0));
        assertFalse(CallService.ring(room, CALLER, "Ana", 0, 0));
        assertEquals(1, CallService.ringing().stream().filter(c -> c.room().equals(room)).count());
    }

    @Test
    void answeringStopsTheRingWithoutMissedNotice() {
        String room = room("ring-answer");
        CallService.ring(room, CALLER, "Ana", 0, 0);
        CallService.set(room, ME, true);
        CallService.Settled settled = CallService.settle(room, ME);
        assertTrue(settled.changed());
        assertNull(settled.missed());
        assertNull(find(room));
    }

    @Test
    void aCancelledCallStopsTheRingAndCountsAsMissed() {
        String room = room("ring-cancel");
        CallService.ring(room, CALLER, "Ana", 0, 0);
        CallService.set(room, CALLER, false);
        CallService.Settled settled = CallService.settle(room, ME);
        assertTrue(settled.changed());
        assertNotNull(settled.missed());
        assertEquals("Ana", settled.missed().callerName());
    }

    @Test
    void aDeclinedCallStaysQuietUntilTheRoomEmpties() {
        String room = room("ring-decline");
        CallService.ring(room, CALLER, "Ana", 0, 0);
        assertTrue(CallService.decline(room));
        assertFalse(CallService.ring(room, CALLER, "Ana", 0, 0), "a link flap must not ring a declined call again");
        CallService.set(room, CALLER, false);
        CallService.settle(room, ME);
        CallService.set(room, CALLER, true);
        assertTrue(CallService.ring(room, CALLER, "Ana", 0, 0), "a new call in the same room rings normally");
    }

    @Test
    void anUnansweredCallExpiresAsMissed() {
        String room = room("ring-expire");
        CallService.ring(room, CALLER, "Ana", 0, 1_000);
        assertTrue(CallService.expire(1_000 + CallService.RING_MS - 1).stream().noneMatch(c -> c.room().equals(room)));
        List<CallService.Incoming> missed = CallService.expire(1_000 + CallService.RING_MS);
        assertTrue(missed.stream().anyMatch(c -> c.room().equals(room)));
        assertNull(find(room));
    }

    @Test
    void myOwnCallAnnouncesRingingOnlyWhileItIsYoungAndOnlyIfIStartedIt() {
        CallService.OwnCall started = new CallService.OwnCall("r", true, 1_000);
        CallService.OwnCall joined = new CallService.OwnCall("r", false, 1_000);
        assertTrue(started.stillRinging(1_000 + CallService.RING_MS - 1));
        assertFalse(started.stillRinging(1_000 + CallService.RING_MS));
        assertFalse(joined.stillRinging(1_000));
    }

    @Test
    void roomMembershipIsSafeToReadAfterTheLastMemberLeaves() {
        CallService.set("ring-empty", CALLER, true);
        assertTrue(CallService.roomsOf(CALLER).contains("ring-empty"));
        assertEquals(List.of("ring-empty"), CallService.dropPeer(CALLER).stream().filter("ring-empty"::equals).toList());
        assertTrue(CallService.members("ring-empty").isEmpty());
        assertFalse(CallService.snapshot().containsKey("ring-empty"));
    }

    private static CallService.Incoming find(String room) {
        return CallService.ringing().stream().filter(c -> c.room().equals(room)).findFirst().orElse(null);
    }
}
