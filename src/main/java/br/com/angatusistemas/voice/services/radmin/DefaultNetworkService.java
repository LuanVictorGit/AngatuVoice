package br.com.angatusistemas.voice.services.radmin;

import br.com.angatusistemas.lib.console.Console;
import br.com.angatusistemas.lib.database.Saveable;
import br.com.angatusistemas.voice.entities.RadminNetwork;
import br.com.angatusistemas.voice.services.LiveHub;
import br.com.angatusistemas.voice.services.RadminService;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Manages the mandatory default network "AngatuVoice": one automatic join or creation for a new participant, joining
 * again when the person asks, and protection against removal. Its periodic check never touches the Radmin window.
 *
 * @author Angatu Sistemas
 */
public final class DefaultNetworkService {

    public enum State {
        CONNECTED("Conectado"),
        CONNECTING("Conectando…"),
        RECONNECTING("Reconectando…"),
        AWAITING_BOOTSTRAP("Aguardando inicialização da rede"),
        SERVICE_STOPPED("Serviço Radmin parado"),
        NOT_INSTALLED("Radmin não instalado"),
        DISCONNECTED("Desconectado"),
        ERROR("Falha na conexão");

        private final String label;

        State(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    private static final String ID = "net_angatuvoice";
    private static final AtomicReference<State> CURRENT_STATE = new AtomicReference<>(State.DISCONNECTED);
    private static final AtomicReference<String> LAST_MESSAGE = new AtomicReference<>("");
    private static final AtomicBoolean PENDING_AUTOJOIN_NOTICE = new AtomicBoolean(false);
    private static final AtomicBoolean JOIN_REQUESTED = new AtomicBoolean(false);

    private DefaultNetworkService() {
    }

    public static State state() {
        return CURRENT_STATE.get();
    }

    public static String message() {
        return LAST_MESSAGE.get();
    }

    public static boolean isConnected() {
        return CURRENT_STATE.get() == State.CONNECTED;
    }

    public static boolean checkAndClearAutoJoinNotice() {
        return PENDING_AUTOJOIN_NOTICE.getAndSet(false);
    }

    public static void notifyAutoJoined() {
        PENDING_AUTOJOIN_NOTICE.set(true);
        LiveHub.emit("net.autojoined", Map.of(
                "network", NetworkDefaults.DEFAULT_NETWORK_NAME,
                "message", "Você entrou na rede AngatuVoice automaticamente. Pode fechar o Radmin e usar o Voice <3"
        ));
    }

    public static void ensureRecord() {
        if (!Saveable.exists(RadminNetwork.class, ID)) {
            RadminNetwork def = new RadminNetwork();
            def.setId(ID);
            def.setName(NetworkDefaults.DEFAULT_NETWORK_NAME);
            def.setKind("default");
            def.setProtectedNetwork(true);
            def.setStatus(State.DISCONNECTED.name().toLowerCase());
            def.setCreatedAt(Instant.now().toEpochMilli());
            def.setLastSeenAt(0);
            def.save();
        }
    }

    public static void reconnect() {
        JOIN_REQUESTED.set(true);
        Thread.startVirtualThread(DefaultNetworkService::tick);
    }

    public static synchronized void tick() {
        try {
            tickInternal();
        } catch (Throwable t) {
            Console.warn("Erro ao atualizar status da rede padrão: %s", t.getMessage());
        }
    }

    private static void tickInternal() {
        if (System.getProperty("angatu.radmin.ip") != null) {
            transition(State.CONNECTED, "Conectado (ambiente de teste).");
            if (JOIN_REQUESTED.getAndSet(false)) {
                notifyAutoJoined();
            }
            return;
        }

        RadminService.Status radminStatus = RadminService.current();
        if (!radminStatus.installed()) {
            transition(State.NOT_INSTALLED, "O Radmin VPN não está instalado no sistema.");
            return;
        }
        if (!radminStatus.running()) {
            transition(State.SERVICE_STOPPED, "O serviço RvControlSvc do Radmin VPN não está em execução.");
            return;
        }

        RadminNetwork record = Saveable.findById(RadminNetwork.class, ID);
        boolean requested = JOIN_REQUESTED.getAndSet(false);
        if (!requested && isMember(record)) {
            markConnected();
            return;
        }
        // Opening the Radmin window unasked is only acceptable once ever, for the first automatic join; after that the button asks.
        if (!requested && (record == null || record.isAutoJoinTried())) {
            transition(State.DISCONNECTED, "Fora da rede " + NetworkDefaults.DEFAULT_NETWORK_NAME + ". Use o botão Reconectar para entrar.");
            return;
        }
        if (record != null) {
            record.setAutoJoinTried(true);
            record.save();
        }

        State previous = CURRENT_STATE.get();
        transition(previous == State.CONNECTED ? State.RECONNECTING : State.CONNECTING,
                "Tentando conectar à rede " + NetworkDefaults.DEFAULT_NETWORK_NAME + "…");
        if (joinOrCreate()) {
            markConnected();
            notifyAutoJoined();
        } else {
            transition(State.DISCONNECTED, "Não foi possível conectar à rede padrão no momento.");
            updateRecord("disconnected");
        }
    }

    /* Radmin only names the networks it has shown in its window, so a join seen here once stands while any membership remains. */
    private static boolean isMember(RadminNetwork record) {
        if (RadminAutomation.query().isConnectedTo(NetworkDefaults.DEFAULT_NETWORK_NAME)) {
            return true;
        }
        return record != null && record.getLastSeenAt() > 0 && RadminAutomation.membershipCount() > 0;
    }

    private static boolean joinOrCreate() {
        String name = NetworkDefaults.DEFAULT_NETWORK_NAME;
        String password = NetworkDefaults.DEFAULT_NETWORK_PASSWORD;
        RadminAutomation.OperationResult join = RadminAutomation.join(name, password);
        if (join.ok()) {
            return true;
        }
        String joinMsg = join.message().toLowerCase();
        Console.log("Tentativa de ingresso na rede padrão retornou: %s", join.message());
        boolean missing = joinMsg.contains("não encontrad") || joinMsg.contains("not found") || joinMsg.contains("inválid")
                || joinMsg.contains("tempo limite") || joinMsg.contains("incorret");
        if (!missing) {
            return false;
        }
        Console.log("Criando a rede padrão automaticamente: %s", name);
        RadminAutomation.OperationResult create = RadminAutomation.create(name, password);
        if (create.ok()) {
            return true;
        }
        String createMsg = create.message().toLowerCase();
        // Another peer created it at the same moment.
        return (createMsg.contains("já existe") || createMsg.contains("already exists")) && RadminAutomation.join(name, password).ok();
    }

    private static void markConnected() {
        if (CURRENT_STATE.get() != State.CONNECTED) {
            updateRecord("connected");
        }
        transition(State.CONNECTED, "Conectado à rede oficial AngatuVoice.");
    }

    public static void validateOperation(String networkName) {
        if (NetworkDefaults.isProtected(networkName)) {
            throw new UnsupportedOperationException("A rede padrão AngatuVoice é obrigatória e não pode ser removida ou alterada.");
        }
    }

    private static void transition(State newState, String msg) {
        State old = CURRENT_STATE.getAndSet(newState);
        LAST_MESSAGE.set(msg);
        if (old != newState) {
            Console.log("Rede padrão [%s -> %s]: %s", old, newState, msg);
            LiveHub.emit("net.status", new StatusEvent(newState.name(), newState.label(), msg));
        }
    }

    private static void updateRecord(String status) {
        RadminNetwork net = Saveable.findById(RadminNetwork.class, ID);
        if (net != null) {
            net.setStatus(status);
            if ("connected".equals(status)) {
                net.setLastSeenAt(Instant.now().toEpochMilli());
            }
            net.save();
        }
    }

    public record StatusEvent(String state, String label, String message) {
    }
}
