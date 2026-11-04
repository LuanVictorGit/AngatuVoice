package br.com.angatusistemas.voice.services;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A TCP relay between two test nodes that can misbehave on demand: stall traffic with every socket left open, or cut
 * only one side of a connection, which is what a real network hiccup or half-open connection looks like to each end.
 *
 * @author Angatu Sistemas
 */
final class ChaosRelay implements AutoCloseable {

    private final ServerSocket server;
    private final InetSocketAddress target;
    private final String sourceIp;
    private final AtomicBoolean stalled = new AtomicBoolean();
    private final AtomicInteger accepted = new AtomicInteger();
    private final Set<Socket> callers = ConcurrentHashMap.newKeySet();
    private final Set<Socket> upstreams = ConcurrentHashMap.newKeySet();

    ChaosRelay(String listenIp, int listenPort, String targetIp, int targetPort, String sourceIp) throws IOException {
        this.server = new ServerSocket(listenPort, 50, InetAddress.getByName(listenIp));
        this.target = new InetSocketAddress(targetIp, targetPort);
        this.sourceIp = sourceIp;
        Thread.startVirtualThread(this::acceptLoop);
    }

    int connections() {
        return accepted.get();
    }

    void stall(boolean on) {
        stalled.set(on);
    }

    void cutCallerSideOnly() {
        callers.forEach(ChaosRelay::closeQuietly);
    }

    void cutTargetSideOnly() {
        upstreams.forEach(ChaosRelay::closeQuietly);
    }

    @Override
    public void close() throws IOException {
        server.close();
        callers.forEach(ChaosRelay::closeQuietly);
        upstreams.forEach(ChaosRelay::closeQuietly);
    }

    private void acceptLoop() {
        while (!server.isClosed()) {
            try {
                Socket caller = server.accept();
                accepted.incrementAndGet();
                Thread.startVirtualThread(() -> relay(caller));
            } catch (IOException e) {
                return;
            }
        }
    }

    private void relay(Socket caller) {
        Socket upstream = new Socket();
        try {
            upstream.bind(new InetSocketAddress(InetAddress.getByName(sourceIp), 0));
            upstream.connect(target, 2_000);
            callers.add(caller);
            upstreams.add(upstream);
            Thread.startVirtualThread(() -> pump(caller, upstream));
            pump(upstream, caller);
        } catch (IOException e) {
            closeQuietly(caller);
            closeQuietly(upstream);
        }
    }

    // A cut leaves the other socket open on purpose: that end must find out by itself, as it would on a real network.
    private void pump(Socket from, Socket to) {
        byte[] buffer = new byte[8192];
        try (InputStream in = from.getInputStream()) {
            OutputStream out = to.getOutputStream();
            for (int n = in.read(buffer); n >= 0; n = in.read(buffer)) {
                while (stalled.get()) {
                    Thread.sleep(50);
                }
                out.write(buffer, 0, n);
                out.flush();
            }
        } catch (IOException e) {
            closeQuietly(from);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
            // nothing left to do for a socket the test already gave up on
        }
    }
}
