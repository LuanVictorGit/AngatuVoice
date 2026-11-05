package br.com.angatusistemas.voice.services.radmin;

import br.com.angatusistemas.lib.console.Console;
import br.com.angatusistemas.lib.gson.GsonAPI;
import br.com.angatusistemas.voice.utils.AppDirs;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Automates Radmin VPN operations using Windows UI Automation on the user's interactive desktop.
 *
 * @author Angatu Sistemas
 */
public final class RadminAutomation {

    private static final String SCRIPT_NAME = "radmin-automation.ps1";
    private static Path cachedScriptPath;

    private RadminAutomation() {
    }

    public static RadminStatus query() {
        String json = executeScript(10, "-Action", "list");
        if (json.isBlank()) {
            return new RadminStatus(false, false, "", "", "Offline", Collections.emptyList(), Collections.emptyList(), "Script execution failed");
        }
        try {
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            boolean installed = obj.has("installed") && obj.get("installed").getAsBoolean();
            boolean running = obj.has("running") && obj.get("running").getAsBoolean();
            String alias = obj.has("alias") ? obj.get("alias").getAsString() : "";
            String ip = obj.has("ip") ? obj.get("ip").getAsString() : "";
            String status = obj.has("status") ? obj.get("status").getAsString() : "Offline";
            String error = obj.has("error") ? obj.get("error").getAsString() : "";

            List<String> networks = new ArrayList<>();
            if (obj.has("networks") && obj.get("networks").isJsonArray()) {
                obj.getAsJsonArray("networks").forEach(e -> networks.add(e.getAsString()));
            }

            List<String> known = new ArrayList<>();
            if (obj.has("knownNetworks") && obj.get("knownNetworks").isJsonArray()) {
                obj.getAsJsonArray("knownNetworks").forEach(e -> known.add(e.getAsString()));
            }

            return new RadminStatus(installed, running, alias, ip, status, networks, known, error);
        } catch (Exception e) {
            Console.warn("Failed to parse Radmin automation query JSON: %s", e.getMessage());
            return new RadminStatus(false, false, "", "", "Error", Collections.emptyList(), Collections.emptyList(), e.getMessage());
        }
    }

    public static OperationResult join(String name, String password) {
        String json = executeScript(15, "-Action", "join", "-Name", name, "-Password", password);
        return parseResult(json, "Falha ao entrar na rede");
    }

    public static OperationResult create(String name, String password) {
        String json = executeScript(15, "-Action", "create", "-Name", name, "-Password", password);
        return parseResult(json, "Falha ao criar rede");
    }

    public static OperationResult leave(String name) {
        String json = executeScript(12, "-Action", "leave", "-Name", name);
        return parseResult(json, "Falha ao sair da rede");
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
        Path dir = AppDirs.data().resolve("scripts");
        AppDirs.ensure(dir);
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
            byte[] bytes = process.getInputStream().readAllBytes();
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return "";
            }
            return new String(bytes, StandardCharsets.UTF_8).trim();
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
            if (networkName == null || networks == null) {
                return false;
            }
            return networks.stream().anyMatch(n -> n.equalsIgnoreCase(networkName.trim()));
        }
    }

    public record OperationResult(boolean ok, String message) {
    }
}
