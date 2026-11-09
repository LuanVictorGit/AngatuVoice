package br.com.angatusistemas.voice.services;

import br.com.angatusistemas.lib.console.Console;
import br.com.angatusistemas.lib.database.Saveable;
import br.com.angatusistemas.voice.entities.Peer;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The chat as the server keeps it. Nothing is stored here: every page, send, edit, deletion and reaction is a question to the
 * server, and every change the server spreads is handed to the interface. Only what the interface needs between two
 * answers lives in memory: the unread counters the server last reported, the HTTP token and the administrator state.
 *
 * @author Angatu Sistemas
 */
public final class ChatService {

    public static final int MAX_TEXT = 4000;
    public static final int MAX_ATTACHMENTS = 10;
    private static final int MAX_PAGE = 100;
    private static final Pattern CHAT = Pattern.compile("publico|g_[0-9a-f]{16}|dm:[0-9a-f]{20}");
    private static final Pattern MESSAGE_ID = Pattern.compile("[0-9A-Za-z:_-]{1,80}");
    private static final Pattern MENTION = Pattern.compile("<@([0-9a-f]{20})>");
    private static final Set<String> ADMIN_ACTIONS = Set.of("login", "mode", "kick", "ban", "unban", "mute", "clear", "wipe", "bans", "stats");
    private static final Map<String, Unread> UNREAD = new ConcurrentHashMap<>();

    private static volatile String httpToken = "";
    private static volatile Admin admin = new Admin(false, false);
    private static volatile long mutedUntil;

    private ChatService() {
    }

    public static boolean validChat(String chat) {
        return chat != null && CHAT.matcher(chat).matches();
    }

    public static boolean validMessageId(String id) {
        return id != null && MESSAGE_ID.matcher(id).matches();
    }

    public static String httpToken() {
        return httpToken;
    }

    public static Admin admin() {
        return admin;
    }

    public static long mutedUntil() {
        return mutedUntil;
    }

    public static Map<String, Unread> unread() {
        return Map.copyOf(UNREAD);
    }

    /* ---- questions to the server ---- */

    /* One page and, beside it, how far each person received and read this conversation: the ticks are drawn from that. */
    public static JsonObject history(String chat, long before, long after, long around, int limit) {
        JsonObject ask = frame("chat.history");
        ask.addProperty("chat", chat);
        if (before > 0) {
            ask.addProperty("before", before);
        }
        if (after > 0) {
            ask.addProperty("after", after);
        }
        if (around > 0) {
            ask.addProperty("around", around);
        }
        ask.addProperty("limit", Math.clamp(limit, 1, MAX_PAGE));
        JsonObject res = PeerNetwork.request(ask);
        JsonArray messages = new JsonArray();
        for (JsonElement el : res.getAsJsonArray("messages")) {
            messages.add(forInterface(el.getAsJsonObject()));
        }
        JsonObject out = new JsonObject();
        out.add("messages", messages);
        out.add("receipts", res.has("receipts") && res.get("receipts").isJsonArray() ? res.getAsJsonArray("receipts") : new JsonArray());
        return out;
    }

    public static JsonObject send(String chat, String text, String nonce, String replyTo, List<String> attachments) {
        JsonObject ask = frame("chat.send");
        ask.addProperty("chat", chat);
        ask.addProperty("text", text);
        ask.addProperty("nonce", nonce);
        if (replyTo != null && !replyTo.isBlank()) {
            ask.addProperty("replyTo", replyTo);
        }
        JsonArray files = new JsonArray();
        attachments.forEach(files::add);
        ask.add("attachments", files);
        return forInterface(PeerNetwork.request(ask).getAsJsonObject("message"));
    }

    public static JsonObject edit(String id, String text) {
        JsonObject ask = frame("chat.edit");
        ask.addProperty("id", id);
        ask.addProperty("text", text);
        return forInterface(PeerNetwork.request(ask).getAsJsonObject("message"));
    }

    public static void delete(String id) {
        JsonObject ask = frame("chat.delete");
        ask.addProperty("id", id);
        PeerNetwork.request(ask);
    }

    public static JsonObject react(String id, String emoji, boolean on) {
        JsonObject ask = frame("chat.react");
        ask.addProperty("id", id);
        ask.addProperty("emoji", emoji);
        ask.addProperty("on", on);
        return forInterface(PeerNetwork.request(ask).getAsJsonObject("message"));
    }

    public static JsonObject pin(String id, boolean on) {
        JsonObject ask = frame("chat.pin");
        ask.addProperty("id", id);
        ask.addProperty("on", on);
        return forInterface(PeerNetwork.request(ask).getAsJsonObject("message"));
    }

    public static JsonObject replaceFile(String id, String file, String with) {
        JsonObject ask = frame("chat.replaceFile");
        ask.addProperty("id", id);
        ask.addProperty("file", file);
        ask.addProperty("with", with);
        return forInterface(PeerNetwork.request(ask).getAsJsonObject("message"));
    }

    public static JsonArray pins(String chat) {
        JsonObject ask = frame("chat.pins");
        ask.addProperty("chat", chat);
        JsonArray out = new JsonArray();
        for (JsonElement el : PeerNetwork.request(ask).getAsJsonArray("messages")) {
            out.add(forInterface(el.getAsJsonObject()));
        }
        return out;
    }

    public static void read(String chat, long seq) {
        UNREAD.remove(chat);
        JsonObject tell = frame("chat.read");
        tell.addProperty("chat", chat);
        tell.addProperty("seq", seq);
        PeerNetwork.tell(tell);
    }

    public static void typing(String chat) {
        JsonObject tell = frame("chat.typing");
        tell.addProperty("chat", chat);
        PeerNetwork.tell(tell);
    }

    /* Only the listed actions travel, with only their own fields: the server decides whether this person may use them. */
    public static JsonObject admin(String action, JsonObject body) {
        if (!ADMIN_ACTIONS.contains(action)) {
            throw new IllegalArgumentException("unknown admin action");
        }
        JsonObject ask = frame("admin." + action);
        for (String field : List.of("password", "active", "id", "reason", "minutes", "chat", "confirm")) {
            if (body.has(field) && body.get(field).isJsonPrimitive()) {
                ask.add(field, body.get(field));
            }
        }
        JsonObject res = PeerNetwork.request(ask);
        if (res.has("admin") && res.get("admin").isJsonObject()) {
            applyAdmin(res.getAsJsonObject("admin"));
        }
        res.remove("t");
        res.remove("r");
        res.remove("ok");
        return res;
    }

    /* ---- what the server spreads ---- */

    static void onWelcome(JsonObject welcome) {
        httpToken = string(welcome, "http");
        mutedUntil = welcome.has("mutedUntil") ? welcome.get("mutedUntil").getAsLong() : 0;
        if (welcome.has("admin") && welcome.get("admin").isJsonObject()) {
            applyAdmin(welcome.getAsJsonObject("admin"));
        }
        Thread.startVirtualThread(ChatService::refreshUnread);
    }

    private static void refreshUnread() {
        try {
            JsonObject chats = PeerNetwork.request(frame("chat.unread")).getAsJsonObject("chats");
            Map<String, Unread> fresh = new HashMap<>();
            for (Map.Entry<String, JsonElement> entry : chats.entrySet()) {
                JsonObject u = entry.getValue().getAsJsonObject();
                int count = u.get("count").getAsInt();
                int mentions = u.has("mentions") ? u.get("mentions").getAsInt() : 0;
                if (count > 0 || mentions > 0) {
                    fresh.put(forInterface(entry.getKey()), new Unread(count, mentions));
                }
            }
            UNREAD.clear();
            UNREAD.putAll(fresh);
            LiveHub.emit("chat.unread", Map.of("chats", unread()));
        } catch (PeerNetwork.Offline | PeerNetwork.Refused e) {
            Console.debug("unread not refreshed: " + e.getMessage());
        }
    }

    static void onServerFrame(JsonObject frame) {
        switch (string(frame, "t")) {
            case "chat.new" -> onNew(frame.getAsJsonObject("message"));
            case "chat.update" -> LiveHub.emit("chat.update", forInterface(frame.getAsJsonObject("message")));
            case "chat.delete" -> LiveHub.emit("chat.delete", Map.of("id", string(frame, "id"), "chat", forInterface(string(frame, "chat"))));
            case "chat.cleared" -> {
                String chat = forInterface(string(frame, "chat"));
                UNREAD.remove(chat);
                LiveHub.emit("chat.cleared", Map.of("chat", chat));
            }
            case "chat.read" -> {
                String chat = forInterface(string(frame, "chat"));
                UNREAD.remove(chat);
                LiveHub.emit("chat.read", Map.of("chat", chat, "seq", frame.get("seq").getAsLong()));
            }
            case "chat.receipts" -> LiveHub.emit("chat.receipts", Map.of("chat", forInterface(string(frame, "chat")),
                    "receipts", frame.has("receipts") && frame.get("receipts").isJsonArray() ? frame.getAsJsonArray("receipts") : new JsonArray()));
            case "chat.wiped" -> {
                UNREAD.clear();
                LiveHub.emit("chat.wiped", Map.of());
            }
            case "chat.typing" -> LiveHub.emit("chat.typing", Map.of("chat", forInterface(string(frame, "chat")),
                    "from", string(frame, "from"), "name", IdentityService.clean(string(frame, "name"))));
            case "admin" -> applyAdmin(frame);
            case "muted" -> {
                mutedUntil = frame.has("until") ? frame.get("until").getAsLong() : 0;
                LiveHub.emit("chat.muted", Map.of("until", mutedUntil));
            }
            default -> { }
        }
    }

    private static void onNew(JsonObject message) {
        JsonObject view = forInterface(message);
        String chat = string(view, "chat");
        String from = string(view, "from");
        boolean mine = view.get("mine").getAsBoolean();
        if (!mine) {
            boolean mentioned = mentionsMe(view);
            UNREAD.merge(chat, new Unread(1, mentioned ? 1 : 0), (a, b) -> new Unread(a.count() + 1, a.mentions() + b.mentions()));
            if (chat.startsWith("dm:")) {
                PeerNetwork.rememberSender(from, string(view, "name"));
            }
            String body = readable(string(view, "text"));
            if (body.isEmpty() && view.has("attachments") && !view.getAsJsonArray("attachments").isEmpty()) {
                body = "Enviou um anexo.";
            }
            String preview = !SettingsService.flag("privacy.preview") ? "Nova mensagem" : body.length() > 120 ? body.substring(0, 120) + "…" : body;
            Notifications.notify(IdentityService.clean(string(view, "name")), preview);
        }
        LiveHub.emit("chat.new", view);
    }

    private static boolean mentionsMe(JsonObject view) {
        String me = PeerNetwork.me().id();
        if (!view.has("mentions") || !view.get("mentions").isJsonArray()) {
            return false;
        }
        for (JsonElement el : view.getAsJsonArray("mentions")) {
            if (me.equals(el.getAsString())) {
                return true;
            }
        }
        return false;
    }

    /* Mentions travel as ids; a notification shows names. */
    private static String readable(String text) {
        Matcher m = MENTION.matcher(text);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            Peer peer = Saveable.findById(Peer.class, m.group(1));
            String name = m.group(1).equals(PeerNetwork.me().id()) ? PeerNetwork.me().name() : peer == null ? "alguém" : peer.getName();
            m.appendReplacement(out, Matcher.quoteReplacement("@" + name));
        }
        m.appendTail(out);
        return out.toString().strip();
    }

    private static void applyAdmin(JsonObject state) {
        admin = new Admin(state.has("admin") && state.get("admin").getAsBoolean(), state.has("active") && state.get("active").getAsBoolean());
        LiveHub.emit("admin", admin);
    }

    /* The server names a direct conversation by both people; the interface names it by the other one. */
    private static JsonObject forInterface(JsonObject message) {
        JsonObject view = message.deepCopy();
        view.addProperty("chat", forInterface(string(message, "chat")));
        view.addProperty("mine", PeerNetwork.me().id().equals(string(message, "from")));
        return view;
    }

    static String forInterface(String chat) {
        if (!chat.startsWith("dm:") || !chat.contains("+")) {
            return chat;
        }
        String me = PeerNetwork.me().id();
        String[] pair = chat.substring(3).split("\\+", 2);
        return "dm:" + (pair[0].equals(me) ? pair[1] : pair[0]);
    }

    private static JsonObject frame(String type) {
        JsonObject frame = new JsonObject();
        frame.addProperty("t", type);
        return frame;
    }

    private static String string(JsonObject o, String key) {
        JsonElement el = o.get(key);
        return el != null && el.isJsonPrimitive() ? el.getAsString() : "";
    }

    public record Unread(int count, int mentions) {
    }

    public record Admin(boolean admin, boolean active) {
    }
}
