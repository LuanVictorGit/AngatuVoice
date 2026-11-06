package br.com.angatusistemas.voice.updater;

import br.com.angatusistemas.lib.gson.GsonAPI;
import br.com.angatusistemas.voice.utils.Crypto;
import br.com.angatusistemas.voice.utils.AppDirs;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Turns a verified package into a staged tree and hands the file swap to the helper script, which outlives the app.
 *
 * @author Angatu Sistemas
 */
final class PackageInstaller {

    private static final long MAX_UNPACKED_BYTES = 1_500L * 1024 * 1024;
    private static final long STALE_JOURNAL_MS = 3 * 60_000L;
    private static final String SCRIPT = "apply-update.ps1";
    private static final byte[] UTF8_BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};

    private PackageInstaller() {
    }

    /* What a patch does not carry. Its hash decides whether the small package fits this install. */
    private static final String[] BASE_FOLDERS = {"runtime", "app/lib"};

    static String baseIdentity(Path install) throws IOException {
        List<String> lines = new ArrayList<>();
        for (String folder : BASE_FOLDERS) {
            Path root = install.resolve(folder);
            if (!Files.isDirectory(root)) {
                throw new IOException("install has no " + folder);
            }
            try (Stream<Path> walk = Files.walk(root)) {
                for (Path file : walk.filter(Files::isRegularFile).toList()) {
                    lines.add(install.relativize(file).toString().replace('\\', '/') + " " + Files.size(file) + " " + sha256(file));
                }
            }
        }
        Collections.sort(lines);
        return HexFormat.of().formatHex(Crypto.sha256(String.join("\n", lines).getBytes(StandardCharsets.UTF_8)));
    }

    static String sha256(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[64 * 1024];
            for (int n = in.read(buffer); n >= 0; n = in.read(buffer)) {
                md.update(buffer, 0, n);
            }
            return HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /* The staged tree is always a complete install: a patch borrows the rest from the current one, by hard link where the disk allows. */
    private static void borrowBase(Path install, Path staging) throws IOException {
        for (String folder : BASE_FOLDERS) {
            Path root = install.resolve(folder);
            try (Stream<Path> walk = Files.walk(root)) {
                for (Path source : walk.toList()) {
                    Path target = staging.resolve(install.relativize(source).toString());
                    if (Files.isDirectory(source)) {
                        Files.createDirectories(target);
                    } else {
                        link(source, target);
                    }
                }
            }
        }
    }

    private static void link(Path source, Path target) throws IOException {
        try {
            Files.createLink(target, source);
        } catch (IOException | UnsupportedOperationException e) {
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    static Path stage(Path zip, Manifest manifest, Optional<Path> patchOver) throws IOException {
        Path staging = zip.resolveSibling("staging");
        deleteTree(staging);
        Files.createDirectories(staging);
        long total = 0;
        try (ZipInputStream in = new ZipInputStream(Files.newInputStream(zip))) {
            for (ZipEntry entry = in.getNextEntry(); entry != null; entry = in.getNextEntry()) {
                Path target = staging.resolve(entry.getName()).normalize();
                if (!target.startsWith(staging)) {
                    throw new IOException("package entry escapes the staging folder: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(target);
                    continue;
                }
                Files.createDirectories(target.getParent());
                try (OutputStream out = Files.newOutputStream(target)) {
                    total += copyBounded(in, out, MAX_UNPACKED_BYTES - total);
                }
            }
        }
        if (patchOver.isPresent()) {
            borrowBase(patchOver.get(), staging);
        }
        verifyLayout(staging, manifest);
        return staging;
    }

    static void launch(Path staging, Manifest manifest, String oldVersion, long pid) throws IOException {
        Path install = AppDirs.install().orElseThrow(() -> new IOException("not an installed copy"));
        Path script = writeScript();
        new ProcessBuilder("powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-WindowStyle", "Hidden",
                "-File", script.toString(), "-InstallDir", install.toString(), "-DataDir", AppDirs.data().toString(),
                "-StagingDir", staging.toString(), "-ProcessId", String.valueOf(pid), "-Version", manifest.version(),
                "-OldVersion", oldVersion).start();
    }

    static void recoverInterrupted() {
        Path journal = AppDirs.data().resolve("update-journal.json");
        Optional<Path> install = AppDirs.install();
        try {
            if (install.isEmpty() || !Files.exists(journal)
                    || System.currentTimeMillis() - Files.getLastModifiedTime(journal).toMillis() < STALE_JOURNAL_MS) {
                return;
            }
            new ProcessBuilder("powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-WindowStyle", "Hidden",
                    "-File", writeScript().toString(), "-InstallDir", install.get().toString(), "-DataDir",
                    AppDirs.data().toString(), "-Recover").start();
        } catch (IOException e) {
            throw new IllegalStateException("cannot start update recovery", e);
        }
    }

    static Optional<JsonObject> takeResult() {
        Path file = AppDirs.data().resolve("update-result.json");
        try {
            if (!Files.exists(file)) {
                return Optional.empty();
            }
            JsonObject result = GsonAPI.get().fromJson(Files.readString(file).strip(), JsonObject.class);
            Files.deleteIfExists(file);
            return Optional.ofNullable(result);
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }

    static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        }
    }

    private static void verifyLayout(Path staging, Manifest manifest) throws IOException {
        Path jar = staging.resolve("app").resolve("angatu-voice-" + manifest.version() + ".jar");
        boolean complete = Files.isRegularFile(staging.resolve("AngatuVoice.exe")) && Files.isRegularFile(jar)
                && Files.isRegularFile(staging.resolve("runtime").resolve("bin").resolve("java.dll"));
        if (!complete) {
            throw new IOException("package layout does not match version " + manifest.version());
        }
    }

    private static Path writeScript() throws IOException {
        Path script = AppDirs.ensure(AppDirs.updates()).resolve(SCRIPT);
        try (InputStream in = PackageInstaller.class.getResourceAsStream("/updater/" + SCRIPT)) {
            if (in == null) {
                throw new IOException("update script missing from the package");
            }
            byte[] body = in.readAllBytes();
            byte[] withBom = new byte[UTF8_BOM.length + body.length];
            System.arraycopy(UTF8_BOM, 0, withBom, 0, UTF8_BOM.length);
            System.arraycopy(body, 0, withBom, UTF8_BOM.length, body.length);
            Files.write(script, withBom);
            Files.setLastModifiedTime(script, FileTime.fromMillis(System.currentTimeMillis()));
        }
        return script;
    }

    private static long copyBounded(InputStream in, OutputStream out, long remaining) throws IOException {
        byte[] buffer = new byte[64 * 1024];
        long copied = 0;
        for (int n = in.read(buffer); n >= 0; n = in.read(buffer)) {
            copied += n;
            if (copied > remaining) {
                throw new IOException("package is larger than allowed once unpacked");
            }
            out.write(buffer, 0, n);
        }
        return copied;
    }

    static void moveReplacing(Path from, Path to) throws IOException {
        Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
    }
}
