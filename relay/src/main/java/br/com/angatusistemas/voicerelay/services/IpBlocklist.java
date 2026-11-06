package br.com.angatusistemas.voicerelay.services;

import br.com.angatusistemas.lib.console.Console;
import br.com.angatusistemas.lib.gson.GsonAPI;
import br.com.angatusistemas.lib.task.Task;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import java.io.IOException;
import java.math.BigInteger;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Refuses the networks on the Spamhaus DROP list. It fails open: a list that cannot be fetched or read serves everyone,
 * because not knowing about an address does not make it hostile.
 *
 * @author Angatu Sistemas
 */
public final class IpBlocklist {

    private static final String[] SOURCES = {"https://www.spamhaus.org/drop/drop_v4.json", "https://www.spamhaus.org/drop/drop_v6.json"};
    private static final long REFRESH_MS = 3_600_000;
    private static final long FIRST_LOAD_MS = 2_000;
    private static final AtomicReference<List<Range>> RANGES = new AtomicReference<>(List.of());
    private static final AtomicLong BLOCKED = new AtomicLong();

    private IpBlocklist() {
    }

    public static void start() {
        Task.runTimerWithFixedDelay(IpBlocklist::refresh, FIRST_LOAD_MS, REFRESH_MS);
    }

    public static boolean isBlocked(String address) {
        InetAddress parsed;
        try {
            // Only ever a literal from the proxy header; anything else must not turn into a DNS lookup.
            if (!address.matches("[0-9a-fA-F:.]+")) {
                return false;
            }
            parsed = InetAddress.getByName(address);
        } catch (UnknownHostException e) {
            return false;
        }
        if (parsed.isLoopbackAddress() || parsed.isSiteLocalAddress() || parsed.isLinkLocalAddress()) {
            return false;
        }
        boolean blocked = contains(RANGES.get(), parsed.getAddress());
        if (blocked) {
            BLOCKED.incrementAndGet();
        }
        return blocked;
    }

    static void replace(List<Range> ranges) {
        RANGES.set(ranges);
    }

    private static void refresh() {
        try (HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()) {
            List<Range> all = new ArrayList<>();
            for (String source : SOURCES) {
                HttpRequest request = HttpRequest.newBuilder(URI.create(source)).timeout(Duration.ofSeconds(30)).GET().build();
                HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() != 200) {
                    throw new IOException(source + " answered " + response.statusCode());
                }
                all.addAll(parse(response.body()));
            }
            all.sort(Comparator.comparingInt((Range r) -> r.family).thenComparing(r -> r.start));
            RANGES.set(List.copyOf(all));
            Console.log("DROP list refreshed: %d ranges, %d connections refused so far", all.size(), BLOCKED.get());
        } catch (IOException | IllegalArgumentException e) {
            Console.warn("DROP list not refreshed, keeping the previous one: %s", e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /* JSON Lines, not an array. The last line says how many ranges there should be, which is how a cut download is caught. */
    static List<Range> parse(String body) {
        List<Range> ranges = new ArrayList<>();
        long expected = -1;
        for (String line : body.split("\n")) {
            if (line.isBlank()) {
                continue;
            }
            JsonObject entry;
            try {
                entry = GsonAPI.get().fromJson(line, JsonObject.class);
            } catch (JsonParseException e) {
                throw new IllegalArgumentException("unreadable line", e);
            }
            if (entry.has("cidr")) {
                ranges.add(Range.of(entry.get("cidr").getAsString()));
            } else if (entry.has("type") && "metadata".equals(entry.get("type").getAsString()) && entry.has("records")) {
                expected = entry.get("records").getAsLong();
            }
        }
        if (expected != ranges.size()) {
            throw new IllegalArgumentException("expected " + expected + " ranges, read " + ranges.size());
        }
        return ranges;
    }

    private static boolean contains(List<Range> ranges, byte[] raw) {
        BigInteger value = new BigInteger(1, raw);
        int low = 0;
        int high = ranges.size() - 1;
        while (low <= high) {
            int middle = (low + high) >>> 1;
            Range range = ranges.get(middle);
            int order = range.family != raw.length ? Integer.compare(range.family, raw.length) : range.start.compareTo(value);
            if (order > 0) {
                high = middle - 1;
            } else if (range.family == raw.length && value.compareTo(range.end) <= 0) {
                return true;
            } else {
                low = middle + 1;
            }
        }
        return false;
    }

    record Range(int family, BigInteger start, BigInteger end) {

        static Range of(String cidr) {
            int slash = cidr.indexOf('/');
            if (slash < 0) {
                throw new IllegalArgumentException("not a CIDR: " + cidr);
            }
            byte[] raw;
            try {
                raw = InetAddress.getByName(cidr.substring(0, slash)).getAddress();
            } catch (UnknownHostException e) {
                throw new IllegalArgumentException("not a CIDR: " + cidr, e);
            }
            int bits = raw.length * 8;
            int prefix = Integer.parseInt(cidr.substring(slash + 1));
            BigInteger mask = BigInteger.ONE.shiftLeft(bits - prefix).subtract(BigInteger.ONE);
            BigInteger start = new BigInteger(1, raw).andNot(mask);
            return new Range(raw.length, start, start.or(mask));
        }
    }
}
