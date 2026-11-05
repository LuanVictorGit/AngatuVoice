package br.com.angatusistemas.voice.services;

import br.com.angatusistemas.lib.database.Saveable;
import br.com.angatusistemas.voice.entities.Setting;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * The preferences allow-list: every key the interface may read or write, its default and its validation.
 *
 * @author Angatu Sistemas
 */
public final class SettingsService {

    private static final Predicate<String> BOOL = v -> v.equals("true") || v.equals("false");
    private static final Pattern HOTKEY = Pattern.compile("|(?:(?:Ctrl|Alt|Shift|Meta)\\+){0,3}[A-Za-z0-9_]{1,12}");
    private static final Pattern DEVICE = Pattern.compile(".{0,200}");
    private static final Map<String, Rule> RULES = rules();
    private static final Set<String> PRIVATE = Set.of("update.token");
    private static final List<Consumer<Map<String, String>>> LISTENERS = new CopyOnWriteArrayList<>();

    private SettingsService() {
    }

    public static Map<String, String> all() {
        Map<String, String> out = new LinkedHashMap<>();
        RULES.forEach((key, rule) -> {
            if (!PRIVATE.contains(key)) {
                out.put(key, rule.defaultValue());
            }
        });
        for (Setting stored : Saveable.findAll(Setting.class)) {
            if (RULES.containsKey(stored.getId()) && !PRIVATE.contains(stored.getId())) {
                out.put(stored.getId(), stored.getValue());
            }
        }
        return out;
    }

    public static void listen(Consumer<Map<String, String>> listener) {
        LISTENERS.add(listener);
    }

    public static String get(String key) {
        Rule rule = RULES.get(key);
        if (rule == null) {
            throw new IllegalArgumentException("unknown setting " + key);
        }
        Setting stored = Saveable.findById(Setting.class, key);
        return stored == null ? rule.defaultValue() : stored.getValue();
    }

    public static boolean isStored(String key) {
        return Saveable.findById(Setting.class, key) != null;
    }

    public static boolean flag(String key) {
        return Boolean.parseBoolean(get(key));
    }

    public static int number(String key) {
        return Integer.parseInt(get(key));
    }

    public static Map<String, String> update(Map<String, String> changes) {
        for (Map.Entry<String, String> change : changes.entrySet()) {
            Rule rule = RULES.get(change.getKey());
            if (rule == null || change.getValue() == null || !rule.valid().test(change.getValue())) {
                throw new IllegalArgumentException("invalid setting " + change.getKey());
            }
        }
        List<Setting> rows = changes.entrySet().stream().map(c -> {
            Setting s = new Setting();
            s.setId(c.getKey());
            s.setValue(c.getValue());
            return s;
        }).toList();
        Saveable.saveAll(rows);
        LISTENERS.forEach(listener -> listener.accept(changes));
        return changes;
    }

    private static Predicate<String> range(int min, int max) {
        return v -> {
            try {
                int n = Integer.parseInt(v);
                return n >= min && n <= max;
            } catch (NumberFormatException e) {
                return false;
            }
        };
    }

    private static Predicate<String> oneOf(String... values) {
        Set<String> allowed = Set.of(values);
        return allowed::contains;
    }

    private static Map<String, Rule> rules() {
        Map<String, Rule> m = new LinkedHashMap<>();
        m.put("autostart", new Rule("true", BOOL));
        m.put("startMinimized", new Rule("false", BOOL));
        m.put("minimizeToTray", new Rule("false", BOOL));
        m.put("closeToTray", new Rule("true", BOOL));
        m.put("theme", new Rule("dark", oneOf("dark", "light")));
        m.put("notifications", new Rule("true", BOOL));
        m.put("privacy.shareAlias", new Rule("true", BOOL));
        m.put("privacy.preview", new Rule("true", BOOL));
        m.put("update.auto", new Rule("true", BOOL));
        m.put("update.channel", new Rule("stable", oneOf("stable", "beta")));
        m.put("update.intervalHours", new Rule("6", range(1, 168)));
        m.put("update.token", new Rule("", v -> v.length() <= 200));
        m.put("audio.input", new Rule("", DEVICE.asMatchPredicate()));
        m.put("audio.output", new Rule("", DEVICE.asMatchPredicate()));
        m.put("audio.inputVolume", new Rule("100", range(0, 200)));
        m.put("audio.outputVolume", new Rule("100", range(0, 200)));
        m.put("audio.sensitivity", new Rule("50", range(0, 100)));
        m.put("audio.ptt", new Rule("false", BOOL));
        m.put("audio.peerVolumes", new Rule("{}", v -> v.length() <= 4000 && v.startsWith("{")));
        m.put("audio.shareVolumes", new Rule("{}", v -> v.length() <= 4000 && v.startsWith("{")));
        m.put("audio.peerMutes", new Rule("[]", v -> v.length() <= 4000 && v.startsWith("[")));
        m.put("audio.echo", new Rule("true", BOOL));
        m.put("audio.noise", new Rule("true", BOOL));
        m.put("video.monitor", new Rule("0", range(0, 15)));
        m.put("video.resolution", new Rule("1080", oneOf("720", "1080", "1440", "native")));
        m.put("video.fps", new Rule("60", oneOf("30", "60")));
        m.put("video.priority", new Rule("detail", oneOf("detail", "motion")));
        m.put("video.bitrate", new Rule("0", range(0, 50000)));
        m.put("video.systemAudio", new Rule("false", BOOL));
        m.put("video.auto", new Rule("true", BOOL));
        m.put("hotkeys.ptt", new Rule("", HOTKEY.asMatchPredicate()));
        m.put("hotkeys.mic", new Rule("Ctrl+Shift+M", HOTKEY.asMatchPredicate()));
        m.put("hotkeys.deafen", new Rule("Ctrl+Shift+D", HOTKEY.asMatchPredicate()));
        m.put("hotkeys.call", new Rule("", HOTKEY.asMatchPredicate()));
        m.put("hotkeys.muteAll", new Rule("", HOTKEY.asMatchPredicate()));
        m.put("hotkeys.share", new Rule("", HOTKEY.asMatchPredicate()));
        m.put("hotkeys.show", new Rule("", HOTKEY.asMatchPredicate()));
        m.put("overlay.enabled", new Rule("true", BOOL));
        m.put("overlay.transparent", new Rule("false", BOOL));
        m.put("overlay.corner", new Rule("tr", oneOf("tl", "tr", "bl", "br")));
        m.put("sounds.enabled", new Rule("true", BOOL));
        m.put("sounds.volume", new Rule("60", range(0, 100)));
        m.put("sounds.ring", new Rule("true", BOOL));
        m.put("ui.lastChat", new Rule("radmin", v -> v.length() <= 80));
        m.put("ui.trayHintShown", new Rule("false", BOOL));
        return Collections.unmodifiableMap(m);
    }

    private record Rule(String defaultValue, Predicate<String> valid) {
    }
}
