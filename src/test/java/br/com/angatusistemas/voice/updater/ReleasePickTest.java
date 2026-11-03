package br.com.angatusistemas.voice.updater;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Which release each update channel is allowed to see.
 *
 * @author Angatu Sistemas
 */
class ReleasePickTest {

    private static final String RELEASES = """
            [{"tag_name":"v1.2.0-beta.1","draft":false,"prerelease":true,"assets":[]},
             {"tag_name":"v1.1.0","draft":false,"prerelease":false,"assets":[]},
             {"tag_name":"v2.0.0","draft":true,"prerelease":false,"assets":[]},
             {"tag_name":"nonsense","draft":false,"prerelease":false,"assets":[]},
             {"tag_name":"v1.0.5","draft":false,"prerelease":false,"assets":[]}]
            """;

    @Test
    void stableIgnoresBetaDraftAndGarbage() {
        assertEquals("v1.1.0", UpdateService.pickRelease(RELEASES, "stable").orElseThrow().tag());
    }

    @Test
    void betaTakesTheNewestPublishedIncludingPreReleases() {
        assertEquals("v1.2.0-beta.1", UpdateService.pickRelease(RELEASES, "beta").orElseThrow().tag());
    }

    @Test
    void noReleaseMeansNothingToInstall() {
        assertTrue(UpdateService.pickRelease("[]", "stable").isEmpty());
    }
}