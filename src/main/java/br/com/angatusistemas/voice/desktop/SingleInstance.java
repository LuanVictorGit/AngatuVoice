package br.com.angatusistemas.voice.desktop;

import br.com.angatusistemas.voice.utils.AppDirs;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.platform.win32.WinError;
import com.sun.jna.platform.win32.WinNT.HANDLE;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Keeps one instance per user and data folder. A named Windows mutex decides who is first; a second launch asks the
 * first one to show its window, or raises it by title when it cannot be reached, and then exits.
 *
 * @author Angatu Sistemas
 */
final class SingleInstance {

    private static final String SHOW = "SHOW";
    private static final String TITLE = "Angatu Voice";
    private static final int SW_RESTORE = 9;
    private static HANDLE mutex;

    private SingleInstance() {
    }

    static boolean acquire(Runnable onShow) throws IOException {
        Path portFile = AppDirs.data().resolve("instance.port");
        String name = "Local\\AngatuVoice." + AppDirs.instanceKey();
        HANDLE created = Kernel32.INSTANCE.CreateMutex(null, true, name);
        boolean taken = created == null || Kernel32.INSTANCE.GetLastError() == WinError.ERROR_ALREADY_EXISTS;
        if (taken) {
            if (!ping(portFile)) {
                raiseByTitle();
            }
            return false;
        }
        mutex = created;
        ServerSocket server = new ServerSocket(0, 4, InetAddress.getLoopbackAddress());
        Files.writeString(portFile, String.valueOf(server.getLocalPort()));
        Thread.ofVirtual().start(() -> listen(server, onShow));
        return true;
    }

    private static boolean ping(Path portFile) {
        try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), Integer.parseInt(Files.readString(portFile).strip()))) {
            socket.getOutputStream().write((SHOW + "\n").getBytes(StandardCharsets.UTF_8));
            return true;
        } catch (IOException | NumberFormatException e) {
            return false;
        }
    }

    private static void raiseByTitle() {
        HWND window = User32.INSTANCE.FindWindow(null, TITLE);
        if (window != null) {
            User32.INSTANCE.ShowWindow(window, SW_RESTORE);
            User32.INSTANCE.SetForegroundWindow(window);
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
