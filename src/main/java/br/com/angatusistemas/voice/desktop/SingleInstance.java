package br.com.angatusistemas.voice.desktop;

import br.com.angatusistemas.voice.utils.AppDirs;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Keeps one instance per user: a second launch asks the first one to show its window and exits.
 *
 * @author Angatu Sistemas
 */
final class SingleInstance {

    private static final String SHOW = "SHOW";
    private static FileChannel channel;
    private static FileLock lock;

    private SingleInstance() {
    }

    static boolean acquire(Runnable onShow) throws IOException {
        Path lockFile = AppDirs.data().resolve("instance.lock");
        Path portFile = AppDirs.data().resolve("instance.port");
        channel = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        lock = channel.tryLock();
        if (lock == null) {
            ping(portFile);
            return false;
        }
        ServerSocket server = new ServerSocket(0, 4, InetAddress.getLoopbackAddress());
        Files.writeString(portFile, String.valueOf(server.getLocalPort()));
        Thread.ofVirtual().start(() -> listen(server, onShow));
        return true;
    }

    private static void ping(Path portFile) {
        try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), Integer.parseInt(Files.readString(portFile).strip()))) {
            socket.getOutputStream().write((SHOW + "\n").getBytes(StandardCharsets.UTF_8));
        } catch (IOException | NumberFormatException e) {
            // the first instance is still starting; the user simply sees nothing happen once
        }
    }

    private static void listen(ServerSocket server, Runnable onShow) {
        while (!server.isClosed()) {
            try (Socket socket = server.accept();
                 BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))) {
                socket.setSoTimeout(2000);
                if (SHOW.equals(in.readLine())) {
                    onShow.run();
                }
            } catch (IOException e) {
                if (server.isClosed()) {
                    return;
                }
            }
        }
    }
}
