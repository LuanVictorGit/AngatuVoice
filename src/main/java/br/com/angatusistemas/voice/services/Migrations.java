package br.com.angatusistemas.voice.services;

import br.com.angatusistemas.lib.console.Console;
import br.com.angatusistemas.lib.database.Saveable;
import br.com.angatusistemas.voice.entities.SchemaState;
import br.com.angatusistemas.voice.entities.Setting;
import br.com.angatusistemas.voice.utils.AppDirs;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.stream.Stream;

/**
 * Brings the local database to the schema this build expects: ordered steps, a backup first, and no silent downgrade.
 *
 * @author Angatu Sistemas
 */
public final class Migrations {

    private static final String ID = "schema";
    private static final int KEEP_BACKUPS = 3;
    private static final List<Runnable> STEPS = List.of(GroupService::ensureRadmin, br.com.angatusistemas.voice.services.radmin.NetworkManager::ensureDefaultNetwork);

    private Migrations() {
    }

    public static int target() {
        return STEPS.size();
    }

    public static void run() {
        SchemaState state = Saveable.findById(SchemaState.class, ID);
        int stored = state == null ? 0 : state.getVersion();
        if (stored > target()) {
            throw new IncompatibleDataException(stored, target());
        }
        if (stored == target()) {
            return;
        }
        if (stored > 0) {
            backup(stored);
        }
        for (int step = stored; step < target(); step++) {
            int applied = step + 1;
            Saveable.transaction(() -> {
                STEPS.get(applied - 1).run();
                SchemaState next = new SchemaState();
                next.setId(ID);
                next.setVersion(applied);
                next.save();
            });
            Console.log("Banco de dados migrado para o esquema %d", applied);
        }
        SchemaState check = Saveable.findById(SchemaState.class, ID);
        if (check == null || check.getVersion() != target()) {
            throw new IllegalStateException("migration did not reach schema " + target());
        }
    }

    public static void indexes() {
        MessageService.indexes();
    }

    private static void backup(int version) {
        Path dir = AppDirs.ensure(AppDirs.backups());
        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        Path target = dir.resolve("database-schema" + version + "-" + stamp + ".db");
        Saveable.query(Setting.class, "VACUUM INTO ?", target.toString());
        try (Stream<Path> files = Files.list(dir)) {
            files.filter(f -> f.getFileName().toString().startsWith("database-schema")).sorted().toList().reversed()
                    .stream().skip(KEEP_BACKUPS).forEach(old -> old.toFile().delete());
        } catch (IOException e) {
            Console.warn("Não foi possível limpar backups antigos: %s", e.getMessage());
        }
    }

    public static final class IncompatibleDataException extends RuntimeException {

        public IncompatibleDataException(int stored, int supported) {
            super("database schema " + stored + " is newer than the supported " + supported);
        }
    }
}
