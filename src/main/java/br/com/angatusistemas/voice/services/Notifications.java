package br.com.angatusistemas.voice.services;

import java.util.function.BiConsumer;

/**
 * Bridges services to the desktop shell: shows a native notification only when the user wants them and is away.
 *
 * @author Angatu Sistemas
 */
public final class Notifications {

    private static volatile BiConsumer<String, String> sink = (title, body) -> { };
    private static volatile boolean windowFocused = true;

    private Notifications() {
    }

    public static void bind(BiConsumer<String, String> display) {
        sink = display;
    }

    public static void focus(boolean focused) {
        windowFocused = focused;
    }

    public static void notify(String title, String body) {
        if (!windowFocused && SettingsService.flag("notifications")) {
            sink.accept(title, body);
        }
    }
}
