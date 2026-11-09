package br.com.angatusistemas.voice.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The page (ui.js) and the native windows must give a person the same default picture: these values come from running the
 * page's own function in Node, and every one of the ten pictures must ship in both formats.
 *
 * @author Angatu Sistemas
 */
class DefaultAvatarTest {

    @Test
    void sameIndexAsThePage() {
        Map<String, Integer> fromPage = Map.of("0123456789abcdef0123", 3, "ffffffffffffffffffff", 1, "a1b2c3d4e5f60718293a", 0,
                "00000000000000000000", 7, "9f8e7d6c5b4a39281706", 3);
        fromPage.forEach((id, index) -> assertEquals(index, AvatarService.defaultIndex(id), id));
    }

    @Test
    void allTenPicturesShip() throws IOException {
        for (int i = 0; i < 10; i++) {
            for (String ext : new String[] {"svg", "png"}) {
                try (InputStream in = getClass().getResourceAsStream("/public/assets/avatars/avatar-" + i + "." + ext)) {
                    assertNotNull(in, "avatar-" + i + "." + ext);
                }
            }
        }
    }
}
