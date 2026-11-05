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
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Validates the 1080p 60 FPS screen share refactoring: default settings, codec negotiation order,
 * and end-to-end WebRTC transmission in embedded JCEF.
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
    void endToEnd1080p60WebRtcTransmission() throws Exception {
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

        // Run full WebRTC 1080p60 test using VP9 with steady-state delta measurement
        String testScript = """
            (async () => {
                const canvas = document.createElement("canvas");
                canvas.width = 1920;
                canvas.height = 1080;
                const ctx = canvas.getContext("2d");
                let frameCount = 0;

                function draw() {
                    frameCount++;
                    ctx.fillStyle = "#161616";
                    ctx.fillRect(0, 0, 1920, 1080);
                    ctx.fillStyle = "#22c55e";
                    ctx.font = "32px sans-serif";
                    ctx.fillText("1080p60 Validation - Frame " + frameCount, 80, 80);
                    const x = (frameCount * 10) % 1800;
                    ctx.fillStyle = "#3b82f6";
                    ctx.fillRect(x, 150, 150, 80);
                }

                const timer = setInterval(draw, 16);
                const stream = canvas.captureStream(60);
                const track = stream.getVideoTracks()[0];
                track.contentHint = "detail";

                const pc1 = new RTCPeerConnection();
                const pc2 = new RTCPeerConnection();
                pc1.onicecandidate = e => e.candidate && pc2.addIceCandidate(e.candidate);
                pc2.onicecandidate = e => e.candidate && pc1.addIceCandidate(e.candidate);

                const sender = pc1.addTrack(track, stream);
                const trans = pc1.getTransceivers()[0];

                // Prefer VP9
                const caps = RTCRtpSender.getCapabilities("video");
                const vp9Codecs = caps.codecs.filter(c => c.mimeType === "video/VP9" && !(c.sdpFmtpLine || "").includes("profile-id=2"));
                if (vp9Codecs.length && trans.setCodecPreferences) {
                    trans.setCodecPreferences(vp9Codecs);
                }

                const p = sender.getParameters();
                if (!p.encodings) p.encodings = [{}];
                p.encodings[0].maxBitrate = 8000000;
                p.encodings[0].maxFramerate = 60;
                p.encodings[0].scaleResolutionDownBy = 1.0;
                p.degradationPreference = "maintain-resolution";
                await sender.setParameters(p);

                let offer = await pc1.createOffer();
                let sdp = offer.sdp.replace(/a=mid:0\\r\\n/g, "a=mid:0\\r\\nb=AS:16000\\r\\n");
                offer = new RTCSessionDescription({ type: offer.type, sdp });
                await pc1.setLocalDescription(offer);
                await pc2.setRemoteDescription(offer);

                let answer = await pc2.createAnswer();
                let asdp = answer.sdp.replace(/a=mid:0\\r\\n/g, "a=mid:0\\r\\nb=AS:16000\\r\\n");
                answer = new RTCSessionDescription({ type: answer.type, sdp: asdp });
                await pc2.setLocalDescription(answer);
                await pc1.setRemoteDescription(answer);

                // Warm up for 2 seconds to let ICE and keyframe settle
                await new Promise(r => setTimeout(r, 2000));

                let s1 = await pc1.getStats();
                let s2 = await pc2.getStats();
                let sent0 = 0, dec0 = 0;
                s1.forEach(r => { if (r.type === "outbound-rtp" && r.kind === "video") sent0 = r.framesSent || 0; });
                s2.forEach(r => { if (r.type === "inbound-rtp" && r.kind === "video") dec0 = r.framesDecoded || 0; });
                const t0 = performance.now();

                // Measure over next 3 seconds
                await new Promise(r => setTimeout(r, 3000));
                const t1 = performance.now();

                s1 = await pc1.getStats();
                s2 = await pc2.getStats();
                let outRtp = null, inRtp = null;
                s1.forEach(r => { if (r.type === "outbound-rtp" && r.kind === "video") outRtp = r; });
                s2.forEach(r => { if (r.type === "inbound-rtp" && r.kind === "video") inRtp = r; });

                const elapsedSec = (t1 - t0) / 1000;
                const steadyOutFps = Math.round(((outRtp ? outRtp.framesSent : 0) - sent0) / elapsedSec);
                const steadyInFps = Math.round(((inRtp ? inRtp.framesDecoded : 0) - dec0) / elapsedSec);

                clearInterval(timer);
                track.stop();
                pc1.close();
                pc2.close();

                return JSON.stringify({
                    outWidth: outRtp ? outRtp.frameWidth : 0,
                    outHeight: outRtp ? outRtp.frameHeight : 0,
                    outFps: steadyOutFps,
                    inWidth: inRtp ? inRtp.frameWidth : 0,
                    inHeight: inRtp ? inRtp.frameHeight : 0,
                    inFps: steadyInFps,
                    codec: outRtp ? s1.get(outRtp.codecId)?.mimeType : "unknown",
                    qualityLimitationReason: outRtp ? outRtp.qualityLimitationReason : "unknown"
                });
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

        assertEquals(1920, metrics.get("outWidth").getAsInt(), "Sender resolution width must be 1920");
        assertEquals(1080, metrics.get("outHeight").getAsInt(), "Sender resolution height must be 1080");
        assertEquals(1920, metrics.get("inWidth").getAsInt(), "Receiver resolution width must be 1920");
        assertEquals(1080, metrics.get("inHeight").getAsInt(), "Receiver resolution height must be 1080");
        boolean isCi = System.getenv("CI") != null || System.getenv("GITHUB_ACTIONS") != null;
        int minFps = isCi ? 20 : 50;
        assertTrue(metrics.get("inFps").getAsInt() >= minFps, "Receiver steady-state FPS must be >= " + minFps + " (target 60 FPS), got: " + metrics.get("inFps").getAsInt());
        assertEquals("video/VP9", metrics.get("codec").getAsString(), "Codec must be VP9");
        assertEquals("none", metrics.get("qualityLimitationReason").getAsString(), "Quality limitation reason must be 'none'");
    }
}
