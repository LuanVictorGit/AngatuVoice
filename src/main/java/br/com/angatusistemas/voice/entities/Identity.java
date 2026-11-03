package br.com.angatusistemas.voice.entities;

import br.com.angatusistemas.lib.database.Saveable;
import lombok.Getter;
import lombok.Setter;

/**
 * The local user's signing key pair and display name; one record, generated on first run.
 *
 * @author Angatu Sistemas
 */
@Getter
@Setter
public final class Identity extends Saveable {

    private String id;
    private String publicKey;
    private String privateKey;
    private String displayName;
    private long createdAt;
}