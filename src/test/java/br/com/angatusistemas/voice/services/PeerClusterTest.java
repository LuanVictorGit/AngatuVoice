package br.com.angatusistemas.voice.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import br.com.angatusistemas.lib.gson.GsonAPI;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The real network engine over real sockets: several nodes in their own JVMs, some connected through a relay that stalls
 * or cuts traffic the way a bad connection does. These are the failures people see as friends leaving and coming back.
 *
 * @author Angatu Sistemas
 */
class PeerClusterTest {

    private static final AtomicInteger NEXT_SUBNET = new AtomicInteger(10);
    private static final AtomicInteger NEXT_PORT = new AtomicInteger(48_000);
    private static final String ROOM = "radmin";

    private final List<Node> nodes = new ArrayList<>();
    private final List<ChaosRelay> relays = new ArrayList<>();
    private final int subnet = NEXT_SUBNET.getAndIncrement();

    @TempDir
    Path temp;

    @AfterEach
    void stopEverything() throws Exception {
        for (Node node : nodes) {
            node.kill();
        }
        for (ChaosRelay relay : relays) {
            relay.close();
        }
    }

    @Test
    void aMeshClosesThroughGossipEvenWithoutBeacons() throws Exception {
        Node a = node(1);
        Node b = node(2);
        Node c = node(3);
        a.command("connect " + b.address());
        c.command("connect " + a.address());
        await("everyone linked to everyone", 6_000, () -> a.onlinePeers() == 2 && b.onlinePeers() == 2 && c.onlinePeers() == 2);
        await("ping measured on every link", 6_000, () -> a.measuredPeers() == 2 && b.measuredPeers() == 2 && c.measuredPeers() == 2);
    }

    @Test
    void aNetworkLargerThanEightPeopleStillFormsAFullMesh() throws Exception {
        // A live outgoing connection used to hold one of eight dial slots for its whole life, so the ninth was never made.
        List<Node> everyone = new ArrayList<>();
        for (int host = 1; host <= 10; host++) {
            everyone.add(node(host));
        }
        Node first = everyone.getFirst();
        for (Node other : everyone.subList(1, everyone.size())) {
            other.command("connect " + first.address());
        }
        await("everyone linked to all nine others", 60_000, () -> everyone.stream().allMatch(n -> n.links() == 9));
    }

    @Test
    void aCallRingsInRealTimeAndStopsWhenAnsweredOrCancelled() throws Exception {
        Node a = node(1);
        Node b = node(2);
        a.command("connect " + b.address());
        await("linked", 5_000, () -> a.onlinePeers() == 1 && b.onlinePeers() == 1);

        a.command("call " + ROOM);
        await("incoming call", 3_000, () -> b.incoming() == 1);
        b.command("call " + ROOM);
        await("ringing stops once answered", 1_500, () -> b.incoming() == 0);
        assertEquals(2, a.membersOf(ROOM));
        b.command("leave " + ROOM);
        a.command("leave " + ROOM);
        await("room empty", 2_000, () -> b.membersOf(ROOM) == 0 && a.membersOf(ROOM) == 0);

        a.command("call " + ROOM);
        await("incoming again", 3_000, () -> b.incoming() == 1);
        a.command("leave " + ROOM);
        await("cancelled", 2_000, () -> b.incoming() == 0);
        assertTrue(b.notices().contains("Chamada perdida."));
    }

    @Test
    void aDeclinedCallDoesNotRingAgainWhenTheLinkFlaps() throws Exception {
        Node a = node(1);
        Node b = node(2);
        a.command("connect " + b.address());
        await("linked", 5_000, () -> a.onlinePeers() == 1 && b.onlinePeers() == 1);
        a.command("call " + ROOM);
        await("incoming", 3_000, () -> b.incoming() == 1);
        b.command("decline " + ROOM);
        await("ringing stops", 1_500, () -> b.incoming() == 0);
        assertEquals(1, b.membersOf(ROOM));
    }

    @Test
    void someoneArrivingLateSeesTheCallAndRingsAtOnce() throws Exception {
        Node a = node(1);
        Node b = node(2);
        a.command("connect " + b.address());
        await("linked", 5_000, () -> a.onlinePeers() == 1 && b.onlinePeers() == 1);
        a.command("call " + ROOM);
        b.command("call " + ROOM);
        await("both in the call", 3_000, () -> a.membersOf(ROOM) == 2 && b.membersOf(ROOM) == 2);

        Node late = node(3);
        long start = System.currentTimeMillis();
        late.command("connect " + a.address());
        await("the late node sees every member", 4_000, () -> late.membersOf(ROOM) == 2);
        await("and rings, the call is still young", 1_500, () -> late.incoming() == 1);
        assertTrue(System.currentTimeMillis() - start < 5_500);
    }

    @Test
    void aShortStallKeepsTheSameConnectionAndNobodyLeaves() throws Exception {
        Pair pair = relayedPair();
        int before = pair.toB.connections() + pair.toA.connections();
        pair.stall(true);
        Thread.sleep(5_000);
        assertEquals(1, pair.a.onlinePeers());
        assertEquals(1, pair.b.onlinePeers());
        pair.stall(false);
        Thread.sleep(3_500);
        assertEquals(before, pair.toB.connections() + pair.toA.connections(), "no reconnection for a short hiccup");
        assertEquals(1, pair.a.links());
        assertEquals(1, pair.b.links());
        pair.assertNoPresenceNoise();
    }

    @Test
    void pingIsNotShownWhileTheLinkIsStalled() throws Exception {
        Pair pair = relayedPair();
        await("ping measured", 6_000, () -> pair.a.measuredPeers() == 1);
        pair.stall(true);
        await("stale value dropped", 15_000, () -> pair.a.measuredPeers() == 0);
        pair.stall(false);
        await("value comes back", 8_000, () -> pair.a.measuredPeers() == 1);
    }

    @Test
    void aHalfOpenConnectionHealsWithoutPresenceFlappingEitherWay() throws Exception {
        Pair pair = relayedPair();
        // Only the socket that reaches A is cut, so B's end looks alive but will never hear anything again.
        pair.toB.cutCallerSideOnly();
        pair.toA.cutTargetSideOnly();
        long start = System.currentTimeMillis();
        await("both ends linked over a new socket", 10_000, () -> pair.a.links() == 1 && pair.b.links() == 1 && pair.measuredBothWays());
        assertTrue(System.currentTimeMillis() - start < 10_000);
        pair.assertNoPresenceNoise();

        pair.toB.cutTargetSideOnly();
        pair.toA.cutCallerSideOnly();
        await("healed from the other side too", 10_000, () -> pair.a.links() == 1 && pair.b.links() == 1 && pair.measuredBothWays());
        pair.assertNoPresenceNoise();
    }

    @Test
    void anOutageLongerThanTheConnectionTimeoutIsStillInvisibleIfItEndsInTime() throws Exception {
        Pair pair = relayedPair();
        pair.a.command("call " + ROOM);
        await("the call is visible on B", 3_000, () -> pair.b.membersOf(ROOM) == 1);
        pair.b.command("decline " + ROOM);
        pair.stall(true);
        await("A dropped the dead socket", 30_000, () -> pair.a.links() == 0);
        assertEquals(1, pair.a.onlinePeers(), "still online during the grace");
        assertTrue(pair.a.isUnstable(0));
        assertEquals(1, pair.b.membersOf(ROOM), "the call membership survives the hiccup");
        pair.stall(false);
        await("reconnected", 15_000, () -> pair.a.links() == 1 && pair.b.links() == 1);
        pair.assertNoPresenceNoise();
        assertEquals(1, pair.b.membersOf(ROOM));
    }

    @Test
    void whatChangedDuringAnOutageIsCorrectedTheMomentTheLinkReturns() throws Exception {
        Pair pair = relayedPair();
        pair.a.command("call " + ROOM);
        await("the call is visible on B", 3_000, () -> pair.b.membersOf(ROOM) == 1);
        pair.stall(true);
        Thread.sleep(500);
        pair.a.command("leave " + ROOM);
        pair.toB.cutCallerSideOnly();
        pair.toB.cutTargetSideOnly();
        pair.toA.cutCallerSideOnly();
        pair.toA.cutTargetSideOnly();
        Thread.sleep(500);
        assertEquals(1, pair.b.membersOf(ROOM), "B cannot know yet");
        pair.stall(false);
        await("B corrected by the room list", 15_000, () -> pair.b.membersOf(ROOM) == 0);
    }

    @Test
    void anOrderlyQuitIsSeenAtOnceAndACrashWithinSeconds() throws Exception {
        Node a = node(1);
        Node b = node(2);
        Node c = node(3);
        a.command("connect " + b.address());
        a.command("connect " + c.address());
        await("linked", 6_000, () -> a.onlinePeers() == 2);
        a.command("call " + ROOM);
        b.command("call " + ROOM);
        await("A sees B in the call", 3_000, () -> a.membersOf(ROOM) == 2);

        b.quit();
        await("quit is immediate", 3_000, () -> a.onlinePeers() == 1 && a.membersOf(ROOM) == 1);

        c.kill();
        await("a crash ends in seconds, the port refuses", 15_000, () -> a.onlinePeers() == 0);
        assertEquals(2, a.notices().stream().filter(n -> n.contains("saiu")).count());
    }

    @Test
    void aPeerThatComesBackIsReconnectedQuickly() throws Exception {
        Node a = node(1);
        Node b = node(2);
        a.command("connect " + b.address());
        await("linked", 5_000, () -> a.onlinePeers() == 1 && b.onlinePeers() == 1);
        String address = b.address();
        b.quit();
        await("left", 2_000, () -> a.onlinePeers() == 0);
        Thread.sleep(4_000);
        Node back = node(2, b.dataDir);
        assertEquals(address, back.address());
        await("back automatically, nobody had to reconnect by hand", 40_000, () -> a.onlinePeers() == 1 && back.onlinePeers() == 1);
    }

    private Pair relayedPair() throws Exception {
        Node a = node(1);
        Node b = node(2);
        String viaForA = ip(11);
        String viaForB = ip(12);
        ChaosRelay toB = new ChaosRelay(viaForB, b.port, b.ip, b.port, viaForA);
        ChaosRelay toA = new ChaosRelay(viaForA, a.port, a.ip, a.port, viaForB);
        relays.add(toB);
        relays.add(toA);
        a.command("connect " + viaForB + ":" + b.port);
        await("linked through the relay", 5_000, () -> a.onlinePeers() == 1 && b.onlinePeers() == 1);
        await("settled", 6_000, () -> a.measuredPeers() == 1 && b.measuredPeers() == 1);
        return new Pair(a, b, toB, toA);
    }

    private String ip(int host) {
        return "127.0." + subnet + "." + host;
    }

    private Node node(int host) throws Exception {
        return node(host, temp.resolve("n" + host));
    }

    private Node node(int host, Path dataDir) throws Exception {
        int port = NEXT_PORT.addAndGet(20);
        Node existing = nodes.stream().filter(n -> n.dataDir.equals(dataDir)).findFirst().orElse(null);
        Node node = new Node(ip(host), existing != null ? existing.port : port, dataDir);
        nodes.add(node);
        return node;
    }

    private static void await(String what, long timeoutMs, BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(50);
        }
        fail("timed out after " + timeoutMs + " ms waiting for: " + what);
    }

    private record Pair(Node a, Node b, ChaosRelay toB, ChaosRelay toA) {

        void stall(boolean on) {
            toB.stall(on);
            toA.stall(on);
        }

        boolean measuredBothWays() {
            return a.measuredPeers() == 1 && b.measuredPeers() == 1;
        }

        void assertNoPresenceNoise() {
            for (Node node : List.of(a, b)) {
                long left = node.notices().stream().filter(n -> n.contains("saiu")).count();
                long joined = node.notices().stream().filter(n -> n.contains("entrou")).count();
                assertEquals(0, left, "nobody should have left: " + node.notices());
                assertEquals(1, joined, "only the first arrival is announced: " + node.notices());
            }
        }
    }

    private static final class Node {

        final String ip;
        final int port;
        final Path dataDir;
        final String id;
        private final Process process;
        private final PrintWriter in;
        private final BlockingQueue<String> answers = new LinkedBlockingQueue<>();
        private final Deque<String> lastOutput = new ConcurrentLinkedDeque<>();

        Node(String ip, int port, Path dataDir) throws Exception {
            this.ip = ip;
            this.port = port;
            this.dataDir = dataDir;
            Files.createDirectories(dataDir);
            String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
            ProcessBuilder builder = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                    "--enable-native-access=ALL-UNNAMED", "-Xshare:auto", "-XX:TieredStopAtLevel=1",
                    "-Dangatu.data=" + dataDir, "-Dangatu.local=" + dataDir.resolve("local"),
                    "-Dangatu.db=" + dataDir.resolve("database.db"), "-Dangatu.radmin.ip=" + ip, "-Dangatu.radmin.alias=node",
                    "-Dangatu.peer.port=" + port, "-Djdk.net.unixdomain.tmpdir=" + System.getProperty("jdk.net.unixdomain.tmpdir", "target"),
                    "-cp", classpath, TestNode.class.getName());
            builder.redirectErrorStream(true);
            process = builder.start();
            in = new PrintWriter(process.getOutputStream(), true, StandardCharsets.UTF_8);
            Thread.startVirtualThread(this::readLoop);
            id = next();
        }

        String address() {
            return ip + ":" + port;
        }

        String command(String line) {
            in.println(line);
            try {
                return next();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }

        void quit() throws InterruptedException {
            command("quit");
            process.waitFor(5, TimeUnit.SECONDS);
        }

        void kill() throws InterruptedException {
            process.destroyForcibly();
            process.waitFor(5, TimeUnit.SECONDS);
        }

        int onlinePeers() {
            int online = 0;
            for (JsonElement peer : state().getAsJsonArray("peers")) {
                online += peer.getAsJsonObject().get("online").getAsBoolean() ? 1 : 0;
            }
            return online;
        }

        boolean isUnstable(int index) {
            return state().getAsJsonArray("peers").get(index).getAsJsonObject().get("unstable").getAsBoolean();
        }

        int measuredPeers() {
            int measured = 0;
            for (var entry : state().getAsJsonObject("latency").entrySet()) {
                measured += entry.getValue().getAsInt() >= 0 ? 1 : 0;
            }
            return measured;
        }

        int links() {
            return state().get("links").getAsInt();
        }

        int incoming() {
            return state().getAsJsonArray("incoming").size();
        }

        int membersOf(String room) {
            JsonObject calls = state().getAsJsonObject("calls");
            return calls.has(room) ? calls.getAsJsonArray(room).size() : 0;
        }

        List<String> notices() {
            List<String> out = new ArrayList<>();
            state().getAsJsonArray("notices").forEach(n -> out.add(n.getAsString()));
            return out;
        }

        private JsonObject state() {
            return GsonAPI.get().fromJson(command("state"), JsonObject.class);
        }

        private String next() throws InterruptedException {
            String answer = answers.poll(20, TimeUnit.SECONDS);
            if (answer == null) {
                throw new IllegalStateException("node at " + address() + " stopped answering: " + String.join(" | ", lastOutput));
            }
            return answer;
        }

        private void readLoop() {
            try (BufferedReader out = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                for (String line = out.readLine(); line != null; line = out.readLine()) {
                    if (line.startsWith("=")) {
                        answers.add(line.substring(1));
                    } else {
                        lastOutput.addLast(line);
                        if (lastOutput.size() > 6) {
                            lastOutput.pollFirst();
                        }
                    }
                }
            } catch (IOException e) {
                answers.add("{}");
            }
        }
    }
}
