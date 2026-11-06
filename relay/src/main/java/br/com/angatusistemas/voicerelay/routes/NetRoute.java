package br.com.angatusistemas.voicerelay.routes;

import br.com.angatusistemas.lib.javalin.routes.Route;
import br.com.angatusistemas.voicerelay.services.NetHub;

/**
 * WS /ws/net: the connection each app keeps open. Anyone may connect by decision; what the gate proves is that a person
 * owns the key behind their id, so nobody can speak as someone else.
 *
 * @author Angatu Sistemas
 */
public final class NetRoute extends Route {

    public NetRoute() {
        super("/ws/net", NetHub::accept);
    }
}
