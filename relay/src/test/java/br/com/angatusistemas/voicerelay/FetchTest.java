package br.com.angatusistemas.voicerelay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.angatusistemas.voicerelay.entities.StoredMessage.Embed;
import br.com.angatusistemas.voicerelay.services.LinkPreview;
import br.com.angatusistemas.voicerelay.services.SafeFetcher;
import br.com.angatusistemas.voicerelay.utils.TextRules;
import com.sun.net.httpserver.HttpServer;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The guarded fetcher refuses every address that would point the server at itself or its network, and the preview parser
 * reads what a page says about itself.
 *
 * @author Angatu Sistemas
 */
class FetchTest {

    @Test
    void internalAddressesAndOddPortsAreRefused() throws Exception {
        System.clearProperty("angatu.fetch.allowLoopback");
        HttpServer local = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        local.start();
        try {
            int port = local.getAddress().getPort();
            for (String url : List.of("http://127.0.0.1:" + port + "/", "http://localhost/", "http://[::1]/", "http://10.0.0.1/",
                    "http://192.168.1.1/", "http://172.16.0.1/", "http://169.254.169.254/latest/meta-data", "http://100.64.0.1/",
                    "http://0.0.0.0/", "http://[fc00::1]/", "http://[fe80::1]/", "http://224.0.0.1/", "http://93.184.215.14:8080/",
                    "ftp://example.com/", "file:///etc/passwd", "http://user@93.184.215.14/")) {
                assertThrows(SafeFetcher.Refused.class, () -> SafeFetcher.open(url, "text/html", 2_000), url);
            }
        } finally {
            local.stop(0);
        }
        assertFalse(SafeFetcher.allowed(InetAddress.getByName("::ffff:10.0.0.1")));
        assertFalse(SafeFetcher.allowed(InetAddress.getByName("64:ff9b::a00:1")));
        assertTrue(SafeFetcher.allowed(InetAddress.getByName("93.184.215.14")));
        assertTrue(SafeFetcher.allowed(InetAddress.getByName("2606:4700::1111")));
    }

    @Test
    void aPageDescribesItselfThroughItsTags() {
        Embed embed = LinkPreview.parse("<head><title>Só o título</title><meta name=\"description\" content=\"Resumo\"></head>",
                URI.create("https://exemplo.com.br/a"), "https://exemplo.com.br/a");
        assertEquals("Só o título", embed.title());
        assertEquals("Resumo", embed.description());
        assertNull(embed.imageUrl());
        Embed og = LinkPreview.parse("<meta content=\"Primeiro\" property=\"og:title\"><meta property=\"og:image\" content=\"javascript:alert(1)\">"
                + "<meta name=\"theme-color\" content=\"red;x\">", URI.create("https://exemplo.com.br/"), "https://exemplo.com.br/");
        assertEquals("Primeiro", og.title());
        assertNull(og.imageUrl(), "only web images");
        assertNull(og.color(), "only hex colors");
        assertNull(LinkPreview.parse("<html><body>nada</body></html>", URI.create("https://exemplo.com.br/"), "https://exemplo.com.br/"));
        assertEquals(256, LinkPreview.parse("<meta property=\"og:title\" content=\"" + "a".repeat(400) + "\">",
                URI.create("https://exemplo.com.br/"), "https://exemplo.com.br/").title().length());
    }

    @Test
    void linksBetweenAngleBracketsAreLeftAlone() {
        assertEquals(List.of("https://a.com/x", "http://b.com"), TextRules.links("veja https://a.com/x, e http://b.com. <https://c.com>"));
        assertEquals(3, TextRules.links("https://1.com https://2.com https://3.com https://4.com").size());
        assertEquals(List.of("0123456789abcdef0123"), TextRules.mentions("oi <@0123456789abcdef0123> <@0123456789abcdef0123> <@xyz>"));
    }
}
