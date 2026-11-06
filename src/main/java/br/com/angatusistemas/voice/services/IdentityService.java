package br.com.angatusistemas.voice.services;

import br.com.angatusistemas.lib.database.Saveable;
import br.com.angatusistemas.voice.entities.Identity;
import br.com.angatusistemas.voice.utils.Crypto;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.time.Instant;

/**
 * Owns the local Ed25519 identity that proves who this installation is to every other participant.
 *
 * @author Angatu Sistemas
 */
public final class IdentityService {

    private static final String ID = "self";
    private static final int MAX_NAME = 32;

    private IdentityService() {
    }

    public static Self load() {
        Identity stored = Saveable.findById(Identity.class, ID);
        if (stored == null) {
            stored = create();
        }
        byte[] pub = Crypto.unb64(stored.getPublicKey());
        return new Self(Crypto.fingerprint(pub), pub, Crypto.signingPrivate(Crypto.unb64(stored.getPrivateKey())),
                displayName(stored));
    }

    public static String rename(String name) {
        String clean = clean(name);
        if (clean.isEmpty()) {
            throw new IllegalArgumentException("blank name");
        }
        Saveable.mutate(Identity.class, ID, identity -> identity.setDisplayName(clean));
        return clean;
    }

    public static String clean(String name) {
        String stripped = name == null ? "" : name.replaceAll("[\\p{Cntrl}\\u202A-\\u202E\\u2066-\\u2069]", "").strip();
        return stripped.length() > MAX_NAME ? stripped.substring(0, MAX_NAME) : stripped;
    }

    private static Identity create() {
        KeyPair keys = Crypto.newSigningKeys();
        Identity identity = new Identity();
        identity.setId(ID);
        identity.setPublicKey(Crypto.b64(keys.getPublic().getEncoded()));
        identity.setPrivateKey(Crypto.b64(keys.getPrivate().getEncoded()));
        identity.setCreatedAt(Instant.now().toEpochMilli());
        identity.setDisplayName(defaultName());
        identity.save();
        return identity;
    }

    private static String displayName(Identity stored) {
        String name = clean(stored.getDisplayName());
        return name.isEmpty() ? defaultName() : name;
    }

    private static String defaultName() {
        return clean(System.getenv().getOrDefault("COMPUTERNAME", "Usuário"));
    }

    public record Self(String id, byte[] publicKey, PrivateKey privateKey, String name) {

        public byte[] sign(byte[] data) {
            return Crypto.sign(privateKey, data);
        }
    }
}
