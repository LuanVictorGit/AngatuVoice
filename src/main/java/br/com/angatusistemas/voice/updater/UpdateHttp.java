package br.com.angatusistemas.voice.updater;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/**
 * HTTPS client for the updater: redirects are followed by hand so every hop stays on an allowed host, and the access
 * token is only ever sent to the API host. The JDK client is used because ranged, streamed downloads need it.
 *
 * @author Angatu Sistemas
 */
final class UpdateHttp {

    private static final int MAX_HOPS = 5;
    private static final String TOKEN_HOST = "api.github.com";
    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(15)).build();

    private UpdateHttp() {
    }

    static HttpResponse<InputStream> get(String url, String token, Map<String, String> headers) throws IOException {
        URI uri = URI.create(url);
        for (int hop = 0; hop <= MAX_HOPS; hop++) {
            check(uri);
            HttpRequest.Builder request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(60))
                    .header("User-Agent", "AngatuVoice-Updater");
            headers.forEach(request::header);
            if (token != null && !token.isBlank() && TOKEN_HOST.equalsIgnoreCase(uri.getHost())) {
                request.header("Authorization", "Bearer " + token);
            }
            HttpResponse<InputStream> response;
            try {
                response = CLIENT.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted", e);
            }
            int status = response.statusCode();
            if (status >= 300 && status < 400 && status != 304) {
                String location = response.headers().firstValue("Location")
                        .orElseThrow(() -> new IOException("redirect without location"));
                response.body().close();
                uri = uri.resolve(location);
                continue;
            }
            return response;
        }
        throw new IOException("too many redirects");
    }

    static byte[] getBytes(String url, String token, Map<String, String> headers, int maxBytes) throws IOException {
        HttpResponse<InputStream> response = get(url, token, headers);
        try (InputStream body = response.body()) {
            if (response.statusCode() != 200) {
                throw new IOException("unexpected status " + response.statusCode());
            }
            byte[] data = body.readNBytes(maxBytes + 1);
            if (data.length > maxBytes) {
                throw new IOException("response too large");
            }
            return data;
        }
    }

    private static void check(URI uri) throws IOException {
        if (!"https".equals(uri.getScheme()) || uri.getHost() == null
                || !UpdateConfig.allowedHosts().contains(uri.getHost().toLowerCase())) {
            throw new IOException("host not allowed: " + uri.getHost());
        }
    }
}
