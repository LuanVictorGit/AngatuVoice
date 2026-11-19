package br.com.angatusistemas.voice.services;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
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
    // A burst of messages in one conversation shows one balloon; a mention or a direct message always shows.
    private static final long SAME_CHAT_GAP_MS = 5_000;
    // Windows keeps a balloon up for a few seconds; a click on the tray icon after that is not a click on the balloon.
    private static final long BALLOON_CLICK_MS = 10_000;
    private static final Map<String, Long> LAST_BY_CHAT = new ConcurrentHashMap<>();
    private static volatile String lastChat;
    private static volatile long lastShownAt;

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
            lastChat = null;
            sink.accept(title, body);
        }
    }

    public static void message(String chat, String title, String body, boolean priority) {
        if (windowFocused.getAsBoolean() || !SettingsService.flag("notifications")) {
            return;
        }
        long now = System.currentTimeMillis();
        Long previous = LAST_BY_CHAT.put(chat, now);
        if (!priority && previous != null && now - previous < SAME_CHAT_GAP_MS) {
            return;
        }
        lastChat = chat;
        lastShownAt = now;
        sink.accept(title, body);
    }

    /* The conversation of the balloon just clicked, if the click came while it could still be on screen. */
    public static String clickedChat() {
        String chat = lastChat;
        lastChat = null;
        return chat != null && System.currentTimeMillis() - lastShownAt < BALLOON_CLICK_MS ? chat : null;
    }
}
