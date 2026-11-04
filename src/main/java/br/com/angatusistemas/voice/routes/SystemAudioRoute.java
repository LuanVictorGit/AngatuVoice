package br.com.angatusistemas.voice.routes;

import br.com.angatusistemas.lib.javalin.routes.Route;
import br.com.angatusistemas.voice.services.SystemAudio;

/**
 * WS /ws/system-audio: the system sound of a screen share without this app's own output; the gate lives in SystemAudio.
 *
 * @author Angatu Sistemas
 */
public final class SystemAudioRoute extends Route {

    public SystemAudioRoute() {
        super("/ws/system-audio", SystemAudio::accept);
    }
}
