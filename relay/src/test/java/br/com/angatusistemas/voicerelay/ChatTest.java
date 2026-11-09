package br.com.angatusistemas.voicerelay;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.angatusistemas.voicerelay.services.NetHub;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpServer;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The server-owned chat on a real server with real sockets: sending, history, edits, reactions, replies, unread state
 * shared by devices, typing, membership, administration, attachments and link previews.
 *
 * @author Angatu Sistemas
 */
class ChatTest {

    private static final String PASSWORD = "senha-de-teste-123";

    @BeforeAll
    static void start() throws Exception {
        System.setProperty("angatu.admin.password", PASSWORD);
        TestClient.startServer();
    }

    private static String nonce() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 20);
    }

    private static JsonObject send(TestClient from, String chat, String text) throws InterruptedException {
        JsonObject res = from.ask("chat.send", "\"chat\":\"" + chat + "\",\"nonce\":\"" + nonce() + "\",\"text\":\"" + text + "\"");
        assertTrue(res.get("ok").getAsBoolean(), res.toString());
        return res.getAsJsonObject("message");
    }

    private static String group() {
        return "g_" + HexFormat.of().formatHex(UUID.randomUUID().toString().getBytes(), 0, 8);
    }

    private static String groups(String group) {
        return "[{\"id\":\"" + group + "\",\"token\":\"" + "c".repeat(64) + "\"}]";
    }

    private static String direct(String a, String b) {
        return "dm:" + (a.compareTo(b) < 0 ? a + "+" + b : b + "+" + a);
    }

    static byte[] webp(int width, int height, int padding) {
        byte[] file = new byte[64 + padding];
        System.arraycopy("RIFF".getBytes(StandardCharsets.US_ASCII), 0, file, 0, 4);
        System.arraycopy("WEBPVP8X".getBytes(StandardCharsets.US_ASCII), 0, file, 8, 8);
        file[16] = 10;
        int w = width - 1;
        int h = height - 1;
        file[24] = (byte) w;
        file[25] = (byte) (w >> 8);
        file[26] = (byte) (w >> 16);
        file[27] = (byte) h;
        file[28] = (byte) (h >> 8);
        file[29] = (byte) (h >> 16);
        return file;
    }

    @Test
    void aMessageIsStoredSpreadToEveryoneAndReadBackInOrder() throws Exception {
        String group = group();
        TestClient a = TestClient.modern("Ana", groups(group)).ready();
        TestClient b = TestClient.modern("Bia", groups(group)).ready();
        JsonObject first = send(a, group, "primeira");
        JsonObject second = send(a, group, "segunda");
        assertTrue(second.get("seq").getAsLong() > first.get("seq").getAsLong());
        assertEquals(Long.toString(first.get("seq").getAsLong()), first.get("id").getAsString());
        assertEquals("Ana", first.get("name").getAsString());
        assertEquals("primeira", b.next("chat.new").getAsJsonObject("message").get("text").getAsString());
        assertEquals("primeira", a.next("chat.new").getAsJsonObject("message").get("text").getAsString(), "the author's own session too");

        JsonObject page = b.ask("chat.history", "\"chat\":\"" + group + "\",\"limit\":10");
        JsonArray messages = page.getAsJsonArray("messages");
        assertEquals(2, messages.size());
        assertEquals("primeira", messages.get(0).getAsJsonObject().get("text").getAsString());
        assertEquals("segunda", messages.get(1).getAsJsonObject().get("text").getAsString());
        JsonArray older = b.ask("chat.history", "\"chat\":\"" + group + "\",\"before\":" + second.get("seq").getAsLong())
                .getAsJsonArray("messages");
        assertEquals(1, older.size());
        JsonArray newer = b.ask("chat.history", "\"chat\":\"" + group + "\",\"after\":" + first.get("seq").getAsLong())
                .getAsJsonArray("messages");
        assertEquals("segunda", newer.get(0).getAsJsonObject().get("text").getAsString());
        JsonArray around = b.ask("chat.history", "\"chat\":\"" + group + "\",\"around\":" + second.get("seq").getAsLong() + ",\"limit\":2")
                .getAsJsonArray("messages");
        assertEquals(2, around.size());
    }

    @Test
    void theSameNonceIsOneMessage() throws Exception {
        String group = group();
        TestClient a = TestClient.modern("Ana", groups(group)).ready();
        TestClient b = TestClient.modern("Bia", groups(group)).ready();
        String n = nonce();
        JsonObject one = a.ask("chat.send", "\"chat\":\"" + group + "\",\"nonce\":\"" + n + "\",\"text\":\"uma vez\"");
        JsonObject two = a.ask("chat.send", "\"chat\":\"" + group + "\",\"nonce\":\"" + n + "\",\"text\":\"uma vez\"");
        assertEquals(one.getAsJsonObject("message").get("id"), two.getAsJsonObject("message").get("id"));
        b.next("chat.new");
        assertFalse(b.received("chat.new", 300));
    }

    @Test
    void anyMemberPinsAndThePinListFollows() throws Exception {
        String group = group();
        TestClient a = TestClient.modern("Ana", groups(group)).ready();
        TestClient b = TestClient.modern("Bia", groups(group)).ready();
        TestClient outsider = TestClient.modern("Caio", "[]").ready();
        String first = send(a, group, "regras do grupo").get("id").getAsString();
        String second = send(a, group, "link da reuniao").get("id").getAsString();
        b.next("chat.new");
        b.next("chat.new");

        JsonObject pinned = b.ask("chat.pin", "\"id\":\"" + first + "\",\"on\":true").getAsJsonObject("message");
        assertTrue(pinned.get("pinned").getAsLong() > 0);
        assertEquals(b.id, pinned.get("pinnedBy").getAsString());
        assertTrue(a.next("chat.update").getAsJsonObject("message").get("pinned").getAsLong() > 0);
        Thread.sleep(5);
        b.ask("chat.pin", "\"id\":\"" + second + "\",\"on\":true");

        JsonArray pins = a.ask("chat.pins", "\"chat\":\"" + group + "\"").getAsJsonArray("messages");
        assertEquals(2, pins.size());
        assertEquals(second, pins.get(0).getAsJsonObject().get("id").getAsString());
        assertFalse(outsider.ask("chat.pin", "\"id\":\"" + first + "\",\"on\":true").get("ok").getAsBoolean());

        JsonObject unpinned = a.ask("chat.pin", "\"id\":\"" + first + "\",\"on\":false").getAsJsonObject("message");
        assertEquals(0, unpinned.get("pinned").getAsLong());
        assertEquals(1, b.ask("chat.pins", "\"chat\":\"" + group + "\"").getAsJsonArray("messages").size());
    }

    @Test
    void onlyTheAuthorEditsAndEveryoneSeesEditsReactionsAndDeletes() throws Exception {
        String group = group();
        TestClient a = TestClient.modern("Ana", groups(group)).ready();
        TestClient b = TestClient.modern("Bia", groups(group)).ready();
        JsonObject message = send(a, group, "erro de digitacao");
        String id = message.get("id").getAsString();
        b.next("chat.new");

        JsonObject refused = b.ask("chat.edit", "\"id\":\"" + id + "\",\"text\":\"invadido\"");
        assertFalse(refused.get("ok").getAsBoolean());
        assertEquals("Só quem escreveu pode editar a mensagem.", refused.get("error").getAsString());

        JsonObject edited = a.ask("chat.edit", "\"id\":\"" + id + "\",\"text\":\"corrigido\"").getAsJsonObject("message");
        assertEquals("corrigido", edited.get("text").getAsString());
        assertTrue(edited.get("edited").getAsLong() > 0);
        assertEquals("corrigido", b.next("chat.update").getAsJsonObject("message").get("text").getAsString());
        a.next("chat.update");

        JsonObject reacted = b.ask("chat.react", "\"id\":\"" + id + "\",\"emoji\":\"👍\",\"on\":true").getAsJsonObject("message");
        JsonObject reaction = reacted.getAsJsonArray("reactions").get(0).getAsJsonObject();
        assertEquals("👍", reaction.get("emoji").getAsString());
        assertEquals(1, reaction.get("count").getAsInt());
        assertEquals(b.id, reaction.getAsJsonArray("users").get(0).getAsString());
        assertEquals(1, a.next("chat.update").getAsJsonObject("message").getAsJsonArray("reactions").size());
        JsonObject undone = b.ask("chat.react", "\"id\":\"" + id + "\",\"emoji\":\"👍\",\"on\":false").getAsJsonObject("message");
        assertEquals(0, undone.getAsJsonArray("reactions").size());
        assertFalse(b.ask("chat.react", "\"id\":\"" + id + "\",\"emoji\":\"ok\",\"on\":true").get("ok").getAsBoolean());

        assertFalse(b.ask("chat.delete", "\"id\":\"" + id + "\"").get("ok").getAsBoolean());
        assertTrue(a.ask("chat.delete", "\"id\":\"" + id + "\"").get("ok").getAsBoolean());
        JsonObject deleted = b.next("chat.delete");
        assertEquals(id, deleted.get("id").getAsString());
        assertEquals(group, deleted.get("chat").getAsString());
        assertEquals(0, b.ask("chat.history", "\"chat\":\"" + group + "\"").getAsJsonArray("messages").size());
    }

    @Test
    void repliesCarryTheirReferenceAndMentionsAreCounted() throws Exception {
        String group = group();
        TestClient a = TestClient.modern("Ana", groups(group)).ready();
        TestClient b = TestClient.modern("Bia", groups(group)).ready();
        JsonObject original = send(b, group, "pergunta");
        JsonObject reply = a.ask("chat.send", "\"chat\":\"" + group + "\",\"nonce\":\"" + nonce() + "\",\"replyTo\":\""
                + original.get("id").getAsString() + "\",\"text\":\"resposta para <@" + b.id + ">\"").getAsJsonObject("message");
        JsonObject ref = reply.getAsJsonObject("ref");
        assertEquals("pergunta", ref.get("text").getAsString());
        assertEquals(b.id, ref.get("from").getAsString());
        assertEquals(original.get("seq").getAsLong(), ref.get("seq").getAsLong());
        assertEquals(0, ref.get("attachments").getAsInt());
        assertFalse(ref.get("missing").getAsBoolean());
        assertEquals(b.id, reply.getAsJsonArray("mentions").get(0).getAsString());

        JsonArray page = a.ask("chat.history", "\"chat\":\"" + group + "\",\"after\":" + original.get("seq").getAsLong())
                .getAsJsonArray("messages");
        assertEquals("pergunta", page.get(0).getAsJsonObject().getAsJsonObject("ref").get("text").getAsString(),
                "a reference outside the page is resolved too");

        JsonObject unread = b.ask("chat.unread", "").getAsJsonObject("chats").getAsJsonObject(group);
        assertEquals(1, unread.get("count").getAsInt(), "one's own messages are never unread");
        assertEquals(1, unread.get("mentions").getAsInt());
        assertEquals(reply.get("seq").getAsLong(), unread.get("lastSeq").getAsLong());

        assertFalse(a.ask("chat.delete", "\"id\":\"" + original.get("id").getAsString() + "\"").get("ok").getAsBoolean(), "not hers");
        assertTrue(b.ask("chat.delete", "\"id\":\"" + original.get("id").getAsString() + "\"").get("ok").getAsBoolean());
        assertFalse(b.ask("chat.delete", "\"id\":\"" + original.get("id").getAsString() + "\"").get("ok").getAsBoolean(), "already gone");
        JsonObject orphan = a.ask("chat.history", "\"chat\":\"" + group + "\"").getAsJsonArray("messages").get(0).getAsJsonObject();
        assertTrue(orphan.getAsJsonObject("ref").get("missing").getAsBoolean());
    }

    @Test
    void privateMessagesReachEveryDeviceAndReadingSyncsBetweenThem() throws Exception {
        TestClient a = TestClient.modern("Ana", "[]").ready();
        TestClient phone = TestClient.modern("Bia", "[]").ready();
        TestClient desk = phone.secondDevice().connect("Bia", "[]", true, true).ready();
        TestClient outsider = TestClient.modern("Cid", "[]").ready();
        JsonObject message = send(a, "dm:" + phone.id, "so entre nos");
        String chat = direct(a.id, phone.id);
        assertEquals(chat, message.get("chat").getAsString());
        assertEquals("so entre nos", phone.next("chat.new").getAsJsonObject("message").get("text").getAsString());
        assertEquals("so entre nos", desk.next("chat.new").getAsJsonObject("message").get("text").getAsString());
        assertFalse(outsider.received("chat.new", 300));

        JsonObject unread = phone.ask("chat.unread", "").getAsJsonObject("chats").getAsJsonObject(chat);
        assertEquals(1, unread.get("count").getAsInt());
        assertEquals(0, unread.get("readSeq").getAsLong());
        assertTrue(phone.ask("chat.read", "\"chat\":\"dm:" + a.id + "\",\"seq\":" + message.get("seq").getAsLong()).get("ok").getAsBoolean());
        JsonObject read = desk.next("chat.read");
        assertEquals(chat, read.get("chat").getAsString());
        assertEquals(message.get("seq").getAsLong(), read.get("seq").getAsLong());
        JsonObject after = desk.ask("chat.unread", "").getAsJsonObject("chats").getAsJsonObject(chat);
        assertEquals(0, after.get("count").getAsInt());
        assertEquals(message.get("seq").getAsLong(), after.get("readSeq").getAsLong());
        phone.ask("chat.read", "\"chat\":\"" + chat + "\",\"seq\":1");
        assertEquals(message.get("seq").getAsLong(), desk.next("chat.read").get("seq").getAsLong(), "reading never goes backwards");
    }

    @Test
    void typingReachesTheOthersAtMostEveryThreeSeconds() throws Exception {
        TestClient a = TestClient.modern("Ana", "[]").ready();
        TestClient b = TestClient.modern("Bia", "[]").ready();
        a.send("{\"t\":\"chat.typing\",\"chat\":\"dm:" + b.id + "\"}");
        JsonObject typing = b.next("chat.typing");
        assertEquals(a.id, typing.get("from").getAsString());
        assertEquals("Ana", typing.get("name").getAsString());
        a.send("{\"t\":\"chat.typing\",\"chat\":\"dm:" + b.id + "\"}");
        assertFalse(b.received("chat.typing", 300));
        assertFalse(a.received("chat.typing", 0), "nobody sees their own typing");
    }

    @Test
    void nobodyReadsOrWritesAChatTheyAreNotIn() throws Exception {
        String group = group();
        TestClient a = TestClient.modern("Ana", groups(group)).ready();
        TestClient b = TestClient.modern("Bia", "[]").ready();
        TestClient c = TestClient.modern("Cid", "[]").ready();
        JsonObject secret = send(a, "dm:" + b.id, "segredo");
        JsonObject inGroup = send(a, group, "do grupo");

        JsonObject history = c.ask("chat.history", "\"chat\":\"" + group + "\"");
        assertFalse(history.get("ok").getAsBoolean());
        assertEquals("Você não participa desta conversa.", history.get("error").getAsString());
        assertFalse(c.ask("chat.history", "\"chat\":\"" + direct(a.id, b.id) + "\"").get("ok").getAsBoolean());
        assertFalse(c.ask("chat.send", "\"chat\":\"" + group + "\",\"nonce\":\"" + nonce() + "\",\"text\":\"oi\"").get("ok").getAsBoolean());
        JsonObject react = c.ask("chat.react", "\"id\":\"" + secret.get("id").getAsString() + "\",\"emoji\":\"😀\",\"on\":true");
        assertEquals("Mensagem não encontrada.", react.get("error").getAsString());
        assertFalse(c.ask("chat.delete", "\"id\":\"" + inGroup.get("id").getAsString() + "\"").get("ok").getAsBoolean());
        assertFalse(c.ask("chat.history", "\"chat\":\"nada\"").get("ok").getAsBoolean());
        assertFalse(c.ask("chat.unread", "").getAsJsonObject("chats").has(direct(a.id, b.id)));
    }

    @Test
    void textIsCleanedAndBounded() throws Exception {
        TestClient a = TestClient.modern("Ana", "[]").ready();
        JsonObject clean = send(a, "publico", "  ola\\u202e mundo\\u0007  ");
        assertEquals("ola mundo", clean.get("text").getAsString());
        JsonObject tooLong = a.ask("chat.send", "\"chat\":\"publico\",\"nonce\":\"" + nonce() + "\",\"text\":\"" + "x".repeat(4001) + "\"");
        assertEquals("A mensagem passa de 4.000 caracteres.", tooLong.get("error").getAsString());
        assertFalse(a.ask("chat.send", "\"chat\":\"publico\",\"nonce\":\"" + nonce() + "\",\"text\":\"   \"").get("ok").getAsBoolean());
    }

    @Test
    void administratorsKickMuteBanAndClear() throws Exception {
        TestClient admin = TestClient.modern("Chefe", "[]").ready();
        TestClient adminDesk = admin.secondDevice().connect("Chefe", "[]", true, true).ready();
        TestClient target = TestClient.modern("Alvo", "[]").ready();
        assertFalse(admin.welcome.getAsJsonObject("admin").get("admin").getAsBoolean());

        assertEquals("Ative o modo administrador para fazer isso.",
                admin.ask("admin.kick", "\"id\":\"" + target.id + "\"").get("error").getAsString());
        assertEquals("Senha incorreta.", admin.ask("admin.login", "\"password\":\"errada\"").get("error").getAsString());
        JsonObject login = admin.ask("admin.login", "\"password\":\"" + PASSWORD + "\"");
        assertTrue(login.getAsJsonObject("admin").get("active").getAsBoolean());
        assertTrue(adminDesk.next("admin").get("active").getAsBoolean(), "the other device learns it too");

        assertTrue(admin.ask("admin.mute", "\"id\":\"" + target.id + "\",\"minutes\":5").get("ok").getAsBoolean());
        assertTrue(target.next("muted").get("until").getAsLong() > System.currentTimeMillis());
        assertFalse(target.ask("chat.send", "\"chat\":\"publico\",\"nonce\":\"" + nonce() + "\",\"text\":\"oi\"").get("ok").getAsBoolean());
        admin.ask("admin.mute", "\"id\":\"" + target.id + "\",\"minutes\":0");
        assertEquals(0, target.next("muted").get("until").getAsLong());
        JsonObject spoken = send(target, "publico", "voltei");

        assertTrue(admin.ask("chat.delete", "\"id\":\"" + spoken.get("id").getAsString() + "\"").get("ok").getAsBoolean(),
                "an administrator deletes anyone's message");

        TestClient otherAdmin = TestClient.modern("Outro", "[]").ready();
        otherAdmin.ask("admin.login", "\"password\":\"" + PASSWORD + "\"");
        assertEquals("Não é possível agir sobre um administrador.",
                admin.ask("admin.kick", "\"id\":\"" + otherAdmin.id + "\"").get("error").getAsString());

        admin.ask("admin.kick", "\"id\":\"" + target.id + "\",\"reason\":\"teste\"");
        assertEquals("teste", target.next("kicked").get("reason").getAsString());
        assertEquals(NetHub.CLOSE_KICKED, target.closed.get(5, TimeUnit.SECONDS));

        TestClient banned = new TestClient().connect("Banido", "[]", true, true, "203.0.113.77").ready();
        admin.ask("admin.ban", "\"id\":\"" + banned.id + "\",\"reason\":\"spam\"");
        banned.next("banned");
        assertEquals(NetHub.CLOSE_REFUSED, banned.closed.get(5, TimeUnit.SECONDS));
        TestClient again = new TestClient(banned.keys, banned.device).connect("Banido", "[]", true, true);
        again.next("banned");
        assertEquals(NetHub.CLOSE_REFUSED, again.closed.get(5, TimeUnit.SECONDS));
        TestClient sameAddress = new TestClient().connect("Vizinho", "[]", true, true, "203.0.113.77");
        sameAddress.next("banned");
        JsonArray bans = admin.ask("admin.bans", "").getAsJsonArray("bans");
        assertTrue(bans.toString().contains(banned.id));
        admin.ask("admin.unban", "\"id\":\"" + banned.id + "\"");
        new TestClient(banned.keys, banned.device).connect("Banido", "[]", true, true, "203.0.113.77").ready();

        String group = group();
        TestClient member = TestClient.modern("Membro", groups(group)).ready();
        send(member, group, "vai sumir");
        TestClient groupAdmin = adminDesk;
        JsonObject cleared = groupAdmin.ask("admin.clear", "\"chat\":\"" + group + "\"");
        assertEquals(1, cleared.get("removed").getAsInt());
        assertEquals(group, member.next("chat.cleared").get("chat").getAsString());

        JsonObject stats = admin.ask("admin.stats", "");
        assertTrue(stats.get("messageBytes").getAsLong() > 0);
        assertTrue(stats.has("messageCount") && stats.has("attachmentBytes") && stats.get("attachmentLimit").getAsLong() > 0);

        admin.ask("admin.mode", "\"active\":false");
        assertFalse(adminDesk.next("admin").get("active").getAsBoolean());
        assertFalse(admin.ask("admin.stats", "").get("ok").getAsBoolean(), "powers only while the mode is on");
    }

    @Test
    void attachmentsAreCheckedStoredAndOnlyServedToTheChat() throws Exception {
        TestClient a = TestClient.modern("Ana", "[]").ready();
        TestClient b = TestClient.modern("Bia", "[]").ready();
        TestClient c = TestClient.modern("Cid", "[]").ready();
        byte[] image = webp(800, 600, 2000);
        HttpResponse<byte[]> uploaded = a.upload("foto.webp", "image/webp", image);
        assertEquals(201, uploaded.statusCode(), new String(uploaded.body(), StandardCharsets.UTF_8));
        JsonObject ref = TestClient.json(uploaded.body());
        assertEquals("image/webp", ref.get("type").getAsString());
        assertEquals(800, ref.get("width").getAsInt());
        assertEquals(600, ref.get("height").getAsInt());
        assertEquals(image.length, ref.get("size").getAsLong());
        String id = ref.get("id").getAsString();
        assertEquals(200, a.get("/api/files/" + id).statusCode(), "the uploader sees it before sending");
        assertEquals(404, b.get("/api/files/" + id).statusCode(), "nobody else does yet");

        JsonObject sent = a.ask("chat.send", "\"chat\":\"dm:" + b.id + "\",\"nonce\":\"" + nonce() + "\",\"text\":\"\",\"attachments\":[\"" + id + "\"]");
        assertTrue(sent.get("ok").getAsBoolean(), sent.toString());
        assertEquals(id, sent.getAsJsonObject("message").getAsJsonArray("attachments").get(0).getAsJsonObject().get("id").getAsString());
        HttpResponse<byte[]> download = b.get("/api/files/" + id);
        assertEquals(200, download.statusCode());
        assertArrayEquals(image, download.body());
        assertEquals("nosniff", download.headers().firstValue("X-Content-Type-Options").orElse(""));
        assertTrue(download.headers().firstValue("Content-Disposition").orElse("").startsWith("inline"));
        assertEquals(404, c.get("/api/files/" + id).statusCode());
        JsonObject reuse = a.ask("chat.send", "\"chat\":\"publico\",\"nonce\":\"" + nonce() + "\",\"text\":\"\",\"attachments\":[\"" + id + "\"]");
        assertFalse(reuse.get("ok").getAsBoolean(), "an attachment belongs to one message");

        HttpResponse<byte[]> text = a.upload("notas.txt", "text/plain", "linha um\nlinha dois".getBytes(StandardCharsets.UTF_8));
        assertEquals(201, text.statusCode());
        JsonObject textRef = TestClient.json(text.body());
        assertEquals("linha um\nlinha dois".length(), textRef.get("size").getAsLong());
        HttpResponse<byte[]> textBack = a.get("/api/files/" + textRef.get("id").getAsString());
        assertTrue(textBack.headers().firstValue("Content-Disposition").orElse("").startsWith("attachment"));
        assertEquals("linha um\nlinha dois", new String(textBack.body(), StandardCharsets.UTF_8));

        byte[] voice = new byte[300];
        voice[0] = 0x1A;
        voice[1] = 0x45;
        voice[2] = (byte) 0xDF;
        voice[3] = (byte) 0xA3;
        HttpResponse<byte[]> recorded = a.upload("Mensagem de voz.webm", "audio/webm;codecs=opus", voice);
        assertEquals(201, recorded.statusCode());
        JsonObject voiceRef = TestClient.json(recorded.body());
        assertEquals("audio/webm", voiceRef.get("type").getAsString());
        assertEquals("Mensagem de voz.webm", voiceRef.get("name").getAsString());
        HttpResponse<byte[]> played = a.get("/api/files/" + voiceRef.get("id").getAsString());
        assertEquals("audio/webm", played.headers().firstValue("Content-Type").orElse(""));
        assertTrue(played.headers().firstValue("Content-Disposition").orElse("").startsWith("inline"));
        byte[] video = voice.clone();
        assertEquals("video/webm", TestClient.json(a.upload("tela.webm", "video/webm", video).body()).get("type").getAsString());
        byte[] mp3 = new byte[200];
        mp3[0] = 'I';
        mp3[1] = 'D';
        mp3[2] = '3';
        assertEquals("audio/mpeg", TestClient.json(a.upload("musica.mp3", "audio/mpeg", mp3).body()).get("type").getAsString());

        byte[] png = new byte[100];
        png[0] = (byte) 0x89;
        png[1] = 'P';
        png[2] = 'N';
        png[3] = 'G';
        assertEquals(415, a.upload("x.png", "image/png", png).statusCode());
        assertEquals(415, a.upload("grande.webp", "image/webp", webp(3000, 1000, 10)).statusCode());
        assertEquals(413, a.upload("enorme.bin", "application/octet-stream", new byte[25 * 1024 * 1024 + 1]).statusCode());
        HttpResponse<byte[]> noToken = TestClient.HTTP.send(java.net.http.HttpRequest.newBuilder(
                java.net.URI.create("http://" + TestClient.base + "/api/files/" + id)).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(401, noToken.statusCode());
    }

    @Test
    void linksBecomePreviewsWithTheirImageServedThroughTheServer() throws Exception {
        System.setProperty("angatu.fetch.allowLoopback", "true");
        HttpServer site = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        byte[] picture = webp(10, 10, 100);
        site.createContext("/pagina", exchange -> {
            byte[] html = ("<html><head><title>Fallback</title><meta property=\"og:title\" content=\"T&iacute;tulo &amp; mais\">"
                    + "<meta property='og:description' content='Uma descri&#231;&#xE3;o'><meta name=\"theme-color\" content=\"#123abc\">"
                    + "<meta property=\"og:site_name\" content=\"Site\"><meta property=\"og:image\" content=\"/foto.webp\">"
                    + "<meta property=\"og:image:width\" content=\"10\"></head><body>corpo</body></html>").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(200, html.length);
            exchange.getResponseBody().write(html);
            exchange.close();
        });
        site.createContext("/foto.webp", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "image/webp");
            exchange.sendResponseHeaders(200, picture.length);
            exchange.getResponseBody().write(picture);
            exchange.close();
        });
        site.createContext("/salto", exchange -> {
            exchange.getResponseHeaders().add("Location", "/pagina");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        site.start();
        try {
            String root = "http://127.0.0.1:" + site.getAddress().getPort();
            TestClient a = TestClient.modern("Ana", "[]").ready();
            JsonObject message = send(a, "publico", "olha " + root + "/salto.");
            JsonObject updated = null;
            for (int i = 0; i < 3 && updated == null; i++) {
                JsonObject event = a.next("chat.update").getAsJsonObject("message");
                if (event.get("id").getAsString().equals(message.get("id").getAsString())) {
                    updated = event;
                }
            }
            JsonObject embed = updated.getAsJsonArray("embeds").get(0).getAsJsonObject();
            assertEquals("Título & mais", embed.get("title").getAsString());
            assertEquals("Uma descrição", embed.get("description").getAsString());
            assertEquals("#123abc", embed.get("color").getAsString());
            assertEquals("Site", embed.get("site").getAsString());
            assertTrue(embed.get("image").getAsBoolean());
            assertEquals(10, embed.get("imageWidth").getAsInt());
            assertFalse(embed.has("imageUrl"), "the image address stays on the server");
            HttpResponse<byte[]> image = a.get("/api/embed/" + message.get("id").getAsString() + "/0");
            assertEquals(200, image.statusCode());
            assertArrayEquals(picture, image.body());

            send(a, "publico", "sem previa <" + root + "/pagina>");
            assertFalse(a.received("chat.update", 1500), "a link between angle brackets asks for no preview");
        } finally {
            site.stop(0);
            System.clearProperty("angatu.fetch.allowLoopback");
        }
    }
}
