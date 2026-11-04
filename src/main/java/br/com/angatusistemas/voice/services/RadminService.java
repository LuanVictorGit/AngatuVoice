package br.com.angatusistemas.voice.services;

import br.com.angatusistemas.voice.utils.Processes;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Detects Radmin VPN from what the product really exposes: its install folder, its control service, the
 * registry alias of the signed-in user and the virtual Ethernet adapter that carries the private network.
 *
 * @author Angatu Sistemas
 */
public final class RadminService {

    private static final String SERVICE = "RvControlSvc";
    private static final String REGISTRY_KEY = "HKLM\\SOFTWARE\\WOW6432Node\\Famatech\\RadminVPN\\1.0";
    private static final Pattern ALIAS = Pattern.compile("(?m)^\\s*Alias\\s+REG_SZ\\s+(.+?)\\s*$");
    private static final int MISSES_TO_DISCONNECT = 2;
    private static final AtomicReference<Status> CURRENT = new AtomicReference<>(Status.EMPTY);
    private static volatile int misses;

    private RadminService() {
    }

    public static Status current() {
        return CURRENT.get();
    }

    public static Status refresh() {
        Status next = probe();
        Status previous = CURRENT.get();
        // A single failed look at the adapter is usually a blink; only a repeated one is reported as a lost network.
        if (!next.connected() && previous.connected() && ++misses < MISSES_TO_DISCONNECT) {
            return previous;
        }
        misses = 0;
        CURRENT.set(next);
        return next;
    }

    private static Status probe() {
        String overrideIp = System.getProperty("angatu.radmin.ip");
        if (overrideIp != null && !overrideIp.isBlank()) {
            return new Status(true, true, true, System.getProperty("angatu.radmin.alias", "dev"), "dev-loopback",
                    overrideIp, "", 8);
        }
        Adapter adapter = findAdapter();
        boolean found = adapter.ip != null;
        boolean installed = found || isInstalled();
        boolean running = found || (installed && Processes.run(5, "sc", "query", SERVICE).contains("RUNNING"));
        return new Status(installed, running, found, readAlias(installed), adapter.name,
                adapter.ip == null ? "" : adapter.ip, adapter.broadcast, adapter.prefix);
    }

    private static boolean isInstalled() {
        for (String root : new String[] {System.getenv("ProgramFiles(x86)"), System.getenv("ProgramFiles")}) {
            if (root != null && Files.exists(Path.of(root, "Radmin VPN", "RvRvpnGui.exe"))) {
                return true;
            }
        }
        return false;
    }

    private static String readAlias(boolean installed) {
        String known = CURRENT.get().alias();
        if (!installed || !known.isBlank()) {
            return known;
        }
        Matcher m = ALIAS.matcher(Processes.run(5, "reg", "query", REGISTRY_KEY, "/v", "Alias"));
        return m.find() ? m.group(1) : "";
    }

    private static Adapter findAdapter() {
        try {
            for (NetworkInterface nic : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!nic.isUp() || !nic.getDisplayName().toLowerCase().contains("radmin vpn")) {
                    continue;
                }
                for (InterfaceAddress address : nic.getInterfaceAddresses()) {
                    InetAddress ip = address.getAddress();
                    if (ip instanceof Inet4Address) {
                        InetAddress broadcast = address.getBroadcast();
                        return new Adapter(nic.getDisplayName(), ip.getHostAddress(),
                                broadcast == null ? "" : broadcast.getHostAddress(), address.getNetworkPrefixLength());
                    }
                }
            }
        } catch (SocketException e) {
            return Adapter.NONE;
        }
        return Adapter.NONE;
    }

    public record Status(boolean installed, boolean running, boolean connected, String alias, String adapter, String ip,
                         String broadcast, int prefix) {

        static final Status EMPTY = new Status(false, false, false, "", "", "", "", 0);

        public List<String> issues() {
            if (!installed) {
                return List.of("O Radmin VPN não está instalado.");
            }
            if (!running) {
                return List.of("O Radmin VPN está instalado, mas não está em execução.");
            }
            return connected ? List.of() : List.of("O Radmin VPN está aberto, mas sem conexão com uma rede.");
        }
    }

    private record Adapter(String name, String ip, String broadcast, int prefix) {

        static final Adapter NONE = new Adapter("", null, "", 0);
    }
}
