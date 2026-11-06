package br.com.angatusistemas.voice.updater;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.angatusistemas.voice.utils.Version;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The small update package: it must turn into a complete install, and only fit the install it was built on.
 *
 * @author Angatu Sistemas
 */
class PatchInstallTest {

    private static final String SHA = "a".repeat(64);
    private static final Manifest.Pkg PKG = new Manifest.Pkg("full.zip", "https://github.com/x/full.zip", 10, SHA);

    @TempDir
    Path temp;

    private static void write(Path file, String body) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, body);
    }

    private Path install(String libBody) throws IOException {
        Path root = temp.resolve("install-" + libBody);
        write(root.resolve("runtime/bin/java.dll"), "runtime");
        write(root.resolve("app/lib/dep.jar"), libBody);
        write(root.resolve("app/angatu-voice-1.0.0.jar"), "old app");
        write(root.resolve("AngatuVoice.exe"), "old launcher");
        return root;
    }

    private static Manifest manifest(Manifest.Patch patch) {
        return new Manifest("angatu-voice", "2.0.0", "stable", "1.0.0", "windows", "x64", false, "", "", PKG, patch);
    }

    @Test
    void identityFollowsTheRuntimeAndLibrariesOnly() throws IOException {
        Path a = install("same");
        String before = PackageInstaller.baseIdentity(a);
        write(a.resolve("app/angatu-voice-1.0.0.jar"), "changed app");
        write(a.resolve("AngatuVoice.exe"), "changed launcher");
        assertEquals(before, PackageInstaller.baseIdentity(a));
        assertNotEquals(before, PackageInstaller.baseIdentity(install("other")));
    }

    @Test
    void aPatchStagesACompleteInstall() throws IOException {
        Path current = install("same");
        Path zip = temp.resolve("updates/app.zip");
        Files.createDirectories(zip.getParent());
        try (OutputStream out = Files.newOutputStream(zip); ZipOutputStream z = new ZipOutputStream(out)) {
            for (Map.Entry<String, String> e : Map.of("AngatuVoice.exe", "new launcher", "app/angatu-voice-2.0.0.jar", "new app").entrySet()) {
                z.putNextEntry(new ZipEntry(e.getKey()));
                z.write(e.getValue().getBytes());
                z.closeEntry();
            }
        }
        Path staged = PackageInstaller.stage(zip, manifest(null), Optional.of(current));
        assertEquals("new app", Files.readString(staged.resolve("app/angatu-voice-2.0.0.jar")));
        assertEquals("new launcher", Files.readString(staged.resolve("AngatuVoice.exe")));
        assertEquals("same", Files.readString(staged.resolve("app/lib/dep.jar")));
        assertTrue(Files.exists(staged.resolve("runtime/bin/java.dll")));
        assertTrue(Files.notExists(staged.resolve("app/angatu-voice-1.0.0.jar")), "the previous jar never comes along");
    }

    @Test
    void oldPackagesGoAndAWaitingOneStays() throws IOException {
        Path updates = temp.resolve("updates");
        write(updates.resolve("1.5.3/package.zip.part"), "abandoned");
        write(updates.resolve("1.7.0/package.zip"), "installed already");
        write(updates.resolve("not-a-version/x"), "unknown");
        write(updates.resolve("2.0.0/app.zip"), "waiting for the end of a call");
        write(updates.resolve("apply-update.ps1"), "needed by recovery");
        Version current = Version.parse("1.7.0");
        PackageInstaller.removePackages(updates, folder -> PackageInstaller.isNewerThan(folder, current));
        assertTrue(Files.notExists(updates.resolve("1.5.3")));
        assertTrue(Files.notExists(updates.resolve("1.7.0")));
        assertTrue(Files.notExists(updates.resolve("not-a-version")));
        assertTrue(Files.exists(updates.resolve("2.0.0/app.zip")));
        assertTrue(Files.exists(updates.resolve("apply-update.ps1")));
    }

    @Test
    void aVersionThatKeepsFailingIsLeftAlone() {
        br.com.angatusistemas.voice.entities.UpdateRecord failed = new br.com.angatusistemas.voice.entities.UpdateRecord();
        failed.setVersion("2.0.0");
        failed.setOutcome("rolled_back");
        assertTrue(!UpdateService.gaveUpOn("2.0.0", java.util.List.of(failed)), "one failure deserves another try");
        assertTrue(UpdateService.gaveUpOn("2.0.0", java.util.List.of(failed, failed)));
        assertTrue(!UpdateService.gaveUpOn("2.0.1", java.util.List.of(failed, failed)), "the next version starts over");
    }

    @Test
    void aPatchFromAnotherHostIsRefused() {
        Manifest.Patch patch = new Manifest.Patch("app.zip", "https://evil.example/app.zip", 10, SHA, SHA);
        assertThrows(IllegalArgumentException.class, () -> manifest(patch).validate(java.util.Set.of("github.com")));
    }
}
