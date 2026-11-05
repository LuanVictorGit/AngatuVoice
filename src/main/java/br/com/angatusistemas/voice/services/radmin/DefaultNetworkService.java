package br.com.angatusistemas.voice.services.radmin;

import br.com.angatusistemas.lib.console.Console;
import br.com.angatusistemas.lib.database.Saveable;
import br.com.angatusistemas.voice.entities.RadminNetwork;
import br.com.angatusistemas.voice.services.LiveHub;
import br.com.angatusistemas.voice.services.RadminService;
import java.time.Instant;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Manages the mandatory default network "AngatuVoice", guaranteeing universal automatic creation
 * and joining for all participants, auto-recovery on network drops, and protection against removal.
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
    private static final long INITIAL_BACKOFF_MS = 3_000;
    private static final long MAX_BACKOFF_MS = 30_000;
    private static final Random JITTER = new Random();

    private static final AtomicReference<State> CURRENT_STATE = new AtomicReference<>(State.DISCONNECTED);
    private static final AtomicReference<String> LAST_MESSAGE = new AtomicReference<>("");
    private static final AtomicInteger RETRY_COUNT = new AtomicInteger(0);
    private static final AtomicBoolean PENDING_AUTOJOIN_NOTICE = new AtomicBoolean(false);
    private static final AtomicBoolean FORCE_NOTIFY_ON_NEXT_CONNECT = new AtomicBoolean(false);
    private static volatile long nextAttemptTime = 0;

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
        nextAttemptTime = 0;
        RETRY_COUNT.set(0);
        FORCE_NOTIFY_ON_NEXT_CONNECT.set(true);
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
            if (FORCE_NOTIFY_ON_NEXT_CONNECT.getAndSet(false)) {
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

        // Query active networks from Radmin GUI
        RadminAutomation.RadminStatus uiaStatus = RadminAutomation.query();
        if (uiaStatus.isConnectedTo(NetworkDefaults.DEFAULT_NETWORK_NAME)) {
            transition(State.CONNECTED, "Conectado à rede oficial AngatuVoice.");
            RETRY_COUNT.set(0);
            nextAttemptTime = 0;
            updateRecord("connected");
            if (FORCE_NOTIFY_ON_NEXT_CONNECT.getAndSet(false)) {
                notifyAutoJoined();
            }
            return;
        }

        long now = System.currentTimeMillis();
        if (now < nextAttemptTime) {
            return;
        }

        State previous = CURRENT_STATE.get();
        State nextState = previous == State.CONNECTED ? State.RECONNECTING : State.CONNECTING;
        transition(nextState, "Tentando conectar à rede " + NetworkDefaults.DEFAULT_NETWORK_NAME + "…");

        // Universal Join-or-Create:
        // 1. Try to join
        RadminAutomation.OperationResult joinRes = RadminAutomation.join(
                NetworkDefaults.DEFAULT_NETWORK_NAME,
                NetworkDefaults.DEFAULT_NETWORK_PASSWORD);

        if (!joinRes.ok()) {
            String joinMsg = joinRes.message().toLowerCase();
            Console.log("Tentativa de ingresso na rede padrão retornou: %s", joinRes.message());
            // If the network does not exist or was not found, attempt automatic creation
            if (joinMsg.contains("não encontrad") || joinMsg.contains("not found")
                    || joinMsg.contains("inválid") || joinMsg.contains("tempo limite")
                    || joinMsg.contains("incorret")) {
                Console.log("Rede padrão não encontrada ou inacessível. Criando rede automaticamente: %s", NetworkDefaults.DEFAULT_NETWORK_NAME);
                RadminAutomation.OperationResult createRes = RadminAutomation.create(
                        NetworkDefaults.DEFAULT_NETWORK_NAME,
                        NetworkDefaults.DEFAULT_NETWORK_PASSWORD);

                if (!createRes.ok()) {
                    String createMsg = createRes.message().toLowerCase();
                    if (createMsg.contains("já existe") || createMsg.contains("already exists")) {
                        // Concurrent creation by another peer, join immediately
                        RadminAutomation.join(
                                NetworkDefaults.DEFAULT_NETWORK_NAME,
                                NetworkDefaults.DEFAULT_NETWORK_PASSWORD);
                    }
                }
            }
        }

        // Check if joined
        RadminAutomation.RadminStatus recheck = RadminAutomation.query();
        if (recheck.isConnectedTo(NetworkDefaults.DEFAULT_NETWORK_NAME)) {
            transition(State.CONNECTED, "Conectado à rede oficial AngatuVoice.");
            RETRY_COUNT.set(0);
            nextAttemptTime = 0;
            updateRecord("connected");
            FORCE_NOTIFY_ON_NEXT_CONNECT.set(false);
            notifyAutoJoined();
        } else {
            transition(State.DISCONNECTED, "Não foi possível conectar à rede padrão no momento.");
            scheduleRetry();
            updateRecord("disconnected");
        }
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

    private static void scheduleRetry() {
        int attempt = RETRY_COUNT.incrementAndGet();
        long backoff = Math.min(INITIAL_BACKOFF_MS * (1L << Math.min(attempt - 1, 5)), MAX_BACKOFF_MS);
        long jitter = JITTER.nextInt(1500);
        nextAttemptTime = System.currentTimeMillis() + backoff + jitter;
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
