package br.com.angatusistemas.voice.services;

import static org.junit.jupiter.api.Assertions.assertThrows;

import br.com.angatusistemas.voice.utils.Crypto;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * Malformed invite codes are refused before anything touches the database.
 *
 * @author Angatu Sistemas
 */
class GroupInviteTest {

    private static String code(String json) {
        return "AV1." + Crypto.b64url(json.getBytes(StandardCharsets.UTF_8));
    }

    private static void refused(String code) {
        assertThrows(IllegalArgumentException.class, () -> GroupService.join(code));
    }

    @Test
    void refusesMalformedInvites() {
        String secret = Crypto.b64url(Crypto.random(16));
        refused(null);
        refused("");
        refused("AV2.abc");
        refused("AV1.%%%");
        refused("AV1." + "a".repeat(500));
        refused(code("not json"));
        refused(code("{\"id\":\"g_123\",\"name\":\"Teste\",\"secret\":\"" + secret + "\"}"));
        refused(code("{\"id\":\"g_0123456789abcdef\",\"name\":\" \",\"secret\":\"" + secret + "\"}"));
        refused(code("{\"id\":\"g_0123456789abcdef\",\"name\":\"" + "n".repeat(41) + "\",\"secret\":\"" + secret + "\"}"));
        refused(code("{\"id\":\"g_0123456789abcdef\",\"name\":\"Teste\",\"secret\":\""
                + Crypto.b64url(Crypto.random(8)) + "\"}"));
        refused(code("{\"id\":\"g_0123456789abcdef\",\"name\":\"Teste\"}"));
    }
}
