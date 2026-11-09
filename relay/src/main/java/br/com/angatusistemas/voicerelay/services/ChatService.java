package br.com.angatusistemas.voicerelay.services;

import br.com.angatusistemas.lib.database.Saveable;
import br.com.angatusistemas.lib.gson.GsonAPI;
import br.com.angatusistemas.voicerelay.entities.ReadState;
import br.com.angatusistemas.voicerelay.entities.StoredMessage;
import br.com.angatusistemas.voicerelay.entities.StoredMessage.Embed;
import br.com.angatusistemas.voicerelay.utils.TextRules;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The chat as the server runs it: every message is checked against who may read the chat, stored, and spread to every
 * connection of every member. Apps only show what this confirmed. Legacy apps keep their old frames until they update.
 *
 * @author Angatu Sistemas
 */
public final class ChatService {

    private static final Pattern NONCE = Pattern.compile("[A-Za-z0-9_-]{1,64}");
    private static final Pattern EMOJI = Pattern.compile("[^\\s\\p{Cntrl}<>]{1,32}");
    private static final int MAX_ATTACHMENTS = 10;
    private static final int MAX_REACTIONS = 20;
    private static final int MAX_PINS = 50;
    private static final int DEFAULT_PAGE = 50;
    private static final int MAX_PAGE = 100;
    private static final int UNREAD_CAP = 100;
    private static final int MENTION_WINDOW = 1000;
    private static final int REF_TEXT = 300;
    private static final long TYPING_EVERY_MS = 3_000;
    private static final int MAX_TYPING_CHATS = 64;

    private ChatService() {
    }

    static JsonObject handle(Session session, String type, JsonObject frame) {
        return switch (type) {
            case "chat.send" -> send(session, frame);
            case "chat.edit" -> edit(session, frame);
            case "chat.delete" -> delete(session, frame);
            case "chat.react" -> react(session, frame);
            case "chat.pin" -> pin(session, frame);
            case "chat.pins" -> pins(session, frame);
            case "chat.history" -> history(session, frame);
            case "chat.read" -> read(session, frame);
            case "chat.typing" -> typing(session, frame);
            case "chat.unread" -> unread(session);
            default -> throw new Refusal("Este pedido não existe.");
        };
    }

    /* The app names a private chat by the other person; the server always answers with the canonical pair. */
    static String canonical(Session session, String chat) {
        if (NetHub.PUBLIC_GROUP.equals(chat)) {
            return chat;
        }
        if (NetHub.GROUP_ID.matcher(chat).matches()) {
            if (!session.groups.contains(chat)) {
                throw new Refusal("Você não participa desta conversa.");
            }
            return chat;
        }
        if (chat.startsWith("dm:")) {
            String rest = chat.substring(3);
            if (NetHub.PERSON_ID.matcher(rest).matches()) {
                return direct(session.id, rest);
            }
            int plus = rest.indexOf('+');
            if (plus > 0 && NetHub.PERSON_ID.matcher(rest.substring(0, plus)).matches()
                    && NetHub.PERSON_ID.matcher(rest.substring(plus + 1)).matches()) {
                String pair = direct(rest.substring(0, plus), rest.substring(plus + 1));
                if (!session.canRead(pair)) {
                    throw new Refusal("Você não participa desta conversa.");
                }
                return pair;
            }
        }
        throw new Refusal("Conversa inválida.");
    }

    static String direct(String a, String b) {
        return "dm:" + (a.compareTo(b) <= 0 ? a + "+" + b : b + "+" + a);
    }

    private static String other(String chat, String person) {
        int plus = chat.indexOf('+');
        String a = chat.substring(3, plus);
        return a.equals(person) ? chat.substring(plus + 1) : a;
    }

    private static JsonObject send(Session session, JsonObject frame) {
        if (!session.allowSend()) {
            throw new Refusal("Você está enviando mensagens rápido demais. Espere alguns segundos.");
        }
        AdminService.requireNotMuted(session.id);
        String chat = canonical(session, string(frame, "chat"));
        String nonce = string(frame, "nonce");
        if (!NONCE.matcher(nonce).matches()) {
            throw new Refusal("Pedido inválido: falta o identificador da mensagem.");
        }
        StoredMessage repeated = MessageStore.findByNonce(session.id, nonce);
        if (repeated != null) {
            return messageAnswer(repeated);
        }
        String text = TextRules.clean(string(frame, "text"));
        if (text.length() > TextRules.MAX_TEXT) {
            throw new Refusal("A mensagem passa de 4.000 caracteres.");
        }
        List<String> attachments = ids(frame, "attachments");
        if (attachments.size() > MAX_ATTACHMENTS) {
            throw new Refusal("Envie no máximo 10 anexos por mensagem.");
        }
        if (text.isEmpty() && attachments.isEmpty()) {
            throw new Refusal("Escreva uma mensagem ou anexe um arquivo.");
        }
        String replyTo = string(frame, "replyTo");
        if (!replyTo.isEmpty()) {
            StoredMessage target = MessageStore.find(replyTo);
            if (target == null || !chat.equals(target.getChat())) {
                throw new Refusal("A mensagem que você respondeu não está mais disponível.");
            }
        }
        StoredMessage message = new StoredMessage();
        message.setChat(chat);
        message.setRecipient(chat.startsWith("dm:") ? other(chat, session.id) : null);
        message.setSender(session.id);
        message.setName(session.name);
        message.setTs(System.currentTimeMillis());
        message.setText(text);
        message.setReplyTo(replyTo.isEmpty() ? null : replyTo);
        List<String> mentions = TextRules.mentions(text);
        message.setMentions(mentions.isEmpty() ? null : mentions);
        message.setNonce(nonce);
        boolean[] fresh = new boolean[1];
        StoredMessage stored = Saveable.computeInTransaction(() -> {
            StoredMessage again = MessageStore.findByNonce(session.id, nonce);
            if (again != null) {
                return again;
            }
            if (!attachments.isEmpty()) {
                message.setAttachments(AttachmentStore.claim(attachments, session.id));
            }
            MessageStore.insert(message);
            AttachmentStore.bind(attachments, message.getId(), chat);
            fresh[0] = true;
            return message;
        });
        if (fresh[0]) {
            JsonObject event = new JsonObject();
            event.addProperty("t", "chat.new");
            event.add("message", view(stored));
            NetHub.toChat(chat, event.toString(), null);
            LinkPreview.schedule(stored);
        }
        return messageAnswer(stored);
    }

    private static JsonObject edit(Session session, JsonObject frame) {
        AdminService.requireNotMuted(session.id);
        StoredMessage message = readable(session, string(frame, "id"));
        if (!session.id.equals(message.getSender())) {
            throw new Refusal("Só quem escreveu pode editar a mensagem.");
        }
        String text = TextRules.clean(string(frame, "text"));
        if (text.length() > TextRules.MAX_TEXT) {
            throw new Refusal("A mensagem passa de 4.000 caracteres.");
        }
        if (text.isEmpty() && message.getAttachments().isEmpty()) {
            throw new Refusal("A mensagem não pode ficar vazia.");
        }
        long now = Math.max(System.currentTimeMillis(), message.getEdited() + 1);
        List<String> mentions = TextRules.mentions(text);
        StoredMessage updated = MessageStore.update(message.getId(), stored -> {
            stored.setText(text);
            stored.setEdited(now);
            stored.setMentions(mentions.isEmpty() ? null : mentions);
            stored.setEmbeds(null);
        });
        if (updated == null) {
            throw new Refusal("Mensagem não encontrada.");
        }
        broadcastUpdate(updated);
        LinkPreview.schedule(updated);
        return messageAnswer(updated);
    }

    private static JsonObject delete(Session session, JsonObject frame) {
        StoredMessage message = readable(session, string(frame, "id"));
        if (!session.id.equals(message.getSender()) && !AdminService.powers(session.id)) {
            throw new Refusal("Você só pode apagar as suas mensagens.");
        }
        MessageStore.delete(message);
        JsonObject event = new JsonObject();
        event.addProperty("t", "chat.delete");
        event.addProperty("id", message.getId());
        event.addProperty("chat", message.getChat());
        NetHub.toChat(message.getChat(), event.toString(), null);
        return new JsonObject();
    }

    private static JsonObject react(Session session, JsonObject frame) {
        AdminService.requireNotMuted(session.id);
        String emoji = string(frame, "emoji");
        if (!EMOJI.matcher(emoji).matches() || emoji.chars().allMatch(c -> c < 0x80)) {
            throw new Refusal("Reação inválida.");
        }
        boolean on = frame.has("on") && frame.get("on").isJsonPrimitive() && frame.get("on").getAsBoolean();
        StoredMessage message = readable(session, string(frame, "id"));
        boolean[] full = new boolean[1];
        StoredMessage updated = MessageStore.update(message.getId(), stored -> {
            Map<String, List<String>> reactions = new LinkedHashMap<>(stored.getReactions());
            List<String> users = new ArrayList<>(reactions.getOrDefault(emoji, List.of()));
            if (on && !users.contains(session.id)) {
                if (!reactions.containsKey(emoji) && reactions.size() >= MAX_REACTIONS) {
                    full[0] = true;
                    return;
                }
                users.add(session.id);
            } else if (!on) {
                users.remove(session.id);
            }
            if (users.isEmpty()) {
                reactions.remove(emoji);
            } else {
                reactions.put(emoji, users);
            }
            stored.setReactions(reactions.isEmpty() ? null : reactions);
        });
        if (updated == null) {
            throw new Refusal("Mensagem não encontrada.");
        }
        if (full[0]) {
            throw new Refusal("Esta mensagem já tem 20 reações diferentes.");
        }
        broadcastUpdate(updated);
        return messageAnswer(updated);
    }

    /* Anyone in the conversation pins or unpins, as in group chats people already use; each conversation keeps up to 50. */
    private static JsonObject pin(Session session, JsonObject frame) {
        AdminService.requireNotMuted(session.id);
        boolean on = frame.has("on") && frame.get("on").isJsonPrimitive() && frame.get("on").getAsBoolean();
        StoredMessage message = readable(session, string(frame, "id"));
        if (on && message.getPinned() == 0 && MessageStore.pinned(message.getChat(), MAX_PINS).size() >= MAX_PINS) {
            throw new Refusal("Esta conversa já tem 50 mensagens fixadas. Desafixe alguma antes.");
        }
        StoredMessage updated = MessageStore.update(message.getId(), stored -> {
            if (on && stored.getPinned() == 0) {
                stored.setPinned(System.currentTimeMillis());
                stored.setPinnedBy(session.id);
            } else if (!on) {
                stored.setPinned(0);
                stored.setPinnedBy(null);
            }
        });
        if (updated == null) {
            throw new Refusal("Mensagem não encontrada.");
        }
        broadcastUpdate(updated);
        return messageAnswer(updated);
    }

    private static JsonObject pins(Session session, JsonObject frame) {
        String chat = canonical(session, string(frame, "chat"));
        List<StoredMessage> list = MessageStore.pinned(chat, MAX_PINS);
        Map<String, StoredMessage> refs = new HashMap<>();
        list.forEach(message -> refs.put(message.getId(), message));
        Set<String> missing = new LinkedHashSet<>();
        list.stream().map(StoredMessage::getReplyTo).filter(id -> id != null && !refs.containsKey(id)).forEach(missing::add);
        MessageStore.findAll(missing).forEach(message -> refs.put(message.getId(), message));
        JsonArray messages = new JsonArray();
        list.forEach(message -> messages.add(view(message, refs)));
        JsonObject answer = new JsonObject();
        answer.addProperty("chat", chat);
        answer.add("messages", messages);
        return answer;
    }

    private static JsonObject history(Session session, JsonObject frame) {
        String chat = canonical(session, string(frame, "chat"));
        int limit = (int) Math.clamp(number(frame, "limit", DEFAULT_PAGE), 1, MAX_PAGE);
        List<StoredMessage> page;
        if (frame.has("before")) {
            page = MessageStore.before(chat, number(frame, "before", Long.MAX_VALUE), limit);
        } else if (frame.has("after")) {
            page = MessageStore.after(chat, number(frame, "after", 0), limit);
        } else if (frame.has("around")) {
            page = MessageStore.around(chat, number(frame, "around", 0), limit);
        } else {
            page = MessageStore.latest(chat, limit);
        }
        Map<String, StoredMessage> refs = new HashMap<>();
        page.forEach(message -> refs.put(message.getId(), message));
        Set<String> missing = new LinkedHashSet<>();
        for (StoredMessage message : page) {
            if (message.getReplyTo() != null && !refs.containsKey(message.getReplyTo())) {
                missing.add(message.getReplyTo());
            }
        }
        MessageStore.findAll(missing).forEach(message -> refs.put(message.getId(), message));
        JsonArray messages = new JsonArray();
        page.forEach(message -> messages.add(view(message, refs)));
        JsonObject answer = new JsonObject();
        answer.addProperty("chat", chat);
        answer.add("messages", messages);
        return answer;
    }

    private static JsonObject read(Session session, JsonObject frame) {
        String chat = canonical(session, string(frame, "chat"));
        long seq = Math.clamp(number(frame, "seq", 0), 0, MessageStore.currentSeq());
        String id = session.id + "|" + chat;
        long effective = Saveable.computeInTransaction(() -> {
            ReadState state = Saveable.findById(ReadState.class, id);
            if (state == null) {
                state = new ReadState();
                state.setId(id);
            } else if (state.getLastSeq() >= seq) {
                return state.getLastSeq();
            }
            state.setLastSeq(seq);
            state.save();
            return seq;
        });
        JsonObject event = new JsonObject();
        event.addProperty("t", "chat.read");
        event.addProperty("chat", chat);
        event.addProperty("seq", effective);
        NetHub.toPerson(session.id, event.toString(), session);
        return new JsonObject();
    }

    private static JsonObject typing(Session session, JsonObject frame) {
        String chat = canonical(session, string(frame, "chat"));
        long now = System.currentTimeMillis();
        Long last = session.typing.get(chat);
        if (last != null && now - last < TYPING_EVERY_MS) {
            return null;
        }
        if (session.typing.size() >= MAX_TYPING_CHATS) {
            session.typing.clear();
        }
        session.typing.put(chat, now);
        JsonObject event = new JsonObject();
        event.addProperty("t", "chat.typing");
        event.addProperty("chat", chat);
        event.addProperty("from", session.id);
        event.addProperty("name", session.name);
        NetHub.toChat(chat, event.toString(), session.id);
        return null;
    }

    private static JsonObject unread(Session session) {
        Set<String> chats = new LinkedHashSet<>();
        chats.add(NetHub.PUBLIC_GROUP);
        chats.addAll(session.groups);
        chats.addAll(MessageStore.directChats(session.id));
        List<String> ids = chats.stream().map(chat -> session.id + "|" + chat).toList();
        Map<String, Long> readSeqs = new HashMap<>();
        Saveable.query(ReadState.class, "SELECT data FROM " + Saveable.tableName(ReadState.class) + " WHERE id IN ("
                + MessageStore.placeholders(ids.size()) + ")", ids.toArray()).forEach(state -> readSeqs.put(state.getId(), state.getLastSeq()));
        JsonObject summary = new JsonObject();
        for (String chat : chats) {
            long readSeq = readSeqs.getOrDefault(session.id + "|" + chat, 0L);
            MessageStore.Unread unread = MessageStore.unread(chat, session.id, readSeq, UNREAD_CAP, MENTION_WINDOW);
            JsonObject entry = new JsonObject();
            entry.addProperty("count", unread.count());
            entry.addProperty("mentions", unread.mentions());
            entry.addProperty("lastSeq", unread.lastSeq());
            entry.addProperty("readSeq", readSeq);
            summary.add(chat, entry);
        }
        JsonObject answer = new JsonObject();
        answer.add("chats", summary);
        return answer;
    }

    private static StoredMessage readable(Session session, String id) {
        StoredMessage message = MessageStore.find(id);
        if (message == null || !session.canRead(message.getChat())) {
            throw new Refusal("Mensagem não encontrada.");
        }
        return message;
    }

    static void broadcastUpdate(StoredMessage message) {
        JsonObject event = new JsonObject();
        event.addProperty("t", "chat.update");
        event.add("message", view(message));
        NetHub.toChat(message.getChat(), event.toString(), null);
    }

    private static JsonObject messageAnswer(StoredMessage message) {
        JsonObject answer = new JsonObject();
        answer.add("message", view(message));
        return answer;
    }

    static JsonObject view(StoredMessage message) {
        Map<String, StoredMessage> refs = new HashMap<>();
        if (message.getReplyTo() != null) {
            StoredMessage target = MessageStore.find(message.getReplyTo());
            if (target != null) {
                refs.put(target.getId(), target);
            }
        }
        return view(message, refs);
    }

    /* What a protocol-2 app sees of a message: never the legacy frame, never the address of a preview image. */
    static JsonObject view(StoredMessage message, Map<String, StoredMessage> refs) {
        JsonObject out = new JsonObject();
        out.addProperty("id", message.getId());
        out.addProperty("seq", message.getSeq());
        out.addProperty("chat", message.getChat());
        out.addProperty("from", message.getSender());
        out.addProperty("name", message.getName() == null ? "Sem nome" : message.getName());
        out.addProperty("ts", message.getTs());
        out.addProperty("edited", message.getEdited());
        out.addProperty("text", message.getText() == null ? "" : message.getText());
        out.addProperty("pinned", message.getPinned());
        if (message.getPinnedBy() != null) {
            out.addProperty("pinnedBy", message.getPinnedBy());
        }
        if (message.getReplyTo() != null) {
            out.addProperty("replyTo", message.getReplyTo());
            out.add("ref", ref(message.getReplyTo(), refs.get(message.getReplyTo())));
        }
        JsonArray mentions = new JsonArray();
        message.getMentions().forEach(mentions::add);
        out.add("mentions", mentions);
        JsonArray reactions = new JsonArray();
        message.getReactions().forEach((emoji, users) -> {
            JsonObject reaction = new JsonObject();
            reaction.addProperty("emoji", emoji);
            reaction.addProperty("count", users.size());
            JsonArray who = new JsonArray();
            users.forEach(who::add);
            reaction.add("users", who);
            reactions.add(reaction);
        });
        out.add("reactions", reactions);
        out.add("attachments", GsonAPI.get().toJsonTree(message.getAttachments()));
        JsonArray embeds = new JsonArray();
        for (Embed embed : message.getEmbeds()) {
            JsonObject e = new JsonObject();
            e.addProperty("url", embed.url());
            e.addProperty("title", embed.title());
            e.addProperty("description", embed.description());
            e.addProperty("site", embed.site());
            e.addProperty("color", embed.color());
            e.addProperty("image", embed.imageUrl() != null);
            e.addProperty("imageWidth", embed.imageWidth());
            e.addProperty("imageHeight", embed.imageHeight());
            embeds.add(e);
        }
        out.add("embeds", embeds);
        if (message.getNonce() != null) {
            out.addProperty("nonce", message.getNonce());
        }
        return out;
    }

    private static JsonObject ref(String id, StoredMessage target) {
        JsonObject ref = new JsonObject();
        ref.addProperty("id", id);
        if (target == null) {
            ref.addProperty("missing", true);
            return ref;
        }
        String text = target.getText() == null ? "" : target.getText();
        ref.addProperty("seq", target.getSeq());
        ref.addProperty("from", target.getSender());
        ref.addProperty("name", target.getName() == null ? "Sem nome" : target.getName());
        ref.addProperty("text", text.length() > REF_TEXT ? text.substring(0, REF_TEXT) : text);
        ref.addProperty("attachments", target.getAttachments().size());
        ref.addProperty("missing", false);
        return ref;
    }

    /* A legacy app's message: routed as before, and also stored in the new format so protocol-2 apps see it. */
    static void legacyMessage(Session from, JsonObject frame) {
        JsonElement payload = frame.get("f");
        if (payload == null || !payload.isJsonObject()) {
            return;
        }
        JsonObject f = payload.getAsJsonObject();
        String chat = string(f, "chat");
        String to = string(f, "to");
        String id = string(f, "id");
        boolean directChat = "dm".equals(chat);
        boolean routable = directChat ? NetHub.PERSON_ID.matcher(to).matches() : from.groups.contains(chat);
        if (!routable || !id.startsWith(from.id + ":") || id.length() > 80 || !from.id.equals(string(f, "from")) || !f.has("ts")) {
            return;
        }
        if (AdminService.mutedUntil(from.id) > 0) {
            return;
        }
        String stored = directChat ? direct(from.id, to) : chat;
        StoredMessage message = new StoredMessage();
        message.setId(id);
        message.setChat(stored);
        message.setRecipient(directChat ? to : null);
        message.setSender(from.id);
        String name = string(f, "name");
        message.setName(TextRules.name(name.isBlank() ? from.name : name));
        message.setTs(System.currentTimeMillis());
        message.setText(TextRules.clamp(TextRules.clean(string(f, "text"))));
        List<String> mentions = TextRules.mentions(message.getText());
        message.setMentions(mentions.isEmpty() ? null : mentions);
        message.setFrame(f.toString());
        long before = MessageStore.currentSeq();
        StoredMessage saved = MessageStore.insertLegacy(message);
        JsonObject confirmation = new JsonObject();
        confirmation.addProperty("t", "stored");
        confirmation.addProperty("id", id);
        from.send(confirmation.toString());
        if (saved.getSeq() <= before) {
            return;
        }
        String legacy = NetHub.envelope(from.id, f, saved.getSeq()).toString();
        JsonObject event = new JsonObject();
        event.addProperty("t", "chat.new");
        event.add("message", view(saved, Map.of()));
        String modern = event.toString();
        NetHub.forEachMember(stored, session -> {
            if (session.modern()) {
                session.send(modern);
            } else if (!session.id.equals(from.id)) {
                session.send(legacy);
            }
        });
        LinkPreview.schedule(saved);
    }

    static void legacySync(Session session, JsonObject frame) {
        long since = number(frame, "since", 0);
        for (StoredMessage m : MessageStore.since(since, session.id, Set.copyOf(session.groups))) {
            JsonObject f;
            try {
                f = GsonAPI.get().fromJson(m.getFrame(), JsonObject.class);
            } catch (JsonParseException e) {
                continue;
            }
            session.send(NetHub.envelope(m.getSender(), f, m.getSeq()).toString());
        }
        session.send("{\"t\":\"synced\"}");
    }

    private static List<String> ids(JsonObject frame, String key) {
        JsonElement el = frame.get(key);
        if (el == null || !el.isJsonArray()) {
            return List.of();
        }
        Set<String> ids = new LinkedHashSet<>();
        for (JsonElement item : el.getAsJsonArray()) {
            if (item.isJsonPrimitive()) {
                ids.add(item.getAsString());
            }
            if (ids.size() > MAX_ATTACHMENTS) {
                break;
            }
        }
        return List.copyOf(ids);
    }

    private static long number(JsonObject frame, String key, long fallback) {
        JsonElement el = frame.get(key);
        if (el == null || !el.isJsonPrimitive() || !el.getAsJsonPrimitive().isNumber()) {
            return fallback;
        }
        try {
            return el.getAsLong();
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static String string(JsonObject o, String key) {
        return TextRules.string(o, key);
    }
}
