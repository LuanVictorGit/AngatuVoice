package br.com.angatusistemas.voice.utils;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Resolves where the app keeps user data, local caches and its own installation, with overrides for tests.
 *
 * @author Angatu Sistemas
 */
public final class AppDirs {

    private static final Path DATA = resolve("angatu.data", "APPDATA");
    private static final Path LOCAL = resolve("angatu.local", "LOCALAPPDATA");

    private AppDirs() {
    }

    public static Path data() {
        return DATA;
    }

    public static Path local() {
        return LOCAL;
    }

    public static Path updates() {
        return LOCAL.resolve("updates");
    }

    public static Path backups() {
        return DATA.resolve("backups");
    }

    public static Path logs() {
        return DATA.resolve("logs");
    }

    public static Optional<Path> install() {
        String home = System.getProperty("angatu.home");
        if (home != null && !home.isBlank()) {
            return Optional.of(Path.of(home));
        }
        return Optional.ofNullable(System.getProperty("jpackage.app-path")).map(Path::of).map(Path::getParent);
    }

    public static Path ensure(Path dir) {
        try {
            return Files.createDirectories(dir);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot create " + dir, e);
        }
    }

    private static Path resolve(String property, String env) {
        String override = System.getProperty(property);
        if (override != null && !override.isBlank()) {
            return ensure(Path.of(override));
        }
        String base = System.getenv(env);
        Path root = base == null || base.isBlank() ? Path.of(System.getProperty("user.home")) : Path.of(base);
        return ensure(root.resolve("AngatuVoice"));
    }
}
