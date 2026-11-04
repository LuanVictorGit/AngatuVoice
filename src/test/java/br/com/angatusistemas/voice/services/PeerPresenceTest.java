package br.com.angatusistemas.voice.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The grace that keeps a network hiccup from reading as someone leaving, and the two rules for picking one socket when
 * peers reconnect.
 *
 * @author Angatu Sistemas
 */
class PeerPresenceTest {

    private static final String LOWER = "aaaa";
    private static final String HIGHER = "bbbb";

    @Test
    void aDroppedPeerStaysPresentDuringTheGrace() {
        PeerPresence.linkLost("grace", "Ana", "26.0.0.1", 47600, 1_000);
        assertTrue(PeerPresence.isLost("grace"));
        assertTrue(PeerPresence.expired(1_000 + PeerPresence.GRACE_MS - 1).stream().noneMatch(l -> l.id().equals("grace")));
        assertEquals(1, PeerPresence.expired(1_000 + PeerPresence.GRACE_MS).stream().filter(l -> l.id().equals("grace")).count());
        assertFalse(PeerPresence.isLost("grace"));
    }

    @Test
    void reconnectingInsideTheGraceIsNotAnArrival() {
        PeerPresence.linkLost("back", "Ana", "26.0.0.2", 47600, 1_000);
        assertTrue(PeerPresence.linkUp("back"), "a recovered peer must not be announced");
        assertFalse(PeerPresence.linkUp("back"), "a new link with no earlier drop is a real arrival");
    }

    @Test
    void aRefusedPortEndsTheGraceEarly() {
        PeerPresence.linkLost("crashed", "Ana", "26.0.0.3", 47600, 1_000);
        PeerPresence.refused("26.0.0.3", 47600);
        assertTrue(PeerPresence.expired(1_001).stream().noneMatch(l -> l.id().equals("crashed")));
        PeerPresence.refused("26.0.0.3", 47600);
        assertEquals(1, PeerPresence.expired(1_001).stream().filter(l -> l.id().equals("crashed")).count());
    }

    @Test
    void aRefusalFromAnotherAddressChangesNothing() {
        PeerPresence.linkLost("other", "Ana", "26.0.0.4", 47600, 1_000);
        PeerPresence.refused("26.0.0.99", 47600);
        PeerPresence.refused("26.0.0.99", 47600);
        assertTrue(PeerPresence.expired(1_001).stream().noneMatch(l -> l.id().equals("other")));
        PeerPresence.forget("other");
    }

    @Test
    void crossingDialsAreSettledTheSameWayOnBothEnds() {
        // Each end looks at the same two sockets, so the answer depends only on who dialled, not on who is asking.
        assertTrue(PeerNetwork.freshConnectionWins(LOWER, HIGHER, 100));
        assertFalse(PeerNetwork.freshConnectionWins(HIGHER, LOWER, 100));
        assertFalse(PeerNetwork.freshConnectionWins(LOWER, LOWER, 100));
    }

    @Test
    void aNewConnectionReplacesAnOldOneThatOnlyLooksAlive() {
        // The bug behind people showing as offline to one side only: an old socket used to win and refuse the new dial.
        assertTrue(PeerNetwork.freshConnectionWins(LOWER, LOWER, 30_000));
        assertTrue(PeerNetwork.freshConnectionWins(HIGHER, LOWER, 30_000));
        assertTrue(PeerNetwork.freshConnectionWins(HIGHER, HIGHER, 30_000));
    }
}
