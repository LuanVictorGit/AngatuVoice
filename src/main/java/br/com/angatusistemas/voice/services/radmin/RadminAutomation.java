package br.com.angatusistemas.voice.services.radmin;

import br.com.angatusistemas.lib.console.Console;
import br.com.angatusistemas.voice.services.RadminService;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.jna.platform.win32.Advapi32Util;
import com.sun.jna.platform.win32.Win32Exception;
import com.sun.jna.platform.win32.WinReg;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Reads the Radmin VPN state from the registry, and automates the operations only its window offers through
 * Windows UI Automation on the user's interactive desktop. Only an explicit action opens that window.
 *
 * @author Angatu Sistemas
 */
public final class RadminAutomation {

    private static final String SCRIPT_NAME = "radmin-automation.ps1";
    private static final String UI_NETWORKS_KEY = "Software\\Famatech\\Radmin VPN\\ui\\networks";
    private static final String MEMBERSHIPS_KEY = "SOFTWARE\\WOW6432Node\\Famatech\\RadminVPN\\1.0\\Networks";
    private static final int ACTION_TIMEOUT_SECONDS = 40;
    private static Path cachedScriptPath;

    private RadminAutomation() {
    }

    /* Called every few seconds: it reads the registry in-process, because a script here costs a compiler run and opens Radmin. */
    public static RadminStatus query() {
        if (System.getProperty("angatu.radmin.ip") != null) {
            String testIp = System.getProperty("angatu.radmin.ip");
            return new RadminStatus(true, true, "node", testIp, "Connected", Collections.singletonList(NetworkDefaults.DEFAULT_NETWORK_NAME), Collections.emptyList(), null);
        }
        RadminService.Status net = RadminService.current();
        List<String> known = subKeys(WinReg.HKEY_CURRENT_USER, UI_NETWORKS_KEY);
        return new RadminStatus(net.installed(), net.running(), net.alias(), net.ip(), net.connected() ? "Online" : "Offline",
                known, known, "");
    }

    /* Radmin lists each network it belongs to by id only, so this says how many there are, not which. */
    public static int membershipCount() {
        return subKeys(WinReg.HKEY_LOCAL_MACHINE, MEMBERSHIPS_KEY).size();
    }

    private static List<String> subKeys(WinReg.HKEY root, String key) {
        try {
            return Advapi32Util.registryKeyExists(root, key) ? List.of(Advapi32Util.registryGetKeys(root, key)) : List.of();
        } catch (Win32Exception e) {
            Console.debug("Radmin registry not readable: " + e.getMessage());
            return List.of();
        }
    }

    public static OperationResult join(String name, String password) {
        if (System.getProperty("angatu.radmin.ip") != null) {
            return new OperationResult(true, "Operação em ambiente de teste.");
        }
        String json = executeScript(ACTION_TIMEOUT_SECONDS, "-Action", "join", "-Name", name, "-Password", password);
        return parseResult(json, "Falha ao entrar na rede");
    }

    public static OperationResult create(String name, String password) {
        if (System.getProperty("angatu.radmin.ip") != null) {
            return new OperationResult(true, "Operação em ambiente de teste.");
        }
        String json = executeScript(ACTION_TIMEOUT_SECONDS, "-Action", "create", "-Name", name, "-Password", password);
        return parseResult(json, "Falha ao criar rede");
    }

    public static OperationResult leave(String name) {
        if (System.getProperty("angatu.radmin.ip") != null) {
            return new OperationResult(true, "Operação em ambiente de teste.");
        }
        String json = executeScript(ACTION_TIMEOUT_SECONDS, "-Action", "leave", "-Name", name);
        return parseResult(json, "Falha ao sair da rede");
    }

    public static OperationResult delete(String name) {
        if (System.getProperty("angatu.radmin.ip") != null) {
            return new OperationResult(true, "Operação em ambiente de teste.");
        }
        String json = executeScript(ACTION_TIMEOUT_SECONDS, "-Action", "delete", "-Name", name);
        return parseResult(json, "Falha ao excluir rede do Radmin");
    }

    public static void show() {
        executeScript(5, "-Action", "show");
    }

    private static OperationResult parseResult(String json, String defaultError) {
        if (json.isBlank()) {
            return new OperationResult(false, defaultError + " (tempo limite excedido)");
        }
        try {
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            boolean ok = obj.has("ok") && obj.get("ok").getAsBoolean();
            String message = obj.has("message") ? obj.get("message").getAsString() : "";
            return new OperationResult(ok, message);
        } catch (Exception e) {
            return new OperationResult(false, defaultError + ": " + e.getMessage());
        }
    }

    private static synchronized Path ensureScript() {
        if (cachedScriptPath != null && Files.exists(cachedScriptPath)) {
            return cachedScriptPath;
        }
        Path dir = Path.of(System.getProperty("java.io.tmpdir"), "angatu-radmin-scripts");
        try {
            Files.createDirectories(dir);
        } catch (IOException ignored) {}
        Path scriptFile = dir.resolve(SCRIPT_NAME);

        try (InputStream in = RadminAutomation.class.getResourceAsStream("/scripts/" + SCRIPT_NAME)) {
            if (in != null) {
                Files.copy(in, scriptFile, StandardCopyOption.REPLACE_EXISTING);
                cachedScriptPath = scriptFile;
                return cachedScriptPath;
            }
        } catch (IOException e) {
            Console.warn("Could not copy bundled radmin script: %s", e.getMessage());
        }

        // Fallback to local dev path if available
        Path devPath = Path.of("src", "main", "resources", "scripts", SCRIPT_NAME).toAbsolutePath();
        if (Files.exists(devPath)) {
            cachedScriptPath = devPath;
            return cachedScriptPath;
        }

        cachedScriptPath = scriptFile;
        return cachedScriptPath;
    }

    private static String executeScript(int timeoutSeconds, String... args) {
        Path script = ensureScript();
        if (!Files.exists(script)) {
            Console.warn("Radmin automation script not found at %s", script);
            return "";
        }

        List<String> command = new ArrayList<>();
        command.add("powershell.exe");
        command.add("-NoProfile");
        command.add("-ExecutionPolicy");
        command.add("Bypass");
        command.add("-File");
        command.add(script.toString());
        Collections.addAll(command, args);

        Process process = null;
        try {
            process = new ProcessBuilder(command).redirectErrorStream(true).start();
            // Reading first would wait on a stuck script forever and hold every later network action behind it.
            InputStream output = process.getInputStream();
            CompletableFuture<byte[]> bytes = CompletableFuture.supplyAsync(() -> {
                try {
                    return output.readAllBytes();
                } catch (IOException e) {
                    return new byte[0];
                }
            });
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return "";
            }
            return new String(bytes.get(5, TimeUnit.SECONDS), StandardCharsets.UTF_8).trim();
        } catch (Exception e) {
            Console.warn("Error running Radmin automation script: %s", e.getMessage());
            return "";
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    public record RadminStatus(boolean installed, boolean running, String alias, String ip, String status,
                               List<String> networks, List<String> knownNetworks, String error) {

        public boolean isConnectedTo(String networkName) {
            if (networkName == null) {
                return false;
            }
            String target = networkName.trim();
            if (networks != null && networks.stream().anyMatch(n -> n.equalsIgnoreCase(target))) {
                return true;
            }
            if (knownNetworks != null && knownNetworks.stream().anyMatch(n -> n.equalsIgnoreCase(target))) {
                return true;
            }
            return false;
        }
    }

    public record OperationResult(boolean ok, String message) {
    }
}
