package br.com.angatusistemas.voicerelay.services;

/**
 * A request the server turns down, carrying the Portuguese text the app shows and the HTTP status when it came over HTTP.
 *
 * @author Angatu Sistemas
 */
public final class Refusal extends RuntimeException {

    private final int status;

    public Refusal(String message) {
        this(400, message);
    }

    public Refusal(int status, String message) {
        super(message, null, false, false);
        this.status = status;
    }

    public int status() {
        return status;
    }
}
