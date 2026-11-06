package br.com.angatusistemas.voicerelay.entities;

import br.com.angatusistemas.lib.database.Saveable;
import lombok.Getter;
import lombok.Setter;

/**
 * A chat message kept until everyone it is for has had the chance to receive it, and then purged.
 *
 * @author Angatu Sistemas
 */
@Getter
@Setter
public final class StoredMessage extends Saveable {

    private String id;
    private long seq;
    private String chat;
    private String recipient;
    private String sender;
    private long ts;
    private String frame;
}
