package br.com.angatusistemas.voicerelay.services;

import br.com.angatusistemas.lib.console.Console;
import br.com.angatusistemas.lib.database.Saveable;
import br.com.angatusistemas.lib.task.Task;
import br.com.angatusistemas.voicerelay.entities.Attachment;
import br.com.angatusistemas.voicerelay.entities.StoredMessage;
import br.com.angatusistemas.voicerelay.entities.StoredMessage.AttachmentRef;
import br.com.angatusistemas.voicerelay.utils.MediaInfo;
import br.com.angatusistemas.voicerelay.utils.MediaInfo.Media;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

/**
 * Files people attach to messages, kept on disk beside the database and outside the message storage count. Uploads are
 * streamed straight to disk, never held in memory. An upload no message takes within an hour is deleted, and past the
 * attachment ceiling the oldest files go and their messages show them as expired.
 *
 * @author Angatu Sistemas
 */
public final class AttachmentStore {

    public static final long MAX_FILE_BYTES = 25L * 1024 * 1024;
    public static final long DEFAULT_LIMIT_BYTES = 10L * 1024 * 1024 * 1024;
    public static final Pattern ID = Pattern.compile("[0-9a-f]{32}");

    private static final String TABLE = Saveable.tableName(Attachment.class);
    private static final long CHECK_EVERY_MS = 3_600_000;
    private static final long UNBOUND_TTL_MS = 3_600_000;
    private static final int MAX_PENDING_PER_PERSON = 20;
    private static final int MAX_NAME = 200;
    private static final int TARGET_PERCENT = 90;
    private static final int BATCH = 100;
    private static final AtomicLong BYTES = new AtomicLong();
    private static final AtomicBoolean ENFORCING = new AtomicBoolean();

    private AttachmentStore() {
    }

    public static void start() {
        Saveable.createIndex(Attachment.class, "owner");
        Saveable.createIndex(Attachment.class, "message");
        Saveable.createIndex(Attachment.class, "createdAt");
        try {
            Files.createDirectories(dir());
            try (DirectoryStream<Path> leftovers = Files.newDirectoryStream(dir(), "*.part")) {
                for (Path part : leftovers) {
                    Files.deleteIfExists(part);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot prepare the attachment folder " + dir(), e);
        }
        BYTES.set(measure());
    }

    public static void startChecks() {
        Task.runTimerWithFixedDelay(() -> {
            sweepUnbound();
            enforceLimit();
            AdminService.forgetOldFailures();
        }, CHECK_EVERY_MS, CHECK_EVERY_MS);
    }

    /* Beside the database, which in the container lives on the /data volume; tests point it somewhere of their own. */
    public static Path dir() {
        String configured = System.getProperty("angatu.attachments");
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured);
        }
        Path db = Path.of(Saveable.databasePath()).toAbsolutePath();
        return db.getParent().resolve("attachments");
    }

    public static Path path(String id) {
        return dir().resolve(id);
    }

    public static Attachment upload(Session owner, String rawName, String declaredType, InputStream body) {
        AdminService.requireNotMuted(owner.id);
        if (pending(owner.id) >= MAX_PENDING_PER_PERSON) {
            throw new Refusal(429, "Há anexos demais esperando envio. Envie a mensagem ou espere alguns minutos.");
        }
        String id = NetHub.randomHex(16);
        Path part = dir().resolve(id + ".part");
        byte[] head = new byte[MediaInfo.HEAD_BYTES];
        int headLength = 0;
        long size = 0;
        boolean kept = false;
        try {
            try (OutputStream out = Files.newOutputStream(part, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = body.read(buffer)) > 0) {
                    if (headLength < head.length) {
                        int copy = Math.min(read, head.length - headLength);
                        System.arraycopy(buffer, 0, head, headLength, copy);
                        headLength += copy;
                    }
                    size += read;
                    if (size > MAX_FILE_BYTES) {
                        throw new Refusal(413, "O arquivo passa de 25 MB.");
                    }
                    out.write(buffer, 0, read);
                }
            }
            if (size == 0) {
                throw new Refusal("O arquivo está vazio.");
            }
            Media media = MediaInfo.sniff(head, headLength, declaredType);
            if (media.refusal() != null) {
                throw new Refusal(415, media.refusal());
            }
            Files.move(part, path(id), StandardCopyOption.ATOMIC_MOVE);
            Attachment attachment = new Attachment();
            attachment.setId(id);
            attachment.setOwner(owner.id);
            attachment.setName(name(rawName));
            attachment.setType(media.type());
            attachment.setSize(size);
            attachment.setWidth(media.width());
            attachment.setHeight(media.height());
            attachment.setCreatedAt(System.currentTimeMillis());
            attachment.save();
            kept = true;
            if (BYTES.addAndGet(size) > limit()) {
                Thread.startVirtualThread(AttachmentStore::enforceLimit);
            }
            return attachment;
        } catch (IOException e) {
            throw new Refusal("O envio do arquivo foi interrompido. Tente de novo.");
        } finally {
            if (!kept) {
                discard(part);
                discard(path(id));
            }
        }
    }

    private static String name(String raw) {
        String name = raw == null ? "" : raw.replaceAll("[\\p{Cntrl}\\u202A-\\u202E\\u2066-\\u2069/\\\\]", "_").strip();
        if (name.isEmpty()) {
            return "arquivo";
        }
        return name.length() > MAX_NAME ? name.substring(name.length() - MAX_NAME) : name;
    }

    private static long pending(String owner) {
        return Saveable.query(Count.class, "SELECT json_object('n',COUNT(*)) AS data FROM " + TABLE
                + " WHERE json_extract(data,'$.owner')=? AND json_extract(data,'$.message') IS NULL", owner).getFirst().n();
    }

    /* Inside the send transaction: only the uploader's own files that no message has taken yet. */
    static List<AttachmentRef> claim(List<String> ids, String owner) {
        Map<String, Attachment> found = new HashMap<>();
        for (Attachment attachment : rows(ids)) {
            found.put(attachment.getId(), attachment);
        }
        List<AttachmentRef> refs = new ArrayList<>(ids.size());
        for (String id : ids) {
            Attachment attachment = found.get(id);
            if (attachment == null || !owner.equals(attachment.getOwner()) || attachment.getMessage() != null) {
                throw new Refusal("Um dos anexos não está mais disponível. Envie o arquivo de novo.");
            }
            refs.add(ref(attachment));
        }
        return refs;
    }

    static void bind(List<String> ids, String messageId, String chat) {
        if (ids.isEmpty()) {
            return;
        }
        List<Attachment> rows = rows(ids);
        for (Attachment attachment : rows) {
            attachment.setMessage(messageId);
            attachment.setChat(chat);
        }
        Saveable.saveAll(rows);
    }

    private static List<Attachment> rows(Collection<String> ids) {
        List<String> valid = ids.stream().filter(id -> ID.matcher(id).matches()).toList();
        if (valid.isEmpty()) {
            return List.of();
        }
        return Saveable.query(Attachment.class, "SELECT data FROM " + TABLE + " WHERE id IN (" + MessageStore.placeholders(valid.size()) + ")",
                valid.toArray());
    }

    public static AttachmentRef ref(Attachment a) {
        return new AttachmentRef(a.getId(), a.getName(), a.getType(), a.getSize(), a.getWidth(), a.getHeight(), false);
    }

    public static Attachment find(String id) {
        return ID.matcher(id).matches() ? Saveable.findById(Attachment.class, id) : null;
    }

    static void deleteRows(Collection<String> ids) {
        List<String> valid = ids.stream().filter(id -> ID.matcher(id).matches()).toList();
        if (!valid.isEmpty()) {
            Saveable.query(Attachment.class, "DELETE FROM " + TABLE + " WHERE id IN (" + MessageStore.placeholders(valid.size()) + ")",
                    valid.toArray());
        }
    }

    static void deleteFiles(Collection<String> ids) {
        for (String id : ids) {
            if (ID.matcher(id).matches()) {
                deleteQuietly(path(id));
            }
        }
    }

    private static void deleteQuietly(Path file) {
        try {
            long size = Files.exists(file) ? Files.size(file) : 0;
            if (Files.deleteIfExists(file)) {
                BYTES.addAndGet(-size);
            }
        } catch (IOException e) {
            Console.error("Could not delete attachment file " + file.getFileName() + ": " + e.getMessage());
        }
    }

    private static void discard(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            Console.error("Could not discard an unfinished upload " + file.getFileName() + ": " + e.getMessage());
        }
    }

    /* Read and deleted one at a time inside a transaction, so a message taking the file at that moment wins the race. */
    static int sweepUnbound() {
        long cutoff = System.currentTimeMillis() - UNBOUND_TTL_MS;
        List<Attachment> stale = Saveable.query(Attachment.class, "SELECT data FROM " + TABLE + " WHERE json_extract(data,'$.message') IS NULL "
                + "AND json_extract(data,'$.createdAt')<? LIMIT 500", cutoff);
        int removed = 0;
        for (Attachment attachment : stale) {
            boolean deleted = Saveable.computeInTransaction(() -> {
                Attachment current = Saveable.findById(Attachment.class, attachment.getId());
                return current != null && current.getMessage() == null && Saveable.deleteById(Attachment.class, current.getId());
            });
            if (deleted) {
                deleteQuietly(path(attachment.getId()));
                removed++;
            }
        }
        if (removed > 0) {
            Console.log("Removed %d attachments no message took within an hour", removed);
        }
        return removed;
    }

    /* Past the ceiling the oldest files go first, until the total is back at the target; their messages keep a marker. */
    static boolean enforceLimit() {
        if (!ENFORCING.compareAndSet(false, true)) {
            return false;
        }
        try {
            long limit = limit();
            long total = measure();
            BYTES.set(total);
            if (total <= limit) {
                return false;
            }
            long before = total;
            long target = limit * TARGET_PERCENT / 100;
            int removed = 0;
            while (total > target) {
                List<Attachment> batch = Saveable.query(Attachment.class, "SELECT data FROM " + TABLE
                        + " ORDER BY json_extract(data,'$.createdAt') ASC LIMIT ?", BATCH);
                if (batch.isEmpty()) {
                    break;
                }
                for (Attachment attachment : batch) {
                    if (total <= target) {
                        break;
                    }
                    Saveable.deleteById(Attachment.class, attachment.getId());
                    deleteQuietly(path(attachment.getId()));
                    total -= attachment.getSize();
                    removed++;
                    if (attachment.getMessage() != null) {
                        expire(attachment.getMessage(), attachment.getId());
                    }
                }
            }
            BYTES.set(measure());
            Console.log("Attachment purge: %d -> %d bytes (limit %d), removed %d files", before, BYTES.get(), limit, removed);
            return true;
        } finally {
            ENFORCING.set(false);
        }
    }

    private static void expire(String messageId, String attachmentId) {
        StoredMessage updated = MessageStore.update(messageId, stored -> stored.setAttachments(stored.getAttachments().stream()
                .map(ref -> ref.id().equals(attachmentId) ? ref.asExpired() : ref).toList()));
        if (updated != null) {
            ChatService.broadcastUpdate(updated);
        }
    }

    public static long measure() {
        return Saveable.query(Count.class, "SELECT json_object('n',COALESCE(SUM(json_extract(data,'$.size')),0)) AS data FROM " + TABLE)
                .getFirst().n();
    }

    public static long bytes() {
        return BYTES.get();
    }

    public static long limit() {
        return MessageStore.setting("angatu.attachment.limit", "ATTACHMENT_STORAGE_LIMIT_BYTES", DEFAULT_LIMIT_BYTES, 1);
    }

    private record Count(long n) {
    }
}
