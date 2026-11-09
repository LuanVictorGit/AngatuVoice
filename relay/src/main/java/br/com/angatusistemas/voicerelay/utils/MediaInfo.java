package br.com.angatusistemas.voicerelay.utils;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * What an uploaded file really is, read from its first bytes rather than from what the app claims, and the size of an
 * image read from its own header. Only WebP and GIF images are accepted: the app converts every other image to WebP.
 *
 * @author Angatu Sistemas
 */
public final class MediaInfo {

    public static final int HEAD_BYTES = 64;

    private static final Pattern SAFE_TYPE = Pattern.compile("(application|text|font|model)/[a-z0-9.+-]{1,80}");

    private MediaInfo() {
    }

    public static Media sniff(byte[] head, int length, String declared) {
        if (startsWith(head, length, 0, 0x89, 'P', 'N', 'G')) {
            return Media.refused("Imagens PNG não são aceitas. Atualize o app para enviar a imagem convertida.");
        }
        if (startsWith(head, length, 0, 0xFF, 0xD8, 0xFF)) {
            return Media.refused("Imagens JPEG não são aceitas. Atualize o app para enviar a imagem convertida.");
        }
        if (isBmp(head, length)) {
            return Media.refused("Imagens BMP não são aceitas. Atualize o app para enviar a imagem convertida.");
        }
        if (startsWith(head, length, 0, 'R', 'I', 'F', 'F') && startsWith(head, length, 8, 'W', 'E', 'B', 'P')) {
            return webp(head, length);
        }
        if (startsWith(head, length, 0, 'G', 'I', 'F', '8') && length >= 10) {
            return new Media("image/gif", le16(head, 6), le16(head, 8), null);
        }
        String claimed = declared == null ? "" : declared.toLowerCase(Locale.ROOT).split(";", 2)[0].strip();
        String media = mediaType(head, length, claimed.startsWith("audio/"));
        if (media != null) {
            return new Media(media, 0, 0, null);
        }
        boolean safe = SAFE_TYPE.matcher(claimed).matches() && !claimed.contains("html") && !claimed.contains("xml")
                && !claimed.contains("javascript");
        return new Media(safe ? claimed : "application/octet-stream", 0, 0, null);
    }

    private static Media webp(byte[] h, int length) {
        if (length < 30) {
            return Media.refused("A imagem WebP está corrompida.");
        }
        int width;
        int height;
        if (startsWith(h, length, 12, 'V', 'P', '8', ' ')) {
            if (!startsWith(h, length, 23, 0x9D, 0x01, 0x2A)) {
                return Media.refused("A imagem WebP está corrompida.");
            }
            width = le16(h, 26) & 0x3FFF;
            height = le16(h, 28) & 0x3FFF;
        } else if (startsWith(h, length, 12, 'V', 'P', '8', 'L')) {
            if ((h[20] & 0xFF) != 0x2F) {
                return Media.refused("A imagem WebP está corrompida.");
            }
            int b0 = h[21] & 0xFF;
            int b1 = h[22] & 0xFF;
            int b2 = h[23] & 0xFF;
            int b3 = h[24] & 0xFF;
            width = 1 + (((b1 & 0x3F) << 8) | b0);
            height = 1 + (((b3 & 0x0F) << 10) | (b2 << 2) | ((b1 & 0xC0) >> 6));
        } else if (startsWith(h, length, 12, 'V', 'P', '8', 'X')) {
            width = 1 + le24(h, 24);
            height = 1 + le24(h, 27);
        } else {
            return Media.refused("A imagem WebP está corrompida.");
        }
        if (width <= 0 || height <= 0) {
            return Media.refused("A imagem WebP está corrompida.");
        }
        if (width > 2560 || height > 2560) {
            return Media.refused("A imagem passa de 2.560 pixels. Atualize o app para enviá-la reduzida.");
        }
        return new Media("image/webp", width, height, null);
    }

    /* WebM and MP4 hold sound or picture alike: a recording the app declares as audio stays audio, everything else is video. */
    private static String mediaType(byte[] h, int length, boolean audio) {
        if (startsWith(h, length, 4, 'f', 't', 'y', 'p')) {
            return audio || startsWith(h, length, 8, 'M', '4', 'A') ? "audio/mp4" : "video/mp4";
        }
        if (startsWith(h, length, 0, 0x1A, 0x45, 0xDF, 0xA3)) {
            return audio ? "audio/webm" : "video/webm";
        }
        if (startsWith(h, length, 0, 'O', 'g', 'g', 'S')) {
            return "audio/ogg";
        }
        if (startsWith(h, length, 0, 'R', 'I', 'F', 'F') && startsWith(h, length, 8, 'W', 'A', 'V', 'E')) {
            return "audio/wav";
        }
        if (startsWith(h, length, 0, 'f', 'L', 'a', 'C')) {
            return "audio/flac";
        }
        if (startsWith(h, length, 0, 'I', 'D', '3') || (length >= 2 && (h[0] & 0xFF) == 0xFF && (h[1] & 0xE6) == 0xE2)) {
            return "audio/mpeg";
        }
        if (startsWith(h, length, 0, '%', 'P', 'D', 'F')) {
            return "application/pdf";
        }
        return null;
    }

    /* "BM" alone starts plenty of text files: a real bitmap also has its reserved header bytes at zero. */
    private static boolean isBmp(byte[] h, int length) {
        return length >= 14 && h[0] == 'B' && h[1] == 'M' && h[6] == 0 && h[7] == 0 && h[8] == 0 && h[9] == 0;
    }

    private static boolean startsWith(byte[] h, int length, int offset, int... expected) {
        if (length < offset + expected.length) {
            return false;
        }
        for (int i = 0; i < expected.length; i++) {
            if ((h[offset + i] & 0xFF) != expected[i]) {
                return false;
            }
        }
        return true;
    }

    private static int le16(byte[] h, int offset) {
        return (h[offset] & 0xFF) | (h[offset + 1] & 0xFF) << 8;
    }

    private static int le24(byte[] h, int offset) {
        return (h[offset] & 0xFF) | (h[offset + 1] & 0xFF) << 8 | (h[offset + 2] & 0xFF) << 16;
    }

    public static boolean inline(String type) {
        return type.startsWith("image/") || type.startsWith("video/") || type.startsWith("audio/");
    }

    public record Media(String type, int width, int height, String refusal) {

        static Media refused(String reason) {
            return new Media(null, 0, 0, reason);
        }
    }
}
