package br.com.angatusistemas.voice.services;

import br.com.angatusistemas.lib.console.Console;
import br.com.angatusistemas.lib.gson.GsonAPI;
import io.javalin.websocket.WsConfig;
import io.javalin.websocket.WsContext;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * The single door of the interface's live channel: checks the session, then registers the socket and fans events out.
 *
 * @author Angatu Sistemas
 */
public final class LiveHub {

    public static final int SESSION_EXPIRED = 4401;
    private static final int PING_SECONDS = 25;
    private static final Set<WsContext> VIEWERS = ConcurrentHashMap.newKeySet();

    private LiveHub() {
    }

    public static void accept(WsConfig ws) {
        ws.onConnect(ctx -> {
            if (!SessionService.valid(ctx.cookie(SessionService.COOKIE), ctx.header("Host"))) {
                ctx.closeSession(SESSION_EXPIRED, "session expired");
                return;
            }
            ctx.enableAutomaticPings(PING_SECONDS, TimeUnit.SECONDS);
            VIEWERS.add(ctx);
            emit("open", Map.of());
        });
        ws.onClose(ctx -> VIEWERS.remove(ctx));
        ws.onError(ctx -> VIEWERS.remove(ctx));
    }

    public static void emit(String event, Object data) {
        if (VIEWERS.isEmpty()) {
            return;
        }
        String json = GsonAPI.get().toJson(Map.of("e", event, "d", data));
        for (WsContext viewer : VIEWERS) {
            try {
                viewer.send(json);
            } catch (RuntimeException e) {
                VIEWERS.remove(viewer);
                Console.debug("live channel dropped: " + e.getClass().getSimpleName());
            }
        }
    }
}
