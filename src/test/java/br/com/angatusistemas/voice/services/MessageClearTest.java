package br.com.angatusistemas.voice.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.angatusistemas.voice.entities.Message;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Clearing chat messages deletes all entries for the chosen chat without affecting other conversations.
 *
 * @author Angatu Sistemas
 */
class MessageClearTest {

    @BeforeAll
    static void setup() {
        MessageService.indexes();
    }

    @Test
    void clearRemovesOnlyTargetChatMessages() {
        String chatA = "dm:peer_123";
        String chatB = "dm:peer_456";

        Message m1 = new Message();
        m1.setId("m_1");
        m1.setChatId(chatA);
        m1.setSenderId("self");
        m1.setSenderName("Eu");
        m1.setText("Olá 1");
        m1.setSentAt(1000);
        MessageService.store(m1);

        Message m2 = new Message();
        m2.setId("m_2");
        m2.setChatId(chatA);
        m2.setSenderId("peer_123");
        m2.setSenderName("Amigo");
        m2.setText("Olá 2");
        m2.setSentAt(2000);
        MessageService.store(m2);

        Message m3 = new Message();
        m3.setId("m_3");
        m3.setChatId(chatB);
        m3.setSenderId("peer_456");
        m3.setSenderName("Outro");
        m3.setText("Mensagem no chat B");
        m3.setSentAt(3000);
        MessageService.store(m3);

        assertEquals(2, MessageService.history(chatA, Long.MAX_VALUE, 10).size());
        assertEquals(1, MessageService.history(chatB, Long.MAX_VALUE, 10).size());

        MessageService.clear(chatA);

        assertEquals(0, MessageService.history(chatA, Long.MAX_VALUE, 10).size());
        assertEquals(1, MessageService.history(chatB, Long.MAX_VALUE, 10).size());
    }

    @Test
    void overlayTransparentSetting() {
        assertFalse(SettingsService.flag("overlay.transparent"));

        SettingsService.update(Map.of("overlay.transparent", "true"));
        assertTrue(SettingsService.flag("overlay.transparent"));

        SettingsService.update(Map.of("overlay.transparent", "false"));
        assertFalse(SettingsService.flag("overlay.transparent"));
    }
}
