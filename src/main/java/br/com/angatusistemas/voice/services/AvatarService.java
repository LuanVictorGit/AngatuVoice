package br.com.angatusistemas.voice.services;

import br.com.angatusistemas.lib.database.Saveable;
import br.com.angatusistemas.voice.entities.Identity;
import br.com.angatusistemas.voice.entities.Peer;
import br.com.angatusistemas.voice.utils.Crypto;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Optional;
import javax.imageio.ImageIO;

/**
 * Profile pictures: a 128x128 WebP cropped by the interface, validated here before it is stored or sent to anyone.
 *
 * @author Angatu Sistemas
 */
public final class AvatarService {

    public static final int MAX_BYTES = 48 * 1024;
    private static final int DEFAULT_PICTURES = 10;
    private static final int MAX_SIDE = 256;
    private static final String SELF = "self";

    private AvatarService() {
    }

    public static byte[] validate(String base64) {
        if (base64 == null || base64.isEmpty() || base64.length() > MAX_BYTES * 4 / 3 + 8) {
            throw new IllegalArgumentException("avatar size");
        }
        byte[] data = Crypto.unb64(base64);
        boolean webp = data.length > 12 && data.length <= MAX_BYTES
                && "RIFF".equals(new String(data, 0, 4, StandardCharsets.US_ASCII))
                && "WEBP".equals(new String(data, 8, 4, StandardCharsets.US_ASCII));
        if (!webp) {
            throw new IllegalArgumentException("avatar is not a small WebP");
        }
        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(data));
            if (image == null || image.getWidth() > MAX_SIDE || image.getHeight() > MAX_SIDE) {
                throw new IllegalArgumentException("avatar cannot be decoded");
            }
        } catch (IOException e) {
            throw new IllegalArgumentException("avatar cannot be decoded", e);
        }
        return data;
    }

    public static void setOwn(String base64) {
        String stored = base64 == null || base64.isEmpty() ? null : Crypto.b64(validate(base64));
        Saveable.mutate(Identity.class, SELF, identity -> identity.setAvatar(stored));
    }

    public static Optional<String> own() {
        Identity identity = Saveable.findById(Identity.class, SELF);
        return Optional.ofNullable(identity == null ? null : identity.getAvatar());
    }

    public static void setPeer(String peerId, String base64) {
        String stored = base64 == null || base64.isEmpty() ? null : Crypto.b64(validate(base64));
        Saveable.mutate(Peer.class, peerId, peer -> peer.setAvatar(stored));
    }

    public static Optional<byte[]> bytes(String id) {
        IdentityService.Self me = PeerNetwork.me();
        String raw;
        if (me != null && me.id().equals(id)) {
            raw = own().orElse(null);
        } else {
            Peer peer = Saveable.findById(Peer.class, id);
            raw = peer == null ? null : peer.getAvatar();
        }
        return Optional.ofNullable(raw).map(Crypto::unb64);
    }

    /* The photo when there is one, otherwise one of the ten default pictures, always the same one for the same id. */
    public static Optional<byte[]> picture(String id) {
        Optional<byte[]> photo = bytes(id);
        if (photo.isPresent()) {
            return photo;
        }
        try (InputStream in = AvatarService.class.getResourceAsStream("/public/assets/avatars/avatar-" + defaultIndex(id) + ".png")) {
            return in == null ? Optional.empty() : Optional.of(in.readAllBytes());
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    /* FNV-1a over the id, as ui.js does it: the page and the native windows must pick the same picture. */
    public static int defaultIndex(String id) {
        int hash = 0x811c9dc5;
        for (int i = 0; i < id.length(); i++) {
            hash ^= id.charAt(i);
            hash *= 0x01000193;
        }
        return Integer.remainderUnsigned(hash, DEFAULT_PICTURES);
    }

    public static String version(String base64) {
        if (base64 == null || base64.isEmpty()) {
            return "";
        }
        byte[] hash = Crypto.sha256(base64.getBytes(StandardCharsets.US_ASCII));
        return HexFormat.of().formatHex(Arrays.copyOf(hash, 4));
    }
}
