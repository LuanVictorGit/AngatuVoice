package br.com.angatusistemas.voice.desktop;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.platform.win32.WinDef.LPARAM;
import com.sun.jna.platform.win32.WinDef.RECT;
import com.sun.jna.platform.win32.WinDef.WPARAM;
import com.sun.jna.platform.win32.WinUser;
import com.sun.jna.ptr.IntByReference;
import java.awt.GraphicsEnvironment;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Background watcher that monitors and automatically dismisses stuck notification toasts from Radmin VPN
 * (e.g. CToastContainer / Qt tooltip popups) so they do not remain permanently stuck on screen.
 *
 * @author Angatu Sistemas
 */
public final class RadminToastWatch {

    private static final int WM_CLOSE = 0x0010;
    private static final int SW_HIDE = 0;
    private static final long MAX_VISIBLE_MS = 3_000L;
    private static final Map<HWND, Long> SEEN = new ConcurrentHashMap<>();
    private static ScheduledExecutorService executor;

    private RadminToastWatch() {
    }

    public static synchronized void start() {
        if (GraphicsEnvironment.isHeadless() || executor != null) {
            return;
        }
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "radmin-toast-watch");
            t.setDaemon(true);
            return t;
        });
        executor.scheduleWithFixedDelay(RadminToastWatch::sweep, 2, 1, TimeUnit.SECONDS);
    }

    public static synchronized void stop() {
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
        SEEN.clear();
    }

    private static void sweep() {
        try {
            User32 user32 = User32.INSTANCE;
            long now = System.currentTimeMillis();

            user32.EnumWindows(new WinUser.WNDENUMPROC() {
                @Override
                public boolean callback(HWND hWnd, Pointer data) {
                    try {
                        if (!user32.IsWindowVisible(hWnd)) {
                            SEEN.remove(hWnd);
                            return true;
                        }

                        char[] clsBuf = new char[256];
                        user32.GetClassName(hWnd, clsBuf, clsBuf.length);
                        String cls = Native.toString(clsBuf);

                        if (cls.contains("QWindowToolTip") || cls.contains("Toast") || cls.contains("QTipLabel")) {
                            IntByReference pid = new IntByReference();
                            user32.GetWindowThreadProcessId(hWnd, pid);
                            int processId = pid.getValue();

                            boolean isRadmin = ProcessHandle.of(processId)
                                    .map(ProcessHandle::info)
                                    .flatMap(ProcessHandle.Info::command)
                                    .map(c -> c.toLowerCase().contains("radmin") || c.toLowerCase().contains("rvrvpn"))
                                    .orElse(false);

                            if (isRadmin) {
                                Long firstSeen = SEEN.putIfAbsent(hWnd, now);
                                if (firstSeen != null && (now - firstSeen) >= MAX_VISIBLE_MS) {
                                    user32.PostMessage(hWnd, WM_CLOSE, new WPARAM(0), new LPARAM(0));
                                    user32.ShowWindow(hWnd, SW_HIDE);
                                    SEEN.remove(hWnd);
                                }
                            }
                        }
                    } catch (Exception ignored) {
                    }
                    return true;
                }
            }, null);

            SEEN.keySet().removeIf(h -> !user32.IsWindowVisible(h));
        } catch (Throwable ignored) {
        }
    }
}
