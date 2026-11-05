package br.com.angatusistemas.voice.services;

import br.com.angatusistemas.voice.services.radmin.DefaultNetworkService;
import br.com.angatusistemas.voice.updater.UpdateService;
import br.com.angatusistemas.voice.utils.Version;
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
        Map<String, Integer> unread = MessageService.unread();
        RadminService.Status radmin = RadminService.current();
        List<GroupView> groups = GroupService.list().stream()
                .map(g -> new GroupView(g.getId(), g.getName(), g.getKind(), unread.getOrDefault(g.getId(), 0),
                        g.getSecret() != null)).toList();
        List<PeerNetwork.PeerView> peers = PeerNetwork.peers();
        Map<String, Integer> directUnread = unread.entrySet().stream().filter(e -> e.getKey().startsWith("dm:"))
                .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        boolean autoJoined = DefaultNetworkService.checkAndClearAutoJoinNotice();
        return new State(new Me(me.id(), me.name(), radmin.alias(), AvatarService.version(AvatarService.own().orElse(null))), radmin, radmin.issues(), Version.current().toString(),
                groups, peers, directUnread, CallService.snapshot(), SettingsService.all(), UpdateService.status(),
                AutostartService.available(), DisplayService.monitors(), CallService.ringing(), autoJoined);
    }

    public record Me(String id, String name, String alias, String avatar) {
    }

    public record GroupView(String id, String name, String kind, int unread, boolean hasInvite) {
    }

    public record State(Me me, RadminService.Status radmin, List<String> issues, String version, List<GroupView> groups,
                        List<PeerNetwork.PeerView> peers, Map<String, Integer> directUnread,
                        Map<String, List<String>> calls, Map<String, String> settings, UpdateService.Status update,
                        boolean autostartAvailable, List<DisplayService.Monitor> monitors,
                        List<CallService.Incoming> incoming, boolean autoJoined) {
    }
}
