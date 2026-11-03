package br.com.angatusistemas.voice.services;

import br.com.angatusistemas.lib.database.Saveable;
import br.com.angatusistemas.voice.entities.Message;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Stores and reads chat history, keeps the unread counters and sanitises text before it is ever persisted or sent.
 *
 * @author Angatu Sistemas
 */
public final class MessageService {

    public static final int MAX_TEXT = 4000;
    public static final int MAX_PAGE = 100;
    private static final int UNREAD_SCAN_LIMIT = 5000;
    private static final Pattern CONTROL = Pattern.compile("[\\p{Cntrl}\\u202A-\\u202E\\u2066-\\u2069&&[^\\n\\t]]");
    private static final String TABLE = Saveable.tableName(Message.class);

    private MessageService() {
    }

    public static void indexes() {
        Saveable.createIndex(Message.class, "chatId");
        Saveable.createIndex(Message.class, "read");
        Saveable.createIndex(Message.class, "pending");
    }

    public static String sanitize(String text) {
        if (text == null) {
            return "";
        }
        String clean = CONTROL.matcher(text.replace("\r\n", "\n")).replaceAll("").strip();
        return clean.length() > MAX_TEXT ? clean.substring(0, MAX_TEXT) : clean;
    }

    public static boolean store(Message message) {
        boolean fresh = !Saveable.exists(Message.class, message.getId());
        if (fresh) {
            message.save();
        }
        return fresh;
    }

    public static List<Message> history(String chat, long before, int limit) {
        List<Message> page = Saveable.query(Message.class,
                "SELECT data FROM " + TABLE + " WHERE json_extract(data,'$.chatId')=? AND json_extract(data,'$.sentAt')<? "
                        + "ORDER BY json_extract(data,'$.sentAt') DESC LIMIT ?",
                chat, before, Math.min(Math.max(limit, 1), MAX_PAGE));
        List<Message> ordered = new ArrayList<>(page);
        Collections.reverse(ordered);
        return ordered;
    }

    public static List<Message> since(String chat, long after, int limit) {
        return Saveable.query(Message.class,
                "SELECT data FROM " + TABLE + " WHERE json_extract(data,'$.chatId')=? AND json_extract(data,'$.sentAt')>? "
                        + "ORDER BY json_extract(data,'$.sentAt') ASC LIMIT ?",
                chat, after, limit);
    }

    public static long newest(String chat) {
        List<Message> latest = history(chat, Long.MAX_VALUE, 1);
        return latest.isEmpty() ? 0 : latest.getFirst().getSentAt();
    }

    public static List<Message> pendingFor(String chat) {
        return Saveable.query(Message.class,
                "SELECT data FROM " + TABLE + " WHERE json_extract(data,'$.chatId')=? AND json_extract(data,'$.pending')=1 "
                        + "ORDER BY json_extract(data,'$.sentAt') ASC LIMIT 200",
                chat);
    }

    public static void delivered(String id) {
        Saveable.mutate(Message.class, id, stored -> stored.setPending(false));
    }

    public static Map<String, Integer> unread() {
        Map<String, Integer> counts = new HashMap<>();
        List<Message> rows = Saveable.query(Message.class,
                "SELECT data FROM " + TABLE + " WHERE json_extract(data,'$.read')=0 LIMIT ?", UNREAD_SCAN_LIMIT);
        rows.forEach(m -> counts.merge(m.getChatId(), 1, Integer::sum));
        return counts;
    }

    public static MessageView view(Message m, String selfId) {
        return new MessageView(m.getId(), m.getChatId(), m.getSenderId(), m.getSenderName(), m.getText(), m.getSentAt(),
                m.isPending(), m.getSenderId().equals(selfId));
    }

    public static void markRead(String chat) {
        List<Message> rows = Saveable.query(Message.class,
                "SELECT data FROM " + TABLE + " WHERE json_extract(data,'$.chatId')=? AND json_extract(data,'$.read')=0 LIMIT ?",
                chat, UNREAD_SCAN_LIMIT);
        rows.forEach(m -> m.setRead(true));
        Saveable.saveAll(rows);
    }

    public static void clear(String chat) {
        List<Message> rows = Saveable.query(Message.class,
                "SELECT data FROM " + TABLE + " WHERE json_extract(data,'$.chatId')=?", chat);
        Saveable.transaction(() -> {
            for (Message m : rows) {
                m.delete();
            }
        });
    }

    public record MessageView(String id, String chat, String from, String name, String text, long ts, boolean pending,
                              boolean mine) {
    }
}
