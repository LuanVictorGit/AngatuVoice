import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Writes manifest.json and manifest.json.sig for a release package. The Ed25519 private key comes from the
 * UPDATE_SIGNING_KEY environment variable (PKCS#8, Base64); it never touches the repository.
 * Usage: java tools/Release.java VERSION CHANNEL ZIP REPOSITORY MIN_VERSION OUT_DIR [NOTES_FILE]
 */
public class Release {
    public static void main(String[] a) throws Exception {
        String version = a[0], channel = a[1], repo = a[3], minVersion = a[4];
        Path zip = Path.of(a[2]), out = Path.of(a[5]);
        String notes = a.length > 6 && Files.exists(Path.of(a[6])) ? Files.readString(Path.of(a[6])).strip() : "";
        String key = System.getenv("UPDATE_SIGNING_KEY");
        if (key == null || key.isBlank()) throw new IllegalStateException("UPDATE_SIGNING_KEY is not set");
        String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(zip)));
        String name = zip.getFileName().toString();
        String url = "https://github.com/" + repo + "/releases/download/v" + version + "/" + name;
        String json = """
                {"app":"angatu-voice","version":"%s","channel":"%s","minVersion":"%s","os":"windows","arch":"x64",
                "mandatory":%s,"notes":"%s","publishedAt":"%s",
                "pkg":{"name":"%s","url":"%s","size":%d,"sha256":"%s"}}
                """.formatted(version, channel, minVersion, System.getenv().getOrDefault("MANDATORY", "false"),
                esc(notes), Instant.now(), name, url, Files.size(zip), sha);
        byte[] raw = json.getBytes(StandardCharsets.UTF_8);
        PrivateKey pk = KeyFactory.getInstance("Ed25519").generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(key.strip())));
        Signature s = Signature.getInstance("Ed25519");
        s.initSign(pk);
        s.update(raw);
        Files.createDirectories(out);
        Files.write(out.resolve("manifest.json"), raw);
        Files.writeString(out.resolve("manifest.json.sig"), Base64.getEncoder().encodeToString(s.sign()));
        System.out.println("manifest for " + version + " (" + channel + ") sha256=" + sha);
    }

    static String esc(String t) {
        return t.replace("\\", "\\\\").replace("\"", "\\\"").replace("\r", "").replace("\n", "\\n");
    }
}