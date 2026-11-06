package br.com.angatusistemas.voice.updater;

import br.com.angatusistemas.lib.console.Console;
import br.com.angatusistemas.lib.database.Saveable;
import br.com.angatusistemas.lib.gson.GsonAPI;
import br.com.angatusistemas.lib.task.Task;
import br.com.angatusistemas.voice.desktop.AppShell;
import br.com.angatusistemas.voice.entities.Setting;
import br.com.angatusistemas.voice.entities.UpdateRecord;
import br.com.angatusistemas.voice.services.CallService;
import br.com.angatusistemas.voice.services.LiveHub;
import br.com.angatusistemas.voice.services.PeerNetwork;
import br.com.angatusistemas.voice.services.SettingsService;
import br.com.angatusistemas.voice.utils.AppDirs;
import br.com.angatusistemas.voice.utils.Version;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

/**
 * The update cycle: find a signed release, download and verify it, stage it, and hand the swap to the helper.
 *
 * @author Angatu Sistemas
 */
public final class UpdateService {

    private static final int MAX_JSON_BYTES = 2 * 1024 * 1024;
    private static final long PROGRESS_EVERY_MS = 250;
    // Nobody may stay on an old version. A check answered with 304 does not spend the GitHub quota.
    private static final long CHECK_EVERY_MS = 120_000;
    private static final long TICK_MS = 15_000;
    private static final int MAX_ROLLBACKS = 2;
    private static final String ETAG_KEY = "_updater.etag";
    private static final String BODY_KEY = "_updater.body";
    private static final String CHECKED_KEY = "_updater.lastCheck";

    private static final AtomicBoolean BUSY = new AtomicBoolean();
    private static volatile Status status = Status.idle();
    private static volatile Manifest pending;
    private static volatile Path stagedZip;
    private static volatile Map<String, String> pendingAssets = Map.of();
    private static volatile Optional<Path> patchBase = Optional.empty();
    private static volatile boolean patchRefused;

    private UpdateService() {
    }

    public static Status status() {
        return status;
    }

    public static void start(boolean launchedByUpdate) {
        if (!launchedByUpdate) {
            PackageInstaller.recoverInterrupted();
        }
        PackageInstaller.takeResult().ifPresent(UpdateService::recordResult);
        check();
        Task.runTimerWithFixedDelay(UpdateService::tick, TICK_MS, TICK_MS);
    }

    public static void confirmStartup() {
        try {
            Files.writeString(AppDirs.data().resolve("startup-ok-" + Version.current()), Instant.now().toString());
            try (Stream<Path> files = Files.list(AppDirs.data())) {
                files.filter(f -> f.getFileName().toString().startsWith("startup-ok-")
                        && !f.getFileName().toString().endsWith(Version.current().toString())).forEach(f -> f.toFile().delete());
            }
        } catch (IOException e) {
            Console.warn("Could not write the startup marker: %s", e.getMessage());
        }
        // Only now: this version has proven it opens, so nothing older can still be needed. A newer one may be waiting for the end of a call.
        Task.runAsync(() -> PackageInstaller.removePackages(AppDirs.updates(),
                folder -> PackageInstaller.isNewerThan(folder, Version.current())));
    }

    public static void check() {
        if (BUSY.compareAndSet(false, true)) {
            Task.runAsync(UpdateService::runCheck);
        }
    }

    public static void install() {
        Manifest manifest = pending;
        Path zip = stagedZip;
        if (!"ready".equals(status.state()) || manifest == null || zip == null || AppDirs.install().isEmpty()) {
            throw new IllegalStateException("no update is ready to install");
        }
        leaveCalls();
        if (BUSY.compareAndSet(false, true)) {
            Task.runAsync(() -> runInstall(manifest, zip));
        }
    }

    /* Every update is mandatory and takes effect at once, whatever the person is doing: a call is left, not waited for. */
    private static void tick() {
        try {
            if (BUSY.get()) {
                return;
            }
            if ("ready".equals(status.state()) && AppDirs.install().isPresent()) {
                install();
            } else if (System.currentTimeMillis() - lastCheck() >= CHECK_EVERY_MS) {
                check();
            }
        } catch (RuntimeException e) {
            Console.error("update tick failed", e);
        }
    }

    /* A version that keeps failing to open must not restart everyone for ever: after two rollbacks, this computer waits for the next one. */
    static boolean gaveUpOn(String version, List<UpdateRecord> history) {
        return history.stream().filter(r -> version.equals(r.getVersion()) && "rolled_back".equals(r.getOutcome())).count() >= MAX_ROLLBACKS;
    }

    private static void runCheck() {
        publish(status.with("checking", null));
        try {
            String channel = SettingsService.get("update.channel");
            Optional<Release> latest = latestRelease(channel);
            saveRaw(CHECKED_KEY, String.valueOf(System.currentTimeMillis()));
            Version current = Version.current();
            if (latest.isEmpty() || latest.get().version.compareTo(current) <= 0) {
                pending = null;
                publish(status.with("current", null));
                return;
            }
            // A copy running outside an installation has nothing to swap, and blocking it would only stop development.
            if (AppDirs.install().isEmpty()) {
                publish(status.with("current", null));
                return;
            }
            Manifest manifest = loadManifest(latest.get(), channel);
            if (gaveUpOn(manifest.version(), history())) {
                publish(status.with("failed", "A versão " + manifest.version() + " não abriu neste computador. O aplicativo aguarda a próxima."));
                return;
            }
            if (manifest.needsFullInstaller(current)) {
                publish(status.about(manifest).with("installer-required",
                        "Esta versão exige um instalador completo. Baixe-o na página do Angatu Voice."));
                return;
            }
            pending = manifest;
            pendingAssets = latest.get().assets;
            publish(status.about(manifest).with("available", null));
            download(manifest);
            Path zip = stagedZip;
            if ("ready".equals(status.state()) && zip != null) {
                leaveCalls();
                runInstall(manifest, zip);
            }
        } catch (SecurityException e) {
            Console.warn("Update refused: %s", e.getMessage());
            publish(status.with("failed", "A atualização foi recusada porque não pôde ser autenticada."));
        } catch (IOException | IllegalArgumentException | IllegalStateException e) {
            Console.warn("Update check failed: %s", e.getMessage());
            publish(status.with("failed", "Não foi possível verificar atualizações agora."));
        } finally {
            BUSY.set(false);
        }
    }

    private static Optional<Release> latestRelease(String channel) throws IOException {
        String cached = rawValue(BODY_KEY);
        String etag = rawValue(ETAG_KEY);
        Map<String, String> headers = Map.of("Accept", "application/vnd.github+json");
        HttpResponse<InputStream> response = UpdateHttp.get(UpdateConfig.releasesUrl(), token(),
                etag.isEmpty() || cached.isEmpty() ? headers : Map.of("Accept", "application/vnd.github+json",
                        "If-None-Match", etag));
        String body;
        try (InputStream in = response.body()) {
            if (response.statusCode() == 304) {
                body = cached;
            } else if (response.statusCode() == 200) {
                byte[] data = in.readNBytes(MAX_JSON_BYTES + 1);
                if (data.length > MAX_JSON_BYTES) {
                    throw new IOException("release list too large");
                }
                body = new String(data, StandardCharsets.UTF_8);
                saveRaw(BODY_KEY, body);
                saveRaw(ETAG_KEY, response.headers().firstValue("ETag").orElse(""));
            } else {
                throw new IOException("release list status " + response.statusCode());
            }
        }
        return pickRelease(body, channel);
    }

    static Optional<Release> pickRelease(String json, String channel) {
        JsonArray releases = GsonAPI.get().fromJson(json, JsonArray.class);
        Release best = null;
        for (JsonElement el : releases) {
            JsonObject r = el.getAsJsonObject();
            try {
                Version v = Version.parse(r.get("tag_name").getAsString());
                boolean draft = r.get("draft").getAsBoolean();
                boolean beta = r.get("prerelease").getAsBoolean() || v.isPreRelease();
                if (draft || (beta && !"beta".equals(channel)) || (best != null && v.compareTo(best.version) <= 0)) {
                    continue;
                }
                best = new Release(v, r.get("tag_name").getAsString(), assets(r.getAsJsonArray("assets")));
            } catch (IllegalArgumentException | NullPointerException | ClassCastException e) {
                Console.debug("release skipped: " + e.getMessage());
            }
        }
        return Optional.ofNullable(best);
    }

    private static Map<String, String> assets(JsonArray array) {
        boolean withToken = !token().isBlank();
        Map<String, String> out = new java.util.HashMap<>();
        for (JsonElement el : array) {
            JsonObject a = el.getAsJsonObject();
            out.put(a.get("name").getAsString(), a.get(withToken ? "url" : "browser_download_url").getAsString());
        }
        return out;
    }

    private static Manifest loadManifest(Release release, String channel) throws IOException {
        String manifestUrl = release.assets.get("manifest.json");
        String signatureUrl = release.assets.get("manifest.json.sig");
        if (manifestUrl == null || signatureUrl == null) {
            throw new IOException("release has no signed manifest");
        }
        Map<String, String> headers = Map.of("Accept", "application/octet-stream");
        byte[] raw = UpdateHttp.getBytes(manifestUrl, token(), headers, MAX_JSON_BYTES);
        byte[] signature = Base64.getDecoder().decode(new String(UpdateHttp.getBytes(signatureUrl, token(), headers, 1024),
                StandardCharsets.UTF_8).strip());
        Manifest manifest = Manifest.verified(raw, signature, UpdateConfig.publicKey(), UpdateConfig.allowedHosts());
        boolean channelOk = "beta".equals(channel) || "stable".equals(manifest.channel());
        if (!channelOk || !manifest.version().equals(release.version.toString())) {
            throw new IllegalArgumentException("manifest does not match its release");
        }
        return manifest;
    }

    /* The usual update changes only the application: the small package fits whenever the runtime and libraries here are the ones it was built on. */
    private static Optional<Path> installToPatch(Manifest manifest) {
        Optional<Path> install = AppDirs.install();
        if (manifest.patch() == null || patchRefused || install.isEmpty()) {
            return Optional.empty();
        }
        try {
            return manifest.patch().base().equals(PackageInstaller.baseIdentity(install.get())) ? install : Optional.empty();
        } catch (IOException e) {
            Console.warn("Could not compare the install with the patch: %s", e.getMessage());
            return Optional.empty();
        }
    }

    private static void download(Manifest manifest) throws IOException {
        // A package for a version that was passed over will never be installed.
        PackageInstaller.removePackages(AppDirs.updates(), manifest.version()::equals);
        Optional<Path> over = installToPatch(manifest);
        patchBase = over;
        boolean patch = over.isPresent();
        String name = patch ? manifest.patch().name() : manifest.pkg().name();
        String url = patch ? manifest.patch().url() : manifest.pkg().url();
        long size = patch ? manifest.patch().size() : manifest.pkg().size();
        String sha = patch ? manifest.patch().sha256() : manifest.pkg().sha256();
        Path dir = AppDirs.ensure(AppDirs.updates().resolve(manifest.version()));
        Path part = dir.resolve(name + ".part");
        Path zip = dir.resolve(name);
        if (!Files.exists(zip)) {
            streamPackage(pendingAssets.getOrDefault(name, url), size, part);
            Files.move(part, zip);
        }
        publish(status.with("validating", null));
        if (Files.size(zip) != size || !sha.equals(PackageInstaller.sha256(zip))) {
            Files.deleteIfExists(zip);
            patchRefused = patch;
            throw new SecurityException("package hash or size mismatch");
        }
        stagedZip = zip;
        publish(status.with("ready", null));
    }

    private static void streamPackage(String url, long total, Path part) throws IOException {
        long have = Files.exists(part) ? Files.size(part) : 0;
        if (have > total) {
            Files.delete(part);
            have = 0;
        }
        Map<String, String> headers = have > 0 ? Map.of("Range", "bytes=" + have + "-") : Map.of();
        HttpResponse<InputStream> response = UpdateHttp.get(url, token(), withAccept(headers));
        int code = response.statusCode();
        if (code != 200 && code != 206) {
            response.body().close();
            throw new IOException("package status " + code);
        }
        if (code == 200) {
            have = 0;
        }
        long received = have;
        long lastPublish = 0;
        try (InputStream in = response.body(); OutputStream out = Files.newOutputStream(part,
                StandardOpenOption.CREATE, have > 0 ? StandardOpenOption.APPEND : StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE)) {
            byte[] buffer = new byte[64 * 1024];
            for (int n = in.read(buffer); n >= 0; n = in.read(buffer)) {
                received += n;
                if (received > total) {
                    throw new SecurityException("package is larger than announced");
                }
                out.write(buffer, 0, n);
                long now = System.currentTimeMillis();
                if (now - lastPublish >= PROGRESS_EVERY_MS) {
                    lastPublish = now;
                    publish(status.downloading(received, total));
                }
            }
        }
        if (received != total) {
            throw new IOException("download ended early: " + received + " of " + total);
        }
    }

    private static Map<String, String> withAccept(Map<String, String> headers) {
        Map<String, String> all = new java.util.HashMap<>(headers);
        all.put("Accept", "application/octet-stream");
        return all;
    }

    private static void runInstall(Manifest manifest, Path zip) {
        try {
            publish(status.with("installing", null));
            Path staging = PackageInstaller.stage(zip, manifest, patchBase);
            publish(status.with("restarting", null));
            PackageInstaller.launch(staging, manifest, Version.current().toString(), ProcessHandle.current().pid());
            Thread.sleep(800);
            PeerNetwork.stop();
            AppShell.exit();
        } catch (IOException | RuntimeException e) {
            Console.error("update install failed", e);
            patchRefused = patchBase.isPresent();
            record(manifest.version(), "failed", e.getMessage());
            publish(status.with("failed", "A instalação da atualização falhou. A versão atual continua funcionando."));
            BUSY.set(false);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void recordResult(JsonObject result) {
        String outcome = result.get("status").getAsString();
        String version = result.get("version").getAsString();
        String detail = result.has("detail") ? result.get("detail").getAsString() : "";
        record(version, outcome, detail);
        if ("ok".equals(outcome)) {
            publish(status.with("updated", null));
        } else {
            publish(status.with("rolledback", "A atualização para a versão " + version
                    + " não pôde ser concluída e a versão anterior foi restaurada."));
        }
    }

    private static void record(String version, String outcome, String detail) {
        UpdateRecord entry = new UpdateRecord();
        entry.setId(UUID.randomUUID().toString());
        entry.setVersion(version);
        entry.setOutcome(outcome);
        entry.setDetail(detail == null ? "" : detail);
        entry.setAt(System.currentTimeMillis());
        entry.save();
    }

    public static List<UpdateRecord> history() {
        return Saveable.query(UpdateRecord.class, "SELECT data FROM " + Saveable.tableName(UpdateRecord.class)
                + " ORDER BY json_extract(data,'$.at') DESC LIMIT 20");
    }

    private static void leaveCalls() {
        String me = PeerNetwork.me() == null ? "" : PeerNetwork.me().id();
        CallService.dropPeer(me);
    }

    private static String token() {
        String env = System.getenv("ANGATU_UPDATE_TOKEN");
        return env != null && !env.isBlank() ? env : SettingsService.get("update.token");
    }

    private static long lastCheck() {
        String raw = rawValue(CHECKED_KEY);
        return raw.isEmpty() ? 0 : Long.parseLong(raw);
    }

    private static String rawValue(String key) {
        Setting stored = Saveable.findById(Setting.class, key);
        return stored == null || stored.getValue() == null ? "" : stored.getValue();
    }

    private static void saveRaw(String key, String value) {
        Setting s = new Setting();
        s.setId(key);
        s.setValue(value);
        s.save();
    }

    private static void publish(Status next) {
        status = next;
        LiveHub.emit("update", next);
    }

    record Release(Version version, String tag, Map<String, String> assets) {
    }

    public record Status(String state, String current, String available, String channel, double progress, long received,
                         long size, String notes, boolean mandatory, long lastCheck, String message, boolean canInstall) {

        static Status idle() {
            return new Status("idle", Version.current().toString(), "", "stable", 0, 0, 0, "", false, 0, "", false);
        }

        Status with(String state, String message) {
            return new Status(state, Version.current().toString(), available, SettingsService.get("update.channel"),
                    "ready".equals(state) ? 1 : progress, received, size, notes, mandatory,
                    UpdateService.lastCheck(), message == null ? "" : message,
                    "ready".equals(state) && AppDirs.install().isPresent());
        }

        Status about(Manifest m) {
            return new Status(state, current, m.version(), channel, 0, 0, m.pkg().size(), m.notes() == null ? "" : m.notes(),
                    m.mandatory(), lastCheck, message, false);
        }

        Status downloading(long received, long total) {
            return new Status("downloading", current, available, channel, total == 0 ? 0 : (double) received / total,
                    received, total, notes, mandatory, lastCheck, "", false);
        }
    }
}
