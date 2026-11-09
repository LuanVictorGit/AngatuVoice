package br.com.angatusistemas.voice.services;

import br.com.angatusistemas.voice.updater.UpdateService;
import br.com.angatusistemas.voice.utils.Version;
import br.com.angatusistemas.lib.gson.GsonAPI;
import com.google.gson.JsonObject;
import java.util.List;
import java.util.Map;

/**
 * Assembles the one read model the interface renders from: identity, network, groups, peers, calls and updater.
 *
 * @author Angatu Sistemas
 */
public final class StateService {

    private StateService() {
    }

    public static State snapshot() {
        IdentityService.Self me = PeerNetwork.me();
        Map<String, ChatService.Unread> unread = ChatService.unread();
        List<GroupView> groups = GroupService.list().stream()
                .map(g -> new GroupView(g.getId(), g.getName(), g.getKind(), g.getSecret() != null)).toList();
        List<PeerNetwork.PeerView> peers = PeerNetwork.peers();
        return new State(new Me(me.id(), me.name(), AvatarService.version(AvatarService.own().orElse(null)), ChatService.admin(),
                ChatService.mutedUntil()), net(), Version.current().toString(), groups, peers, unread, CallService.snapshot(), SettingsService.all(), UpdateService.status(),
                AutostartService.available(), DisplayService.monitors(), CallService.ringing());
    }

    private static Net net() {
        JsonObject turn = PeerNetwork.turn();
        return new Net(PeerNetwork.connected(), PeerNetwork.streamUrl(), turn == null ? null : GsonAPI.get().fromJson(turn, Turn.class));
    }

    public record Me(String id, String name, String avatar, ChatService.Admin admin, long mutedUntil) {
    }

    public record Net(boolean connected, String streamUrl, Turn turn) {
    }

    public record Turn(List<String> urls, String username, String credential) {
    }

    public record GroupView(String id, String name, String kind, boolean hasInvite) {
    }

    public record State(Me me, Net net, String version, List<GroupView> groups,
                        List<PeerNetwork.PeerView> peers, Map<String, ChatService.Unread> unread,
                        Map<String, List<String>> calls, Map<String, String> settings, UpdateService.Status update,
                        boolean autostartAvailable, List<DisplayService.Monitor> monitors,
                        List<CallService.Incoming> incoming) {
    }
}
