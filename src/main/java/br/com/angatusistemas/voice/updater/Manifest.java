package br.com.angatusistemas.voice.updater;

import br.com.angatusistemas.lib.gson.GsonAPI;
import br.com.angatusistemas.voice.utils.Crypto;
import br.com.angatusistemas.voice.utils.Version;
import com.google.gson.JsonParseException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The signed description of a release. Nothing in it is trusted until the signature and every field have been checked.
 *
 * @author Angatu Sistemas
 */
public record Manifest(String app, String version, String channel, String minVersion, String os, String arch,
                       boolean mandatory, String notes, String publishedAt, Pkg pkg, Patch patch) {

    public static final long MAX_PACKAGE_BYTES = 700L * 1024 * 1024;
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

    public record Pkg(String name, String url, long size, String sha256) {
    }

    /* Only the launcher and the application jar. It applies over an install whose runtime and libraries hash to {@code base}. */
    public record Patch(String name, String url, long size, String sha256, String base) {
    }

    public static Manifest verified(byte[] raw, byte[] signature, byte[] publicKey, Set<String> allowedHosts) {
        if (!Crypto.verify(publicKey, raw, signature)) {
            throw new SecurityException("manifest signature is not valid");
        }
        Manifest m;
        try {
            m = GsonAPI.get().fromJson(new String(raw, StandardCharsets.UTF_8), Manifest.class);
        } catch (JsonParseException e) {
            throw new IllegalArgumentException("manifest is not valid JSON", e);
        }
        m.validate(allowedHosts);
        return m;
    }

    public void validate(Set<String> allowedHosts) {
        boolean ok = "angatu-voice".equals(app) && "windows".equals(os) && "x64".equals(arch)
                && ("stable".equals(channel) || "beta".equals(channel)) && pkg != null && pkg.sha256 != null
                && SHA256.matcher(pkg.sha256).matches() && pkg.size > 0 && pkg.size <= MAX_PACKAGE_BYTES
                && pkg.name != null && pkg.name.matches("[A-Za-z0-9._-]{1,120}\\.zip");
        Version.parse(version);
        Version.parse(minVersion);
        URI uri = pkg == null || pkg.url == null ? null : URI.create(pkg.url);
        ok = ok && uri != null && "https".equals(uri.getScheme()) && uri.getHost() != null
                && allowedHosts.contains(uri.getHost().toLowerCase());
        if (!ok) {
            throw new IllegalArgumentException("manifest failed validation");
        }
        if (patch != null) {
            URI patchUri = patch.url == null ? null : URI.create(patch.url);
            boolean patchOk = patch.sha256 != null && SHA256.matcher(patch.sha256).matches() && patch.base != null
                    && SHA256.matcher(patch.base).matches() && patch.size > 0 && patch.size <= MAX_PACKAGE_BYTES
                    && patch.name != null && patch.name.matches("[A-Za-z0-9._-]{1,120}\\.zip") && patchUri != null
                    && "https".equals(patchUri.getScheme()) && patchUri.getHost() != null
                    && allowedHosts.contains(patchUri.getHost().toLowerCase());
            if (!patchOk) {
                throw new IllegalArgumentException("manifest patch failed validation");
            }
        }
    }

    public Version parsedVersion() {
        return Version.parse(version);
    }

    public boolean needsFullInstaller(Version installed) {
        return Version.parse(minVersion).compareTo(installed) > 0;
    }
}
