package br.com.angatusistemas.voice.services;

import br.com.angatusistemas.voice.utils.AppDirs;
import io.javalin.http.BadRequestResponse;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The sound wheel's eight slots. Each plays a sound the app draws itself unless the person put an audio file of their own
 * in it; those files live in the data folder, one per slot, and are checked to really be audio before they are kept.
 *
 * @author Angatu Sistemas
 */
public final class SoundboardService {

    public static final int SLOTS = 8;
    public static final long MAX_BYTES = 2L * 1024 * 1024;

    private SoundboardService() {
    }

    public static boolean validSlot(int slot) {
        return slot >= 0 && slot < SLOTS;
    }

    public static List<Integer> custom() {
        List<Integer> slots = new ArrayList<>();
        for (int i = 0; i < SLOTS; i++) {
            if (Files.isRegularFile(file(i))) {
                slots.add(i);
            }
        }
        return slots;
    }

    public static Optional<Path> read(int slot) {
        Path file = file(slot);
        return Files.isRegularFile(file) ? Optional.of(file) : Optional.empty();
    }

    public static void store(int slot, byte[] data) {
        if (data.length == 0 || data.length > MAX_BYTES) {
            throw new BadRequestResponse("Escolha um áudio de até 2 MB.");
        }
        if (!looksLikeAudio(data)) {
            throw new BadRequestResponse("Esse arquivo não é um áudio MP3, OGG, WAV, WebM ou M4A.");
        }
        try {
            Path target = file(slot);
            Files.createDirectories(target.getParent());
            Path partial = target.resolveSibling(target.getFileName() + ".part");
            Files.write(partial, data);
            Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static void remove(int slot) {
        try {
            Files.deleteIfExists(file(slot));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Path file(int slot) {
        return AppDirs.data().resolve("soundboard").resolve("slot-" + slot);
    }

    /* The page decodes what it gets, so anything that is not audio is refused here rather than handed to the decoder. */
    private static boolean looksLikeAudio(byte[] d) {
        if (d.length < 12) {
            return false;
        }
        boolean id3 = d[0] == 'I' && d[1] == 'D' && d[2] == '3';
        boolean mpegFrame = (d[0] & 0xFF) == 0xFF && (d[1] & 0xE0) == 0xE0;
        boolean ogg = d[0] == 'O' && d[1] == 'g' && d[2] == 'g' && d[3] == 'S';
        boolean wav = d[0] == 'R' && d[1] == 'I' && d[2] == 'F' && d[3] == 'F' && d[8] == 'W' && d[9] == 'A' && d[10] == 'V' && d[11] == 'E';
        boolean webm = (d[0] & 0xFF) == 0x1A && (d[1] & 0xFF) == 0x45 && (d[2] & 0xFF) == 0xDF && (d[3] & 0xFF) == 0xA3;
        boolean mp4 = d[4] == 'f' && d[5] == 't' && d[6] == 'y' && d[7] == 'p';
        return id3 || mpegFrame || ogg || wav || webm || mp4;
    }
}
