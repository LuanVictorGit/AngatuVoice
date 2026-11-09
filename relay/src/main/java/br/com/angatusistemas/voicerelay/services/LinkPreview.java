package br.com.angatusistemas.voicerelay.services;

import br.com.angatusistemas.lib.console.Console;
import br.com.angatusistemas.voicerelay.entities.StoredMessage;
import br.com.angatusistemas.voicerelay.entities.StoredMessage.Embed;
import br.com.angatusistemas.voicerelay.utils.TextRules;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.Charset;
import java.nio.charset.IllegalCharsetNameException;
import java.nio.charset.StandardCharsets;
import java.nio.charset.UnsupportedCharsetException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Link previews: after a message is stored or edited, up to three of its links are fetched through the guarded fetcher and
 * their Open Graph tags become the message's embeds. It never delays sending, and only a few fetches run at once.
 *
 * @author Angatu Sistemas
 */
public final class LinkPreview {

    public static final long TIMEOUT_MS = 5_000;
    public static final int MAX_HTML_BYTES = 1024 * 1024;

    private static final Semaphore SLOTS = new Semaphore(4);
    private static final Pattern META = Pattern.compile("<meta\\s([^>]*)>", Pattern.CASE_INSENSITIVE);
    private static final Pattern ATTRIBUTE = Pattern.compile("([a-zA-Z:_-]+)\\s*=\\s*(\"([^\"]*)\"|'([^']*)'|([^\\s\"'>]+))");
    private static final Pattern TITLE = Pattern.compile("<title[^>]*>(.*?)</title>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern HEAD_END = Pattern.compile("</head\\s*>", Pattern.CASE_INSENSITIVE);
    private static final Pattern ENTITY = Pattern.compile("&(#x[0-9a-fA-F]{1,6}|#[0-9]{1,7}|[a-zA-Z]{2,8});");
    // The named entities Portuguese pages still write by hand; anything else is left as it came.
    private static final Map<String, String> NAMED = Map.ofEntries(Map.entry("amp", "&"), Map.entry("lt", "<"), Map.entry("gt", ">"),
            Map.entry("quot", "\""), Map.entry("apos", "'"), Map.entry("nbsp", " "), Map.entry("aacute", "á"), Map.entry("eacute", "é"),
            Map.entry("iacute", "í"), Map.entry("oacute", "ó"), Map.entry("uacute", "ú"), Map.entry("agrave", "à"), Map.entry("acirc", "â"),
            Map.entry("ecirc", "ê"), Map.entry("ocirc", "ô"), Map.entry("atilde", "ã"), Map.entry("otilde", "õ"), Map.entry("ccedil", "ç"),
            Map.entry("Aacute", "Á"), Map.entry("Eacute", "É"), Map.entry("Iacute", "Í"), Map.entry("Oacute", "Ó"), Map.entry("Uacute", "Ú"),
            Map.entry("Atilde", "Ã"), Map.entry("Otilde", "Õ"), Map.entry("Ccedil", "Ç"), Map.entry("hellip", "…"), Map.entry("ndash", "–"),
            Map.entry("mdash", "—"), Map.entry("laquo", "«"), Map.entry("raquo", "»"), Map.entry("copy", "©"));
    private static final Pattern COLOR = Pattern.compile("#([0-9a-fA-F]{3}|[0-9a-fA-F]{6}|[0-9a-fA-F]{8})");
    private static final Pattern SPACES = Pattern.compile("\\s+");

    private LinkPreview() {
    }

    public static void schedule(StoredMessage message) {
        List<String> links = TextRules.links(message.getText() == null ? "" : message.getText());
        if (links.isEmpty()) {
            return;
        }
        String id = message.getId();
        long edited = message.getEdited();
        Thread.startVirtualThread(() -> unfurl(id, edited, links));
    }

    private static void unfurl(String id, long edited, List<String> links) {
        List<Embed> embeds = new ArrayList<>();
        for (String link : links) {
            Embed embed = fetch(link);
            if (embed != null) {
                embeds.add(embed);
            }
        }
        if (embeds.isEmpty()) {
            return;
        }
        boolean[] applied = new boolean[1];
        StoredMessage updated = MessageStore.update(id, stored -> {
            if (stored.getEdited() == edited) {
                stored.setEmbeds(embeds);
                applied[0] = true;
            }
        });
        if (updated != null && applied[0]) {
            ChatService.broadcastUpdate(updated);
        }
    }

    static Embed fetch(String link) {
        try {
            SLOTS.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
        try (SafeFetcher.Fetched page = SafeFetcher.open(link, "text/html,application/xhtml+xml;q=0.9", TIMEOUT_MS)) {
            if (page.status() != 200 || !page.mediaType().equals("text/html") && !page.mediaType().equals("application/xhtml+xml")) {
                return null;
            }
            byte[] html = page.readUpTo(MAX_HTML_BYTES);
            return parse(new String(html, charset(page.charset())), page.uri(), link);
        } catch (IOException e) {
            Console.log("No link preview for %s: %s", host(link), e.getMessage());
            return null;
        } finally {
            SLOTS.release();
        }
    }

    private static String host(String link) {
        try {
            return new URI(link).getHost();
        } catch (URISyntaxException e) {
            return "an invalid address";
        }
    }

    private static Charset charset(String name) {
        if (name == null) {
            return StandardCharsets.UTF_8;
        }
        try {
            return Charset.forName(name);
        } catch (IllegalCharsetNameException | UnsupportedCharsetException e) {
            return StandardCharsets.UTF_8;
        }
    }

    public static Embed parse(String html, URI page, String link) {
        Matcher headEnd = HEAD_END.matcher(html);
        String head = headEnd.find() ? html.substring(0, headEnd.start()) : html;
        Map<String, String> meta = new HashMap<>();
        Matcher tags = META.matcher(head);
        while (tags.find()) {
            Map<String, String> attributes = new HashMap<>();
            Matcher attribute = ATTRIBUTE.matcher(tags.group(1));
            while (attribute.find()) {
                String value = attribute.group(3) != null ? attribute.group(3) : attribute.group(4) != null ? attribute.group(4) : attribute.group(5);
                attributes.putIfAbsent(attribute.group(1).toLowerCase(Locale.ROOT), value);
            }
            String key = attributes.getOrDefault("property", attributes.get("name"));
            String content = attributes.get("content");
            if (key != null && content != null) {
                meta.putIfAbsent(key.toLowerCase(Locale.ROOT), decode(content));
            }
        }
        String title = meta.get("og:title");
        if (title == null) {
            Matcher titleTag = TITLE.matcher(head);
            title = titleTag.find() ? decode(titleTag.group(1)) : null;
        }
        String description = meta.getOrDefault("og:description", meta.get("description"));
        title = text(title, 256);
        description = text(description, 1000);
        if (title == null && description == null) {
            return null;
        }
        String color = meta.get("theme-color");
        color = color != null && COLOR.matcher(color.strip()).matches() ? color.strip() : null;
        String image = image(page, meta.getOrDefault("og:image", meta.getOrDefault("og:image:url", meta.get("og:image:secure_url"))));
        return new Embed(link, title, description, text(meta.get("og:site_name"), 100), color, image,
                image == null ? 0 : dimension(meta.get("og:image:width")), image == null ? 0 : dimension(meta.get("og:image:height")));
    }

    private static String image(URI page, String raw) {
        if (raw == null || raw.isBlank() || raw.length() > 2048) {
            return null;
        }
        try {
            URI resolved = page.resolve(new URI(raw.strip()));
            String scheme = resolved.getScheme() == null ? "" : resolved.getScheme().toLowerCase(Locale.ROOT);
            return (scheme.equals("http") || scheme.equals("https")) && resolved.getHost() != null ? resolved.toString() : null;
        } catch (URISyntaxException | IllegalArgumentException e) {
            return null;
        }
    }

    private static int dimension(String value) {
        if (value == null || !value.strip().matches("\\d{1,5}")) {
            return 0;
        }
        return Integer.parseInt(value.strip());
    }

    private static String text(String value, int max) {
        if (value == null) {
            return null;
        }
        String clean = SPACES.matcher(TextRules.clean(value)).replaceAll(" ").strip();
        if (clean.isEmpty()) {
            return null;
        }
        return clean.length() > max ? clean.substring(0, max) : clean;
    }

    private static String decode(String value) {
        Matcher entity = ENTITY.matcher(value);
        StringBuilder out = new StringBuilder(value.length());
        while (entity.find()) {
            String name = entity.group(1);
            String replacement;
            if (name.startsWith("#")) {
                int code = name.startsWith("#x") ? Integer.parseInt(name.substring(2), 16) : Integer.parseInt(name.substring(1));
                replacement = Character.isValidCodePoint(code) ? Character.toString(code) : "";
            } else {
                replacement = NAMED.getOrDefault(name, entity.group());
            }
            entity.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        entity.appendTail(out);
        return out.toString();
    }
}
