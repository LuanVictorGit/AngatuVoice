package br.com.angatusistemas.voicerelay.services;

import br.com.angatusistemas.lib.database.Saveable;
import br.com.angatusistemas.voicerelay.entities.Admin;
import br.com.angatusistemas.voicerelay.entities.Ban;
import br.com.angatusistemas.voicerelay.entities.Mute;
import br.com.angatusistemas.voicerelay.utils.TextRules;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Administration: who proved the server's password, whether their administrator mode is on, and the powers that only
 * work while it is: kick, ban, mute, clear a chat or every message, and read the storage figures. Administrators cannot act on each other.
 *
 * @author Angatu Sistemas
 */
public final class AdminService {

    private static final String WIPE_CONFIRMATION = "APAGAR TUDO";
    private static final int MAX_ATTEMPTS = 5;
    private static final long ATTEMPT_WINDOW_MS = 600_000;
    private static final int MAX_MUTE_MINUTES = 43_200;
    private static final int MAX_REASON = 200;
    private static final int MAX_BANS_LISTED = 500;
    private static final Map<String, Deque<Long>> FAILURES = new ConcurrentHashMap<>();

    private AdminService() {
    }

    public static void start() {
        Saveable.createIndex(Ban.class, "address");
    }

    static JsonObject handle(Session session, String type, JsonObject frame) {
        if ("admin.login".equals(type)) {
            return login(session, TextRules.string(frame, "password"));
        }
        if ("admin.mode".equals(type)) {
            return mode(session, frame.has("active") && frame.get("active").isJsonPrimitive() && frame.get("active").getAsBoolean());
        }
        if (!powers(session.id)) {
            throw new Refusal("Ative o modo administrador para fazer isso.");
        }
        return switch (type) {
            case "admin.kick" -> kick(session, frame);
            case "admin.ban" -> ban(session, frame);
            case "admin.unban" -> unban(frame);
            case "admin.mute" -> mute(session, frame);
            case "admin.clear" -> clear(frame);
            case "admin.bans" -> bans();
            case "admin.stats" -> stats();
            case "admin.wipe" -> wipe(frame);
            default -> throw new Refusal("Este pedido não existe.");
        };
    }

    private static JsonObject login(Session session, String password) {
        String expected = System.getProperty("angatu.admin.password", System.getenv("ADMIN_PASSWORD"));
        if (expected == null || expected.isBlank()) {
            throw new Refusal("O acesso de administrador não está configurado neste servidor.");
        }
        String byPerson = "id:" + session.id;
        String byAddress = "ip:" + session.address;
        if (tooMany(byPerson) || tooMany(byAddress)) {
            throw new Refusal("Muitas tentativas. Tente de novo daqui a alguns minutos.");
        }
        if (!MessageDigest.isEqual(sha256(expected), sha256(password))) {
            failed(byPerson);
            failed(byAddress);
            throw new Refusal("Senha incorreta.");
        }
        Admin admin = Saveable.findById(Admin.class, session.id);
        if (admin == null) {
            admin = new Admin();
            admin.setId(session.id);
            admin.setSince(System.currentTimeMillis());
        }
        admin.setActive(true);
        admin.save();
        return announce(session.id);
    }

    private static JsonObject mode(Session session, boolean active) {
        Admin admin = Saveable.mutate(Admin.class, session.id, stored -> stored.setActive(active));
        if (admin == null) {
            throw new Refusal("Você ainda não entrou como administrador.");
        }
        return announce(session.id);
    }

    /* Every device of the person learns the new state at once, and the one that asked gets it in its answer. */
    private static JsonObject announce(String person) {
        JsonObject state = status(person);
        JsonObject event = state.deepCopy();
        event.addProperty("t", "admin");
        NetHub.toPerson(person, event.toString(), null);
        JsonObject answer = new JsonObject();
        answer.add("admin", state);
        return answer;
    }

    private static JsonObject kick(Session session, JsonObject frame) {
        String target = target(session, frame);
        JsonObject notice = new JsonObject();
        notice.addProperty("t", "kicked");
        notice.addProperty("reason", reason(frame));
        NetHub.dismiss(target, notice.toString(), NetHub.CLOSE_KICKED, "kicked");
        return new JsonObject();
    }

    private static JsonObject ban(Session session, JsonObject frame) {
        String target = target(session, frame);
        Session latest = NetHub.latest(target);
        Ban ban = new Ban();
        ban.setId(target);
        ban.setAddress(latest != null && isPublic(latest.address) ? latest.address : null);
        ban.setReason(reason(frame));
        ban.setBy(session.id);
        ban.setAt(System.currentTimeMillis());
        ban.save();
        NetHub.dismiss(target, "{\"t\":\"banned\"}", NetHub.CLOSE_REFUSED, "banned");
        return new JsonObject();
    }

    private static JsonObject unban(JsonObject frame) {
        String target = TextRules.string(frame, "id");
        if (!NetHub.PERSON_ID.matcher(target).matches() || !Saveable.deleteById(Ban.class, target)) {
            throw new Refusal("Essa pessoa não está banida.");
        }
        return new JsonObject();
    }

    private static JsonObject mute(Session session, JsonObject frame) {
        String target = target(session, frame);
        JsonElement minutesField = frame.get("minutes");
        if (minutesField == null || !minutesField.isJsonPrimitive() || !minutesField.getAsJsonPrimitive().isNumber()) {
            throw new Refusal("Informe por quantos minutos.");
        }
        long minutes = Math.clamp(minutesField.getAsLong(), 0, MAX_MUTE_MINUTES);
        long until = 0;
        if (minutes == 0) {
            Saveable.deleteById(Mute.class, target);
        } else {
            until = System.currentTimeMillis() + minutes * 60_000;
            Mute mute = new Mute();
            mute.setId(target);
            mute.setUntil(until);
            mute.setBy(session.id);
            mute.save();
        }
        JsonObject event = new JsonObject();
        event.addProperty("t", "muted");
        event.addProperty("until", until);
        NetHub.toPerson(target, event.toString(), null);
        return new JsonObject();
    }

    private static JsonObject clear(JsonObject frame) {
        String chat = TextRules.string(frame, "chat");
        boolean valid = NetHub.PUBLIC_GROUP.equals(chat) || NetHub.GROUP_ID.matcher(chat).matches() || isDirect(chat);
        if (!valid) {
            throw new Refusal("Conversa inválida.");
        }
        int removed = MessageStore.clear(chat);
        JsonObject event = new JsonObject();
        event.addProperty("t", "chat.cleared");
        event.addProperty("chat", chat);
        NetHub.toChat(chat, event.toString(), null);
        JsonObject answer = new JsonObject();
        answer.addProperty("removed", removed);
        return answer;
    }

    private static boolean isDirect(String chat) {
        if (!chat.startsWith("dm:") || chat.indexOf('+') < 0) {
            return false;
        }
        String a = chat.substring(3, chat.indexOf('+'));
        String b = chat.substring(chat.indexOf('+') + 1);
        return NetHub.PERSON_ID.matcher(a).matches() && NetHub.PERSON_ID.matcher(b).matches() && a.compareTo(b) <= 0;
    }

    private static JsonObject bans() {
        List<Ban> all = Saveable.query(Ban.class, "SELECT data FROM " + Saveable.tableName(Ban.class)
                + " ORDER BY json_extract(data,'$.at') DESC LIMIT ?", MAX_BANS_LISTED);
        JsonArray list = new JsonArray();
        all.forEach(ban -> {
            JsonObject entry = new JsonObject();
            entry.addProperty("id", ban.getId());
            entry.addProperty("reason", ban.getReason());
            entry.addProperty("by", ban.getBy());
            entry.addProperty("at", ban.getAt());
            entry.addProperty("address", ban.getAddress() != null);
            list.add(entry);
        });
        JsonObject answer = new JsonObject();
        answer.add("bans", list);
        return answer;
    }

    private static JsonObject stats() {
        MessageStore.Totals totals = MessageStore.measure();
        JsonObject answer = new JsonObject();
        answer.addProperty("messageBytes", totals.bytes());
        answer.addProperty("messageCount", totals.count());
        answer.addProperty("attachmentBytes", AttachmentStore.bytes());
        answer.addProperty("attachmentLimit", AttachmentStore.limit());
        return answer;
    }

    private static JsonObject wipe(JsonObject frame) {
        if (!WIPE_CONFIRMATION.equals(TextRules.string(frame, "confirm"))) {
            throw new Refusal("Para apagar todas as mensagens, confirme digitando APAGAR TUDO.");
        }
        MessageStore.Wipe wipe = MessageStore.wipe();
        if (wipe == null) {
            throw new Refusal("Uma limpeza geral já está em andamento.");
        }
        NetHub.toEveryone("{\"t\":\"chat.wiped\"}");
        JsonObject answer = new JsonObject();
        answer.addProperty("removed", wipe.messages());
        answer.addProperty("attachments", wipe.attachments());
        return answer;
    }

    private static String target(Session session, JsonObject frame) {
        String target = TextRules.string(frame, "id");
        if (!NetHub.PERSON_ID.matcher(target).matches()) {
            throw new Refusal("Pessoa inválida.");
        }
        if (target.equals(session.id) || Saveable.exists(Admin.class, target)) {
            throw new Refusal("Não é possível agir sobre um administrador.");
        }
        return target;
    }

    private static String reason(JsonObject frame) {
        String reason = TextRules.clean(TextRules.string(frame, "reason")).replace('\n', ' ');
        return reason.length() > MAX_REASON ? reason.substring(0, MAX_REASON) : reason;
    }

    public static JsonObject status(String person) {
        Admin admin = Saveable.findById(Admin.class, person);
        JsonObject status = new JsonObject();
        status.addProperty("admin", admin != null);
        status.addProperty("active", admin != null && admin.isActive());
        return status;
    }

    public static boolean powers(String person) {
        Admin admin = Saveable.findById(Admin.class, person);
        return admin != null && admin.isActive();
    }

    public static long mutedUntil(String person) {
        Mute mute = Saveable.findById(Mute.class, person);
        return mute == null || mute.getUntil() <= System.currentTimeMillis() ? 0 : mute.getUntil();
    }

    static void requireNotMuted(String person) {
        if (mutedUntil(person) > 0) {
            throw new Refusal("Você foi silenciado por um administrador e não pode enviar, editar nem reagir por enquanto.");
        }
    }

    public static boolean banned(String person, String address) {
        if (Saveable.exists(Ban.class, person)) {
            return true;
        }
        return address != null && isPublic(address) && Saveable.findFirstByField(Ban.class, "address", address) != null;
    }

    /* Loopback and private addresses are shared by everyone behind them (the proxy, a test), so they are never banned. */
    static boolean isPublic(String address) {
        if (address == null || !address.matches("[0-9a-fA-F:.]+")) {
            return false;
        }
        try {
            InetAddress parsed = InetAddress.getByName(address);
            return !(parsed.isLoopbackAddress() || parsed.isSiteLocalAddress() || parsed.isLinkLocalAddress() || parsed.isAnyLocalAddress());
        } catch (UnknownHostException e) {
            return false;
        }
    }

    private static boolean tooMany(String key) {
        Deque<Long> failures = FAILURES.get(key);
        if (failures == null) {
            return false;
        }
        synchronized (failures) {
            long now = System.currentTimeMillis();
            while (!failures.isEmpty() && now - failures.peekFirst() > ATTEMPT_WINDOW_MS) {
                failures.pollFirst();
            }
            return failures.size() >= MAX_ATTEMPTS;
        }
    }

    private static void failed(String key) {
        Deque<Long> failures = FAILURES.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (failures) {
            failures.addLast(System.currentTimeMillis());
        }
    }

    public static void forgetOldFailures() {
        long now = System.currentTimeMillis();
        FAILURES.entrySet().removeIf(entry -> {
            synchronized (entry.getValue()) {
                Long last = entry.getValue().peekLast();
                return last == null || now - last > ATTEMPT_WINDOW_MS;
            }
        });
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
