package br.com.angatusistemas.voice.desktop;

import com.sun.jna.Native;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.platform.win32.WinUser;
import java.awt.Window;

/**
 * The page's Fullscreen API made real: Chromium only fills the window it is in, so the window itself loses its frame and
 * covers the whole monitor it is on, then gets its frame, size and maximized state back. Done in Win32 instead of Swing,
 * because Swing can only drop a window's frame by destroying it, and Chromium lives inside that window.
 *
 * @author Angatu Sistemas
 */
final class FullScreen {

    private static final int FRAME_STYLES = WinUser.WS_CAPTION | WinUser.WS_THICKFRAME;
    private static final int SWP_FRAMECHANGED = 0x0020;
    private static final int SWP_NOZORDER = 0x0004;
    private static final int SWP_NOMOVE = 0x0002;
    private static final int SWP_NOSIZE = 0x0001;
    private static final int SWP_NOOWNERZORDER = 0x0200;
    private static final int SWP_SHOWWINDOW = 0x0040;

    private final Window window;
    private WinUser.WINDOWPLACEMENT placement;
    private int style;
    private boolean active;

    FullScreen(Window window) {
        this.window = window;
    }

    /* Runs on the Swing thread; every Win32 call below is answered by the window's own thread. */
    void set(boolean on) {
        if (on == active) {
            return;
        }
        User32 user32 = User32.INSTANCE;
        HWND hwnd = new HWND(Native.getWindowPointer(window));
        if (on) {
            placement = new WinUser.WINDOWPLACEMENT();
            user32.GetWindowPlacement(hwnd, placement);
            style = user32.GetWindowLong(hwnd, WinUser.GWL_STYLE);
            WinUser.MONITORINFO monitor = new WinUser.MONITORINFO();
            user32.GetMonitorInfo(user32.MonitorFromWindow(hwnd, WinUser.MONITOR_DEFAULTTONEAREST), monitor);
            user32.SetWindowLong(hwnd, WinUser.GWL_STYLE, style & ~FRAME_STYLES);
            user32.SetWindowPos(hwnd, new HWND(com.sun.jna.Pointer.NULL), monitor.rcMonitor.left, monitor.rcMonitor.top,
                    monitor.rcMonitor.right - monitor.rcMonitor.left, monitor.rcMonitor.bottom - monitor.rcMonitor.top,
                    SWP_FRAMECHANGED | SWP_NOOWNERZORDER | SWP_SHOWWINDOW);
        } else {
            user32.SetWindowLong(hwnd, WinUser.GWL_STYLE, style);
            user32.SetWindowPos(hwnd, null, 0, 0, 0, 0, SWP_FRAMECHANGED | SWP_NOMOVE | SWP_NOSIZE | SWP_NOZORDER | SWP_NOOWNERZORDER);
            user32.SetWindowPlacement(hwnd, placement);
        }
        active = on;
    }
}
