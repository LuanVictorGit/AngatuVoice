package br.com.angatusistemas.voice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.angatusistemas.lib.AngatuLib;
import br.com.angatusistemas.lib.console.Console;
import br.com.angatusistemas.voice.desktop.AppShell;
import br.com.angatusistemas.voice.services.Migrations;
import br.com.angatusistemas.voice.services.SettingsService;
import br.com.angatusistemas.voice.utils.AppDirs;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Validates the 1080p 60 FPS screen share: default settings, and the path through the video card from end to end in embedded JCEF.
 *
 * @author Angatu Sistemas
 */
class ScreenShare1080p60Test {

    private static int port;
    private static HttpClient http;

    @BeforeAll
    static void setup() throws Exception {
        System.setProperty("angatu.radmin.ip", "127.0.0.98");
        System.setProperty("angatu.radmin.alias", "teste");
        System.setProperty("angatu.debug.port", "9343");
        System.setProperty("angatu.db", AppDirs.data().resolve("test-screenshare-3.db").toString());

        try (ServerSocket probe = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            port = probe.getLocalPort();
        }
        Main.configure(port);
        Migrations.run();
        Migrations.indexes();
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

    @Test
    void endToEndThroughTheVideoCard() throws Exception {
        AppShell.open(port);

        String cdpWsUrl = null;
        for (int i = 0; i < 50; i++) {
            Thread.sleep(500);
            try {
                HttpResponse<String> resp = http.send(
                        HttpRequest.newBuilder(URI.create("http://127.0.0.1:9343/json")).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() == 200) {
                    JsonArray targets = JsonParser.parseString(resp.body()).getAsJsonArray();
                    for (var t : targets) {
                        JsonObject obj = t.getAsJsonObject();
                        String url = obj.has("url") ? obj.get("url").getAsString() : "";
                        if ("page".equals(obj.get("type").getAsString()) && url.contains("/app") && obj.has("webSocketDebuggerUrl")) {
                            cdpWsUrl = obj.get("webSocketDebuggerUrl").getAsString();
                            break;
                        }
                    }
                    if (cdpWsUrl != null) break;
                }
            } catch (Exception ignored) {
            }
        }

        assertNotNull(cdpWsUrl, "Embedded Chromium must be accessible via CDP on port 9343 and loaded /app");

        Thread.sleep(1000);

        CompletableFuture<String> evalResult = new CompletableFuture<>();
        WebSocket ws = http.newWebSocketBuilder()
                .buildAsync(URI.create(cdpWsUrl), new WebSocket.Listener() {
                    StringBuilder buf = new StringBuilder();

                    @Override
                    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                        buf.append(data);
                        if (last) {
                            String msg = buf.toString();
                            buf.setLength(0);
                            if (msg.contains("\"id\":400")) {
                                evalResult.complete(msg);
                            }
                        }
                        return WebSocket.Listener.super.onText(webSocket, data, last);
                    }
                }).get(5, TimeUnit.SECONDS);

        // The real sender and receiver from hwvideo.js, over a real data channel, with a picture that keeps changing.
        String testScript = """
            (async () => {
                const support = await AV.hwVideo.support();
                if (!support.encode || !support.decode) return JSON.stringify({ skipped: true });
                const canvas = document.createElement("canvas");
                canvas.width = 1920;
                canvas.height = 1080;
                const ctx = canvas.getContext("2d");
                const noise = document.createElement("canvas");
                noise.width = 2320;
                noise.height = 1080;
                const nctx = noise.getContext("2d");
                const image = nctx.createImageData(2320, 1080);
                for (let i = 0; i < image.data.length; i += 4) {
                    const v = Math.random() * 90;
                    image.data[i] = v; image.data[i + 1] = v; image.data[i + 2] = v; image.data[i + 3] = 255;
                }
                nctx.putImageData(image, 0, 0);
                let n = 0;
                const timer = setInterval(() => { n++; ctx.drawImage(noise, -(n * 7) % 400, 0); }, 16);
                const track = canvas.captureStream(60).getVideoTracks()[0];

                const pc1 = new RTCPeerConnection();
                const pc2 = new RTCPeerConnection();
                pc1.onicecandidate = (e) => e.candidate && pc2.addIceCandidate(e.candidate);
                pc2.onicecandidate = (e) => e.candidate && pc1.addIceCandidate(e.candidate);
                const open = { negotiated: true, id: 8, ordered: false, maxPacketLifeTime: 150 };
                const out = pc1.createDataChannel("av-video", open);
                const inc = pc2.createDataChannel("av-video", open);
                out.binaryType = inc.binaryType = "arraybuffer";
                let failed = "";
                const sender = AV.hwVideo.createSender({ track, codec: support.encode, target: () => ({ height: 1080, fps: 60, bitrate: 8000000 }), onfail: () => { failed = "encoder"; } });
                const receiver = AV.hwVideo.createReceiver({ channel: inc, onfail: () => { failed = "decoder"; } });
                out.onmessage = (e) => { if (AV.hwVideo.isKeyRequest(e.data)) sender.requestKey(); };
                inc.onmessage = (e) => receiver.take(e.data);
                const offer = await pc1.createOffer();
                await pc1.setLocalDescription(offer);
                await pc2.setRemoteDescription(offer);
                const answer = await pc2.createAnswer();
                await pc2.setLocalDescription(answer);
                await pc1.setRemoteDescription(answer);
                await new Promise((r) => { out.onopen = r; if (out.readyState === "open") r(); });
                sender.add("viewer", out);

                const video = document.createElement("video");
                video.muted = true;
                video.srcObject = receiver.stream;
                video.play();

                await new Promise((r) => setTimeout(r, 3000));
                const before = receiver.counters.decoded;
                const t0 = performance.now();
                await new Promise((r) => setTimeout(r, 5000));
                const inFps = Math.round((receiver.counters.decoded - before) / ((performance.now() - t0) / 1000));
                const result = { inFps, width: receiver.counters.width, height: receiver.counters.height, shown: video.videoWidth, failed };
                clearInterval(timer);
                sender.stop();
                receiver.close();
                track.stop();
                pc1.close();
                pc2.close();
                return JSON.stringify(result);
            })()
        """;

        JsonObject req = new JsonObject();
        req.addProperty("id", 400);
        req.addProperty("method", "Runtime.evaluate");
        JsonObject params = new JsonObject();
        params.addProperty("expression", testScript);
        params.addProperty("returnByValue", true);
        params.addProperty("awaitPromise", true);
        req.add("params", params);

        ws.sendText(req.toString(), true);

        String resultJson = evalResult.get(30, TimeUnit.SECONDS);
        Console.log("TEST EVAL RESULT: %s", resultJson);

        JsonObject respObj = JsonParser.parseString(resultJson).getAsJsonObject();
        String innerJson = respObj.getAsJsonObject("result").getAsJsonObject("result").get("value").getAsString();
        JsonObject metrics = JsonParser.parseString(innerJson).getAsJsonObject();

        // A machine with no H.264 on its video card, like a CI runner, goes through WebRTC instead: nothing to measure here.
        Assumptions.assumeFalse(metrics.has("skipped"), "no H.264 video card on this machine");
        assertEquals("", metrics.get("failed").getAsString());
        assertEquals(1920, metrics.get("width").getAsInt(), "picture width");
        assertEquals(1080, metrics.get("height").getAsInt(), "picture height");
        assertEquals(1920, metrics.get("shown").getAsInt(), "the video element shows the decoded frames");
        assertTrue(metrics.get("inFps").getAsInt() >= 30, "steady frame rate through the video card, got " + metrics.get("inFps"));
    }
}
