package br.com.angatusistemas.voice.routes;

import br.com.angatusistemas.lib.gson.GsonAPI;
import br.com.angatusistemas.lib.javalin.routes.Route;
import br.com.angatusistemas.lib.javalin.routes.RouteType;
import br.com.angatusistemas.voice.services.AutostartService;
import br.com.angatusistemas.voice.services.PeerNetwork;
import br.com.angatusistemas.voice.services.RadminService;
import br.com.angatusistemas.voice.utils.AppDirs;
import br.com.angatusistemas.voice.utils.Version;
import io.javalin.http.Context;
import java.util.List;
import java.util.Map;

/**
 * GET /api/system/diagnostics: network and connection facts for the support panel.
 *
 * @author Angatu Sistemas
 */
public final class DiagnosticsRoute extends Route {

    public DiagnosticsRoute() {
        super("/api/system/diagnostics", RouteType.GET, DiagnosticsRoute::handle);
    }

    private static void handle(Context ctx) {
        Guard.requireSession(ctx);
        List<PeerNetwork.PeerView> peers = PeerNetwork.peers();
        ctx.json(GsonAPI.get().toJson(Map.of(
                "radmin", RadminService.current(),
                "version", Version.current().toString(),
                "peersKnown", peers.size(),
                "peersOnline", peers.stream().filter(PeerNetwork.PeerView::online).count(),
                "install", AppDirs.install().map(Object::toString).orElse(""),
                "autostart", AutostartService.enabled(),
                "java", System.getProperty("java.version"))));
    }
}
