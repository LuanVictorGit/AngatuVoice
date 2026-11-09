package br.com.angatusistemas.voicerelay.entities;

import br.com.angatusistemas.lib.database.Saveable;
import lombok.Getter;
import lombok.Setter;

/**
 * A person who proved the administrator password, and whether their administrator mode is on.
 *
 * @author Angatu Sistemas
 */
@Getter
@Setter
public final class Admin extends Saveable {

    private String id;
    private boolean active;
    private long since;
}
