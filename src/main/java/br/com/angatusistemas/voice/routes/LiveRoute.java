package br.com.angatusistemas.voice.routes;

import br.com.angatusistemas.lib.javalin.routes.Route;
import br.com.angatusistemas.voice.services.LiveHub;

/**
 * WS /ws: the one live channel of the interface; the gate lives in LiveHub.
 *
 * @author Angatu Sistemas
 */
public final class LiveRoute extends Route {

    public LiveRoute() {
        super("/ws", LiveHub::accept);
    }
}