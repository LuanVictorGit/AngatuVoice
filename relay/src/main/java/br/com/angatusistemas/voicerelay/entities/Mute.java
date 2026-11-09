package br.com.angatusistemas.voicerelay.entities;

import br.com.angatusistemas.lib.database.Saveable;
import lombok.Getter;
import lombok.Setter;

/**
 * A person who may not send, edit or react until a given time.
 *
 * @author Angatu Sistemas
 */
@Getter
@Setter
public final class Mute extends Saveable {

    private String id;
    private long until;
    private String by;
}
