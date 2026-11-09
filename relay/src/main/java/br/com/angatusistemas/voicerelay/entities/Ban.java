package br.com.angatusistemas.voicerelay.entities;

import br.com.angatusistemas.lib.database.Saveable;
import lombok.Getter;
import lombok.Setter;

/**
 * A banned identity, and the public address it was connected from when the ban was made.
 *
 * @author Angatu Sistemas
 */
@Getter
@Setter
public final class Ban extends Saveable {

    private String id;
    private String address;
    private String reason;
    private String by;
    private long at;
}
