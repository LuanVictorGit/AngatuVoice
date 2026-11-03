package br.com.angatusistemas.voice.desktop;

import br.com.angatusistemas.lib.console.Console;
import br.com.angatusistemas.voice.services.LiveHub;
import br.com.angatusistemas.voice.services.SettingsService;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef.LPARAM;
import com.sun.jna.platform.win32.WinDef.LRESULT;
import com.sun.jna.platform.win32.WinDef.WPARAM;
import com.sun.jna.platform.win32.WinUser;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * System-wide shortcuts through a low-level keyboard hook, so push-to-talk works with the window minimised or unfocused.
 * The hook only observes: every key is passed on, and the work happens off the hook thread.
 *
 * @author Angatu Sistemas
 */
final class Hotkeys {

    private static final List<String> ACTIONS = List.of("ptt", "mic", "deafen", "call", "muteAll");
    private static final Map<String, Integer> KEYS = keys();
    private static final Map<String, Combo> BINDINGS = new ConcurrentHashMap<>();
    private static final Set<String> HELD = ConcurrentHashMap.newKeySet();
    private static final int WM_KEYDOWN = 0x100;
    private static final int WM_KEYUP = 0x101;
    private static final int WM_SYSKEYDOWN = 0x104;
    private static final int WM_SYSKEYUP = 0x105;
    private static final int WM_QUIT = 0x12;
    private static volatile int hookThread;

    private Hotkeys() {
    }

    static void start() {
        reload();
        SettingsService.listen(changes -> {
            if (changes.keySet().stream().anyMatch(k -> k.startsWith("hotkeys."))) {
                reload();
            }
        });
        Thread thread = new Thread(Hotkeys::loop, "av-hotkeys");
        thread.setDaemon(true);
        thread.start();
    }

    static void stop() {
        int id = hookThread;
        if (id != 0) {
            User32.INSTANCE.PostThreadMessage(id, WM_QUIT, new WPARAM(0), new LPARAM(0));
        }
    }

    private static void reload() {
        BINDINGS.clear();
        for (String action : ACTIONS) {
            Combo combo = Combo.parse(SettingsService.get("hotkeys." + action));
            if (combo != null) {
                BINDINGS.put(action, combo);
            }
        }
    }

    private static void loop() {
        User32 user32 = User32.INSTANCE;
        hookThread = Kernel32.INSTANCE.GetCurrentThreadId();
        WinUser.LowLevelKeyboardProc proc = (nCode, wParam, info) -> {
            if (nCode >= 0) {
                onKey(wParam.intValue(), info.vkCode);
            }
            return user32.CallNextHookEx(null, nCode, wParam, new LPARAM(Pointer.nativeValue(info.getPointer())));
        };
        WinUser.HHOOK hook = user32.SetWindowsHookEx(WinUser.WH_KEYBOARD_LL, proc,
                Kernel32.INSTANCE.GetModuleHandle(null), 0);
        if (hook == null) {
            Console.warn("Atalhos globais indisponíveis: o Windows recusou o gancho de teclado.");
            hookThread = 0;
            return;
        }
        WinUser.MSG msg = new WinUser.MSG();
        while (user32.GetMessage(msg, null, 0, 0) > 0) {
            user32.TranslateMessage(msg);
            user32.DispatchMessage(msg);
        }
        user32.UnhookWindowsHookEx(hook);
        hookThread = 0;
    }

    private static void onKey(int message, int vk) {
        boolean down = message == WM_KEYDOWN || message == WM_SYSKEYDOWN;
        boolean up = message == WM_KEYUP || message == WM_SYSKEYUP;
        if (!down && !up) {
            return;
        }
        BINDINGS.forEach((action, combo) -> {
            if (down && combo.matches(vk) && HELD.add(action)) {
                fire(action, "down");
            } else if (up && combo.vk == vk && HELD.remove(action)) {
                fire(action, "up");
            }
        });
    }

    private static void fire(String action, String state) {
        Thread.startVirtualThread(() -> LiveHub.emit("hotkey", Map.of("action", action, "state", state)));
    }

    private static boolean pressed(int vk) {
        return (User32.INSTANCE.GetAsyncKeyState(vk) & 0x8000) != 0;
    }

    private static Map<String, Integer> keys() {
        Map<String, Integer> k = new HashMap<>();
        for (char c = 'A'; c <= 'Z'; c++) {
            k.put(String.valueOf(c), (int) c);
        }
        for (char c = '0'; c <= '9'; c++) {
            k.put(String.valueOf(c), (int) c);
        }
        for (int i = 1; i <= 24; i++) {
            k.put("F" + i, 0x6F + i);
        }
        Map<String, Integer> named = Map.ofEntries(Map.entry("SPACE", 0x20), Map.entry("ENTER", 0x0D), Map.entry("TAB", 0x09),
                Map.entry("LEFT", 0x25), Map.entry("UP", 0x26), Map.entry("RIGHT", 0x27), Map.entry("DOWN", 0x28),
                Map.entry("HOME", 0x24), Map.entry("END", 0x23), Map.entry("PAGE_UP", 0x21), Map.entry("PAGE_DOWN", 0x22),
                Map.entry("INSERT", 0x2D), Map.entry("DELETE", 0x2E), Map.entry("CAPS_LOCK", 0x14),
                Map.entry("BACKQUOTE", 0xC0), Map.entry("MINUS", 0xBD), Map.entry("EQUALS", 0xBB),
                Map.entry("BACK_SLASH", 0xDC), Map.entry("SEMICOLON", 0xBA), Map.entry("QUOTE", 0xDE),
                Map.entry("COMMA", 0xBC), Map.entry("PERIOD", 0xBE), Map.entry("SLASH", 0xBF),
                Map.entry("OPEN_BRACKET", 0xDB), Map.entry("CLOSE_BRACKET", 0xDD));
        k.putAll(named);
        return Map.copyOf(k);
    }

    private record Combo(boolean ctrl, boolean alt, boolean shift, boolean meta, int vk) {

        static Combo parse(String text) {
            if (text == null || text.isBlank()) {
                return null;
            }
            boolean ctrl = false;
            boolean alt = false;
            boolean shift = false;
            boolean meta = false;
            Integer vk = null;
            for (String token : text.split("\\+")) {
                switch (token.toUpperCase()) {
                    case "CTRL" -> ctrl = true;
                    case "ALT" -> alt = true;
                    case "SHIFT" -> shift = true;
                    case "META" -> meta = true;
                    default -> vk = KEYS.get(token.toUpperCase());
                }
            }
            return vk == null ? null : new Combo(ctrl, alt, shift, meta, vk);
        }

        boolean matches(int pressedVk) {
            return pressedVk == vk && pressed(0x11) == ctrl && pressed(0x12) == alt && pressed(0x10) == shift
                    && (pressed(0x5B) || pressed(0x5C)) == meta;
        }
    }
}
