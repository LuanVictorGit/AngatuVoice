package br.com.angatusistemas.voicerelay.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The DROP filter: matches by range in both families, rejects a cut download, and never blocks what it must not.
 *
 * @author Angatu Sistemas
 */
class IpBlocklistTest {

    private static final String BODY = """
            {"cidr":"1.10.16.0/20","sblid":"SBL1","rir":"apnic"}
            {"cidr":"2a06:e480::/29","sblid":"SBL2","rir":"ripencc"}
            {"type":"metadata","timestamp":1789220642,"size":100,"records":2,"copyright":"x"}
            """;

    private static void load(String body) {
        List<IpBlocklist.Range> ranges = new ArrayList<>(IpBlocklist.parse(body));
        ranges.sort(Comparator.comparingInt(IpBlocklist.Range::family).thenComparing(IpBlocklist.Range::start));
        IpBlocklist.replace(ranges);
    }

    @Test
    void matchesInsideTheRangeOnly() {
        load(BODY);
        assertTrue(IpBlocklist.isBlocked("1.10.16.1"));
        assertTrue(IpBlocklist.isBlocked("1.10.31.255"));
        assertFalse(IpBlocklist.isBlocked("1.10.32.0"));
        assertFalse(IpBlocklist.isBlocked("8.8.8.8"));
        assertTrue(IpBlocklist.isBlocked("2a06:e480::1"));
        assertFalse(IpBlocklist.isBlocked("2001:4860:4860::8888"));
    }

    @Test
    void privateRangesAndNonsenseAreNeverBlocked() {
        load("""
                {"cidr":"10.0.0.0/8"}
                {"type":"metadata","records":1}
                """);
        assertFalse(IpBlocklist.isBlocked("10.1.2.3"));
        assertFalse(IpBlocklist.isBlocked("127.0.0.1"));
        assertFalse(IpBlocklist.isBlocked("unknown"));
    }

    @Test
    void anEmptyListServesEveryone() {
        IpBlocklist.replace(List.of());
        assertFalse(IpBlocklist.isBlocked("1.10.16.1"));
    }

    @Test
    void aCutDownloadIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> IpBlocklist.parse("""
                {"cidr":"1.10.16.0/20"}
                {"type":"metadata","records":2}
                """));
        assertThrows(IllegalArgumentException.class, () -> IpBlocklist.parse("{\"cidr\":\"1.10.16.0/20\"}\n"));
        assertEquals(2, IpBlocklist.parse(BODY).size());
    }
}
