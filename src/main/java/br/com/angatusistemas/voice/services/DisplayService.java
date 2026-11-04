package br.com.angatusistemas.voice.services;

import com.sun.jna.Native;
import com.sun.jna.Structure;
import com.sun.jna.win32.StdCallLibrary;
import java.util.ArrayList;
import java.util.List;

/**
 * Lists the active monitors with the same numbering Chromium's screen capture uses, so a share source id points at
 * exactly the monitor the person picked.
 *
 * @author Angatu Sistemas
 */
public final class DisplayService {

    private static final int DISPLAY_DEVICE_ACTIVE = 0x1;
    private static final int DISPLAY_DEVICE_PRIMARY = 0x4;
    private static final int MAX_DEVICES = 32;

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

    public record Monitor(int id, boolean primary) {
    }

    private interface Displays extends StdCallLibrary {

        Displays INSTANCE = Native.load("user32", Displays.class);

        boolean EnumDisplayDevicesW(String device, int index, DisplayDevice out, int flags);
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
