package br.com.angatusistemas.voice.utils;

import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.HexFormat;
import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Thin wrappers over the JDK primitives the peer protocol and the updater need: Ed25519, X25519, HMAC, AES-GCM.
 *
 * @author Angatu Sistemas
 */
public final class Crypto {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int GCM_TAG_BITS = 128;

    private Crypto() {
    }

    public static KeyPair newSigningKeys() {
        return generate("Ed25519");
    }

    public static KeyPair newAgreementKeys() {
        return generate("X25519");
    }

    public static byte[] sign(PrivateKey key, byte[] data) {
        try {
            Signature s = Signature.getInstance("Ed25519");
            s.initSign(key);
            s.update(data);
            return s.sign();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("signing failed", e);
        }
    }

    public static boolean verify(byte[] publicKey, byte[] data, byte[] signature) {
        try {
            Signature s = Signature.getInstance("Ed25519");
            s.initVerify(KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(publicKey)));
            s.update(data);
            return s.verify(signature);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            return false;
        }
    }

    public static PrivateKey signingPrivate(byte[] pkcs8) {
        try {
            return KeyFactory.getInstance("Ed25519").generatePrivate(new PKCS8EncodedKeySpec(pkcs8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("invalid private key", e);
        }
    }

    public static PublicKey signingPublic(byte[] x509) {
        try {
            return KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(x509));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("invalid public key", e);
        }
    }

    public static byte[] agree(PrivateKey own, byte[] peerPublicX509) {
        try {
            KeyAgreement ka = KeyAgreement.getInstance("X25519");
            ka.init(own);
            ka.doPhase(KeyFactory.getInstance("X25519").generatePublic(new X509EncodedKeySpec(peerPublicX509)), true);
            return ka.generateSecret();
        } catch (GeneralSecurityException e) {
            throw new IllegalArgumentException("key agreement failed", e);
        }
    }

    public static byte[] sha256(byte[]... parts) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            for (byte[] part : parts) {
                md.update(part);
            }
            return md.digest();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    public static byte[] hmac(byte[] key, byte[] data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    public static byte[] seal(byte[] key, byte[] nonce, byte[] plain) {
        return gcm(Cipher.ENCRYPT_MODE, key, nonce, plain);
    }

    public static byte[] open(byte[] key, byte[] nonce, byte[] sealed) {
        return gcm(Cipher.DECRYPT_MODE, key, nonce, sealed);
    }

    public static String fingerprint(byte[] publicKey) {
        return HexFormat.of().formatHex(sha256(publicKey), 0, 10);
    }

    public static byte[] random(int bytes) {
        byte[] out = new byte[bytes];
        RANDOM.nextBytes(out);
        return out;
    }

    public static boolean equal(byte[] a, byte[] b) {
        return MessageDigest.isEqual(a, b);
    }

    public static String b64(byte[] data) {
        return Base64.getEncoder().encodeToString(data);
    }

    public static byte[] unb64(String text) {
        return Base64.getDecoder().decode(text);
    }

    public static String b64url(byte[] data) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(data);
    }

    public static byte[] unb64url(String text) {
        return Base64.getUrlDecoder().decode(text);
    }

    private static KeyPair generate(String algorithm) {
        try {
            return KeyPairGenerator.getInstance(algorithm).generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(algorithm + " unavailable", e);
        }
    }

    private static byte[] gcm(int mode, byte[] key, byte[] nonce, byte[] data) {
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(mode, new SecretKeySpec(key, "AES"), new GCMParameterSpec(GCM_TAG_BITS, nonce));
            return c.doFinal(data);
        } catch (GeneralSecurityException e) {
            throw new IllegalArgumentException("authentication failed", e);
        }
    }
}
