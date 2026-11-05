package br.com.angatusistemas.voice.services.radmin;

import br.com.angatusistemas.lib.console.Console;
import br.com.angatusistemas.lib.database.Saveable;
import br.com.angatusistemas.voice.entities.RadminNetwork;
import br.com.angatusistemas.voice.services.LiveHub;
import br.com.angatusistemas.voice.services.PeerNetwork;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Orchestrates all Radmin networks managed by AngatuVoice: default network enforcement,
 * custom network management, state synchronization with Radmin GUI, and real-time updates.
 *
 * @author Angatu Sistemas
 */
public final class NetworkManager {

    private NetworkManager() {
    }

    public static void ensureDefaultNetwork() {
        DefaultNetworkService.ensureRecord();
    }

    public static synchronized List<NetworkView> list() {
        ensureDefaultNetwork();
        RadminAutomation.RadminStatus radmin = RadminAutomation.query();
        List<RadminNetwork> stored = Saveable.findAll(RadminNetwork.class);

        // Auto-discover networks present in Radmin but not yet in SQLite
        for (String activeNet : radmin.networks()) {
            boolean exists = stored.stream().anyMatch(s -> s.getName().equalsIgnoreCase(activeNet));
            if (!exists && !NetworkDefaults.isProtected(activeNet)) {
                RadminNetwork discovered = new RadminNetwork();
                discovered.setId("net_" + activeNet.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "_"));
                discovered.setName(activeNet);
                discovered.setKind("custom");
                discovered.setProtectedNetwork(false);
                discovered.setStatus("connected");
                discovered.setCreatedAt(Instant.now().toEpochMilli());
                discovered.setLastSeenAt(Instant.now().toEpochMilli());
                discovered.save();
                stored.add(discovered);
            }
        }

        List<NetworkView> views = new ArrayList<>();
        int onlinePeers = (int) PeerNetwork.peers().stream().filter(PeerNetwork.PeerView::online).count();

        for (RadminNetwork net : stored) {
            boolean isProtected = net.isProtectedNetwork() || NetworkDefaults.isProtected(net.getName());
            boolean activeInRadmin = radmin.isConnectedTo(net.getName());
            String status = activeInRadmin ? "connected" : (isProtected ? DefaultNetworkService.state().name().toLowerCase() : "disconnected");

            views.add(new NetworkView(
                    net.getId(),
                    net.getName(),
                    isProtected ? "default" : "custom",
                    isProtected,
                    status,
                    isProtected ? DefaultNetworkService.message() : (activeInRadmin ? "Conectado" : "Desconectado"),
                    activeInRadmin ? radmin.ip() : "",
                    isProtected ? onlinePeers : 0,
                    net.getLastSeenAt(),
                    net.getCreatedAt()
            ));
        }

        // Default network always first
        views.sort(Comparator.comparing(NetworkView::isDefault).reversed().thenComparing(NetworkView::name));
        return views;
    }

    public static RadminAutomation.OperationResult join(String name, String password) {
        if (name == null || name.isBlank()) {
            return new RadminAutomation.OperationResult(false, "O nome da rede não pode ser vazio.");
        }
        String cleanName = name.trim();
        if (NetworkDefaults.isProtected(cleanName)) {
            DefaultNetworkService.reconnect();
            return new RadminAutomation.OperationResult(true, "Reconexão da rede padrão AngatuVoice iniciada.");
        }

        if (password == null || password.isBlank()) {
            return new RadminAutomation.OperationResult(false, "A senha da rede não pode ser vazia.");
        }

        RadminAutomation.OperationResult res = RadminAutomation.join(cleanName, password);
        if (res.ok()) {
            registerNetwork(cleanName, "connected");
            emitChange();
        }
        return res;
    }

    public static RadminAutomation.OperationResult create(String name, String password) {
        if (name == null || name.isBlank()) {
            return new RadminAutomation.OperationResult(false, "O nome da rede não pode ser vazio.");
        }
        String cleanName = name.trim();
        if (NetworkDefaults.isProtected(cleanName)) {
            DefaultNetworkService.reconnect();
            return new RadminAutomation.OperationResult(true, "A rede AngatuVoice é gerenciada automaticamente pelo sistema.");
        }

        if (password == null || password.length() < 6) {
            return new RadminAutomation.OperationResult(false, "A senha deve ter no mínimo 6 caracteres.");
        }

        RadminAutomation.OperationResult res = RadminAutomation.create(cleanName, password);
        if (res.ok()) {
            registerNetwork(cleanName, "connected");
            emitChange();
        }
        return res;
    }

    public static RadminAutomation.OperationResult leave(String name) {
        DefaultNetworkService.validateOperation(name);
        RadminAutomation.OperationResult res = RadminAutomation.leave(name.trim());
        if (res.ok()) {
            updateStatus(name.trim(), "disconnected");
            emitChange();
        }
        return res;
    }

    public static void delete(String name) {
        DefaultNetworkService.validateOperation(name);
        String clean = name.trim();
        RadminAutomation.delete(clean);
        for (RadminNetwork net : Saveable.findAll(RadminNetwork.class)) {
            if (net.getName().equalsIgnoreCase(clean)) {
                net.delete();
                break;
            }
        }
        emitChange();
    }

    public static void tick() {
        DefaultNetworkService.tick();
    }

    private static void registerNetwork(String name, String status) {
        for (RadminNetwork s : Saveable.findAll(RadminNetwork.class)) {
            if (s.getName().equalsIgnoreCase(name)) {
                s.setStatus(status);
                s.setLastSeenAt(Instant.now().toEpochMilli());
                s.save();
                return;
            }
        }
        RadminNetwork net = new RadminNetwork();
        net.setId("net_" + name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "_"));
        net.setName(name);
        net.setKind("custom");
        net.setProtectedNetwork(false);
        net.setStatus(status);
        net.setCreatedAt(Instant.now().toEpochMilli());
        net.setLastSeenAt(Instant.now().toEpochMilli());
        net.save();
    }

    private static void updateStatus(String name, String status) {
        for (RadminNetwork s : Saveable.findAll(RadminNetwork.class)) {
            if (s.getName().equalsIgnoreCase(name)) {
                s.setStatus(status);
                s.save();
                return;
            }
        }
    }

    private static void emitChange() {
        LiveHub.emit("net.networks", list());
    }

    public record NetworkView(String id, String name, String kind, boolean isDefault, String status,
                              String message, String ip, int onlinePeers, long lastSeenAt, long createdAt) {
    }
}
