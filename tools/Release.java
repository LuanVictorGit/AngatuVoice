import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;
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
        String patch = "";
        if (a.length > 8) {
            Path patchZip = Path.of(a[7]);
            String patchName = patchZip.getFileName().toString();
            patch = """
                    ,"patch":{"name":"%s","url":"%s","size":%d,"sha256":"%s","base":"%s"}""".formatted(patchName,
                    "https://github.com/" + repo + "/releases/download/v" + version + "/" + patchName, Files.size(patchZip),
                    hex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(patchZip))), baseIdentity(Path.of(a[8])));
        }
        String json = """
                {"app":"angatu-voice","version":"%s","channel":"%s","minVersion":"%s","os":"windows","arch":"x64",
                "mandatory":%s,"notes":"%s","publishedAt":"%s",
                "pkg":{"name":"%s","url":"%s","size":%d,"sha256":"%s"}%s}
                """.formatted(version, channel, minVersion, System.getenv().getOrDefault("MANDATORY", "false"),
                esc(notes), Instant.now(), name, url, Files.size(zip), sha, patch);
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

    /* Must match PackageInstaller.baseIdentity: the client hashes its own install the same way. */
    static String baseIdentity(Path install) throws Exception {
        List<String> lines = new ArrayList<>();
        for (String folder : new String[] {"runtime", "app/lib"}) {
            try (Stream<Path> walk = Files.walk(install.resolve(folder))) {
                for (Path file : walk.filter(Files::isRegularFile).toList()) {
                    lines.add(install.relativize(file).toString().replace('\\', '/') + " " + Files.size(file) + " "
                            + hex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file))));
                }
            }
        }
        Collections.sort(lines);
        return hex(MessageDigest.getInstance("SHA-256").digest(String.join("\n", lines).getBytes(StandardCharsets.UTF_8)));
    }

    static String hex(byte[] bytes) {
        return HexFormat.of().formatHex(bytes);
    }

    static String esc(String t) {
        return t.replace("\\", "\\\\").replace("\"", "\\\"").replace("\r", "").replace("\n", "\\n");
    }
}