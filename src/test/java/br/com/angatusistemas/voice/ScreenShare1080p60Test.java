package br.com.angatusistemas.voice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.angatusistemas.lib.AngatuLib;
import br.com.angatusistemas.voice.services.Migrations;
import br.com.angatusistemas.voice.services.SettingsService;
import br.com.angatusistemas.voice.utils.AppDirs;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.http.HttpClient;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The settings a new installation starts a screen share with.
 *
 * @author Angatu Sistemas
 */
class ScreenShare1080p60Test {

    private static int port;
    private static HttpClient http;

    @BeforeAll
    static void setup() throws Exception {
        System.setProperty("angatu.db", AppDirs.data().resolve("test-screenshare-3.db").toString());

        try (ServerSocket probe = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            port = probe.getLocalPort();
        }
        Main.configure(port);
        Migrations.run();
        new AngatuLib("localhost", port, false);
        http = HttpClient.newHttpClient();
    }

    @Test
    void defaultSettingsTarget1080p60() {
        assertEquals("1080", SettingsService.get("video.resolution"));
        assertEquals("60", SettingsService.get("video.fps"));
        assertEquals("detail", SettingsService.get("video.priority"));
        assertEquals("0", SettingsService.get("video.bitrate"));
        assertTrue(SettingsService.flag("video.auto"));
    }
}
