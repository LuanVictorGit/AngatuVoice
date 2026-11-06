package br.com.angatusistemas.voice.updater;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.angatusistemas.voice.utils.Crypto;
import br.com.angatusistemas.voice.utils.Version;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Signature, field validation, version ordering and package extraction rules of the updater.
 *
 * @author Angatu Sistemas
 */
class UpdaterSecurityTest {

    private static final Set<String> HOSTS = Set.of("github.com");
    private static final String SHA = "a".repeat(64);

    private static String manifest(String url, String sha) {
        return """
                {"app":"angatu-voice","version":"1.1.0","channel":"stable","minVersion":"1.0.0","os":"windows",
                "arch":"x64","mandatory":false,"notes":"","publishedAt":"2026-11-03T00:00:00Z",
                "pkg":{"name":"angatu-voice-1.1.0.zip","url":"%s","size":1024,"sha256":"%s"}}
                """.formatted(url, sha);
    }

    @Test
    void acceptsValidSignature() {
        KeyPair keys = Crypto.newSigningKeys();
        byte[] raw = manifest("https://github.com/x/y.zip", SHA).getBytes(StandardCharsets.UTF_8);
        Manifest m = Manifest.verified(raw, Crypto.sign(keys.getPrivate(), raw), keys.getPublic().getEncoded(), HOSTS);
        assertEquals("1.1.0", m.version());
    }

    @Test
    void rejectsTamperedOrForeignSignature() {
        KeyPair keys = Crypto.newSigningKeys();
        byte[] raw = manifest("https://github.com/x/y.zip", SHA).getBytes(StandardCharsets.UTF_8);
        byte[] sig = Crypto.sign(keys.getPrivate(), raw);
        byte[] tampered = manifest("https://github.com/x/z.zip", SHA).getBytes(StandardCharsets.UTF_8);
        assertThrows(SecurityException.class,
                () -> Manifest.verified(tampered, sig, keys.getPublic().getEncoded(), HOSTS));
        byte[] other = Crypto.newSigningKeys().getPublic().getEncoded();
        assertThrows(SecurityException.class, () -> Manifest.verified(raw, sig, other, HOSTS));
    }

    @Test
    void rejectsBadHashAndForeignHost() {
        KeyPair keys = Crypto.newSigningKeys();
        for (String json : new String[]{manifest("https://github.com/x/y.zip", "XYZ"),
                manifest("https://evil.example/y.zip", SHA), manifest("http://github.com/y.zip", SHA)}) {
            byte[] raw = json.getBytes(StandardCharsets.UTF_8);
            byte[] sig = Crypto.sign(keys.getPrivate(), raw);
            assertThrows(IllegalArgumentException.class,
                    () -> Manifest.verified(raw, sig, keys.getPublic().getEncoded(), HOSTS));
        }
    }

    @Test
    void ordersVersions() {
        assertTrue(Version.parse("1.0.1").compareTo(Version.parse("1.0.0")) > 0);
        assertTrue(Version.parse("1.1.0-beta.1").compareTo(Version.parse("1.1.0")) < 0);
        assertTrue(Version.parse("v2.0.0").compareTo(Version.parse("1.9.9")) > 0);
        assertFalse(Version.parse("1.0.0").isPreRelease());
        assertThrows(IllegalArgumentException.class, () -> Version.parse("1.0"));
    }

    @Test
    void stageRejectsZipSlip(@TempDir Path dir) throws Exception {
        Path zip = dir.resolve("pkg.zip");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip))) {
            out.putNextEntry(new ZipEntry("../evil.txt"));
            out.write(1);
            out.closeEntry();
        }
        Exception e = assertThrows(java.io.IOException.class, () -> PackageInstaller.stage(zip, null, java.util.Optional.empty()));
        assertTrue(e.getMessage().contains("escapes"));
        assertFalse(Files.exists(dir.resolve("evil.txt")));
    }
}
