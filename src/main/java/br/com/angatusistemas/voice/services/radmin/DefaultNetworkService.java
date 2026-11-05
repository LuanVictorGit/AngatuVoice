package br.com.angatusistemas.voice.services.radmin;

import br.com.angatusistemas.lib.console.Console;
import br.com.angatusistemas.lib.database.Saveable;
import br.com.angatusistemas.voice.entities.RadminNetwork;
import br.com.angatusistemas.voice.services.LiveHub;
import br.com.angatusistemas.voice.services.RadminService;
import br.com.angatusistemas.voice.services.SettingsService;
import java.time.Instant;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Manages the mandatory default network "AngatuVoice", guaranteeing automatic connection,
 * controlled bootstrap, auto-recovery on network drops, and protection against removal.
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

    public static boolean isBootstrapMaster() {
        return Boolean.getBoolean("angatu.bootstrap.master") || SettingsService.flag("radmin.bootstrap.master");
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

    public static synchronized void reconnect() {
        nextAttemptTime = 0;
        RETRY_COUNT.set(0);
        tick();
    }

    public static synchronized void tick() {
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
            return;
        }

        long now = System.currentTimeMillis();
        if (now < nextAttemptTime) {
            return;
        }

        State previous = CURRENT_STATE.get();
        State nextState = previous == State.CONNECTED ? State.RECONNECTING : State.CONNECTING;
        transition(nextState, "Tentando conectar à rede " + NetworkDefaults.DEFAULT_NETWORK_NAME + "…");

        boolean master = isBootstrapMaster();
        if (master) {
            Console.log("Máquina autorizada para bootstrap: criando ou ingressando na rede %s", NetworkDefaults.DEFAULT_NETWORK_NAME);
            RadminAutomation.OperationResult createRes = RadminAutomation.create(
                    NetworkDefaults.DEFAULT_NETWORK_NAME,
                    NetworkDefaults.DEFAULT_NETWORK_PASSWORD);

            if (!createRes.ok()) {
                // If it already exists, join it
                RadminAutomation.join(NetworkDefaults.DEFAULT_NETWORK_NAME, NetworkDefaults.DEFAULT_NETWORK_PASSWORD);
            }
        } else {
            RadminAutomation.OperationResult joinRes = RadminAutomation.join(
                    NetworkDefaults.DEFAULT_NETWORK_NAME,
                    NetworkDefaults.DEFAULT_NETWORK_PASSWORD);

            if (!joinRes.ok()) {
                String msg = joinRes.message().toLowerCase();
                if (msg.contains("não encontrad") || msg.contains("not found")) {
                    transition(State.AWAITING_BOOTSTRAP, "Rede AngatuVoice ainda não inicializada pela máquina administradora.");
                    scheduleRetry();
                    updateRecord("awaiting_bootstrap");
                    return;
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
