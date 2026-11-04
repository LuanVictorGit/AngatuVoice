package br.com.angatusistemas.voice.services;

import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Bridges services to the desktop shell: shows a native notification only when the user wants them and is away.
 *
 * @author Angatu Sistemas
 */
public final class Notifications {

    private static volatile BiConsumer<String, String> sink = (title, body) -> { };
    private static volatile BooleanSupplier windowFocused = () -> true;
    private static volatile Consumer<List<CallService.Incoming>> ringingSink = calls -> { };

    private Notifications() {
    }

    public static void bind(BiConsumer<String, String> display, BooleanSupplier focused) {
        sink = display;
        windowFocused = focused;
    }

    public static void bindRinging(Consumer<List<CallService.Incoming>> display) {
        ringingSink = display;
    }

    public static void ringing(List<CallService.Incoming> calls) {
        ringingSink.accept(calls);
    }

    public static void notify(String title, String body) {
        if (!windowFocused.getAsBoolean() && SettingsService.flag("notifications")) {
            sink.accept(title, body);
        }
    }
}
