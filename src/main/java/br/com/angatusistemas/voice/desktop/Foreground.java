package br.com.angatusistemas.voice.desktop;

import com.sun.jna.Native;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef.DWORD;
import com.sun.jna.platform.win32.WinDef.HWND;
import java.awt.Window;

/**
 * Brings a window to the front for real. Windows only lets the foreground thread hand focus over, so this briefly
 * shares input state with it; without that, a window opened a few seconds after the click stays behind the others.
 *
 * @author Angatu Sistemas
 */
final class Foreground {

    private static final int SW_RESTORE = 9;

    private Foreground() {
    }

    static void raise(Window window) {
        User32 user32 = User32.INSTANCE;
        HWND target = new HWND(Native.getWindowPointer(window));
        HWND current = user32.GetForegroundWindow();
        int ours = Kernel32.INSTANCE.GetCurrentThreadId();
        int theirs = current == null ? 0 : user32.GetWindowThreadProcessId(current, null);
        boolean attached = theirs != 0 && theirs != ours
                && user32.AttachThreadInput(new DWORD(ours), new DWORD(theirs), true);
        try {
            if ((window instanceof java.awt.Frame frame) && (frame.getExtendedState() & java.awt.Frame.ICONIFIED) != 0) {
                user32.ShowWindow(target, SW_RESTORE);
            }
            user32.BringWindowToTop(target);
            user32.SetForegroundWindow(target);
            user32.SetFocus(target);
        } finally {
            if (attached) {
                user32.AttachThreadInput(new DWORD(ours), new DWORD(theirs), false);
            }
        }
    }
}
