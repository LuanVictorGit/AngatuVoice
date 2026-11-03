package br.com.angatusistemas.voice.services;

import br.com.angatusistemas.lib.database.Saveable;
import br.com.angatusistemas.lib.gson.GsonAPI;
import br.com.angatusistemas.voice.entities.Group;
import br.com.angatusistemas.voice.utils.Crypto;
import com.google.gson.JsonParseException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Manages the conversation spaces: the automatic Radmin network and private groups shared by an invite code.
 *
 * @author Angatu Sistemas
 */
public final class GroupService {

    public static final String RADMIN = "radmin";
    private static final String PREFIX = "AV1.";
    private static final Pattern ID = Pattern.compile("g_[0-9a-f]{16}");
    private static final int MAX_NAME = 40;

    private GroupService() {
    }

    public static void ensureRadmin() {
        if (!Saveable.exists(Group.class, RADMIN)) {
            Group radmin = new Group();
            radmin.setId(RADMIN);
            radmin.setName("Rede Radmin");
            radmin.setKind("radmin");
            radmin.setCreatedAt(Instant.now().toEpochMilli());
            radmin.save();
        }
    }

    public static List<Group> list() {
        return Saveable.findAll(Group.class);
    }

    public static Group find(String id) {
        return Saveable.findById(Group.class, id);
    }

    public static Group create(String name) {
        String clean = IdentityService.clean(name);
        if (clean.isEmpty() || clean.length() > MAX_NAME) {
            throw new IllegalArgumentException("invalid group name");
        }
        Group group = new Group();
        group.setId("g_" + HexFormat.of().formatHex(Crypto.random(8)));
        group.setName(clean);
        group.setKind("private");
        group.setSecret(Crypto.b64url(Crypto.random(16)));
        group.setCreatedAt(Instant.now().toEpochMilli());
        group.save();
        return group;
    }

    public static String invite(String id) {
        Group group = find(id);
        if (group == null || group.getSecret() == null) {
            throw new IllegalArgumentException("group has no invite");
        }
        String json = GsonAPI.get().toJson(new Invite(group.getId(), group.getName(), group.getSecret()));
        return PREFIX + Crypto.b64url(json.getBytes(StandardCharsets.UTF_8));
    }

    public static Group join(String code) {
        Invite invite = parse(code);
        if (Saveable.exists(Group.class, invite.id())) {
            throw new IllegalStateException("already member");
        }
        Group group = new Group();
        group.setId(invite.id());
        group.setName(invite.name());
        group.setKind("private");
        group.setSecret(invite.secret());
        group.setCreatedAt(Instant.now().toEpochMilli());
        group.save();
        return group;
    }

    public static void leave(String id) {
        Group group = find(id);
        if (group == null || RADMIN.equals(id)) {
            throw new IllegalArgumentException("cannot leave " + id);
        }
        group.delete();
    }

    private static Invite parse(String code) {
        try {
            if (code == null || !code.startsWith(PREFIX) || code.length() > 400) {
                throw new IllegalArgumentException("invalid invite");
            }
            Invite invite = GsonAPI.get().fromJson(
                    new String(Crypto.unb64url(code.substring(PREFIX.length())), StandardCharsets.UTF_8), Invite.class);
            boolean valid = invite != null && invite.id() != null && ID.matcher(invite.id()).matches()
                    && invite.name() != null && !invite.name().isBlank() && invite.name().length() <= MAX_NAME
                    && invite.secret() != null && Crypto.unb64url(invite.secret()).length == 16;
            if (!valid) {
                throw new IllegalArgumentException("invalid invite");
            }
            return new Invite(invite.id(), IdentityService.clean(invite.name()), invite.secret());
        } catch (JsonParseException | IllegalArgumentException e) {
            throw new IllegalArgumentException("invalid invite", e);
        }
    }

    private record Invite(String id, String name, String secret) {
    }
}
