package br.com.angatusistemas.voicerelay.services;

import br.com.angatusistemas.lib.database.Saveable;
import br.com.angatusistemas.voicerelay.entities.ReadState;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.Collection;
import java.util.List;

/**
 * How far each person has received and read each conversation, the ticks of the chat: one row per person and
 * conversation, moved only forward. Delivered moves when the server hands a message to one of the person's devices, or
 * when the person reconnects and learns what they missed; read moves when the person looks at the conversation. Every
 * change goes to the conversation's sessions, so the author sees one tick, two, then two blue ones.
 *
 * @author Angatu Sistemas
 */
public final class Receipts {

    private static final String TABLE = Saveable.tableName(ReadState.class);
    private static final int MAX_ROWS = 500;

    private Receipts() {
    }

    public static void start() {
        Saveable.createIndex(ReadState.class, "chat");
    }

    static String id(String person, String chat) {
        return person + "|" + chat;
    }

    /* Several people at once, for one new message: one event with everyone whose cursor moved. */
    static void delivered(String chat, long seq, Collection<String> persons) {
        JsonArray changed = new JsonArray();
        for (String person : persons) {
            ReadState moved = advance(person, chat, seq, 0);
            if (moved != null) {
                changed.add(view(moved));
            }
        }
        announce(chat, changed);
    }

    /* Returns the read cursor after the call; reading also means having received. */
    static long read(String person, String chat, long seq) {
        ReadState moved = advance(person, chat, seq, seq);
        if (moved == null) {
            ReadState current = Saveable.findById(ReadState.class, id(person, chat));
            return current == null ? 0 : current.getLastSeq();
        }
        JsonArray changed = new JsonArray();
        changed.add(view(moved));
        announce(chat, changed);
        return moved.getLastSeq();
    }

    static JsonArray of(String chat) {
        JsonArray all = new JsonArray();
        List<ReadState> rows = Saveable.query(ReadState.class, "SELECT data FROM " + TABLE + " WHERE json_extract(data,'$.chat')=? LIMIT ?",
                chat, MAX_ROWS);
        rows.forEach(row -> all.add(view(row)));
        return all;
    }

    /* Inside one transaction, so a read and a delivery arriving together never undo each other. Null when nothing moved. */
    private static ReadState advance(String person, String chat, long delivered, long read) {
        return Saveable.computeInTransaction(() -> {
            ReadState state = Saveable.findById(ReadState.class, id(person, chat));
            if (state == null) {
                state = new ReadState();
                state.setId(id(person, chat));
            }
            long newRead = Math.max(state.getLastSeq(), read);
            long newDelivered = Math.max(Math.max(state.getDeliveredSeq(), delivered), newRead);
            boolean labelled = chat.equals(state.getChat()) && person.equals(state.getPerson());
            if (newRead == state.getLastSeq() && newDelivered == state.getDeliveredSeq() && labelled) {
                return null;
            }
            state.setChat(chat);
            state.setPerson(person);
            state.setLastSeq(newRead);
            state.setDeliveredSeq(newDelivered);
            state.save();
            return state;
        });
    }

    private static JsonObject view(ReadState state) {
        JsonObject out = new JsonObject();
        out.addProperty("person", state.getPerson());
        out.addProperty("d", state.getDeliveredSeq());
        out.addProperty("r", state.getLastSeq());
        return out;
    }

    private static void announce(String chat, JsonArray changed) {
        if (changed.isEmpty()) {
            return;
        }
        JsonObject event = new JsonObject();
        event.addProperty("t", "chat.receipts");
        event.addProperty("chat", chat);
        event.add("receipts", changed);
        NetHub.toChat(chat, event.toString(), null);
    }
}
