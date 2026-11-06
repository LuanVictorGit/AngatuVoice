package br.com.angatusistemas.voicerelay.services;

import br.com.angatusistemas.lib.console.Console;
import br.com.angatusistemas.lib.database.Saveable;
import br.com.angatusistemas.lib.task.Task;
import br.com.angatusistemas.voicerelay.entities.StoredMessage;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Holds messages for people who are offline and removes them before they weigh on the server: anything past the retention
 * period, and in each chat anything past the newest ones. Nothing is kept in memory but the sequence counter.
 *
 * @author Angatu Sistemas
 */
public final class MessageStore {

    private static final String TABLE = Saveable.tableName(StoredMessage.class);
    private static final long PURGE_EVERY_MS = 3_600_000;
    private static final long DAY_MS = 86_400_000;
    private static final int SYNC_LIMIT = 1000;
    private static final int RETENTION_DAYS = env("MESSAGE_RETENTION_DAYS", 30);
    private static final int MAX_PER_CHAT = env("MESSAGE_MAX_PER_CHAT", 1000);
    private static final AtomicLong SEQ = new AtomicLong();
    private static final Set<String> TOUCHED = ConcurrentHashMap.newKeySet();

    private MessageStore() {
    }

    public static void start() {
        Saveable.createIndex(StoredMessage.class, "seq");
        Saveable.createIndex(StoredMessage.class, "chat");
        Saveable.createIndex(StoredMessage.class, "recipient");
        Saveable.createIndex(StoredMessage.class, "ts");
        List<StoredMessage> last = Saveable.query(StoredMessage.class,
                "SELECT data FROM " + TABLE + " ORDER BY json_extract(data,'$.seq') DESC LIMIT 1");
        SEQ.set(last.isEmpty() ? 0 : last.getFirst().getSeq());
    }

    public static void startPurge() {
        Task.runTimerWithFixedDelay(() -> purge(System.currentTimeMillis()), PURGE_EVERY_MS, PURGE_EVERY_MS);
    }

    /* A message sent again after a lost acknowledgement is the same row: it keeps its place and is not delivered twice from here. */
    public static long store(String id, String chat, String recipient, String sender, long ts, String frame) {
        StoredMessage existing = Saveable.findById(StoredMessage.class, id);
        if (existing != null) {
            return existing.getSeq();
        }
        StoredMessage message = new StoredMessage();
        message.setId(id);
        message.setSeq(SEQ.incrementAndGet());
        message.setChat(chat);
        message.setRecipient(recipient);
        message.setSender(sender);
        message.setTs(ts);
        message.setFrame(frame);
        message.save();
        TOUCHED.add(chat);
        return message.getSeq();
    }

    /* Everything a person missed, in order: messages to them, and to any group they are in. */
    public static List<StoredMessage> since(long seq, String person, Set<String> groups) {
        List<Object> args = new ArrayList<>();
        args.add(seq);
        args.add(person);
        args.add(person);
        StringBuilder chats = new StringBuilder();
        for (String group : groups) {
            chats.append(chats.isEmpty() ? "?" : ",?");
            args.add(group);
        }
        args.add(SYNC_LIMIT);
        return Saveable.query(StoredMessage.class, "SELECT data FROM " + TABLE + " WHERE json_extract(data,'$.seq')>? "
                + "AND json_extract(data,'$.sender')<>? AND (json_extract(data,'$.recipient')=? OR json_extract(data,'$.chat') IN ("
                + chats + ")) ORDER BY json_extract(data,'$.seq') ASC LIMIT ?", args.toArray());
    }

    public static void purge(long now) {
        long oldest = now - RETENTION_DAYS * DAY_MS;
        List<String> overflow = new ArrayList<>();
        for (String chat : List.copyOf(TOUCHED)) {
            TOUCHED.remove(chat);
            List<StoredMessage> cut = Saveable.query(StoredMessage.class, "SELECT data FROM " + TABLE
                    + " WHERE json_extract(data,'$.chat')=? ORDER BY json_extract(data,'$.seq') DESC LIMIT 1 OFFSET ?", chat, MAX_PER_CHAT);
            if (!cut.isEmpty()) {
                overflow.add(chat + "|" + cut.getFirst().getSeq());
            }
        }
        long before = Saveable.count(StoredMessage.class);
        Saveable.query(StoredMessage.class, "DELETE FROM " + TABLE + " WHERE json_extract(data,'$.ts')<?", oldest);
        for (String entry : overflow) {
            int bar = entry.lastIndexOf('|');
            Saveable.query(StoredMessage.class, "DELETE FROM " + TABLE + " WHERE json_extract(data,'$.chat')=? AND json_extract(data,'$.seq')<=?",
                    entry.substring(0, bar), Long.parseLong(entry.substring(bar + 1)));
        }
        long removed = before - Saveable.count(StoredMessage.class);
        if (removed > 0) {
            Console.log("Purged %d old messages", removed);
        }
    }

    public static long count() {
        return Saveable.count(StoredMessage.class);
    }

    private static int env(String name, int fallback) {
        String value = System.getenv(name);
        return value == null || !value.matches("\\d{1,6}") ? fallback : Integer.parseInt(value);
    }
}
