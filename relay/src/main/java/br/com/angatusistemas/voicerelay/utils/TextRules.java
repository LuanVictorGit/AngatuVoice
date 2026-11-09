package br.com.angatusistemas.voicerelay.utils;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The rules every piece of text from an app goes through: what characters survive, how long it may be, and what the
 * server reads out of it (mentions and links).
 *
 * @author Angatu Sistemas
 */
public final class TextRules {

    public static final int MAX_TEXT = 4000;
    public static final int MAX_NAME = 32;
    public static final int MAX_MENTIONS = 20;
    public static final int MAX_LINKS = 3;

    private static final Pattern UNSAFE = Pattern.compile("[\\p{Cntrl}&&[^\\n\\t]]|[\\u202A-\\u202E\\u2066-\\u2069\\u200E\\u200F\\u061C]");
    private static final Pattern NAME_UNSAFE = Pattern.compile("[\\p{Cntrl}\\u202A-\\u202E\\u2066-\\u2069\\u200E\\u200F\\u061C]");
    private static final Pattern MENTION = Pattern.compile("<@([0-9a-f]{20})>");
    private static final Pattern LINK = Pattern.compile("https?://[^\\s<>\"]+", Pattern.CASE_INSENSITIVE);
    private static final String TRAILING = ".,;:!?)]}'*_~";

    private TextRules() {
    }

    public static String clean(String text) {
        if (text == null) {
            return "";
        }
        return UNSAFE.matcher(text.replace("\r\n", "\n").replace('\r', '\n')).replaceAll("").strip();
    }

    public static String clamp(String text) {
        return text.length() > MAX_TEXT ? text.substring(0, MAX_TEXT) : text;
    }

    public static String name(String name) {
        String stripped = NAME_UNSAFE.matcher(name == null ? "" : name).replaceAll("").strip();
        if (stripped.isEmpty()) {
            return "Sem nome";
        }
        return stripped.length() > MAX_NAME ? stripped.substring(0, MAX_NAME) : stripped;
    }

    public static List<String> mentions(String text) {
        Set<String> found = new LinkedHashSet<>();
        Matcher matcher = MENTION.matcher(text);
        while (matcher.find() && found.size() < MAX_MENTIONS) {
            found.add(matcher.group(1));
        }
        return List.copyOf(found);
    }

    /* A link written between angle brackets asks for no preview, the same convention chat apps already use. */
    public static List<String> links(String text) {
        Set<String> found = new LinkedHashSet<>();
        Matcher matcher = LINK.matcher(text);
        while (matcher.find() && found.size() < MAX_LINKS) {
            int start = matcher.start();
            String link = matcher.group();
            while (!link.isEmpty() && TRAILING.indexOf(link.charAt(link.length() - 1)) >= 0) {
                link = link.substring(0, link.length() - 1);
            }
            int end = start + link.length();
            boolean bracketed = start > 0 && text.charAt(start - 1) == '<' && end < text.length() && text.charAt(end) == '>';
            if (!bracketed && link.length() > "https://".length() && link.length() <= 2048) {
                found.add(link);
            }
        }
        return new ArrayList<>(found);
    }

    public static String string(JsonObject o, String key) {
        JsonElement el = o == null ? null : o.get(key);
        return el != null && el.isJsonPrimitive() ? el.getAsString() : "";
    }
}
