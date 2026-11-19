package br.com.angatusistemas.voicerelay.routes;

import br.com.angatusistemas.lib.javalin.routes.Route;
import br.com.angatusistemas.lib.javalin.routes.RouteType;
import br.com.angatusistemas.voicerelay.entities.Attachment;
import br.com.angatusistemas.voicerelay.services.AttachmentStore;
import br.com.angatusistemas.voicerelay.services.NetHub;
import br.com.angatusistemas.voicerelay.services.Session;
import br.com.angatusistemas.voicerelay.utils.MediaInfo;
import io.javalin.http.Context;
import io.javalin.http.NotFoundResponse;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * GET /api/files/{id}: an attachment, only for people who can read the chat it was sent to (or its uploader, before that).
 *
 * @author Angatu Sistemas
 */
public final class FileRoute extends Route {

    private static final Pattern RANGE = Pattern.compile("bytes=([0-9]{0,18})-([0-9]{0,18})");

    public FileRoute() {
        super("/api/files/{id}", RouteType.GET, FileRoute::handle);
    }

    private static void handle(Context ctx) {
        Session session = NetHub.httpSession(ctx);
        Attachment attachment = AttachmentStore.find(ctx.pathParam("id"));
        boolean allowed = attachment != null && (attachment.getChat() == null ? session.id().equals(attachment.getOwner())
                : session.canRead(attachment.getChat()));
        if (!allowed) {
            throw new NotFoundResponse("Anexo não encontrado.");
        }
        Path file = AttachmentStore.path(attachment.getId());
        try {
            long size = Files.size(file);
            String encoded = URLEncoder.encode(attachment.getName(), StandardCharsets.UTF_8).replace("+", "%20");
            String disposition = MediaInfo.inline(attachment.getType()) ? "inline" : "attachment";
            ctx.header("X-Content-Type-Options", "nosniff");
            ctx.header("Cache-Control", "no-store");
            // The proxy in front asks for gzip, and a compressed text body never matches the raw size: send it as it is on disk.
            ctx.disableCompression();
            ctx.header("Content-Disposition", disposition + "; filename*=UTF-8''" + encoded);
            ctx.header("Accept-Ranges", "bytes");
            ctx.contentType(attachment.getType());
            // A video player seeks by asking for a part of the file; without this it can only play from the start.
            long[] range = range(ctx.header("Range"), size);
            if (range == null) {
                ctx.header("Content-Length", Long.toString(size));
                ctx.result(Files.newInputStream(file));
                return;
            }
            long length = range[1] - range[0] + 1;
            ctx.status(206);
            ctx.header("Content-Range", "bytes " + range[0] + "-" + range[1] + "/" + size);
            ctx.header("Content-Length", Long.toString(length));
            InputStream in = Files.newInputStream(file);
            in.skipNBytes(range[0]);
            ctx.result(limited(in, length));
        } catch (NoSuchFileException e) {
            throw new NotFoundResponse("Este anexo expirou.");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /* One range of "bytes=start-end", "bytes=start-" or "bytes=-suffix"; anything else is answered with the whole file. */
    private static long[] range(String header, long size) {
        if (header == null || size == 0) {
            return null;
        }
        Matcher m = RANGE.matcher(header.trim());
        if (!m.matches() || m.group(1).isEmpty() && m.group(2).isEmpty()) {
            return null;
        }
        try {
            long start;
            long end;
            if (m.group(1).isEmpty()) {
                start = Math.max(0, size - Long.parseLong(m.group(2)));
                end = size - 1;
            } else {
                start = Long.parseLong(m.group(1));
                end = m.group(2).isEmpty() ? size - 1 : Math.min(Long.parseLong(m.group(2)), size - 1);
            }
            return start <= end && start < size ? new long[]{start, end} : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static InputStream limited(InputStream in, long length) {
        return new InputStream() {
            private long left = length;

            @Override
            public int read() throws IOException {
                if (left <= 0) {
                    return -1;
                }
                int b = in.read();
                if (b >= 0) {
                    left--;
                }
                return b;
            }

            @Override
            public int read(byte[] buffer, int offset, int count) throws IOException {
                if (left <= 0) {
                    return -1;
                }
                int n = in.read(buffer, offset, (int) Math.min(count, left));
                if (n > 0) {
                    left -= n;
                }
                return n;
            }

            @Override
            public void close() throws IOException {
                in.close();
            }
        };
    }
}
