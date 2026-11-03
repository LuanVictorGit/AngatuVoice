package br.com.angatusistemas.voice.updater;

import br.com.angatusistemas.voice.utils.Crypto;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The single place that says where releases live and which key signs them; swap the repository here only.
 *
 * @author Angatu Sistemas
 */
public final class UpdateConfig {

    private static final Properties PROPS = load();

    private UpdateConfig() {
    }

    public static String releasesUrl() {
        return PROPS.getProperty("api") + "/repos/" + PROPS.getProperty("repository") + "/releases?per_page=15";
    }

    public static byte[] publicKey() {
        return Crypto.unb64(PROPS.getProperty("publicKey"));
    }

    public static Set<String> allowedHosts() {
        return Arrays.stream(PROPS.getProperty("allowedHosts").split(",")).map(String::strip).collect(Collectors.toSet());
    }

    private static Properties load() {
        Properties p = new Properties();
        try (InputStream in = UpdateConfig.class.getResourceAsStream("/updater.properties")) {
            p.load(in);
        } catch (IOException | NullPointerException e) {
            throw new IllegalStateException("updater.properties is missing from the package", e);
        }
        return p;
    }
}
