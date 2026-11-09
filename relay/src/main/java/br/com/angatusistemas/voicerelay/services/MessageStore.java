package br.com.angatusistemas.voicerelay.services;

import br.com.angatusistemas.lib.console.Console;
import br.com.angatusistemas.lib.database.Saveable;
import br.com.angatusistemas.lib.gson.GsonAPI;
import br.com.angatusistemas.voicerelay.entities.ReadState;
import br.com.angatusistemas.voicerelay.entities.StoredMessage;
import br.com.angatusistemas.voicerelay.entities.StoredMessage.AttachmentRef;
import br.com.angatusistemas.voicerelay.utils.TextRules;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Every chat message on the server. Nothing is ever removed on its own: messages go only when their author or an
 * administrator deletes them, or when an administrator clears a chat or wipes everything. Besides the sequence counter,
 * only the running byte total lives in memory, measured exactly at startup and after every bulk removal.
 *
 * @author Angatu Sistemas
 */
public final class MessageStore {

    private static final String TABLE = Saveable.tableName(StoredMessage.class);
    private static final String SEQ_COLUMN = "json_extract(data,'$.seq')";
    private static final String CHAT_COLUMN = "json_extract(data,'$.chat')";
    private static final String SENDER_COLUMN = "json_extract(data,'$.sender')";
    private static final int SYNC_LIMIT = 1000;
    private static final int BATCH = 500;
    private static final AtomicLong SEQ = new AtomicLong();
    private static final AtomicLong BYTES = new AtomicLong();
    private static final AtomicBoolean WIPING = new AtomicBoolean();

    private MessageStore() {
    }

    public static void start() {
        Saveable.createIndex(StoredMessage.class, "seq");
        Saveable.createIndex(StoredMessage.class, "chat");
        Saveable.createIndex(StoredMessage.class, "recipient");
        Saveable.createIndex(StoredMessage.class, "sender");
        Saveable.createIndex(StoredMessage.class, "nonce");
        Saveable.query(StoredMessage.class, "CREATE INDEX IF NOT EXISTS " + TABLE + "_chat_seq ON " + TABLE + "(" + CHAT_COLUMN + ","
                + SEQ_COLUMN + ")");
        List<StoredMessage> last = Saveable.query(StoredMessage.class, "SELECT data FROM " + TABLE + " ORDER BY " + SEQ_COLUMN + " DESC LIMIT 1");
        SEQ.set(last.isEmpty() ? 0 : last.getFirst().getSeq());
        migrateLegacy();
        BYTES.set(measure().bytes());
        Console.log("Message storage: %d bytes in %d messages", BYTES.get(), count());
    }

    /* Rows written by apps before protocol 2 hold only the signed frame: the text and name are copied out of it once. */
    private static void migrateLegacy() {
        int migrated = 0;
        while (true) {
            List<StoredMessage> batch = Saveable.query(StoredMessage.class, "SELECT data FROM " + TABLE
                    + " WHERE json_extract(data,'$.text') IS NULL AND json_extract(data,'$.frame') IS NOT NULL LIMIT ?", BATCH);
            if (batch.isEmpty()) {
                break;
            }
            for (StoredMessage message : batch) {
                JsonObject frame = parse(message.getFrame());
                message.setText(TextRules.clamp(TextRules.clean(TextRules.string(frame, "text"))));
                String name = TextRules.string(frame, "name");
                message.setName(name.isBlank() ? null : TextRules.name(name));
            }
            Saveable.saveAll(batch);
            migrated += batch.size();
        }
        if (migrated > 0) {
            Console.log("Migrated %d legacy messages to the new format", migrated);
        }
    }

    private static JsonObject parse(String frame) {
        try {
            JsonObject parsed = GsonAPI.get().fromJson(frame, JsonObject.class);
            return parsed == null ? new JsonObject() : parsed;
        } catch (JsonParseException e) {
            return new JsonObject();
        }
    }

    /* Called inside a transaction: the single writer orders commits, so a seq is never visible before a smaller one. */
    public static StoredMessage insert(StoredMessage message) {
        long seq = SEQ.incrementAndGet();
        message.setSeq(seq);
        if (message.getId() == null) {
            message.setId(Long.toString(seq));
        }
        message.save();
        added(sizeOf(message));
        return message;
    }

    /* A legacy message sent again after a lost acknowledgement is the same row: it keeps its place and is stored once. */
    public static StoredMessage insertLegacy(StoredMessage message) {
        return Saveable.computeInTransaction(() -> {
            StoredMessage existing = Saveable.findById(StoredMessage.class, message.getId());
            return existing != null ? existing : insert(message);
        });
    }

    public static StoredMessage find(String id) {
        return id == null || id.isEmpty() || id.length() > 120 ? null : Saveable.findById(StoredMessage.class, id);
    }

    public static StoredMessage findByNonce(String sender, String nonce) {
        List<StoredMessage> found = Saveable.query(StoredMessage.class, "SELECT data FROM " + TABLE
                + " WHERE json_extract(data,'$.nonce')=? AND " + SENDER_COLUMN + "=? LIMIT 1", nonce, sender);
        return found.isEmpty() ? null : found.getFirst();
    }

    public static List<StoredMessage> findAll(Collection<String> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        return Saveable.query(StoredMessage.class, "SELECT data FROM " + TABLE + " WHERE id IN (" + placeholders(ids.size()) + ")",
                ids.toArray());
    }

    public static List<StoredMessage> latest(String chat, int limit) {
        return reversed(Saveable.query(StoredMessage.class, "SELECT data FROM " + TABLE + " WHERE " + CHAT_COLUMN + "=? ORDER BY "
                + SEQ_COLUMN + " DESC LIMIT ?", chat, limit));
    }

    public static List<StoredMessage> before(String chat, long seq, int limit) {
        return reversed(Saveable.query(StoredMessage.class, "SELECT data FROM " + TABLE + " WHERE " + CHAT_COLUMN + "=? AND " + SEQ_COLUMN
                + "<? ORDER BY " + SEQ_COLUMN + " DESC LIMIT ?", chat, seq, limit));
    }

    public static List<StoredMessage> after(String chat, long seq, int limit) {
        return Saveable.query(StoredMessage.class, "SELECT data FROM " + TABLE + " WHERE " + CHAT_COLUMN + "=? AND " + SEQ_COLUMN
                + ">? ORDER BY " + SEQ_COLUMN + " ASC LIMIT ?", chat, seq, limit);
    }

    /* The chat index narrows the scan to one conversation; pins are few, so the filter on top costs nothing. */
    public static List<StoredMessage> pinned(String chat, int limit) {
        return Saveable.query(StoredMessage.class, "SELECT data FROM " + TABLE + " WHERE " + CHAT_COLUMN + "=? AND json_extract(data,'$.pinned')>0 "
                + "ORDER BY json_extract(data,'$.pinned') DESC LIMIT ?", chat, limit);
    }

    public static List<StoredMessage> around(String chat, long seq, int limit) {
        List<StoredMessage> page = new ArrayList<>(before(chat, seq, limit / 2));
        page.addAll(Saveable.query(StoredMessage.class, "SELECT data FROM " + TABLE + " WHERE " + CHAT_COLUMN + "=? AND " + SEQ_COLUMN
                + ">=? ORDER BY " + SEQ_COLUMN + " ASC LIMIT ?", chat, seq, limit - page.size()));
        return page;
    }

    private static List<StoredMessage> reversed(List<StoredMessage> rows) {
        List<StoredMessage> copy = new ArrayList<>(rows);
        Collections.reverse(copy);
        return copy;
    }

    /* Private chats a person took part in, bounded: the unread summary reads one row per chat from this. */
    public static List<String> directChats(String person) {
        List<ChatRow> rows = Saveable.query(ChatRow.class, "SELECT json_object('chat', c) AS data FROM (SELECT DISTINCT " + CHAT_COLUMN
                + " AS c FROM " + TABLE + " WHERE (" + SENDER_COLUMN + "=? OR json_extract(data,'$.recipient')=?) AND "
                + CHAT_COLUMN + " LIKE 'dm:%' LIMIT 500)", person, person);
        return rows.stream().map(ChatRow::chat).filter(chat -> chat != null && chat.contains(person)).toList();
    }

    public static Unread unread(String chat, String person, long readSeq, int cap, int mentionWindow) {
        List<Unread> rows = Saveable.query(Unread.class, "SELECT json_object("
                + "'count',(SELECT COUNT(*) FROM (SELECT 1 FROM " + TABLE + " WHERE " + CHAT_COLUMN + "=? AND " + SEQ_COLUMN
                + ">? AND " + SENDER_COLUMN + "<>? LIMIT ?)),"
                + "'mentions',(SELECT COUNT(*) FROM (SELECT data AS d FROM " + TABLE + " WHERE " + CHAT_COLUMN + "=? AND " + SEQ_COLUMN
                + ">? AND " + SENDER_COLUMN + "<>? ORDER BY " + SEQ_COLUMN + " DESC LIMIT ?) m"
                + " WHERE EXISTS (SELECT 1 FROM json_each(m.d,'$.mentions') WHERE value=?)),"
                + "'lastSeq',COALESCE((SELECT MAX(" + SEQ_COLUMN + ") FROM " + TABLE + " WHERE " + CHAT_COLUMN + "=?),0)) AS data",
                chat, readSeq, person, cap, chat, readSeq, person, mentionWindow, person, chat);
        return rows.getFirst();
    }

    /* Changes a stored message in one transaction and keeps the byte total in step with what was written. */
    public static StoredMessage update(String id, Consumer<StoredMessage> change) {
        long[] sizes = new long[2];
        StoredMessage updated = Saveable.mutate(StoredMessage.class, id, stored -> {
            sizes[0] = sizeOf(stored);
            change.accept(stored);
            sizes[1] = sizeOf(stored);
        });
        if (updated != null) {
            added(sizes[1] - sizes[0]);
        }
        return updated;
    }

    public static void delete(StoredMessage message) {
        removeRows(List.of(message));
    }

    /* Removes the rows and the attachments they carry; the files go only after the rows are gone for good. */
    private static int removeRows(List<StoredMessage> rows) {
        if (rows.isEmpty()) {
            return 0;
        }
        List<String> ids = rows.stream().map(StoredMessage::getId).toList();
        List<String> files = rows.stream().flatMap(m -> m.getAttachments().stream()).map(AttachmentRef::id).toList();
        long bytes = rows.stream().mapToLong(MessageStore::sizeOf).sum();
        Saveable.transaction(() -> {
            Saveable.query(StoredMessage.class, "DELETE FROM " + TABLE + " WHERE id IN (" + placeholders(ids.size()) + ")", ids.toArray());
            AttachmentStore.deleteRows(files);
        });
        BYTES.addAndGet(-bytes);
        AttachmentStore.deleteFiles(files);
        return files.size();
    }

    public static int clear(String chat) {
        int removed = 0;
        while (true) {
            List<StoredMessage> batch = Saveable.query(StoredMessage.class, "SELECT data FROM " + TABLE + " WHERE " + CHAT_COLUMN + "=? LIMIT ?",
                    chat, BATCH);
            if (batch.isEmpty()) {
                break;
            }
            removeRows(batch);
            removed += batch.size();
        }
        BYTES.set(measure().bytes());
        return removed;
    }

    /* Everything a legacy app missed, in order: legacy rows to them, and to any group they are in. */
    public static List<StoredMessage> since(long seq, String person, Set<String> groups) {
        List<Object> args = new ArrayList<>();
        args.add(seq);
        args.add(person);
        args.add(person);
        args.addAll(groups);
        args.add(SYNC_LIMIT);
        return Saveable.query(StoredMessage.class, "SELECT data FROM " + TABLE + " WHERE " + SEQ_COLUMN + ">? "
                + "AND json_extract(data,'$.frame') IS NOT NULL AND " + SENDER_COLUMN + "<>? AND (json_extract(data,'$.recipient')=? OR "
                + CHAT_COLUMN + " IN (" + placeholders(groups.size()) + ")) ORDER BY " + SEQ_COLUMN + " ASC LIMIT ?", args.toArray());
    }

    private static void added(long delta) {
        BYTES.addAndGet(delta);
    }

    /* Everything at once, by an administrator's explicit order; a second order while one runs is turned down, not queued. */
    public static Wipe wipe() {
        if (!WIPING.compareAndSet(false, true)) {
            return null;
        }
        try {
            long before = measure().bytes();
            int messages = 0;
            int files = 0;
            while (true) {
                List<StoredMessage> batch = Saveable.query(StoredMessage.class, "SELECT data FROM " + TABLE + " LIMIT ?", BATCH);
                if (batch.isEmpty()) {
                    break;
                }
                files += removeRows(batch);
                messages += batch.size();
            }
            Saveable.deleteAll(ReadState.class);
            BYTES.set(measure().bytes());
            Console.log("Wiped all messages: %d -> %d bytes, removed %d messages and %d attachments", before, BYTES.get(), messages, files);
            return new Wipe(messages, files);
        } finally {
            WIPING.set(false);
        }
    }

    public static Totals measure() {
        return Saveable.query(Totals.class, "SELECT json_object('bytes',COALESCE(SUM(length(CAST(data AS BLOB))),0),'count',COUNT(*)) AS data FROM "
                + TABLE).getFirst();
    }

    public static long sizeOf(StoredMessage message) {
        return GsonAPI.get().toJson(message).getBytes(StandardCharsets.UTF_8).length;
    }

    public static long currentSeq() {
        return SEQ.get();
    }

    public static long bytes() {
        return BYTES.get();
    }

    public static long count() {
        return Saveable.count(StoredMessage.class);
    }

    /* A system property wins so tests can shrink limits; then the environment; anything unreadable keeps the default. */
    static long setting(String property, String env, long fallback, long minimum) {
        String value = System.getProperty(property);
        if (value == null) {
            value = System.getenv(env);
        }
        if (value == null || !value.strip().matches("\\d{1,18}")) {
            return fallback;
        }
        return Math.max(minimum, Long.parseLong(value.strip()));
    }

    static String placeholders(int count) {
        return count == 0 ? "NULL" : "?" + ",?".repeat(count - 1);
    }

    public record Totals(long bytes, long count) {
    }

    public record Wipe(int messages, int attachments) {
    }

    public record Unread(long count, long mentions, long lastSeq) {
    }

    private record ChatRow(String chat) {
    }
}
