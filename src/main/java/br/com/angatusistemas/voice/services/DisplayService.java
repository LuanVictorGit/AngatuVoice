package br.com.angatusistemas.voice.services;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.platform.win32.WinUser;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.win32.StdCallLibrary;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.OptionalLong;
import java.util.Set;

/**
 * What a screen share can show. Monitors are listed with the same numbering Chromium's screen capture uses, so a share
 * source id points at exactly the monitor the person picked. Windows are the ones a person would call an open app or game:
 * visible, titled, not minimised, not a tool window, not hidden by the system, and never this app's own.
 *
 * @author Angatu Sistemas
 */
public final class DisplayService {

    private static final int DISPLAY_DEVICE_ACTIVE = 0x1;
    private static final int DISPLAY_DEVICE_PRIMARY = 0x4;
    private static final int MAX_DEVICES = 32;
    private static final int MAX_WINDOWS = 64;
    private static final int MAX_TITLE = 120;
    private static final int GWL_EXSTYLE = -20;
    private static final int WS_EX_TOOLWINDOW = 0x00000080;
    private static final int GW_OWNER = 4;
    private static final int DWMWA_CLOAKED = 14;

    private DisplayService() {
    }

    public static List<Monitor> monitors() {
        List<Monitor> monitors = new ArrayList<>();
        for (int index = 0; index < MAX_DEVICES; index++) {
            DisplayDevice device = new DisplayDevice();
            if (!Displays.INSTANCE.EnumDisplayDevicesW(null, index, device, 0)) {
                break;
            }
            if ((device.StateFlags & DISPLAY_DEVICE_ACTIVE) != 0) {
                monitors.add(new Monitor(index, (device.StateFlags & DISPLAY_DEVICE_PRIMARY) != 0));
            }
        }
        return monitors;
    }

    public static List<AppWindow> windows() {
        Set<Long> own = ownProcesses();
        List<AppWindow> found = new ArrayList<>();
        Displays.INSTANCE.EnumWindows((hwnd, data) -> {
            shareable(hwnd, own).ifPresent(pid -> found.add(new AppWindow(Pointer.nativeValue(hwnd.getPointer()), title(hwnd), appName(pid))));
            return found.size() < MAX_WINDOWS;
        }, null);
        return found;
    }

    /* The process behind a window the page asked to hear, but only if that window is one the list would offer. */
    public static OptionalLong processOf(long handle) {
        if (handle == 0) {
            return OptionalLong.empty();
        }
        return shareable(new HWND(new Pointer(handle)), ownProcesses());
    }

    private static OptionalLong shareable(HWND hwnd, Set<Long> own) {
        Displays user32 = Displays.INSTANCE;
        if (!user32.IsWindow(hwnd) || !user32.IsWindowVisible(hwnd) || user32.IsIconic(hwnd) || user32.GetWindowTextLengthW(hwnd) == 0
                || user32.GetWindow(hwnd, GW_OWNER) != null || (user32.GetWindowLongW(hwnd, GWL_EXSTYLE) & WS_EX_TOOLWINDOW) != 0) {
            return OptionalLong.empty();
        }
        IntByReference cloaked = new IntByReference();
        if (Dwm.INSTANCE.DwmGetWindowAttribute(hwnd, DWMWA_CLOAKED, cloaked, 4) == 0 && cloaked.getValue() != 0) {
            return OptionalLong.empty();
        }
        IntByReference pid = new IntByReference();
        user32.GetWindowThreadProcessId(hwnd, pid);
        long process = Integer.toUnsignedLong(pid.getValue());
        return process == 0 || own.contains(process) ? OptionalLong.empty() : OptionalLong.of(process);
    }

    private static Set<Long> ownProcesses() {
        Set<Long> own = new HashSet<>();
        own.add(ProcessHandle.current().pid());
        ProcessHandle.current().descendants().forEach(p -> own.add(p.pid()));
        return own;
    }

    private static String title(HWND hwnd) {
        char[] buffer = new char[MAX_TITLE + 1];
        int length = Displays.INSTANCE.GetWindowTextW(hwnd, buffer, buffer.length);
        String text = new String(buffer, 0, Math.max(0, length)).replaceAll("\\p{Cntrl}", "").strip();
        return text.length() > MAX_TITLE ? text.substring(0, MAX_TITLE) : text;
    }

    private static String appName(long pid) {
        return ProcessHandle.of(pid).flatMap(p -> p.info().command())
                .map(command -> Path.of(command).getFileName().toString().replaceAll("(?i)\\.exe$", ""))
                .orElse("");
    }

    public record Monitor(int id, boolean primary) {
    }

    public record AppWindow(long id, String title, String app) {
    }

    private interface Displays extends StdCallLibrary {

        Displays INSTANCE = Native.load("user32", Displays.class);

        boolean EnumDisplayDevicesW(String device, int index, DisplayDevice out, int flags);

        boolean EnumWindows(WinUser.WNDENUMPROC callback, Pointer data);

        boolean IsWindow(HWND hwnd);

        boolean IsWindowVisible(HWND hwnd);

        boolean IsIconic(HWND hwnd);

        int GetWindowTextLengthW(HWND hwnd);

        int GetWindowTextW(HWND hwnd, char[] text, int max);

        HWND GetWindow(HWND hwnd, int command);

        int GetWindowLongW(HWND hwnd, int index);

        int GetWindowThreadProcessId(HWND hwnd, IntByReference pid);
    }

    private interface Dwm extends StdCallLibrary {

        Dwm INSTANCE = Native.load("dwmapi", Dwm.class);

        int DwmGetWindowAttribute(HWND hwnd, int attribute, IntByReference value, int size);
    }

    @Structure.FieldOrder({"cb", "DeviceName", "DeviceString", "StateFlags", "DeviceID", "DeviceKey"})
    public static final class DisplayDevice extends Structure {

        public int cb;
        public char[] DeviceName = new char[32];
        public char[] DeviceString = new char[128];
        public int StateFlags;
        public char[] DeviceID = new char[128];
        public char[] DeviceKey = new char[128];

        public DisplayDevice() {
            cb = size();
        }
    }
}
