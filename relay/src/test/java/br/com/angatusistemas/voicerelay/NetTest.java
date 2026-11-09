package br.com.angatusistemas.voicerelay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.angatusistemas.voicerelay.services.NetHub;
import com.google.gson.JsonObject;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The network channel on a real server and real sockets: proof of identity, presence across devices, routing, and the
 * legacy protocol old apps still speak.
 *
 * @author Angatu Sistemas
 */
class NetTest {

    @BeforeAll
    static void start() throws Exception {
        TestClient.startServer();
    }

    private static String legacyMessage(TestClient from, String to, String chat, String text) {
        String id = from.id + ":" + UUID.randomUUID();
        return "{\"t\":\"msg\",\"f\":{\"t\":\"msg\",\"id\":\"" + id + "\",\"chat\":\"" + chat + "\",\"to\":\"" + to + "\",\"from\":\""
                + from.id + "\",\"ts\":" + System.currentTimeMillis() + ",\"text\":\"" + text + "\",\"name\":\"Ana\"}}";
    }

    @Test
    void aSignatureThatProvesNothingIsRefused() throws Exception {
        TestClient liar = new TestClient();
        liar.connect("liar", "[]", false, true);
        assertEquals(NetHub.CLOSE_REFUSED, liar.closed.get(5, TimeUnit.SECONDS));
    }

    @Test
    void peopleSeeEachOtherComeAndGo() throws Exception {
        TestClient a = TestClient.legacy("Ana", "[]").ready();
        TestClient b = TestClient.legacy("Bia", "[]").ready();
        assertTrue(b.welcome.getAsJsonArray("peers").toString().contains(a.id), "the newcomer sees who is here");
        assertFalse(b.welcome.has("http"), "legacy apps get the old welcome");
        assertEquals(b.id, a.next("online").getAsJsonObject("peer").get("id").getAsString());
        b.send("{\"t\":\"bye\"}");
        assertEquals(b.id, a.next("offline").get("id").getAsString());
    }

    @Test
    void aSecondDeviceDoesNotReplaceTheFirstAndPresenceFollowsTheLastOne() throws Exception {
        TestClient watcher = TestClient.modern("Cid", "[]").ready();
        TestClient phone = TestClient.modern("Ana", "[]").ready();
        assertEquals(phone.id, watcher.next("online").getAsJsonObject("peer").get("id").getAsString());
        TestClient desk = phone.secondDevice().connect("Ana", "[]", true, true).ready();
        assertEquals(2, desk.welcome.get("proto").getAsInt());
        assertEquals(64, desk.welcome.get("http").getAsString().length());
        assertFalse(watcher.received("online", 300), "a second device is not a new arrival");
        assertFalse(phone.closed.isDone(), "both devices stay connected");

        watcher.send("{\"t\":\"route\",\"to\":\"" + phone.id + "\",\"f\":{\"t\":\"call\"}}");
        assertEquals(watcher.id, desk.next("route").get("from").getAsString());
        assertFalse(phone.received("route", 300), "signalling goes only to the most recent device");

        TestClient sameDevice = new TestClient(desk.keys, desk.device).connect("Ana", "[]", true, true).ready();
        assertEquals(NetHub.CLOSE_REPLACED, desk.closed.get(5, TimeUnit.SECONDS), "the same device replaces itself");
        phone.send("{\"t\":\"bye\"}");
        assertFalse(watcher.received("offline", 500), "still online on another device");
        sameDevice.send("{\"t\":\"bye\"}");
        assertEquals(phone.id, watcher.next("offline").get("id").getAsString());
    }

    @Test
    void framesGoOnlyToWhoTheyAreForAndCarryTheRealSender() throws Exception {
        TestClient a = TestClient.legacy("Ana", "[]").ready();
        TestClient b = TestClient.legacy("Bia", "[]").ready();
        TestClient c = TestClient.legacy("Cid", "[]").ready();
        a.send("{\"t\":\"route\",\"to\":\"" + b.id + "\",\"f\":{\"t\":\"sig\",\"from\":\"forged\"}}");
        JsonObject got = b.next("route");
        assertEquals(a.id, got.get("from").getAsString());
        assertEquals("sig", got.getAsJsonObject("f").get("t").getAsString());
        assertFalse(c.received("route", 200));
    }

    @Test
    void privateGroupsNeedTheRightToken() throws Exception {
        String group = "g_" + HexFormat.of().formatHex(new byte[] {1, 2, 3, 4, 5, 6, 7, (byte) (System.nanoTime() & 0x7f)});
        String good = "[{\"id\":\"" + group + "\",\"token\":\"" + "a".repeat(64) + "\"}]";
        String bad = "[{\"id\":\"" + group + "\",\"token\":\"" + "b".repeat(64) + "\"}]";
        TestClient member = TestClient.legacy("Ana", good).ready();
        TestClient also = TestClient.legacy("Bia", good).ready();
        TestClient outsider = TestClient.legacy("Cid", bad).ready();
        member.send("{\"t\":\"route\",\"to\":\"" + group + "\",\"f\":{\"t\":\"call\"}}");
        assertEquals(member.id, also.next("route").get("from").getAsString());
        assertFalse(outsider.received("route", 200));
        outsider.send("{\"t\":\"route\",\"to\":\"" + group + "\",\"f\":{\"t\":\"call\"}}");
        Thread.sleep(200);
        assertEquals(0, member.frames.stream().filter(f -> "route".equals(f.get("t").getAsString())).count());
    }

    @Test
    void someoneOfflineReceivesWhatTheyMissed() throws Exception {
        TestClient a = TestClient.legacy("Ana", "[]").ready();
        TestClient b = new TestClient();
        a.send(legacyMessage(a, b.id, "dm", "oi"));
        a.next("stored");
        b.connect("Bia", "[]", true, false).ready();
        b.send("{\"t\":\"sync\",\"since\":0}");
        JsonObject got = b.until("synced").stream().filter(f -> "route".equals(f.get("t").getAsString())
                && "oi".equals(f.getAsJsonObject("f").get("text").getAsString())).findFirst().orElseThrow();
        assertEquals(a.id, got.get("from").getAsString());
        assertTrue(got.get("seq").getAsLong() > 0);
        b.send("{\"t\":\"sync\",\"since\":" + got.get("seq").getAsLong() + "}");
        assertFalse(b.until("synced").stream().anyMatch(f -> f.toString().contains("\"oi\"")), "nothing twice");
    }

    @Test
    void aLegacyMessageReachesNewAppsInTheNewFormatAndIsStoredOnce() throws Exception {
        TestClient old = TestClient.legacy("Ana", "[]").ready();
        TestClient fresh = TestClient.modern("Bia", "[]").ready();
        TestClient oldPeer = TestClient.legacy("Cid", "[]").ready();
        String frame = legacyMessage(old, "", "publico", "ola de longe");
        old.send(frame);
        old.next("stored");
        JsonObject message = fresh.next("chat.new").getAsJsonObject("message");
        assertEquals("ola de longe", message.get("text").getAsString());
        assertEquals(old.id, message.get("from").getAsString());
        assertEquals("publico", message.get("chat").getAsString());
        assertFalse(message.has("frame"), "the legacy frame never reaches a new app");
        assertEquals("ola de longe", oldPeer.next("route").getAsJsonObject("f").get("text").getAsString());

        old.send(frame);
        old.next("stored");
        assertFalse(fresh.received("chat.new", 300), "a resend after a lost acknowledgement is not a second message");

        JsonObject page = fresh.ask("chat.history", "\"chat\":\"publico\",\"limit\":100");
        long copies = page.getAsJsonArray("messages").asList().stream()
                .filter(m -> m.getAsJsonObject().get("text").getAsString().equals("ola de longe")).count();
        assertEquals(1, copies);
    }

    @Test
    void newMessagesAreNotSentToLegacyApps() throws Exception {
        TestClient old = TestClient.legacy("Ana", "[]").ready();
        TestClient fresh = TestClient.modern("Bia", "[]").ready();
        JsonObject res = fresh.ask("chat.send", "\"chat\":\"publico\",\"nonce\":\"n" + UUID.randomUUID().toString().substring(0, 8)
                + "\",\"text\":\"so para novos\"");
        assertTrue(res.get("ok").getAsBoolean());
        assertFalse(old.received("route", 300));
        old.send("{\"t\":\"sync\",\"since\":0}");
        assertFalse(old.until("synced").stream().anyMatch(f -> f.toString().contains("so para novos")));
    }
}
