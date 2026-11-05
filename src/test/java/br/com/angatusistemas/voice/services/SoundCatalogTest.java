package br.com.angatusistemas.voice.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Keeps the sound names used by the interface, the catalogue in ui.js and the files on disk in agreement: a name without a file plays nothing and raises no error.
 *
 * @author Angatu Sistemas
 */
class SoundCatalogTest {
    private static final Path PUBLIC = Path.of("src/main/resources/public");
    private static final Path SCRIPTS = PUBLIC.resolve("scripts");
    private static final Pattern CATALOGUE_ENTRY = Pattern.compile("(\\w+): \"(/assets/audio/[^\"]+\\.mp3)\"");
    private static final Pattern PLAY_CALL = Pattern.compile("AV\\.sound\\(([^;\\n]*?)\\);");
    private static final Pattern QUOTED = Pattern.compile("\"(\\w+)\"");
    private static final Pattern TEST_ENTRY = Pattern.compile("\"sound:(\\w+)\"");
    private static final Pattern LEAVE_CUE = Pattern.compile("rtc\\.leave\\(\"(\\w+)\"\\)");

    private static String script(String name) throws IOException {
        return Files.readString(SCRIPTS.resolve(name));
    }

    private static Set<String> matches(Pattern pattern, String text) {
        Set<String> found = new TreeSet<>();
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) found.add(matcher.group(1));
        return found;
    }

    private static Set<String> catalogue() throws IOException {
        String ui = script("ui.js");
        return matches(CATALOGUE_ENTRY, ui.substring(ui.indexOf("const SOUND_FILES")));
    }

    @Test
    void everyPlayedNameIsInTheCatalogue() throws IOException {
        Set<String> played = new TreeSet<>(matches(LEAVE_CUE, script("rtc.js")));
        for (String file : List.of("ui.js", "app.js", "rtc.js", "settings.js")) {
            Matcher call = PLAY_CALL.matcher(script(file));
            while (call.find()) played.addAll(matches(QUOTED, call.group(1)));
        }
        assertTrue(played.size() > 10, "the search stopped finding the calls: " + played);
        played.removeAll(catalogue());
        assertEquals(Set.of(), played, "played but not in the catalogue of ui.js");
    }

    @Test
    void everyCatalogueEntryCanBeTestedInTheSettings() throws IOException {
        assertEquals(catalogue(), matches(TEST_ENTRY, script("settings.js")));
    }

    @Test
    void catalogueAndFilesMatchExactly() throws IOException {
        Set<String> referenced = new TreeSet<>();
        Matcher matcher = Pattern.compile("\"/assets/(audio/[^\"]+\\.mp3)\"").matcher(script("ui.js"));
        while (matcher.find()) referenced.add(matcher.group(1));
        Path assets = PUBLIC.resolve("assets");
        Set<String> onDisk = new TreeSet<>();
        try (Stream<Path> files = Files.walk(assets.resolve("audio"))) {
            files.filter(p -> p.toString().endsWith(".mp3")).forEach(p -> onDisk.add(assets.relativize(p).toString().replace('\\', '/')));
        }
        assertEquals(onDisk, referenced);
    }
}
