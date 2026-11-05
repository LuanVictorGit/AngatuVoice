package br.com.angatusistemas.voice.services.radmin;

/**
 * Mandatory, permanent and immutable configuration values for the system's default network.
 *
 * @author Angatu Sistemas
 */
public final class NetworkDefaults {

    public static final String DEFAULT_NETWORK_NAME = "AngatuVoice";
    public static final String DEFAULT_NETWORK_PASSWORD = "senha123";
    public static final boolean DEFAULT_NETWORK_REQUIRED = true;
    public static final boolean DEFAULT_NETWORK_PROTECTED = true;

    private NetworkDefaults() {
    }

    public static boolean isProtected(String networkName) {
        return networkName != null && DEFAULT_NETWORK_NAME.equalsIgnoreCase(networkName.trim());
    }
}
