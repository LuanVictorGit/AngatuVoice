package br.com.angatusistemas.voicerelay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.angatusistemas.lib.database.Saveable;
import br.com.angatusistemas.voicerelay.entities.Attachment;
import br.com.angatusistemas.voicerelay.entities.ReadState;
import br.com.angatusistemas.voicerelay.entities.StoredMessage;
import br.com.angatusistemas.voicerelay.services.AttachmentStore;
import br.com.angatusistemas.voicerelay.services.MessageStore;
import com.google.gson.JsonObject;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * Message storage on a real database: rows old apps wrote are migrated once, nothing is ever deleted on its own however
 * much is stored, the byte count stays exact, and the administrator's wipe removes everything once, files included.
 *
 * @author Angatu Sistemas
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class StorageTest {

    private static final String PASSWORD = "senha-de-teste-456";
    private static String legacyId;

    @BeforeAll
    static void start() throws Exception {
        Path dir = Files.createTempDirectory("relay-storage");
        System.setProperty("angatu.db", dir.resolve("database.db").toString());
        System.setProperty("angatu.admin.password", PASSWORD);
        StoredMessage legacy = new StoredMessage();
        legacyId = "0123456789abcdef0123:legacy";
        legacy.setId(legacyId);
        legacy.setSeq(1);
        legacy.setChat("publico");
        legacy.setSender("0123456789abcdef0123");
        legacy.setTs(1);
        legacy.setFrame("{\"t\":\"msg\",\"text\":\"mensagem antiga\",\"name\":\"Ana\"}");
        legacy.save();
        TestClient.startServer();
    }

    private static StoredMessage message(String text) {
        StoredMessage message = new StoredMessage();
        message.setChat("publico");
        message.setSender("aaaaaaaaaaaaaaaaaaaa");
        message.setName("Teste");
        message.setTs(System.currentTimeMillis());
        message.setText(text);
        return Saveable.computeInTransaction(() -> MessageStore.insert(message));
    }

    private static String nonce() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 20);
    }

    @Test
    @Order(1)
    void legacyRowsAreMigratedOnceAtStartup() {
        StoredMessage migrated = Saveable.findById(StoredMessage.class, legacyId);
        assertEquals("mensagem antiga", migrated.getText());
        assertEquals("Ana", migrated.getName());
        assertTrue(migrated.getFrame() != null, "the frame stays for legacy sync");
    }

    @Test
    @Order(2)
    void nothingIsEverDeletedOnItsOwnAndTheCountStaysExact() throws Exception {
        long before = MessageStore.count();
        String filler = "x".repeat(3000);
        for (int i = 0; i < 400; i++) {
            message(filler);
        }
        Thread.sleep(500);
        assertEquals(before + 400, MessageStore.count(), "every message is still there");
        assertTrue(Saveable.exists(StoredMessage.class, legacyId), "the oldest too");
        MessageStore.Totals totals = MessageStore.measure();
        assertTrue(totals.bytes() > 1_200_000);
        assertEquals(totals.bytes(), MessageStore.bytes(), "the running total matches the exact sum");
    }

    @Test
    @Order(3)
    void theWipeNeedsAnActiveAdministratorAndTheExactConfirmation() throws Exception {
        TestClient person = TestClient.modern("Pessoa", "[]").ready();
        assertEquals("Ative o modo administrador para fazer isso.",
                person.ask("admin.wipe", "\"confirm\":\"APAGAR TUDO\"").get("error").getAsString());
        TestClient admin = TestClient.modern("Chefe", "[]").ready();
        admin.ask("admin.login", "\"password\":\"" + PASSWORD + "\"");
        JsonObject unconfirmed = admin.ask("admin.wipe", "\"confirm\":\"apagar tudo\"");
        assertFalse(unconfirmed.get("ok").getAsBoolean());
        assertEquals("Para apagar todas as mensagens, confirme digitando APAGAR TUDO.", unconfirmed.get("error").getAsString());
        admin.ask("admin.mode", "\"active\":false");
        assertFalse(admin.ask("admin.wipe", "\"confirm\":\"APAGAR TUDO\"").get("ok").getAsBoolean());
        assertTrue(MessageStore.count() > 400, "nothing was removed");
    }

    @Test
    @Order(4)
    void theWipeRemovesEveryMessageItsFilesAndReadStateAndTellsEveryone() throws Exception {
        TestClient author = TestClient.modern("Autora", "[]").ready();
        HttpResponse<byte[]> uploaded = author.upload("foto.webp", "image/webp", ChatTest.webp(20, 20, 50));
        String file = TestClient.json(uploaded.body()).get("id").getAsString();
        JsonObject sent = author.ask("chat.send", "\"chat\":\"publico\",\"nonce\":\"" + nonce() + "\",\"text\":\"com foto\",\"attachments\":[\""
                + file + "\"]");
        assertTrue(sent.get("ok").getAsBoolean(), sent.toString());
        author.ask("chat.read", "\"chat\":\"publico\",\"seq\":" + sent.getAsJsonObject("message").get("seq").getAsLong());
        assertTrue(Saveable.count(ReadState.class) > 0);
        assertTrue(Files.exists(AttachmentStore.path(file)));
        TestClient bystander = TestClient.modern("Testemunha", "[]").ready();

        TestClient admin = TestClient.modern("Chefe", "[]").ready();
        admin.ask("admin.login", "\"password\":\"" + PASSWORD + "\"");
        long expected = MessageStore.count();
        JsonObject wiped = admin.ask("admin.wipe", "\"confirm\":\"APAGAR TUDO\"");
        assertTrue(wiped.get("ok").getAsBoolean(), wiped.toString());
        assertEquals(expected, wiped.get("removed").getAsLong());
        assertEquals(1, wiped.get("attachments").getAsInt());
        bystander.next("chat.wiped");
        author.next("chat.wiped");
        assertEquals(0, MessageStore.count());
        assertEquals(0, Saveable.count(ReadState.class));
        assertEquals(0, MessageStore.bytes());
        assertFalse(Files.exists(AttachmentStore.path(file)));
        assertFalse(Saveable.exists(Attachment.class, file));
        assertEquals(0, author.ask("chat.history", "\"chat\":\"publico\"").getAsJsonArray("messages").size());
        JsonObject stats = admin.ask("admin.stats", "");
        assertEquals(0, stats.get("messageBytes").getAsLong());
        assertEquals(0, stats.get("messageCount").getAsLong());
    }

    @Test
    @Order(5)
    void concurrentWipesRunOnce() throws Exception {
        for (int i = 0; i < 1500; i++) {
            message("z".repeat(200));
        }
        CountDownLatch go = new CountDownLatch(1);
        List<MessageStore.Wipe> results = new CopyOnWriteArrayList<>();
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            threads.add(Thread.startVirtualThread(() -> {
                try {
                    go.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                MessageStore.Wipe wipe = MessageStore.wipe();
                if (wipe != null && wipe.messages() > 0) {
                    results.add(wipe);
                }
            }));
        }
        go.countDown();
        for (Thread thread : threads) {
            thread.join();
        }
        assertEquals(1, results.size(), "one wipe did the work");
        assertEquals(1500, results.getFirst().messages());
        assertEquals(0, MessageStore.count());
    }
}
