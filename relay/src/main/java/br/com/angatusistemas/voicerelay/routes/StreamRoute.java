package br.com.angatusistemas.voicerelay.routes;

import br.com.angatusistemas.lib.javalin.routes.Route;
import br.com.angatusistemas.voicerelay.services.StreamHub;

/**
 * WS /ws/stream: the relay's only channel. It is open to anyone by decision, so its gate is the hub's limits and the
 * unguessable stream id, which only people in the call ever receive.
 *
 * @author Angatu Sistemas
 */
public final class StreamRoute extends Route {

    public StreamRoute() {
        super("/ws/stream", StreamHub::accept);
    }
}
