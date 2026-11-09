package br.com.angatusistemas.voicerelay.services;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;

/**
 * Fetches a page or image from the open internet on behalf of a message without letting anyone point the server at its
 * own network. Every hop's host is resolved here, every address is checked, and the connection goes to that checked
 * address, so a name that resolves differently a moment later cannot slip past. Redirects are followed by hand.
 *
 * @author Angatu Sistemas
 */
public final class SafeFetcher {

    public static final String USER_AGENT = "AngatuVoiceBot/1.0 (+https://angatusistemas.com.br)";

    private static final int MAX_REDIRECTS = 3;
    private static final int MAX_HEADER_BYTES = 16 * 1024;
    private static final String TEST_LOOPBACK = "angatu.fetch.allowLoopback";

    private SafeFetcher() {
    }

    public static Fetched open(String url, String accept, long timeoutMs) throws IOException {
        long deadline = System.nanoTime() + timeoutMs * 1_000_000;
        URI uri = parse(url);
        for (int hop = 0; ; hop++) {
            InetAddress address = resolve(uri.getHost());
            boolean tls = "https".equalsIgnoreCase(uri.getScheme());
            int port = uri.getPort() == -1 ? (tls ? 443 : 80) : uri.getPort();
            if (port != 80 && port != 443 && !testMode()) {
                throw new Refused("port " + port + " is not allowed");
            }
            Socket socket = new Socket();
            boolean handedOver = false;
            try {
                socket.connect(new InetSocketAddress(address, port), remaining(deadline));
                Socket channel = tls ? secure(socket, uri.getHost(), port) : socket;
                channel.setSoTimeout(remaining(deadline));
                OutputStream out = channel.getOutputStream();
                out.write(request(uri, port, tls, accept).getBytes(StandardCharsets.ISO_8859_1));
                out.flush();
                InputStream in = new BufferedInputStream(channel.getInputStream());
                int status = status(readLine(in));
                Map<String, String> headers = headers(in);
                if (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) {
                    String location = headers.get("location");
                    if (location == null || hop >= MAX_REDIRECTS) {
                        throw new Refused("too many redirects");
                    }
                    uri = parse(resolveAgainst(uri, location));
                    continue;
                }
                InputStream body = "chunked".equalsIgnoreCase(headers.getOrDefault("transfer-encoding", "")) ? new Chunked(in) : in;
                long length = parseLength(headers.get("content-length"));
                handedOver = true;
                return new Fetched(status, headers.getOrDefault("content-type", ""), length,
                        new Guarded(body, length, deadline, channel), uri);
            } finally {
                if (!handedOver) {
                    socket.close();
                }
            }
        }
    }

    private static String resolveAgainst(URI base, String location) throws Refused {
        try {
            return base.resolve(new URI(location.strip())).toString();
        } catch (URISyntaxException | IllegalArgumentException e) {
            throw new Refused("bad redirect");
        }
    }

    private static URI parse(String url) throws Refused {
        try {
            URI uri = new URI(url);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if (!scheme.equals("http") && !scheme.equals("https") || uri.getHost() == null || uri.getRawUserInfo() != null) {
                throw new Refused("only plain http and https addresses");
            }
            return uri;
        } catch (URISyntaxException | IllegalArgumentException e) {
            throw new Refused("bad address");
        }
    }

    private static InetAddress resolve(String host) throws IOException {
        InetAddress[] all = InetAddress.getAllByName(host);
        for (InetAddress address : all) {
            if (!allowed(address)) {
                throw new Refused("address not public: " + host);
            }
        }
        return all[0];
    }

    public static boolean allowed(InetAddress address) {
        if (address.isLoopbackAddress()) {
            return testMode();
        }
        if (address.isAnyLocalAddress() || address.isLinkLocalAddress() || address.isSiteLocalAddress() || address.isMulticastAddress()) {
            return false;
        }
        byte[] b = address.getAddress();
        if (address instanceof Inet4Address) {
            return publicV4(b);
        }
        if (address instanceof Inet6Address) {
            if ((b[0] & 0xFE) == 0xFC) {
                return false;
            }
            boolean nat64 = (b[0] & 0xFF) == 0x00 && (b[1] & 0xFF) == 0x64 && (b[2] & 0xFF) == 0xFF && (b[3] & 0xFF) == 0x9B;
            boolean compatible = true;
            for (int i = 0; i < 12; i++) {
                compatible &= b[i] == 0;
            }
            if (nat64 || compatible) {
                return publicV4(new byte[] {b[12], b[13], b[14], b[15]});
            }
            return true;
        }
        return false;
    }

    private static boolean publicV4(byte[] b) {
        int first = b[0] & 0xFF;
        int second = b[1] & 0xFF;
        return !(first == 0 || first == 10 || first == 127 || first >= 224
                || first == 100 && second >= 64 && second <= 127
                || first == 169 && second == 254
                || first == 172 && second >= 16 && second <= 31
                || first == 192 && second == 168
                || first == 192 && second == 0 && (b[2] & 0xFF) == 0
                || first == 198 && (second == 18 || second == 19));
    }

    private static boolean testMode() {
        return Boolean.getBoolean(TEST_LOOPBACK);
    }

    private static Socket secure(Socket socket, String host, int port) throws IOException {
        SSLContext context;
        try {
            context = SSLContext.getDefault();
        } catch (NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
        SSLSocket ssl = (SSLSocket) context.getSocketFactory().createSocket(socket, host, port, true);
        SSLParameters parameters = ssl.getSSLParameters();
        parameters.setEndpointIdentificationAlgorithm("HTTPS");
        if (!host.startsWith("[") && !host.matches("[0-9.]+")) {
            parameters.setServerNames(List.of(new SNIHostName(host)));
        }
        ssl.setSSLParameters(parameters);
        ssl.startHandshake();
        return ssl;
    }

    private static String request(URI uri, int port, boolean tls, String accept) {
        String path = (uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath())
                + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery());
        String host = uri.getHost() + (port == (tls ? 443 : 80) ? "" : ":" + port);
        return "GET " + path + " HTTP/1.1\r\nHost: " + host + "\r\nUser-Agent: " + USER_AGENT + "\r\nAccept: " + accept
                + "\r\nAccept-Encoding: identity\r\nAccept-Language: pt-BR,pt;q=0.9,en;q=0.5\r\nConnection: close\r\n\r\n";
    }

    private static int status(String line) throws IOException {
        String[] parts = line.split(" ", 3);
        if (parts.length < 2 || !parts[0].startsWith("HTTP/1.")) {
            throw new IOException("not an HTTP/1 response");
        }
        try {
            return Integer.parseInt(parts[1]);
        } catch (NumberFormatException e) {
            throw new IOException("bad status line");
        }
    }

    private static Map<String, String> headers(InputStream in) throws IOException {
        Map<String, String> headers = new HashMap<>();
        int total = 0;
        while (true) {
            String line = readLine(in);
            total += line.length() + 2;
            if (total > MAX_HEADER_BYTES) {
                throw new IOException("headers too long");
            }
            if (line.isEmpty()) {
                return headers;
            }
            int colon = line.indexOf(':');
            if (colon > 0) {
                headers.put(line.substring(0, colon).strip().toLowerCase(Locale.ROOT), line.substring(colon + 1).strip());
            }
        }
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) != -1 && c != '\n') {
            if (line.size() > MAX_HEADER_BYTES) {
                throw new IOException("line too long");
            }
            if (c != '\r') {
                line.write(c);
            }
        }
        if (c == -1 && line.size() == 0) {
            throw new IOException("connection closed");
        }
        return line.toString(StandardCharsets.ISO_8859_1);
    }

    private static long parseLength(String value) {
        if (value == null || !value.strip().matches("\\d{1,18}")) {
            return -1;
        }
        return Long.parseLong(value.strip());
    }

    private static int remaining(long deadline) throws IOException {
        long ms = (deadline - System.nanoTime()) / 1_000_000;
        if (ms <= 0) {
            throw new IOException("timed out");
        }
        return (int) Math.min(ms, Integer.MAX_VALUE);
    }

    public record Fetched(int status, String contentType, long length, InputStream body, URI uri) implements AutoCloseable {

        public String mediaType() {
            return contentType.split(";", 2)[0].strip().toLowerCase(Locale.ROOT);
        }

        public String charset() {
            for (String part : contentType.split(";")) {
                String p = part.strip();
                if (p.toLowerCase(Locale.ROOT).startsWith("charset=")) {
                    return p.substring(8).replace("\"", "").strip();
                }
            }
            return null;
        }

        /* Reads at most max bytes; a body that goes on past that is cut there. */
        public byte[] readUpTo(int max) throws IOException {
            return body.readNBytes(max);
        }

        @Override
        public void close() throws IOException {
            body.close();
        }
    }

    // The address is not one the server may fetch.
    public static final class Refused extends IOException {

        Refused(String message) {
            super(message);
        }
    }

    /* Stops at the declared length, enforces the overall deadline, and closes the connection with the stream. */
    private static final class Guarded extends InputStream {

        private final InputStream in;
        private final long deadline;
        private final Socket socket;
        private long left;

        Guarded(InputStream in, long length, long deadline, Socket socket) {
            this.in = in;
            this.left = length < 0 ? Long.MAX_VALUE : length;
            this.deadline = deadline;
            this.socket = socket;
        }

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            return read(one, 0, 1) == -1 ? -1 : one[0] & 0xFF;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            if (left <= 0) {
                return -1;
            }
            socket.setSoTimeout(remaining(deadline));
            int read = in.read(buffer, offset, (int) Math.min(length, left));
            if (read > 0) {
                left -= read;
            }
            return read;
        }

        @Override
        public void close() throws IOException {
            socket.close();
        }
    }

    private static final class Chunked extends InputStream {

        private final InputStream in;
        private long chunk;
        private boolean done;

        Chunked(InputStream in) {
            this.in = in;
        }

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            return read(one, 0, 1) == -1 ? -1 : one[0] & 0xFF;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            if (done) {
                return -1;
            }
            if (chunk == 0) {
                String line = readLine(in);
                String size = line.split(";", 2)[0].strip();
                if (size.isEmpty() || !size.matches("[0-9a-fA-F]{1,12}")) {
                    throw new IOException("bad chunk");
                }
                chunk = Long.parseLong(size, 16);
                if (chunk == 0) {
                    done = true;
                    return -1;
                }
            }
            int read = in.read(buffer, offset, (int) Math.min(length, chunk));
            if (read == -1) {
                throw new IOException("chunk cut short");
            }
            chunk -= read;
            if (chunk == 0) {
                readLine(in);
            }
            return read;
        }
    }
}
